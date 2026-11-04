from pathlib import Path
import json,os,subprocess,sys
base=Path('artifacts/workflow-expansion');out=base/'regression-pipeline-v1';out.mkdir(exist_ok=False)
state={'completed':False,'pid':os.getpid(),'results':[]}
def save():(out/'record.json').write_text(json.dumps(state,indent=2)+'\n')
save()
for layout in ['tablet','phone']:
 for case in ['detail','form','reviewed']:
  name='regression-'+case+'-'+layout+'-v1';state['active_step']=name;save()
  with (out/(name+'.log')).open('wb') as log:r=subprocess.run([sys.executable,str(base/'run-owned-regressions-v1.py'),'--layout',layout,'--variant','current','--case',case,'--name',name],stdout=log,stderr=subprocess.STDOUT)
  state['results'].append({'name':name,'exit_code':r.returncode});save();print(name,'exit',r.returncode,flush=True)
  if r.returncode:raise SystemExit(r.returncode)
state['completed']=True;save()
