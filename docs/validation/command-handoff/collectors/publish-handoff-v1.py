from pathlib import Path
import hashlib,json,subprocess,xml.etree.ElementTree as E
base=Path('artifacts/command-handoff');public=Path('docs/validation/command-handoff');pictures=Path('docs/screenshots/command-handoff')
read=lambda p:json.loads(Path(p).read_text());sha=lambda b:hashlib.sha256(b).hexdigest()
def require(value,message):
 if not value:raise RuntimeError(message)
require(not (public/'checkpoint.json').exists(),'Published checkpoint exists; verify instead')
matrix=read(base/'owned-matrix-v3/record.json');final=read(base/'final-verification-v3/record.json')
continuation=read(base/'continuation-v4/record.json')
require(matrix['completed'] and not final['completed'] and continuation['completed'],'Verified continuation incomplete')
final_runs=[r for r in final['runs'] if r['exit_code']==0]+continuation['runs']
public.mkdir(parents=True,exist_ok=True);pictures.mkdir(parents=True,exist_ok=True)
bindings=[];projections=[];cohorts=[];frames=[];capture_hashes={}
def write(source,target,raw=False):
 source=Path(source);data=source.read_bytes();original=data
 if not raw:data=data.decode().replace('<user>','&lt;user&gt;' if target.suffix=='.xml' else '<user>').encode()
 target.parent.mkdir(parents=True,exist_ok=True);require(not target.exists() or target.read_bytes()==data,'Destination differs')
 target.write_bytes(data);bindings.append(dict(artifact=str(source.relative_to(base)),public=str(target),original_sha256=sha(original),public_sha256=sha(data),transformation='identity' if data==original else 'UTF-8 host-home replacement only; XML-safe &lt;user&gt; in XML, <user> elsewhere'))
def project(source,target,keys):
 value=read(source);out={k:value[k] for k in keys if k in value}
 if 'cleanup' in value:out['cleanup']={k:{'passed':r.get('passed') is True} for k,r in value['cleanup'].items()}
 data=(json.dumps(out,indent=2)+'\n').encode();target.parent.mkdir(parents=True,exist_ok=True);require(not target.exists() or target.read_bytes()==data,'Projection differs');target.write_bytes(data)
 projections.append(dict(artifact=str(Path(source).relative_to(base)),public=str(target),allowed_keys=keys,cleanup_projection='passed boolean only' if 'cleanup' in value else None));return out
state_keys=['completed','runtime_source_commit','test_source_commit','app_sha256','test_apk_sha256','layout','font','method','expected_failure','collector_sha256','preservation_helper_sha256','durable_file_count','evidence_file_count','runner_exit_code','passes','cleanup_checks','cleanup_passed','same_boot','durable_data_unchanged','pre_existing_evidence_unchanged','native_case_records','native_completed','preservation_owner_released','original_evidence_restored','app','phase_outcome','expected_phase','compatible_listed_workflow','negative_capability_observed']
runner_keys=['completed','serial','avd','layout','font_scale','classes','expected','runner_sha256','archive_sha256','typed_workflow_archive_sha256','cleanup_helper_sha256','clipboard_helper_sha256','document_helper_sha256','instrumentation_excluded_tests','reviewed_json_command','reviewed_translation_command','expect_completion_return','apk_sha256','test_apk_sha256','installed_apks_match','instrumentation_exit_code','passes','tests_completed','owned_extension_count']
superseded={r['name'] for r in matrix['runs'] if r['name'].startswith(('owned-ack-','owned-duplicate-'))}
final_names=[r['name'] for r in matrix['runs']+final_runs if not r['name'].startswith('matched-lifetime-') and r['name'] not in superseded]
negative_names=[r['name'] for r in final_runs if r['name'].startswith('matched-lifetime-')]
require(len(final_names)==34 and len(set(final_names))==34 and len(negative_names)==2,'Unexpected final cohort accounting')
for state_path in sorted(base.glob('*/state.json')):
 root=state_path.parent
 if not (root/'cohort/record.json').exists():continue
 name=root.name;dest=public/'cohorts'/name
 state=project(state_path,dest/'state.json',state_keys);runner=project(root/'cohort/record.json',dest/'runner-record.json',runner_keys)
 require(state['cleanup_passed'] and state['same_boot'] and state['durable_data_unchanged'] and state['pre_existing_evidence_unchanged'],'Preservation failed')
 write(root/'cohort/tests.log',dest/'tests.log')
 for directory in ['captures','readiness-evidence','extension-grid-evidence']:
  capture_root=root/'cohort'/directory
  if not capture_root.exists():continue
  for p in sorted(capture_root.iterdir()):
   require(p.suffix in ['.png','.json','.xml'],'Unexpected capture path');capture_hashes[str(p.relative_to(base))]=sha(p.read_bytes())
   if p.name.endswith('-record.json'):write(p,dest/'cases'/p.name,raw=True)
 category='final-positive' if name in final_names else 'final-matched-negative' if name in negative_names else 'historical-failure' if not state['completed'] or state.get('expected_failure') else 'historical-observation'
 if category=='final-positive':require(state['completed'] and runner['passes']==runner['expected'],'Incomplete positive')
 if category=='final-matched-negative':require(state['completed'] and runner['passes']==0,'Old control unexpectedly passed')
 cohorts.append(dict(name=name,category=category,passes=runner['passes'],expected=runner['expected'],cleanup_checks=len(runner['cleanup']),app_sha256=state['app_sha256'],test_apk_sha256=state['test_apk_sha256']))
