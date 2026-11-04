from pathlib import Path
import hashlib,json,os,subprocess,sys
base=Path('artifacts/offline-workflows');out=base/'final-native-pipeline-v4';out.mkdir(exist_ok=False)
def read(p):return json.loads(p.read_text())
def require(v,m):
 if not v:raise RuntimeError(m)
collector=base/'run-case-v4.py';h=hashlib.sha256(collector.read_bytes()).hexdigest()
retained=['control-uuid-v7-new-tablet100-v2','control-typeid-pack-new-landscape200-v2','control-json-selection-new-phone100-v2','return-regressions-tablet100-v4']
for name in retained:require(read(base/name/'state.json')['native_positive_completed'],'Retained member incomplete')
state={'completed':False,'pid':os.getpid(),'collector_sha256':h,'already_completed_cohorts':retained,'runs':[]}
def save():(out/'record.json').write_text(json.dumps(state,indent=2)+'\n')
rows=[('return-regressions','phone',1),('offline-form-tablet','tablet',1),('offline-form-phone','phone',1),('json-selection','tablet',1)]
rows += [('critical-offline-form',layout,2) for layout in ['tablet','phone','landscape']]
rows += [('json-selection',layout,2) for layout in ['tablet','phone','landscape']]
rows += [('typeid-pack','landscape',1),('legacy-review','tablet',1),('legacy-review','phone',1)]
save()
for case,layout,font in rows:
 name='final-'+case+'-'+layout+str(font*100)+'-v4';command=[sys.executable,str(collector),'--case',case,'--layout',layout,'--font',str(font),'--name',name,'--app','new','--completion-return']
 if case=='legacy-review':command.remove('--completion-return')
 with (out/(name+'.log')).open('wb') as log:r=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
 state['runs'].append({'name':name,'exit_code':r.returncode});save();print(name,r.returncode,flush=True)
 require(r.returncode==0,'Native pipeline failed; inspect the retained attempt before continuing')
 require(hashlib.sha256(collector.read_bytes()).hexdigest()==h,'Collector changed')
all_names=retained+[r['name'] for r in state['runs']];receipts=[read(base/n/'state.json') for n in all_names]
require(all(r['completed'] and r['native_positive_completed'] and r['cleanup_passed'] and r['durable_data_unchanged'] and r['pre_existing_evidence_unchanged'] for r in receipts),'Incomplete final cohort')
state.update(completed=True,cohorts=all_names,executions=sum(r['passes'] for r in receipts),cleanup_checks=sum(r['cleanup_checks'] for r in receipts),production_apk_sha256=sorted({r['app_sha256'] for r in receipts}),instrumentation_apk_sha256=sorted({r['test_apk_sha256'] for r in receipts}));save();print(json.dumps({k:state[k] for k in ['completed','executions','cleanup_checks','production_apk_sha256','instrumentation_apk_sha256']},indent=2))
