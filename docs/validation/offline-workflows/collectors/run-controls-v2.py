from pathlib import Path
import hashlib,json,os,subprocess,sys
base=Path('artifacts/offline-workflows');out=base/'controls-pipeline-v2';out.mkdir(exist_ok=False)
state={'completed':False,'pid':os.getpid(),'collector_sha256':hashlib.sha256((base/'run-case-v2.py').read_bytes()).hexdigest(),'runs':[]}
def save():(out/'record.json').write_text(json.dumps(state,indent=2)+'\n')
# Same instrumentation and exact cases/profiles on both app APKs.
rows=[('uuid-v7','tablet',1),('typeid-pack','landscape',2),('json-selection','phone',1)]
save()
for app in ['old','new']:
 for case,layout,font in rows:
  name='control-'+case+'-'+app+'-'+layout+str(font*100)+'-v2'
  command=[sys.executable,str(base/'run-case-v2.py'),'--case',case,'--layout',layout,'--font',str(font),'--name',name,'--app',app,'--completion-return']
  if app=='old':command+=['--expect-missing-return']
  with (out/(name+'.log')).open('wb') as log:r=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
  state['runs'].append({'name':name,'exit_code':r.returncode});save();print(name,r.returncode,flush=True)
  if r.returncode:raise RuntimeError('Control pipeline failed; inspect retained attempt')
state['completed']=True;save()
