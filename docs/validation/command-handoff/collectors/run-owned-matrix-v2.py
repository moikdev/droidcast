from pathlib import Path
import json,os,subprocess,sys
from preservation import require
base=Path('artifacts/command-handoff');out=base/'owned-matrix-v2';out.mkdir(exist_ok=False)
methods=[('recreatedChildRejectsDisposedBackCallback','recreation'),('returnedCommandRejectsDisposedPreferencesCallback','preferences'),('contextArgumentsAndFallbackReachChildAndCopy','context'),('acknowledgementRevokesParentBeforeChildComposition','ack'),('duplicateLaunchRequestsTransferOnlyOnce','duplicate'),('queuedRequestCannotLaunchAfterParentCancellation','cancel'),('invalidRequestsKeepTheParentAndClipboard','invalid'),('ordinaryRelaunchClearsContextAndFallback','ordinary')]
state=dict(completed=False,pid=os.getpid(),runs=[])
def save():(out/'record.json').write_text(json.dumps(state,indent=2)+'\n')
save()
for layout in ['tablet','phone']:
 for method,label in methods:
  name='owned-'+label+'-'+layout+'100-v2'
  command=[sys.executable,str(base/'run-owned-v1.py'),'--name',name,'--method',method,'--layout',layout,'--build','build-candidate-v2','--harness','owned-harness-v1']
  with (out/(name+'.log')).open('wb') as log:r=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
  state['runs'].append(dict(name=name,exit_code=r.returncode));save();print(name,r.returncode,flush=True)
  require(r.returncode==0,'Case failed; pipeline stops, preserve evidence and inspect')
state['completed']=True;save()
