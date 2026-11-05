from pathlib import Path
import hashlib,json,os,subprocess
B=Path('artifacts/webview-compatibility/audit-repair-v1');C=B/'guard-control';C.mkdir(exist_ok=False)
P=Path('app/src/main/java/dev/tinycast/android/extensions/ExtensionRuntime.kt')
sha=lambda d:hashlib.sha256(d).hexdigest()
original=P.read_bytes();expected=subprocess.check_output(['git','show','fec0a4bf1155c08c141606f92ae8fb1feab4bba0:'+str(P)])
assert original==expected and not subprocess.check_output(['git','status','--porcelain'])
needle=b'check(authorized()) { "This command session has ended" }';replacement=b'check(active()) { "This command session has ended" }'
assert original.count(needle)==1
mutated=original.replace(needle,replacement)
(C/'ExtensionRuntime.original.kt').write_bytes(original);(C/'ExtensionRuntime.mutated.kt').write_bytes(mutated)
paths=subprocess.check_output(['git','ls-files','app/src/main','app/build.gradle.kts','build.gradle.kts','settings.gradle.kts','gradle.properties','gradle'],text=True).splitlines()
r={'completed':False,'purpose':'Synthetic approval-guard regression control only; never a candidate','source_parent':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'runtime_source':'fec0a4bf1155c08c141606f92ae8fb1feab4bba0','build_stamp':'local','single_source_mutation':{'path':str(P),'before_sha256':sha(original),'after_sha256':sha(mutated),'before':needle.decode(),'after':replacement.decode()},'main_source_hashes':{p:sha(Path(p).read_bytes()) for p in paths if Path(p).is_file()},'recipe':['./gradlew','-PdroidcastBuildCommit=local',':app:assembleDebug','--console=plain']}
r['main_source_hashes'][str(P)]=sha(mutated)
def save():(C/'identity.json').write_text(json.dumps(r,indent=2)+'\n')
save()
try:
 P.write_bytes(mutated);r['source_mutation_applied']=True;save()
 env=dict(os.environ,JAVA_HOME='<host-home>/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home',ANDROID_HOME='<host-home>/Library/Android/sdk')
 with (C/'build.log').open('xb') as log:result=subprocess.run(r['recipe'],stdout=log,stderr=subprocess.STDOUT,env=env,timeout=240)
 r['build_exit_code']=result.returncode;save();assert result.returncode==0
 assert P.read_bytes()==mutated
 apk=Path('app/build/outputs/apk/debug/app-debug.apk');(C/'app-debug.apk').write_bytes(apk.read_bytes());r['apk_sha256']=sha(apk.read_bytes())
 import zipfile
 with zipfile.ZipFile(C/'app-debug.apk') as z:r['assets']={p:sha(z.read(p)) for p in z.namelist() if p.startswith('assets/')}
 r['completed']=True
finally:
 assert P.read_bytes() in (mutated,original),'Unexpected source modification; inspect before restoring'
 P.write_bytes(original);r['source_restored_exactly']=P.read_bytes()==expected;save()
assert not subprocess.check_output(['git','diff','--',str(P)])
print(json.dumps({k:r[k] for k in ['completed','apk_sha256','source_restored_exactly']}))
