from pathlib import Path
import hashlib,json,os,shutil,subprocess
base=Path('artifacts/workflow-expansion');out=base/'build-probe-v1'
if out.exists():raise RuntimeError('Probe build already attempted; inspect before repeating')
old_root=Path('<user>/.t3/scratch/droidcast-calculator-actions-27caf3/artifacts/calculator-actions/build-harness-v12')
old=json.loads((old_root/'state.json').read_text())
def sha(data):return hashlib.sha256(data).hexdigest()
def git(*args):return subprocess.check_output(['git',*args],text=True).strip()
head=git('rev-parse','HEAD')
if git('status','--porcelain=v1','--untracked-files=all'):raise RuntimeError('Probe source must be committed')
sources={p:sha(Path(p).read_bytes()) for p in old['source_hashes']}
changed=[p for p,h in sources.items() if old['source_hashes'][p]!=h]
if changed!=['app/src/androidTest/java/dev/tinycast/android/ExtensionDetailDeviceTest.kt']:raise RuntimeError('Unexpected baseline source delta')
out.mkdir();state=dict(completed=False,pid=os.getpid(),test_source_commit=head,test_compile_target_commit=old['runtime_source_commit'],baseline_runtime_source_commit=old['runtime_source_commit'],source_hashes=sources,production_sources_unchanged=True,changed_sources=changed)
def save():(out/'state.json').write_text(json.dumps(state,indent=2)+'\n')
save()
env=dict(os.environ,JAVA_HOME='<user>/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home',ANDROID_HOME='<user>/Library/Android/sdk')
with (out/'build.log').open('wb') as log:
    result=subprocess.run(['./gradlew',':app:assembleDebugAndroidTest','-PdroidcastBuildCommit='+old['runtime_source_commit'],'-PdroidcastTestBuildType=debug','--console=plain'],env=env,stdout=log,stderr=subprocess.STDOUT)
state['build_exit_code']=result.returncode;save()
if result.returncode:raise RuntimeError('Diagnostic instrumentation compile failed')
pair=out/'candidate';pair.mkdir()
source=base/'baseline-debug.apk'
if sha(source.read_bytes())!=old['apk_hashes']['app-debug.apk']:raise RuntimeError('Baseline runtime changed')
shutil.copyfile(source,pair/'app-debug.apk')
shutil.copyfile('app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',pair/'app-debug-androidTest.apk')
state['apk_hashes']={p.name:sha(p.read_bytes()) for p in pair.iterdir()}
if head!=git('rev-parse','HEAD') or git('status','--porcelain=v1','--untracked-files=all') or any(sha(Path(p).read_bytes())!=h for p,h in sources.items()):raise RuntimeError('Source changed during diagnostic compile')
state['completed']=True;save()
print(json.dumps({k:state[k] for k in ['completed','test_source_commit','test_compile_target_commit','baseline_runtime_source_commit','apk_hashes']},indent=2))
