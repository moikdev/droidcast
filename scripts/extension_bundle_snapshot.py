#!/usr/bin/env python3
"""Freeze a bounded batch of public Store bundles without executing commands."""
from pathlib import Path, PurePosixPath
from io import BytesIO
from urllib.parse import urlsplit
from urllib.request import Request, build_opener
from urllib.error import HTTPError, URLError
from http.client import HTTPException
import argparse
import hashlib
import json
import os
import re
import stat
import time
import zipfile
import zlib

from extension_manifest_catalog import (CatalogError, PinnedRedirects, decode, normalize, require,
    sha256, read_bounded, write_bytes, write_json, locked_output, run_receipt)

MAX_PLAN_BYTES = 128 * 1024
MAX_METADATA_BYTES = 1024 * 1024
MAX_ARCHIVE_BYTES = 32 * 1024 * 1024
MAX_FILE_BYTES = 16 * 1024 * 1024
MAX_EXPANDED_BYTES = 96 * 1024 * 1024
MAX_MANIFEST_BYTES = 512 * 1024
SAFE_NAME = re.compile(r"[A-Za-z0-9_-]{1,128}\Z")
HEX40 = re.compile(r"[0-9a-f]{40}\Z")
HEX64 = re.compile(r"[0-9a-f]{64}\Z")
SCOPE = "Frozen public Store bundles for a named execution batch; snapshotting is not Android compatibility evidence."
RETRYABLE_HTTP = {408, 429, 500, 502, 503, 504}


def load_plan(data):
    require(len(data) <= MAX_PLAN_BYTES, "Execution plan exceeds size bound")
    plan = decode(data)
    require(isinstance(plan, dict) and type(plan.get("schema_version")) is int
            and plan["schema_version"] == 1, "Unsupported execution-plan schema")
    require(isinstance(plan.get("batch_id"), str) and SAFE_NAME.fullmatch(plan["batch_id"]), "Invalid batch identity")
    source = plan.get("planning_source")
    if source is not None:
        require(isinstance(source, dict) and source.get("repository") == "raycast/extensions"
                and isinstance(source.get("commit"), str) and HEX40.fullmatch(source["commit"])
                and isinstance(source.get("catalog_sha256"), str) and HEX64.fullmatch(source["catalog_sha256"]),
                "Invalid planning-source identity")
    entries = plan.get("extensions")
    require(isinstance(entries, list) and 0 < len(entries) <= 100, "Missing or excessive batch targets")
    directories, identities = set(), set()
    for item in entries:
        require(isinstance(item, dict), "Invalid batch target")
        for field in ("directory", "store_name", "handle"):
            require(isinstance(item.get(field), str) and SAFE_NAME.fullmatch(item[field]), "Invalid target identity")
        require(item["directory"] not in directories, "Duplicate source directory")
        identity = (item["handle"].casefold(), item["store_name"])
        require(identity not in identities, "Duplicate Store lookup identity")
        directories.add(item["directory"]); identities.add(identity)
        if "planning_manifest_sha256" in item:
            require(isinstance(item["planning_manifest_sha256"], str)
                    and HEX64.fullmatch(item["planning_manifest_sha256"]), "Invalid planning-manifest identity")
        commands = item.get("commands")
        require(isinstance(commands, list) and 0 < len(commands) <= 100
                and all(isinstance(name, str) and SAFE_NAME.fullmatch(name) for name in commands)
                and len(commands) == len(set(commands)), "Invalid planned command names")
    return plan


def download_url(value):
    require(isinstance(value, str) and len(value) <= 16384
            and not any(char.isspace() or char in ("\0", "\\") for char in value), "Invalid download address")
    try:
        parsed = urlsplit(value)
        valid = parsed.scheme == "https" and parsed.hostname == "extensions.raycast-releases.com" \
            and parsed.port in (None, 443) and parsed.username is None and parsed.password is None \
            and not parsed.fragment and parsed.path.startswith("/")
    except ValueError:
        valid = False
    require(valid, "Unexpected Store download address")
    return value


