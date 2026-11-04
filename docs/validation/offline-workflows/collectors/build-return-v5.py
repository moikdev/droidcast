from pathlib import Path
import hashlib,json,os,shutil,subprocess,xml.etree.ElementTree as E,zipfile
base=Path('artifacts/offline-workflows');out=base/'build-return-v5'
def require(value,message):
 if not value:raise RuntimeError(message)
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def git(*a):return subprocess.check_output(['git',*a],text=True).strip()
head=git('rev-parse','HEAD');require(head=='ed3831303a8319a194f0ae137830acba1f3580d5' and not git('status','--porcelain=v1','--untracked-files=all'),'Source not frozen')
require(not out.exists(),'Build attempt exists')
prior=json.loads((base/'build-harness-v1/state.json').read_text());sources={p:sha(p) for p in prior['source_hashes']}
changed=[p for p,h in sources.items() if h!=prior['source_hashes'][p]]
require(len(changed)==6,'Unexpected source changes')
out.mkdir();state={'completed':False,'pid':os.getpid(),'source_commit':head,'test_source_commit':head,'runtime_source_commit':head,'source_hashes':sources,'changed_sources_since_c3d9956':changed,'commands':[],'runs':[]}
def save():(out/'state.json').write_text(json.dumps(state,indent=2)+'\n')
save();env=dict(os.environ,JAVA_HOME='<user>/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home',ANDROID_HOME='<user>/Library/Android/sdk')
commands=[['./gradlew',':app:testDebugUnitTest',':app:assembleDebug',':app:assembleDebugAndroidTest',':app:lintDebug','-PdroidcastTestBuildType=debug'],['./gradlew',':app:assembleBenchmark',':app:assembleBenchmarkAndroidTest',':app:lintBenchmark','-PdroidcastTestBuildType=benchmark']]
for index,command in enumerate(commands):
 command+=['-PdroidcastBuildCommit='+head,'--console=plain'];state['commands'].append(command);save()
 with (out/('build-'+str(index)+'.log')).open('wb') as log:r=subprocess.run(command,env=env,stdout=log,stderr=subprocess.STDOUT)
 state['runs'].append({'index':index,'exit_code':r.returncode});save();require(r.returncode==0,'Build failed; inspect log')
 target=out/('candidate' if index==0 else 'benchmark');target.mkdir();variant='debug' if index==0 else 'benchmark'
 for source in [Path('app/build/outputs/apk')/variant/('app-'+variant+'.apk'),Path('app/build/outputs/apk/androidTest')/variant/('app-'+variant+'-androidTest.apk')]:shutil.copyfile(source,target/source.name)
 state['apk_hashes' if index==0 else 'benchmark_apk_hashes']={p.name:sha(p) for p in target.iterdir()};save()
reports=out/'reports';reports.mkdir();cases=[]
for p in Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
 node=E.parse(p).getroot();require(not node.findall('.//failure') and not node.findall('.//error') and not node.findall('.//skipped'),'JVM failure')
 cases.extend((c.get('classname'),c.get('name')) for c in node.findall('.//testcase'));shutil.copyfile(p,reports/p.name)
require(len(cases)==len(set(cases)) and len(cases)==278,'Unexpected JVM accounting');state['jvm_cases']=len(cases);state['lint']={}
for variant in ['debug','benchmark']:
 p=Path('app/build/reports/lint-results-'+variant+'.xml');node=E.parse(p).getroot();counts={s:sum(r.get('severity')==s for r in node.findall('issue')) for s in ['Error','Fatal','Warning']};require(not counts['Error'] and not counts['Fatal'],'Lint error');state['lint'][variant]=counts;shutil.copyfile(p,reports/p.name)
with zipfile.ZipFile(base/'build-harness-v1/candidate/app-debug.apk') as old:
 old_assets={n:hashlib.sha256(old.read(n)).hexdigest() for n in old.namelist() if n.startswith('assets/')}
with zipfile.ZipFile(out/'candidate/app-debug.apk') as current:
 current_assets={n:hashlib.sha256(current.read(n)).hexdigest() for n in current.namelist() if n.startswith('assets/')}
require(old_assets==current_assets,'Packaged assets changed');state['unchanged_packaged_assets']=old_assets
require(head==git('rev-parse','HEAD') and not git('status','--porcelain=v1','--untracked-files=all') and all(sha(p)==h for p,h in sources.items()),'Source changed during build')
state['completed']=True;save();print(json.dumps({k:state[k] for k in ['completed','source_commit','apk_hashes','jvm_cases','lint']},indent=2))
