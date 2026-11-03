from pathlib import Path
import collections,hashlib,json,re,shutil,xml.etree.ElementTree as ET
B=Path('artifacts/markdown-detail');PLAN=json.loads((B/'audit-repair-publication-plan-v3.json').read_text())
OUT=Path(PLAN['public_validation']);IMAGES=Path(PLAN['public_images']);sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
def read(name):return json.loads((B/name).read_text())
final=read(PLAN['final_native']);build=read(PLAN['final_candidate']);local=read(PLAN['final_local']);before=read(PLAN['before']);after=read(PLAN['after'])
assert final['completed'] and final['native_executions']==185 and local['completed'] and build['completed']
assert before['completed'] and after['completed'] and before['expected_negative_executions']==after['positive_executions']==12
assert before['test_apk_sha256']==after['test_apk_sha256']==build['common_test_apk_sha256']
assert all(sha(p)==h for p,h in build['source_hashes'].items())
initial=json.loads(Path(PLAN['initial_checkpoint']).read_text());assert all(sha(p)==h for p,h in initial['public_files'].items()),'Keep the original generation unchanged'
assert not OUT.exists() and not IMAGES.exists(),'Never overwrite an evidence generation'
CASE_KEYS=set(json.loads(Path('docs/validation/extension-compatibility/native-detail/case-projection.json').read_text())['root_keys_kept'])
CASE_KEYS.update('cached_image_visibility_cycles cached_image_visibility_scope native_text_edge_glyph_pixels table_limit_source_units table_limit_columns table_limit_body_rows parser_limit_is_readable_and_next_document_works object_tag_publication_scope object_tag_binding_before object_tag_binding_after object_tag_focus_before_id object_tag_focus_after_id object_tag_unchanged_callback_preserved_and_changed_visual_rejected object_tag_keyboard_focus_and_current_enter_preserved'.split())
HOST_KEYS=set('completed avd layout font_scale classes expected runner_sha256 archive_sha256 typed_workflow_archive_sha256 cleanup_helper_sha256 clipboard_helper_sha256 document_helper_sha256 apk_sha256 test_apk_sha256 installed_apks_match instrumentation_exit_code passes tests_completed owned_extension_count cleanup shared_regression_cohort'.split())
STATE_KEYS=set('runs runner_source_commit current_repository_source_commit apk_build_source_hashes frozen_build_record python_record query_deadline_record frozen_android_results python_execution retained_native_executions fresh_native_executions retained_native_record separate_transport_recovery probes changed_sources_since_apk_build implementation_commit android_test_changes_from_common completed production_source_commit test_source_commit common_test_source_commit source_hashes production_sources_unchanged sources_unchanged app_sha256 test_apk_sha256 common_test_apk_sha256 candidate_directory build_exit_code exit_code negative_jvm negative_jvm_reuse jvm lint android_test_sources_match_common host_json_provider_not_defined_in_app_or_instrumentation native_executions expected_negative_executions positive_executions junit_passes cleanup_checks preserved_evidence_files preservation_checks retained_jvm debug_build_and_lint_record python_reuse node_reuse benchmark_apk_sha256 benchmark_test_apk_sha256 log_sha256 failure_type preservation_failure_type scope paired_before_record'.split())
COHORT_KEYS=set('retained retained_from runner_sha256 passes group layout font_scale expected output completed exit_code expected_negative_executions junit_passes case_receipts cleanup_checks cleanup_all_passed'.split())
def normalize(text,xml=False):
 text=re.sub(r'/Users/[^/\s]+/\.t3/(?:scratch|projects)/[^/\s\"<>]+','<checkout>',text)
 text=re.sub(r'/Users/[^/\s]+/Library/Android/sdk','<android-sdk>',text)
 text=re.sub(r'/Users/[^/\s]+/Library/Java/JavaVirtualMachines/[^/\s]+/Contents/Home','<java-home>',text)
 text=re.sub(r'/Users/[^/\s]+/\.gradle','<gradle-cache>',text)
 text=re.sub(r'/Users/[^/\s\"<>]+','<user-home>',text)
 text=re.sub(r'(?:/private)?/var/folders/[^\s\"<>]+','<temporary>',text)
 if xml:text=re.sub(r'<(checkout|android-sdk|java-home|gradle-cache|user-home|temporary)>',r'&lt;\1&gt;',text)
 return text
def clean(value):
 if isinstance(value,str):return normalize(value)
 if isinstance(value,list):return [clean(x) for x in value]
 if isinstance(value,dict):return {k:clean(v) for k,v in value.items()}
 return value
