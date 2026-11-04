from pathlib import Path
import argparse,hashlib,json,os,re,subprocess,sys
from preservation import verify,require,absent
base=Path('artifacts/paste-copy');p=argparse.ArgumentParser();p.add_argument('--name',required=True);p.add_argument('--layout',choices=['tablet','phone','landscape'],default='tablet');p.add_argument('--font',type=float,default=1);p.add_argument('--app',choices=['old','new'],default='new');a=p.parse_args();require(re.fullmatch(r'[a-z0-9-]+',a.name) and 1<=a.font<=2,'Invalid cohort arguments')
out=base/a.name;require(not out.exists(),'Attempt exists; inspect before repeating');read=lambda p:json.loads(Path(p).read_text());sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
identity=read(base/'candidate-v2/identity.json');test=base/'candidate-v2/app-debug-androidTest.apk';app=base/'candidate-v2/app-debug.apk' if a.app=='new' else Path('<related-checkout>/droidcast-command-handoff-27caf3/artifacts/command-handoff/build-candidate-v2/candidate/app-debug.apk')
expected_app=identity['apk_hashes'][app.name] if a.app=='new' else '2a41ea1407cccf23b70afada6fde64de55bc0bd3d8f035645a44532b12d14e3d'
require(sha(app)==expected_app and sha(test)==identity['apk_hashes'][test.name],'Frozen APK differs')
for path,digest in identity['source_hashes'].items():
 if path.endswith('.py'):require(sha(path)==digest,'Executed Python source changed')
before=verify();out.mkdir(mode=0o700);state=dict(completed=False,pid=os.getpid(),app=a.app,layout=a.layout,font=a.font,scope='Explicit native Copy fallback; actual insertion remains unsupported/partial',runtime_source_commit=identity['source_commit'] if a.app=='new' else '56134f31f74211d7ec4d8faaf18ef5f84658242e',test_source_commit=identity['source_commit'],app_sha256=sha(app),test_apk_sha256=sha(test),collector_sha256=sha(__file__),**before)
def save():(out/'state.json').write_text(json.dumps(state,indent=2)+'\n')
save();cache=Path('<related-checkout>/droidcast-command-catalog-27caf3/artifacts/extension-catalog/representative-001-bundles-v1/archives')
command=[sys.executable,'scripts/test-reviewed-text-input.py','--serial','emulator-5580','--avd','Tinycast_Tablet_API_36','--layout',a.layout,'--font-scale',str(a.font),'--output',str(out/'cohort'),'--apk',str(app),'--test-apk',str(test),'--classes','dev.tinycast.android.ReviewedTextDeviceTest#selectedTranslationRunsOnlyAfterReviewAndReportsExactCopyOrServiceFailure','--expected','1','--reviewed-translation-command','instant-translate-paste','--expect-paste-copy-fallback','--json-archive',str(cache/'223987eaca2c894e37ada9e087669f260cda79d6a3b88ff11463f429cc952e6a.zip'),'--translate-archive',str(cache/'909c8a3916ca91612bb610da257f5718af2ef25f303ea95371e907eaa1cf7d69.zip')]
with (out/'host.log').open('wb') as log:r=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,env=dict(os.environ,ANDROID_HOME='<android-sdk>'))
receipt=read(out/'cohort/record.json');cleanup=receipt.get('cleanup',{});state.update(runner_exit_code=r.returncode,passes=receipt.get('passes',0),cleanup_checks=len(cleanup),cleanup_passed=bool(cleanup) and all(x.get('passed') for x in cleanup.values()));save();require(state['cleanup_passed'] and absent('files/reviewed-text-fixture-lease'),'Cleanup failed; inspect retained owner');require(verify()==before,'Preservation differs');state.update(same_boot=True,durable_data_unchanged=True,pre_existing_evidence_unchanged=True)
cases=list((out/'cohort/captures').glob('*-record.json'));require(len(cases)==1,'Missing native case');case=read(cases[0]);state['workflow_outcome']=case.get('workflow_outcome',case.get('observed_phase_outcome','uncredited-failure'))
if a.app=='new':
 state['completed']=r.returncode==0 and receipt.get('completed') is True and state['passes']==1 and case.get('passed') is True and state['workflow_outcome']=='partial-paste-explicit-copy-fallback' and case.get('native_copy_was_explicit') is True and case.get('copy_exact') is True and case.get('returned_to_external_caller') is True and case.get('source_text_and_selection_unchanged') is True
else:
 log=(out/'cohort/tests.log').read_text();state['completed']=r.returncode!=0 and state['passes']==0 and 'A rejected paste with owned output must offer Copy result instead' in log and case.get('owned_service_output_matched_paste_attempt') is True
 state['expected_negative']=True
save();print(json.dumps({k:state[k] for k in ['completed','app','layout','font','workflow_outcome','passes','cleanup_checks','cleanup_passed']},indent=2));require(state['completed'],'Frozen paste cohort did not establish its required phase')
