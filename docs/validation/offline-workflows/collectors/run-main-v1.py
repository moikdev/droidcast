from pathlib import Path
import json,os,subprocess,sys
base=Path('artifacts/offline-workflows');out=base/'main-pipeline-v1';out.mkdir(exist_ok=False)
assert json.loads((base/'native-typeid-parse-tablet-v1/state.json').read_text())['completed']
order=['typeid-pack','uuid-format','uuid-v7','uuid-ulid','uuid-typeid','lorem-paragraphs','lorem-sentences','json-lines','json-value','json-selection']
steps=[(case,'tablet') for case in order]+[(case,'phone') for case in ['typeid-parse',*order]]+[('legacy-review',layout) for layout in ['tablet','phone']]
state={'completed':False,'pid':os.getpid(),'steps':steps,'results':[],'prior_completed_complement':'native-typeid-parse-tablet-v1'}
def save():(out/'record.json').write_text(json.dumps(state,indent=2)+'\n')
save()
for index,(case,layout) in enumerate(steps):
 name='native-'+case+'-'+layout+'-v1';state['active_step']=name;save()
 with (out/(name+'.log')).open('wb') as log:r=subprocess.run([sys.executable,str(base/'run-case-v1.py'),'--case',case,'--layout',layout,'--name',name],stdout=log,stderr=subprocess.STDOUT)
 state['results'].append({'index':index,'name':name,'exit_code':r.returncode});save();print(name,'exit',r.returncode,flush=True)
 if r.returncode:raise SystemExit(r.returncode)
state['completed']=True;save();print('Completed eleven listed workflows per layout and default reviewed-JSON regressions')
