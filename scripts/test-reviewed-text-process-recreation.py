#!/usr/bin/env python3
"""Verify saved-task restoration after real process death on the owned API-36 tablet.

No app instrumentation runs during death/restoration. Shell briefly locks the
existing task to bring it forward without delivering another intent, then exits
lock mode before observations. The app keeps its ordinary UID and permissions.
"""
from pathlib import Path
import argparse
import hashlib
import io
import json
import os
import re
import struct
import tarfile
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

from android_test_cleanup import Cleanup, reviewed_text_fixture_ids


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--avd', required=True)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    require(args.serial.startswith('emulator-') and args.avd == 'Tinycast_Tablet_API_36', 'Use only the dedicated emulator')
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        sdk = next(line.removeprefix('sdk.dir=') for line in Path('local.properties').read_text().splitlines() if line.startswith('sdk.dir='))
    adb = [str(Path(sdk) / 'platform-tools/adb'), '-s', args.serial]
    app = 'dev.tinycast.android'
    lease_root = 'files/reviewed-text-fixture-lease'
    owner = uuid.uuid4().hex
    identity = 'reviewed-text-' + uuid.uuid4().hex
    xml_path = '/data/local/tmp/droidcast-reviewed-' + owner + '.xml'

    def raw(*values, data=None):
        return subprocess.check_output(adb + list(values), input=data, stderr=subprocess.STDOUT, timeout=60)

    def text(*values):
        return raw(*values).decode().strip()

    def absent(path):
        value = subprocess.run(adb + ['shell', 'run-as', app, 'test', '-e', path], capture_output=True)
        require(value.returncode in [0, 1], 'Private file ownership cannot be determined')
        return value.returncode == 1

    def pid():
        value = subprocess.run(adb + ['shell', 'pidof', app], capture_output=True)
        require(value.returncode in [0, 1], 'App process state cannot be determined')
        pids = value.stdout.decode().split()
        require(len(pids) <= 1, 'Expected one target app process')
        return pids[0] if pids else None

    def lock_mode():
        value = text('shell', 'dumpsys', 'activity', 'activities')
        found = re.search(r'mLockTaskModeState=(\w+)', value)
        require(found is not None, 'Task-lock state is unavailable')
        return found.group(1)

    def wait(predicate, message, seconds=20):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(.15)
        raise ValueError(message)

    require(text('shell', 'getprop', 'ro.kernel.qemu') == '1' and text('shell', 'getprop', 'ro.boot.qemu.avd_name') == args.avd, 'Wrong emulator')
    require(text('shell', 'getprop', 'ro.build.version.sdk') == '36', 'This saved-task probe is pinned to API 36')
    require(text('shell', 'id').startswith('uid=2000('), 'adbd must be unprivileged')
    require('ActiveInstrumentation{' not in text('shell', 'dumpsys', 'activity', 'processes'), 'Another instrumentation is active')
    size, density = text('shell', 'wm', 'size'), text('shell', 'wm', 'density')
    require(size == 'Physical size: 1920x1200' and density == 'Physical density: 240', 'Use the original full tablet profile')
    font = text('shell', 'settings', 'get', 'system', 'font_scale')
    require(font == 'null' or float(font) == 1, 'Use the normal-font tablet profile')
    require(lock_mode() == 'NONE', 'Refuse an existing task-lock owner')
    keys = ['enabled_accessibility_services', 'accessibility_enabled', 'show_ime_with_hard_keyboard']
    secure = {key: text('shell', 'settings', 'get', 'secure', key) for key in keys}
    require(secure['enabled_accessibility_services'] == 'null' and secure['accessibility_enabled'] == '0', 'Refuse active accessibility services')
    for path in [lease_root, 'files/empty-view-fixture-lease', 'files/compatibility-batch-fixtures']:
        require(absent(path), 'An earlier test owner remains')
    require(subprocess.run(adb + ['shell', 'test', '-e', xml_path], capture_output=True).returncode == 1, 'An earlier UI-dump owner remains')
    build = json.loads((args.candidate / 'identity.json').read_text())
    for package, filename in [(app, 'app.apk'), (app + '.test', 'test.apk')]:
        expected = sha((args.candidate / filename).read_bytes())
        require(build[filename + '_sha256'] == expected, 'Candidate identity differs')
        path = text('shell', 'pm', 'path', package).removeprefix('package:')
        require(sha(raw('exec-out', 'cat', path)) == expected, 'Install the frozen APK pair before this probe')
    args.output.mkdir(parents=True, exist_ok=False, mode=0o700)
    record = {'completed': False, 'pid': os.getpid(), 'serial': args.serial, 'avd': args.avd,
              'source_commit': build['source_commit'], 'apk_sha256': build['app.apk_sha256'], 'test_apk_sha256': build['test.apk_sha256'],
              'runner_sha256': sha(Path(__file__).read_bytes()), 'owner_nonce': owner, 'owned_extension_id': identity,
              'scope': 'Actual pending/approved process death and restoration of the same saved Android task; owned probe, not a Store workflow', 'phases': []}

    def save():
        (args.output / 'record.json').write_text(json.dumps(record, indent=2) + '\n')

    def hierarchy():
        raw('shell', 'uiautomator', 'dump', xml_path)
        return raw('exec-out', 'cat', xml_path)

    def nodes():
        return list(ET.fromstring(hierarchy()).iter('node'))

    def one(resource, package=app):
        found = [node for node in nodes() if node.get('resource-id') == resource and node.get('package') == package]
        require(len(found) == 1, 'Expected one exact native UI target: ' + resource)
        return found[0]

    def tap(resource, package=app):
        node = one(resource, package)
        require(node.get('enabled') == 'true' and (node.get('clickable') == 'true' or node.get('focusable') == 'true'), 'UI target is not actionable')
        bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
        require(bounds is not None, 'Invalid native bounds')
        left, top, right, bottom = map(int, bounds.groups())
        require(0 <= left < right <= 1920 and 0 <= top < bottom <= 1200, 'Target is outside the tablet viewport')
        raw('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))

    def capture(name):
        data = hierarchy(); png = raw('exec-out', 'screencap', '-p')
        require(png[:8] == b'\x89PNG\r\n\x1a\n' and struct.unpack('>II', png[16:24]) == (1920, 1200), 'Invalid screenshot')
        (args.output / (name + '.xml')).write_bytes(data); (args.output / (name + '.png')).write_bytes(png)
        (args.output / (name + '-frame.json')).write_text(json.dumps({'scope': record['scope'], 'app_pid': pid(), 'lock_mode': lock_mode(),
             'xml_sha256': sha(data), 'png_sha256': sha(png), 'synchronization': 'Native hierarchy before screenshot, task lock already released'}, indent=2) + '\n')

    def run_count():
        path = 'shared_prefs/extension-' + identity + '.xml'
        if absent(path):
            return 0
        values = ET.fromstring(raw('exec-out', 'run-as', app, 'cat', path))
        local = next((node.text for node in values.findall('string') if node.get('name') == 'local'), '{}')
        return json.loads(local or '{}').get('runs', 0)

    def preserved_data():
        result = {}
        for directory in ['files/extensions', 'files/extension-data', 'shared_prefs']:
            if absent(directory):
                continue
            archive = raw('exec-out', 'run-as', app, 'tar', '-cf', '-', directory)
            with tarfile.open(fileobj=io.BytesIO(archive), mode='r:') as tar:
                for member in tar.getmembers():
                    parts = Path(member.name).parts
                    if identity in parts or Path(member.name).name in ['extension-' + identity + '.xml', 'extension-' + identity + '.xml.bak']:
                        continue
                    if directory == 'shared_prefs' and not Path(member.name).name.startswith('extension-'):
                        continue
                    result[member.name] = sha(tar.extractfile(member).read()) if member.isfile() else member.type.decode('ascii')
        return result

    save()
    raw('shell', 'run-as', app, 'mkdir', lease_root)
    backups = {}
    original_data = None
    try:
        lease = {'schema_version': 1, 'fixture_kind': 'reviewed-text-process-recreation-v1', 'owner_nonce': owner, 'owned_extension_ids': [identity]}
        raw('shell', 'run-as', app, 'tee', lease_root + '/owners.json', data=(json.dumps(lease) + '\n').encode())
        raw('shell', 'am', 'force-stop', app)
        original_data = preserved_data()
        record['original_installation_data_fingerprint_sha256'] = sha(json.dumps(original_data, sort_keys=True).encode())
        for name in ['palette', 'emoji']:
            backups[name] = raw('exec-out', 'run-as', app, 'cat', 'shared_prefs/' + name + '.xml')
            backup = args.output / ('private-' + name + '-backup.xml'); backup.write_bytes(backups[name]); backup.chmod(0o600)
        package = 'files/extensions/' + identity
        for path in [package, 'files/extension-data/' + identity, 'shared_prefs/extension-' + identity + '.xml']:
            require(absent(path), 'Owned namespace already exists')
        raw('shell', 'run-as', app, 'mkdir', package)
        listing = {'id': identity, 'name': 'owned-process-input', 'title': 'OwnedProcessInspector', 'description': 'Owned lifecycle probe',
                   'author': 'Droidcast tests', 'handle': 'test', 'download_url': '', 'source_url': '', 'commit_sha': '', 'command_count': 1}
        manifest = {'name': 'owned-process-input', 'commands': [{'name': 'inspect', 'title': 'InspectProcessInput', 'mode': 'view'}]}
        script = """const React=require('react'),api=require('@raycast/api');
module.exports.default=function(){const [message,setMessage]=React.useState('Waiting for owned input');
React.useEffect(()=>{(async()=>{const count=Number(await api.LocalStorage.getItem('runs')||0);await api.LocalStorage.setItem('runs',count+1);
const text=await api.getSelectedText();setMessage('Observed input: '+text)})().catch(e=>setMessage('Owned error: '+e.message))},[]);
return React.createElement(api.Detail,{markdown:message});};"""
        for name, data in [('.tinycast-store.json', json.dumps(listing)), ('package.json', json.dumps(manifest)), ('inspect.js', script)]:
            raw('shell', 'run-as', app, 'tee', package + '/' + name, data=data.encode())
        record['probe_sha256'] = sha(script.encode()); save()
        for phase in ['pending', 'approved']:
            raw('shell', 'am', 'force-stop', app); raw('shell', 'am', 'force-stop', app + '.test')
            payload = 'OwnedProcessRecreationInput-' + phase + '-' + owner[:12]
            raw('shell', 'am', 'start', '-n', app + '.test/dev.tinycast.android.ReviewedTextCallerActivity',
                '--es', 'source', payload, '--ei', 'selectionStart', '0', '--ei', 'selectionEnd', str(len(payload)), '--ez', 'readOnly', 'true')
            tap('android:id/button1', app + '.test')
            wait(lambda: any(node.get('resource-id') == 'text-input-preview' for node in nodes()), 'The external text preview did not open')
            if phase == 'approved':
                tap('text-input-choose'); tap('inline-menu-search'); raw('shell', 'input', 'text', 'InspectProcessInput')
                tap('text-command-' + identity + '/inspect'); tap('text-input-run')
                wait(lambda: any('Observed input: ' + payload in node.get('text', '') for node in nodes()), 'The owned command did not receive the approved input')
            expected_count = 1 if phase == 'approved' else 0
            wait(lambda: run_count() == expected_count, 'Unexpected command execution count')
            capture(phase + '-before-death')
            before = pid(); require(before is not None, 'The app process is absent before death')
            activities = text('shell', 'dumpsys', 'activity', 'activities')
            task_ids = re.findall(r'(?:mResumedActivity:|topResumedActivity=)\s*ActivityRecord\{[^}]*dev\.tinycast\.android/\.MainActivity\s+t(\d+)', activities)
            require(len(set(task_ids)) == 1, 'Cannot identify the exact resumed target task')
            task_id = task_ids[0]
            raw('shell', 'input', 'keyevent', 'KEYCODE_HOME')
            raw('shell', 'am', 'kill', app)
            wait(lambda: pid() is None, 'The target process was not killed')
            # Bring forward the same saved task without a new incoming intent.
            try:
                raw('shell', 'am', 'task', 'lock', task_id)
                wait(lambda: pid() is not None and pid() != before, 'The task did not create a fresh app process')
                wait(lambda: any(node.get('resource-id') == 'text-input-error' and 'review has ended' in node.get('text', '') for node in nodes()), 'Saved-task restoration did not expire the input')
            finally:
                raw('shell', 'am', 'task', 'lock', 'stop')
            require(lock_mode() == 'NONE', 'Task lock was not restored')
            after = pid(); require(after is not None and after != before, 'The process identity did not change')
            observed = nodes()
            require(not any(node.get('resource-id') == 'text-input-preview' or payload in node.get('text', '') for node in observed if node.get('package') == app), 'Restored app exposed the old input')
            require(run_count() == expected_count, 'The restored command silently ran again')
            capture(phase + '-after-death')
            tap('text-input-cancel')
            require(one('android:id/edit', app + '.test').get('text') == payload, 'The source changed after return')
            capture(phase + '-source-return')
            record['phases'].append({'phase': phase, 'old_pid': before, 'new_pid': after, 'same_saved_task_id': task_id,
                                     'no_new_intent_for_restoration': True, 'expired_without_input_or_replay': True,
                                     'run_count_before_and_after': expected_count, 'source_unchanged': True})
            save()
        record['completed'] = True
    finally:
        cleanup = Cleanup(record)
        cleanup.attempt('exit-owned-task-lock', lambda: raw('shell', 'am', 'task', 'lock', 'stop'))
        cleanup.attempt('stop-app', lambda: raw('shell', 'am', 'force-stop', app))
        cleanup.attempt('stop-owned-caller', lambda: raw('shell', 'am', 'force-stop', app + '.test'))

        def owned_data():
            lease_bytes = raw('exec-out', 'run-as', app, 'cat', lease_root + '/owners.json')
            identities = reviewed_text_fixture_ids(json.loads(lease_bytes), owner, process_recreation=True)
            (args.output / 'owner-lease.json').write_bytes(lease_bytes)
            for item in identities:
                paths = ['files/extensions/' + item, 'files/extension-data/' + item, 'shared_prefs/extension-' + item + '.xml', 'shared_prefs/extension-' + item + '.xml.bak']
                raw('shell', 'run-as', app, 'rm', '-rf', *paths)
                for path in paths: cleanup.require(absent(path), 'Owned fixture remains')
        cleanup.attempt('owned-fixture-data', owned_data)
        cleanup.attempt('owned-ui-dump', lambda: raw('shell', 'rm', '-f', xml_path))
        for name, data in backups.items():
            cleanup.attempt(name + '-backup', lambda name=name: raw('shell', 'run-as', app, 'rm', '-f', 'shared_prefs/' + name + '.xml.bak'))
            cleanup.attempt(name + '-preferences', lambda name=name, data=data: raw('shell', 'run-as', app, 'tee', 'shared_prefs/' + name + '.xml', data=data))
            cleanup.attempt(name + '-verified', lambda name=name, data=data: cleanup.require(raw('exec-out', 'run-as', app, 'cat', 'shared_prefs/' + name + '.xml') == data, 'Preferences differ'))

        def verify():
            cleanup.require(original_data is not None and preserved_data() == original_data, 'Preexisting extension packages, data or preferences differ')
            cleanup.require(lock_mode() == 'NONE', 'Task lock remains')
            cleanup.require(text('shell', 'wm', 'size') == size and text('shell', 'wm', 'density') == density, 'Geometry changed')
            cleanup.require(text('shell', 'settings', 'get', 'system', 'font_scale') == font, 'Font changed')
            cleanup.require({key: text('shell', 'settings', 'get', 'secure', key) for key in keys} == secure, 'Secure settings changed')
            cleanup.require(text('shell', 'id').startswith('uid=2000('), 'adbd became privileged')
            cleanup.require(not text('shell', 'run-as', app, 'id').startswith(('uid=0', 'uid=1000(', 'uid=2000(')), 'App became privileged')
        cleanup.attempt('verify-state-and-unprivileged-identity', verify)

        def release():
            cleanup.require(not cleanup.errors, 'Retain recovery ownership after restoration failure')
            reviewed_text_fixture_ids(json.loads(raw('exec-out', 'run-as', app, 'cat', lease_root + '/owners.json')), owner, process_recreation=True)
            raw('shell', 'run-as', app, 'rm', '-rf', lease_root)
        cleanup.attempt('release-lease', release)
        record['completed'] = record['completed'] and not cleanup.errors
        save(); cleanup.finish()
    print(json.dumps({'completed': record['completed'], 'phases': len(record['phases']), 'cleanup_checks': len(record['cleanup'])}))


if __name__ == '__main__':
    main()
