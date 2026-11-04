from pathlib import Path,PurePosixPath
import argparse,hashlib,io,json,os,re,subprocess,sys,tarfile
cases={
 'return-guards':('ExtensionFormDeviceTest',['ownedNoViewReturnRejectsDisposedAndReplacedCallbacks'],None),
 'return-keyboard':('ExtensionFormDeviceTest',['ownedNoViewReturnAcceptsFocusedEnterAndPreservesEscape'],None),
 'return-pending':('ExtensionFormDeviceTest',['ownedNoViewPendingCommandKeepsHeaderCancellation'],None),
 'uuid-v7':('ExtensionFormDeviceTest',['frozenUuidV7GeneratesThreeFreshIdsAndOwnedHistory'],'uuid'),
 'uuid-ulid':('ExtensionFormDeviceTest',['frozenUuidUlidsHaveBoundedTimestampsMonotonicOrderAndOwnedHistory'],'uuid'),
 'uuid-typeid':('ExtensionFormDeviceTest',['frozenUuidTypeIdsHaveOwnedPrefixesAndVersionSevenSuffixes'],'uuid'),
 'typeid-parse':('ExtensionFormDeviceTest',['frozenUuidParsesTheIndependentTypeIdVector'],'uuid'),
 'uuid-format':('ExtensionFormDeviceTest',['frozenUuidFormatsTheIndependentCanonicalVector'],'uuid'),
 'typeid-pack':('ExtensionFormDeviceTest',['frozenUuidPacksTheIndependentTypeIdVector'],'uuid'),
 'lorem-paragraphs':('ExtensionFormDeviceTest',['frozenLoremProducesExactlyTwoBoundedParagraphs'],'lorem'),
 'lorem-sentences':('ExtensionFormDeviceTest',['frozenLoremProducesExactlyThreeBoundedSentences'],'lorem'),
 'json-lines':('ExtensionFormDeviceTest',['frozenJsonLinesRejectsInvalidInputThenCopiesExactUnicodeLines'],None),
 'json-value':('ExtensionFormDeviceTest',['frozenJsonValueEscapesUnicodeAndReloadsItsOwnedInput'],None),
 'json-selection':('ReviewedTextDeviceTest',['selectedJsonRunsOnlyAfterReviewThenCopiesAndReturns'],'selection'),
 'legacy-review':('ReviewedTextDeviceTest',['selectedJsonRunsOnlyAfterReviewThenCopiesAndReturns','sharedJsonRunsOnlyAfterReviewThenCopiesAndReturns','writableSelectionStillUsesCopyWithoutReplacingSource'],None)}
p=argparse.ArgumentParser();p.add_argument('--case',choices=list(cases),required=True);p.add_argument('--layout',choices=['tablet','phone','landscape'],required=True);p.add_argument('--font',type=float,default=1);p.add_argument('--name',required=True);p.add_argument('--app',choices=['old','new'],required=True);p.add_argument('--completion-return',action='store_true');p.add_argument('--expect-missing-return',action='store_true');args=p.parse_args()
base=Path('artifacts/offline-workflows');out=base/args.name
require_pattern=bool(re.fullmatch('[a-z0-9][a-z0-9-]{0,100}',args.name))
def require(value,message):
 if not value:raise RuntimeError(message)
def sha(data):return hashlib.sha256(data).hexdigest()
def read(path):return json.loads(path.read_text())
require(require_pattern and 1<=args.font<=2,'Invalid cohort arguments');require(not out.exists(),'Attempt exists; inspect before repeating')
build=read(base/'build-return-v2/state.json');require(build['completed'],'Harness incomplete')
old=read(base/'build-harness-v1/state.json')
require(not args.expect_missing_return or args.app=='old' and (args.completion_return or args.case in ['return-guards','return-keyboard']) and args.case not in ['return-pending','legacy-review','json-lines','json-value'],'Invalid missing-Return control')
apk=base/('build-harness-v1' if args.app=='old' else 'build-return-v2')/'candidate/app-debug.apk';test=base/'build-return-v2/candidate/app-debug-androidTest.apk'
app_identity=old if args.app=='old' else build
require(sha(apk.read_bytes())==app_identity['apk_hashes'][apk.name] and sha(test.read_bytes())==build['apk_hashes'][test.name],'Frozen APK mismatch')
for path,h in build['source_hashes'].items():
 if path.endswith('.py'):require(sha(Path(path).read_bytes())==h,'Executed runner/helper sources differ from compiled cohort identity')
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
klass,names,extra_kind=cases[args.case]
classes=','.join('dev.tinycast.android.'+klass+'#'+name for name in names)
cache=Path('<user>/.t3/scratch/droidcast-command-catalog-27caf3/artifacts/extension-catalog/representative-001-bundles-v1/archives')
extra=[]
if extra_kind in ['uuid','lorem']:
 key={'uuid':'e34e4512815e96b67e47c9666f0307e501d9dfa1619bba4be8290c6205a55897.zip','lorem':'2803877a52b0cb51da9647dd0c31661a1d8e7714c4b748ec39440da57a5b5d24.zip'}[extra_kind]
 extra=['--'+extra_kind+'-archive',str(cache/key)]
