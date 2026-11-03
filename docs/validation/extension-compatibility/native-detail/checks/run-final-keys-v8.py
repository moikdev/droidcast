from pathlib import Path, PurePosixPath
import hashlib,json,os,re,subprocess,uuid

B=Path('artifacts/markdown-detail'); STATE=B/'final-native-v8-state.json'
assert not STATE.exists()
build=json.loads((B/'final-native-harness-v7-state.json').read_text())
old=json.loads((B/'final-native-v7-state.json').read_text())
assert not old['completed'] and old['failure_type']=='AssertionError'
retained=[c for c in old['cohorts'] if c['completed']]
assert len(retained)==21 and sum(c['junit_passes'] for c in retained)==150
assert old['cohorts'][-1]['group']=='keys' and not Path(old['cohorts'][-1]['output']).exists()
sources=build['source_hashes'];sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
assert all(sha(p)==h for p,h in sources.items())
pair=Path(build['candidate_directory'])
assert sha(pair/'app.apk')==build['app_sha256'] and sha(pair/'test.apk')==build['test_apk_sha256']
adb=['<android-sdk>/platform-tools/adb','-s','emulator-5580']; app='dev.tinycast.android'
def raw(*args,data=None):return subprocess.check_output(adb+list(args),input=data,stderr=subprocess.STDOUT,timeout=60)
def text(*args):return raw(*args).decode().strip()
def absent(path):
    r=subprocess.run(adb+['shell','run-as',app,'test','-e',path],capture_output=True,timeout=30)
    assert r.returncode in (0,1) and not r.stdout and not r.stderr
    return r.returncode==1
def idle():
    assert text('shell','getprop','ro.boot.qemu.avd_name')=='Tinycast_Tablet_API_36'
    assert text('shell','id').startswith('uid=2000(')
    assert 'ActiveInstrumentation{' not in text('shell','dumpsys','activity','processes')
    assert 'Override' not in text('shell','wm','size')+text('shell','wm','density')
    for path in ['files/reviewed-text-fixture-lease','files/empty-view-fixture-lease','files/compatibility-batch-fixtures','files/reviewed-text-evidence']:assert absent(path)
idle()
nonce=uuid.uuid4().hex; owner='files/markdown-detail-evidence-preservation-'+nonce
backup=B/'private-keyboard-evidence-preservation-v8';assert not backup.exists();backup.mkdir(mode=0o700)
folders=['extension-grid-evidence','readiness-evidence']
originals={};moved=[]
for folder in folders:
    root='files/'+folder;assert not absent(root)
    assert not text('shell','run-as',app,'find',root,'-type','l')
    paths=text('shell','run-as',app,'find',root,'-type','f').splitlines()
    for name in paths:
        path=PurePosixPath(name);assert name.startswith(root+'/') and '..' not in path.parts
        data=raw('exec-out','run-as',app,'cat',name)
        dest=backup/path.relative_to('files');dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(data);dest.chmod(0o600)
        originals[str(path.relative_to('files'))]=hashlib.sha256(data).hexdigest()
assert len(originals)==136
(backup/'original-file-hashes.json').write_text(json.dumps(originals,indent=2)+'\n')
s=dict(completed=False,pid=os.getpid(),production_source_commit=build['production_source_commit'],test_source_commit=build['test_source_commit'],
 source_hashes=sources,app_sha256=build['app_sha256'],test_apk_sha256=build['test_apk_sha256'],cohorts=retained.copy(),
 retained_partial_pipeline='final-native-v7-state.json',retained_completed_cohorts=21,retained_native_executions=150,fresh_native_executions=0,
 preservation_checks={'original_inventory_copied_and_verified':{'passed':True}},preserved_evidence_files=136,
 scope='Same immutable app/test APKs:150 completed v7 executions retained; twelve shared-key regressions executed separately after preserving136 pre-existing evidence files. The v7 no-mutation preflight failure remains incomplete and uncredited.')
private=dict(owner=owner,owner_nonce=nonce,moved=moved,completed=False)
def save():
    STATE.write_text(json.dumps(s,indent=2)+'\n')
    (backup/'recovery-owner.json').write_text(json.dumps(private,indent=2)+'\n')