def safe(raw):
 assert not re.search(rb'/Users/[A-Za-z0-9_.-]+/',raw)
 assert not re.search(rb'[?&](?:X-Amz-Signature|X-Goog-Signature|Signature)=',raw,re.I)
states=['audit-controls-build-v1-state.json','audit-controls-build-v2-state.json','audit-controls-build-v3-state.json','audit-controls-build-v4-state.json',
 'audit-repair-build-v1-state.json','audit-repair-build-v2-state.json','audit-repair-build-v3-state.json','audit-final-local-v1-state.json','audit-final-local-v2-state.json','audit-final-native-v1-state.json','audit-final-native-v2-state.json','transport-runner-python-v1-state.json','private-query-deadline-v1-state.json','image-state-diagnostic-build-v1.json','image-visibility-stress-build-v1.json','image-visibility-stress-build-v2.json','image-visibility-stress-v1-state.json',PLAN['final_candidate'],PLAN['before'],PLAN['after'],PLAN['final_native'],PLAN['final_local']]
for name in states:read(name)
for entry in PLAN['frames']:
 for extension in ['.png','.xml','-frame.json']:
  p=B/entry['origin_cohort']/'captures'/(entry['basename']+extension);assert p.is_file(),p
  if extension=='.xml':ET.fromstring(p.read_bytes())
  if extension!='.png':safe(p.read_bytes())
required_checks=['audit-controls-build-v1.log', 'audit-controls-build-v2.log', 'audit-controls-build-v3.log', 'audit-controls-build-v4.log', 'audit-controls-jvm-negative-v2.log', 'audit-repair-build-v1.log', 'audit-repair-build-v2.log', 'audit-final-local-v1.log', 'build-audit-controls-v4.py', 'run-audit-before-controls-v1.py', 'run-audit-after-controls-v1.py', 'run-audit-final-native-v1.py', 'build-audit-repair-v2.py', 'build-audit-final-local-v1.py', 'audit-repair-build-v3.log', 'audit-final-local-v2.log', 'build-audit-repair-v3.py', 'build-audit-final-local-v2.py', 'run-audit-final-native-v2.py', 'image-state-diagnostic-build-v1.log', 'image-visibility-stress-build-v1.log', 'image-visibility-stress-build-v2.log', 'build-image-state-diagnostic-v1.py', 'build-image-visibility-stress-v1.py', 'build-image-visibility-stress-v2.py', 'run-image-visibility-stress-v1.py', 'research/json-java-20240303-v1/record.json', 'transport-runner-python-v1-normal.log', 'transport-runner-python-v1-optimized.log', 'check-transport-runner-python-v1.py', 'check-private-query-deadline-v1.py', 'prepare-final-runtime-identity-v4.py', 'run-audit-final-native-v3.py', 'recover-transport-cohort-v1.py', 'private-query-deadline-v1/before.log', 'private-query-deadline-v1/after.log', 'private-query-deadline-v1/worker.py', 'private-query-deadline-v1/delayed_client.py']
for name in required_checks:assert (B/name).is_file(),name
assert len(list((Path(build['candidate_directory'])/'jvm').glob('TEST-*.xml')))==29
assert len(list((B/'audit-repair-build-v1-failed-jvm').glob('TEST-*.xml')))==29
for variant in ['debug','benchmark']:assert (Path(local['candidate_directory'])/('lint-'+variant+'.xml')).is_file()
assert (Path(read('audit-controls-build-v2-state.json')['candidate_directory'])/'old-parser-jvm-negative.xml').is_file()
recovery=read('transport-cohort-recovery-v1/public-recovery.json');assert recovery['completed'] and len(recovery['checks'])==9 and all(recovery['checks'].values())
assert read('private-query-deadline-v1-state.json')['completed']
assert final['retained_native_executions']==164 and final['fresh_native_executions']==21
OUT.mkdir(parents=True);IMAGES.mkdir(parents=True)
def write(path,data):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(clean(data),indent=2,ensure_ascii=False)+'\n')
bindings=[];cohorts={};omitted={}
for name in states:
 d=read(name);value={k:v for k,v in d.items() if k in STATE_KEYS};value['cohorts']=[{k:v for k,v in c.items() if k in COHORT_KEYS} for c in d.get('cohorts',[])]
 write(OUT/'pipelines'/name,value)
 for c in d.get('cohorts',[]):
  if c.get('output') and (Path(c['output'])/'record.json').is_file():cohorts[Path(c['output']).name]=Path(c['output'])
