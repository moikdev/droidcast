from pathlib import Path
import hashlib,json,subprocess,xml.etree.ElementTree as E
base=Path('artifacts/offline-workflows');public=Path('docs/validation/offline-workflows');pictures=Path('docs/screenshots/offline-workflows')
read=lambda p:json.loads(Path(p).read_text())
sha=lambda data:hashlib.sha256(data).hexdigest()
def require(v,m):
 if not v:raise RuntimeError(m)
final=read(base/'final-native-pipeline-v8/record.json');require(final['completed'],'Final native pipeline incomplete')
intermediate=read(base/'final-native-pipeline-v4/record.json');require(intermediate['completed'],'Intermediate pipeline incomplete')
require(read(base/'controls-pipeline-v2/record.json')['completed'],'Controls incomplete')
require(not (public/'checkpoint.json').exists(),'Publication already exists; verify instead of overwriting')
public.mkdir(parents=True,exist_ok=True);pictures.mkdir(parents=True,exist_ok=True)
bindings=[];projections=[];capture_hashes={};cohorts=[];selected_frames=[]
def write(source,target,raw=False):
 original=Path(source).read_bytes();data=original
 if not raw:
  replacement='&lt;user&gt;' if target.suffix=='.xml' else '<user>'
  data=original.decode().replace('<user>',replacement).encode()
 target.parent.mkdir(parents=True,exist_ok=True);require(not target.exists(),'Public destination exists')
 target.write_bytes(data)
 bindings.append({'artifact':str(Path(source).relative_to(base)),'public':str(target),'original_sha256':sha(original),'public_sha256':sha(data),'transformation':'identity' if data==original else 'UTF-8 host-home replacement; XML-safe &lt;user&gt; in XML, <user> elsewhere; no other byte changes'})
 return data
def project(source,target,keys):
 value=read(source);data={key:value[key] for key in keys if key in value}
 if 'cleanup' in value:data['cleanup']={key:{'passed':row.get('passed') is True} for key,row in value['cleanup'].items()}
 target.parent.mkdir(parents=True,exist_ok=True);require(not target.exists(),'Projection exists');target.write_text(json.dumps(data,indent=2)+'\n')
 projections.append({'artifact':str(Path(source).relative_to(base)),'public':str(target),'allowed_keys':keys,'cleanup_projection':'passed boolean only' if 'cleanup' in value else None})
 return data
state_keys=['completed','layout','font','case','methods','app_sha256','app_cohort','completion_return_expected','intended_missing_return_control','test_apk_sha256','test_source_commit','runtime_source_commit','collector_sha256','durable_file_count','pre_existing_evidence_count','runner_exit_code','passes','cleanup_checks','cleanup_passed','native_completed','durable_data_unchanged','pre_existing_evidence_unchanged','same_boot','intended_initial_target_control','case_receipts_passed','native_positive_completed','intended_negative_completed']
runner_keys=['completed','serial','avd','layout','font_scale','classes','expected','runner_sha256','archive_sha256','typed_workflow_archive_sha256','cleanup_helper_sha256','clipboard_helper_sha256','document_helper_sha256','instrumentation_excluded_tests','reviewed_json_command','expect_completion_return','apk_sha256','test_apk_sha256','installed_apks_match','instrumentation_exit_code','passes','tests_completed','owned_extension_count']
baseline=sorted(p.parent.name for p in base.glob('native-*-v1/state.json'))
require(len(baseline)==24 and sum(read(base/n/'state.json')['passes'] for n in baseline)==28,'Baseline accounting changed')
critical=sorted(p.parent.name for p in base.glob('critical-*-v1/state.json'))
require(len(critical)==5,'Initial critical cohort accounting changed')
controls=['control-'+case+'-old-'+profile+'-v2' for case,profile in [('uuid-v7','tablet100'),('typeid-pack','landscape200'),('json-selection','phone100')]]
names=baseline+critical+['diagnostic-json-lines-old-landscape200-v2']+controls+['return-regressions-tablet100-v3']+intermediate['cohorts']+['control-uuid-v7-old-tablet100-v6']+final['negative_controls']+final['cohorts']
require(len(names)==len(set(names)),'Duplicate cohort credit')
# Publish every case receipt/log, and hash every retained raw capture. Actual
# images are selected explicitly below; unpublished originals stay separate.
for name in names:
 root=base/name;dest=public/'cohorts'/name
 state=project(root/'state.json',dest/'state.json',state_keys);runner=project(root/'cohort/record.json',dest/'runner-record.json',runner_keys)
 require(state['cleanup_passed'] and state['durable_data_unchanged'] and state['pre_existing_evidence_unchanged'] and state['same_boot'],'Restoration/preservation failed')
 write(root/'cohort/tests.log',dest/'tests.log')
 for p in sorted((root/'cohort/captures').iterdir()):
  require(p.suffix in ['.png','.xml','.json'],'Unexpected capture path');capture_hashes[str(p.relative_to(base))]=sha(p.read_bytes())
  if p.name.endswith('-record.json'):write(p,dest/'cases'/p.name,raw=True)
 category='final-positive' if name in final['cohorts'] else 'intermediate-positive' if name in intermediate['cohorts'] else 'expected-initial-target' if state.get('intended_initial_target_control') else 'expected-missing-return' if name in controls or name in final['negative_controls'] else 'old-pointer-diagnostic' if name.startswith('diagnostic-') else 'failed-clipboard-fixture' if name=='control-uuid-v7-old-tablet100-v6' else 'failed-keyboard-setup' if name=='return-regressions-tablet100-v3' else 'old-critical' if name in critical else 'old-baseline'
 cohorts.append({'name':name,'category':category,'passes':runner['passes'],'expected':runner['expected'],'cleanup_checks':len(runner['cleanup']),'outer_completed':state['completed'],'app_sha256':state['app_sha256'],'test_apk_sha256':state['test_apk_sha256']})
