from pathlib import Path
import hashlib,json,subprocess,xml.etree.ElementTree as E
base=Path('artifacts/workflow-expansion');public=Path('docs/validation/extension-compatibility/fetch');pictures=Path('docs/screenshots/extension-compatibility/fetch')
assert not public.exists() and not pictures.exists()
read=lambda p:json.loads(Path(p).read_text())
sha=lambda data:hashlib.sha256(data).hexdigest()
for name in ['network-fonts-pipeline-v1','regression-pipeline-v1','selected-pipeline-v1','default-discovery-v1']:
 assert read(base/name/'record.json')['completed'],name+' incomplete'
assert read(base/'final-local-build-v2/record.json')['completed']
assert read(base/'python-guards-v3/record.json')['completed']
public.mkdir(parents=True);pictures.mkdir(parents=True)
bindings=[];projections=[];raw_captures={};cohorts=[]
def write(source,target,raw=False):
 data=Path(source).read_bytes();original=data
 if not raw:
  text=data.decode('utf-8');replacement='&lt;user&gt;' if str(target).endswith('.xml') else '<user>'
  data=text.replace('<user>',replacement).encode('utf-8')
 target.parent.mkdir(parents=True,exist_ok=True);assert not target.exists();target.write_bytes(data)
 bindings.append({'artifact':str(Path(source).relative_to(base)),'public':str(target),'original_sha256':sha(original),'public_sha256':sha(data),'transformation':'identity' if data==original else 'UTF-8 host-home replacement with XML-safe &lt;user&gt; or <user>; no other byte changes'})
 return data
def project(source,target,keys):
 value=read(source);data={key:value[key] for key in keys if key in value}
 if 'cleanup' in value:data['cleanup']={key:{'passed':row.get('passed') is True} for key,row in value['cleanup'].items()}
 target.parent.mkdir(parents=True,exist_ok=True);assert not target.exists();target.write_text(json.dumps(data,indent=2)+'\n')
 projections.append({'artifact':str(Path(source).relative_to(base)),'public':str(target),'allowed_keys':keys,'cleanup_projection':'passed boolean only' if 'cleanup' in value else None})
 return data
runner_keys=['completed','serial','avd','layout','font_scale','classes','expected','runner_sha256','archive_sha256','typed_workflow_archive_sha256','cleanup_helper_sha256','clipboard_helper_sha256','document_helper_sha256','apk_sha256','test_apk_sha256','installed_apks_match','instrumentation_exit_code','passes','tests_completed','owned_extension_count','instrumentation_excluded_tests']
state_keys=['completed','layout','font','variant','case','app_sha256','test_apk_sha256','test_source_commit','test_compile_target_commit','runtime_source_commit','collector_sha256','durable_file_count','pre_existing_evidence_count','runner_exit_code','passes','cleanup_checks','cleanup_passed','native_completed','durable_data_unchanged','pre_existing_evidence_unchanged','same_boot','routing','network_observation','workflow_outcome','intended_negative_witness','positive_witness','observation_collector_witness']
names=['native-fetch-baseline-tablet-v1','native-fetch-baseline-phone-v1','native-fetch-current-tablet-v1','native-fetch-current-phone-v1','matched-old-tablet-v2','matched-old-phone-v2','native-policy-tablet-v2','native-policy-phone-v2','native-https-tablet-v2','native-https-phone-v2']
names += ['frozen-'+case+'-'+layout+'-v1' for layout in ['tablet','phone'] for case in ['translate','weather']]
names += ['frozen-selected-translate-'+layout+'-v1' for layout in ['tablet','phone']]
names += ['critical-'+case+'-'+layout+'200-v1' for layout in ['phone','landscape','tablet'] for case in ['policy','https']]
names += ['regression-'+case+'-'+layout+'-v1' for layout in ['tablet','phone'] for case in ['detail','form','reviewed']]
for name in names:
 root=base/name;dest=public/'cohorts'/name
 state=project(root/'state.json',dest/'state.json',state_keys)
 record=project(root/'cohort/record.json',dest/'runner-record.json',runner_keys)
 assert state.get('cleanup_passed') is True and state.get('durable_data_unchanged') is True and state.get('pre_existing_evidence_unchanged') is True
 write(root/'cohort/tests.log',dest/'tests.log')
 captures=root/'cohort/captures'
 for source in sorted(captures.iterdir()):
  assert source.suffix in ['.png','.xml','.json']
  target=(pictures/name/source.name) if source.suffix=='.png' else (dest/'captures'/source.name)
  data=write(source,target,raw=True);raw_captures[str(source.relative_to(base))]=sha(data)
 cohorts.append({'name':name,'passes':record['passes'],'cleanup_checks':len(record['cleanup']),'outer_completed':state['completed']})
