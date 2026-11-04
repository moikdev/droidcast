from pathlib import Path, PurePosixPath
import hashlib, io, json, os, re, subprocess, tarfile

base = Path('artifacts/menu-readability')
build = json.loads((base / 'build-harness-v18/state.json').read_text())
pilot = json.loads((base / 'candidate-current-caller-diagnostics-landscape100-current-v18/probe.json').read_text())
def require(value, message):
    if not value:
        raise RuntimeError(message)
def sha(data):
    return hashlib.sha256(data).hexdigest()
require(build['completed'] and build['sources_unchanged'] and pilot['completed'], 'Inspect the completed current pilot/build before the matrix')
require(all(sha(Path(p).read_bytes()) == h for p, h in build['source_hashes'].items()), 'Frozen source changed')
root = base / 'matrix-current-v18'; require(not root.exists(), 'Existing matrix: inspect actual handles/receipts before resuming')
adb = ['<android-sdk>/platform-tools/adb', '-s', 'emulator-5580']; app = 'dev.tinycast.android'
def raw(*values):
    return subprocess.check_output(adb + list(values), stderr=subprocess.STDOUT, timeout=60)
def text(*values):
    return raw(*values).decode().strip()
def absent(path):
    r = subprocess.run(adb + ['shell', 'run-as', app, 'test', '-e', path], capture_output=True, timeout=30)
    require(r.returncode in [0, 1] and not r.stdout and not r.stderr, 'Cannot establish private path state')
    return r.returncode == 1
def idle():
    require(text('shell', 'getprop', 'ro.boot.qemu.avd_name') == 'Tinycast_Tablet_API_36', 'Wrong AVD')
    require(text('shell', 'id').startswith('uid=2000('), 'Privileged adbd')
    require('ActiveInstrumentation{' not in text('shell', 'dumpsys', 'activity', 'processes'), 'Instrumentation is live')
    for path in ['files/reviewed-text-fixture-lease', 'files/empty-view-fixture-lease', 'files/compatibility-batch-fixtures', 'files/compact-menu-evidence-preservation-v1']:
        require(absent(path), 'Recovery ownership remains')
def durable_fingerprint():
    roots = [p for p in ['files/extensions', 'files/extension-data', 'shared_prefs'] if not absent(p)]
    require('shared_prefs' in roots, 'Missing preferences root')
    for p in roots:
        require(not text('shell', 'run-as', app, 'find', p, '-type', 'l'), 'Do not fingerprint symlinked data')
    data = raw('exec-out', 'run-as', app, 'tar', '-cf', '-', *roots)
    hashes = {}
    with tarfile.open(fileobj=io.BytesIO(data), mode='r:') as archive:
        for member in archive:
            p = PurePosixPath(member.name)
            require(not p.is_absolute() and '..' not in p.parts, 'Unexpected durable-data member')
            if member.isdir():
                continue
            require(member.isfile() and any(member.name.startswith(root + '/') for root in roots), 'Unexpected durable-data namespace/type')
            require(member.name not in hashes, 'Duplicate durable-data member')
            hashes[member.name] = sha(archive.extractfile(member).read())
    return dict(roots=roots, hashes=hashes, boot_id=text('shell', 'cat', '/proc/sys/kernel/random/boot_id'))

retained_build = json.loads((base / 'build-harness-v10/state.json').read_text())
form_source = 'app/src/androidTest/java/dev/tinycast/android/ExtensionFormDeviceTest.kt'
require(retained_build['source_hashes'][form_source] == build['source_hashes'][form_source], 'Retained Form source changed')
retained = []
for font in [1, 2]:
    for layout in ['landscape', 'phone', 'tablet']:
        folder = base / f'candidate-current-extension-menus-{layout}{font*100}-current-v10'
        host = json.loads((folder / 'cohort/record.json').read_text()); outer = json.loads((folder / 'probe.json').read_text())
        require(host['completed'] and host['passes'] == 3 and host['apk_sha256'] == build['app_sha256'] and host['test_apk_sha256'] == retained_build['test_apk_sha256'] and all(v['passed'] for v in host['cleanup'].values()), 'Retained Form cohort incomplete')
        require(outer['completed'] and outer['preservation_owner_released'] and outer['original_evidence_restored_and_verified'], 'Retained Form evidence unrestored')
        retained.append(dict(output=str(folder), passes=3, cleanup_checks=len(host['cleanup']), app_sha256=host['apk_sha256'], test_apk_sha256=host['test_apk_sha256'], layout=layout, font_scale=font, form_source_sha256=build['source_hashes'][form_source]))
