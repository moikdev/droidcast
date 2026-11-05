import importlib.util
import io
from pathlib import Path
import sys
import subprocess
import tarfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
spec = importlib.util.spec_from_file_location('webview_engine_runner', ROOT / 'test-webview-engine.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class WebViewRunnerTests(unittest.TestCase):
    def rejected_suite(self, suite, method, extra=()):
        result = subprocess.run([sys.executable, str(ROOT / 'test-webview-engine.py'),
            '--serial', 'emulator-5598', '--avd', 'Droidcast_WebView_API26_27caf3',
            '--apk', '/unused-app.apk', '--test-apk', '/unused-test.apk', '--output', '/unused-output',
            '--suite', suite, '--cases', method, *extra], capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn('Both frozen APKs are required', result.stderr)
        self.assertNotIn('FileNotFoundError', result.stderr)
        return result.stderr

    def test_native_suite_cannot_select_engine_cases(self):
        self.assertIn('Unsupported native cases', self.rejected_suite('native-input', 'incompatibleEngineShowsNativeRecoveryWithoutDispatch'))

    def test_default_engine_suite_cannot_select_native_input_cases(self):
        self.assertIn('Unsupported native cases', self.rejected_suite('engine', 'recordCallerSelectionAcrossNativeRoutes'))

    def test_engine_negative_classifier_cannot_certify_native_input_diagnostics(self):
        self.assertIn('Negative control is limited', self.rejected_suite('native-input', 'recordDefaultImeAndControlledCompositionNavigation', ['--expected-negative']))

    def test_controlled_contracts_require_owned_ime(self):
        self.assertIn('Controlled contracts require', self.rejected_suite('native-input', 'exactContentAssertionAcceptsCompositionAndRejectsChangedCharacters'))

    def test_system_ime_diagnostics_cannot_use_owned_ime(self):
        self.assertIn('Owned IME is limited', self.rejected_suite('native-input', 'recordCallerSelectionAcrossNativeRoutes', ['--input-mode', 'owned']))

    def test_engine_suite_cannot_change_input_method(self):
        self.assertIn('Owned IME is limited', self.rejected_suite('engine', 'incompatibleEngineShowsNativeRecoveryWithoutDispatch', ['--input-mode', 'owned']))

    def test_touch_suite_cannot_select_engine_or_input_cases(self):
        for method in ('recordCallerSelectionAcrossNativeRoutes', 'modernEnginePublishesListAndRunsLiveAction'):
            self.assertIn('Unsupported native cases', self.rejected_suite('touch-search', method))

    def test_touch_suite_cannot_change_input_method_or_use_engine_negative_classifier(self):
        method = 'launcherTouchClearPreservesFocusCategoryAndKeyboard'
        self.assertIn('Owned IME is limited', self.rejected_suite('touch-search', method, ['--input-mode', 'owned']))
        self.assertIn('Negative control is limited', self.rejected_suite('touch-search', method, ['--expected-negative']))

    def test_package_absence_uses_exact_successful_inventory(self):
        self.assertFalse(runner.listed_package_presence('', 'dev.tinycast.android'))
        self.assertFalse(runner.listed_package_presence('package:dev.tinycast.android.test\n', 'dev.tinycast.android'))
        self.assertTrue(runner.listed_package_presence('package:dev.tinycast.android\n', 'dev.tinycast.android'))
        for error in ('Error: transport failed', 'package:/not/a/package'):
            with self.assertRaises(ValueError):
                runner.listed_package_presence(error, 'dev.tinycast.android')

    def archive(self, entries):
        data = io.BytesIO()
        with tarfile.open(fileobj=data, mode='w') as archive:
            for name, kind, value in entries:
                info = tarfile.TarInfo(name); info.type = kind; info.size = len(value)
                archive.addfile(info, io.BytesIO(value))
        return data.getvalue()

    def test_private_archives_preserve_file_bytes_without_extraction(self):
        data = self.archive([('files/owned/data', tarfile.REGTYPE, b'\x00\r\nprivate'),
                             ('shared_prefs/palette.xml', tarfile.REGTYPE, b'<map/>')])
        self.assertEqual({'files/owned/data': b'\x00\r\nprivate', 'shared_prefs/palette.xml': b'<map/>'}, runner.archive_files(data))

    def test_archive_rejects_paths_outside_registered_app_namespaces(self):
        for name in ('/files/data', '../files/data', 'files/../other', 'cache/private'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                runner.archive_files(self.archive([(name, tarfile.REGTYPE, b'x')]))

    def test_archive_rejects_duplicate_or_nonregular_entries(self):
        with self.assertRaises(ValueError):
            runner.archive_files(self.archive([('files/a', tarfile.REGTYPE, b'x')] * 2))
        for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.FIFOTYPE):
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                runner.archive_files(self.archive([('files/a', kind, b'')]))

    def test_guards_remain_active_with_optimized_python(self):
        with self.assertRaises(ValueError):
            runner.require(False, 'retained owner')

    def test_fixture_has_only_local_state_actions_and_two_declared_commands(self):
        import json
        files = runner.fixture('webview-engine-' + 'a' * 32)
        self.assertEqual({'index.js', 'grid.js', 'package.json', '.tinycast-store.json'}, set(files))
        self.assertEqual(['index', 'grid'], [c['name'] for c in json.loads(files['package.json'])['commands']])
        for name in ('index.js', 'grid.js'):
            for denied in (b'Clipboard', b'fetch(', b'LocalStorage', b'launchCommand'):
                self.assertNotIn(denied, files[name])


if __name__ == '__main__':
    unittest.main()
