from pathlib import Path
import hashlib,json,os,subprocess,sys
base=Path('artifacts/workflow-expansion');out=base/'core-v2'
if out.exists():raise RuntimeError('Core v2 already attempted; inspect before repeating')
if not json.loads((base/'build-harness-v2/state.json').read_text())['completed']:raise RuntimeError('Harness incomplete')
out.mkdir();state=dict(completed=False,pid=os.getpid(),collector_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),jobs=[])
def save():(out/'state.json').write_text(json.dumps(state,indent=2)+'\n')
save()
jobs=[('matched-old-tablet-v2','baseline','tablet','policy'),('matched-old-phone-v2','baseline','phone','policy'),
      ('native-policy-tablet-v2','current','tablet','policy'),('native-policy-phone-v2','current','phone','policy'),
      ('native-https-tablet-v2','current','tablet','https'),('native-https-phone-v2','current','phone','https')]
for name,variant,layout,case in jobs:
    state['current']=name;save();print('Starting '+name,flush=True)
    with (out/(name+'.log')).open('wb') as log:
        r=subprocess.run([sys.executable,str(base/'run-fetch-v2.py'),'--name',name,'--variant',variant,'--layout',layout,'--case',case],stdout=log,stderr=subprocess.STDOUT)
    path=base/name/'state.json';value=json.loads(path.read_text()) if path.exists() else {}
    row=dict(name=name,exit_code=r.returncode,completed=value.get('completed'),passes=value.get('passes'),cleanup_checks=value.get('cleanup_checks'))
    state['jobs'].append(row);save();print(json.dumps(row),flush=True)
    if r.returncode:raise RuntimeError('Core cohort failed; preserve and inspect before continuing')
if hashlib.sha256(Path(__file__).read_bytes()).hexdigest()!=state['collector_sha256']:raise RuntimeError('Core pipeline changed')
state['completed']=True;state['current']=None;save();print('Core fetch cohorts completed',flush=True)