def inspect_metadata(item, data):
    require(len(data) <= MAX_METADATA_BYTES, "Store metadata exceeds size bound")
    value = decode(data)
    require(isinstance(value, dict), "Invalid Store metadata")
    require(value.get("name") == item["store_name"], "Store package name changed")
    author = value.get("author")
    require(isinstance(author, dict) and isinstance(author.get("handle"), str)
            and author["handle"].casefold() == item["handle"].casefold(), "Store author identity changed")
    require(isinstance(value.get("id"), str) and SAFE_NAME.fullmatch(value["id"]), "Unsafe Store identity")
    require(isinstance(value.get("commit_sha"), str) and HEX40.fullmatch(value["commit_sha"]), "Missing Store-reported commit")
    url = download_url(value.get("download_url"))
    # Signed verify queries stay only in the private raw-metadata cache. The
    # exported snapshot never includes a download URL or query-token value.
    return {"store_id": value["id"], "store_name": value["name"], "store_handle": author["handle"],
            "store_reported_commit_sha": value["commit_sha"], "metadata_sha256": sha256(data)}, url


def inspect_archive(item, data):
    require(len(data) <= MAX_ARCHIVE_BYTES, "Store archive exceeds 32 MiB bound")
    try:
        with zipfile.ZipFile(BytesIO(data)) as archive:
            entries = archive.infolist()
            require(0 < len(entries) <= 4000, "Missing or excessive archive members")
            files, paths, declared_total = {}, set(), 0
            for entry in entries:
                name = entry.orig_filename
                path = PurePosixPath(name)
                require(name and not any(ord(char) < 32 or ord(char) == 127 or char == "\\" for char in name)
                        and not path.is_absolute() and ".." not in path.parts
                        and str(path) == name.rstrip("/") and not re.match(r"^[A-Za-z]:", name), "Unsafe archive member path")
                require(str(path) not in paths, "Duplicate archive member path")
                paths.add(str(path))
                mode = entry.external_attr >> 16
                require(stat.S_IFMT(mode) in (0, stat.S_IFREG, stat.S_IFDIR), "Archive contains a special file")
                require(stat.S_IFMT(mode) != stat.S_IFDIR or entry.is_dir(), "Ambiguous archive directory")
                require(not entry.flag_bits & 1, "Encrypted archive member")
                require(0 <= entry.file_size <= MAX_FILE_BYTES, "Archive member exceeds expanded bound")
                declared_total += entry.file_size
                require(declared_total <= MAX_EXPANDED_BYTES, "Archive exceeds total expanded bound")
                if not entry.is_dir(): files[str(path)] = entry
            manifests = [path for path in files if PurePosixPath(path).name == "package.json"
                         and len(PurePosixPath(path).parts) <= 3]
            require(len(manifests) == 1, "Archive must contain one shallow package manifest")
            manifest_path = manifests[0]
            require(files[manifest_path].file_size <= MAX_MANIFEST_BYTES, "Manifest exceeds size bound")
            inventory, manifest_data, total = [], None, 0
            for name in sorted(files):
                digest, size, chunks = hashlib.sha256(), 0, []
                with archive.open(files[name]) as stream:
                    while True:
                        chunk = stream.read(65536)
                        if not chunk: break
                        size += len(chunk); total += len(chunk)
                        require(size <= MAX_FILE_BYTES and total <= MAX_EXPANDED_BYTES, "Actual expanded bytes exceed bound")
                        digest.update(chunk)
                        if name == manifest_path: chunks.append(chunk)
                require(size == files[name].file_size, "Archive size declaration mismatch")
                inventory.append({"path": name, "sha256": digest.hexdigest(), "bytes": size})
                if name == manifest_path: manifest_data = b"".join(chunks)
            manifest = normalize(item["directory"], manifest_data)
            require(manifest["package_name"] == item["store_name"], "Archive package differs from Store identity")
            prefix = PurePosixPath(manifest_path).parent
            hashes = {entry["path"]: entry for entry in inventory}
            names = []
            for command in manifest["commands"]:
                name = command["name"]
                require(isinstance(name, str) and SAFE_NAME.fullmatch(name), "Unsafe built command name")
                require(name not in names, "Duplicate command name in bundle")
                names.append(name)
                built = str(prefix / (name + ".js"))
                require(built in hashes, "Declared command has no prebuilt JavaScript file")
                command["file_sha256"] = hashes[built]["sha256"]
                command["file_bytes"] = hashes[built]["bytes"]
            require(names, "Bundle declares no commands")
            missing = sorted(set(item["commands"]) - set(names))
            return {**manifest, "archive_sha256": sha256(data), "archive_bytes": len(data),
                    "manifest_sha256": sha256(manifest_data), "manifest_path": manifest_path,
                    "expanded_bytes": total, "files": len(files),
                    "file_inventory_sha256": sha256(json.dumps(inventory, sort_keys=True, separators=(",", ":")).encode()),
                    "planned_commands_missing": missing, "source_association": "Planning directory only; Store bundle is not certified as the planning-source commit."}
    except (zipfile.BadZipFile, RuntimeError, UnicodeError, EOFError, OSError, NotImplementedError, zlib.error) as error:
        raise CatalogError("Invalid or unreadable Store archive") from error


