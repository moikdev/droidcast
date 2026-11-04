from pathlib import Path
import hashlib,json,subprocess,sys
base=Path('artifacts/paste-copy');out=base/'continuation-v7';out.mkdir(exist_ok=False)
state=dict(completed=False,collector_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),cohorts=[],prior_pipeline='final-matrix-v6 remains failed; four later planned cohorts were unrun')
def save():(out/'record.json').write_text(json.dumps(state,indent=2)+'\n')
commands=[('owned-keyboard-landscape200-v7',['run-owned-v7.py','--name','owned-keyboard-landscape200-v7','--method','keyboardCopyHidesImeAndEscapeReturns','--layout','landscape','--font','2'])]
for layout in ['tablet','phone']:
 for group in ['callers','handoff']:
  name='regression-'+group+'-'+layout+'100-v7';commands.append((name,['run-regressions-v7.py','--name',name,'--group',group,'--layout',layout]))
save()
for name,command in commands:
 state['current']=name;save();result=subprocess.run([sys.executable,str(base/command[0]),*command[1:]])
 state['cohorts'].append(dict(name=name,exit_code=result.returncode));save()
 if result.returncode:raise RuntimeError('Cohort failed; later planned cohorts remain unrun: '+name)
state.update(completed=True,current=None);save();print(json.dumps(state,indent=2))
