from pathlib import Path
import hashlib,json,os,shutil,subprocess,xml.etree.ElementTree as E,zipfile
base=Path('artifacts/offline-workflows');out=base/'build-harness-v1'
assert not out.exists()
sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
git=lambda *a:subprocess.check_output(['git',*a],text=True).strip()
head=git('rev-parse','HEAD');assert head=='c3d9956dc7711667b1898d6c65920f4d782c13c5' and not git('status','--porcelain=v1','--untracked-files=all')
parent=Path('<user>/.t3/scratch/droidcast-workflow-expansion-27caf3/artifacts/workflow-expansion')
identity=json.loads((parent/'final-source-v1/state.json').read_text());runtime=identity['runtime_source_commit']
assert not git('diff','--name-only',runtime,head,'--','app/src/main','app/build.gradle.kts','build.gradle.kts','settings.gradle.kts','gradle.properties','gradle')
sources={path:sha(path) for path in identity['source_hashes']}
changed=[path for path,h in sources.items() if h!=identity['source_hashes'][path]]
assert set(changed)=={'app/src/androidTest/java/dev/tinycast/android/ExtensionFormDeviceTest.kt','app/src/androidTest/java/dev/tinycast/android/ReviewedTextDeviceTest.kt','scripts/test-reviewed-text-input.py','scripts/tests/test_reviewed_text_runner_guards.py'}
frozen=parent/'build-runtime-v1/candidate/app-debug.apk';assert sha(frozen)=='b25450577102e16fb89fd2ef6f91e61070556d0ad06bb2c80bbbce97b5da0e5e'
out.mkdir();state={'completed':False,'pid':os.getpid(),'test_source_commit':head,'runtime_source_commit':runtime,'test_compile_target_commit':runtime,'baseline_merge':'3831cd02b96a6cec93dd20086e555bdf897674fd','source_hashes':sources,'changed_sources':changed,'production_sources_identical_to_frozen_app':True,'commands':[],'runs':[]}
def save():(out/'state.json').write_text(json.dumps(state,indent=2)+'\n')
save();env=dict(os.environ,JAVA_HOME='<user>/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home',ANDROID_HOME='<user>/Library/Android/sdk')
commands=[['./gradlew',':app:testDebugUnitTest',':app:assembleDebug',':app:assembleDebugAndroidTest',':app:lintDebug','-PdroidcastTestBuildType=debug'],['./gradlew',':app:assembleBenchmark',':app:assembleBenchmarkAndroidTest',':app:lintBenchmark','-PdroidcastTestBuildType=benchmark']]
for index,command in enumerate(commands):
 command+=['-PdroidcastBuildCommit='+runtime,'--console=plain'];state['commands'].append(command);save()
 with (out/('build-'+str(index)+'.log')).open('wb') as log:r=subprocess.run(command,env=env,stdout=log,stderr=subprocess.STDOUT)
 state['runs'].append({'index':index,'exit_code':r.returncode});save()
 if r.returncode:raise RuntimeError('Offline harness build failed; inspect retained log')
 if index==0:
  pair=out/'candidate';pair.mkdir();shutil.copyfile(frozen,pair/'app-debug.apk');shutil.copyfile('app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',pair/'app-debug-androidTest.apk')
  state['apk_hashes']={p.name:sha(p) for p in pair.iterdir()};state['freshly_built_debug_app_sha256']=sha('app/build/outputs/apk/debug/app-debug.apk');save()
reports=out/'reports';reports.mkdir();cases=[]
for path in Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
 node=E.parse(path).getroot();assert not node.findall('.//failure') and not node.findall('.//error') and not node.findall('.//skipped')
 cases.extend((case.get('classname'),case.get('name')) for case in node.findall('.//testcase'));shutil.copyfile(path,reports/path.name)
assert len(cases)==len(set(cases));state['jvm_cases']=len(cases);state['lint']={}
for variant in ['debug','benchmark']:
 path=Path('app/build/reports/lint-results-'+variant+'.xml');node=E.parse(path).getroot();counts={severity:len([row for row in node.findall('issue') if row.get('severity')==severity]) for severity in ['Error','Fatal','Warning']}
 assert not counts['Error'] and not counts['Fatal'];state['lint'][variant]=counts;shutil.copyfile(path,reports/path.name)
with zipfile.ZipFile(frozen) as apk:
 assert {name.removeprefix('assets/'):hashlib.sha256(apk.read(name)).hexdigest() for name in apk.namelist() if name.startswith('assets/')}=={str(p.relative_to('app/src/main/assets')):sha(p) for p in Path('app/src/main/assets').rglob('*') if p.is_file()}
assert head==git('rev-parse','HEAD') and not git('status','--porcelain=v1','--untracked-files=all') and all(sha(p)==h for p,h in sources.items())
state['completed']=True;save();print(json.dumps({k:state[k] for k in ['completed','test_source_commit','runtime_source_commit','apk_hashes','freshly_built_debug_app_sha256','jvm_cases','lint']},indent=2))
