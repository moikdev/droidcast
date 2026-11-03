from pathlib import Path
import hashlib, json, os, subprocess

b = Path('artifacts/form-pickers')
state = b / 'final-typed-pipeline-v5-state.json'
assert not state.exists(), 'Inspect an existing immutable run before repeating it'
build_name = 'file-workflow-harness-v28-state.json'
build = json.loads((b / build_name).read_text())
old = json.loads((b / 'file-workflow-harness-v26-state.json').read_text())
sha = lambda p: hashlib.sha256(Path(p).read_bytes()).hexdigest()
assert build['completed'] and build['exit_code'] == 0 and build['sources_unchanged']
assert all(sha(p) == h for p, h in build['source_hashes'].items())
assert build['frozen_app_sha256'] == old['frozen_app_sha256']
assert build['production_source_commit'] == old['production_source_commit']
path = 'app/src/androidTest/java/dev/tinycast/android/ExtensionFormDeviceTest.kt'
assert [p for p, h in old['source_hashes'].items() if sha(p) != h] == [path]
before = subprocess.check_output(['git', 'show', old['test_source_commit'] + ':' + path])
after = Path(path).read_bytes()
start = b'    @Test fun checkboxLabelTogglesExactlyOnce()'
end = b'    private fun assertCheckboxSemantics('
a, z = before.index(start), after.index(start)
ae, ze = before.index(end, a), after.index(end, z)
assert before[:a] == after[:z] and before[ae:] == after[ze:]

record = dict(completed=False, pid=os.getpid(),
              production_source_commit=build['production_source_commit'],
              current_test_source_commit=build['test_source_commit'],
              retained_test_source_commit=old['test_source_commit'],
              source_hashes=build['source_hashes'], retained_source_hashes=old['source_hashes'],
              only_changed_test_method='checkboxLabelTogglesExactlyOnce',
              outside_changed_method_byte_identical=True,
              expected_native_executions=183, retained_groups=[], steps=[])

def save():
    state.write_text(json.dumps(record, indent=2) + '\n')

for group, count in [('real', 18), ('typed', 21), ('lifecycle', 9), ('extended', 48), ('files', 27)]:
    p = b / f'final-typed-native-{group}-v4-state.json'
    d = json.loads(p.read_text())
    assert d['completed'] and d['test_source_commit'] == old['test_source_commit']
    assert d['production_source_commit'] == build['production_source_commit']
    assert sum(c['passes'] for c in d['cohorts']) == count
    assert all(c['completed'] and c['exit_code'] == 0 and c['cleanup_all_passed'] for c in d['cohorts'])
    assert 'checkboxLabelTogglesExactlyOnce' not in d['methods']
    record['retained_groups'].append(dict(group=group, state=p.name, state_sha256=sha(p),
        passes=count, cleanup_checks=sum(c['cleanup_checks'] for c in d['cohorts'])))

legacy_path = b / 'final-typed-native-legacy-legacymonitoredv1-state.json'
legacy = json.loads(legacy_path.read_text())
monitor_path = b / 'main-ime-group-monitor-v1-state.json'
monitor = json.loads(monitor_path.read_text())
assert legacy['completed'] and legacy['test_source_commit'] == build['test_source_commit']
assert legacy['production_source_commit'] == build['production_source_commit']
assert sum(c['passes'] for c in legacy['cohorts']) == 36
assert all(c['completed'] and c['exit_code'] == 0 and c['cleanup_all_passed'] for c in legacy['cohorts'])
assert monitor['completed'] and monitor['engine_exit_code'] == 0
for c in legacy['cohorts']:
    assert any(s['cohort'] == c['name'] and 'mInputShown=true' in s['flags'] for s in monitor['samples'])
record['steps'].append(dict(group='legacy', version='legacymonitoredv1', state=legacy_path.name,
    state_sha256=sha(legacy_path), completed=True, exit_code=0, passes=36,
    cleanup_checks=sum(c['cleanup_checks'] for c in legacy['cohorts']), cleanup_all_passed=True,
    reused_completed_group=True,
    observation_boundary='Read-only input-method monitoring was coupled to the active test. Earlier unmonitored IME timeouts remain unresolved failures; no policy or source repair is claimed.'))

script = 'run-critical-font-native-v1.py'
version = 'fontfinalv1'
font_path = b / f'final-typed-native-criticalfonts-{version}-state.json'
assert not font_path.exists(), 'Do not repeat a font group without inspecting its state'
record['frozen_wrappers'] = {script: sha(b / script)}
save()
try:
    assert all(sha(p) == h for p, h in record['source_hashes'].items())
    step = dict(group='criticalfonts', version=version, state=font_path.name, completed=False)
    record['steps'].append(step)
    save()
    code = subprocess.run(['python3', str(b / script), 'criticalfonts', build_name, version]).returncode
    step['exit_code'] = code
    if font_path.exists():
        d = json.loads(font_path.read_text())
        step.update(completed=d['completed'], cohorts=len(d['cohorts']),
                    passes=sum(c.get('passes', 0) or 0 for c in d['cohorts']),
                    cleanup_checks=sum(c.get('cleanup_checks', 0) for c in d['cohorts']),
                    cleanup_all_passed=bool(d['cohorts']) and all(c.get('cleanup_all_passed') for c in d['cohorts']),
                    state_sha256=sha(font_path))
    save()
    assert code == 0 and step['completed'] and step['passes'] == 24 and step['cleanup_all_passed']
    assert sum(s['passes'] for s in record['retained_groups'] + record['steps']) == 183
    assert all(sha(p) == h for p, h in record['source_hashes'].items())
    assert all(sha(b / p) == h for p, h in record['frozen_wrappers'].items())
    record['completed'] = True
    save()
    print(json.dumps(dict(completed=True, native_executions=183,
        cleanup_checks=sum(s['cleanup_checks'] for s in record['retained_groups'] + record['steps']))), flush=True)
except BaseException as failure:
    record['failure_type'] = type(failure).__name__
    save()
    raise
