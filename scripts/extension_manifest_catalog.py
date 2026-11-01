#!/usr/bin/env python3
"""Collect pinned Raycast manifest declarations. Never execute or certify commands."""
from concurrent.futures import ThreadPoolExecutor, as_completed
from contextlib import contextmanager
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import HTTPRedirectHandler, Request, build_opener
import argparse
import fcntl
import hashlib
import json
import os
import re
import sys
import time

MAX_MANIFEST_BYTES = 512 * 1024
MAX_INVENTORY_BYTES = 4 * 1024 * 1024
RETRYABLE_HTTP = {408, 429, 500, 502, 503, 504}
HEX40 = re.compile(r"[0-9a-f]{40}\Z")
HEX64 = re.compile(r"[0-9a-f]{64}\Z")
SCOPE = "Static manifest declarations from a pinned open-source tree; not the live Store or runtime compatibility."


class CatalogError(ValueError):
    pass


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def git_hash(kind, data):
    return hashlib.sha1(kind.encode() + b" " + str(len(data)).encode() + b"\0" + data).hexdigest()


def unique_object(pairs):
    result = {}
    for name, value in pairs:
        if name in result:
            raise CatalogError("Duplicate JSON object key")
        result[name] = value
    return result


def decode(data):
    try:
        return json.loads(data.decode("utf-8"), object_pairs_hook=unique_object)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise CatalogError("Invalid UTF-8 JSON") from error


def read_bounded(path, maximum):
    with path.open("rb") as stream:
        data = stream.read(maximum + 1)
    if len(data) > maximum:
        raise CatalogError("Input exceeds size limit")
    return data


def require(condition, message):
    if not condition:
        raise CatalogError(message)


def load_inventory(data):
    value = decode(data)
    require(isinstance(value, dict), "Inventory must be an object")
    require(value.get("repository") == "raycast/extensions", "Unexpected repository")
    require(value.get("truncated") is False, "Inventory must explicitly be non-truncated")
    for field in ("commit", "extensionsTree"):
        require(isinstance(value.get(field), str) and HEX40.fullmatch(value[field]), "Invalid pinned Git identity")
    entries = value.get("entries")
    require(isinstance(entries, list) and 0 < len(entries) <= 10000, "Missing or excessive directory inventory")
    trees = {}
    for entry in entries:
        require(isinstance(entry, dict), "Invalid directory entry")
        name, tree = entry.get("name"), entry.get("treeSha")
        require(isinstance(name, str) and 0 < len(name.encode()) < 256 and name not in (".", "..")
                and not any(char in name for char in ("/", "\\", "\0", "\r", "\n")), "Unsafe directory name")
        require(name not in trees, "Duplicate directory name")
        require(isinstance(tree, str) and HEX40.fullmatch(tree), "Invalid directory tree identity")
        trees[name] = tree
    # Reconstruct the direct Git tree, including Git's directory sort rule.
    # Dropping entries from a non-truncated response cannot silently shrink coverage.
    body = b"".join(b"40000 " + name.encode() + b"\0" + bytes.fromhex(trees[name])
                    for name in sorted(trees, key=lambda name: (name + "/").encode()))
    require(git_hash("tree", body) == value["extensionsTree"], "Directory inventory does not match pinned Git tree")
    return value, trees


def load_anchors(data, commit, names):
    if data is None:
        return {}
    value = decode(data)
    require(isinstance(value, dict) and value.get("sourceCommit") == commit, "Blob anchors use another source commit")
    entries = value.get("entries")
    require(isinstance(entries, list), "Invalid blob anchor list")
    anchors = {}
    for entry in entries:
        require(isinstance(entry, dict), "Invalid blob anchor")
        name = entry.get("name")
        require(isinstance(name, str) and name in names and name not in anchors, "Unexpected or duplicate blob anchor")
        blob, digest = entry.get("manifestBlob"), entry.get("manifestSha256")
        require(isinstance(blob, str) and HEX40.fullmatch(blob) and isinstance(digest, str)
                and HEX64.fullmatch(digest), "Invalid blob anchor identity")
        anchors[name] = (blob, digest)
    return anchors


def text(value):
    require(value is None or isinstance(value, str) and len(value) <= 4096, "Invalid manifest text field")
    return value


def platforms(value):
    require(value is None or isinstance(value, list) and len(value) <= 32
            and all(isinstance(item, str) and len(item) <= 128 for item in value), "Invalid declared platforms")
    return value


def requirements(values):
    if values is None:
        return []
    require(isinstance(values, list) and len(values) <= 2000, "Invalid manifest requirements")
    result = []
    for item in values:
        require(isinstance(item, dict), "Invalid manifest requirement")
        required = item.get("required")
        require(required is None or isinstance(required, bool), "Invalid required flag")
        # Defaults and preference values are deliberately excluded from the export.
        result.append({"name": text(item.get("name")), "type": text(item.get("type")),
                       "required": required, "has_default": "default" in item})
    return result


