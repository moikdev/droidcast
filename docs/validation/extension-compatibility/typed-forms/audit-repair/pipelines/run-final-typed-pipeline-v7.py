"""Complete only unstarted groups; retain successful same-APK cohorts explicitly."""
from pathlib import Path
import hashlib, json, os, subprocess

b = Path('artifacts/form-pickers')
state = b / 'final-typed-pipeline-v7-state.json'
assert not state.exists()
sha = lambda p: hashlib.sha256(Path(p).read_bytes()).hexdigest()
build_name = 'typed-build-audit-repair-v1-state.json'
build = json.loads((b / build_name).read_text())
assert build['completed'] and build['exit_code'] == 0 and build['sources_unchanged']
assert all(sha(p) == h for p, h in build['source_hashes'].items())
prior = json.loads((b / 'final-typed-pipeline-v6-state.json').read_text())
assert not prior['completed'] and prior['steps'][-1]['group'] == 'legacy'
assert prior['steps'][-1]['passes'] == 35 and prior['steps'][-1]['cleanup_all_passed']
record = dict(completed=False, pid=os.getpid(), production_source_commit=build['production_source_commit'],
    test_source_commit=build['test_source_commit'], source_hashes=build['source_hashes'],
    app_sha256=build['app_sha256'], test_apk_sha256=build['test_apk_sha256'],
    expected_native_executions=195, groups=[], matched_controls=prior['matched_controls'],
    prior_failed_pipeline=dict(state='final-typed-pipeline-v6-state.json', sha256=sha(b / 'final-typed-pipeline-v6-state.json'),
        boundary='Original legacy landscape11/12 remains failed, excluded and unexplained; no source/policy change is credited.'),
    assembly_boundary='Five successful groups and successful legacy member cohorts are retained with their original identities. Only the two previously unstarted groups run here.')
def save():
    state.write_text(json.dumps(record, indent=2) + '\n')
for group, count in [('real',18),('typed',21),('lifecycle',9),('extended',48),('files',27)]:
    step = next(s for s in prior['steps'] if s['group'] == group)
    d = json.loads((b / step['state']).read_text())
    assert d['completed'] and step['passes'] == count and step['cleanup_all_passed']
    assert d['production_source_commit'] == build['production_source_commit'] and d['test_source_commit'] == build['test_source_commit']
    record['groups'].append({**step, 'retained_completed_group':True})

old_path = b / 'final-typed-native-legacy-auditfinalv1-state.json'
fresh_path = b / 'final-typed-native-legacy-imewindowv1-state.json'
old, fresh = json.loads(old_path.read_text()), json.loads(fresh_path.read_text())
monitor = json.loads((b / 'main-ime-window-diagnostic-v1-state.json').read_text())
assert fresh['completed'] and monitor['completed'] and monitor['engine_exit_code'] == 0
assert old['methods'] == fresh['methods']
assert all(d['production_source_commit'] == build['production_source_commit'] and d['test_source_commit'] == build['test_source_commit'] for d in [old,fresh])
assert [c['passes'] for c in old['cohorts']] == [12,12,11]
members = old['cohorts'][:2] + fresh['cohorts']
assert len(members) == 3 and all(c['completed'] and c['passes'] == 12 and c['cleanup_all_passed'] for c in members)
for c in members:
    host = json.loads((Path(c['output']) / 'record.json').read_text())
    assert host['completed'] and host['apk_sha256'] == build['app_sha256'] and host['test_apk_sha256'] == build['test_apk_sha256']
legacy_path = b / 'final-typed-native-legacy-auditverifiedv1-state.json'
assert not legacy_path.exists()
legacy = dict(completed=True, production_source_commit=build['production_source_commit'],
    test_source_commit=build['test_source_commit'], methods=old['methods'], cohorts=members,
    source_controllers={old_path.name:sha(old_path), fresh_path.name:sha(fresh_path)},
    scope='Three completed whole legacy cohorts on the exact same app/test APK. Tablet and phone retain their original completed runs; landscape is a complete fresh12-case cohort with read-only IME/WindowManager monitoring. The prior failed landscape11/12 is not rewritten or credited.',
    original_failed_cohort=old['cohorts'][-1], unresolved_failure='Pre-entry IME-show timeout; diagnostic pass does not isolate cause or demonstrate a product repair.')
legacy_path.write_text(json.dumps(legacy, indent=2) + '\n')
record['groups'].append(dict(group='legacy', state=legacy_path.name, state_sha256=sha(legacy_path),
    completed=True, passes=36, cohorts=3, cleanup_checks=sum(c['cleanup_checks'] for c in members),
    cleanup_all_passed=True, composite_completed_cohorts=True))
for group in ['auditrepairs','criticalfonts']:
    assert not (b / f'final-typed-native-{group}-auditfinalv1-state.json').exists()
save()
try:
    for group, expected in [('auditrepairs',12),('criticalfonts',24)]:
        assert all(sha(p) == h for p, h in build['source_hashes'].items())
        script = 'run-critical-font-native-v1.py' if group == 'criticalfonts' else 'run-final-typed-native-v3.py'
        step = dict(group=group, expected_executions=expected, completed=False, wrapper_sha256=sha(b / script))
        record['groups'].append(step); save()
        print(json.dumps(dict(starting_group=group, expected_executions=expected)), flush=True)
        code = subprocess.run(['python3', str(b / script), group, build_name, 'auditfinalv1']).returncode
        p = b / f'final-typed-native-{group}-auditfinalv1-state.json'
        step['exit_code'] = code
        if p.exists():
            d = json.loads(p.read_text())
            step.update(state=p.name, state_sha256=sha(p), completed=d['completed'], cohorts=len(d['cohorts']),
                passes=sum(c.get('passes',0) or 0 for c in d['cohorts']),
                cleanup_checks=sum(c.get('cleanup_checks',0) for c in d['cohorts']),
                cleanup_all_passed=bool(d['cohorts']) and all(c.get('cleanup_all_passed') for c in d['cohorts']))
        save()
        assert code == 0 and step['completed'] and step['passes'] == expected and step['cleanup_all_passed']
        assert step['wrapper_sha256'] == sha(b / script)
    assert sum(g['passes'] for g in record['groups']) == 195
    assert sum(g['cohorts'] for g in record['groups']) == 27
    assert all(sha(p) == h for p, h in build['source_hashes'].items())
    record['completed'] = True; save()
    print(json.dumps(dict(completed=True,native_executions=195,cohorts=27,
        cleanup_checks=sum(g['cleanup_checks'] for g in record['groups']),
        separate_matched_positive_executions=12,separate_matched_negative_executions=12)),flush=True)
except BaseException as failure:
    record['failure_type'] = type(failure).__name__; save(); raise
