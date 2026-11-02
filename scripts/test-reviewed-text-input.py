#!/usr/bin/env python3
"""Run explicit selected/shared-text workflows on the dedicated owned emulator.

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

from android_test_cleanup import Cleanup, reviewed_text_fixture_ids
from android_clipboard_recovery import ClipboardRecovery


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--avd', required=True)
    parser.add_argument('--layout', choices=['tablet', 'phone'], required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--test-apk', type=Path, required=True)
    parser.add_argument('--json-archive', type=Path, required=True)
    parser.add_argument('--classes', default='dev.tinycast.android.ReviewedTextDeviceTest')
    parser.add_argument('--expected', type=int, default=9)
    parser.add_argument('--font-scale', type=float, default=1)
    parser.add_argument('--inject-clipboard-restore-failure', action='store_true',
                        help='Negative control only: retain the lease after an intentional process-death probe')
    args = parser.parse_args()
    require(args.serial.startswith('emulator-') and args.avd == 'Tinycast_Tablet_API_36', 'Use only the dedicated emulator')
    require(1 <= args.expected <= 24 and 1 <= args.font_scale <= 2, 'Invalid cohort size or font scale')
    require(re.fullmatch(r'[A-Za-z0-9_.,#]+', args.classes) is not None and all(
        item.split('#')[0] in ['dev.tinycast.android.ReviewedTextDeviceTest', 'dev.tinycast.android.ReviewedTextLifecycleDeviceTest',
                              'dev.tinycast.android.ReviewedTextClipboardCrashDeviceTest']
        for item in args.classes.split(',')), 'Only the owned reviewed-text tests are allowed')
    require(not args.inject_clipboard_restore_failure or args.classes == 'dev.tinycast.android.ReviewedTextClipboardCrashDeviceTest',
            'Restoration failure is limited to the deliberate process-death control')
    require(args.apk.name != args.test_apk.name, 'APK archive names must differ')
    archive = args.json_archive.read_bytes()
    require(sha(archive) == 'fc420406a59251daee0c9af32e216026724631480b3a4e2f5d65d0c507b09a58', 'Frozen JSON archive changed')
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
        result = subprocess.run(adb + ['shell', 'run-as', app, 'test', '-e', path], capture_output=True)
        require(result.returncode in [0, 1], 'Cannot determine private path ownership')
        return result.returncode == 1

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
    args.output.mkdir(parents=True, exist_ok=False, mode=0o700)
    shutil.copyfile(__file__, args.output / 'runner.py')
    helper = Path(__file__).with_name('android_test_cleanup.py')
    shutil.copyfile(helper, args.output / 'cleanup-helper.py')
    clipboard_helper = Path(__file__).with_name('android_clipboard_recovery.py')
    shutil.copyfile(clipboard_helper, args.output / 'clipboard-helper.py')
    record = {'completed': False, 'pid': os.getpid(), 'serial': args.serial, 'avd': args.avd, 'layout': args.layout,
              'font_scale': args.font_scale, 'classes': args.classes, 'expected': args.expected, 'owner_nonce': owner,
              'runner_sha256': sha(Path(__file__).read_bytes()), 'archive_sha256': sha(archive),
              'cleanup_helper_sha256': sha(helper.read_bytes()),
              'clipboard_helper_sha256': sha(clipboard_helper.read_bytes()),
              'original_size': size, 'original_density': density, 'original_font_scale': font, 'settings_before': secure,
              'recovery_lease': root}

    def save():
        (args.output / 'record.json').write_text(json.dumps(record, indent=2) + '\n')

    save()
    raw('shell', 'run-as', app, 'mkdir', root)  # Atomic claim, never mkdir -p.
    backups = {}
    evidence_owned = False
    clipboard_recovery = ClipboardRecovery(raw, args.output, owner, app + '.test', record)
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
        raw('shell', 'run-as', app, 'tee', root + '/json-format.zip', data=archive)
        if args.layout == 'phone':
            raw('shell', 'wm', 'size', '1080x2340'); raw('shell', 'wm', 'density', '420')
        raw('shell', 'settings', 'put', 'system', 'font_scale', str(args.font_scale))
        raw('shell', 'am', 'start', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
        require(absent('files/reviewed-text-evidence'), 'Retain or archive an earlier text evidence directory first')
        evidence_owned = True
        save()
        clipboard_recovery.allow_mutations()
        save()
        command = adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'dedicatedAvd', args.avd,
                         '-e', 'reviewedTextFixture', 'true', '-e', 'expectedTextInputLeaseOwner', owner,
                         '-e', 'expectedClipboardRecoveryOwner', owner,
                         '-e', 'class', args.classes, app + '.test/androidx.test.runner.AndroidJUnitRunner']
        with (args.output / 'tests.log').open('wb') as log:
            process = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=max(600, args.expected * 90))
        record['instrumentation_exit_code'] = process.returncode
        log = (args.output / 'tests.log').read_text()
        record['passes'] = len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: 0$', log, re.M))
        record['tests_completed'] = process.returncode == 0 and record['passes'] == args.expected and f'OK ({args.expected} test' in log and 'FAILURES!!!' not in log
        if not absent('files/reviewed-text-evidence'):
            destination = args.output / 'captures'; destination.mkdir()
            for name in text('shell', 'run-as', app, 'ls', 'files/reviewed-text-evidence').splitlines():
                require(re.fullmatch(r'[a-z0-9-]+\.(png|xml|json)', name), 'Unexpected evidence path')
                data = raw('exec-out', 'run-as', app, 'cat', 'files/reviewed-text-evidence/' + name)
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
                if absent('files/reviewed-text-evidence'):
                    return
                destination = args.output / 'captures'
                for name in text('shell', 'run-as', app, 'ls', 'files/reviewed-text-evidence').splitlines():
                    require(re.fullmatch(r'[a-z0-9-]+\.(png|xml|json)', name), 'Unexpected evidence path')
                    cleanup.require((destination / name).is_file(), 'Retain uncopied failure evidence')
                    cleanup.require((destination / name).read_bytes() == raw('exec-out', 'run-as', app, 'cat', 'files/reviewed-text-evidence/' + name), 'Evidence copy differs')
                raw('shell', 'run-as', app, 'rm', '-rf', 'files/reviewed-text-evidence')
            cleanup.attempt('copied-evidence', remove_copied_evidence)
        for name, data in backups.items():
            cleanup.attempt(name + '-backup', lambda name=name: raw('shell', 'run-as', app, 'rm', '-f', 'shared_prefs/' + name + '.xml.bak'))
            cleanup.attempt(name + '-preferences', lambda name=name, data=data: raw('shell', 'run-as', app, 'tee', 'shared_prefs/' + name + '.xml', data=data))
            cleanup.attempt(name + '-verified', lambda name=name, data=data: cleanup.require(raw('exec-out', 'run-as', app, 'cat', 'shared_prefs/' + name + '.xml') == data, 'Preferences differ'))
        cleanup.attempt('font-scale', lambda: raw('shell', 'settings', 'delete', 'system', 'font_scale') if font == 'null' else raw('shell', 'settings', 'put', 'system', 'font_scale', font))
        for key, value in secure.items():
            cleanup.attempt('secure-' + key, lambda key=key, value=value: raw('shell', 'settings', 'delete', 'secure', key) if value == 'null' else raw('shell', 'settings', 'put', 'secure', key, value))
        if args.layout == 'phone':
            cleanup.attempt('size', lambda: raw('shell', 'wm', 'size', 'reset'))
            cleanup.attempt('density', lambda: raw('shell', 'wm', 'density', 'reset'))

        def verify():
            cleanup.require(text('shell', 'wm', 'size') == size and text('shell', 'wm', 'density') == density, 'Geometry differs')
            cleanup.require(text('shell', 'settings', 'get', 'system', 'font_scale') == font, 'Font differs')
            cleanup.require({key: text('shell', 'settings', 'get', 'secure', key) for key in keys} == secure, 'Secure settings differ')
            cleanup.require(text('shell', 'id').startswith('uid=2000('), 'adbd UID changed')
            cleanup.require(not text('shell', 'run-as', app, 'id').startswith(('uid=0', 'uid=1000(', 'uid=2000(')), 'App UID is privileged')
        cleanup.attempt('verify-state-and-unprivileged-identity', verify)

        def release():
            cleanup.require(not cleanup.errors, 'Retain recovery ownership after restoration failure')
            reviewed_text_fixture_ids(json.loads(raw('exec-out', 'run-as', app, 'cat', lease_path)), owner)
            clipboard_recovery.release()
            raw('shell', 'run-as', app, 'rm', '-rf', root)  # Final device operation.
        cleanup.attempt('release-lease', release)
        record['completed'] = record['completed'] and not cleanup.errors
        save(); cleanup.finish()
    print(json.dumps({'completed': record['completed'], 'passes': record.get('passes'), 'cleanup_checks': len(record['cleanup'])}))
    require(record['completed'], 'Workflow assertions failed; inspect the retained tests.log')


if __name__ == '__main__':
    main()
