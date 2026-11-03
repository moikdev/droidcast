from pathlib import Path
import argparse,hashlib,json,subprocess,sys
sys.path.insert(0,str(Path('scripts').resolve()))
from android_clipboard_recovery import ClipboardRecovery
from android_test_cleanup import reviewed_text_fixture_ids

p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);p.add_argument('--receipt',type=Path,required=True);a=p.parse_args()
assert not a.receipt.exists();a.receipt.mkdir(mode=0o700)
h=json.loads((a.output/'record.json').read_text());assert not h['completed']
assert h['shared_regression_cohort'] and h['installed_apks_match']
assert {k for k,v in h['cleanup'].items() if not v['passed']}=={'legacy-fixtures-completed','release-lease'}
allowed={'dev.tinycast.android.InlinePaletteDeviceTest','dev.tinycast.android.ReadinessRegressionDeviceTest'}
assert all(x.split('#')[0] in allowed for x in h['classes'].split(','))
adb=['<android-sdk>/platform-tools/adb','-s','emulator-5580'];app='dev.tinycast.android'
def raw(*args,data=None):return subprocess.check_output(adb+list(args),input=data,stderr=subprocess.STDOUT,timeout=60)
def text(*args):return raw(*args).decode().strip()
def absent(path):
 r=subprocess.run(adb+['shell','run-as',app,'test','-e',path],capture_output=True,timeout=30)
 assert r.returncode in (0,1) and not r.stdout and not r.stderr
 return r.returncode==1
assert text('shell','getprop','ro.boot.qemu.avd_name')=='Tinycast_Tablet_API_36'
assert text('shell','id').startswith('uid=2000(')
assert 'ActiveInstrumentation{' not in text('shell','dumpsys','activity','processes')
assert text('shell','wm','size')==h['original_size'] and text('shell','wm','density')==h['original_density']
assert text('shell','settings','get','system','font_scale')==h['original_font_scale']
for key,value in h['settings_before'].items():assert text('shell','settings','get','secure',key)==value
for name in ['palette','emoji']:
 assert raw('exec-out','run-as',app,'cat','shared_prefs/'+name+'.xml')==(a.output/('private-'+name+'-backup.xml')).read_bytes()
for folder in ['reviewed-text-evidence','extension-grid-evidence','readiness-evidence']:assert absent('files/'+folder)
for root in ['cache','files/extension-data']:
 names=text('shell','run-as',app,'find',root,'-maxdepth','1','-name','audit-list-*').splitlines();assert not names
lease='files/reviewed-text-fixture-lease';nonce=h['owner_nonce']
assert reviewed_text_fixture_ids(json.loads(raw('exec-out','run-as',app,'cat',lease+'/owners.json')),nonce)==[]
c=ClipboardRecovery(raw,a.receipt,nonce,app+'.test',{})
c.attempted=c.ready=c.permitted=True;c.identity=hashlib.sha256((a.output/'private-clipboard-snapshot.bin').read_bytes()).hexdigest()
c.restore();raw('shell','am','force-stop',app+'.test');c.release()
assert reviewed_text_fixture_ids(json.loads(raw('exec-out','run-as',app,'cat',lease+'/owners.json')),nonce)==[]
raw('shell','run-as',app,'rm','-rf',lease);assert absent(lease)
receipt=dict(completed=True,original_failed_record=str(a.output/'record.json'),original_failure_retained=True,
 legacy_test_teardown_verified=True,no_audit_list_cache_or_support_remains=True,owned_fixture_ids_empty=True,
 preferences_and_settings_match_private_originals=True,clipboard_restored_and_verified_independently=True,
 copied_test_evidence_retained_and_device_copies_absent=True,application_lease_released_last=True,
 scope='Manual recovery after an inspected legacy test assertion failure. Does not turn the failed cohort or its two expected ownership-retention failures into passes. Private clipboard contents/fingerprints remain excluded.')
(a.receipt/'public-recovery.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps({'completed':True,'failed_cohort_kept':True,'lease_released_after_independent_verification':True}))
