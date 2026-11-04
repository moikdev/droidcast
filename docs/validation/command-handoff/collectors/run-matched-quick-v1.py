from pathlib import Path
import json, os, subprocess, sys
from preservation import require

base = Path('artifacts/command-handoff')
out = base / 'matched-quick-v1'
out.mkdir(exist_ok=False)
record = dict(completed=False, pid=os.getpid(), runs=[], scope='Same frozen v5 instrumentation on old/current apps; old collector passes witness a blocked capability, not working compatibility or JUnit failures')

def save():
    (out / 'record.json').write_text(json.dumps(record, indent=2) + '\n')

save()
for layout in ['tablet', 'phone']:
    for app in ['old', 'new']:
        name = 'matched-quick-' + app + '-' + layout + '100-v1'
        command = [sys.executable, str(base / 'run-quick-control-v1.py'), '--app', app, '--layout', layout, '--name', name]
        with (out / (name + '.log')).open('wb') as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
        record['runs'].append(dict(name=name, exit_code=result.returncode))
        save()
        print(name, result.returncode, flush=True)
        require(result.returncode == 0, 'Matched sequence failed; inspect before continuing')
record['completed'] = True
save()
print(json.dumps(record, indent=2))
