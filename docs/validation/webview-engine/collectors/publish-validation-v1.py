from pathlib import Path
import hashlib,json,re,shutil,subprocess
from xml.etree import ElementTree as ET
B=Path('artifacts/webview-compatibility');D=Path('docs/validation/webview-engine');S=Path('docs/screenshots/webview-engine')
sha=lambda b:hashlib.sha256(b).hexdigest()
read=lambda p:json.loads(Path(p).read_text())
def require(v,m):
 if not v:raise ValueError(m)
roots={'legacy100':B/'final-v5/legacy100','legacy200':B/'final-v5/legacy200','modern-tablet100':B/'final-v4/modern-tablet100','modern-phone200':B/'final-v4/modern-phone200','modern-landscape200':B/'final-v4/modern-landscape200','review-lifecycle':B/'shared-regressions-v2/review-lifecycle','handoff':B/'shared-regressions-v2/handoff','paste':B/'shared-regressions-v2/paste','legacy-common-positive':B/'final-v6/legacy-positive','legacy-common-negative':B/'final-v6/legacy-old-control'}
for name,root in roots.items():
 r=read(root/'record.json');require(r['completed'],name+' is incomplete')
 require(all(v['passed'] for v in r['cleanup'].values()),name+' restoration failed')
require(read(B/'shared-regressions-v2/record.json')['completed'],'Outer preservation incomplete')
require(not D.exists() and not S.exists(),'Publication already exists; inspect before changing it')
D.mkdir(parents=True);S.mkdir(parents=True)
bindings=[];projections=[]
def put(source,destination,xml=False,redact=False):
 original=source.read_bytes();value=original
 if redact:
  for old,new in [(str(Path.cwd()),'<checkout>'),(str(Path.home()),'<host-home>')]:
   if xml:new=new.replace('<','&lt;').replace('>','&gt;')
   value=value.replace(old.encode(),new.encode())
 destination.parent.mkdir(parents=True,exist_ok=True);destination.write_bytes(value)
 bindings.append({'original':str(source.resolve().relative_to(Path.cwd())),'original_sha256':sha(original),'public':str(destination),'public_sha256':sha(value),'transform':'replace checkout/home with escaped placeholders' if redact and xml else 'replace checkout/home with placeholders' if redact else 'identity'})
 if xml:ET.fromstring(value)
 return destination
record_keys={'completed','expected_negative','runner_sha256','apk_sha256','test_apk_sha256','methods','api','font_scale','layout','cleanup','provider_before','original_geometry','original_font_scale','fixture_sha256','instrumentation_exit_code','passes','failures','initialization_error','native_expected_outcome','preserved_file_count','tests_completed','tests_completed','expected','classes','test_filter','installed_apks_match'}
case_keys=set('scope case passed android_api provider provider_version font_scale time_to_result_ms runtime_error published_native_tree caller_query_restored explicit_settings_destination compile_body_executed invalid_syntax_caught api_probe_result live_action_published closed_owner_remained_inactive preflight_bridge_render_rejected preflight_host_api_rejected stale_settings_launches replacement_not_closed second_failure_scope fresh_recovery_actions palette_preferences_restored fixture_cleanup_owner target_witnesses text_witnesses text_setup fixture_bytecode_scope width_dp height_dp launcher_query_preserved obsolete_host_and_chrome_callbacks_rejected one_transfer_before_native_composition owned_data_removed preexisting_installations_and_data_preserved protocol_setup context_argument_fallback_values_verified copy_exact child_raw_grant_absent duplicate_after_copy_rejected duplicate_probe_setup return_and_escape_cancelled_without_copy cancelled_before_run external_caller_uses_separate_package_and_uid preview_no_execution_and_clipboard_unchanged no_view_completion_released_input async_and_sync_host_api_rejections_released_input live_write_and_publication_controls_succeeded stale_write_render_error_depth_and_close_blocked clipboard_unchanged_until_copy exact_unicode_output_copied executed_command returned_to_external_caller source_text_and_selection_unchanged native_code_monospace native_format_contract_started archive_sha256 command_sha256 probe_command_sha256 fixture_child_sha256 fixture_manifest_sha256 fixture_parent_sha256 view_probe_sha256 clipboard_restoration_scope pointer_taps paused_parent_probe'.split())
def project(source,destination,keys):
 value=read(source);payload={k:value[k] for k in sorted(keys) if k in value};destination.parent.mkdir(parents=True,exist_ok=True);destination.write_text(json.dumps(payload,indent=2)+'\n')
 projections.append({'original':str(source.resolve().relative_to(Path.cwd())),'original_sha256':sha(source.read_bytes()),'public':str(destination),'public_sha256':sha(destination.read_bytes()),'keys':sorted(payload)})
 return payload
matrix=[];capture_hashes={}
for name,root in roots.items():
 r=project(root/'record.json',D/'cohorts'/name/'record.json',record_keys)
 put(root/'tests.log',D/'cohorts'/name/'tests.log',redact=True)
 matrix.append({'cohort':name,'passes':r.get('passes'),'junit_failures':r.get('failures',0),'expected_negative':r.get('expected_negative',False),'cleanup_checks':len(r['cleanup']),'app_sha256':r['apk_sha256'],'test_apk_sha256':r['test_apk_sha256']})
 for p in sorted((root/'captures').iterdir()):
  require(p.is_file() and p.suffix in ('.png','.xml','.json'),'Unexpected capture file')
  capture_hashes[str(p)]=sha(p.read_bytes())
  if p.name.startswith('case-') or p.name.endswith('-record.json'):
   project(p,D/'cohorts'/name/'cases'/p.name,case_keys)
  else:
   target=(S/name/p.name) if p.suffix=='.png' else (D/'cohorts'/name/'captures'/p.name)
   put(p,target,xml=p.suffix=='.xml')
