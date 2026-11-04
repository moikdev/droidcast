"""Markdown's new runner entry must not broaden device/fixture authorization."""
from pathlib import Path
import os
import json
import subprocess
import sys
import tempfile
import unittest


RUNNER = Path(__file__).resolve().parents[1] / 'test-reviewed-text-input.py'
MARKDOWN = 'dev.tinycast.android.ExtensionMarkdownDeviceTest'
DETAIL = 'dev.tinycast.android.ExtensionDetailDeviceTest'
CALCULATOR = 'dev.tinycast.android.CalculatorActionsDeviceTest'


class ReviewedRunnerGuardTests(unittest.TestCase):
    def refused_without_device_access(self, classes, options, error, without_archive=False):
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
                if without_archive:
                    at = command.index('--json-archive')
                    del command[at:at + 2]
                result = subprocess.run(command, capture_output=True, text=True, timeout=15,
                                        env=dict(os.environ, ANDROID_HOME=str(sdk)))
                self.assertNotEqual(0, result.returncode)
                self.assertIn(error, result.stderr)
                self.assertFalse(marker.exists(), 'Rejected options must not reach even a read-only adb query')
                self.assertFalse(output.exists(), 'Rejected options must not claim output or a recovery owner')

    def test_markdown_cannot_mix_with_shared_legacy_fixtures(self):
        self.refused_without_device_access(MARKDOWN + ',dev.tinycast.android.InlinePaletteDeviceTest', [],
                                           'Keep the shared menu/keyboard regression cohort separate')

    def test_calculator_cannot_mix_with_extension_fixtures(self):
        self.refused_without_device_access(CALCULATOR + ',' + DETAIL, [], 'Keep the owned calculator cohort separate')

    def test_calculator_cannot_claim_documents_or_inject_recovery_failure(self):
        self.refused_without_device_access(CALCULATOR, ['--document-fixtures'], 'Document fixtures belong only to the owned Form cohort')
        self.refused_without_device_access(CALCULATOR, ['--inject-clipboard-restore-failure'],
                                           'Restoration failure is limited to the deliberate process-death control')

    def test_similarly_named_calculator_class_is_not_authorized(self):
        self.refused_without_device_access(CALCULATOR + 'Extra', [], 'Only owned input/Form/Markdown')

    def test_extensions_still_require_the_frozen_archive_before_any_device_query(self):
        self.refused_without_device_access(DETAIL, [], 'A frozen JSON archive is required outside the owned calculator cohort',
                                           without_archive=True)

    def test_calculator_without_an_archive_reaches_only_read_only_avd_preflight(self):
        for optimized in [False, True]:
            with self.subTest(optimized=optimized), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                sdk = root / 'sdk'
                adb = sdk / 'platform-tools/adb'
                adb.parent.mkdir(parents=True)
                marker = root / 'preflight-arguments.json'
                adb.write_text('#!' + sys.executable + '\nimport json,sys\nfrom pathlib import Path\n'
                               'Path(' + repr(str(marker)) + ').write_text(json.dumps(sys.argv[1:]))\nraise SystemExit(97)\n')
                adb.chmod(0o700)
                output = root / 'output'
                command = [sys.executable] + (['-O'] if optimized else []) + [str(RUNNER),
                    '--serial', 'emulator-5580', '--avd', 'Tinycast_Tablet_API_36', '--layout', 'tablet',
                    '--apk', str(root / 'not-read.apk'), '--test-apk', str(root / 'not-read-test.apk'),
                    '--output', str(output), '--classes', CALCULATOR]
                result = subprocess.run(command, capture_output=True, text=True, timeout=15,
                                        env=dict(os.environ, ANDROID_HOME=str(sdk)))
                self.assertNotEqual(0, result.returncode)
                self.assertIn('CalledProcessError', result.stderr)
                self.assertEqual(['-s', 'emulator-5580', 'shell', 'getprop', 'ro.kernel.qemu'], json.loads(marker.read_text()))
                self.assertFalse(output.exists(), 'Read-only preflight failure must not claim output or ownership')

    def test_markdown_cannot_claim_document_fixtures(self):
        self.refused_without_device_access(MARKDOWN, ['--document-fixtures'],
                                           'Document fixtures belong only to the owned Form cohort')

    def test_markdown_cannot_inject_a_clipboard_recovery_failure(self):
        self.refused_without_device_access(MARKDOWN, ['--inject-clipboard-restore-failure'],
                                           'Restoration failure is limited to the deliberate process-death control')

    def test_similarly_named_arbitrary_class_is_not_authorized(self):
        self.refused_without_device_access(MARKDOWN + 'Extra', [], 'Only owned input/Form/Markdown')

    def test_detail_cannot_claim_document_fixtures(self):
        self.refused_without_device_access(DETAIL, ['--document-fixtures'], 'Document fixtures belong only to the owned Form cohort')

    def test_detail_cannot_inject_a_clipboard_recovery_failure(self):
        self.refused_without_device_access(DETAIL, ['--inject-clipboard-restore-failure'],
                                           'Restoration failure is limited to the deliberate process-death control')

    def test_detail_cannot_mix_with_shared_legacy_fixtures(self):
        self.refused_without_device_access(DETAIL + ',dev.tinycast.android.ReadinessRegressionDeviceTest', [],
                                           'Keep the shared menu/keyboard regression cohort separate')

    def test_similarly_named_detail_class_is_not_authorized(self):
        self.refused_without_device_access(DETAIL + 'Extra', [], 'Only owned input/Form/Markdown')

    def test_markdown_cannot_claim_html_colors(self):
        self.refused_without_device_access(MARKDOWN, ['--html-colors-archive', 'not-read.zip'],
                                           'HTML Colors belongs only to the owned Detail cohort')

    def test_form_cannot_claim_html_colors(self):
        self.refused_without_device_access('dev.tinycast.android.ExtensionFormDeviceTest', ['--html-colors-archive', 'not-read.zip'],
                                           'HTML Colors belongs only to the owned Detail cohort')

    def test_legacy_cannot_claim_html_colors(self):
        self.refused_without_device_access('dev.tinycast.android.InlinePaletteDeviceTest', ['--html-colors-archive', 'not-read.zip'],
                                           'HTML Colors belongs only to the owned Detail cohort')

    def test_html_colors_cannot_mix_with_another_owned_class(self):
        self.refused_without_device_access(DETAIL + ',' + MARKDOWN, ['--html-colors-archive', 'not-read.zip'],
                                           'HTML Colors belongs only to the owned Detail cohort')


if __name__ == '__main__':
    unittest.main()
