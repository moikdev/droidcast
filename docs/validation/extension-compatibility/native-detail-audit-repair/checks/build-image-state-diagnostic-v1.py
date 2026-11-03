from pathlib import Path
import hashlib,json,os,subprocess,shutil
B=Path('artifacts/markdown-detail');S=B/'image-state-diagnostic-build-v1.json';assert not S.exists()
sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip();assert head.startswith('952fec8')
assert not subprocess.check_output(['git','status','--porcelain'],text=True).strip()
paths=subprocess.check_output(['git','ls-files','app','scripts','build.gradle.kts','settings.gradle.kts','gradle.properties','gradle'],text=True).splitlines();sources={p:sha(p) for p in paths if Path(p).is_file()}
s=dict(completed=False,pid=os.getpid(),implementation_commit=head,source_hashes=sources,scope='Read-only DEBUG decoded/composed state probe; not a repair or final regression cohort')
S.write_text(json.dumps(s,indent=2)+'\n')
command=['./gradlew','--no-daemon','-PdroidcastBuildCommit='+head,':app:assembleDebug']
with (B/'image-state-diagnostic-build-v1.log').open('x') as log:
 code=subprocess.run(command,env=dict(os.environ,ANDROID_HOME='<android-sdk>',JAVA_HOME='<java-home>'),stdout=log,stderr=subprocess.STDOUT).returncode
s['exit_code']=code;s['sources_unchanged']=all(sha(p)==h for p,h in sources.items());S.write_text(json.dumps(s,indent=2)+'\n');assert code==0 and s['sources_unchanged']
out=B/('candidate-'+head[:7]+'-image-state-diagnostic-v1');out.mkdir(exist_ok=False);shutil.copyfile('app/build/outputs/apk/debug/app-debug.apk',out/'app.apk')
s.update(completed=True,candidate_directory=str(out),app_sha256=sha(out/'app.apk'));S.write_text(json.dumps(s,indent=2)+'\n');print(json.dumps({k:v for k,v in s.items() if k!='source_hashes'}),flush=True)
