from pathlib import Path
import json,os,subprocess,sys
base=Path('artifacts/workflow-expansion');out=base/'network-fonts-pipeline-v1';out.mkdir(exist_ok=False)
steps=[('run-network-v1.py',['--name','frozen-'+case+'-phone-v1','--variant','current','--layout','phone','--case',case]) for case in ['translate','weather']]
steps += [('run-fetch-v3.py',['--name','critical-'+case+'-'+layout+'200-v1','--variant','current','--layout',layout,'--case',case,'--font','2']) for layout in ['phone','landscape','tablet'] for case in ['policy','https']]
state={'completed':False,'pid':os.getpid(),'steps':steps,'results':[]}
def save():(out/'record.json').write_text(json.dumps(state,indent=2)+'\n')
save()
for index,(script,args) in enumerate(steps):
 state['active_step']=index;save()
 with (out/(str(index)+'.log')).open('wb') as log:r=subprocess.run([sys.executable,str(base/script),*args],stdout=log,stderr=subprocess.STDOUT)
 state['results'].append({'index':index,'exit_code':r.returncode});save()
 print('step',index,'exit',r.returncode,flush=True)
 if r.returncode:raise SystemExit(r.returncode)
state['completed']=True;save();print('Completed phone service observations and six critical-font transport cases')
