from pathlib import Path,PurePosixPath
import hashlib,io,json,os,re,subprocess,tarfile
base=Path('artifacts/workflow-expansion');out=base/'default-discovery-v1'
def require(value,message):
 if not value:raise RuntimeError(message)
def sha(data):return hashlib.sha256(data).hexdigest()
def read(path):return json.loads(path.read_text())
require(not out.exists(),'Discovery attempt already exists')
adb=['<user>/Library/Android/sdk/platform-tools/adb','-s','emulator-5580'];app='dev.tinycast.android'
def raw(*values):return subprocess.check_output(adb+list(values),timeout=60,stderr=subprocess.STDOUT)
def text(*values):return raw(*values).decode().strip()
def absent(path):
    r=subprocess.run(adb+['shell','run-as',app,'test','-e',path],capture_output=True,timeout=20)
    require(r.returncode in [0,1] and not r.stdout and not r.stderr,'Path absence is unknown')
    return r.returncode==1
def verify_snapshot(name):
    expected=read(base/name);roots=expected['roots'];data=raw('exec-out','run-as',app,'tar','-cf','-',*roots);hashes={}
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        for member in archive:
            path=PurePosixPath(member.name)
            require(not path.is_absolute() and '..' not in path.parts,'Unsafe snapshot member')
            if member.isdir():continue
            require(member.isfile() and member.name not in hashes and any(member.name.startswith(root+'/') for root in roots),'Unexpected snapshot member')
            hashes[member.name]=sha(archive.extractfile(member).read())
    require(hashes==expected['hashes'],'Pre-existing data/evidence differs')
    return len(hashes)
require(text('shell','getprop','ro.boot.qemu.avd_name')=='Tinycast_Tablet_API_36','Wrong emulator')
require(text('shell','id').startswith('uid=2000('),'Privileged adbd')
require('ActiveInstrumentation{' not in text('shell','dumpsys','activity','processes'),'Instrumentation active')
for path in ['files/reviewed-text-fixture-lease','files/reviewed-text-evidence','files/empty-view-fixture-lease','files/compatibility-batch-fixtures']:
    require(absent(path),'Prior fixture ownership remains')
boot=read(base/'private-durable-before-v1.json')['boot_id']
require(text('shell','cat','/proc/sys/kernel/random/boot_id')==boot,'Emulator boot changed')
durable=verify_snapshot('private-durable-before-v1.json');evidence=verify_snapshot('private-evidence-before-v1.json')
build=read(base/'build-harness-v4/state.json')
for package,name in [(app,'app-debug.apk'),(app+'.test','app-debug-androidTest.apk')]:
 path=text('shell','pm','path',package).removeprefix('package:')
 require(sha(raw('exec-out','cat',path))==build['apk_hashes'][name],'Installed APK identity mismatch')
out.mkdir();state={'completed':False,'scope':'AndroidJUnitRunner log-only discovery; no fixture setup, clipboard mutation, or test-method executions; JUnit execution credit is zero','executed_cases':0,'apk_hashes':build['apk_hashes'],'results':[]}
def save():(out/'record.json').write_text(json.dumps(state,indent=2)+'\n')
save()
case='dev.tinycast.android.ReviewedTextDeviceTest#selectedTranslationRunsOnlyAfterReviewAndReportsExactCopyOrServiceFailure'
for mode,extra,expected in [('default',['-e','notClass',case],9),('opt-in',[],10)]:
 command=adb+['shell','am','instrument','-w','-r','-e','log','true','-e','class','dev.tinycast.android.ReviewedTextDeviceTest',*extra,app+'.test/androidx.test.runner.AndroidJUnitRunner']
 result=subprocess.run(command,capture_output=True,timeout=120);(out/(mode+'.log')).write_bytes(result.stdout+result.stderr)
 tests=re.findall(r'^INSTRUMENTATION_STATUS: test=(.+)$',result.stdout.decode(),re.M);unique=sorted(set(tests))
 require(result.returncode==0 and len(unique)==expected,'Discovery count differs')
 require(('selectedTranslationRunsOnlyAfterReviewAndReportsExactCopyOrServiceFailure' in unique)==(mode=='opt-in'),'Optional test filter differs')
 state['results'].append({'mode':mode,'discovered_methods':unique,'count':len(unique),'executed_cases':0});save()
raw('shell','am','force-stop',app);raw('shell','am','force-stop',app+'.test')
verify_snapshot('private-durable-before-v1.json');verify_snapshot('private-evidence-before-v1.json')
require(text('shell','cat','/proc/sys/kernel/random/boot_id')==boot,'Boot changed')
state.update(completed=True,durable_file_count=durable,pre_existing_evidence_count=evidence,durable_data_unchanged=True,pre_existing_evidence_unchanged=True,same_boot=True);save();print(json.dumps({'completed':True,'discovered':[r['count'] for r in state['results']],'executed_cases':0}))