require(sum(c['cleanup_checks'] for c in retained) == 116, 'Retained Form accounting differs')
idle(); root.mkdir(mode=0o700)
before = durable_fingerprint()
p = root / 'private-durable-before.json'; p.write_text(json.dumps(before, indent=2) + '\n'); p.chmod(0o600)
state = dict(completed=False, pid=os.getpid(), production_source_commit=build['production_source_commit'],
    test_source_commit=build['test_source_commit'], source_hashes=build['source_hashes'],
    app_sha256=build['app_sha256'], test_apk_sha256=build['test_apk_sha256'], cohorts=[],
    retained_extension_menu_cohorts=retained, retained_extension_menu_executions=18, retained_extension_menu_cleanup_checks=116,
    durable_file_count=len(before['hashes']), durable_roots=before['roots'],
    scope='Two touch flows on all three layouts at 1/1.3/1.5/2 font scale, plus all13 Inline methods at 100%. Six completed unchanged-source Form menu cohorts retain their original v10 instrumentation identity; final PR/audits/CI remain separate gates.')
def save():
    (root / 'state.json').write_text(json.dumps(state, indent=2) + '\n')
save()
profiles = [('landscape', 1), ('landscape', 2), ('phone', 2), ('tablet', 2), ('phone', 1), ('tablet', 1)]
profiles += [(layout, font) for font in [1.3, 1.5] for layout in ['landscape', 'phone', 'tablet']]
groups = [('inline', layout, 1, 13) for layout in ['landscape', 'phone', 'tablet']]
groups += [('touch', layout, font, 2) for layout, font in profiles]
error = None
try:
    for case, layout, font, expected in groups:
        idle()
        require(all(sha(Path(p).read_bytes()) == h for p, h in build['source_hashes'].items()), 'Frozen source changed')
        out = base / f'candidate-current-{case}-{layout}{round(font * 100)}-current-v18'
        require(not out.exists(), 'Cohort already exists; do not repeat it')
        command = ['python3', str(base / 'run-candidate-probe-v18.py'), '--candidate', 'current', '--case', case,
                   '--layout', layout, '--font-scale', str(font)]
        cohort = dict(case=case, layout=layout, font_scale=font, expected=expected, output=str(out), completed=False, command=command)
        state['cohorts'].append(cohort); save(); print(json.dumps({'running': out.name, 'expected': expected}), flush=True)
        with (root / (out.name + '.log')).open('x') as log:
            cohort['exit_code'] = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT).returncode
        save()
        require((out / 'cohort/record.json').is_file(), 'Inspect failed preflight before repeating any native action')
        host = json.loads((out / 'cohort/record.json').read_text()); outer = json.loads((out / 'probe.json').read_text())
        log = (out / 'cohort/tests.log').read_text()
        codes = [int(v) for v in re.findall(r'^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)$', log, re.M)]
        cohort.update(junit_passes=host.get('passes'), cleanup_checks=len(host.get('cleanup', {})),
                      failed_cleanup=[k for k, v in host.get('cleanup', {}).items() if not v['passed']])
        save()
        require(cohort['exit_code'] == 0 and host['completed'] and host['installed_apks_match'] and host['passes'] == expected,
                'Native cohort failed; retain its original result and inspect recovery ownership')
        require(codes.count(0) == expected and not any(code < 0 for code in codes), 'Incomplete/failed/assumption cohort')
        require(host['apk_sha256'] == build['app_sha256'] and host['test_apk_sha256'] == build['test_apk_sha256'], 'Native APK identities differ')
        required_cleanup = {'stop-app', 'stop-owned-caller', 'clipboard-restored-and-verified-independently',
            'stop-clipboard-recovery-activity', 'owned-fixture-data', 'copied-evidence', 'legacy-fixtures-completed',
            'palette-backup', 'palette-preferences', 'palette-verified', 'emoji-backup', 'emoji-preferences', 'emoji-verified',
            'font-scale', 'secure-enabled_accessibility_services', 'secure-accessibility_enabled', 'secure-show_ime_with_hard_keyboard',
            'verify-state-and-unprivileged-identity', 'release-lease'}
        if layout != 'tablet': required_cleanup.update({'size', 'density'})
        require(not cohort['failed_cleanup'] and set(host['cleanup']) == required_cleanup, 'Host cleanup profile differs or a check failed')
        require(outer['original_evidence_restored_and_verified'] and outer['preservation_owner_released'] and len(outer['original_hashes']) == 136,
                'Pre-existing evidence restoration incomplete')
        for filename in ['menu-category-touch-record.json', 'menu-actions-touch-record.json']:
            record = json.loads((out / 'cohort/extension-grid-evidence' / filename).read_text())
            require(record['passed'] and record['installed_build_commit'] == build['production_source_commit'] and
                    abs(record['font_scale'] - font) < .001 and len(record['observations']) == 2 and record['pointer_taps'], 'Incomplete touch-flow receipt')
            if filename.startswith('menu-actions'):
                require(all(r['long_action_natural_lines_and_complete_semantics'] and r['long_action_label'] == 'Set keyboard shortcut' and r['empty_natural_lines_reachable_after_hide'] and r['query_and_caret_preserved'] and
                            r['empty_enter_left_sentinel_unchanged'] and r['exact_fresh_copy_and_caller_range'] and r['caller_query_after_return'] == 'Menu action fixture 5041' for r in record['observations']), 'Touch action contract incomplete')
            else:
                require([r['initial_ime_visible'] for r in record['observations']] == [False, True] and
                        all(r['escape_query_and_range_restored'] and r['category'] == 'Notes' and r['caller_query_after_escape'] == 'Settings' for r in record['observations']), 'Category start-state/return contract incomplete')
        if case in ['touch', 'inline']:
            for name in ['menu-category-touch-record.json', 'menu-actions-touch-record.json']:
                record = json.loads((out / 'cohort/extension-grid-evidence' / name).read_text())
                require(record['launcher_keyboard_setup'] and all(s['settled'] for s in record['launcher_keyboard_setup']), 'Missing native starting-state witness')
        cohort['completed'] = True; save(); print(json.dumps({k: cohort[k] for k in ['case', 'layout', 'font_scale', 'junit_passes', 'cleanup_checks']}), flush=True)
