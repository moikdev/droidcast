from pathlib import Path
import json,os,subprocess,sys
from preservation import require,verify
base=Path('artifacts/command-handoff');out=base/'continuation-v4';out.mkdir(exist_ok=False)
prior=json.loads((base/'final-verification-v3/record.json').read_text());require(not prior['completed'] and prior['runs'][-1]=={'name':'regression-menus-tablet100-v2','exit_code':1},'Unexpected preceding pipeline state')
require(json.loads((base/'regression-menus-tablet100-v3/state.json').read_text())['completed'],'Tablet menu continuation incomplete')
verify()
state=dict(completed=False,pid=os.getpid(),runs=[dict(name='regression-menus-tablet100-v3',exit_code=0,already_completed=True)])
def save():(out/'record.json').write_text(json.dumps(state,indent=2)+'\n')
save()
for layout,groups in [('tablet',['review']),('phone',['forms','detail','menus','review'])]:
 for group in groups:
  name='regression-'+group+'-'+layout+('100-v3' if group=='menus' else '100-v2')
  command=[str(base/'run-menu-regression-v1.py'),'--layout',layout] if group=='menus' else [str(base/'run-regression-v1.py'),'--name',name,'--method',group,'--layout',layout]
  with (out/(name+'.log')).open('wb') as log:r=subprocess.run([sys.executable,*command],stdout=log,stderr=subprocess.STDOUT)
  state['runs'].append(dict(name=name,exit_code=r.returncode));save();print(name,r.returncode,flush=True)
  require(r.returncode==0,'Continuation failed; retain original state')
state['completed']=True;save()
