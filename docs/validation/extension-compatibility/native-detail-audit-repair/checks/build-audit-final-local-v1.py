from pathlib import Path
import hashlib,json,subprocess,os,shutil,collections,xml.etree.ElementTree as ET
B=Path('artifacts/markdown-detail');STATE=B/'audit-final-local-v1-state.json';assert not STATE.exists()
build=json.loads((B/'audit-repair-build-v2-state.json').read_text());assert build['completed'] and build['sources_unchanged']
old=json.loads((B/'final-local-checks-v7-state.json').read_text());assert old['completed']
sources=build['source_hashes'];sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest();assert all(sha(p)==h for p,h in sources.items())
for name in ['python_reuse','node_reuse']:
 assert all(sha(p)==h for p,h in old[name]['matching_relevant_source_hashes'].items())
command=['./gradlew','--no-daemon','-PdroidcastBuildCommit='+build['production_source_commit'],'-PdroidcastTestBuildType=benchmark',':app:assembleBenchmark',':app:assembleBenchmarkAndroidTest',':app:lintBenchmark']
s=dict(completed=False,pid=os.getpid(),production_source_commit=build['production_source_commit'],test_source_commit=build['test_source_commit'],source_hashes=sources,
 retained_jvm=dict(record='audit-repair-build-v2-state.json',sha256=sha(B/'audit-repair-build-v2-state.json'),cases=264,production_and_jvm_sources_unchanged=True),
 debug_build_and_lint_record='audit-repair-build-v2-state.json',python_reuse=old['python_reuse'],node_reuse=old['node_reuse'],command=command)
def save():STATE.write_text(json.dumps(s,indent=2)+'\n')
save()
try:
 with (B/'audit-final-local-v1.log').open('x') as log:code=subprocess.run(command,env=dict(os.environ,ANDROID_HOME='<android-sdk>',JAVA_HOME='<java-home>'),stdout=log,stderr=subprocess.STDOUT).returncode
 s.update(exit_code=code,sources_unchanged=all(sha(p)==h for p,h in sources.items()));save();assert code==0 and s['sources_unchanged']
 out=B/('candidate-'+build['production_source_commit'][:7]+'-audit-final-local-v1');out.mkdir(exist_ok=False)
 for source,name in [('app/build/outputs/apk/benchmark/app-benchmark.apk','benchmark.apk'),('app/build/outputs/apk/androidTest/benchmark/app-benchmark-androidTest.apk','benchmark-test.apk')]:shutil.copyfile(source,out/name)
 lint={}
 for variant,source in [('debug',Path(build['candidate_directory'])/'lint-results-debug.xml'),('benchmark',Path('app/build/reports/lint-results-benchmark.xml'))]:
  counts=collections.Counter(x.get('severity') for x in ET.parse(source).getroot());assert counts['Error']==counts['Fatal']==0
  lint[variant]={k:counts[k] for k in ['Error','Fatal','Warning']};shutil.copyfile(source,out/('lint-'+variant+'.xml'))
 s.update(completed=True,candidate_directory=str(out),benchmark_apk_sha256=sha(out/'benchmark.apk'),benchmark_test_apk_sha256=sha(out/'benchmark-test.apk'),lint=lint,log_sha256=sha(B/'audit-final-local-v1.log'));save()
 print(json.dumps({k:s[k] for k in ['completed','benchmark_apk_sha256','benchmark_test_apk_sha256','lint']}),flush=True)
except BaseException as failure:s['failure_type']=type(failure).__name__;save();raise
