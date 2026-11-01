import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock
from urllib.error import HTTPError

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import extension_manifest_catalog as catalog


COMMIT = "a" * 40
MANIFEST = b'{"name":"alpha","commands":[{"name":"copy","title":"Copy","mode":"no-view"}]}'


def inventory(names=("alpha", "beta")):
    entries = [{"name": name, "treeSha": "b" * 40} for name in names]
    body = b"".join(b"40000 " + item["name"].encode() + b"\0" + bytes.fromhex(item["treeSha"])
                    for item in sorted(entries, key=lambda item: (item["name"] + "/").encode()))
    tree = hashlib.sha1(b"tree " + str(len(body)).encode() + b"\0" + body).hexdigest()
    return {"repository": "raycast/extensions", "commit": COMMIT,
            "extensionsTree": tree, "truncated": False, "entries": entries}


def encoded(value):
    return json.dumps(value).encode()


def record():
    return catalog.source_record("alpha", "b" * 40, COMMIT)


class ManifestCatalogTests(unittest.TestCase):
    def test_a_live_collector_lock_prevents_a_second_writer(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            with catalog.locked_output(output):
                with self.assertRaisesRegex(catalog.CatalogError, "Another collector"):
                    catalog.collect(encoded(inventory()), None, output, limit=0)
                self.assertFalse((output / "run.json").exists())

    def test_truncated_empty_or_missing_tree_inventory_cannot_claim_zero_extension_success(self):
        for bad in (dict(inventory(), truncated=True), dict(inventory(), entries=[]),
                    dict(inventory(), entries=inventory()["entries"][:1])):
            with self.subTest(bad=bad), self.assertRaises(catalog.CatalogError):
                catalog.load_inventory(encoded(bad))

    def test_duplicate_unsafe_or_unpinned_inventory_is_rejected(self):
        bad_values = [inventory(("alpha", "alpha")), inventory(("../alpha",)),
                      dict(inventory(), commit="main"), dict(inventory(), extensionsTree="b" * 40)]
        for bad in bad_values:
            with self.subTest(bad=bad), self.assertRaises(catalog.CatalogError):
                catalog.load_inventory(encoded(bad))
        with self.assertRaises(catalog.CatalogError):
            catalog.load_inventory(b'{"truncated":false,"truncated":true}')

    def test_partial_batch_keeps_pending_entries_and_every_command_untested(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            fetch = Mock(return_value=MANIFEST)
            value = catalog.collect(encoded(inventory()), None, output, limit=1, fetcher=fetch)
            self.assertEqual(1, fetch.call_count)
            self.assertEqual(1, value["summary"]["manifests_read"])
            self.assertEqual(1, value["summary"]["not_yet_inspected"])
            self.assertFalse(value["summary"]["manifest_discovery_complete"])
            self.assertEqual(["read", "pending"], [item["manifest_inspection"] for item in value["extensions"]])
            self.assertEqual("untested", value["extensions"][0]["commands"][0]["verification"])
            self.assertEqual(0, value["summary"]["runtime_tested_commands"])

    def test_resume_reconstructs_raw_metadata_and_export_without_network(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            initial = catalog.collect(encoded(inventory()), None, output, limit=2, fetcher=lambda _: MANIFEST)
            original_bytes = (output / "catalog.json").read_bytes()
            path = output / "records" / catalog.cache_key("alpha")
            poisoned = json.loads(path.read_text())
            poisoned.update(verification="working", commands=[{"name": "invented", "verification": "working"}])
            path.write_text(json.dumps(poisoned))
            no_network = Mock(side_effect=AssertionError("Resume must not fetch"))
            resumed = catalog.collect(encoded(inventory()), None, output, limit=0, fetcher=no_network)
            self.assertEqual(initial, resumed)
            self.assertEqual(original_bytes, (output / "catalog.json").read_bytes())
            no_network.assert_not_called()

    def test_changed_raw_cache_or_source_identity_fails_before_network(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            catalog.collect(encoded(inventory()), None, output, limit=1, fetcher=lambda _: MANIFEST)
            raw = output / "raw" / catalog.cache_key("alpha")
            raw.write_bytes(MANIFEST + b" ")
            with self.assertRaisesRegex(catalog.CatalogError, "cache mismatch"):
                catalog.collect(encoded(inventory()), None, output, fetcher=Mock())
            self.assertFalse(json.loads((output / "run.json").read_text())["completed"])
            raw.write_bytes(MANIFEST)
            path = output / "records" / catalog.cache_key("alpha")
            value = json.loads(path.read_text())
            value["source_commit"] = "c" * 40
            path.write_text(json.dumps(value))
            with self.assertRaisesRegex(catalog.CatalogError, "source identity"):
                catalog.collect(encoded(inventory()), None, output, fetcher=Mock())

    def test_a_changed_inventory_cannot_reuse_an_output_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            catalog.collect(encoded(inventory()), None, output, limit=0)
            with self.assertRaisesRegex(catalog.CatalogError, "another output"):
                catalog.collect(encoded(inventory(("gamma",))), None, output, limit=0)

    def test_legacy_receipt_cannot_promote_an_ambiguous_manifest_to_read(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            source = encoded(inventory(("alpha",)))
            catalog.collect(source, None, output, limit=0)
            raw = b'{"commands":[],"commands":[{"name":"hidden"}]}'
            receipt = dict(record(), manifest_inspection="read", manifest_sha256=catalog.sha256(raw),
                           git_blob_sha1=catalog.git_hash("blob", raw), commands=[{"name": "invented"}])
            (output / "raw" / catalog.cache_key("alpha")).write_bytes(raw)
            (output / "records" / catalog.cache_key("alpha")).write_bytes(encoded(receipt))
            value = catalog.collect(source, None, output, limit=0, fetcher=Mock(side_effect=AssertionError()))
            self.assertEqual(1, value["summary"]["retrieval_or_manifest_errors"])
            self.assertEqual(0, value["summary"]["declared_commands"])
            self.assertFalse(value["summary"]["manifest_discovery_complete"])
            self.assertEqual("invalid-manifest", value["extensions"][0]["manifest_inspection"])

    def test_transient_retry_is_bounded_and_permanent_http_errors_are_retained(self):
        transient = HTTPError("https://example.invalid", 429, "controlled", {}, None)
        fetch = Mock(side_effect=[transient, MANIFEST])
        sleep = Mock()
        value, raw = catalog.retrieve(record(), fetcher=fetch, sleep=sleep)
        self.assertEqual("read", value["manifest_inspection"])
        self.assertEqual(2, value["http_attempts"])
        sleep.assert_called_once_with(1)
        permanent = HTTPError("https://example.invalid", 404, "controlled", {}, None)
        fetch = Mock(side_effect=permanent)
        value, raw = catalog.retrieve(record(), fetcher=fetch, sleep=Mock(side_effect=AssertionError()))
        self.assertEqual(1, fetch.call_count)
        self.assertEqual("retrieval-error", value["manifest_inspection"])
        self.assertEqual(404, value["http_status"])
        self.assertIsNone(raw)
        fetch = Mock(side_effect=TimeoutError())
        value, _ = catalog.retrieve(record(), fetcher=fetch, sleep=lambda _: None)
        self.assertEqual(2, fetch.call_count)
        self.assertEqual("retrieval-error", value["manifest_inspection"])

    def test_error_resume_requires_explicit_retry_and_never_certifies_runtime(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            error = HTTPError("https://example.invalid", 404, "controlled", {}, None)
            failed = catalog.collect(encoded(inventory(("alpha",))), None, output, fetcher=Mock(side_effect=error))
            self.assertFalse(failed["summary"]["manifest_discovery_complete"])
            self.assertEqual(1, failed["summary"]["retrieval_or_manifest_errors"])
            no_fetch = Mock(side_effect=AssertionError())
            catalog.collect(encoded(inventory(("alpha",))), None, output, fetcher=no_fetch)
            no_fetch.assert_not_called()
            recovered = catalog.collect(encoded(inventory(("alpha",))), None, output, retry_errors=True, fetcher=lambda _: MANIFEST)
            self.assertTrue(recovered["summary"]["manifest_discovery_complete"])
            self.assertEqual(0, recovered["summary"]["runtime_tested_commands"])

    def test_malformed_oversized_or_wrong_blob_bytes_are_not_read_manifests(self):
        for data in (b"[]", b'{"commands":{}}', b'{"commands":[],"commands":[]}',
                     b"\xff", b"x" * (catalog.MAX_MANIFEST_BYTES + 1)):
            with self.subTest(data=data[:32]):
                value, raw = catalog.retrieve(record(), fetcher=lambda _, data=data: data)
                self.assertEqual("invalid-manifest", value["manifest_inspection"])
                self.assertIsNone(raw)
        value, raw = catalog.retrieve(record(), anchor=("0" * 40, "0" * 64), fetcher=lambda _: MANIFEST)
        self.assertEqual("invalid-manifest", value["manifest_inspection"])
        self.assertIsNone(raw)

    def test_anchor_must_match_the_source_and_inventory(self):
        for value in ({"sourceCommit": "c" * 40, "entries": []},
                      {"sourceCommit": COMMIT, "entries": [{"name": "outside"}]}):
            with self.assertRaises(catalog.CatalogError):
                catalog.load_anchors(encoded(value), COMMIT, {"alpha"})

    def test_defaults_and_non_semver_urls_do_not_enter_export(self):
        data = {"preferences": [{"name": "token", "type": "password", "required": True,
                                 "default": "controlled-sensitive-default"}],
                "dependencies": {"remote": "https://user:controlled-password@example.invalid/x"},
                "commands": [{"name": "copy", "mode": "view", "verification": "working"}]}
        value = catalog.normalize("alpha", encoded(data))
        serialized = json.dumps(value)
        self.assertNotIn("controlled-sensitive", serialized)
        self.assertNotIn("controlled-password", serialized)
        self.assertTrue(value["extension_preferences"][0]["has_default"])
        self.assertEqual("untested", value["commands"][0]["verification"])

    def test_redirect_cannot_remove_the_pinned_commit_even_on_the_same_host(self):
        from urllib.request import Request
        request = Request(record()["source_url"])
        with self.assertRaises(HTTPError) as failure:
            catalog.PinnedRedirects().redirect_request(request, None, 302, "controlled", {},
                                                       "https://raw.githubusercontent.com/raycast/extensions/main/package.json")
        failure.exception.close()

    def test_partial_cli_exit_is_failure_and_bounds_survive_python_optimization(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "inventory.json"
            source.write_bytes(encoded(inventory()))
            self.assertEqual(1, catalog.main(["--inventory", str(source), "--output", str(root / "output"), "--limit", "0"]))
            with self.assertRaises(catalog.CatalogError):
                catalog.collect(source.read_bytes(), None, root / "invalid", limit=-1)


if __name__ == "__main__":
    unittest.main()