def normalize(name, data):
    require(len(data) <= MAX_MANIFEST_BYTES, "Manifest exceeds 512 KiB bound")
    value = decode(data)
    require(isinstance(value, dict), "Manifest root must be an object")
    commands = value.get("commands", [])
    require(isinstance(commands, list) and len(commands) <= 2000, "Invalid command declarations")
    output = []
    for index, item in enumerate(commands):
        require(isinstance(item, dict), "Command declaration must be an object")
        arguments = item.get("arguments")
        output.append({"declaration_index": index, "name": text(item.get("name")),
                       "title": text(item.get("title")), "mode": text(item.get("mode")),
                       "declared_platforms": platforms(item.get("platforms")),
                       "arguments": requirements(arguments), "preferences": requirements(item.get("preferences")),
                       "verification": "untested"})
    dependencies = {}
    for group in ("dependencies", "devDependencies"):
        values = value.get(group, {})
        require(isinstance(values, dict), "Invalid dependency declarations")
        dependencies[group] = {text(key): spec if isinstance(spec, str)
                               and re.fullmatch(r"[\w\s.^~<>=*|+\-]{1,128}", spec)
                               else "[non-semver specifier; inspect pinned source]"
                               for key, spec in sorted(values.items())}
    return {"name": name, "title": text(value.get("title")), "package_name": text(value.get("name")),
            "declared_platforms": platforms(value.get("platforms")), "commands_declared": "commands" in value,
            "commands": output, "extension_preferences": requirements(value.get("preferences")),
            "dependencies": dependencies, "verification": "untested"}