except BaseException as failure:
    error = failure; state['failure_type'] = type(failure).__name__; save()
finally:
    try:
        after = durable_fingerprint()
        p = root / 'private-durable-after.json'; p.write_text(json.dumps(after, indent=2) + '\n'); p.chmod(0o600)
        state['same_boot'] = before['boot_id'] == after['boot_id']
        state['durable_data_unchanged'] = before['roots'] == after['roots'] and before['hashes'] == after['hashes']
    except BaseException as failure:
        state['durable_verification_failure_type'] = type(failure).__name__
    save()
if error is not None:
    raise error
require(len(state['cohorts']) == 15 and all(c['completed'] for c in state['cohorts']), 'Incomplete matrix')
require(state.get('same_boot') is True and state.get('durable_data_unchanged') is True, 'Durable-data preservation is not established')
state.update(completed=True, native_executions=sum(c['junit_passes'] for c in state['cohorts']),
             cleanup_checks=sum(c['cleanup_checks'] for c in state['cohorts']))
require(state['native_executions'] == 63 and state['cleanup_checks'] == 305, 'Matrix accounting differs')
save(); print(json.dumps({k: state[k] for k in ['completed', 'native_executions', 'cleanup_checks', 'durable_data_unchanged', 'same_boot']}), flush=True)
