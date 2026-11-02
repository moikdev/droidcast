import json
from http.client import IncompleteRead
from io import BytesIO
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import extension_bundle_snapshot as bundles


SECRET = "private-signed-query-test-value"
DOWNLOAD = "https://extensions.raycast-releases.com/opaque-build?verify=" + SECRET


def encoded(value):
    return json.dumps(value).encode()


def target(name="alpha"):
    return {"directory": name, "store_name": name, "handle": "MixedAuthor", "commands": ["copy"]}


def plan(*targets):
    return encoded({"schema_version": 1, "batch_id": "first-batch", "extensions": list(targets) or [target()]})


def metadata(name="alpha"):
    return encoded({"name": name, "id": name + "-store-id", "author": {"handle": "mixedauthor"},
                    "commit_sha": "c" * 40, "download_url": DOWNLOAD})


def manifest(name="alpha"):
    return encoded({"name": name, "commands": [{"name": "copy", "title": "Copy", "mode": "no-view"}],
                    "preferences": [{"name": "credential", "type": "password", "default": SECRET}],
                    "dependencies": {"private": "https://example.invalid/?token=" + SECRET}})


def archive(files=None):
    stream = BytesIO()
    with zipfile.ZipFile(stream, "w", zipfile.ZIP_DEFLATED) as output:
        for name, data in (files or [("package.json", manifest()), ("copy.js", b"export default () => 42")]):
            output.writestr(name, data)
    return stream.getvalue()


def network(url, maximum):
    return metadata() if url.startswith("https://www.raycast.com/") else archive()