cohorts['image-state-diagnostic-v1-landscape100']=B/'image-state-diagnostic-v1-landscape100'
for name,origin in sorted(cohorts.items()):
 d=json.loads((origin/'record.json').read_text());write(OUT/'cohorts'/name/'record.json',{k:v for k,v in d.items() if k in HOST_KEYS})
 dest=OUT/'cohorts'/name/'tests.log';dest.write_text(normalize((origin/'tests.log').read_text()))
 for source in sorted((origin/'captures').glob('*-record.json')):
  d=json.loads(source.read_text());keep={k:v for k,v in d.items() if k in CASE_KEYS or re.fullmatch(r'(metadata-(title|value|url)|image-information)-(line-witnesses|natural-lines)',k)}
  omitted[name+'/'+source.name]=sorted(set(d)-set(keep));write(OUT/'cohorts'/name/source.name,keep)
for entry in PLAN['frames']:
 for extension in ['.png','.xml','-frame.json']:
  source=B/entry['origin_cohort']/'captures'/(entry['basename']+extension)
  dest=(IMAGES if extension=='.png' else OUT/'frames')/(entry['name']+extension);dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,dest)
  assert sha(source)==sha(dest);bindings.append(dict(public=str(dest),origin=str(source.relative_to(B)),sha256=sha(source),transformation='none'))
write(OUT/'frames.json',PLAN['frames']);write(OUT/'publication-plan.json',PLAN)

# Only this explicit public receipt is copied. Private recovery snapshots,
# original fingerprints, VM disks and key-bearing startup logs stay excluded.
source=B/'transport-cohort-recovery-v1/public-recovery.json';dest=OUT/'transport-recovery/public-recovery.json';safe(source.read_bytes());dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,dest)
bindings.append(dict(public=str(dest),origin=str(source.relative_to(B)),sha256=sha(source),transformation='none'))
recovered=B/'transport-cohort-recovery-v1/recovered-captures'
for source in recovered.glob('*-record.json'):
 d=json.loads(source.read_text());keep={k:v for k,v in d.items() if k in CASE_KEYS};write(OUT/'transport-recovery/recovered-cases'/source.name,keep)

checks=['audit-controls-build-v1.log', 'audit-controls-build-v2.log', 'audit-controls-build-v3.log', 'audit-controls-build-v4.log', 'audit-controls-jvm-negative-v2.log', 'audit-repair-build-v1.log', 'audit-repair-build-v2.log', 'audit-final-local-v1.log', 'build-audit-controls-v4.py', 'run-audit-before-controls-v1.py', 'run-audit-after-controls-v1.py', 'run-audit-final-native-v1.py', 'build-audit-repair-v2.py', 'build-audit-final-local-v1.py', 'audit-repair-build-v3.log', 'audit-final-local-v2.log', 'build-audit-repair-v3.py', 'build-audit-final-local-v2.py', 'run-audit-final-native-v2.py', 'image-state-diagnostic-build-v1.log', 'image-visibility-stress-build-v1.log', 'image-visibility-stress-build-v2.log', 'build-image-state-diagnostic-v1.py', 'build-image-visibility-stress-v1.py', 'build-image-visibility-stress-v2.py', 'run-image-visibility-stress-v1.py', 'transport-runner-python-v1-normal.log', 'transport-runner-python-v1-optimized.log', 'check-transport-runner-python-v1.py', 'check-private-query-deadline-v1.py', 'prepare-final-runtime-identity-v4.py', 'run-audit-final-native-v3.py', 'recover-transport-cohort-v1.py', 'private-query-deadline-v1/before.log', 'private-query-deadline-v1/after.log', 'private-query-deadline-v1/worker.py', 'private-query-deadline-v1/delayed_client.py']
sources=[(B/x,OUT/'checks'/x) for x in checks]
sources += [(p,OUT/'checks/jvm'/p.name) for p in (Path(build['candidate_directory'])/'jvm').glob('TEST-*.xml')]
sources += [(p,OUT/'checks/failed-host-json-v1'/p.name) for p in (B/'audit-repair-build-v1-failed-jvm').glob('TEST-*.xml')]
sources += [(Path(local['candidate_directory'])/('lint-'+v+'.xml'),OUT/'checks'/('lint-'+v+'.xml')) for v in ['debug','benchmark']]
negative=Path(read('audit-controls-build-v2-state.json')['candidate_directory'])/'old-parser-jvm-negative.xml';sources.append((negative,OUT/'checks/old-parser-jvm-negative.xml'))
for source,dest in sources:
 dest.parent.mkdir(parents=True,exist_ok=True);dest.write_text(normalize(source.read_text(),xml=source.suffix=='.xml'))
 if source.suffix=='.xml':ET.fromstring(dest.read_bytes())
 bindings.append(dict(public=str(dest),origin=str(source.relative_to(B)),sha256=sha(source),public_sha256=sha(dest),transformation='normalize-host-prefixes-xml-safe-v1'))
