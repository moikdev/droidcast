from pathlib import Path
import hashlib,json,os,subprocess,sys
base=Path('artifacts/offline-workflows')
def read(p):return json.loads(p.read_text())
def require(v,m):
 if not v:raise RuntimeError(m)
require(read(base/'build-harness-v7/state.json')['completed'],'Repaired build incomplete')
require(read(base/'final-native-pipeline-v4/record.json')['completed'],'Earlier immutable pipeline still active/incomplete')
out=base/'final-native-pipeline-v8';out.mkdir(exist_ok=False)
collector=base/'run-case-v7.py';h=hashlib.sha256(collector.read_bytes()).hexdigest()
state={'completed':False,'pid':os.getpid(),'collector_sha256':h,'runs':[]}
def save():(out/'record.json').write_text(json.dumps(state,indent=2)+'\n')
rows=[]
for app in ['old','previous','new']:
 controls=[('uuid-v7','tablet',1),('typeid-pack','landscape',2),('json-selection','phone',1)] if app!='previous' else [('typeid-pack','landscape',2)]
 for case,layout,font in controls:rows.append(('control-'+case+'-'+app+'-'+layout+str(font*100)+'-v8',case,layout,font,app))
rows.append(('return-regressions-tablet100-v8','return-regressions','tablet',1,'new'))
positive=[('return-regressions','phone',1),('offline-form-tablet','tablet',1),('offline-form-phone','phone',1),('json-selection','tablet',1)]
positive += [('critical-offline-form',layout,2) for layout in ['tablet','phone','landscape']]
positive += [('json-selection',layout,2) for layout in ['tablet','phone','landscape']]
positive += [('typeid-pack','landscape',1),('legacy-review','tablet',1),('legacy-review','phone',1)]
rows += [('final-'+case+'-'+layout+str(font*100)+'-v8',case,layout,font,app) for case,layout,font in positive for app in ['new']]
save()
for name,case,layout,font,app in rows:
 command=[sys.executable,str(collector),'--case',case,'--layout',layout,'--font',str(font),'--name',name,'--app',app]
 if case!='legacy-review':command+=['--completion-return']
 if app=='old':command+=['--expect-missing-return']
 if app=='previous':command+=['--expect-initial-target-failure']
 with (out/(name+'.log')).open('wb') as log:r=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
 state['runs'].append({'name':name,'app':app,'exit_code':r.returncode});save();print(name,r.returncode,flush=True)
 require(r.returncode==0,'Native pipeline failed; inspect the retained attempt before continuing')
 require(hashlib.sha256(collector.read_bytes()).hexdigest()==h,'Collector changed')
current=[row['name'] for row in state['runs'] if row['app']=='new'];negative=[row['name'] for row in state['runs'] if row['app']!='new']
receipts=[read(base/n/'state.json') for n in current]
require(all(r['completed'] and r['native_positive_completed'] and r['cleanup_passed'] and r['durable_data_unchanged'] and r['pre_existing_evidence_unchanged'] for r in receipts),'Incomplete final cohort')
for n in negative:require(read(base/n/'state.json')['intended_negative_completed'],'Negative control incomplete')
state.update(completed=True,cohorts=current,negative_controls=negative,executions=sum(r['passes'] for r in receipts),cleanup_checks=sum(r['cleanup_checks'] for r in receipts),production_apk_sha256=sorted({r['app_sha256'] for r in receipts}),instrumentation_apk_sha256=sorted({r['test_apk_sha256'] for r in receipts}));save();print(json.dumps({k:state[k] for k in ['completed','executions','cleanup_checks','production_apk_sha256','instrumentation_apk_sha256']},indent=2))
