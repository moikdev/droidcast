from pathlib import Path
import hashlib,json,os,shutil,subprocess,zipfile
base=Path('artifacts/workflow-expansion');out=base/'build-runtime-v1'
if out.exists():raise RuntimeError('Candidate runtime build already attempted; inspect before repeating')
def sha(data):return hashlib.sha256(data).hexdigest()
def git(*args):return subprocess.check_output(['git',*args],text=True).strip()
old=json.loads((base/'build-probe-v1/state.json').read_text())
head=git('rev-parse','HEAD')
if git('status','--porcelain=v1','--untracked-files=all'):raise RuntimeError('Candidate source must be committed')
paths=set(old['source_hashes'])|{str(p) for p in Path('app/src/main/assets').rglob('*') if p.is_file()}|{str(p) for p in Path('app/src/test/js').glob('*.cjs')}
sources={p:sha(Path(p).read_bytes()) for p in sorted(paths)}
with zipfile.ZipFile(base/'baseline-debug.apk') as archive:
    baseline_assets={name:sha(archive.read(name)) for name in archive.namelist() if name.startswith('assets/')}
current_assets={'assets/'+str(p.relative_to('app/src/main/assets')):sha(p.read_bytes()) for p in Path('app/src/main/assets').rglob('*') if p.is_file()}
if set(current_assets)!=set(baseline_assets) or [name for name,h in current_assets.items() if h!=baseline_assets[name]]!=['assets/android-runtime.js']:raise RuntimeError('Unexpected packaged asset change')
out.mkdir();state=dict(completed=False,pid=os.getpid(),runtime_source_commit=head,source_hashes=sources,baseline_packaged_assets=baseline_assets,
    scope='Candidate debug runtime build only; final JVM/build/lint/device gates remain separate',common_test_source_commit=old['test_source_commit'],common_test_compile_target_commit=old['test_compile_target_commit'])
def save():(out/'state.json').write_text(json.dumps(state,indent=2)+'\n')
save();env=dict(os.environ,JAVA_HOME='<user>/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home',ANDROID_HOME='<user>/Library/Android/sdk')
with (out/'build.log').open('wb') as log:
    r=subprocess.run(['./gradlew',':app:assembleDebug','-PdroidcastBuildCommit='+head,'--console=plain'],env=env,stdout=log,stderr=subprocess.STDOUT)
state['build_exit_code']=r.returncode;save()
if r.returncode:raise RuntimeError('Candidate runtime build failed')
pair=out/'candidate';pair.mkdir()
shutil.copyfile('app/build/outputs/apk/debug/app-debug.apk',pair/'app-debug.apk')
test=base/'build-probe-v1/candidate/app-debug-androidTest.apk'
if sha(test.read_bytes())!=old['apk_hashes'][test.name]:raise RuntimeError('Common instrumentation changed')
shutil.copyfile(test,pair/test.name)
state['apk_hashes']={p.name:sha(p.read_bytes()) for p in pair.iterdir()}
if head!=git('rev-parse','HEAD') or git('status','--porcelain=v1','--untracked-files=all') or any(sha(Path(p).read_bytes())!=h for p,h in sources.items()):raise RuntimeError('Candidate sources changed during build')
state['completed']=True;save();print(json.dumps({k:state[k] for k in ['completed','runtime_source_commit','common_test_source_commit','apk_hashes']},indent=2))
