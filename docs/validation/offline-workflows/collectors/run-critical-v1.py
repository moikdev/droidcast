from pathlib import Path
import json,os,subprocess,sys
base=Path('artifacts/offline-workflows');out=base/'critical-pipeline-v1'
assert json.loads((base/'main-pipeline-v1/record.json').read_text())['completed'],'Main cohort incomplete'
out.mkdir(exist_ok=False)
steps=[(case,layout) for layout in ['phone','landscape','tablet'] for case in ['typeid-pack','json-lines','json-value']]
steps += [('json-selection',layout) for layout in ['phone','landscape']]
state={'completed':False,'pid':os.getpid(),'steps':steps,'results':[],'font_scale':2}
def save():(out/'record.json').write_text(json.dumps(state,indent=2)+'\n')
save()
for index,(case,layout) in enumerate(steps):
 name='critical-'+case+'-'+layout+'200-v1';state['active_step']=name;save()
 with (out/(name+'.log')).open('wb') as log:r=subprocess.run([sys.executable,str(base/'run-case-v1.py'),'--case',case,'--layout',layout,'--font','2','--name',name],stdout=log,stderr=subprocess.STDOUT)
 state['results'].append({'index':index,'name':name,'exit_code':r.returncode});save();print(name,'exit',r.returncode,flush=True)
 if r.returncode:raise SystemExit(r.returncode)
state['completed']=True;save();print('Completed eleven critical large-text workflow cases')