class PinnedRedirects(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        # Even a same-host redirect could replace the immutable commit path.
        raise HTTPError(request.full_url, code, "Pinned manifest redirects are refused", headers, fp)


def fetch(url):
    request = Request(url, headers={"User-Agent": "Droidcast-manifest-catalog/1", "Accept": "application/json"})
    with build_opener(PinnedRedirects()).open(request, timeout=20) as response:
        return response.read(MAX_MANIFEST_BYTES + 1)


def source_record(name, tree, commit):
    return {"name": name, "source_commit": commit, "directory_tree": tree,
            "source_url": f"https://raw.githubusercontent.com/raycast/extensions/{commit}/extensions/{quote(name, safe='')}/package.json",
            "verification": "untested"}


def inspected(record, data, anchor):
    digest, blob = sha256(data), git_hash("blob", data)
    if anchor is not None:
        require((blob, digest) == anchor, "Pinned manifest blob/hash mismatch")
    return dict(record, **normalize(record["name"], data), manifest_sha256=digest,
                git_blob_sha1=blob, independently_anchored=anchor is not None, manifest_inspection="read")


def retrieve(record, anchor=None, fetcher=fetch, sleep=time.sleep):
    result = dict(record, manifest_inspection="retrieval-error")
    for attempt in (1, 2):
        result["http_attempts"] = attempt
        try:
            data = fetcher(record["source_url"])
            return inspected(result, data, anchor), data
        except HTTPError as error:
            error.close()
            if error.code in RETRYABLE_HTTP and attempt == 1:
                sleep(1)
                continue
            result.update(http_status=error.code, failure="HTTP retrieval failed")
            break
        except (URLError, TimeoutError, OSError):
            if attempt == 1:
                sleep(1)
                continue
            result["failure"] = "Transport failure after bounded retry"
        except CatalogError as error:
            result.update(manifest_inspection="invalid-manifest", failure=str(error))
            break
    return result, None


def write_bytes(path, data):
    temporary = path.with_suffix(path.suffix + ".tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(data)
    os.replace(temporary, path)


def write_json(path, value):
    write_bytes(path, (json.dumps(value, indent=2, ensure_ascii=True, sort_keys=True) + "\n").encode())


@contextmanager
def locked_output(output):
    output.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (output / ".collector.lock").open("a+") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise CatalogError("Another collector owns this output directory") from error
        yield


@contextmanager
def run_receipt(output):
    run = {"pid": os.getpid(), "completed": False, "phase": "cache-verification"}
    write_json(output / "run.json", run)
    try:
        yield run
    except BaseException as error:
        write_json(output / "run.json", dict(run, completed=False, failure_type=type(error).__name__))
        raise


def cache_key(name):
    return sha256(name.encode()) + ".json"


def cached(output, record, anchor):
    path = output / "records" / cache_key(record["name"])
    if not path.exists():
        return None
    receipt = decode(read_bounded(path, MAX_INVENTORY_BYTES))
    require(isinstance(receipt, dict) and all(receipt.get(key) == value for key, value in record.items()
            if key != "verification"), "Cache source identity mismatch")
    if receipt.get("manifest_inspection") != "read":
        require(receipt.get("manifest_inspection") in ("retrieval-error", "invalid-manifest"), "Invalid cached inspection state")
        # Only retain bounded, known failure fields; never trust cached compatibility or commands.
        result = dict(record, manifest_inspection=receipt["manifest_inspection"])
        for field in ("failure", "http_status", "http_attempts"):
            if field in receipt:
                result[field] = receipt[field] if field != "failure" else text(receipt[field])
        return result
    data = read_bounded(output / "raw" / cache_key(record["name"]), MAX_MANIFEST_BYTES)
    require(sha256(data) == receipt.get("manifest_sha256") and git_hash("blob", data) == receipt.get("git_blob_sha1"),
            "Immutable manifest cache mismatch")
    # Re-normalize raw bytes: tampered cache metadata cannot invent commands or promote status.
    try:
        result = inspected(record, data, anchor)
    except CatalogError as error:
        # Older discovery receipts may have accepted ambiguous manifests. Keep
        # these entries visible as invalid instead of importing their commands.
        return dict(record, manifest_inspection="invalid-manifest", failure=str(error),
                    manifest_sha256=sha256(data), git_blob_sha1=git_hash("blob", data))
    if "http_attempts" in receipt:
        result["http_attempts"] = receipt["http_attempts"]
    return result


def summary(names, records):
    read = [item for item in records.values() if item["manifest_inspection"] == "read"]
    commands = sum(len(item["commands"]) for item in read)
    return {"directory_count": len(names), "manifests_read": len(read),
            "retrieval_or_manifest_errors": len(records) - len(read), "not_yet_inspected": len(names) - len(records),
            "declared_commands": commands, "independently_anchored_manifests": sum(item["independently_anchored"] for item in read),
            "all_directories_attempted": len(names) == len(records), "manifest_discovery_complete": len(read) == len(names),
            "runtime_tested_commands": 0, "runtime_untested_commands": commands}


def collect(inventory_data, anchors_data, output, limit=100, workers=4, retry_errors=False, fetcher=fetch):
    require(type(limit) is int and 0 <= limit <= 10000 and type(workers) is int and 1 <= workers <= 4, "Invalid batch bounds")
    inventory, trees = load_inventory(inventory_data)
    anchors = load_anchors(anchors_data, inventory["commit"], trees)
    with locked_output(output), run_receipt(output) as run:
        snapshot = output / "source-inventory.json"
        if snapshot.exists():
            require(read_bounded(snapshot, MAX_INVENTORY_BYTES) == inventory_data, "Use another output directory for another inventory")
        else:
            write_bytes(snapshot, inventory_data)
        for name in ("raw", "records"):
            (output / name).mkdir(exist_ok=True, mode=0o700)
        identities = {name: source_record(name, trees[name], inventory["commit"]) for name in trees}
        records = {}
        for name, identity in identities.items():
            value = cached(output, identity, anchors.get(name))
            if value is not None:
                records[name] = value
        priority = sorted(anchors) + sorted(set(trees) - set(anchors))
        pending = [name for name in priority if name not in records
                   or retry_errors and records[name]["manifest_inspection"] != "read"][:limit]
        run.update(requested_batch=len(pending), phase="retrieval")
        write_json(output / "run.json", run)
        with ThreadPoolExecutor(max_workers=workers) as executor:
            jobs = {executor.submit(retrieve, identities[name], anchors.get(name), fetcher): name for name in pending}
            for future in as_completed(jobs):
                item, raw = future.result()
                name = item["name"]
                if raw is not None:
                    write_bytes(output / "raw" / cache_key(name), raw)
                write_json(output / "records" / cache_key(name), item)
                records[name] = item
                write_json(output / "progress.json", summary(trees, records))
        catalog = {"schema_version": 1, "repository": inventory["repository"], "source_commit": inventory["commit"],
                   "extensions_tree": inventory["extensionsTree"], "source_inventory_sha256": sha256(inventory_data),
                   "directory_tree_verified": True, "inventory_truncated": False, "scope": SCOPE,
                   "summary": summary(trees, records),
                   "extensions": [records.get(name, dict(identities[name], manifest_inspection="pending")) for name in sorted(trees)]}
        write_json(output / "catalog.json", catalog)
        write_json(output / "run.json", dict(run, completed=True, phase="completed", summary=catalog["summary"]))
        return catalog


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", required=True, type=Path)
    parser.add_argument("--blob-anchors", type=Path, help="Optional independently retrieved Git blob/SHA-256 anchors")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--limit", type=int, default=100, help="Maximum new retrievals; zero verifies cached bytes without fetching")
    parser.add_argument("--workers", type=int, default=4, choices=range(1, 5))
    parser.add_argument("--retry-errors", action="store_true")
    args = parser.parse_args(argv)
    try:
        catalog = collect(read_bounded(args.inventory, MAX_INVENTORY_BYTES),
                          read_bounded(args.blob_anchors, MAX_INVENTORY_BYTES) if args.blob_anchors else None,
                          args.output, args.limit, args.workers, args.retry_errors)
    except (CatalogError, OSError) as error:
        print(f"Catalog failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(catalog["summary"], sort_keys=True))
    return 0 if catalog["summary"]["manifest_discovery_complete"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
