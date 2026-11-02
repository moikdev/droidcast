import sys
from pathlib import Path
import unittest
from unittest.mock import patch
import subprocess
import ast
import json
import tempfile
from types import SimpleNamespace
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


class RunnerRestorationOwnershipTests(unittest.TestCase):
    """Execute the real runner finalizer with hermetic device I/O, not adb."""
    def run_finalizer(self, fail_preferences=False, replace_owner=False):
        owner = 'a' * 32
        app = 'dev.tinycast.android'
        root = 'files/empty-view-fixture-lease'
        lease = {'schema_version': 1, 'fixture_kind': 'empty-view-device-test-v1', 'owner_nonce': owner,
                 'owned_extension_ids': ['empty-view-' + 'b' * 32]}
        events = []
        prefs, emoji = b'original palette', b'original emoji'
        state = {'lease': lease, 'palette': b'changed palette', 'emoji': b'changed emoji'}
        def raw(*args, data=None):
            events.append(args)
            if args == ('exec-out', 'run-as', app, 'cat', root + '/owners.json'):
                if state['lease'] is None: raise FileNotFoundError('Lease already released')
                return json.dumps(state['lease']).encode()
            if args[:4] == ('shell', 'run-as', app, 'tee'):
                key = 'palette' if args[4].endswith('/palette.xml') else 'emoji'
                if key == 'palette' and fail_preferences: raise OSError('Controlled restoration failure')
                state[key] = data
            if args == ('exec-out', 'run-as', app, 'cat', 'shared_prefs/palette.xml'): return state['palette']
            if args == ('exec-out', 'run-as', app, 'cat', 'shared_prefs/emoji.xml'): return state['emoji']
            if args == ('shell', 'run-as', app, 'rm', '-rf', root): state['lease'] = None
            if args[:4] == ('shell', 'settings', 'get', 'secure'): return settings[args[4]].encode()
            if args == ('shell', 'settings', 'get', 'system', 'font_scale'): return b'1.0'
            if args == ('shell', 'wm', 'size'): return b'Physical size: 1920x1200'
            if args == ('shell', 'wm', 'density'): return b'Physical density: 240'
            if args == ('shell', 'id'): return b'uid=2000(shell) groups=2000(shell)'
            if args == ('shell', 'run-as', app, 'id'):
                if replace_owner: state['lease'] = dict(lease, owner_nonce='c' * 32)
                return b'uid=10213(app) groups=10213(app)'
            return b''
        settings = {'enabled_accessibility_services': 'null', 'accessibility_enabled': '0', 'show_ime_with_hard_keyboard': '1'}
        source = Path(__file__).resolve().parents[1] / 'test-extension-grid.py'
        finalizer = [node for node in ast.parse(source.read_text()).body if isinstance(node, ast.Try) and node.finalbody][-1]
        code = compile(ast.fix_missing_locations(ast.Module(body=finalizer.finalbody, type_ignores=[])), str(source), 'exec')
        record = {'completed': True, 'original_size': 'Physical size: 1920x1200', 'original_density': 'Physical density: 240'}
        error = None
        with tempfile.TemporaryDirectory() as temporary:
            values = dict(Cleanup=Cleanup, empty_view_fixture_ids=empty_view_fixture_ids, json=json, subprocess=SimpleNamespace(run=lambda *a, **k: subprocess.CompletedProcess(a, 1)),
                          raw=raw, text=lambda *a: raw(*a).decode(), r=record, app=app, adb=['hermetic-adb'], a=SimpleNamespace(output=Path(temporary), layout='phone', size=None),
                          empty_view_owned=True, empty_owner=owner, empty_root=root, empty_lease=root + '/owners.json', fixture_owned=False, upload_owned=False,
                          source_approval=None, prefs=prefs, emoji_prefs=emoji, settings=settings, keys=list(settings), font_before='1.0')
            try: exec(code, values)
            except CleanupError as failure: error = failure
        return record, error, events, state['lease']

    def test_lease_release_is_the_last_device_operation_after_restoration_and_verification(self):
        record, error, events, lease = self.run_finalizer()
        self.assertIsNone(error)
        self.assertTrue(record['completed'])
        self.assertIsNone(lease)
        self.assertEqual(('shell', 'run-as', 'dev.tinycast.android', 'rm', '-rf', 'files/empty-view-fixture-lease'), events[-1])

    def test_failed_restoration_keeps_ownership_and_still_attempts_remaining_restoration(self):
        record, error, events, lease = self.run_finalizer(fail_preferences=True)
        self.assertIsInstance(error, CleanupError)
        self.assertFalse(record['completed'])
        self.assertIsNotNone(lease)
        self.assertFalse(record['cleanup']['preferences']['passed'])
        self.assertTrue(record['cleanup']['emoji-preferences']['passed'])
        self.assertTrue(record['cleanup']['verify-geometry']['passed'])
        self.assertTrue(record['cleanup']['verify-identity']['passed'])

    def test_a_changed_owner_is_revalidated_and_never_released(self):
        record, error, events, lease = self.run_finalizer(replace_owner=True)
        self.assertIsInstance(error, CleanupError)
        self.assertFalse(record['completed'])
        self.assertEqual('c' * 32, lease['owner_nonce'])
        self.assertFalse(record['cleanup']['empty-view-lease']['passed'])


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
