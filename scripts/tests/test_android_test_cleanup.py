import sys
from pathlib import Path
import unittest
from unittest.mock import patch
import subprocess
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from android_test_cleanup import Cleanup, CleanupError, transition_adbd, empty_view_fixture_ids


class EmptyViewLeaseTests(unittest.TestCase):
    owner = 'a' * 32

    def lease(self, ids=None):
        return {'schema_version': 1, 'fixture_kind': 'empty-view-device-test-v1', 'owner_nonce': self.owner,
                'owned_extension_ids': ['empty-view-' + 'b' * 32] if ids is None else ids}

    def test_only_the_exact_owned_namespace_is_accepted(self):
        value = self.lease()
        self.assertEqual(value['owned_extension_ids'], empty_view_fixture_ids(value, self.owner))
        self.assertEqual([], empty_view_fixture_ids(self.lease([]), self.owner))
        for bad in ['../palette', 'cache/empty-view-' + 'b' * 32, 'empty-view-' + 'B' * 32,
                    'empty-view-' + 'b' * 31, 'empty-view-' + 'b' * 32 + '/x', 'empty-view-%2f', None]:
            with self.assertRaises(CleanupError): empty_view_fixture_ids(self.lease([bad]), self.owner)

    def test_malformed_foreign_and_extra_field_leases_cannot_authorize_cleanup(self):
        for bad in [None, [], {}, dict(self.lease(), owner_nonce='c' * 32), dict(self.lease(), fixture_kind='other'),
                    dict(self.lease(), schema_version=2), dict(self.lease(), schema_version=True), dict(self.lease(), extra='ignored'),
                    dict(self.lease(), owned_extension_ids='empty-view-' + 'b' * 32)]:
            with self.assertRaises(CleanupError): empty_view_fixture_ids(bad, self.owner)
        with self.assertRaises(CleanupError): empty_view_fixture_ids(self.lease(), '../owner')

    def test_duplicate_and_excessive_leases_are_rejected_before_deletion(self):
        identity = 'empty-view-' + 'b' * 32
        for ids in [[identity, identity], ['empty-view-' + format(i, '032x') for i in range(101)]]:
            with self.assertRaises(CleanupError): empty_view_fixture_ids(self.lease(ids), self.owner)


class CleanupFailureTest(unittest.TestCase):
    def test_failed_release_and_clipboard_do_not_skip_preferences_settings_or_unroot(self):
        record = {}; cleanup = Cleanup(record); restored = []
        def fail(): raise AssertionError('controlled restoration failure')
        cleanup.attempt('release', fail)
        cleanup.attempt('clipboard', fail)
        for action in ['preferences', 'settings', 'viewport', 'unroot']:
            cleanup.attempt(action, lambda name=action: restored.append(name))
        self.assertEqual(['preferences', 'settings', 'viewport', 'unroot'], restored)
        self.assertFalse(record['cleanup']['release']['passed'])
        self.assertFalse(record['cleanup']['clipboard']['passed'])
        with self.assertRaisesRegex(CleanupError, 'release, clipboard'): cleanup.finish()

    def test_cleanup_continues_after_interruption_and_never_reports_it_as_success(self):
        cleanup = Cleanup({}); restored = []
        def interrupted(): raise KeyboardInterrupt()
        cleanup.attempt('clipboard', interrupted)
        cleanup.attempt('unroot', lambda: restored.append(True))
        self.assertEqual([True], restored)
        with self.assertRaises(CleanupError): cleanup.finish()

    @patch('android_test_cleanup.time.sleep')
    @patch('android_test_cleanup.subprocess.run')
    def test_unroot_waits_past_old_root_and_offline_states(self, run, sleep):
        run.side_effect = [subprocess.CompletedProcess([], 1),
                           subprocess.CompletedProcess([], 0, b'uid=0(root)'),
                           subprocess.CompletedProcess([], 1, b''),
                           subprocess.CompletedProcess([], 0, b'uid=2000(shell)')]
        transition_adbd(['adb', '-s', 'emulator-test'], False)
        self.assertEqual(4, run.call_count)
        self.assertEqual(2, sleep.call_count)

    @patch('android_test_cleanup.time.monotonic', side_effect=[0, 16])
    @patch('android_test_cleanup.subprocess.run')
    def test_unroot_timeout_is_a_failure(self, run, clock):
        with self.assertRaises(CleanupError): transition_adbd(['adb'], False)


if __name__ == '__main__': unittest.main()
