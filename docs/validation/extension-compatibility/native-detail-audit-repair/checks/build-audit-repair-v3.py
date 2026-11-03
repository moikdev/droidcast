from pathlib import Path
import hashlib,json,subprocess,os,shutil,collections,struct,zipfile,xml.etree.ElementTree as ET
B=Path('artifacts/markdown-detail');STATE=B/'audit-repair-build-v3-state.json';assert not STATE.exists()
head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip();assert not subprocess.check_output(['git','status','--porcelain'],text=True).strip()
sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
paths=subprocess.check_output(['git','ls-files','app','scripts','build.gradle.kts','settings.gradle.kts','gradle.properties','gradle'],text=True).splitlines();sources={p:sha(p) for p in paths if Path(p).is_file()}
common=json.loads((B/'audit-controls-build-v4-state.json').read_text());assert common['completed']
android_test_changes=[p for p,h in common['source_hashes'].items() if p.startswith('app/src/androidTest/') and sha(p)!=h]
assert set(android_test_changes)=={'app/src/androidTest/assets/owned-detail-fixture.js','app/src/androidTest/java/dev/tinycast/android/ExtensionDetailDeviceTest.kt'}
assert json.loads((B/'audit-before-controls-v1-state.json').read_text())['completed']
command=['./gradlew','--no-daemon','-PdroidcastBuildCommit='+head,':app:testDebugUnitTest',':app:assembleDebug',':app:assembleDebugAndroidTest',':app:lintDebug']
s=dict(completed=False,pid=os.getpid(),production_source_commit=head,test_source_commit=head,source_hashes=sources,command=command,
 common_test_source_commit=common['common_test_source_commit'],common_test_apk_sha256=common['test_apk_sha256'],android_test_sources_match_common=False,android_test_changes_from_common=android_test_changes)
def save():STATE.write_text(json.dumps(s,indent=2)+'\n')
save()
try:
 with (B/'audit-repair-build-v3.log').open('x') as log:code=subprocess.run(command,env=dict(os.environ,ANDROID_HOME='<android-sdk>',JAVA_HOME='<java-home>'),stdout=log,stderr=subprocess.STDOUT).returncode
 s.update(exit_code=code,sources_unchanged=all(sha(p)==h for p,h in sources.items()));save();assert code==0 and s['sources_unchanged']
 out=B/('candidate-'+head[:7]+'-audit-repair-v3');out.mkdir(exist_ok=False)
 for source,name in [('app/build/outputs/apk/debug/app-debug.apk','app.apk'),('app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk','test.apk')]:shutil.copyfile(source,out/name)
 old=Path(common['candidate_directory'])/'test.apk';assert sha(old)==common['test_apk_sha256'];shutil.copyfile(old,out/'common-test.apk')
 jvm=out/'jvm';jvm.mkdir();total=failures=errors=skips=0
 for p in Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
  root=ET.parse(p).getroot();total+=int(root.get('tests','0'));failures+=int(root.get('failures','0'));errors+=int(root.get('errors','0'));skips+=int(root.get('skipped','0'));shutil.copyfile(p,jvm/p.name)
 assert total==264 and failures==errors==skips==0
 lint=Path('app/build/reports/lint-results-debug.xml');counts=collections.Counter(x.get('severity') for x in ET.parse(lint).getroot());assert counts['Error']==counts['Fatal']==0;shutil.copyfile(lint,out/lint.name)
 for name in ['app.apk','test.apk']:
  with zipfile.ZipFile(out/name) as archive:
   for entry in archive.namelist():
    if not (entry.startswith('classes') and entry.endswith('.dex')):continue
    raw=archive.read(entry);assert raw.startswith(b'dex\n')
    strings=struct.unpack_from('<I',raw,60)[0];types=struct.unpack_from('<I',raw,68)[0]
    count,offset=struct.unpack_from('<II',raw,96)
    for i in range(count):
     type_id=struct.unpack_from('<I',raw,offset+i*32)[0];string_id=struct.unpack_from('<I',raw,types+type_id*4)[0]
     position=struct.unpack_from('<I',raw,strings+string_id*4)[0]
     while raw[position]&128:position+=1
     position+=1;descriptor=raw[position:raw.index(0,position)]
     assert not descriptor.startswith(b'Lorg/json/'),'The host-only JSON provider must not define application/instrumentation classes'
 s['host_json_provider_not_defined_in_app_or_instrumentation']=True
 s.update(completed=True,candidate_directory=str(out),app_sha256=sha(out/'app.apk'),test_apk_sha256=sha(out/'test.apk'),jvm=dict(cases=total,failures=failures,errors=errors,skipped=skips),lint={k:counts[k] for k in ['Error','Fatal','Warning']});save()
 print(json.dumps({k:s[k] for k in ['completed','production_source_commit','app_sha256','test_apk_sha256','common_test_apk_sha256','jvm','lint']}),flush=True)
except BaseException as failure:s['failure_type']=type(failure).__name__;save();raise
