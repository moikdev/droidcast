from pathlib import Path
import hashlib, json, os, re, subprocess, sys

b = Path('artifacts/form-pickers')
phase, layout, build_name, version = sys.argv[1:]
assert phase in {'before', 'after'} and layout in {'tablet', 'phone', 'landscape'}
assert version.isalnum() and '/' not in build_name
font = 1 if layout == 'tablet' else 2
state = b / f'date-audit-controls-{phase}-{layout}-{version}-state.json'
assert not state.exists(), 'Inspect an existing immutable run before repeating it'
out = b / f'date-audit-controls-{phase}-{version}-{layout}{font * 100}'
assert not out.exists()
build_path = b / build_name
build = json.loads(build_path.read_text())
assert build['completed'] and build['exit_code'] == 0 and build['sources_unchanged']
sha = lambda p: hashlib.sha256(Path(p).read_bytes()).hexdigest()
root = Path(build['candidate_directory'])
assert sha(root / 'app.apk') == build.get('app_sha256', build.get('frozen_app_sha256'))
assert sha(root / 'test.apk') == build['test_apk_sha256']
assert all(sha(p) == h for p, h in build['source_hashes'].items())
methods = {
    'datePickerQueuedConfirmationCannotOverwriteANewerValue': 'A stale draft must not call onChange',
    'datePickerQueuedConfirmationCannotOverwriteAResetValue': 'A stale draft must not call onChange',
    'datePickerConfirmationRechecksValueBeforeComposition': 'Acceptance must recheck the value before composition can invalidate the dialog',
    'clearingDateLetsTheSameTextFieldRefocusAndSubmit': 'Clear date must release the previous native editor focus',
}
frozen = {str(p): sha(p) for p in [build_path, root / 'app.apk', root / 'test.apk',
    Path('scripts/test-reviewed-text-input.py'), Path('scripts/android_clipboard_recovery.py'),
    Path('scripts/android_test_cleanup.py')]}
command = ['python3', 'scripts/test-reviewed-text-input.py', '--serial', 'emulator-5580',
    '--avd', 'Tinycast_Tablet_API_36', '--layout', layout, '--font-scale', str(font),
    '--output', str(out), '--apk', str(root / 'app.apk'), '--test-apk', str(root / 'test.apk'),
    '--json-archive', '<catalog-checkout>/artifacts/extension-catalog/representative-001-bundles-v1/archives/223987eaca2c894e37ada9e087669f260cda79d6a3b88ff11463f429cc952e6a.zip',
    '--classes', ','.join('dev.tinycast.android.ExtensionFormDeviceTest#' + m for m in methods),
    '--expected', str(len(methods))]
record = dict(completed=False, pid=os.getpid(), phase=phase,
    production_source_commit=build['production_source_commit'], test_source_commit=build['test_source_commit'],
    host_source_commit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
    methods=methods, source_hashes=build['source_hashes'], frozen_inputs=frozen,
    wrapper_sha256=sha(__file__), cohorts=[dict(name=out.name, output=str(out), completed=False)],
    expectation='Four intended assertion failures, zero JUnit passes' if phase == 'before' else 'Four completed passing regressions')
def save():
    state.write_text(json.dumps(record, indent=2) + '\n')
save()
try:
    with (b / (out.name + '-host.log')).open('x') as log:
        code = subprocess.run(command, env=dict(os.environ, ANDROID_HOME='<home>/Library/Android/sdk'),
            stdout=log, stderr=subprocess.STDOUT).returncode
    assert (out / 'record.json').exists(), 'Missing host record; inspect preflight before repeating any device action'
    host = json.loads((out / 'record.json').read_text())
    cleanup = host.get('cleanup', {})
    cohort = record['cohorts'][0]
    cohort.update(exit_code=code, completed=host['completed'], native_completed=host['completed'],
        passes=host.get('passes'), cleanup_checks=len(cleanup),
        cleanup_all_passed=bool(cleanup) and all(c['passed'] for c in cleanup.values()))
    save()
    assert cohort['cleanup_all_passed'], 'Restoration/recovery must finish before another native run'
    text = (out / 'tests.log').read_text()
    observations = []
    for match in re.finditer(r'(.*?)^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$', text, re.M | re.S):
        block, status = match.group(1), int(match.group(2))
        names = re.findall(r'^INSTRUMENTATION_STATUS: test=(.+)$', block, re.M)
        if status in {0, -2, -3, -4} and names:
            method = names[-1]
            observations.append(dict(method=method, code=status,
                expected_assertion_found=method in methods and methods[method] in block))
    record['case_outcomes'] = observations
    save()
    assert len(observations) == 4 and {c['method'] for c in observations} == set(methods)
    cases = list((out / 'captures').glob('form-*-record.json'))
    assert len(cases) == 4
    for p in cases:
        case = json.loads(p.read_text())
        assert case['installed_build_commit'] == build['production_source_commit']
        assert case['owned_data_removed'] and case['preexisting_installations_and_data_preserved']
        assert abs(case['font_scale'] - font) < .01
    if phase == 'before':
        assert code == 1 and not host['completed'] and host['passes'] == 0
        assert all(c['code'] == -2 and c['expected_assertion_found'] for c in observations), observations
        assert all(not json.loads(p.read_text()).get('passed', False) for p in cases)
        record.update(expected_negative_executions=4, junit_passes=0, expected_negative_verified=True)
    else:
        assert code == 0 and host['completed'] and host['passes'] == 4
        assert all(c['code'] == 0 for c in observations)
        assert all(json.loads(p.read_text())['passed'] for p in cases)
        record.update(junit_passes=4)
    assert all(sha(p) == h for p, h in frozen.items())
    assert all(sha(p) == h for p, h in build['source_hashes'].items())
    record['completed'] = True
    save()
    print(json.dumps(dict(completed=True, phase=phase, cohort=cohort,
        expected_negative_executions=record.get('expected_negative_executions', 0),
        junit_passes=record['junit_passes'])), flush=True)
except BaseException as failure:
    record['failure_type'] = type(failure).__name__
    save()
    raise