source=B/'research/json-java-20240303-v1/record.json';dest=OUT/'research/json-java-20240303-v1.json';dest.parent.mkdir(parents=True,exist_ok=True);safe(source.read_bytes());shutil.copyfile(source,dest)
bindings.append(dict(public=str(dest),origin=str(source.relative_to(B)),sha256=sha(source),transformation='none'))
write(OUT/'final-source-identity.json',build);write(OUT/'local-checks.json',local)
retained=[]
for name,origin in sorted(cohorts.items()):
 for folder in ['captures','extension-grid-evidence','readiness-evidence']:
  for p in sorted((origin/folder).glob('*')):
   if p.is_file() and (p.suffix in ['.png','.xml'] or p.name.endswith('-frame.json')):retained.append(dict(origin=name+'/'+str(p.relative_to(origin)),sha256=sha(p)))
for p in sorted((B/'transport-cohort-recovery-v1/recovered-captures').glob('*')):
 if p.is_file() and (p.suffix in ['.png','.xml'] or p.name.endswith('-frame.json')):retained.append(dict(origin='transport-cohort-recovery-v1/recovered-captures/'+p.name,sha256=sha(p)))
write(OUT/'retained-capture-hashes.json',retained);write(OUT/'publication-bindings.json',bindings)
write(OUT/'case-projection.json',dict(root_keys_kept=sorted(CASE_KEYS),root_keys_omitted=omitted,private_clipboard_and_recovery_inputs='Excluded; snapshots, backups and original clipboard fingerprints are never read or hashed for publication.'))
shutil.copyfile(__file__,OUT/'publish.py')
files=[p for root in [OUT,IMAGES] for p in root.rglob('*') if p.is_file()]
for p in files:
 if p.suffix in ['.json','.xml','.py','.log']:safe(p.read_bytes())
 if p.suffix=='.xml':ET.fromstring(p.read_bytes())
write(OUT/'checkpoint.json',dict(schema_version=1,issue=89,implementation_commit=build['production_source_commit'],test_source_commit=build['test_source_commit'],
 app_sha256=build['app_sha256'],test_apk_sha256=build['test_apk_sha256'],runner_source_commit=build['runner_source_commit'],current_repository_source_commit=build['current_repository_source_commit'],apk_build_source_hashes=build['apk_build_source_hashes'],retained_native_executions=final['retained_native_executions'],fresh_native_executions=final['fresh_native_executions'],separate_transport_recovery_checks=9,transport_failed_cleanup_checks_retained=20,android_test_sources_match_common=build['android_test_sources_match_common'],android_test_changes_from_common=build['android_test_changes_from_common'],common_test_source_commit=build['common_test_source_commit'],common_test_apk_sha256=build['common_test_apk_sha256'],
 final_native_executions=185,final_native_cohorts=len(final['cohorts']),final_native_cleanup_checks=final['cleanup_checks'],separate_preservation_checks=len(final['preservation_checks']),preserved_evidence_files=136,
 matched_old_expected_negatives=12,matched_repaired_positives=12,matched_old_cleanup_checks=before['cleanup_checks'],matched_repaired_cleanup_checks=after['cleanup_checks'],
 source_hashes=build['source_hashes'],selected_raw_capture_sets=len(PLAN['frames']),retained_capture_hashes=len(retained),
 initial_publication_commit=PLAN['initial_publication_commit'],initial_checkpoint=PLAN['initial_checkpoint'],initial_runtime_status='Historical 4bb10aa milestone; includes the three audited defects, not current repair credit.',
 scope='Scoped repaired native Markdown/metadata checkpoint. Known legacy menu failures stay under #91. No full Store/platform/OEM/accessibility/provider/production clearance.',
 public_files={str(p):sha(p) for p in sorted(files)}))
print(json.dumps(dict(public_files=len(files)+1,raw_capture_sets=len(PLAN['frames']),retained_capture_hashes=len(retained)),indent=2))
