from pathlib import Path,PurePosixPath
import argparse,hashlib,io,json,os,subprocess,sys,tarfile
p=argparse.ArgumentParser();p.add_argument('--layout',choices=['tablet','phone','landscape'],required=True);p.add_argument('--name',required=True);p.add_argument('--variant',choices=['baseline','current'],required=True);p.add_argument('--case',choices=['detail','form','reviewed'],required=True);p.add_argument('--font',type=float,default=1);args=p.parse_args()
if not (args.name.isascii() and args.name.replace('-','').isalnum() and 1<=args.font<=2):raise RuntimeError('Invalid cohort arguments')
if args.variant!='current':raise RuntimeError('Owned regressions use current runtime only')
base=Path('artifacts/workflow-expansion');out=base/args.name
def require(value,message):
    if not value:raise RuntimeError(message)
def sha(data):return hashlib.sha256(data).hexdigest()
def read(path):return json.loads(path.read_text())
require(not out.exists(),'Prior native attempt exists; inspect before repeating')
build=read(base/'build-harness-v3/state.json');require(build['completed'],'Probe build incomplete')
pair=base/'build-harness-v3/candidate';apk=base/'baseline-debug.apk' if args.variant=='baseline' else pair/'app-debug.apk';test=pair/'app-debug-androidTest.apk'
require(sha(test.read_bytes())==build['apk_hashes'][test.name] and sha(apk.read_bytes())==('31ad1cfdac4632bf54f32aae67f11fa0b642a1f8f67079b15030f89295ec0f4b' if args.variant=='baseline' else build['apk_hashes']['app-debug.apk']),'Frozen APK differs')
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
out.mkdir(mode=0o700)
state=dict(completed=False,pid=os.getpid(),layout=args.layout,font=args.font,variant=args.variant,case=args.case,app_sha256=sha(apk.read_bytes()),test_apk_sha256=sha(test.read_bytes()),
    test_source_commit=build['test_source_commit'],test_compile_target_commit=build['test_compile_target_commit'],runtime_source_commit=build['baseline_runtime_source_commit'] if args.variant=='baseline' else build['runtime_source_commit'],
    collector_sha256=sha(Path(__file__).read_bytes()),durable_file_count=durable,pre_existing_evidence_count=evidence)
def save():(out/'state.json').write_text(json.dumps(state,indent=2)+'\n')
save()
archive='<user>/.t3/scratch/droidcast-command-catalog-27caf3/artifacts/extension-catalog/representative-001-bundles-v1/archives/223987eaca2c894e37ada9e087669f260cda79d6a3b88ff11463f429cc952e6a.zip'
groups={
 'detail':('ExtensionDetailDeviceTest',['listTouchDetailPreservesQuerySelectionExactCopyAndCaller','frozenHtmlColorsRendersSvgMetadataCopiesAndReturns']),
 'form':('ExtensionFormDeviceTest',['frozenJsonFormUsesEditedPreferencesCopiesUnicodeAndReturns','controlledTransformsAndRejectedEditsRemainAuthoritative','rapidUncontrolledEditRemainsTheSubmittedValue']),
 'reviewed':('ReviewedTextDeviceTest',['selectedJsonRunsOnlyAfterReviewThenCopiesAndReturns'])}
class_name,method_names=groups[args.case]
method=','.join('dev.tinycast.android.'+class_name+'#'+name for name in method_names)
extra=[]
if args.case=='detail':extra=['--html-colors-archive','<user>/.t3/scratch/droidcast-markdown-detail-27caf3/artifacts/markdown-detail/html-colors-bundle-v1/archives/a5519f5ee2ab77a41c5a59063bc8848ddd69ac7f838ff37aee88f0cfc0ad1a8a.zip']
with (out/'host.log').open('wb') as log:
    result=subprocess.run([sys.executable,'scripts/test-reviewed-text-input.py','--serial','emulator-5580','--avd','Tinycast_Tablet_API_36','--layout',args.layout,'--font-scale',str(args.font),'--output',str(out/'cohort'),
        '--apk',str(apk),'--test-apk',str(test),'--json-archive',archive,'--classes',method,'--expected',str(len(method_names)),*extra],stdout=log,stderr=subprocess.STDOUT,
        env=dict(os.environ,ANDROID_HOME='<user>/Library/Android/sdk'))
receipt=read(out/'cohort/record.json');state.update(runner_exit_code=result.returncode,passes=receipt.get('passes'),cleanup_checks=len(receipt.get('cleanup',{})),
    cleanup_passed=all(row.get('passed') for row in receipt.get('cleanup',{}).values()),native_completed=receipt.get('completed'));save()
require(state['cleanup_passed'] and absent('files/reviewed-text-fixture-lease'),'Native recovery ownership remains; inspect before continuing')
verify_snapshot('private-durable-before-v1.json');verify_snapshot('private-evidence-before-v1.json')
require(text('shell','cat','/proc/sys/kernel/random/boot_id')==boot,'Boot changed')
state.update(durable_data_unchanged=True,pre_existing_evidence_unchanged=True,same_boot=True);save()
witness=state['passes']==len(method_names) and result.returncode==0 and receipt.get('completed') is True
state['positive_witness']=witness
require(sha(Path(__file__).read_bytes())==state['collector_sha256'],'Collector changed during execution')
state['completed']=bool(witness);save()
print(json.dumps({k:state.get(k) for k in ['completed','layout','font','variant','case','passes','routing','intended_negative_witness','positive_witness','cleanup_checks','cleanup_passed','durable_data_unchanged','pre_existing_evidence_unchanged']},indent=2))
require(state['completed'],'Cohort did not establish its intended native witness; inspect the actual outcome')
