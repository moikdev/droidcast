import importlib.util
import io
from pathlib import Path
import sys
import tarfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
spec = importlib.util.spec_from_file_location('webview_engine_runner', ROOT / 'test-webview-engine.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class WebViewRunnerTests(unittest.TestCase):
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