def fetch(url, maximum):
    # Serial requests are paced as well as bounded by target/byte limits.
    now = time.monotonic()
    remaining = .25 - (now - getattr(fetch, "last_request", 0))
    if remaining > 0: time.sleep(remaining)
    fetch.last_request = time.monotonic()
    request = Request(url, headers={"User-Agent": "DroidcastBundleSnapshot/0.1", "Accept-Encoding": "identity"})
    with build_opener(PinnedRedirects()).open(request, timeout=20) as response:
        data = response.read(maximum + 1)
    require(len(data) <= maximum, "Response exceeds byte bound")
    return data


def retrieve(url, maximum, fetcher):
    for attempt in (1, 2):
        try:
            data = fetcher(url, maximum)
            require(isinstance(data, bytes) and len(data) <= maximum, "Response exceeds byte bound")
            return data, None
        except HTTPError as error:
            failure = {"kind": "HTTPError", "http_status": error.code, "attempts": attempt}
            error.close()
            if error.code not in RETRYABLE_HTTP: break
        except (URLError, HTTPException, TimeoutError, OSError):
            failure = {"kind": "TransportError", "attempts": attempt}
        except CatalogError:
            # Invalid/oversized responses are visible per-target failures; do
            # not echo the exception or retry downloading more oversized data.
            return None, {"kind": "ResponseRejected", "attempts": attempt}
    return None, failure


def cache_key(item):
    return sha256(item["directory"].encode())


def private_read(path, maximum):
    require(not path.is_symlink() and path.is_file(), "Unsafe or missing cache file")
    return read_bounded(path, maximum)


def base_record(item):
    return {"directory": item["directory"], "store_name": item["store_name"], "handle": item["handle"],
            "planning_manifest_sha256": item.get("planning_manifest_sha256"),
            "planned_commands": item["commands"], "state": "pending", "verification": "untested"}


def cached_error(value, phase):
    if value is None:
        return None
    require(isinstance(value, dict) and value.get("phase") in ("metadata", "archive"), "Invalid cached error phase")
    # Reconstruct known fields so cached messages/URLs cannot enter an export.
    kinds = {"HTTPError", "TransportError", "ResponseRejected"}
    require(value.get("kind") in kinds and type(value.get("attempts")) is int
            and 1 <= value["attempts"] <= 2, "Invalid cached retrieval error")
    error = {"kind": value["kind"], "attempts": value["attempts"], "phase": value["phase"]}
    if error["kind"] == "HTTPError":
        require(type(value.get("http_status")) is int and 100 <= value["http_status"] <= 599,
                "Invalid cached HTTP status")
        error["http_status"] = value["http_status"]
    return error if error["phase"] == phase else None


def cached(output, item):
    key = cache_key(item)
    record_path = output / "records" / (key + ".json")
    prior = decode(private_read(record_path, 4 * 1024 * 1024)) if record_path.exists() else {}
    require(isinstance(prior, dict), "Invalid bundle cache record")
    if prior:
        require(all(prior.get(k) == item[k] for k in ("directory", "store_name", "handle")), "Cache uses another target identity")
    result = base_record(item)
    metadata_path = output / "metadata" / (key + ".json")
    archive_path = output / "archives" / (key + ".zip")
    for field, path in (("metadata_sha256", metadata_path), ("archive_sha256", archive_path)):
        if prior.get(field):
            require(path.exists(), "Frozen cache bytes are missing")
            maximum = MAX_METADATA_BYTES if field == "metadata_sha256" else MAX_ARCHIVE_BYTES
            require(sha256(private_read(path, maximum)) == prior[field], "Frozen cache digest mismatch")
    if not metadata_path.exists():
        require(not archive_path.exists(), "Archive has no pinned metadata")
        error = cached_error(prior.get("last_error"), "metadata")
        if error:
            result.update(state="retrieval-error", last_error=error)
        return result
    metadata = private_read(metadata_path, MAX_METADATA_BYTES)
    result["metadata_sha256"] = sha256(metadata)
    try:
        public, _ = inspect_metadata(item, metadata)
    except CatalogError as error:
        return dict(result, state="inspection-error", phase="metadata", reason=str(error))
    result.update(public)
    if not archive_path.exists():
        error = cached_error(prior.get("last_error"), "archive")
        if error:
            result.update(state="retrieval-error", last_error=error)
        return result
    archive = private_read(archive_path, MAX_ARCHIVE_BYTES)
    result["archive_sha256"] = sha256(archive)
    try:
        inspected = inspect_archive(item, archive)
    except CatalogError as error:
        return dict(result, state="inspection-error", phase="archive", reason=str(error))
    # Rebuild declarations from observed bytes, never cached status fields.
    result.update(inspected)
    result["directory"] = item["directory"]
    result["state"] = "frozen"
    result["archive_cache_path"] = "archives/" + key + ".zip"
    return result


