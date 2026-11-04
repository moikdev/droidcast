from pathlib import Path
import hashlib, json, os, subprocess, sys
from preservation import require

base = Path('artifacts/context-workflows')
baseline = json.loads((base / 'baseline-pipeline-v4/record.json').read_text())
require(baseline['completed'], 'Baseline matrix incomplete')
out = base / 'critical-pipeline-v4'
require(not out.exists(), 'Critical pipeline already exists; inspect before repeating')
out.mkdir()
collector = base / 'run-observation-v4.py'
digest = hashlib.sha256(collector.read_bytes()).hexdigest()
state = dict(completed=False, pid=os.getpid(), collector_sha256=digest, runs=[],
             scope='Functional enlarged-text/viewport smoke for selected boundary and result paths; recorded pointer actions require complete 48dp targets. Only AI denial/Return has complete natural-line probes. This is not broad typography or accessibility certification.')

def save():
    (out / 'record.json').write_text(json.dumps(state, indent=2) + '\n')

save()
for layout in ['tablet', 'phone', 'landscape']:
    for command in ['lorem-ai', 'quick-translate', 'translate', 'menu-bar']:
        name = 'critical-' + command + '-' + layout + '200-v4'
        invocation = [sys.executable, str(collector), '--command', command, '--layout', layout, '--font', '2', '--name', name]
        with (out / (name + '.log')).open('wb') as log:
            process = subprocess.run(invocation, stdout=log, stderr=subprocess.STDOUT)
        state['runs'].append(dict(name=name, exit_code=process.returncode))
        save()
        print(name, process.returncode, flush=True)
        require(process.returncode == 0, 'Critical observation failed; inspect before continuing')
        require(hashlib.sha256(collector.read_bytes()).hexdigest() == digest, 'Collector changed')
records = [json.loads((base / row['name'] / 'state.json').read_text()) for row in state['runs']]
require(len(records) == 12 and all(row['completed'] and row['cleanup_passed'] and row['durable_data_unchanged'] and row['pre_existing_evidence_unchanged'] for row in records), 'Incomplete critical matrix')
state.update(completed=True, executions=sum(row['passes'] for row in records), cleanup_checks=sum(row['cleanup_checks'] for row in records),
             outcomes=[dict(command=row['command_name'], layout=row['layout'], outcome=row['workflow_outcome']) for row in records])
save()
print(json.dumps({key: state[key] for key in ['completed', 'executions', 'cleanup_checks', 'outcomes']}, indent=2))