class BundleSnapshotTests(unittest.TestCase):
    def test_snapshot_redacts_signed_addresses_and_defaults_and_never_certifies_runtime(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            value = bundles.collect(plan(), output, fetcher=network)
            self.assertTrue(value["summary"]["ready_for_execution"])
            self.assertEqual(0, value["summary"]["runtime_tested_commands"])
            item = value["extensions"][0]
            self.assertEqual("untested", item["verification"])
            self.assertEqual("untested", item["commands"][0]["verification"])
            self.assertEqual(bundles.sha256(b"export default () => 42"), item["commands"][0]["file_sha256"])
            self.assertNotIn(SECRET, (output / "snapshot.json").read_text())
            self.assertNotIn("download_url", item)
            raw = output / "metadata" / (bundles.cache_key(target()) + ".json")
            self.assertIn(SECRET, raw.read_text())
            self.assertEqual(0o600, stat.S_IMODE(raw.stat().st_mode))
            self.assertEqual(0o700, stat.S_IMODE(output.stat().st_mode))

    def test_resume_preserves_lookup_handle_and_rebuilds_status_from_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            initial = bundles.collect(plan(), output, fetcher=network)
            key = bundles.cache_key(target())
            record = output / "records" / (key + ".json")
            value = json.loads(record.read_text())
            value.update(verification="working", commands=[{"name": "invented", "verification": "working"}])
            record.write_text(json.dumps(value))
            no_network = Mock(side_effect=AssertionError("Resume must use pinned bytes"))
            resumed = bundles.collect(plan(), output, limit=0, fetcher=no_network)
            self.assertEqual(initial, resumed)
            self.assertEqual("MixedAuthor", resumed["extensions"][0]["handle"])
            self.assertEqual("mixedauthor", resumed["extensions"][0]["store_handle"])
            no_network.assert_not_called()

    def test_tampered_archive_or_missing_pinned_bytes_fail_before_network(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            bundles.collect(plan(), output, fetcher=network)
            path = output / "archives" / (bundles.cache_key(target()) + ".zip")
            original = path.read_bytes()
            path.write_bytes(original + b"changed")
            fetch = Mock()
            with self.assertRaisesRegex(bundles.CatalogError, "digest mismatch"):
                bundles.collect(plan(), output, fetcher=fetch)
            self.assertFalse(json.loads((output / "run.json").read_text())["completed"])
            path.unlink()
            with self.assertRaisesRegex(bundles.CatalogError, "bytes are missing"):
                bundles.collect(plan(), output, fetcher=fetch)
            fetch.assert_not_called()

    def test_interrupt_after_metadata_does_not_refresh_the_build_on_resume(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            not_found = HTTPError("https://example.invalid/private", 404, "missing", {}, None)
            first = bundles.collect(plan(), output, fetcher=Mock(side_effect=not_found))
            self.assertEqual(1, first["summary"]["retrieval-error"])
            fetch = Mock(side_effect=[metadata(), KeyboardInterrupt()])
            with self.assertRaises(KeyboardInterrupt):
                bundles.collect(plan(), output, retry_errors=True, fetcher=fetch)
            self.assertFalse(json.loads((output / "run.json").read_text())["completed"])
            resume = Mock(return_value=archive())
            result = bundles.collect(plan(), output, fetcher=resume)
            self.assertTrue(result["summary"]["ready_for_execution"])
            self.assertEqual([(DOWNLOAD, bundles.MAX_ARCHIVE_BYTES)], [call.args for call in resume.call_args_list])

    def test_archive_retrieval_errors_keep_metadata_pinned_and_are_not_automatic_retries(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            fetch = Mock(side_effect=[metadata(), HTTPError(DOWNLOAD, 403, "expired", {}, None)])
            failed = bundles.collect(plan(), output, fetcher=fetch)
            self.assertEqual(2, fetch.call_count)
            self.assertEqual("archive", failed["extensions"][0]["last_error"]["phase"])
            no_network = Mock()
            bundles.collect(plan(), output, fetcher=no_network)
            no_network.assert_not_called()
            resumed = Mock(return_value=archive())
            value = bundles.collect(plan(), output, retry_errors=True, fetcher=resumed)
            self.assertTrue(value["summary"]["ready_for_execution"])
            self.assertEqual(DOWNLOAD, resumed.call_args.args[0])

    def test_cached_errors_cannot_publish_extra_fields_or_status_claims(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            bundles.collect(plan(), output, fetcher=Mock(side_effect=HTTPError(DOWNLOAD, 404, "secret", {}, None)))
            path = output / "records" / (bundles.cache_key(target()) + ".json")
            value = json.loads(path.read_text())
            value.update(verification="working")
            value["last_error"]["download_url"] = DOWNLOAD
            path.write_text(json.dumps(value))
            result = bundles.collect(plan(), output, limit=0, fetcher=Mock())
            self.assertEqual("untested", result["extensions"][0]["verification"])
            self.assertNotIn(SECRET, (output / "snapshot.json").read_text())

    def test_partial_batches_keep_pending_targets_visible(self):
        with tempfile.TemporaryDirectory() as directory:
            fetch = Mock(side_effect=[metadata(), archive()])
            value = bundles.collect(plan(target(), target("beta")), Path(directory), limit=1, fetcher=fetch)
            self.assertEqual(1, value["summary"]["frozen"])
            self.assertEqual(1, value["summary"]["pending"])
            self.assertFalse(value["summary"]["ready_for_execution"])
            self.assertEqual(2, fetch.call_count)

    def test_missing_planned_command_is_a_plan_mismatch_not_a_runtime_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            item = dict(target(), commands=["missing"])
            result = bundles.collect(plan(item), Path(directory), fetcher=network)
            self.assertEqual("frozen", result["extensions"][0]["state"])
            self.assertEqual(["missing"], result["extensions"][0]["planned_commands_missing"])
            self.assertFalse(result["summary"]["ready_for_execution"])
            self.assertEqual(0, result["summary"]["runtime_tested_commands"])

    def test_oversized_response_is_visible_without_retrying_or_aborting_other_targets(self):
        with tempfile.TemporaryDirectory() as directory:
            fetch = Mock(side_effect=[metadata(), b"x" * 65])
            with patch.object(bundles, "MAX_ARCHIVE_BYTES", 64):
                result = bundles.collect(plan(), Path(directory), fetcher=fetch)
            self.assertEqual(2, fetch.call_count)
            self.assertEqual("ResponseRejected", result["extensions"][0]["last_error"]["kind"])

    def test_retry_is_bounded_and_transport_error_never_echoes_partial_body(self):
        fetch = Mock(side_effect=IncompleteRead(SECRET.encode()))
        data, error = bundles.retrieve(DOWNLOAD, 100, fetch)
        self.assertIsNone(data)
        self.assertEqual(2, fetch.call_count)
        self.assertEqual({"kind": "TransportError", "attempts": 2}, error)

    def test_invalid_identity_duplicate_keys_and_foreign_output_are_rejected(self):
        for data in [plan(target(), target()), plan(dict(target(), directory="../alpha")),
                     b'{"schema_version":1,"schema_version":1}',
                     encoded({"schema_version": True, "batch_id": "x", "extensions": [target()]})]:
            with self.subTest(data=data), self.assertRaises(bundles.CatalogError):
                bundles.load_plan(data)
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            (output / "unrelated").write_text("keep")
            with self.assertRaisesRegex(bundles.CatalogError, "unrelated"):
                bundles.collect(plan(), output, fetcher=Mock())

    def test_another_plan_cannot_reuse_frozen_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            bundles.collect(plan(), output, fetcher=network)
            with self.assertRaisesRegex(bundles.CatalogError, "another batch plan"):
                bundles.collect(plan(dict(target(), commands=["other"])), output, fetcher=Mock())

    def test_symlinked_raw_cache_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            bundles.collect(plan(), output, fetcher=network)
            path = output / "metadata" / (bundles.cache_key(target()) + ".json")
            data = path.read_bytes()
            path.unlink()
            source = output / "outside.json"
            source.write_bytes(data)
            path.symlink_to(source)
            with self.assertRaisesRegex(bundles.CatalogError, "Unsafe"):
                bundles.collect(plan(), output, fetcher=Mock())

    def test_store_identity_and_download_host_are_verified(self):
        for field, value in [("name", "other"), ("author", {"handle": "other"}), ("commit_sha", "latest"),
                             ("download_url", "http://extensions.raycast-releases.com/file"),
                             ("download_url", "https://extensions.raycast-releases.com.evil.invalid/file"),
                             ("download_url", "https://user@extensions.raycast-releases.com/file"),
                             ("download_url", DOWNLOAD + "#fragment")]:
            item = json.loads(metadata()); item[field] = value
            with self.subTest(field=field, value=value), self.assertRaises(bundles.CatalogError):
                bundles.inspect_metadata(target(), encoded(item))

    def test_archives_reject_traversal_special_files_and_missing_built_commands(self):
        link = zipfile.ZipInfo("linked.js"); link.external_attr = (stat.S_IFLNK | 0o777) << 16
        for files in [[("../escape", b"bad")], [(link, b"target")],
                      [("package.json", manifest())],
                      [("package.json", manifest()), ("copy.js", b"a"), ("nested/package.json", manifest())],
                      [("package.json", manifest("other")), ("copy.js", b"a")]]:
            with self.subTest(files=str(files)[:80]), self.assertRaises(bundles.CatalogError):
                bundles.inspect_archive(target(), archive(files))

    def test_invalid_archive_is_an_inspection_error_with_retained_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            result = bundles.collect(plan(), Path(directory), fetcher=Mock(side_effect=[metadata(), b"not a zip"]))
            self.assertEqual(1, result["summary"]["inspection-error"])
            self.assertEqual(bundles.sha256(b"not a zip"), result["extensions"][0]["archive_sha256"])
            self.assertFalse(result["summary"]["ready_for_execution"])

    def test_execution_projection_verifies_bytes_and_drops_arbitrary_signed_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            bundles.collect(plan(), output, fetcher=network)
            path = output / "snapshot.json"
            snapshot = json.loads(path.read_text())
            snapshot["extensions"][0]["download_url"] = DOWNLOAD
            path.write_text(json.dumps(snapshot))
            fixture, archives = bundles.execution_fixture(path)
            self.assertNotIn(SECRET, json.dumps(fixture))
            self.assertEqual(1, len(archives))
            self.assertEqual(["copy"], fixture["extensions"][0]["planned_commands"])
            self.assertEqual(bundles.sha256(path.read_bytes()), fixture["source_snapshot_sha256"])
            self.assertIn("installation is not tested", fixture["scope"])

    def test_execution_projection_rejects_invented_command_hashes_and_escaping_archive_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            bundles.collect(plan(), output, fetcher=network)
            path = output / "snapshot.json"
            original = json.loads(path.read_text())
            for kind in ["hash", "path", "status"]:
                value = json.loads(json.dumps(original))
                if kind == "hash": value["extensions"][0]["commands"][0]["file_sha256"] = "0" * 64
                if kind == "path": value["extensions"][0]["archive_cache_path"] = "../outside.zip"
                if kind == "status": value["extensions"][0]["verification"] = "working"
                path.write_text(json.dumps(value))
                with self.subTest(kind=kind), self.assertRaises(bundles.CatalogError):
                    bundles.execution_fixture(path)


if __name__ == "__main__":
    unittest.main()