if extra_kind=='selection':extra=['--reviewed-json-command','formatSelection']
if args.completion_return:extra+=['--expect-completion-return']
state={'completed':False,'pid':os.getpid(),'layout':args.layout,'font':args.font,'case':args.case,'methods':names,'app_sha256':app_identity['apk_hashes'][apk.name],'app_cohort':args.app,'completion_return_expected':args.completion_return,'intended_missing_return_control':args.expect_missing_return,'test_apk_sha256':build['apk_hashes'][test.name],'test_source_commit':build['test_source_commit'],'runtime_source_commit':app_identity['runtime_source_commit'],'collector_sha256':sha(Path(__file__).read_bytes()),'durable_file_count':durable,'pre_existing_evidence_count':evidence}
def save():(out/'state.json').write_text(json.dumps(state,indent=2)+'\n')
save()
with (out/'host.log').open('wb') as log:
 result=subprocess.run([sys.executable,'scripts/test-reviewed-text-input.py','--serial','emulator-5580','--avd','Tinycast_Tablet_API_36','--layout',args.layout,'--font-scale',str(args.font),'--output',str(out/'cohort'),'--apk',str(apk),'--test-apk',str(test),'--json-archive',str(cache/'223987eaca2c894e37ada9e087669f260cda79d6a3b88ff11463f429cc952e6a.zip'),'--classes',classes,'--expected',str(len(names)),*extra],stdout=log,stderr=subprocess.STDOUT,env=dict(os.environ,ANDROID_HOME='<user>/Library/Android/sdk'))
receipt=read(out/'cohort/record.json');state.update(runner_exit_code=result.returncode,passes=receipt.get('passes'),cleanup_checks=len(receipt.get('cleanup',{})),cleanup_passed=bool(receipt.get('cleanup')) and all(row.get('passed') for row in receipt.get('cleanup',{}).values()),native_completed=receipt.get('completed'));save()
require(state['cleanup_passed'] and absent('files/reviewed-text-fixture-lease'),'Recovery ownership remains; stop and inspect before another run')
verify_snapshot('private-durable-before-v1.json');verify_snapshot('private-evidence-before-v1.json');require(text('shell','cat','/proc/sys/kernel/random/boot_id')==boot,'Boot changed')
state.update(durable_data_unchanged=True,pre_existing_evidence_unchanged=True,same_boot=True);save()
records=[]
for name in names:
 prefix='form-' if klass=='ExtensionFormDeviceTest' else 'text-'
 found=list((out/'cohort/captures').glob(prefix+name.lower()+'-record.json'));require(len(found)==1,'Missing case receipt');record=read(found[0]);records.append(record)
state['case_receipts_passed']=all(record.get('passed') is True for record in records)
if extra_kind=='selection' and not args.expect_missing_return:require(all(record.get('executed_command')=='formatSelection' and record.get('returned_to_external_caller') is True and record.get('source_text_and_selection_unchanged') is True for record in records),'No-view source/command identity or return differs')
state['native_positive_completed']=result.returncode==0 and receipt.get('completed') is True and state['passes']==len(names) and state['case_receipts_passed']
if args.expect_missing_return:
 log=(out/'cohort/tests.log').read_text();require(len(names)==1 and result.returncode!=0 and state['passes']==0 and not receipt.get('completed') and not state['case_receipts_passed'] and 'A completed no-view command must expose its touch Return action' in log,'Control failed for an unintended reason')
 state['intended_negative_completed']=True
state['completed']=state['native_positive_completed'] or args.expect_missing_return and state.get('intended_negative_completed') is True;save()
require(sha(Path(__file__).read_bytes())==state['collector_sha256'],'Collector changed during execution')
print(json.dumps({key:state.get(key) for key in ['completed','case','layout','font','passes','cleanup_checks','cleanup_passed','durable_data_unchanged','pre_existing_evidence_unchanged']},indent=2))
require(state['completed'],'Case failed; inspect retained failure before continuing')
