from pathlib import Path
import json, os, subprocess, sys

base = Path('artifacts/context-workflows')
out = base / 'final-sequence-v4'
out.mkdir(exist_ok=False)
record = dict(completed=False, pid=os.getpid(), runs=[])

def save():
    (out / 'record.json').write_text(json.dumps(record, indent=2) + '\n')

save()
for script in ['run-baseline-pipeline-v4.py', 'run-critical-pipeline-v4.py', 'discover-observations-v2.py']:
    with (out / (script + '.log')).open('wb') as log:
        process = subprocess.run([sys.executable, str(base / script)], stdout=log, stderr=subprocess.STDOUT)
    record['runs'].append(dict(script=script, exit_code=process.returncode))
    save()
    print(script, process.returncode, flush=True)
    if process.returncode:
        raise RuntimeError('Final sequence stopped; inspect retained failure before continuing')
record['completed'] = True
save()
print(json.dumps(record, indent=2))