def frame(name,phase):
 root=base/name/'cohort/captures';files=list(root.glob('*-'+phase+'.png'));require(len(files)==1,'Selected phase missing: '+name+'/'+phase);stem=files[0].stem
 if any(x['cohort']==name and x['stem']==stem for x in frames):return
 for suffix in ['.png','.xml','-frame.json']:
  p=root/(stem+suffix);require(p.exists(),'Incomplete triplet');write(p,pictures/name/p.name if suffix=='.png' else public/'cohorts'/name/'captures'/p.name,raw=True)
 frames.append(dict(cohort=name,stem=stem))
for layout in ['tablet','phone']:
 for label,phase in [('ordinary','ordinary-child-without-context'),('recreation','recreated-child-retains-context'),('preferences','restored-parent-rejects-old-preferences'),('context','child-context'),('cancel','cancelled-caller'),('invalid','rejected-request-feedback')]:frame('owned-'+label+'-'+layout+'100-v3',phase)
 for phase in ['parent-ready','caller-return']:frame('owned-context-'+layout+'100-v3',phase)
for name in final_names:
 if name.startswith('protocol-'):frame(name,'child-after-queued-callbacks')
 elif name.startswith('critical-context-'):frame(name,'child-context')
 elif name.startswith('critical-invalid-'):frame(name,'rejected-request-feedback')
 elif name.startswith('final-quick-'):
  for phase in ['translation-chosen','translation-native-result','translation-exact-copy','translation-context-return']:frame(name,phase)
for phase in ['exact-context-copy','caller-return']:frame('critical-context-landscape200-v3',phase)
for name in negative_names:frame(name,'failure')
for layout in ['tablet','phone']:frame('matched-quick-old-'+layout+'100-v1','translation-runtime-boundary')
frame('owned-ordinary-tablet100-v2','failure')
for folder in ['build-candidate-v1','build-candidate-v2']:
 value=read(base/folder/'state.json');project(base/folder/'state.json',public/'identities'/(folder+'.json'),[k for k in value if k!='pid'])
 for p in sorted((base/folder).glob('*.log')):write(p,public/'checks'/folder/p.name)
 if folder=='build-candidate-v2':
  for p in sorted((base/folder/'reports').rglob('*')):
   if p.is_file():write(p,public/'checks'/folder/'reports'/p.relative_to(base/folder/'reports'))
for version in [2,3]:
 for p in sorted((base/('final-harness-reports-v'+str(version))).glob('*.xml')):write(p,public/'checks'/('final-lint-v'+str(version))/p.name)
for folder in ['common-v5','owned-harness-v1','owned-harness-v2','owned-harness-v3','repaired-harness-v2']:
 write(base/folder/'identity.json',public/'identities'/(folder+'.json'))
for name in ['asset-comparison-v1.json','harness-transition-v2.json','python-owned-v1.log','python-owned-optimized-v1.log','owned-harness-v1-build.log','owned-harness-v2-build.log','owned-harness-v2-benchmark-build.log','owned-harness-v2-debug-lint.log','final-harness-checks-v2.json','owned-harness-v3-build.log','owned-harness-v3-benchmark-build.log','owned-harness-v3-debug-lint.log','final-harness-checks-v3.json']:
 write(base/name,public/'checks'/name)
for folder in ['owned-matrix-v2','owned-matrix-v3','final-verification-v3','continuation-v4']:
 value=read(base/folder/'record.json');project(base/folder/'record.json',public/'pipelines'/(folder+'.json'),[k for k in value if k!='pid'])
write(base/'regression-menus-tablet100-v2/host.log',public/'checks/menu-preflight.log')
write(base/'menu-preflight-v1.json',public/'checks/menu-preflight-v1.json')
for p in sorted(base.glob('*.py')):write(p,public/'collectors'/p.name)
for p in public.rglob('*.xml'):E.parse(p)
for root in [public,pictures]:
 for p in root.rglob('*'):
  if p.is_file() and p.suffix!='.png':require((b'/' + b'Users' + b'/') not in p.read_bytes(),'Host path published')
files={str(p):sha(p.read_bytes()) for root in [public,pictures] for p in sorted(root.rglob('*')) if p.is_file()}
require(set(files)=={r['public'] for r in bindings+projections},'Unlisted public evidence')
positive=[r for r in cohorts if r['category']=='final-positive'];negative=[r for r in cohorts if r['category']=='final-matched-negative']
record=dict(schema_version=1,scope='Bounded same-extension foreground handoff; owned lifecycle protocols plus the configured frozen Quick Translate path, not broad parity',source_head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),source_hashes=read(base/'owned-harness-v3/identity.json')['source_hashes'],cohorts=cohorts,original_public_bindings=bindings,allowlisted_projections=projections,retained_capture_hashes=capture_hashes,selected_frame_sets=frames,public_files=files,final_native_executions=sum(r['passes'] for r in positive),final_cleanup_checks=sum(r['cleanup_checks'] for r in positive),final_cohorts=len(positive),matched_negative_executions=sum(r['expected'] for r in negative),matched_negative_cleanup_checks=sum(r['cleanup_checks'] for r in negative),total_executions=sum(r['expected'] for r in cohorts),total_passes=sum(r['passes'] for r in cohorts),total_cleanup_checks=sum(r['cleanup_checks'] for r in cohorts))
(public/'checkpoint.json').write_text(json.dumps(record,indent=2)+'\n')
print(json.dumps({k:record[k] for k in ['final_native_executions','final_cleanup_checks','final_cohorts','total_executions','total_passes','total_cleanup_checks']},indent=2));print('Public files:',len(files),'frames:',len(frames))