def frame(cohort,method,phase):
 prefix='text-' if method.startswith(('selected','shared','writable')) else 'form-'
 stem=prefix+method.lower()+'-'+phase;root=base/cohort/'cohort/captures'
 paths=[root/(stem+suffix) for suffix in ['.png','.xml','-frame.json']]
 require(all(p.exists() for p in paths),'Missing selected frame '+stem)
 if (cohort,stem) in selected_frames:return
 for p in paths:
  dest=(pictures/cohort/p.name) if p.suffix=='.png' else (public/'cohorts'/cohort/'captures'/p.name)
  write(p,dest,raw=True)
 selected_frames.append((cohort,stem))
# Matched old/new completion illustrations with the exact same instrumentation.
for app in ['old','new']:
 frame('control-uuid-v7-'+app+'-tablet100-v8','frozenUuidV7GeneratesThreeFreshIdsAndOwnedHistory','offline-copied-output')
 frame('control-typeid-pack-'+app+'-landscape200-v8','frozenUuidPacksTheIndependentTypeIdVector','offline-copied-output')
 frame('control-json-selection-'+app+'-phone100-v8','selectedJsonRunsOnlyAfterReviewThenCopiesAndReturns','copied')
frame('control-json-selection-new-phone100-v8','selectedJsonRunsOnlyAfterReviewThenCopiesAndReturns','return')
# Each of the ten Form-hosted commands has a real current phone frame.
form_methods=read(base/'final-offline-form-phone-phone100-v8/state.json')['methods']
for method in form_methods:frame('final-offline-form-phone-phone100-v8',method,'offline-copied-output')
# View destinations/errors and persistence; focused keyboard/lifetime/cancellation.
for phase in ['json-lines-invalid-input','json-lines-unfiltered-action-visible','json-lines-detail']:
 frame('final-offline-form-phone-phone100-v8','frozenJsonLinesRejectsInvalidInputThenCopiesExactUnicodeLines',phase)
frame('final-offline-form-phone-phone100-v8','frozenJsonValueEscapesUnicodeAndReloadsItsOwnedInput','json-value-reopened')
for cohort in ['return-regressions-tablet100-v8','final-return-regressions-phone100-v8']:
 for method,phases in [('ownedNoViewReturnRejectsDisposedAndReplacedCallbacks',['completion-ready','completion-replacement-ready','completion-caller-return']),('ownedNoViewReturnAcceptsFocusedEnterAndPreservesEscape',['completion-focused-return']),('ownedNoViewPendingCommandKeepsHeaderCancellation',['pending-without-return','pending-cancelled'])]:
  for phase in phases:frame(cohort,method,phase)
# Enlarged-text representative outputs on all three layouts, plus external return.
for layout in ['tablet','phone','landscape']:
 cohort='final-critical-offline-form-'+layout+'200-v8'
 for method in ['frozenUuidPacksTheIndependentTypeIdVector','frozenJsonLinesRejectsInvalidInputThenCopiesExactUnicodeLines','frozenJsonValueEscapesUnicodeAndReloadsItsOwnedInput']:frame(cohort,method,'offline-copied-output')
 frame(cohort,'frozenJsonLinesRejectsInvalidInputThenCopiesExactUnicodeLines','json-lines-unfiltered-action-visible')
 cohort='final-json-selection-'+layout+'200-v8'
 for phase in ['completion-return-visible','return']:frame(cohort,'selectedJsonRunsOnlyAfterReviewThenCopiesAndReturns',phase)