# Preserve all source identities; each names its own compiled cohort.
put(B/'unused-stamp-build/identity.json',D/'identities/unused-stamp-build.json')
for version in ['v1','v2','v3','v4','v5','v6']:
 p=B/('candidate-'+version)/'identity.json'
 if p.exists():put(p,D/'identities'/('candidate-'+version+'.json'))
for version in ['v4','v5','v6']:
 identity=read(B/('candidate-'+version)/'identity.json')
 for path,digest in identity['sources'].items():
  data=subprocess.check_output(['git','show',identity['test_source']+':'+path]);require(sha(data)==digest,'Frozen source mismatch: '+path)
for name in ['final-runtime-build-v2.log','final-harness-build-v3.log','final-harness-build-v4.log','final-harness-build-v5.log','final-benchmark-build-v2.log','final-benchmark-harness-build-v3.log','python-final-v4.log','python-optimized-final-v4.log']:
 put(B/name,D/'checks'/name,redact=True)
for p in sorted(Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')):put(p,D/'checks/jvm'/p.name,xml=True,redact=True)
for variant in ['debug','benchmark']:put(Path('app/build/reports/lint-results-'+variant+'.xml'),D/'checks'/('lint-results-'+variant+'.xml'),xml=True,redact=True)
shared=read(B/'shared-regressions-v2/record.json');(D/'shared-preservation.json').write_text(json.dumps({k:shared[k] for k in ['completed','app_sha256','test_apk_sha256','collector_sha256','cohorts','restoration','preserved_files']},indent=2)+'\n')
# Historical outcomes are retained separately, including initialization failures.
attempt_roots=[B/'legacy-before',B/'legacy-candidate-v1',B/'legacy-candidate-v2',B/'modern-tablet-v1',B/'legacy-final100-v1',B/'modern-tablet-final100-v1',B/'legacy-final200-v1',B/'modern-phone-final200-v1',B/'legacy-final200-v2',B/'modern-phone-final200-v2',B/'legacy-old-control-v3',B/'modern-landscape-final200-v1',B/'final-v4/legacy100',B/'final-v5/legacy-old-control']
attempt_roots += [B/'shared-regressions-v1'/name for name in ['review-lifecycle','handoff','paste']]
history=[]
for root in attempt_roots:
 p=root/'record.json'
 if not p.exists():p=root/'run-record.json'
 r=read(p);log=(root/'tests.log').read_text() if (root/'tests.log').exists() else ''
 init='INSTRUMENTATION_STATUS: test=initializationError' in log
 row={'attempt':str(root.relative_to(B)),'completed':r.get('completed',False),'junit_passes':len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: 0$',log,re.M)),'junit_failures':len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: -2$',log,re.M)),'initialization_failure':init,'native_cases_reaching_execution':0 if init else len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: (?:0|-2)$',log,re.M)),'record_sha256':sha(p.read_bytes()),'test_log_sha256':sha((root/'tests.log').read_bytes()) if log else None,'apk_sha256':r.get('apk_sha256'),'test_apk_sha256':r.get('test_apk_sha256'),'cleanup':r.get('cleanup',{}),'final_credit':False}
 history.append(row);put(root/'tests.log',D/'history'/root.relative_to(B)/'tests.log',redact=True) if log else None
 for recovery in root.glob('*recovery.json'):
  if recovery.name.startswith('private'):
   continue
  safe=read(recovery)
  (D/'history'/root.relative_to(B)).mkdir(parents=True,exist_ok=True)
  project(recovery,D/'history'/root.relative_to(B)/recovery.name,set('completed original_outer_run_still_failed original_junit_passes original_passes original_failures original_run_record_sha256 checks preserved_files native_executions reason'.split()))
(D/'historical-attempts.json').write_text(json.dumps(history,indent=2)+'\n')
(D/'matrix.json').write_text(json.dumps(matrix,indent=2)+'\n')
(D/'capture-original-hashes.json').write_text(json.dumps(capture_hashes,indent=2)+'\n')
# Collector copies have no host paths or private inputs; retained source hashes
# bind the executed local originals to these declared redactions.
for p in [Path(__file__),B/'run-shared-regressions-v2.py',B/'run-final-legacy-v5.py',B/'run-final-modern-v4.py']:
 put(p,D/'collectors'/p.name,redact=True)
(D/'bindings.json').write_text(json.dumps(bindings,indent=2)+'\n');(D/'projections.json').write_text(json.dumps(projections,indent=2)+'\n')
for p in D.rglob('*.xml'):ET.parse(p)
for p in list(D.rglob('*'))+list(S.rglob('*')):
 if p.is_file():require(str(Path.home()).encode() not in p.read_bytes(),'Unredacted host path: '+str(p))
files={str(p):sha(p.read_bytes()) for root in [D,S] for p in root.rglob('*') if p.is_file()}
(D/'checkpoint.json').write_text(json.dumps({'scope':'Recorded emulator observations and source/binary bindings; no broad parity certification','public_files':files,'source_head':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()},indent=2)+'\n')
print(json.dumps({'passes':sum(r['passes'] for r in matrix if not r['expected_negative']),'positive_cohorts':sum(not r['expected_negative'] for r in matrix),'negative_failures':sum(r['junit_failures'] for r in matrix if r['expected_negative']),'cleanup_checks':sum(r['cleanup_checks'] for r in matrix),'screenshots':sum(1 for p in S.rglob('*.png')),'public_files':len(files)}))
