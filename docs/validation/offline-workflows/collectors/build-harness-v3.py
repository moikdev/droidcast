from pathlib import Path
import hashlib,json,os,shutil,subprocess,zipfile,xml.etree.ElementTree as E
base=Path('artifacts/offline-workflows');out=base/'build-harness-v3'
def require(v,m):
 if not v:raise RuntimeError(m)
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def git(*a):return subprocess.check_output(['git',*a],text=True).strip()
head=git('rev-parse','HEAD');prior=json.loads((base/'build-return-v2/state.json').read_text());runtime=prior['runtime_source_commit']
require(head=='0bb4dbbb958815a32415f4d545b85beb27e55af5' and not git('status','--porcelain=v1','--untracked-files=all'),'Not frozen')
require(git('diff','--name-only',runtime,head)=='app/src/androidTest/java/dev/tinycast/android/ExtensionFormDeviceTest.kt','Unexpected implementation changes')
sources={p:sha(p) for p in prior['source_hashes']};require([p for p in sources if sources[p]!=prior['source_hashes'][p]]==['app/src/androidTest/java/dev/tinycast/android/ExtensionFormDeviceTest.kt'],'Unexpected source changes')
out.mkdir(exist_ok=False);state={'completed':False,'pid':os.getpid(),'source_commit':head,'test_source_commit':head,'runtime_source_commit':runtime,'source_hashes':sources,'commands':[],'runs':[],'retained_jvm_cases':278,'python_source_hashes_unchanged':True}
def save():(out/'state.json').write_text(json.dumps(state,indent=2)+'\n')
save();env=dict(os.environ,JAVA_HOME='<user>/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home',ANDROID_HOME='<user>/Library/Android/sdk')
for variant in ['debug','benchmark']:
 command=['./gradlew',':app:assemble'+variant.capitalize()+'AndroidTest',':app:lint'+variant.capitalize(),'-PdroidcastTestBuildType='+variant,'-PdroidcastBuildCommit='+runtime,'--console=plain'];state['commands'].append(command);save()
 with (out/(variant+'-build.log')).open('wb') as log:r=subprocess.run(command,env=env,stdout=log,stderr=subprocess.STDOUT)
 state['runs'].append({'variant':variant,'exit_code':r.returncode});save();require(r.returncode==0,'Harness compilation failed')
 target=out/('candidate' if variant=='debug' else 'benchmark');target.mkdir()
 name='app-'+variant+'-androidTest.apk';shutil.copyfile(Path('app/build/outputs/apk/androidTest')/variant/name,target/name)
 app_name='app-'+variant+'.apk';shutil.copyfile(base/'build-return-v2'/('candidate' if variant=='debug' else 'benchmark')/app_name,target/app_name)
 state['apk_hashes' if variant=='debug' else 'benchmark_apk_hashes']={p.name:sha(p) for p in target.iterdir()};save()
reports=out/'reports';reports.mkdir();state['lint']={}
for variant in ['debug','benchmark']:
 p=Path('app/build/reports/lint-results-'+variant+'.xml');node=E.parse(p).getroot();counts={s:sum(r.get('severity')==s for r in node.findall('issue')) for s in ['Error','Fatal','Warning']};require(not counts['Error'] and not counts['Fatal'],'Lint failed');state['lint'][variant]=counts;shutil.copyfile(p,reports/p.name)
with zipfile.ZipFile(base/'build-return-v2/candidate/app-debug-androidTest.apk') as a,zipfile.ZipFile(out/'candidate/app-debug-androidTest.apk') as b:
 require(set(a.namelist())==set(b.namelist()),'ZIP paths changed');state['test_apk_changed_payloads']=[n for n in a.namelist() if a.read(n)!=b.read(n)]
require(all(sha(p)==h for p,h in sources.items()) and head==git('rev-parse','HEAD') and not git('status','--porcelain=v1','--untracked-files=all'),'Source changed')
state['completed']=True;save();print(json.dumps({k:state[k] for k in ['completed','source_commit','runtime_source_commit','apk_hashes','test_apk_changed_payloads','lint']},indent=2))
