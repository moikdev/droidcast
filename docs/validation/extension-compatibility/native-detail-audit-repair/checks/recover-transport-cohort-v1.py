from pathlib import Path, PurePosixPath
import hashlib,json,re,subprocess,sys,xml.etree.ElementTree as ET
sys.path.insert(0,str(Path('scripts').resolve()))
from android_clipboard_recovery import ClipboardRecovery
from android_test_cleanup import private_path_absent,reviewed_text_fixture_ids
B=Path('artifacts/markdown-detail');SOURCE=B/'audit-final-native-v2-markdown-phone200';OUT=B/'transport-cohort-recovery-v1'
assert not OUT.exists(),'Inspect any existing recovery journal before repeating mutations'
h=json.loads((SOURCE/'record.json').read_text());assert not h['completed'] and h['tests_completed'] and h['passes']==4
assert h['classes']=='dev.tinycast.android.ExtensionMarkdownDeviceTest' and h['layout']=='phone' and h['font_scale']==2
assert len(h['cleanup'])==20 and not any(x['passed'] for x in h['cleanup'].values())
outer=json.loads((B/'private-audit-final-evidence-preservation-v2/recovery-owner.json').read_text())
originals=json.loads((B/'private-audit-final-evidence-preservation-v2/original-file-hashes.json').read_text());assert len(originals)==136
app='dev.tinycast.android';lease='files/reviewed-text-fixture-lease';nonce=h['owner_nonce'];owner=outer['owner']
assert re.fullmatch(r'files/markdown-detail-final-evidence-[0-9a-f]{32}',owner)
assert owner.endswith(outer['owner_nonce']) and not outer['completed']
adb=['<android-sdk>/platform-tools/adb','-s','emulator-5580']
def raw(*args,data=None):return subprocess.check_output(adb+list(args),input=data,stderr=subprocess.STDOUT,timeout=30)
def text(*args):return raw(*args).decode().strip()
def absent(path):return private_path_absent(subprocess.run(adb+['shell','run-as',app,'test','-e',path],capture_output=True,timeout=30))
def sha(data):return hashlib.sha256(data).hexdigest()
assert text('shell','getprop','sys.boot_completed')=='1'
assert text('shell','getprop','ro.boot.qemu.avd_name')=='Tinycast_Tablet_API_36'
assert text('shell','id').startswith('uid=2000(')
assert not text('shell','run-as',app,'id').startswith(('uid=0','uid=1000(','uid=2000('))
assert 'ActiveInstrumentation{' not in text('shell','dumpsys','activity','processes')
for package,expected in [(app,h['apk_sha256']),(app+'.test',h['test_apk_sha256'])]:
    path=text('shell','pm','path',package).removeprefix('package:');assert sha(raw('exec-out','cat',path))==expected
def current_ids():return reviewed_text_fixture_ids(json.loads(raw('exec-out','run-as',app,'cat',lease+'/owners.json')),nonce)
ids=current_ids();assert len(ids)==4
expected_outer={'nonce':outer['owner_nonce'],'folders':['extension-grid-evidence','readiness-evidence']}
def verify_outer():assert json.loads(raw('exec-out','run-as',app,'cat',owner+'/owner.json'))==expected_outer
verify_outer()
OUT.mkdir(mode=0o700);journal={'phase':'ownership_and_apks_verified','checks':{},'scope':'Recovery only; original failed cohort remains failed'}
def save():
    p=OUT/'private-recovery-journal.json';p.write_text(json.dumps(journal,indent=2)+'\n');p.chmod(0o600)
def passed(name):journal['checks'][name]=True;save();print('Verified recovery step:',name,flush=True)
save()
raw('shell','am','force-stop',app);raw('shell','am','force-stop',app+'.test')
evidence='files/reviewed-text-evidence';assert not absent(evidence)
names=text('shell','run-as',app,'ls',evidence).splitlines();assert names and all(re.fullmatch(r'[a-z0-9-]+\.(png|xml|json)',n) for n in names)
captures=OUT/'recovered-captures';captures.mkdir(mode=0o700)
for name in names:
    data=raw('exec-out','run-as',app,'cat',evidence+'/'+name)
    if name.endswith('.png'):assert data.startswith(b'\x89PNG\r\n\x1a\n')
    elif name.endswith('.xml'):ET.fromstring(data)
    else:json.loads(data)
    p=captures/name;p.write_bytes(data);p.chmod(0o600)
cases=[json.loads(p.read_text()) for p in captures.glob('*-record.json')]
assert len(cases)==4 and all(x['passed'] and x['owned_data_removed'] and x['preexisting_installations_and_data_preserved'] for x in cases)
assert text('shell','run-as',app,'ls',evidence).splitlines()==names
for name in names:assert (captures/name).read_bytes()==raw('exec-out','run-as',app,'cat',evidence+'/'+name)
passed('failed_cohort_raw_evidence_recovered_byte_identically')
assert current_ids()==ids;raw('shell','run-as',app,'rm','-rf',evidence);assert absent(evidence)
for identity in ids:
    paths=['files/extensions/'+identity,'cache/'+identity,'files/extension-data/'+identity,'shared_prefs/extension-'+identity+'.xml','shared_prefs/extension-'+identity+'.xml.bak']
    raw('shell','run-as',app,'rm','-rf',*paths);assert all(absent(p) for p in paths)
