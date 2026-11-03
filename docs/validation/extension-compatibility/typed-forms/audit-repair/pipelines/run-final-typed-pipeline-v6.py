from pathlib import Path
import hashlib, json, os, subprocess

b = Path('artifacts/form-pickers')
state = b / 'final-typed-pipeline-v6-state.json'
assert not state.exists()
version = 'auditfinalv1'
build_name = 'typed-build-audit-repair-v1-state.json'
build = json.loads((b / build_name).read_text())
sha = lambda p: hashlib.sha256(Path(p).read_bytes()).hexdigest()
assert build['completed'] and build['exit_code'] == 0 and build['sources_unchanged']
assert all(sha(p) == h for p, h in build['source_hashes'].items())
record = dict(completed=False, pid=os.getpid(), production_source_commit=build['production_source_commit'],
    test_source_commit=build['test_source_commit'], source_hashes=build['source_hashes'],
    app_sha256=build['app_sha256'], test_apk_sha256=build['test_apk_sha256'],
    expected_native_executions=195, matched_controls=[], steps=[])
for phase in ['before', 'after']:
    for layout in ['tablet', 'phone', 'landscape']:
        p = b / f'date-audit-controls-{phase}-{layout}-v1-state.json'
        d = json.loads(p.read_text())
        assert d['completed'] and d['cohorts'][0]['cleanup_all_passed']
        if phase == 'before':
            assert d['expected_negative_verified'] and d['expected_negative_executions'] == 4 and d['junit_passes'] == 0
        else:
            assert d['junit_passes'] == 4 and d['production_source_commit'] == build['production_source_commit']
        record['matched_controls'].append(dict(state=p.name, source_sha256=sha(p), phase=phase,
            junit_passes=d['junit_passes'], expected_negative_executions=d.get('expected_negative_executions', 0),
            cleanup_checks=d['cohorts'][0]['cleanup_checks']))
steps = [('real', 18), ('typed', 21), ('lifecycle', 9), ('extended', 48),
         ('files', 27), ('legacy', 36), ('auditrepairs', 12), ('criticalfonts', 24)]
for group, expected in steps:
    assert not (b / f'final-typed-native-{group}-{version}-state.json').exists()
scripts = ['run-final-typed-native-v3.py', 'run-critical-font-native-v1.py', 'run-main-ime-group-monitor-v2.py']
record['frozen_wrappers'] = {name: sha(b / name) for name in scripts}
def save():
    state.write_text(json.dumps(record, indent=2) + '\n')
save()
try:
    for group, expected in steps:
        assert all(sha(p) == h for p, h in build['source_hashes'].items())
        assert all(sha(b / p) == h for p, h in record['frozen_wrappers'].items())
        step = dict(group=group, version=version, expected_executions=expected, completed=False)
        record['steps'].append(step)
        save()
        print(json.dumps(dict(starting_group=group, expected_executions=expected)), flush=True)
        if group == 'legacy':
            command = ['python3', str(b / 'run-main-ime-group-monitor-v2.py'), build_name, version]
        else:
            script = 'run-critical-font-native-v1.py' if group == 'criticalfonts' else 'run-final-typed-native-v3.py'
            command = ['python3', str(b / script), group, build_name, version]
        code = subprocess.run(command).returncode
        p = b / f'final-typed-native-{group}-{version}-state.json'
        step['exit_code'] = code
        if p.exists():
            d = json.loads(p.read_text())
            assert d['production_source_commit'] == build['production_source_commit']
            assert d['test_source_commit'] == build['test_source_commit']
            step.update(state=p.name, state_sha256=sha(p), completed=d['completed'],
                cohorts=len(d['cohorts']), passes=sum(c.get('passes', 0) or 0 for c in d['cohorts']),
                cleanup_checks=sum(c.get('cleanup_checks', 0) for c in d['cohorts']),
                cleanup_all_passed=bool(d['cohorts']) and all(c.get('cleanup_all_passed') for c in d['cohorts']))
        save()
        assert code == 0 and step['completed'] and step['passes'] == expected and step['cleanup_all_passed']
        print(json.dumps(step), flush=True)
    assert sum(s['passes'] for s in record['steps']) == 195
    assert sum(s['cohorts'] for s in record['steps']) == 27
    assert all(sha(p) == h for p, h in build['source_hashes'].items())
    record['completed'] = True
    save()
    print(json.dumps(dict(completed=True, native_executions=195, cohorts=27,
        cleanup_checks=sum(s['cleanup_checks'] for s in record['steps']),
        separate_matched_positive_executions=12, separate_matched_negative_executions=12)), flush=True)
except BaseException as failure:
    record['failure_type'] = type(failure).__name__
    save()
    raise
