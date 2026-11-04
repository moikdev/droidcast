from pathlib import Path
import json,os,subprocess,sys
from preservation import require
base=Path('artifacts/command-handoff');out=base/'final-verification-v3';out.mkdir(exist_ok=False)
require(json.loads((base/'owned-matrix-v3/record.json').read_text())['completed'],'Owned matrix incomplete')
state=dict(completed=False,pid=os.getpid(),runs=[])
def save():(out/'record.json').write_text(json.dumps(state,indent=2)+'\n')
def run(name,command):
 with (out/(name+'.log')).open('wb') as log:r=subprocess.run([sys.executable,*command],stdout=log,stderr=subprocess.STDOUT)
 state['runs'].append(dict(name=name,exit_code=r.returncode));save();print(name,r.returncode,flush=True)
 require(r.returncode==0,'Verification stops at failure; inspect retained cohort')
save()
for method,label in [('recreatedChildRejectsDisposedBackCallback','recreation'),('returnedCommandRejectsDisposedPreferencesCallback','preferences')]:
 name='matched-lifetime-'+label+'-old-tablet100-v3'
 run(name,[str(base/'run-owned-v1.py'),'--name',name,'--method',method,'--build','build-candidate-v1','--harness','owned-harness-v2','--expect-failure'])
for layout in ['tablet','phone']:
 for method,label in [('acknowledgementRevokesParentBeforeChildComposition','ack'),('duplicateLaunchRequestsTransferOnlyOnce','duplicate')]:
  name='protocol-'+label+'-'+layout+'100-v4'
  run(name,[str(base/'run-owned-v1.py'),'--name',name,'--method',method,'--layout',layout,'--build','build-candidate-v2','--harness','owned-harness-v3'])
for layout,font in [('tablet',2),('phone',2),('landscape',1),('landscape',2)]:
 name='critical-context-'+layout+str(font*100)+'-v3'
 run(name,[str(base/'run-owned-v1.py'),'--name',name,'--method','contextArgumentsAndFallbackReachChildAndCopy','--layout',layout,'--font',str(font),'--build','build-candidate-v2','--harness','owned-harness-v2'])
for layout in ['phone','landscape']:
 name='critical-invalid-'+layout+'200-v2'
 run(name,[str(base/'run-owned-v1.py'),'--name',name,'--method','invalidRequestsKeepTheParentAndClipboard','--layout',layout,'--font','2','--build','build-candidate-v2','--harness','owned-harness-v2'])
for layout,font in [('tablet',1),('phone',1),('phone',2),('landscape',2)]:
 name='final-quick-'+layout+str(font*100)+'-v3'
 run(name,[str(base/'run-quick-control-v2.py'),'--name',name,'--app','new','--layout',layout,'--font',str(font)])
for layout in ['tablet','phone']:
 for group in ['forms','detail','menus','review']:
  name='regression-'+group+'-'+layout+'100-v2'
  run(name,[str(base/'run-regression-v1.py'),'--name',name,'--method',group,'--layout',layout])
state['completed']=True;save()
