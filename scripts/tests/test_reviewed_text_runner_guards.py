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
CLIPBOARD = 'dev.tinycast.android.ClipboardEvidenceDeviceTest'
ACTIVATION = 'dev.tinycast.android.ActivationDeviceTest'
HANDOFF = 'dev.tinycast.android.ExtensionCommandHandoffDeviceTest'


class ReviewedRunnerGuardTests(unittest.TestCase):
    def test_paste_copy_expectation_cannot_broaden_other_fixtures(self):
        selected = 'dev.tinycast.android.ReviewedTextDeviceTest#selectedTranslationRunsOnlyAfterReviewAndReportsExactCopyOrServiceFailure'
        for classes, options in [(DETAIL, []), (selected, []),
                                 (selected, ['--reviewed-translation-command', 'instant-translate-paste']),
                                 ('dev.tinycast.android.ReviewedTextDeviceTest', ['--reviewed-translation-command', 'instant-translate-paste', '--translate-archive', 'not-read.zip'])]:
            self.refused_without_device_access(classes, ['--expect-paste-copy-fallback', *options],
                'Paste Copy fallback requires the single frozen paste observation')

    def test_handoff_cannot_mix_with_other_fixtures(self):
        for selected in [DETAIL, CALCULATOR, 'dev.tinycast.android.ReviewedTextDeviceTest', CLIPBOARD]:
            self.refused_without_device_access(HANDOFF + ',' + selected, [],
                'Keep the owned command handoff cohort separate from other fixtures')

    def test_handoff_cannot_claim_unrelated_archives_or_recovery_controls(self):
        for flag, error in [('--translate-archive', 'Network bundle is not authorized'),
                            ('--weather-archive', 'Network bundle is not authorized'),
                            ('--html-colors-archive', 'HTML Colors belongs only'),
                            ('--uuid-archive', 'Offline bundles belong only')]:
            self.refused_without_device_access(HANDOFF, [flag, 'not-read.zip'], error)
        self.refused_without_device_access(HANDOFF, ['--document-fixtures'], 'Document fixtures belong only')
        self.refused_without_device_access(HANDOFF, ['--inject-clipboard-restore-failure'], 'Restoration failure is limited')

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

    def test_frozen_network_archives_are_limited_to_the_owned_detail_cohort(self):
        for flag in ['--translate-archive', '--weather-archive']:
            denied = [MARKDOWN, CALCULATOR, CLIPBOARD,
                             ACTIVATION + '#explicitLauncherPrimaryCommitsComposedQueryAndActivates',
                             'dev.tinycast.android.ExtensionFormDeviceTest']
            if flag == '--weather-archive':
                denied.append('dev.tinycast.android.ReviewedTextDeviceTest')
            for selected in denied:
                with self.subTest(flag=flag, selected=selected):
                    self.refused_without_device_access(selected, [flag, 'not-read-network.zip'],
                                                       'Network bundle is not authorized for this test class')

    def test_offline_archives_are_limited_to_the_owned_form_cohort(self):
        for flag in ['--uuid-archive', '--lorem-archive']:
            for selected in [MARKDOWN, DETAIL, CALCULATOR, CLIPBOARD, 'dev.tinycast.android.ReviewedTextDeviceTest']:
                with self.subTest(flag=flag, selected=selected):
                    self.refused_without_device_access(selected, [flag, 'not-read-offline.zip'],
                        'Offline bundles belong only to the owned Form cohort')

    def test_no_view_json_cannot_change_other_review_or_runtime_cases(self):
        for selected in ['dev.tinycast.android.ReviewedTextDeviceTest',
                         'dev.tinycast.android.ReviewedTextDeviceTest#sharedJsonRunsOnlyAfterReviewThenCopiesAndReturns',
                         DETAIL, CALCULATOR, 'dev.tinycast.android.ExtensionFormDeviceTest']:
            with self.subTest(selected=selected):
                self.refused_without_device_access(selected, ['--reviewed-json-command', 'formatSelection'],
                    'The no-view formatter requires the single reviewed selection workflow')

    def test_completion_return_cannot_broaden_the_authorized_workflow(self):
        for selected in [MARKDOWN, DETAIL, CALCULATOR, CLIPBOARD,
                         'dev.tinycast.android.ReviewedTextDeviceTest',
                         'dev.tinycast.android.ReviewedTextDeviceTest#selectedJsonRunsOnlyAfterReviewThenCopiesAndReturns']:
            with self.subTest(selected=selected):
                self.refused_without_device_access(selected, ['--expect-completion-return'],
                    'Completion Return belongs only to owned Form or the single reviewed no-view JSON workflow')

    def test_network_archives_do_not_borrow_the_calculator_archive_exemption(self):
        for flag in ['--translate-archive', '--weather-archive']:
            self.refused_without_device_access(CALCULATOR, [flag, 'not-read-network.zip'],
                                               'Network bundle is not authorized for this test class', without_archive=True)

    def test_reviewed_translate_still_requires_the_frozen_json_archive_before_device_access(self):
        self.refused_without_device_access('dev.tinycast.android.ReviewedTextDeviceTest',
            ['--translate-archive', 'not-read-network.zip'],
            'A frozen JSON archive is required outside the owned calculator cohort', without_archive=True)

    def test_selected_translation_requires_its_archive_before_any_device_access(self):
        self.refused_without_device_access('dev.tinycast.android.ReviewedTextDeviceTest#'
            'selectedTranslationRunsOnlyAfterReviewAndReportsExactCopyOrServiceFailure', [],
            'The selected translation observation requires --translate-archive')

    def test_translation_variants_cannot_change_other_review_or_runtime_cohorts(self):
        for selected in ['dev.tinycast.android.ReviewedTextDeviceTest', DETAIL, CALCULATOR,
                         'dev.tinycast.android.ReviewedTextDeviceTest#selectedJsonRunsOnlyAfterReviewThenCopiesAndReturns']:
            self.refused_without_device_access(selected, ['--reviewed-translation-command', 'quick-translate'],
                'Translation variants require the single reviewed translation workflow')

    def test_menu_bar_observation_requires_its_archive_before_any_device_access(self):
        self.refused_without_device_access('dev.tinycast.android.ReviewedTextDeviceTest#'
            'desktopMenuBarIsUnavailableInReviewedChooserAndReturnsToSource', [],
            'The menu-bar observation requires --weather-archive')

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

    def test_only_the_preference_backed_activation_case_is_authorized(self):
        for selected in [ACTIVATION, ACTIVATION + '#explicitListPrimaryCommitsComposedQueryAndActivates']:
            self.refused_without_device_access(selected, [], 'Only the preference-backed launcher Activation case is authorized')

    def test_clipboard_cannot_borrow_calculator_archive_or_document_exceptions(self):
        self.refused_without_device_access(CLIPBOARD, [], 'A frozen JSON archive is required outside the owned calculator cohort',
                                           without_archive=True)
        self.refused_without_device_access(CLIPBOARD, ['--document-fixtures'], 'Document fixtures belong only to the owned Form cohort')

    def test_clipboard_and_owned_extension_cohorts_remain_separate(self):
        self.refused_without_device_access(CLIPBOARD + ',' + DETAIL, [], 'Keep the shared menu/keyboard regression cohort separate')

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
