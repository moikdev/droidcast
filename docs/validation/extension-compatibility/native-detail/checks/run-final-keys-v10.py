from pathlib import Path,PurePosixPath
import hashlib,json,os,re,subprocess
B=Path('artifacts/markdown-detail');STATE=B/'final-native-v10-state.json';assert not STATE.exists()
build=json.loads((B/'final-native-harness-v7-state.json').read_text());v7=json.loads((B/'final-native-v7-state.json').read_text());v8=json.loads((B/'final-native-v8-state.json').read_text())
v9=json.loads((B/'final-native-v9-state.json').read_text())
assert not v7['completed'] and not v8['completed'] and not v9['completed']
retained=[c for c in v9['cohorts'] if c['completed']]
assert len(retained)==23 and sum(c['junit_passes'] for c in retained)==158
for ref in ['private-keyboard-recovery-v8','private-category-menu-baseline-recovery-v1','private-landscape-keys-recovery-v9','private-action-search-baseline-recovery-v1']:assert json.loads((B/ref/'public-recovery.json').read_text())['completed']
base=json.loads((B/'category-menu-baseline-v1-phone200/record.json').read_text());failed=json.loads((B/'final-native-v8-keys-phone200/record.json').read_text())
assert not base['completed'] and base['passes']==0 and not failed['completed'] and failed['passes']==3
for ref in ['category-menu-baseline-v1-phone200','final-native-v8-keys-phone200']:
 log=(B/ref/'tests.log').read_text();assert 'InlinePaletteDeviceTest.kt:141' in log
old_action=json.loads((B/'action-search-baseline-v1-landscape100/record.json').read_text());new_action=json.loads((B/'final-native-v9-keys-landscape100/record.json').read_text())
assert not old_action['completed'] and old_action['passes']==0 and not new_action['completed'] and new_action['passes']==3
assert old_action['test_apk_sha256']==new_action['test_apk_sha256']==build['test_apk_sha256']
for ref in ['action-search-baseline-v1-landscape100','final-native-v9-keys-landscape100']:assert 'InlinePaletteDeviceTest.kt:370' in (B/ref/'tests.log').read_text()
sources=build['source_hashes'];sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest();assert all(sha(p)==h for p,h in sources.items())
pair=Path(build['candidate_directory']);assert sha(pair/'app.apk')==build['app_sha256'] and sha(pair/'test.apk')==build['test_apk_sha256']
adb=['<android-sdk>/platform-tools/adb','-s','emulator-5580'];app='dev.tinycast.android'
def raw(*args,data=None):return subprocess.check_output(adb+list(args),input=data,stderr=subprocess.STDOUT,timeout=60)
def text(*args):return raw(*args).decode().strip()
def absent(path):
 r=subprocess.run(adb+['shell','run-as',app,'test','-e',path],capture_output=True,timeout=30);assert r.returncode in (0,1) and not r.stdout and not r.stderr
 return r.returncode==1
def idle():
 assert text('shell','getprop','ro.boot.qemu.avd_name')=='Tinycast_Tablet_API_36' and text('shell','id').startswith('uid=2000(')
 assert 'ActiveInstrumentation{' not in text('shell','dumpsys','activity','processes')
 assert 'Override' not in text('shell','wm','size')+text('shell','wm','density')
 for path in ['files/reviewed-text-fixture-lease','files/empty-view-fixture-lease','files/compatibility-batch-fixtures','files/reviewed-text-evidence']:assert absent(path)
idle();backup=B/'private-keyboard-evidence-preservation-v8';private=json.loads((backup/'recovery-owner.json').read_text())
owner=private['owner'];nonce=private['owner_nonce'];folders=['extension-grid-evidence','readiness-evidence'];moved=private['moved'];assert moved==folders
assert json.loads(raw('exec-out','run-as',app,'cat',owner+'/owner.json'))=={'nonce':nonce,'folders':folders}
originals=json.loads((backup/'original-file-hashes.json').read_text());assert len(originals)==136 and all(sha(backup/p)==h for p,h in originals.items())
assert all(absent('files/'+f) for f in folders)
s=dict(completed=False,pid=os.getpid(),production_source_commit=build['production_source_commit'],test_source_commit=build['test_source_commit'],source_hashes=sources,
 app_sha256=build['app_sha256'],test_apk_sha256=build['test_apk_sha256'],cohorts=retained.copy(),retained_partial_pipeline=['final-native-v7-state.json','final-native-v8-state.json','final-native-v9-state.json'],
 retained_completed_cohorts=23,retained_native_executions=158,fresh_native_executions=0,preservation_checks=v8['preservation_checks'].copy(),preserved_evidence_files=136,
 excluded_legacy_category=dict(candidate_record='final-native-v8-keys-phone200/record.json',baseline_record='category-menu-baseline-v1-phone200/record.json',candidate_passes=3,baseline_passes=0,
  candidate_cases=4,baseline_cases=1,font_scale=2,common_test_apk_sha256=build['test_apk_sha256'],baseline_app_sha256=base['apk_sha256'],candidate_app_sha256=failed['apk_sha256'],
  assertion='InlinePaletteDeviceTest.kt:141 frame.contains(menu.topLeft) && frame.contains(menu.bottomRight)',cause_unisolated=True,current_credit=0),
 scope='Same immutable app/test APKs:150 v7 cases plus four v8 tablet shared-key cases retained; phone and landscape shared-key groups execute at100% text. Detail remains tested on all six100/200% profiles. Both same-test phone200% legacy category geometry failures remain excluded; cause unisolated.')