# Freeze source/APK identity without process or machine-specific fields.
for name in ['build-probe-v1','build-runtime-v1','build-harness-v2','build-harness-v3','build-harness-v4']:
 value=read(base/name/'state.json');keys=[key for key in value if key!='pid']
 project(base/name/'state.json',public/'identities'/(name+'.json'),keys)
 write(base/name/'build.log',public/'checks'/(name+'.log'))
write(base/'build-harness-v4/benchmark-build.log',public/'checks/final-benchmark-test-build.log')
value=read(base/'final-local-build-v2/record.json');keys=['completed','head','source_hashes','runtime_build_commit','runs','jvm_cases','lint','apk_hashes']
project(base/'final-local-build-v2/record.json',public/'checks/final-local-build.json',keys)
for p in sorted((base/'final-local-build-v2/reports').iterdir()):write(p,public/'checks/reports'/p.name)
for name in ['build-0.log','build-1.log']:write(base/'final-local-build-v2'/name,public/'checks'/name)
for generation in ['v1','v2','v3']:
 for name in ['normal.log','optimized.log','record.json']:write(base/('python-guards-'+generation)/name,public/'checks'/('python-'+generation)/name)
write(base/'final-source-v1/state.json',public/'identities/final-source-v1.json')
for name in ['default.log','opt-in.log','record.json']:write(base/'default-discovery-v1'/name,public/'checks/discovery'/name)
for name in ['android-fetch.log','android-form-focus.log','android-form-submit.log','record.json']:write(base/'host-current-v1'/name,public/'checks/node'/name)
for name in ['record.json','tests.log','old-adapter.js']:write(base/'host-fetch-control-v1'/name,public/'checks/node-negative'/name)
write(base/'final-local-build-v1/build.log',public/'checks/failed-task-selection.log')
for name in ['build-probe-v1.py','build-runtime-v1.py','build-harness-v2.py','build-harness-v3.py','build-harness-v4.py','run-selected-translation-v1.py','run-selected-pipeline-v1.py','run-discovery-v1.py','run-fetch-baseline-v1.py','run-fetch-candidate-v1.py','run-fetch-v2.py','run-fetch-v3.py','run-fetch-core-v2.py','run-network-v1.py','run-network-fonts-v1.py','run-owned-regressions-v1.py','run-regression-pipeline-v1.py','final-local-build-v2.py','publish-fetch-v1.py','verify-fetch-v1.py']:
 write(base/name,public/'collectors'/name)
for p in public.rglob('*.xml'):E.parse(p)
# Public outputs must contain no literal host home path, clipboard originals or recovery backup names.
for p in [*public.rglob('*'),*pictures.rglob('*')]:
 if p.is_file() and p.suffix!='.png':
  data=p.read_bytes();assert (b'/' + b'Users' + b'/') not in data,(p,'host path')
files={str(p):sha(p.read_bytes()) for root in [public,pictures] for p in sorted(root.rglob('*')) if p.is_file()}
checkpoint={'schema_version':1,'scope':'Native fetch routing repair; all old/failed observations remain separate; static inventory remains untested',
 'source_head':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'source_hashes':read(base/'final-source-v1/state.json')['source_hashes'],
 'cohorts':cohorts,'original_public_bindings':bindings,'allowlisted_projections':projections,'raw_capture_hashes':raw_captures,'public_files':files}
(public/'checkpoint.json').write_text(json.dumps(checkpoint,indent=2)+'\n')
print(json.dumps({'public_files':len(files),'raw_capture_files':len(raw_captures),'cohorts':len(cohorts),'passes_including_distinct_historical_harness':sum(c['passes'] for c in cohorts),'cleanup_checks':sum(c['cleanup_checks'] for c in cohorts),'PNG_count':len(list(pictures.rglob('*.png')))},indent=2))
