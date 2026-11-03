"""Add historical controls, selected raw triplets and retained capture hashes."""
from pathlib import Path
import json,sys,hashlib
sys.path.insert(0,str(Path(__file__).parent))
from typed_publication_helpers_v2 import sha,public_host,write_json,copy_text,copy_raw
b=Path('artifacts/form-pickers');stage=b/'publication-v5';proof=stage/'validation';images=stage/'screenshots'
assert json.loads((stage/'preparation-state.json').read_text())['completed']
out=stage/'history-preparation-state.json';assert not out.exists()
record={'completed':False,'transformations':[],'historical_cohorts':[],'captures':[]}
def save():write_json(out,record)
save()
controller_names=['typed-old-baseline-v1-state.json','typed-matched-candidate-v1-state.json','full-day-matched-v1-state.json','full-day-matched-v3-state.json','all-day-control-v1-before-state.json','all-day-control-v1-after-state.json','file-native-single-v11-state.json','file-native-single-v12-state.json','ime-screen-control-v1-before-state.json','ime-screen-control-v1-after-state.json','ime-screen-control-v3-after-state.json','ime-screen-control-v4-after-state.json','ime-screen-control-v5-after-state.json','final-typed-native-lifecycle-v1-state.json','final-typed-native-lifecycle-v2-state.json','final-typed-native-lifecycle-v3-state.json','final-typed-native-typed-v1-state.json','final-typed-native-real-v1-state.json','final-typed-native-real-diagnosticv1-state.json','final-typed-native-real-diagnosticv2-state.json','final-typed-native-real-diagnosticv3-state.json','import-death-v1-state.json','final-typed-native-legacy-v4-state.json','final-typed-native-legacy-v5-state.json','final-typed-native-legacy-v6-state.json','final-typed-native-legacy-checkboxv1-state.json','final-typed-native-legacy-imediagnosticv1-state.json','final-typed-native-legacy-imesequencev1-state.json']
selection=json.loads((b/'raw-capture-selection-v2.json').read_text());assert selection['completed'] and len(selection['captures'])==33
roots={item['source_root'] for item in selection['captures']}
for name in controller_names:
 path=b/name;assert path.exists(),name;d=json.loads(path.read_text())
 # Controllers are normalized by an allowlist too. Private full host records,
 # recovery snapshots and their fingerprints are never copied or hashed.
 public={key:d[key] for key in ['completed','source_commit','production_source_commit','test_source_commit','common_test_source_commit','host_source_commit','methods','scope','expectation','failure_type','test_apk_sha256','frozen_inputs','wrapper_sha256','junit_passes','cleanup_checks','cleanup_all_passed','document_sources_verified','expected_zero_junit_passes_and_death_verified','independent_recovery_verified'] if key in d}
 public['source_controller']=name;public['cohorts']=[]
 for c in d.get('cohorts',[]):
  root=Path(c['output']) if c.get('output') else b/c['name'];roots.add(root.name)
  public['cohorts'].append({k:v for k,v in c.items() if k in ['name','completed','exit_code','native_completed','passes','cleanup_checks','cleanup_all_passed','document_sources_verified','expected_screen_reachability_failure_verified','screen_confirm_control_found','actual_ime_window_bounds','screen_confirm_bounds','production_source_commit','app_sha256','label','layout','expected_negative_executions','expected_negative_verified','expected_missing_all_day_control_verified','all_six_owned_case_boundaries_confirmed','change_runtime_type','expected_date_iso','observed_date_iso','host_completed','junit_failure_codes','tests_completed']})
 write_json(proof/'historical'/'controllers'/name,public)
for name in sorted(roots):
 root=b/name;assert (root/'record.json').exists(),name
 # Current v4 groups already have complete staged receipts.
 if (proof/'cohorts'/name).exists():continue
 host=json.loads((root/'record.json').read_text());dest=proof/'historical'/'cohorts'/name;dest.mkdir(parents=True)
 write_json(dest/'record.json',public_host(host))
 if (root/'tests.log').exists():record['transformations'].append(copy_text(root/'tests.log',dest/'tests.log'))
 for p in sorted((root/'captures').glob('form-*-record.json')):
  d=json.loads(p.read_text());sensitive_operational={'owner_nonce','instrumentation_pid'}
  if sensitive_operational & d.keys():
   public={k:v for k,v in d.items() if k not in sensitive_operational};write_json(dest/'cases'/p.name,public)
   record['transformations'].append({'source':str(p),'source_sha256':sha(p),'published_sha256':sha(dest/'cases'/p.name),'transformation':'JSON remove only owned operational owner_nonce and instrumentation_pid; retain observed results'})
  else:record['transformations'].append(copy_raw(p,dest/'cases'/p.name))
 record['historical_cohorts'].append({'name':name,'native_completed':host.get('completed'),'passes':host.get('passes'),'cleanup_checks':len(host.get('cleanup',{})),'cleanup_all_passed':bool(host.get('cleanup')) and all(c.get('passed') for c in host['cleanup'].values())})
for item in selection['captures']:
 root=b/item['source_root'];source=root/'captures'/item['source_file'];stem=source.stem;identity=item['name'];host=json.loads((root/'record.json').read_text());case_name='form-'+source.name.split('-',2)[1]+'-record.json';case=json.loads((source.parent/case_name).read_text())
 entries=[]
 for src,dest in [(source,images/(identity+'.png')),(source.with_suffix('.xml'),proof/'captures'/(identity+'.xml')),(source.with_name(stem+'-frame.json'),proof/'captures'/(identity+'-frame.json'))]:entries.append(copy_raw(src,dest))
 record['transformations']+=entries
 record['captures'].append({**item,'installed_build_commit':case.get('installed_build_commit'),'app_sha256':host['apk_sha256'],'test_apk_sha256':host['test_apk_sha256'],'case_passed':case.get('passed',False),'case_source_sha256':sha(source.parent/case_name),'raw_files':entries})
# Original native capture roots only; exclude the staging directory itself.
retained={}
for directory in b.rglob('captures'):
 if any(part.startswith('publication-') for part in directory.parts):continue
 if directory.is_dir():
  for p in directory.iterdir():
   if p.is_file() and p.suffix in ['.png','.xml','.json']:retained[str(p)]=sha(p)
manual=b/'file-matrix-v2-tablet100'/'manual-recovery-v1'
if manual.exists():
 for p in manual.rglob('*'):
  if p.is_file() and p.suffix in ['.png','.xml']:
   retained[str(p)]=sha(p)
write_json(proof/'retained-capture-hashes.json',{'scope':'Original native captures only; no private host/original/recovery snapshot hashes. Old interrupted outcomes are not upgraded.','files':retained})
write_json(proof/'capture-manifest.json',{'scope':'33 byte-exact PNG/XML/frame sets; historical and current identities remain distinct. No crops or image edits.','captures':record['captures']})
record.update(completed=True,selected_raw_sets=len(record['captures']),retained_capture_files=len(retained));save();print(json.dumps({k:record[k] for k in ['completed','selected_raw_sets','retained_capture_files']},indent=2))
