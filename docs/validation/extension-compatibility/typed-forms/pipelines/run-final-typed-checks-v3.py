from pathlib import Path
import hashlib,json,os,subprocess,shutil,xml.etree.ElementTree as ET
b=Path('artifacts/form-pickers');state=b/'final-typed-checks-v3-state.json';assert not state.exists()
pipeline=json.loads((b/'final-typed-pipeline-v5-state.json').read_text());assert pipeline['completed'] and sum(s['passes'] for s in pipeline['retained_groups'])+sum(s['passes'] for s in pipeline['steps'])==183
native=json.loads((b/'file-workflow-harness-v28-state.json').read_text());old=json.loads((b/'typed-build-ime-layout-v4-state.json').read_text());sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
assert all(sha(p)==value for p,value in native['source_hashes'].items())
changed=[p for p,value in old['source_hashes'].items() if sha(p)!=value];assert changed==['app/src/androidTest/java/dev/tinycast/android/ExtensionFormDeviceTest.kt'],changed
for p in subprocess.check_output(['git','ls-files','app/src/main','app/build.gradle.kts','build.gradle.kts','gradle.properties','settings.gradle.kts'],text=True).splitlines():assert sha(p)==old['source_hashes'][p]
command=['./gradlew','--no-daemon','-PdroidcastBuildCommit='+native['production_source_commit'],':app:testDebugUnitTest',':app:assembleDebug',':app:assembleDebugAndroidTest',':app:assembleBenchmark',':app:lintDebug',':app:lintBenchmark']
record={'completed':False,'pid':os.getpid(),'production_source_commit':native['production_source_commit'],'test_source_commit':native['test_source_commit'],'source_hashes':native['source_hashes'],'command':command,'changed_sources_since_full_build':changed,'retained_node_python_checks':{name:old['checks'][name] for name in ['node-focus','node-submit','python','python-optimized']},'retained_check_scope':'25 Node contracts and77 Python cases per mode are retained, not rerun; every corresponding source is unchanged.'}
def save():state.write_text(json.dumps(record,indent=2)+'\n')
save();log=b/'final-typed-checks-v3-gradle.log'
try:
 with log.open('x') as output:code=subprocess.run(command,env=dict(os.environ,JAVA_HOME='<home>/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home',ANDROID_HOME='<home>/Library/Android/sdk'),stdout=output,stderr=subprocess.STDOUT).returncode
 record.update(exit_code=code,log_sha256=sha(log));save();assert code==0
 out=b/'final-typed-checks-v3';out.mkdir(exist_ok=False)
 shutil.copytree('app/build/test-results/testDebugUnitTest',out/'jvm')
 totals={key:0 for key in ['tests','failures','errors','skipped']}
 for p in (out/'jvm').glob('TEST-*.xml'):
  suite=ET.parse(p).getroot()
  for key in totals:totals[key]+=int(suite.get(key,'0'))
 assert totals=={'tests':229,'failures':0,'errors':0,'skipped':0};record['jvm']=totals
 record['jvm_task_lines']=[line for line in log.read_text().splitlines() if line.startswith('> Task :app:testDebugUnitTest')]
 record['lint']={}
 for variant in ['debug','benchmark']:
  p=Path('app/build/reports/lint-results-'+variant+'.xml');shutil.copyfile(p,out/p.name);rows=ET.parse(p).getroot().findall('issue');record['lint'][variant]={key:sum(row.get('severity')==key for row in rows) for key in ['Error','Fatal','Warning']};assert record['lint'][variant]['Error']==record['lint'][variant]['Fatal']==0
 apks={'app/build/outputs/apk/debug/app-debug.apk':native['frozen_app_sha256'],'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk':native['test_apk_sha256'],'app/build/outputs/apk/benchmark/app-benchmark.apk':old['benchmark_apk_sha256']}
 record['rebuilt_apk_hashes']={p:sha(p) for p in apks};record['rebuilt_apks_equal_frozen']=all(sha(p)==expected for p,expected in apks.items());save();assert record['rebuilt_apks_equal_frozen']
 assert all(sha(p)==value for p,value in native['source_hashes'].items());record.update(completed=True,sources_unchanged=True);save();print(json.dumps({key:record[key] for key in ['completed','jvm','jvm_task_lines','lint','rebuilt_apks_equal_frozen']},indent=2))
except BaseException as failure:record.update(failure_type=type(failure).__name__,sources_unchanged=all(sha(p)==value for p,value in native['source_hashes'].items()));save();raise
