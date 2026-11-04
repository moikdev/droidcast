from pathlib import Path
import hashlib, json, os, subprocess, sys
from preservation import require, verify

base = Path('artifacts/context-workflows')
read = lambda path: json.loads(Path(path).read_text())
baseline = read(base / 'baseline-pipeline-v4/record.json')
critical = read(base / 'critical-pipeline-v4/record.json')
require(baseline['completed'] and not critical['completed'] and len(critical['runs']) == 11, 'Unexpected predecessor state')
require(critical['runs'][-1] == dict(name='critical-translate-landscape200-v4', exit_code=1), 'Different preceding failure')
require('extension-back needs a complete visible 48dp target' in (base / 'critical-translate-landscape200-v4/cohort/tests.log').read_text(), 'Different failing assertion')
identity = read(base / 'build-observations-v5/state.json')
require(identity['completed'], 'New build incomplete')
retained = [row['name'] for row in baseline['runs']] + [row['name'] for row in critical['runs'] if row['exit_code'] == 0]
require(len(retained) == 24 and all(read(base / name / 'state.json')['completed'] for name in retained), 'Incomplete retained positives')
out = base / 'continuation-v5'
out.mkdir(exist_ok=False)
collector = base / 'run-observation-v5.py'
digest = hashlib.sha256(collector.read_bytes()).hexdigest()
state = dict(completed=False, pid=os.getpid(), collector_sha256=digest, retained_v4_cohorts=retained, runs=[],
             excluded_v4_failure='critical-translate-landscape200-v4',
             unrun_in_failed_v4_pipeline='critical-menu-bar-landscape200-v4',
             scope='Retained v4 same-app positives plus separately identified v5 pointer-protocol continuation; no production repair or same-test-APK equivalence inferred')

def save():
    (out / 'record.json').write_text(json.dumps(state, indent=2) + '\n')

save()
rows = [('critical-translate-landscape200-v5', 'translate', 'landscape', 2),
        ('critical-menu-bar-landscape200-v5', 'menu-bar', 'landscape', 2),
        ('regression-quick-translate-view-phone100-v5', 'quick-translate-view', 'phone', 1)]
for name, command, layout, font in rows:
    invocation = [sys.executable, str(collector), '--command', command, '--layout', layout, '--font', str(font), '--name', name]
    with (out / (name + '.log')).open('wb') as log:
        process = subprocess.run(invocation, stdout=log, stderr=subprocess.STDOUT)
    state['runs'].append(dict(name=name, exit_code=process.returncode))
    save()
    print(name, process.returncode, flush=True)
    require(process.returncode == 0, 'Continuation failed; inspect before continuing')
    require(hashlib.sha256(collector.read_bytes()).hexdigest() == digest, 'Collector changed')
fresh = [row['name'] for row in state['runs']]
all_names = retained + fresh
records = [read(base / name / 'state.json') for name in all_names]
require(len(records) == 27 and all(row['completed'] and row['cleanup_passed'] and row['durable_data_unchanged'] and row['pre_existing_evidence_unchanged'] for row in records), 'Incomplete verified cohorts')
require(len({row['app_sha256'] for row in records}) == 1 and len({row['test_apk_sha256'] for row in records}) == 2, 'Unexpected APK split')
verify()
with (out / 'discovery.log').open('wb') as log:
    process = subprocess.run([sys.executable, str(base / 'discover-observations-v3.py')], stdout=log, stderr=subprocess.STDOUT)
state['discovery_exit_code'] = process.returncode
save()
require(process.returncode == 0 and read(base / 'discovery-v1/record.json')['completed'], 'Discovery failed')
state.update(completed=True, verified_positive_cohorts=all_names, matrix_cohorts=retained + fresh[:2],
             regression_cohorts=fresh[2:], executions=sum(row['passes'] for row in records),
             cleanup_checks=sum(row['cleanup_checks'] for row in records),
             outcomes=[dict(cohort=name, command=row['command_name'], layout=row['layout'], font=row['font'], outcome=row['workflow_outcome'], test_source_commit=row['test_source_commit']) for name, row in zip(all_names, records)])
save()
print(json.dumps({key: state[key] for key in ['completed', 'executions', 'cleanup_checks']}, indent=2))
