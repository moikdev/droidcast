from pathlib import Path
import hashlib,json,os,shutil,subprocess,zipfile
b=Path('artifacts/empty-feedback');source='15eb95c1c8d91611d5cd099989bf67eaaf5ecaf3';sha=lambda x:hashlib.sha256(x).hexdigest()
assert subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()==source
assert not subprocess.check_output(['git','status','--porcelain'])
env=dict(os.environ,JAVA_HOME='<user-home>/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home',ANDROID_HOME='<android-sdk>')
common=['./gradlew','--no-daemon','--max-workers=1','-Pkotlin.compiler.execution.strategy=in-process','-Dorg.gradle.jvmargs=-Xmx1536m','-PdroidcastBuildCommit='+source]
with (b/'host-v1.log').open('xb') as log:
 subprocess.run(common+[':app:assembleDebug',':app:assembleDebugAndroidTest',':app:testDebugUnitTest',':app:lintDebug'],env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
 log.flush();root=b/'candidate-v1';root.mkdir(exist_ok=False)
 paths=subprocess.check_output(['git','ls-files'],text=True).splitlines()
 inputs=[p for p in paths if p.startswith(('app/src/','gradle/')) or p in ('app/build.gradle.kts','build.gradle.kts','settings.gradle.kts','gradle.properties','gradlew') or p.startswith('scripts/') and p.endswith('.py')]
 j={'source_commit':source,'app_source':source,'test_source':source,'controller_source':source,'build_commit_property':source,'source_sha256':{p:sha(Path(p).read_bytes()) for p in inputs},'apk_sha256':{},'zip_payload_sha256':{}}
 for filename,p in [('app-debug.apk',Path('app/build/outputs/apk/debug/app-debug.apk')),('app-debug-androidTest.apk',Path('app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))]:
  target=root/filename;shutil.copy2(p,target);j['apk_sha256'][filename]=sha(target.read_bytes())
  with zipfile.ZipFile(target) as z:j['zip_payload_sha256'][filename]={n:sha(z.read(n)) for n in z.namelist() if not n.endswith('/')}
 old=Path('<user-home>/.t3/scratch/droidcast-touch-clear-27caf3/artifacts/touch-clear/candidate-v3/app-debug.apk');assert sha(old.read_bytes())=='974b2faadb54b23d5f2d259371943379f7b372c7ad14651f898ee0a82959d750'
 with zipfile.ZipFile(old) as a,zipfile.ZipFile(root/'app-debug.apk') as c:
  assets={n:sha(a.read(n)) for n in a.namelist() if n.startswith('assets/') and not n.endswith('/')};assert assets=={n:sha(c.read(n)) for n in c.namelist() if n.startswith('assets/') and not n.endswith('/')}
 j['unchanged_original_assets']=assets;j['old_app']={'source':'5e7cb6b6e0bc146a9e78085f844594afe4d5e1be','sha256':sha(old.read_bytes()),'scope':'Reviewed PR112 app; common new instrumentation will exercise the original clipped empty feedback'}
 (root/'debug-build.log').write_bytes((b/'host-v1.log').read_bytes())
 (root/'identity.json').write_text(json.dumps(j,indent=2)+'\n');print('Frozen debug candidate',json.dumps(j['apk_sha256']),flush=True)
 subprocess.run(common+['-PdroidcastTestBuildType=benchmark',':app:assembleBenchmark',':app:assembleBenchmarkAndroidTest',':app:lintBenchmark'],env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
print('Debug/JVM/lint and benchmark checks completed',flush=True)
