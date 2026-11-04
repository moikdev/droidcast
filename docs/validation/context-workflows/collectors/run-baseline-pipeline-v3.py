from pathlib import Path
import hashlib, json, os, subprocess, sys
from preservation import require

base = Path('artifacts/context-workflows')
out = base / 'baseline-pipeline-v3'
require(not out.exists(), 'Pipeline already exists; inspect before repeating')
out.mkdir()
collector = base / 'run-observation-v3.py'
digest = hashlib.sha256(collector.read_bytes()).hexdigest()
state = dict(completed=False, pid=os.getpid(), collector_sha256=digest, runs=[])

def save():
    (out / 'record.json').write_text(json.dumps(state, indent=2) + '\n')

save()
for layout in ['tablet', 'phone']:
    for command in ['quick-translate', 'lorem-ai', 'menu-bar', 'translate', 'quick-translate-view', 'instant-translate-paste', 'instant-translate-view']:
        name = 'baseline-' + command + '-' + layout + '100-v3'
        invocation = [sys.executable, str(collector), '--command', command, '--layout', layout, '--name', name]
        with (out / (name + '.log')).open('wb') as log:
            process = subprocess.run(invocation, stdout=log, stderr=subprocess.STDOUT)
        state['runs'].append(dict(name=name, exit_code=process.returncode))
        save()
        print(name, process.returncode, flush=True)
        require(process.returncode == 0, 'Observation collector failed; inspect retained attempt before continuing')
        require(hashlib.sha256(collector.read_bytes()).hexdigest() == digest, 'Collector changed')
records = [json.loads((base / row['name'] / 'state.json').read_text()) for row in state['runs']]
require(len(records) == 14 and all(row['completed'] and row['cleanup_passed'] and row['durable_data_unchanged'] and row['pre_existing_evidence_unchanged'] for row in records), 'Incomplete baseline matrix')
state.update(completed=True, executions=sum(row['passes'] for row in records), cleanup_checks=sum(row['cleanup_checks'] for row in records),
             outcomes=[dict(command=row['command_name'], layout=row['layout'], outcome=row['workflow_outcome'], bytecode_executed=row['extension_bytecode_executed']) for row in records])
save()
print(json.dumps({key: state[key] for key in ['completed', 'executions', 'cleanup_checks', 'outcomes']}, indent=2))