passed('only_registered_fixture_paths_removed')
for name in ['palette','emoji']:
    data=(SOURCE/('private-'+name+'-backup.xml')).read_bytes()
    raw('shell','run-as',app,'rm','-f','shared_prefs/'+name+'.xml.bak')
    raw('shell','run-as',app,'tee','shared_prefs/'+name+'.xml',data=data)
    assert raw('exec-out','run-as',app,'cat','shared_prefs/'+name+'.xml')==data
passed('preferences_equal_private_originals')
font=h['original_font_scale']
raw('shell','settings','delete','system','font_scale') if font=='null' else raw('shell','settings','put','system','font_scale',font)
for key,value in h['settings_before'].items():
    raw('shell','settings','delete','secure',key) if value=='null' else raw('shell','settings','put','secure',key,value)
raw('shell','wm','size','reset');raw('shell','wm','density','reset')
def verify_shared_state():
    assert text('shell','wm','size')==h['original_size'] and text('shell','wm','density')==h['original_density']
    assert text('shell','settings','get','system','font_scale')==font
    assert all(text('shell','settings','get','secure',k)==v for k,v in h['settings_before'].items())
    for name in ['palette','emoji']:
        assert raw('exec-out','run-as',app,'cat','shared_prefs/'+name+'.xml')==(SOURCE/('private-'+name+'-backup.xml')).read_bytes()
    assert text('shell','id').startswith('uid=2000(')
    assert not text('shell','run-as',app,'id').startswith(('uid=0','uid=1000(','uid=2000('))
verify_shared_state();passed('font_geometry_secure_settings_and_unprivileged_identity_restored')
c=ClipboardRecovery(raw,OUT,nonce,app+'.test',{})
c.attempted=c.ready=c.permitted=True;c.identity=sha((SOURCE/'private-clipboard-snapshot.bin').read_bytes())
c.restore();raw('shell','am','force-stop',app+'.test');passed('clipboard_restored_and_verified_independently')
def inventory(folder):
    root='files/'+folder;assert not text('shell','run-as',app,'find',root,'-type','l')
    result={}
    for name in text('shell','run-as',app,'find',root,'-type','f').splitlines():
        path=PurePosixPath(name);assert name.startswith(root+'/') and '..' not in path.parts
        result[str(path.relative_to('files'))]=sha(raw('exec-out','run-as',app,'cat',name))
    return result
for folder in expected_outer['folders']:
    verify_outer();assert current_ids()==ids
    source=owner+'/'+folder;destination='files/'+folder
    if absent(destination):assert not absent(source);raw('shell','run-as',app,'mv',source,destination)
    else:assert absent(source),'Conflicting preserved evidence roots'
restored={}
for folder in expected_outer['folders']:restored.update(inventory(folder))
assert restored==originals;passed('all_136_preserved_evidence_files_match_private_originals')
verify_shared_state();verify_outer();assert current_ids()==ids
raw('shell','run-as',app,'rm',owner+'/owner.json');raw('shell','run-as',app,'rmdir',owner);assert absent(owner)
passed('outer_preservation_owner_released_after_restoration')
verify_shared_state();assert current_ids()==ids
c.release();passed('clipboard_owner_released_after_verified_restoration')
assert current_ids()==ids
raw('shell','run-as',app,'rm','-rf',lease)  # Final device operation; all restoration/verification precedes release.
passed('application_recovery_lease_released_last')
journal.update(phase='completed',completed=True);save()
public=dict(completed=True,original_failed_record='audit-final-native-v2-markdown-phone200/record.json',
    original_failed_cleanup_checks_retained=20,original_failed_cohort_not_credited=True,
    recovered_case_receipts=4,recovered_raw_files=len(names),preserved_evidence_files=136,checks=journal['checks'],
    original_and_restarted_apks_match=True,private_disk_checkpoint_retained=True,
    scope='Manual recovery after a transport stall and cold boot. Original failed runner/outer-preservation results remain failed. APK identity, preferences, clipboard, font, geometry, secure settings and 136 evidence bytes are verified. Original logical installed-data fingerprints were not persisted for a cross-boot comparison; the private pre-restart disk checkpoint remains available. No app/image-readiness fix is established.')
(OUT/'public-recovery.json').write_text(json.dumps(public,indent=2)+'\n')
print(json.dumps({'completed':True,'recovered_case_receipts':4,'original_failures_retained':True,'checks':len(journal['checks'])}),flush=True)