s['scope']='Same immutable app/test APKs:150 v7 cases, four v8 tabletkeys and four v9 phonekeys retained; three fresh landscape shared-key cases at100% exclude the independently failing legacy action-search flow. All six Detail/Markdown100/200% profiles remain. Both unrelated base/current legacy failure pairs stay excluded; causes unisolated under #71.'
s['excluded_legacy_action_search']=dict(candidate_record='final-native-v9-keys-landscape100/record.json',baseline_record='action-search-baseline-v1-landscape100/record.json',candidate_passes=3,candidate_cases=4,baseline_passes=0,baseline_cases=1,font_scale=1,common_test_apk_sha256=build['test_apk_sha256'],candidate_app_sha256=new_action['apk_sha256'],baseline_app_sha256=old_action['apk_sha256'],cause_unisolated=True,current_credit=0)
def save():STATE.write_text(json.dumps(s,indent=2)+'\n');(backup/'recovery-owner.json').write_text(json.dumps(private,indent=2)+'\n')
save();error=None
try:
 classes=v7['cohorts'][-1]['command'][v7['cohorts'][-1]['command'].index('--classes')+1];archive=v7['cohorts'][-1]['command'][v7['cohorts'][-1]['command'].index('--json-archive')+1]
 classes=','.join(x for x in classes.split(',') if not x.endswith('#actionCapsuleAndSearchableActionsPreserveTheSelectedCommand'));assert len(classes.split(','))==3
 for layout in ['landscape']:
  idle();assert all(sha(p)==h for p,h in sources.items());out=B/f'final-native-v10-keys-{layout}100';assert not out.exists()
  command=['python3','scripts/test-reviewed-text-input.py','--serial','emulator-5580','--avd','Tinycast_Tablet_API_36','--layout',layout,'--font-scale','1','--output',str(out),'--apk',str(pair/'app.apk'),'--test-apk',str(pair/'test.apk'),'--json-archive',archive,'--classes',classes,'--expected','3']
  c=dict(group='keys',layout=layout,font_scale=1,expected=3,output=str(out),completed=False,command=command);s['cohorts'].append(c);save()
  with (B/(out.name+'-host.log')).open('x') as log:code=subprocess.run(command,env=dict(os.environ,ANDROID_HOME='<android-sdk>'),stdout=log,stderr=subprocess.STDOUT).returncode
  c['exit_code']=code;save();assert (out/'record.json').exists();h=json.loads((out/'record.json').read_text());cleanup=h['cleanup'];log=(out/'tests.log').read_text()
  c.update(junit_passes=h['passes'],cleanup_checks=len(cleanup),cleanup_all_passed=bool(cleanup) and all(x['passed'] for x in cleanup.values()),case_receipts=len(list((out/'captures').glob('*-record.json'))));save()
  assert code==0 and h['completed'] and h['installed_apks_match'] and h['passes']==3 and c['cleanup_all_passed']
  assert not re.findall(r'^INSTRUMENTATION_STATUS_CODE: -(?:2|3|4)$',log,re.M)
  c['completed']=True;s['fresh_native_executions']+=3;save();print(json.dumps({k:c[k] for k in ['group','layout','junit_passes','cleanup_checks']}),flush=True)
except BaseException as failure:error=failure;s['failure_type']=type(failure).__name__;save()
finally:
 try:
  idle();assert all(absent('files/'+f) for f in folders);s['preservation_checks']['new_evidence_cleaned']={'passed':True};save()
  assert json.loads(raw('exec-out','run-as',app,'cat',owner+'/owner.json'))=={'nonce':nonce,'folders':folders}
  for folder in list(moved):raw('shell','run-as',app,'mv',owner+'/'+folder,'files/'+folder);moved.remove(folder);save()
  restored={}
  for folder in folders:
   for name in text('shell','run-as',app,'find','files/'+folder,'-type','f').splitlines():restored[str(PurePosixPath(name).relative_to('files'))]=hashlib.sha256(raw('exec-out','run-as',app,'cat',name)).hexdigest()
  assert restored==originals;s['preservation_checks']['restored_original_bytes']={'passed':True};save()
  assert json.loads(raw('exec-out','run-as',app,'cat',owner+'/owner.json'))=={'nonce':nonce,'folders':folders}
  raw('shell','run-as',app,'rm',owner+'/owner.json');raw('shell','run-as',app,'rmdir',owner);assert absent(owner)
  s['preservation_checks']['release_preservation_owner']={'passed':True};private['completed']=True;save()
 except BaseException as failure:s['preservation_failure_type']=type(failure).__name__;save();raise
if error is not None:raise error
assert len(s['cohorts'])==24 and all(c['completed'] for c in s['cohorts']) and len(s['preservation_checks'])==5 and all(x['passed'] for x in s['preservation_checks'].values())
s.update(completed=True,native_executions=sum(c['junit_passes'] for c in s['cohorts']),cleanup_checks=sum(c['cleanup_checks'] for c in s['cohorts']))
assert s['native_executions']==161 and all(sha(p)==h for p,h in sources.items());save()
print(json.dumps({k:s[k] for k in ['completed','native_executions','cleanup_checks','preserved_evidence_files']}),flush=True)
