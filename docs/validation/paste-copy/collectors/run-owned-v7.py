from pathlib import Path
import argparse,hashlib,json,os,re,subprocess,sys
from preservation import verify,require,absent
base=Path('artifacts/paste-copy');p=argparse.ArgumentParser();p.add_argument('--name',required=True);p.add_argument('--method');p.add_argument('--legacy-reveal',action='store_true');p.add_argument('--layout',choices=['tablet','phone','landscape'],default='tablet');p.add_argument('--font',type=float,default=1);a=p.parse_args();require(re.fullmatch(r'[a-z0-9-]+',a.name) and 1<=a.font<=2 and (not a.method or re.fullmatch(r'[A-Za-z0-9]+',a.method)),'Invalid arguments')
require(not a.legacy_reveal or a.method=='keyboardCopyHidesImeAndEscapeReturns' and a.layout=='landscape' and a.font==2,'Legacy control scope differs')
out=base/a.name;require(not out.exists(),'Attempt exists; inspect before repeating');read=lambda p:json.loads(Path(p).read_text());sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
build=read(base/'candidate-v2/identity.json');harness=read(base/'owned-harness-v7/identity.json');app=base/'candidate-v2/app-debug.apk';test=base/'owned-harness-v7/app-debug-androidTest.apk'
require(sha(app)==harness['app_sha256'] and sha(test)==harness['test_apk_sha256'],'Frozen APK differs')
for path,digest in harness['source_hashes'].items():
 if path.endswith('.py'):require(sha(path)==digest,'Executed Python source differs')
before=verify();out.mkdir(mode=0o700);expected=1 if a.method else 13
state=dict(completed=False,pid=os.getpid(),layout=a.layout,font=a.font,method=a.method,expected=expected,scope='Owned Unicode, capability and lifecycle protocols; injected native owner case is not a JavaScript producer workflow',runtime_source_commit=build['source_commit'],test_source_commit=harness['test_source_commit'],app_sha256=sha(app),test_apk_sha256=sha(test),collector_sha256=sha(__file__),**before)
def save():(out/'state.json').write_text(json.dumps(state,indent=2)+'\n')
save();archive='<related-checkout>/droidcast-command-catalog-27caf3/artifacts/extension-catalog/representative-001-bundles-v1/archives/223987eaca2c894e37ada9e087669f260cda79d6a3b88ff11463f429cc952e6a.zip';classes='dev.tinycast.android.ExtensionPasteCopyDeviceTest'+('#'+a.method if a.method else '')
command=[sys.executable,'scripts/test-reviewed-text-input.py','--serial','emulator-5580','--avd','Tinycast_Tablet_API_36','--layout',a.layout,'--font-scale',str(a.font),'--output',str(out/'cohort'),'--apk',str(app),'--test-apk',str(test),'--classes',classes,'--expected',str(expected),'--json-archive',archive]
if a.legacy_reveal:command.append('--paste-copy-legacy-reveal')
with (out/'host.log').open('wb') as log:r=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,env=dict(os.environ,ANDROID_HOME='<android-sdk>'))
receipt=read(out/'cohort/record.json');cleanup=receipt.get('cleanup',{});state.update(runner_exit_code=r.returncode,passes=receipt.get('passes',0),cleanup_checks=len(cleanup),cleanup_passed=bool(cleanup) and all(x.get('passed') for x in cleanup.values()));save();require(state['cleanup_passed'] and absent('files/reviewed-text-fixture-lease'),'Cleanup failed; retain owner and inspect');require(verify()==before,'Preservation differs');state.update(same_boot=True,durable_data_unchanged=True,pre_existing_evidence_unchanged=True)
cases=list((out/'cohort/captures').glob('pastecopy-*-record.json'));state['case_records']=len(cases);state['completed']=r.returncode==0 and receipt.get('completed') is True and state['passes']==expected and len(cases)==expected and all(read(c).get('passed') is True for c in cases)

if a.legacy_reveal:
 case=read(cases[0]);paths=case.get('reveal_paths',[]);log=(out/'cohort/tests.log').read_text()
 state['expected_negative']=r.returncode!=0 and state['passes']==0 and 'extension-primary' in log and 'node is no longer in the tree' in log and any(x.get('legacy_four_pixel_gutter') is True and x.get('start_inside_rounded_panel') is False and x.get('dialog_before')=='extension-run' and x.get('command_before')=='editor' and x.get('dialog_after') is None and x.get('command_after') is None for x in paths)
 state['scope']='Matched legacy test-gesture control; no application regression credit'
 state['completed']=state['expected_negative']
save();print(json.dumps({k:state[k] for k in ['completed','layout','font','method','expected','passes','cleanup_checks','cleanup_passed']},indent=2));require(state['completed'],'Owned cohort failed; retain its actual outcome')
