#!/usr/bin/env python3
"""Collect isolated real-bundle workflow observations on the owned emulator.

A passing instrumentation collector means its observations and cleanup were
collected. Compatibility outcomes are reported separately for each workflow.
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

from android_test_cleanup import Cleanup
from extension_bundle_snapshot import execution_fixture
from extension_manifest_catalog import require


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--avd', required=True)
    parser.add_argument('--layout', required=True, choices=['tablet', 'phone'])
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--bundle-snapshot', required=True, type=Path)
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--test-apk', required=True, type=Path)
    args = parser.parse_args()
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        sdk = next(line.removeprefix('sdk.dir=') for line in Path('local.properties').read_text().splitlines() if line.startswith('sdk.dir='))
    adb = [str(Path(sdk) / 'platform-tools/adb'), '-s', args.serial]
    app = 'dev.tinycast.android'

    def raw(*values, data=None):
        return subprocess.check_output(adb + list(values), input=data, stderr=subprocess.STDOUT, timeout=60)

    def text(*values):
        return raw(*values).decode().strip()

    def absent(path, private=False):
        prefix = ['shell', 'run-as', app] if private else ['shell']
        return subprocess.run(adb + prefix + ['test', '-e', path], capture_output=True).returncode != 0

    require(args.serial.startswith('emulator-') and args.avd == 'Tinycast_Tablet_API_36', 'Only the dedicated emulator is supported')
    require(text('shell', 'getprop', 'ro.kernel.qemu') == '1', 'Refuse a physical device')
    require(text('shell', 'getprop', 'ro.boot.qemu.avd_name') == args.avd, 'Emulator identity changed')
    require(text('shell', 'id').startswith('uid=2000('), 'adbd must be unprivileged')
    require('ActiveInstrumentation{' not in text('shell', 'dumpsys', 'activity', 'processes'), 'Another instrumentation owns the emulator')
    original_size, original_density = text('shell', 'wm', 'size'), text('shell', 'wm', 'density')
    require('Override' not in original_size + original_density, 'Restore an existing display override before this run')
    keys = ['enabled_accessibility_services', 'accessibility_enabled', 'show_ime_with_hard_keyboard']
    secure = {key: text('shell', 'settings', 'get', 'secure', key) for key in keys}
    require(secure['enabled_accessibility_services'] == 'null' and secure['accessibility_enabled'] == '0', 'Refuse active accessibility services')
    font = text('shell', 'settings', 'get', 'system', 'font_scale')
    fixture, archives = execution_fixture(args.bundle_snapshot)
    fixture_bytes = (json.dumps(fixture, indent=2) + '\n').encode()
    expected = {(entry['directory'], command) for entry in fixture['extensions'] for command in entry['planned_commands']}
    root = 'files/compatibility-batch-fixtures'
    upload = '/data/local/tmp/droidcast-bundles-' + uuid.uuid4().hex
    require(absent(root, True) and absent(upload), 'Refuse to overwrite existing workflow fixtures')
    require(args.apk.name != args.test_apk.name, 'App and test APKs need distinct archive filenames')
    args.output.mkdir(parents=True, exist_ok=False, mode=0o700)
    shutil.copyfile(__file__, args.output / 'runner.py')
    raw('shell', 'am', 'force-stop', app)
    backups = {name: raw('exec-out', 'run-as', app, 'cat', 'shared_prefs/' + name + '.xml') for name in ['palette', 'emoji']}
    for name, data in backups.items():
        path = args.output / ('private-' + name + '-backup.xml')
        path.write_bytes(data)
        path.chmod(0o600)
    record = {'completed': False, 'pid': os.getpid(), 'avd': args.avd, 'serial': args.serial, 'layout': args.layout,
              'runner_sha256': digest(Path(__file__).read_bytes()), 'source_snapshot_sha256': fixture['source_snapshot_sha256'],
              'fixture_sha256': digest(fixture_bytes), 'expected_workflows': len(expected), 'original_size': original_size,
              'original_density': original_density, 'original_font_scale': font, 'settings_before': secure,
              'fixture_upload_directory': upload, 'fixture_directory': root,
              'scope': 'Real prebuilt code in isolated cache/data. Store installation is not exercised. Collector pass is not a compatibility pass.'}

    def save():
        (args.output / 'record.json').write_text(json.dumps(record, indent=2) + '\n')

    save()
    owned = uploaded = False
    try:
        for source, key in [(args.apk, 'app_sha256'), (args.test_apk, 'test_apk_sha256')]:
            frozen = args.output / source.name
            shutil.copyfile(source, frozen)
            record[key] = digest(frozen.read_bytes())
            raw('install', '-r', str(frozen))
        for package, key in [(app, 'app_sha256'), (app + '.test', 'test_apk_sha256')]:
            installed = text('shell', 'pm', 'path', package).removeprefix('package:')
            require(digest(raw('exec-out', 'cat', installed)) == record[key], 'Installed APK bytes differ from the frozen input')
        record['installed_matches'] = True
        raw('shell', 'run-as', app, 'mkdir', root)
        owned = True
        # The owner lease exists before any extension cache/data can be made.
        raw('shell', 'run-as', app, 'tee', root + '/owners.json', data=b'{"owned_extension_ids":[]}\n')
        raw('shell', 'run-as', app, 'mkdir', root + '/archives')
        raw('shell', 'run-as', app, 'tee', root + '/fixture.json', data=fixture_bytes)
        require(text('shell', 'run-as', app, 'sha256sum', root + '/fixture.json').split()[0] == record['fixture_sha256'], 'Uploaded fixture digest mismatch')
        raw('shell', 'mkdir', upload)
        uploaded = True
        frozen_archives = args.output / 'archives'
        frozen_archives.mkdir()
        for entry in fixture['extensions']:
            name = entry['archive_name']
            frozen = frozen_archives / name
            shutil.copyfile(archives[name], frozen)
            require(digest(frozen.read_bytes()) == entry['archive_sha256'], 'Frozen archive changed during staging')
            raw('push', str(frozen), upload + '/' + name)
            raw('shell', 'chmod', '0644', upload + '/' + name)
            raw('shell', 'run-as', app, 'cp', upload + '/' + name, root + '/archives/' + name)
            require(text('shell', 'run-as', app, 'sha256sum', root + '/archives/' + name).split()[0] == entry['archive_sha256'], 'Uploaded archive digest mismatch')
        (args.output / 'fixture.json').write_bytes(fixture_bytes)
        raw('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
        if args.layout == 'phone':
            raw('shell', 'wm', 'size', '1080x2340')
            raw('shell', 'wm', 'density', '420')
        raw('shell', 'run-as', app, 'rm', '-rf', 'files/compatibility-evidence')
        save()
        command = adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'dedicatedAvd', args.avd,
                         '-e', 'expectedBundleFixtureSha256', record['fixture_sha256'],
                         '-e', 'class', 'dev.tinycast.android.FrozenBundleWorkflowDeviceTest',
                         app + '.test/androidx.test.runner.AndroidJUnitRunner']
        with (args.output / 'tests.log').open('xb') as log:
            subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=max(600, len(expected) * 90))
        log = (args.output / 'tests.log').read_text()
        record['collector_test_passed'] = 'OK (1 test)' in log and 'FAILURES!!!' not in log
        evidence = 'files/compatibility-evidence'
        names = text('shell', 'run-as', app, 'ls', evidence).splitlines()
        for name in names:
            require(re.fullmatch(r'[a-z0-9-]+\.(json|png|xml)', name), 'Unsafe evidence filename')
            (args.output / name).write_bytes(raw('exec-out', 'run-as', app, 'cat', evidence + '/' + name))
        observations = json.loads((args.output / 'collection.json').read_text())
        require(observations['completed'] and record['collector_test_passed'], 'Workflow collector did not complete')
        rows = observations['workflows']
        require(len(rows) == len(expected) and {(row['directory'], row['command']) for row in rows} == expected, 'Workflow coverage differs from the named batch')
        allowed = {'untested', 'tested-working-for-listed-workflow', 'tested-partial', 'observed-failed',
                   'platform-unsupported-with-specific-reason', 'requires-user-configuration/authentication'}
        require(all(row['status'] in allowed for row in rows), 'Unknown compatibility status')
        require(observations['fixture_sha256'] == record['fixture_sha256'], 'Collector used another fixture')
        record['workflow_status_counts'] = {status: sum(row['status'] == status for row in rows) for status in sorted({row['status'] for row in rows})}
        record['completed'] = True
    finally:
        cleanup = Cleanup(record)
        cleanup.attempt('stop-app', lambda: raw('shell', 'am', 'force-stop', app))
        if owned:
            def clean_owners():
                lease = json.loads(raw('exec-out', 'run-as', app, 'cat', root + '/owners.json'))
                owners = lease.get('owned_extension_ids')
                require(isinstance(owners, list) and len(owners) <= 100, 'Invalid workflow owner lease')
                require(all(isinstance(owner, str) and re.fullmatch(r'compat-batch-[0-9a-f]{32}', owner) for owner in owners), 'Unsafe workflow owner identity')
                require(len(set(owners)) == len(owners), 'Duplicate workflow owner lease')
                for owner in owners:
                    raw('shell', 'run-as', app, 'rm', '-rf', 'cache/' + owner, 'files/extension-data/' + owner)
                    raw('shell', 'run-as', app, 'rm', '-f', 'shared_prefs/extension-' + owner + '.xml', 'shared_prefs/extension-' + owner + '.xml.bak')
                    cleanup.require(all(absent(path, True) for path in ['cache/' + owner, 'files/extension-data/' + owner,
                        'shared_prefs/extension-' + owner + '.xml', 'shared_prefs/extension-' + owner + '.xml.bak']), 'Isolated extension data removed')
                record['isolated_owners_cleaned'] = len(owners)
            cleanup.attempt('isolated-extension-data', clean_owners)
            # Keep the lease available for recovery if any owner cleanup fails.
            if cleanup.results['isolated-extension-data']['passed']:
                cleanup.attempt('bundle-fixtures', lambda: raw('shell', 'run-as', app, 'rm', '-rf', root))
        if uploaded:
            cleanup.attempt('bundle-upload', lambda: raw('shell', 'rm', '-rf', upload))
        for name, data in backups.items():
            cleanup.attempt(name + '-backup', lambda name=name: raw('shell', 'run-as', app, 'rm', '-f', 'shared_prefs/' + name + '.xml.bak'))
            cleanup.attempt(name + '-preferences', lambda name=name, data=data: raw('shell', 'run-as', app, 'tee', 'shared_prefs/' + name + '.xml', data=data))
        for key, value in secure.items():
            cleanup.attempt('secure-' + key, lambda key=key, value=value: raw('shell', 'settings', 'delete', 'secure', key) if value == 'null' else raw('shell', 'settings', 'put', 'secure', key, value))
        cleanup.attempt('font-scale', lambda: raw('shell', 'settings', 'delete', 'system', 'font_scale') if font == 'null' else raw('shell', 'settings', 'put', 'system', 'font_scale', font))
        if args.layout == 'phone':
            cleanup.attempt('size', lambda: raw('shell', 'wm', 'size', 'reset'))
            cleanup.attempt('density', lambda: raw('shell', 'wm', 'density', 'reset'))

        def verify():
            for name, data in backups.items():
                cleanup.require(raw('exec-out', 'run-as', app, 'cat', 'shared_prefs/' + name + '.xml') == data, name + ' restored')
            cleanup.require({key: text('shell', 'settings', 'get', 'secure', key) for key in keys} == secure, 'secure settings restored')
            cleanup.require(text('shell', 'settings', 'get', 'system', 'font_scale') == font, 'font restored')
            cleanup.require(text('shell', 'wm', 'size') == original_size and text('shell', 'wm', 'density') == original_density, 'geometry restored')
            cleanup.require(absent(root, True) and absent(upload), 'owned fixtures removed')
            cleanup.require(text('shell', 'id').startswith('uid=2000('), 'adbd unprivileged')
            uid = text('shell', 'run-as', app, 'id')
            cleanup.require(not uid.startswith(('uid=0(', 'uid=1000(', 'uid=2000(')), 'ordinary app UID')
        cleanup.attempt('verify-restoration', verify)
        record['completed'] = record['completed'] and not cleanup.errors
        save()
        cleanup.finish()
    print(json.dumps({'collection_complete': record['completed'], 'workflow_status_counts': record['workflow_status_counts']}))


if __name__ == '__main__':
    main()
