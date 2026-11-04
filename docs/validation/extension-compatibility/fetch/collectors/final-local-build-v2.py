from pathlib import Path
import hashlib,json,os,shutil,subprocess,xml.etree.ElementTree as E
base=Path('artifacts/workflow-expansion');out=base/'final-local-build-v2'
assert not out.exists()
head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
assert not subprocess.check_output(['git','status','--porcelain=v1','--untracked-files=all'],text=True).strip()
source=json.loads((base/'build-harness-v3/state.json').read_text())
sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
assert source['completed'] and all(sha(p)==h for p,h in source['source_hashes'].items())
out.mkdir();state={'completed':False,'head':head,'pid':os.getpid(),'source_hashes':source['source_hashes'],'runtime_build_commit':source['runtime_source_commit']}
def save():(out/'record.json').write_text(json.dumps(state,indent=2)+'\n')
save()
env=dict(os.environ,JAVA_HOME='<user>/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home',ANDROID_HOME='<user>/Library/Android/sdk')
commands=[['./gradlew',':app:testDebugUnitTest',':app:assembleDebug',':app:assembleDebugAndroidTest',':app:assembleBenchmark',':app:lintDebug',':app:lintBenchmark','-PdroidcastTestBuildType=debug'],['./gradlew',':app:assembleBenchmarkAndroidTest','-PdroidcastTestBuildType=benchmark']]
state['commands']=commands;state['runs']=[];save()
for index,command in enumerate(commands):
 command += ['-PdroidcastBuildCommit='+source['runtime_source_commit'],'--console=plain']
 with (out/('build-'+str(index)+'.log')).open('wb') as log:r=subprocess.run(command,env=env,stdout=log,stderr=subprocess.STDOUT)
 state['runs'].append({'index':index,'exit_code':r.returncode});save()
 if r.returncode:raise RuntimeError('Final local build failed; inspect retained log')
reports=out/'reports';reports.mkdir();cases=[]
for p in Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
 root=E.parse(p).getroot();assert not root.findall('.//failure') and not root.findall('.//error') and not root.findall('.//skipped')
 cases.extend((c.get('classname'),c.get('name')) for c in root.findall('.//testcase'));shutil.copyfile(p,reports/p.name)
assert len(cases)==len(set(cases))
state['jvm_cases']=len(cases);state['lint']={}
for variant in ['debug','benchmark']:
 p=Path('app/build/reports/lint-results-'+variant+'.xml');root=E.parse(p).getroot();counts={k:len([i for i in root.findall('issue') if i.get('severity')==k]) for k in ['Error','Fatal','Warning']}
 state['lint'][variant]=counts;assert not counts['Error'] and not counts['Fatal'];shutil.copyfile(p,reports/p.name)
state['apk_hashes']={str(p):sha(p) for p in Path('app/build/outputs/apk').rglob('*.apk')}
assert all(sha(p)==h for p,h in source['source_hashes'].items()) and head==subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
state['completed']=True;save();print(json.dumps({k:state[k] for k in ['completed','head','jvm_cases','lint']},indent=2))
