from pathlib import Path
import hashlib,json,os,shutil,subprocess
base=Path('artifacts/workflow-expansion');out=base/'build-harness-v4'
if out.exists():raise RuntimeError('Harness v4 already attempted; inspect before repeating')
def sha(data):return hashlib.sha256(data).hexdigest()
def git(*args):return subprocess.check_output(['git',*args],text=True).strip()
runtime=json.loads((base/'build-runtime-v1/state.json').read_text())
head=git('rev-parse','HEAD')
if not runtime['completed'] or git('status','--porcelain=v1','--untracked-files=all'):raise RuntimeError('Runtime incomplete or harness source dirty')
sources={p:sha(Path(p).read_bytes()) for p in runtime['source_hashes']}
changed=[p for p,h in sources.items() if h!=runtime['source_hashes'][p]]
if set(changed)!={'app/src/androidTest/java/dev/tinycast/android/ExtensionDetailDeviceTest.kt','scripts/test-reviewed-text-input.py','scripts/tests/test_reviewed_text_runner_guards.py','app/src/androidTest/java/dev/tinycast/android/ReviewedTextDeviceTest.kt'}:raise RuntimeError('Unexpected harness-only source change')
out.mkdir();state=dict(completed=False,pid=os.getpid(),test_source_commit=head,test_compile_target_commit=runtime['runtime_source_commit'],runtime_source_commit=runtime['runtime_source_commit'],
    baseline_runtime_source_commit='b99a65ec3e96ea27cbeec5759f6083ef56d3c4ff',source_hashes=sources,changed_sources=changed,production_sources_unchanged=True)
def save():(out/'state.json').write_text(json.dumps(state,indent=2)+'\n')
save();env=dict(os.environ,JAVA_HOME='<user>/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home',ANDROID_HOME='<user>/Library/Android/sdk')
with (out/'build.log').open('wb') as log:
    r=subprocess.run(['./gradlew',':app:assembleDebugAndroidTest','-PdroidcastBuildCommit='+runtime['runtime_source_commit'],'-PdroidcastTestBuildType=debug','--console=plain'],env=env,stdout=log,stderr=subprocess.STDOUT)
state['build_exit_code']=r.returncode;save()
if r.returncode:raise RuntimeError('Harness v4 compile failed')
pair=out/'candidate';pair.mkdir()
apk=base/'build-runtime-v1/candidate/app-debug.apk'
if sha(apk.read_bytes())!=runtime['apk_hashes'][apk.name]:raise RuntimeError('Frozen runtime changed')
shutil.copyfile(apk,pair/apk.name)
shutil.copyfile('app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',pair/'app-debug-androidTest.apk')
state['apk_hashes']={p.name:sha(p.read_bytes()) for p in pair.iterdir()}
if head!=git('rev-parse','HEAD') or git('status','--porcelain=v1','--untracked-files=all') or any(sha(Path(p).read_bytes())!=h for p,h in sources.items()):raise RuntimeError('Harness sources changed during compile')
with (out/'benchmark-build.log').open('wb') as log:
    benchmark=subprocess.run(['./gradlew',':app:assembleBenchmarkAndroidTest','-PdroidcastBuildCommit='+runtime['runtime_source_commit'],'-PdroidcastTestBuildType=benchmark','--console=plain'],env=env,stdout=log,stderr=subprocess.STDOUT)
state['benchmark_test_build_exit_code']=benchmark.returncode;save()
if benchmark.returncode:raise RuntimeError('Final benchmark instrumentation compile failed')
state['completed']=True;save();print(json.dumps({k:state[k] for k in ['completed','test_source_commit','runtime_source_commit','apk_hashes']},indent=2))
