"""Markdown's new runner entry must not broaden device/fixture authorization."""
from pathlib import Path
import os
import subprocess
import sys
import tempfile
import unittest


RUNNER = Path(__file__).resolve().parents[1] / 'test-reviewed-text-input.py'
MARKDOWN = 'dev.tinycast.android.ExtensionMarkdownDeviceTest'


class ReviewedRunnerGuardTests(unittest.TestCase):
    def refused_without_device_access(self, classes, options, error):
        for optimized in [False, True]:
            with self.subTest(optimized=optimized), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                sdk = root / 'sdk'
                adb = sdk / 'platform-tools/adb'
                adb.parent.mkdir(parents=True)
                marker = root / 'device-was-accessed'
                adb.write_text('#!' + sys.executable + '\nfrom pathlib import Path\n'
                               'Path(' + repr(str(marker)) + ').write_text("unexpected")\nraise SystemExit(99)\n')
                adb.chmod(0o700)
                output = root / 'output'
                command = [sys.executable] + (['-O'] if optimized else []) + [str(RUNNER),
                    '--serial', 'emulator-5580', '--avd', 'Tinycast_Tablet_API_36', '--layout', 'tablet',
                    '--apk', str(root / 'app.apk'), '--test-apk', str(root / 'test.apk'),
                    '--json-archive', str(root / 'not-read.zip'), '--output', str(output),
                    '--classes', classes, *options]
                result = subprocess.run(command, capture_output=True, text=True, timeout=15,
                                        env=dict(os.environ, ANDROID_HOME=str(sdk)))
                self.assertNotEqual(0, result.returncode)
                self.assertIn(error, result.stderr)
                self.assertFalse(marker.exists(), 'Rejected options must not reach even a read-only adb query')
                self.assertFalse(output.exists(), 'Rejected options must not claim output or a recovery owner')

    def test_markdown_cannot_mix_with_shared_legacy_fixtures(self):
        self.refused_without_device_access(MARKDOWN + ',dev.tinycast.android.InlinePaletteDeviceTest', [],
                                           'Keep the shared menu/keyboard regression cohort separate')

    def test_markdown_cannot_claim_document_fixtures(self):
        self.refused_without_device_access(MARKDOWN, ['--document-fixtures'],
                                           'Document fixtures belong only to the owned Form cohort')

    def test_markdown_cannot_inject_a_clipboard_recovery_failure(self):
        self.refused_without_device_access(MARKDOWN, ['--inject-clipboard-restore-failure'],
                                           'Restoration failure is limited to the deliberate process-death control')

    def test_similarly_named_arbitrary_class_is_not_authorized(self):
        self.refused_without_device_access(MARKDOWN + 'Extra', [], 'Only owned input/Form/Markdown')


if __name__ == '__main__':
    unittest.main()