save();assert absent(owner)
raw('shell','run-as',app,'mkdir',owner)
raw('shell','run-as',app,'tee',owner+'/owner.json',data=(json.dumps({'nonce':nonce,'folders':folders})+'\n').encode())
error=None
try:
    for folder in folders:
        raw('shell','run-as',app,'mv','files/'+folder,owner+'/'+folder);moved.append(folder);save()
    assert all(absent('files/'+f) for f in folders)
    s['preservation_checks']['preserved_directories_absent_during_runs']={'passed':True};save()
    classes=old['cohorts'][-1]['command'][old['cohorts'][-1]['command'].index('--classes')+1]
    archive=old['cohorts'][-1]['command'][old['cohorts'][-1]['command'].index('--json-archive')+1]
    for layout,font in [('tablet',1),('phone',2),('landscape',2)]:
        idle();assert all(sha(p)==h for p,h in sources.items())
        out=B/f'final-native-v8-keys-{layout}{font*100}';assert not out.exists()
        command=['python3','scripts/test-reviewed-text-input.py','--serial','emulator-5580','--avd','Tinycast_Tablet_API_36','--layout',layout,'--font-scale',str(font),'--output',str(out),'--apk',str(pair/'app.apk'),'--test-apk',str(pair/'test.apk'),'--json-archive',archive,'--classes',classes,'--expected','4']
        c=dict(group='keys',layout=layout,font_scale=font,expected=4,output=str(out),completed=False,command=command);s['cohorts'].append(c);save()
        with (B/(out.name+'-host.log')).open('x') as log:code=subprocess.run(command,env=dict(os.environ,ANDROID_HOME='<android-sdk>'),stdout=log,stderr=subprocess.STDOUT).returncode
        c['exit_code']=code;save();assert (out/'record.json').exists()
        host=json.loads((out/'record.json').read_text());cleanup=host['cleanup'];log=(out/'tests.log').read_text()
        c.update(junit_passes=host['passes'],cleanup_checks=len(cleanup),cleanup_all_passed=bool(cleanup) and all(x['passed'] for x in cleanup.values()),case_receipts=len(list((out/'captures').glob('*-record.json'))));save()
        assert code==0 and host['completed'] and host['installed_apks_match'] and host['passes']==4 and c['cleanup_all_passed']
        assert not re.findall(r'^INSTRUMENTATION_STATUS_CODE: -(?:2|3|4)$',log,re.M)
        c['completed']=True;s['fresh_native_executions']+=4;save();print(json.dumps({k:c[k] for k in ['group','layout','junit_passes','cleanup_checks']}),flush=True)
except BaseException as failure:
    error=failure;s['failure_type']=type(failure).__name__;save()
finally:
    try:
        idle();assert all(absent('files/'+f) for f in folders)
        s['preservation_checks']['new_evidence_cleaned']={'passed':True};save()
        current=json.loads(raw('exec-out','run-as',app,'cat',owner+'/owner.json'))
        assert current=={'nonce':nonce,'folders':folders}
        for folder in list(moved):
            raw('shell','run-as',app,'mv',owner+'/'+folder,'files/'+folder);moved.remove(folder);save()
        restored={}
        for folder in folders:
            for name in text('shell','run-as',app,'find','files/'+folder,'-type','f').splitlines():
                rel=str(PurePosixPath(name).relative_to('files'))
                restored[rel]=hashlib.sha256(raw('exec-out','run-as',app,'cat',name)).hexdigest()
        assert restored==originals
        s['preservation_checks']['restored_original_bytes']={'passed':True};save()
        assert json.loads(raw('exec-out','run-as',app,'cat',owner+'/owner.json'))=={'nonce':nonce,'folders':folders}
        raw('shell','run-as',app,'rm',owner+'/owner.json');raw('shell','run-as',app,'rmdir',owner)
        assert absent(owner);s['preservation_checks']['release_preservation_owner']={'passed':True};private['completed']=True;save()
    except BaseException as failure:
        s['preservation_failure_type']=type(failure).__name__;save();raise
if error is not None:raise error
assert all(c['completed'] for c in s['cohorts']) and len(s['cohorts'])==24
assert all(x['passed'] for x in s['preservation_checks'].values()) and len(s['preservation_checks'])==5
s.update(completed=True,native_executions=sum(c['junit_passes'] for c in s['cohorts']),cleanup_checks=sum(c['cleanup_checks'] for c in s['cohorts']))
assert s['native_executions']==162 and all(sha(p)==h for p,h in sources.items());save()
print(json.dumps({k:s[k] for k in ['completed','native_executions','cleanup_checks','preserved_evidence_files']}),flush=True)