def collect(plan_data, output, limit=3, retry_errors=False, fetcher=fetch):
    plan = load_plan(plan_data)
    require(isinstance(limit, int) and not isinstance(limit, bool) and 0 <= limit <= 1000, "Invalid target limit")
    output = Path(output)
    require(not output.is_symlink(), "Snapshot output cannot be a symlink")
    with locked_output(output):
        pinned = output / "bundle-plan.json"
        if pinned.exists():
            require(private_read(pinned, MAX_PLAN_BYTES) == plan_data, "Use another output directory for another batch plan")
        else:
            require(all(p.name == ".collector.lock" for p in output.iterdir()), "Snapshot output contains unrelated files")
            output.chmod(0o700)
            write_bytes(pinned, plan_data)
        for name in ("metadata", "archives", "records"):
            path = output / name
            require(not path.is_symlink(), "Snapshot cache directory cannot be a symlink")
            path.mkdir(mode=0o700, exist_ok=True)
        with run_receipt(output) as run:
            records = {item["directory"]: cached(output, item) for item in plan["extensions"]}
            run.update(phase="bundle-retrieval", plan_sha256=sha256(plan_data), requested_limit=limit)
            write_json(output / "run.json", run)
            attempts = 0
            for item in plan["extensions"]:
                name, key = item["directory"], cache_key(item)
                value = records[name]
                if value["state"] in ("frozen", "inspection-error"): continue
                if value["state"] == "retrieval-error" and not retry_errors: continue
                if attempts >= limit: break
                attempts += 1
                metadata_path = output / "metadata" / (key + ".json")
                archive_path = output / "archives" / (key + ".zip")
                phase = "metadata"
                if not metadata_path.exists():
                    from urllib.parse import quote
                    url = "https://www.raycast.com/api/v1/extensions/" + quote(item["handle"], safe="") + "/" + quote(item["store_name"], safe="")
                    url += "?_=" + str(time.time_ns() // 1000000)
                    data, error = retrieve(url, MAX_METADATA_BYTES, fetcher)
                    if error:
                        value = dict(base_record(item), state="retrieval-error", last_error=dict(error, phase=phase))
                        records[name] = value; write_json(output / "records" / (key + ".json"), value)
                        continue
                    write_bytes(metadata_path, data)
                # Persist the first received metadata before requesting an
                # archive. Resume never silently asks for a newer Store build.
                value = cached(output, item)
                records[name] = value; write_json(output / "records" / (key + ".json"), value)
                if value["state"] == "inspection-error": continue
                _, url = inspect_metadata(item, private_read(metadata_path, MAX_METADATA_BYTES))
                data, error = retrieve(url, MAX_ARCHIVE_BYTES, fetcher)
                if error:
                    value = dict(value, state="retrieval-error", last_error=dict(error, phase="archive"))
                else:
                    write_bytes(archive_path, data)
                    value = cached(output, item)
                records[name] = value; write_json(output / "records" / (key + ".json"), value)
            entries = [records[item["directory"]] for item in plan["extensions"]]
            counts = {state: sum(item["state"] == state for item in entries)
                      for state in ("pending", "retrieval-error", "inspection-error", "frozen")}
            counts["targets"] = len(entries)
            counts["plan_mismatches"] = sum(bool(item.get("planned_commands_missing")) for item in entries)
            counts["runtime_tested_commands"] = 0
            counts["ready_for_execution"] = counts["frozen"] == len(entries) and not counts["plan_mismatches"]
            snapshot = {"schema_version": 1, "scope": SCOPE, "batch_id": plan["batch_id"],
                        "plan_sha256": sha256(plan_data), "summary": counts, "extensions": entries}
            if plan.get("planning_source") is not None:
                snapshot["planning_source"] = {key: plan["planning_source"][key]
                                               for key in ("repository", "commit", "catalog_sha256")}
            write_json(output / "snapshot.json", snapshot)
            run.update(completed=True, phase="completed", attempted_targets=attempts,
                       snapshot_sha256=sha256((output / "snapshot.json").read_bytes()))
            write_json(output / "run.json", run)
            return snapshot


def execution_fixture(snapshot_path):
    """Read-only verification/projection for an isolated Android execution run.

    Raw Store metadata, signed addresses and arbitrary cached fields never go
    to the device. Every command declaration/hash is rebuilt from ZIP bytes.
    """
    snapshot_path = Path(snapshot_path)
    root = snapshot_path.parent
    require(not root.is_symlink() and not (root / "archives").is_symlink(), "Unsafe execution snapshot directory")
    raw = private_read(snapshot_path, 4 * 1024 * 1024)
    snapshot = decode(raw)
    require(isinstance(snapshot, dict) and type(snapshot.get("schema_version")) is int
            and snapshot["schema_version"] == 1 and snapshot.get("scope") == SCOPE, "Invalid bundle snapshot schema")
    entries = snapshot.get("extensions")
    require(isinstance(entries, list) and all(isinstance(item, dict) for item in entries), "Invalid snapshot targets")
    plan = {"schema_version": 1, "batch_id": snapshot.get("batch_id"),
            "planning_source": snapshot.get("planning_source"), "extensions": []}
    for entry in entries:
        plan["extensions"].append({"directory": entry.get("directory"), "store_name": entry.get("store_name"),
                                   "handle": entry.get("handle"), "commands": entry.get("planned_commands")})
    load_plan(json.dumps(plan).encode())
    fixture = {"schema_version": 1, "batch_id": plan["batch_id"], "source_snapshot_sha256": sha256(raw),
               "scope": "Isolated execution of hash-verified prebuilt bundles; Store UI installation is not tested here.",
               "extensions": []}
    paths, total = {}, 0
    for item, entry in zip(plan["extensions"], entries):
        require(entry.get("state") == "frozen" and entry.get("verification") == "untested", "Snapshot target is not an untested frozen bundle")
        require(isinstance(entry.get("store_id"), str) and SAFE_NAME.fullmatch(entry["store_id"]), "Invalid frozen Store identity")
        require(isinstance(entry.get("store_handle"), str) and entry["store_handle"].casefold() == item["handle"].casefold(), "Frozen Store handle changed")
        require(isinstance(entry.get("store_reported_commit_sha"), str)
                and HEX40.fullmatch(entry["store_reported_commit_sha"]), "Invalid reported Store commit")
        archive_name = cache_key(item) + ".zip"
        require(entry.get("archive_cache_path") == "archives/" + archive_name, "Unexpected frozen archive path")
        archive_path = root / "archives" / archive_name
        data = private_read(archive_path, MAX_ARCHIVE_BYTES)
        total += len(data)
        require(total <= 256 * 1024 * 1024, "Execution batch exceeds 256 MiB archive bound")
        inspected = inspect_archive(item, data)
        require(not inspected["planned_commands_missing"], "Planned commands missing from frozen bundle")
        for key in ("archive_sha256", "manifest_sha256", "manifest_path", "file_inventory_sha256", "commands"):
            require(entry.get(key) == inspected[key], "Frozen execution declaration or digest mismatch")
        fixture["extensions"].append({"directory": item["directory"], "store_name": item["store_name"],
            "store_id": entry["store_id"], "store_handle": entry["store_handle"],
            "store_reported_commit_sha": entry["store_reported_commit_sha"],
            "archive_name": archive_name, "archive_sha256": inspected["archive_sha256"],
            "manifest_sha256": inspected["manifest_sha256"], "manifest_path": inspected["manifest_path"],
            "planned_commands": item["commands"], "commands": [
                {key: command[key] for key in ("name", "mode", "file_sha256")} for command in inspected["commands"]]})
        paths[archive_name] = archive_path
    return fixture, paths


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--limit", type=int, default=3)
    parser.add_argument("--retry-errors", action="store_true")
    args = parser.parse_args(argv)
    try:
        snapshot = collect(read_bounded(args.plan, MAX_PLAN_BYTES), args.output, args.limit, args.retry_errors)
    except (CatalogError, OSError) as error:
        # Do not echo URLs from network exceptions or signed metadata.
        print("Snapshot failed: " + (str(error) if isinstance(error, CatalogError) else type(error).__name__))
        return 1
    print(json.dumps(snapshot["summary"], sort_keys=True))
    return 0 if snapshot["summary"]["ready_for_execution"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
