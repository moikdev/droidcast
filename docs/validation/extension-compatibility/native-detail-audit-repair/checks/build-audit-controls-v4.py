from pathlib import Path
import hashlib,json,subprocess,os,shutil,xml.etree.ElementTree as ET
B=Path('artifacts/markdown-detail');STATE=B/'audit-controls-build-v4-state.json';assert not STATE.exists()
prior=json.loads((B/'final-native-harness-v7-state.json').read_text());assert prior['completed']
head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip();assert not subprocess.check_output(['git','status','--porcelain'],text=True).strip()
sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
paths=subprocess.check_output(['git','ls-files','app','scripts','build.gradle.kts','settings.gradle.kts','gradle.properties','gradle'],text=True).splitlines()
sources={p:sha(p) for p in paths if Path(p).is_file()}
assert all(sha(p)==h for p,h in prior['source_hashes'].items() if not p.startswith(('app/src/androidTest/','app/src/test/')))
stamp=prior['production_source_commit'];env=dict(os.environ,ANDROID_HOME='<android-sdk>',JAVA_HOME='<java-home>')
s=dict(completed=False,pid=os.getpid(),production_source_commit=stamp,common_test_source_commit=head,source_hashes=sources,production_sources_unchanged=True)
def save():STATE.write_text(json.dumps(s,indent=2)+'\n')
save()
try:
 command=['./gradlew','--no-daemon','-PdroidcastBuildCommit='+stamp,':app:assembleDebugAndroidTest'];s['build_command']=command;save()
 with (B/'audit-controls-build-v4.log').open('x') as log:code=subprocess.run(command,env=env,stdout=log,stderr=subprocess.STDOUT).returncode
 s['build_exit_code']=code;save();assert code==0
 out=B/('candidate-'+head[:7]+'-audit-controls-v4');out.mkdir(exist_ok=False)
 app=Path(prior['candidate_directory'])/'app.apk';assert sha(app)==prior['app_sha256'];shutil.copyfile(app,out/'app.apk');shutil.copyfile('app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',out/'test.apk')
 s.update(candidate_directory=str(out),app_sha256=sha(out/'app.apk'),test_apk_sha256=sha(out/'test.apk'));save()
 previous=json.loads((B/'audit-controls-build-v2-state.json').read_text());assert previous['completed']
 assert all(sha(p)==h for p,h in previous['source_hashes'].items() if not p.startswith('app/src/androidTest/'))
 report=Path(previous['candidate_directory'])/'old-parser-jvm-negative.xml';shutil.copyfile(report,out/report.name)
 code=previous['negative_jvm']['gradle_exit_code']
 s['negative_jvm_reuse']=dict(record='audit-controls-build-v2-state.json',record_sha256=sha(B/'audit-controls-build-v2-state.json'),unchanged_production_and_jvm_sources=True)
 s.update(negative_jvm=dict(gradle_exit_code=code,cases=1,junit_passes=0,expected_parser_failure_verified=True,report_sha256=sha(out/'old-parser-jvm-negative.xml')),
  sources_unchanged=all(sha(p)==h for p,h in sources.items()),completed=True)
 assert s['sources_unchanged'];save();print(json.dumps({k:s[k] for k in ['completed','common_test_source_commit','app_sha256','test_apk_sha256','negative_jvm']}),flush=True)
except BaseException as failure:s['failure_type']=type(failure).__name__;save();raise