frame('final-typeid-pack-landscape100-v8','frozenUuidPacksTheIndependentTypeIdVector','offline-copied-output')
# Retain the intermediate initially-clipped control and both earlier failures.
frame('control-typeid-pack-previous-landscape200-v8','frozenUuidPacksTheIndependentTypeIdVector','offline-copied-output')
# Preserve the two failed observations and the stronger old-app pointer witness.
frame('critical-json-lines-landscape200-v1','frozenJsonLinesRejectsInvalidInputThenCopiesExactUnicodeLines','failure')
frame('diagnostic-json-lines-old-landscape200-v2','frozenJsonLinesRejectsInvalidInputThenCopiesExactUnicodeLines','json-lines-unfiltered-action-visible')
frame('return-regressions-tablet100-v3','ownedNoViewReturnAcceptsFocusedEnterAndPreservesEscape','failure')
frame('control-uuid-v7-old-tablet100-v6','frozenUuidV7GeneratesThreeFreshIdsAndOwnedHistory','failure')
# Existing committed before aliases remain exactly equal to their local originals.
for layout in ['tablet','phone']:
 cohort='native-uuid-v7-'+layout+'-v1';stem='form-frozenuuidv7generatesthreefreshidsandownedhistory-offline-copied-output'
 for suffix,target_name in [('.png','before-no-view-'+layout+'.png'),('.xml','before-no-view-'+layout+'.xml'),('-frame.json','before-no-view-'+layout+'-frame.json')]:
  src=base/cohort/'cohort/captures'/(stem+suffix)
  target=(pictures if suffix=='.png' else public)/'touch-return-baseline'/target_name
  require(target.exists(),'Existing baseline alias missing')
  require(src.read_bytes()==target.read_bytes(),'Committed baseline alias changed')
  bindings.append({'artifact':str(src.relative_to(base)),'public':str(target),'original_sha256':sha(src.read_bytes()),'public_sha256':sha(target.read_bytes()),'transformation':'identity; existing committed before alias, no extra execution credit'})
for name in ['build-harness-v1','build-return-v2','build-harness-v3','build-return-v5','build-harness-v7']:
 value=read(base/name/'state.json');project(base/name/'state.json',public/'identities'/(name+'.json'),[k for k in value if k!='pid'])
 for p in sorted((base/name).glob('*.log')):write(p,public/'checks'/name/p.name)
 for p in sorted((base/name/'reports').iterdir()):write(p,public/'checks'/name/'reports'/p.name)
for name in ['normal.log','optimized.log','record.json']:write(base/'python-final-v1'/name,public/'checks/python-final'/name)
for name in ['python-return-v2.log','python-return-v2-optimized.log','contracts.json','independent-vectors.json','fresh-app-payload-comparison-v1.json']:write(base/name,public/'checks'/name)
for name in ['build-harness-v1.py','build-return-v2.py','build-harness-v3.py','build-return-v5.py','build-harness-v7.py','run-case-v1.py','run-case-v2.py','run-case-v3.py','run-case-v4.py','run-case-v5.py','run-case-v7.py','run-main-v1.py','run-critical-v1.py','run-controls-v2.py','run-final-native-v4.py','run-final-native-v6.py','run-final-native-v8.py','run-python-final-v1.py','publish-offline-v1.py','verify-offline-v1.py']:write(base/name,public/'collectors'/name)
for name in ['main-pipeline-v1','critical-pipeline-v1','controls-pipeline-v2','final-native-pipeline-v4','final-native-pipeline-v6','final-native-pipeline-v8']:
 value=read(base/name/'record.json');project(base/name/'record.json',public/'pipelines'/(name+'.json'),[k for k in value if k!='pid'])
for p in public.rglob('*.xml'):E.parse(p)
for p in [*public.rglob('*'),*pictures.rglob('*')]:
 if p.is_file() and p.suffix!='.png':require((b'/' + b'Users' + b'/') not in p.read_bytes(),'Host path in public evidence')
require(sum(c['passes'] for c in cohorts if c['category']=='final-positive')==final['executions'],'Final accounting mismatch')
files={str(p):sha(p.read_bytes()) for root in [public,pictures] for p in sorted(root.rglob('*')) if p.is_file()}
checkpoint={'schema_version':1,'scope':'Eleven listed offline workflows and completed no-view touch Return; historical failures and binaries remain distinct; no static inventory or whole-Store parity','source_head':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'source_hashes':read(base/'build-harness-v7/state.json')['source_hashes'],'cohorts':cohorts,'original_public_bindings':bindings,'allowlisted_projections':projections,'retained_capture_hashes':capture_hashes,'selected_frame_sets':[{'cohort':c,'stem':s} for c,s in selected_frames],'public_files':files,'final_native_executions':final['executions'],'final_native_cleanup_checks':final['cleanup_checks']}
(public/'checkpoint.json').write_text(json.dumps(checkpoint,indent=2)+'\n')
print(json.dumps({'public_files':len(files),'retained_capture_files':len(capture_hashes),'selected_frame_sets':len(selected_frames),'all_distinct_cohorts':len(cohorts),'final_native_executions':final['executions'],'final_native_cleanup_checks':final['cleanup_checks']},indent=2))
