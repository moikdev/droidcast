from pathlib import Path
import hashlib, json, os, subprocess, sys

base = Path('artifacts/form-pickers')
group, build_name, version = sys.argv[1:]
plan_path = base / 'date-audit-landscape-diagnostic-plan-v1.json'
plan = json.loads(plan_path.read_text())
assert group in plan['groups'] and version.isalnum() and '/' not in build_name
state = base / f'final-typed-native-{group}-{version}-state.json'
assert not state.exists(), 'Immutable run record already exists'
build_path = base / build_name
build = json.loads(build_path.read_text())
assert build['completed'] and build['exit_code'] == 0 and build['sources_unchanged']
root = Path(build['candidate_directory'])
sha = lambda p: hashlib.sha256(Path(p).read_bytes()).hexdigest()
assert sha(root / 'app.apk') == build.get('app_sha256', build.get('frozen_app_sha256')) and sha(root / 'test.apk') == build['test_apk_sha256']
assert all(sha(p) == value for p, value in build['source_hashes'].items())
snapshot_root = base / 'typed-real-bundles-v1'
snapshot = json.loads((snapshot_root / 'snapshot.json').read_text())
archives = {v['directory']: (snapshot_root / v['archive_cache_path'], v['archive_sha256']) for v in snapshot['extensions']}
assert all(sha(p) == value for p, value in archives.values())
frozen = {str(p): sha(p) for p in [plan_path, build_path, root / 'app.apk', root / 'test.apk',
          Path('scripts/test-reviewed-text-input.py'), Path('scripts/android_form_documents_fixture.py'),
          Path('scripts/android_clipboard_recovery.py'), Path('scripts/android_test_cleanup.py'),
          *(p for p, value in archives.values())]}
methods = plan['groups'][group]
record = dict(completed=False, pid=os.getpid(), group=group, methods=methods,
              production_source_commit=build['production_source_commit'], test_source_commit=build['test_source_commit'],
              host_source_commit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
              frozen_inputs=frozen, wrapper_sha256=sha(__file__), cohorts=[])
def save():
    state.write_text(json.dumps(record, indent=2) + '\n')
save()
try:
    for layout, font in plan['profiles']:
        assert all(sha(p) == value for p, value in frozen.items())
        assert all(sha(p) == value for p, value in build['source_hashes'].items())
        out = base / f'final-typed-native-{group}-{version}-{layout}{font * 100}'
        assert not out.exists()
        command = ['python3', 'scripts/test-reviewed-text-input.py', '--serial', 'emulator-5580', '--avd', 'Tinycast_Tablet_API_36',
                   '--layout', layout, '--font-scale', str(font), '--output', str(out), '--apk', str(root / 'app.apk'),
                   '--test-apk', str(root / 'test.apk'), '--json-archive',
                   '<catalog-checkout>/artifacts/extension-catalog/representative-001-bundles-v1/archives/223987eaca2c894e37ada9e087669f260cda79d6a3b88ff11463f429cc952e6a.zip',
                   '--classes', ','.join('dev.tinycast.android.ExtensionFormDeviceTest#' + method for method in methods),
                   '--expected', str(len(methods))]
        if group in ['files', 'real', 'lifecycle']:
            command.append('--document-fixtures')
        if group == 'real':
            command += ['--mood-archive', str(archives['mood'][0]), '--checksum-archive', str(archives['checksum'][0])]
        cohort = dict(name=out.name, output=str(out), command=command, completed=False)
        record['cohorts'].append(cohort); save()
        with (base / (out.name + '-host.log')).open('x') as log:
            code = subprocess.run(command, env=dict(os.environ, ANDROID_HOME='<home>/Library/Android/sdk'), stdout=log, stderr=subprocess.STDOUT).returncode
        cohort['exit_code'] = code
        if not (out / 'record.json').exists():
            cohort['preflight_failed_before_output_creation'] = not out.exists(); save()
            raise RuntimeError('No host record; inspect retained preflight log before repeating any device mutation')
        host = json.loads((out / 'record.json').read_text())
        cleanup = host.get('cleanup', {})
        cohort.update(completed=host['completed'], passes=host.get('passes'), cleanup_checks=len(cleanup),
                      cleanup_all_passed=bool(cleanup) and all(v['passed'] for v in cleanup.values()),
                      document_sources_verified=host.get('document_fixture_restored'))
        save()
        print(json.dumps({k: cohort[k] for k in ['name', 'completed', 'passes', 'cleanup_all_passed']}), flush=True)
        assert code == 0 and cohort['completed'] and cohort['passes'] == len(methods) and cohort['cleanup_all_passed']
        if group in ['files', 'real', 'lifecycle']:
            assert cohort['document_sources_verified'] is True
        cases = list((out / 'captures').glob('form-*-record.json'))
        assert len(cases) == len(methods)
        for p in cases:
            case = json.loads(p.read_text())
            assert case['passed'] and case['owned_data_removed'] and case['preexisting_installations_and_data_preserved']
            assert case['installed_build_commit'] == build['production_source_commit'] and abs(case['font_scale'] - font) < .01
    assert all(sha(p) == value for p, value in frozen.items())
    assert all(sha(p) == value for p, value in build['source_hashes'].items())
    record['completed'] = True; save()
except BaseException as failure:
    record['failure_type'] = type(failure).__name__; save(); raise
