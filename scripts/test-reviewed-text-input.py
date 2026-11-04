#!/usr/bin/env python3
"""Run owned reviewed-input, Form and Markdown workflows on the dedicated emulator.

The recovery lease remains held through all shared-state restoration and checks.
Archives, backups and raw diagnostics stay in the private local output directory.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import uuid

from android_test_cleanup import Cleanup, private_path_absent, reviewed_text_fixture_ids
from android_clipboard_recovery import ClipboardRecovery
from android_form_documents_fixture import FormDocumentsFixture


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def frozen_form_archive(path, expected):
    limit = 32 * 1024 * 1024
    require(path.stat().st_size <= limit, 'Frozen Form archive exceeds the size bound')
    with path.open('rb') as stream:
        data = stream.read(limit + 1)
    require(len(data) <= limit and sha(data) == expected, 'Frozen Form archive changed')
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--avd', required=True)
    parser.add_argument('--layout', choices=['tablet', 'phone', 'landscape'], required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--test-apk', type=Path, required=True)
    parser.add_argument('--json-archive', type=Path, help='Required frozen formatter for extension and shared regression cohorts')
    parser.add_argument('--mood-archive', type=Path, help='Optional hash-pinned Mood Tracker bundle for the owned real-Form workflow')
    parser.add_argument('--checksum-archive', type=Path, help='Optional hash-pinned Checksum bundle for the owned real-picker workflow')
    parser.add_argument('--html-colors-archive', type=Path, help='Optional hash-pinned HTML Colors bundle for the owned native Detail workflow')
    parser.add_argument('--translate-archive', type=Path, help='Optional hash-pinned Translate bundle for owned network observations')
    parser.add_argument('--weather-archive', type=Path, help='Optional hash-pinned Weather bundle for owned network observations')
    parser.add_argument('--classes', default='dev.tinycast.android.ReviewedTextDeviceTest')
    parser.add_argument('--expected', type=int, default=9)
    parser.add_argument('--font-scale', type=float, default=1)
    parser.add_argument('--document-fixtures', action='store_true', help='Lease read-only documents in the test APK for real system-picker workflows')
    parser.add_argument('--inject-clipboard-restore-failure', action='store_true',
                        help='Negative control only: retain the lease after an intentional process-death probe')
    args = parser.parse_args()
    require(args.serial.startswith('emulator-') and args.avd == 'Tinycast_Tablet_API_36', 'Use only the dedicated emulator')
    require(1 <= args.expected <= 24 and 1 <= args.font_scale <= 2, 'Invalid cohort size or font scale')
    require(re.fullmatch(r'[A-Za-z0-9_.,#]+', args.classes) is not None and all(
        item.split('#')[0] in ['dev.tinycast.android.ReviewedTextDeviceTest', 'dev.tinycast.android.ReviewedTextLifecycleDeviceTest',
                              'dev.tinycast.android.ReviewedTextClipboardCrashDeviceTest',
                              'dev.tinycast.android.ExtensionFormDeviceTest',
                              'dev.tinycast.android.ExtensionMarkdownDeviceTest',
                              'dev.tinycast.android.ExtensionDetailDeviceTest',
                              'dev.tinycast.android.CalculatorActionsDeviceTest',
                              'dev.tinycast.android.ClipboardEvidenceDeviceTest', 'dev.tinycast.android.ActivationDeviceTest',
                              'dev.tinycast.android.InlinePaletteDeviceTest', 'dev.tinycast.android.ReadinessRegressionDeviceTest']
        for item in args.classes.split(',')), 'Only owned input/Form/Markdown or shared menu/keyboard regression tests are allowed')
    require(all(not item.startswith('dev.tinycast.android.ActivationDeviceTest') or
                item == 'dev.tinycast.android.ActivationDeviceTest#explicitLauncherPrimaryCommitsComposedQueryAndActivates'
                for item in args.classes.split(',')), 'Only the preference-backed launcher Activation case is authorized')
    regression_classes = {'dev.tinycast.android.InlinePaletteDeviceTest', 'dev.tinycast.android.ReadinessRegressionDeviceTest',
                          'dev.tinycast.android.ClipboardEvidenceDeviceTest', 'dev.tinycast.android.ActivationDeviceTest'}
    regression = any(item.split('#')[0] in regression_classes for item in args.classes.split(','))
    calculator = any(item.split('#')[0] == 'dev.tinycast.android.CalculatorActionsDeviceTest' for item in args.classes.split(','))
    require(not calculator or all(item.split('#')[0] == 'dev.tinycast.android.CalculatorActionsDeviceTest' for item in args.classes.split(',')),
            'Keep the owned calculator cohort separate from extension and legacy fixtures')
    require(not regression or all(item.split('#')[0] in regression_classes for item in args.classes.split(',')),
            'Keep the shared menu/keyboard regression cohort separate from owned extension fixtures')
    require(not args.document_fixtures or all(item.split('#')[0] == 'dev.tinycast.android.ExtensionFormDeviceTest' for item in args.classes.split(',')),
            'Document fixtures belong only to the owned Form cohort')
    evidence_dirs = ['reviewed-text-evidence'] + (['extension-grid-evidence', 'readiness-evidence'] if regression else [])
    require(not args.inject_clipboard_restore_failure or args.classes == 'dev.tinycast.android.ReviewedTextClipboardCrashDeviceTest',
            'Restoration failure is limited to the deliberate process-death control')
    require(not args.html_colors_archive or all(item.split('#')[0] == 'dev.tinycast.android.ExtensionDetailDeviceTest'
        for item in args.classes.split(',')), 'HTML Colors belongs only to the owned Detail cohort')
    require(not args.weather_archive or all(item.split('#')[0] == 'dev.tinycast.android.ExtensionDetailDeviceTest'
        for item in args.classes.split(',')), 'Network bundle is not authorized for this test class')
    require(not args.translate_archive or all(item.split('#')[0] in
        ['dev.tinycast.android.ExtensionDetailDeviceTest', 'dev.tinycast.android.ReviewedTextDeviceTest'] for item in args.classes.split(',')),
        'Network bundle is not authorized for this test class')
    require(args.apk.name != args.test_apk.name, 'APK archive names must differ')
    require(calculator or args.json_archive is not None, 'A frozen JSON archive is required outside the owned calculator cohort')
    archive = None if args.json_archive is None else frozen_form_archive(args.json_archive,
        'fc420406a59251daee0c9af32e216026724631480b3a4e2f5d65d0c507b09a58')
    typed_archives = {}
    require(not (args.mood_archive or args.checksum_archive) or all(
        item.split('#')[0] == 'dev.tinycast.android.ExtensionFormDeviceTest' for item in args.classes.split(',')),
        'Typed Store bundles belong only to the owned Form cohort')
    for name, path, expected in [('mood', args.mood_archive, '46bd1d6f6598b3ecab43d6eac9778a8b4aba7e3d609859f90feceaf391b9b221'),
                                 ('checksum', args.checksum_archive, '8528552825c393464aaea7be81b88dfe54d0e51ce87d1c5cd4530adaa674203d')]:
        if path is not None:
            typed_archives[name] = frozen_form_archive(path, expected)
    if args.html_colors_archive is not None:
        typed_archives['html-colors'] = frozen_form_archive(args.html_colors_archive,
            '8a346fd2bd4ed01ceeacdeb71462b458014faa539a1f2b563638983843bdf0fb')
    for name, path, expected in [('translate', args.translate_archive, '9ecbb40257fbc1d0d32778efa95c1392fc6c46d03eb6099e3995a73b6e16eec2'),
                                 ('weather', args.weather_archive, '20ecbb18633829b80aa5cf3668a9876d1cacee9510548f7c2ba01ea695ec2b0c')]:
        if path is not None:
            typed_archives[name] = frozen_form_archive(path, expected)
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        sdk = next(line.removeprefix('sdk.dir=') for line in Path('local.properties').read_text().splitlines() if line.startswith('sdk.dir='))
    adb = [str(Path(sdk) / 'platform-tools/adb'), '-s', args.serial]
    app = 'dev.tinycast.android'
    root = 'files/reviewed-text-fixture-lease'
    lease_path = root + '/owners.json'
    owner = uuid.uuid4().hex

    def raw(*values, data=None):
        return subprocess.check_output(adb + list(values), input=data, stderr=subprocess.STDOUT, timeout=60)

    def text(*values):
        return raw(*values).decode().strip()

    def absent(path):
        result = subprocess.run(adb + ['shell', 'run-as', app, 'test', '-e', path], capture_output=True, timeout=60)
        return private_path_absent(result)

    require(text('shell', 'getprop', 'ro.kernel.qemu') == '1', 'Refuse physical devices')
    require(text('shell', 'getprop', 'ro.boot.qemu.avd_name') == args.avd, 'Wrong emulator identity')
    require(text('shell', 'id').startswith('uid=2000('), 'adbd must remain unprivileged')
    require('ActiveInstrumentation{' not in text('shell', 'dumpsys', 'activity', 'processes'), 'Another instrumentation is active')
    size, density = text('shell', 'wm', 'size'), text('shell', 'wm', 'density')
    require('Override' not in size + density, 'An earlier display override remains')
    keys = ['enabled_accessibility_services', 'accessibility_enabled', 'show_ime_with_hard_keyboard']
    secure = {key: text('shell', 'settings', 'get', 'secure', key) for key in keys}
    require(secure['enabled_accessibility_services'] == 'null' and secure['accessibility_enabled'] == '0', 'Refuse active accessibility services')
    font = text('shell', 'settings', 'get', 'system', 'font_scale')
    for path in [root, 'files/empty-view-fixture-lease', 'files/compatibility-batch-fixtures']:
        require(absent(path), 'An earlier fixture lease exists; inspect it before continuing')
    for folder in evidence_dirs:
        require(absent('files/' + folder), 'Retain or archive an earlier evidence directory before continuing')
    args.output.mkdir(parents=True, exist_ok=False, mode=0o700)
    shutil.copyfile(__file__, args.output / 'runner.py')
    helper = Path(__file__).with_name('android_test_cleanup.py')
    shutil.copyfile(helper, args.output / 'cleanup-helper.py')
    clipboard_helper = Path(__file__).with_name('android_clipboard_recovery.py')
    shutil.copyfile(clipboard_helper, args.output / 'clipboard-helper.py')
    document_helper = Path(__file__).with_name('android_form_documents_fixture.py')
    shutil.copyfile(document_helper, args.output / 'document-helper.py')
    record = {'completed': False, 'pid': os.getpid(), 'serial': args.serial, 'avd': args.avd, 'layout': args.layout,
              'font_scale': args.font_scale, 'classes': args.classes, 'expected': args.expected, 'owner_nonce': owner,
              'runner_sha256': sha(Path(__file__).read_bytes()), 'archive_sha256': sha(archive) if archive is not None else None,
              'typed_workflow_archive_sha256': {name: sha(data) for name, data in typed_archives.items()},
              'cleanup_helper_sha256': sha(helper.read_bytes()),
              'clipboard_helper_sha256': sha(clipboard_helper.read_bytes()),
              'document_helper_sha256': sha(document_helper.read_bytes()),
              'original_size': size, 'original_density': density, 'original_font_scale': font, 'settings_before': secure,
              'recovery_lease': root, 'evidence_directories': evidence_dirs, 'shared_regression_cohort': regression}

    def save():
        (args.output / 'record.json').write_text(json.dumps(record, indent=2) + '\n')

    save()
    raw('shell', 'run-as', app, 'mkdir', root)  # Atomic claim, never mkdir -p.
    backups = {}
    evidence_owned = False
    clipboard_recovery = ClipboardRecovery(raw, args.output, owner, app + '.test', record)
    document_fixture = FormDocumentsFixture(raw, owner, app + '.test', record)
    try:
        lease = {'schema_version': 1, 'fixture_kind': 'reviewed-text-device-test-v1', 'owner_nonce': owner, 'owned_extension_ids': []}
        raw('shell', 'run-as', app, 'tee', lease_path, data=(json.dumps(lease) + '\n').encode())
        raw('shell', 'run-as', app, 'tee', root + '/host.json', data=(json.dumps({'pid': os.getpid(), 'owner_nonce': owner, 'output': str(args.output.resolve())}) + '\n').encode())
        raw('shell', 'am', 'force-stop', app)
        for name in ['palette', 'emoji']:
            backups[name] = raw('exec-out', 'run-as', app, 'cat', 'shared_prefs/' + name + '.xml')
            path = args.output / ('private-' + name + '-backup.xml')
            path.write_bytes(backups[name]); path.chmod(0o600)
        for source in [args.apk, args.test_apk]:
            shutil.copyfile(source, args.output / source.name)
            raw('install', '-r', str(source))
        record['apk_sha256'] = sha(args.apk.read_bytes()); record['test_apk_sha256'] = sha(args.test_apk.read_bytes())
        for package, expected in [(app, record['apk_sha256']), (app + '.test', record['test_apk_sha256'])]:
            path = text('shell', 'pm', 'path', package).removeprefix('package:')
            require(sha(raw('exec-out', 'cat', path)) == expected, 'Installed APK identity mismatch')
        record['installed_apks_match'] = True
        clipboard_recovery.snapshot()  # Durable before any test clipboard writes.
        if args.document_fixtures:
            document_fixture.setup()
        if archive is not None:
            raw('shell', 'run-as', app, 'tee', root + '/json-format.zip', data=archive)
        for name, data in typed_archives.items():
            raw('shell', 'run-as', app, 'tee', root + '/' + name + '.zip', data=data)
        if args.layout in ['phone', 'landscape']:
            raw('shell', 'wm', 'size', '1080x2340' if args.layout == 'phone' else '2340x1080')
            raw('shell', 'wm', 'density', '420')
        raw('shell', 'settings', 'put', 'system', 'font_scale', str(args.font_scale))
        raw('shell', 'am', 'start', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
        for folder in evidence_dirs:
            require(absent('files/' + folder), 'An evidence directory appeared before instrumentation')
        evidence_owned = True
        save()
        clipboard_recovery.allow_mutations()
        save()
        command = adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'dedicatedAvd', args.avd,
                         '-e', 'reviewedTextFixture', 'true', '-e', 'expectedTextInputLeaseOwner', owner,
                         '-e', 'expectedClipboardRecoveryOwner', owner,
                         '-e', 'expectedFormDocumentOwner', owner if args.document_fixtures else 'none',
                         '-e', 'class', args.classes, app + '.test/androidx.test.runner.AndroidJUnitRunner']
        with (args.output / 'tests.log').open('wb') as log:
            process = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=max(600, args.expected * 90))
        record['instrumentation_exit_code'] = process.returncode
        log = (args.output / 'tests.log').read_text()
        record['passes'] = len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: 0$', log, re.M))
        record['tests_completed'] = process.returncode == 0 and record['passes'] == args.expected and f'OK ({args.expected} test' in log and 'FAILURES!!!' not in log
        for folder in evidence_dirs:
            if absent('files/' + folder):
                continue
            destination = args.output / ('captures' if folder == 'reviewed-text-evidence' else folder); destination.mkdir()
            for name in text('shell', 'run-as', app, 'ls', 'files/' + folder).splitlines():
                require(re.fullmatch(r'[a-z0-9-]+\.(png|xml|json)', name), 'Unexpected evidence path')
                data = raw('exec-out', 'run-as', app, 'cat', 'files/' + folder + '/' + name)
                (destination / name).write_bytes(data)
        record['completed'] = record['tests_completed']
    finally:
        cleanup = Cleanup(record)
        cleanup.attempt('stop-app', lambda: raw('shell', 'am', 'force-stop', app))
        cleanup.attempt('stop-owned-caller', lambda: raw('shell', 'am', 'force-stop', app + '.test'))
        if args.inject_clipboard_restore_failure:
            cleanup.attempt('inject-owned-restore-failure', clipboard_recovery.inject_restore_failure)
        cleanup.attempt('clipboard-restored-and-verified-independently', clipboard_recovery.restore)
        cleanup.attempt('stop-clipboard-recovery-activity', lambda: raw('shell', 'am', 'force-stop', app + '.test'))

        def owned_data():
            lease_bytes = raw('exec-out', 'run-as', app, 'cat', lease_path)
            identities = reviewed_text_fixture_ids(json.loads(lease_bytes), owner)
            (args.output / 'owner-lease.json').write_bytes(lease_bytes)
            for identity in identities:
                paths = ['files/extensions/' + identity, 'cache/' + identity, 'files/extension-data/' + identity,
                         'shared_prefs/extension-' + identity + '.xml', 'shared_prefs/extension-' + identity + '.xml.bak']
                raw('shell', 'run-as', app, 'rm', '-rf', *paths)
                for path in paths:
                    cleanup.require(absent(path), 'Owned fixture path remains')
            record['owned_extension_count'] = len(identities)
        cleanup.attempt('owned-fixture-data', owned_data)
        if evidence_owned:
            def remove_copied_evidence():
                for folder in evidence_dirs:
                    if absent('files/' + folder):
                        continue
                    destination = args.output / ('captures' if folder == 'reviewed-text-evidence' else folder)
                    for name in text('shell', 'run-as', app, 'ls', 'files/' + folder).splitlines():
                        require(re.fullmatch(r'[a-z0-9-]+\.(png|xml|json)', name), 'Unexpected evidence path')
                        cleanup.require((destination / name).is_file(), 'Retain uncopied failure evidence')
                        cleanup.require((destination / name).read_bytes() == raw('exec-out', 'run-as', app, 'cat', 'files/' + folder + '/' + name), 'Evidence copy differs')
                    raw('shell', 'run-as', app, 'rm', '-rf', 'files/' + folder)
            cleanup.attempt('copied-evidence', remove_copied_evidence)
        if regression:
            cleanup.attempt('legacy-fixtures-completed', lambda: cleanup.require(record.get('tests_completed') is True,
                            'Retain the lease for inspection after an incomplete legacy fixture cohort'))
        for name, data in backups.items():
            cleanup.attempt(name + '-backup', lambda name=name: raw('shell', 'run-as', app, 'rm', '-f', 'shared_prefs/' + name + '.xml.bak'))
            cleanup.attempt(name + '-preferences', lambda name=name, data=data: raw('shell', 'run-as', app, 'tee', 'shared_prefs/' + name + '.xml', data=data))
            cleanup.attempt(name + '-verified', lambda name=name, data=data: cleanup.require(raw('exec-out', 'run-as', app, 'cat', 'shared_prefs/' + name + '.xml') == data, 'Preferences differ'))
        cleanup.attempt('font-scale', lambda: raw('shell', 'settings', 'delete', 'system', 'font_scale') if font == 'null' else raw('shell', 'settings', 'put', 'system', 'font_scale', font))
        for key, value in secure.items():
            cleanup.attempt('secure-' + key, lambda key=key, value=value: raw('shell', 'settings', 'delete', 'secure', key) if value == 'null' else raw('shell', 'settings', 'put', 'secure', key, value))
        if args.layout in ['phone', 'landscape']:
            cleanup.attempt('size', lambda: raw('shell', 'wm', 'size', 'reset'))
            cleanup.attempt('density', lambda: raw('shell', 'wm', 'density', 'reset'))

        def verify():
            cleanup.require(text('shell', 'wm', 'size') == size and text('shell', 'wm', 'density') == density, 'Geometry differs')
            cleanup.require(text('shell', 'settings', 'get', 'system', 'font_scale') == font, 'Font differs')
            cleanup.require({key: text('shell', 'settings', 'get', 'secure', key) for key in keys} == secure, 'Secure settings differ')
            cleanup.require(text('shell', 'id').startswith('uid=2000('), 'adbd UID changed')
            cleanup.require(not text('shell', 'run-as', app, 'id').startswith(('uid=0', 'uid=1000(', 'uid=2000(')), 'App UID is privileged')
        cleanup.attempt('verify-state-and-unprivileged-identity', verify)
        if args.document_fixtures and document_fixture.claimed:
            cleanup.attempt('owned-document-sources-unchanged', document_fixture.verify)

        def release():
            cleanup.require(not cleanup.errors, 'Retain recovery ownership after restoration failure')
            reviewed_text_fixture_ids(json.loads(raw('exec-out', 'run-as', app, 'cat', lease_path)), owner)
            if args.document_fixtures:
                document_fixture.release()
            clipboard_recovery.release()
            raw('shell', 'run-as', app, 'rm', '-rf', root)  # Final device operation.
        cleanup.attempt('release-lease', release)
        record['completed'] = record['completed'] and not cleanup.errors
        save(); cleanup.finish()
    print(json.dumps({'completed': record['completed'], 'passes': record.get('passes'), 'cleanup_checks': len(record['cleanup'])}))
    require(record['completed'], 'Workflow assertions failed; inspect the retained tests.log')


if __name__ == '__main__':
    main()
