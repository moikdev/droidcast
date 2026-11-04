from pathlib import Path
import argparse, hashlib, json, os, re, subprocess, sys
from preservation import verify, require, absent
base=Path('artifacts/command-handoff')
p=argparse.ArgumentParser()
p.add_argument('--name',required=True);p.add_argument('--method',choices=['forms','detail','menus','review'],required=True)
p.add_argument('--layout',choices=['tablet','phone','landscape'],default='tablet');p.add_argument('--font',type=float,default=1)
p.add_argument('--harness',default='owned-harness-v2');p.add_argument('--build',default='build-candidate-v2')
p.add_argument('--expect-failure',action='store_true')
a=p.parse_args()
require(all(re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9-]{0,100}',x) for x in [a.name,a.harness,a.build,a.method]),'Invalid paths or method')
require(1<=a.font<=2,'Invalid font')
out=base/a.name;require(not out.exists(),'Attempt exists; inspect before repeating')
read=lambda x:json.loads(Path(x).read_text());sha=lambda x:hashlib.sha256(Path(x).read_bytes()).hexdigest()
build=read(base/a.build/'state.json');harness=read(base/a.harness/'identity.json')
require(build['completed'],'Incomplete build')
for path,digest in harness['source_hashes'].items():
    if path.endswith('.py'):require(sha(path)==digest,'Executed Python source changed')
app=base/a.build/'candidate/app-debug.apk';test=base/a.harness/'app-debug-androidTest.apk'
require(sha(app)==build['apk_hashes'][app.name] and sha(test)==harness['test_apk_sha256'],'APK mismatch')
before=verify();out.mkdir(mode=0o700)
cache=Path('<user>/.t3/scratch/droidcast-command-catalog-27caf3/artifacts/extension-catalog/representative-001-bundles-v1/archives')
groups={'forms':('ExtensionFormDeviceTest',['nativeConfigurationEditsValidateCancelAndRun','replacedConfigurationRejectsQueuedEditsRunAndBack','ownedNoViewReturnRejectsDisposedAndReplacedCallbacks','ownedNoViewReturnAcceptsFocusedEnterAndPreservesEscape']),'detail':('ExtensionDetailDeviceTest',['listTouchDetailPreservesQuerySelectionExactCopyAndCaller','recreationStartsAnewOwnerAndRejectsThePriorNativeCallback']),'menus':('ReadinessRegressionDeviceTest',['rapidExtensionListDownThenEnterRunsTheCurrentAction']),'review':('ReviewedTextDeviceTest',['selectedJsonRunsOnlyAfterReviewThenCopiesAndReturns','cancelBeforeRunReturnsWithoutExecutingOrSaving'])}
klass,methods=groups[a.method];classes=','.join('dev.tinycast.android.'+klass+'#'+m for m in methods)
command=[sys.executable,'scripts/test-reviewed-text-input.py','--serial','emulator-5580','--avd','Tinycast_Tablet_API_36','--layout',a.layout,'--font-scale',str(a.font),'--output',str(out/'cohort'),'--apk',str(app),'--test-apk',str(test),'--classes',classes,'--expected',str(len(methods)),'--json-archive',str(cache/'223987eaca2c894e37ada9e087669f260cda79d6a3b88ff11463f429cc952e6a.zip')]
state=dict(completed=False,pid=os.getpid(),runtime_source_commit=build['runtime_source_commit'],test_source_commit=harness['test_source_commit'],app_sha256=sha(app),test_apk_sha256=sha(test),layout=a.layout,font=a.font,method=a.method,expected_failure=a.expect_failure,collector_sha256=sha(__file__),**before)
def save():(out/'state.json').write_text(json.dumps(state,indent=2)+'\n')
save()
with (out/'host.log').open('wb') as log:r=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,env=dict(os.environ,ANDROID_HOME='<user>/Library/Android/sdk'))
receipt=read(out/'cohort/record.json');cleanup=receipt.get('cleanup',{})
state.update(runner_exit_code=r.returncode,passes=receipt.get('passes',0),cleanup_checks=len(cleanup),cleanup_passed=bool(cleanup) and all(x.get('passed') for x in cleanup.values()))
save();require(state['cleanup_passed'] and absent('files/reviewed-text-fixture-lease'),'Cleanup failed; inspect retained ownership')
require(verify()==before,'Preservation changed')
state.update(same_boot=True,durable_data_unchanged=True,pre_existing_evidence_unchanged=True)
records=list((out/'cohort/captures').glob('*-record.json'))
state['native_case_records']=len(records)
state['completed']=r.returncode!=0 and state['passes']==0 if a.expect_failure else r.returncode==0 and receipt.get('completed') is True and state['passes']==len(methods)
save();require(sha(__file__)==state['collector_sha256'],'Collector changed')
print(json.dumps(state,indent=2));require(state['completed'],'Owned case did not establish intended outcome; inspect retained attempt')
