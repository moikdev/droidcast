import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from android_clipboard_recovery import ClipboardRecovery, ROOT, public_receipt, validate_receipt


class ClipboardRecoveryProtocolTest(unittest.TestCase):
    owner = 'a' * 32
    request = 'b' * 32
    snapshot = 'c' * 64

    def receipt(self):
        return {'schema_version': 1, 'owner_nonce': self.owner, 'request_id': self.request,
                'phase': 'restored', 'snapshot_sha256': self.snapshot, 'content_sha256': 'd' * 64}

    def test_rejects_receipts_for_another_owner_or_old_request(self):
        for key in ['owner_nonce', 'request_id']:
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_receipt(dict(self.receipt(), **{key: 'e' * 32}), self.owner, self.request, 'restored')

    def test_snapshot_or_failed_receipts_cannot_certify_restoration(self):
        for phase in ['snapshot-ready', 'failed', None]:
            with self.subTest(phase=phase), self.assertRaises(ValueError):
                validate_receipt(dict(self.receipt(), phase=phase), self.owner, self.request, 'restored')

    def test_rejects_missing_malformed_or_changed_snapshot_identities(self):
        for identity in [None, True, 'c' * 63, 'f' * 64]:
            with self.subTest(identity=identity), self.assertRaises(ValueError):
                validate_receipt(dict(self.receipt(), snapshot_sha256=identity), self.owner, self.request, 'restored', self.snapshot)

    def test_boolean_schema_is_not_a_valid_protocol_version(self):
        with self.assertRaises(ValueError):
            validate_receipt(dict(self.receipt(), schema_version=True), self.owner, self.request, 'restored')

    def test_public_receipts_exclude_clipboard_contents_and_fingerprints(self):
        public = public_receipt(dict(self.receipt(), clipboard_contents='Owned sensitive canary'))
        self.assertEqual({'schema_version', 'owner_nonce', 'request_id', 'phase'}, set(public))
        self.assertNotIn('Owned sensitive canary', json.dumps(public))

    def helper(self, receipt):
        calls = []
        def raw(*command, **unused):
            calls.append(command)
            if command[-1] == ROOT + '/owner.json':
                return json.dumps({'schema_version': 1, 'owner_nonce': self.owner}).encode()
            if command[-1] == ROOT + '/receipt.json':
                return json.dumps(receipt).encode()
            return b''
        helper = ClipboardRecovery(raw, '.', self.owner, 'dev.tinycast.android.test', {})
        helper.attempted = helper.ready = helper.permitted = True
        helper.identity = self.snapshot
        return helper, calls

    def test_failed_restore_keeps_snapshot_and_never_deletes_its_owner(self):
        helper, calls = self.helper(self.receipt())
        with self.assertRaises(ValueError):
            helper.release()
        self.assertFalse(any('rm' in call for call in calls))

    def test_release_rechecks_the_current_receipt_instead_of_trusting_memory(self):
        helper, calls = self.helper(dict(self.receipt(), request_id='e' * 32))
        helper.restored_request = self.request
        with self.assertRaises(ValueError):
            helper.release()
        self.assertFalse(any('rm' in call for call in calls))

    def test_verified_restore_releases_only_the_owned_fixed_namespace(self):
        helper, calls = self.helper(self.receipt())
        helper.restored_request = self.request
        helper.release()
        self.assertEqual(('shell', 'run-as', 'dev.tinycast.android.test', 'rm', '-rf', ROOT), calls[-2])
        self.assertEqual(('shell', 'run-as', 'dev.tinycast.android.test', 'test', '!', '-e', ROOT), calls[-1])


if __name__ == '__main__':
    unittest.main()
