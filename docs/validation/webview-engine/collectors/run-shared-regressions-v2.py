from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,uuid
sys.path.insert(0,'scripts');s=importlib.util.spec_from_file_location('engine_runner','scripts/test-webview-engine.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
b=Path('artifacts/webview-compatibility');out=b/'shared-regressions-v2';app='dev.tinycast.android';test=app+'.test';lease='files/webview-regression-lease'
A=['<host-home>/Library/Android/sdk/platform-tools/adb','-s','emulator-5580']
def raw(*args,data=None):return subprocess.check_output(A+list(args),input=data,stderr=subprocess.STDOUT,timeout=120)
def text(*args):return raw(*args).decode().strip()
def sha(data):return hashlib.sha256(data).hexdigest()
def require(v,msg):
 if not v:raise ValueError(msg)
require(text('emu','avd','name').splitlines()[0]=='Tinycast_Tablet_API_36' and text('shell','id').startswith('uid=2000('),'Owned modern AVD only')
require('ActiveInstrumentation{' not in text('shell','dumpsys','activity','processes'),'Active instrumentation')
require(not text('shell','run-as',app,'find','files','-maxdepth','1','-name','*lease*'),'A recovery owner remains')
apk=b/'candidate-v4/app-debug.apk';harness=b/'candidate-v4/app-debug-androidTest.apk';ident=json.loads((b/'candidate-v4/identity.json').read_text())
for p in [apk,harness]:require(sha(p.read_bytes())==ident['apks'][p.name],'APK changed')
archive=Path('<host-home>/.t3/scratch/droidcast-command-catalog-27caf3/artifacts/extension-catalog/representative-001-bundles-v1/archives/223987eaca2c894e37ada9e087669f260cda79d6a3b88ff11463f429cc952e6a.zip');require(sha(archive.read_bytes())=='fc420406a59251daee0c9af32e216026724631480b3a4e2f5d65d0c507b09a58','Frozen formatter changed')
require('Override' not in text('shell','wm','size')+text('shell','wm','density'),'Geometry owned elsewhere')
out.mkdir(exist_ok=False,mode=0o700);owner={'kind':'webview-regression-v1','nonce':uuid.uuid4().hex}
(out/'private-owner.json').write_text(json.dumps(owner));(out/'private-owner.json').chmod(0o600)
r={'completed':False,'app_sha256':sha(apk.read_bytes()),'test_apk_sha256':sha(harness.read_bytes()),'collector_sha256':sha(Path(__file__).read_bytes()),'cohorts':{},'restoration':{}}
def save():(out/'record.json').write_text(json.dumps(r,indent=2)+'\n')
save();raw('shell','run-as',app,'mkdir',lease);raw('shell','run-as',app,'tee',lease+'/owner.json',data=json.dumps(owner).encode())
def owned():require(json.loads(raw('exec-out','run-as',app,'cat',lease+'/owner.json'))==owner,'Owner changed')
for pkg in [test,app]:raw('shell','am','force-stop',pkg)
originals={}
for pkg in [app,test]:
 data=raw('exec-out','cat',text('shell','pm','path',pkg).removeprefix('package:'));p=out/('private-original-'+pkg+'.apk');p.write_bytes(data);p.chmod(0o600);originals[pkg]=(p,sha(data))
data=raw('exec-out','run-as',app,'tar','-cf','-','files','shared_prefs');(out/'private-data-before.tar').write_bytes(data);(out/'private-data-before.tar').chmod(0o600)
before=m.archive_files(data);fingerprints=lambda d:{p:sha(v) for p,v in d.items() if not p.startswith(lease+'/')};before_hashes=fingerprints(before)
groups={
 'review-lifecycle':['ReviewedTextDeviceTest#selectedJsonRunsOnlyAfterReviewThenCopiesAndReturns','ReviewedTextDeviceTest#cancelBeforeRunReturnsWithoutExecutingOrSaving','ReviewedTextLifecycleDeviceTest#staleRuntimeCannotPublishOrWriteAfterReplacement','ReviewedTextLifecycleDeviceTest#rejectedAsyncAndSyncHostCallsReleaseApprovedInput','ReviewedTextLifecycleDeviceTest#noViewCompletionReleasesItsApprovedInput'],
 'handoff':['ExtensionCommandHandoffDeviceTest#contextArgumentsAndFallbackReachChildAndCopy','ExtensionCommandHandoffDeviceTest#acknowledgementRevokesParentBeforeChildComposition'],
 'paste':['ExtensionPasteCopyDeviceTest#pointerCopyIsExactOnceAndReturns','ExtensionPasteCopyDeviceTest#returnAndEscapeCancelWithoutCopy']}
try:
 for name,methods in groups.items():
  owned();r['pending_cohort']=name;save()
  command=[sys.executable,'scripts/test-reviewed-text-input.py','--serial','emulator-5580','--avd','Tinycast_Tablet_API_36','--layout','tablet','--font-scale','1','--output',str(out/name),'--apk',str(apk),'--test-apk',str(harness),'--json-archive',str(archive),'--classes',','.join('dev.tinycast.android.'+x for x in methods),'--expected',str(len(methods))]
  with (out/(name+'-host.log')).open('xb') as log:result=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,env=dict(os.environ,ANDROID_HOME='<host-home>/Library/Android/sdk'),timeout=700)
  receipt=json.loads((out/name/'record.json').read_text());r['cohorts'][name]={'exit_code':result.returncode,'completed':receipt['completed'],'passes':receipt.get('passes'),'cleanup_checks':len(receipt['cleanup']),'cleanup_passed':all(v['passed'] for v in receipt['cleanup'].values())};save()
  require(result.returncode==0 and receipt['completed'] and receipt['passes']==len(methods),'Native regression failed')
 r['pending_cohort']=None
finally:
 owned();require('ActiveInstrumentation{' not in text('shell','dumpsys','activity','processes'),'Retain owner under active instrumentation')
 for pkg in [test,app]:raw('shell','am','force-stop',pkg)
 require(not text('shell','run-as',app,'find','files','-maxdepth','1','-name','reviewed-text*lease*'),'Inner recovery owner retained')
 profile=before.get('files/profileInstalled')
 if profile is None:raw('shell','run-as',app,'rm','-f','files/profileInstalled')
 else:raw('shell','run-as',app,'tee','files/profileInstalled',data=profile)
 require(fingerprints(m.archive_files(raw('exec-out','run-as',app,'tar','-cf','-','files','shared_prefs')))==before_hashes,'Original data/evidence changed')
 r['restoration']['original_files_and_evidence']=True;r['preserved_files']=len(before_hashes);save()
 for pkg,(path,expected) in originals.items():
  raw('install','-r','-t',str(path));require(sha(raw('exec-out','cat',text('shell','pm','path',pkg).removeprefix('package:')))==expected,'Original binary changed')
 r['restoration']['original_apks']=True;require(text('shell','id').startswith('uid=2000('),'adbd changed');r['restoration']['unprivileged']=True
 owned();raw('shell','run-as',app,'rm','-rf',lease);r['restoration']['release_after_verification']=True
 r['completed']=len(r['cohorts'])==len(groups) and all(v['completed'] for v in r['cohorts'].values());save()
print(json.dumps(r))
