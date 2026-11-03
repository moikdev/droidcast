from pathlib import Path,PurePosixPath
import hashlib,json,os,re,subprocess,uuid
B=Path('artifacts/markdown-detail');STATE=B/'audit-final-native-v1-state.json';assert not STATE.exists()
build=json.loads((B/'audit-repair-build-v2-state.json').read_text());assert build['completed'] and build['sources_unchanged']
assert json.loads((B/'audit-after-controls-v1-state.json').read_text())['completed']
sources=build['source_hashes'];sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest();assert all(sha(p)==h for p,h in sources.items())
pair=Path(build['candidate_directory']);assert sha(pair/'app.apk')==build['app_sha256'] and sha(pair/'test.apk')==build['test_apk_sha256']
archive=Path('<checkout>/artifacts/extension-catalog/representative-001-bundles-v1/archives/223987eaca2c894e37ada9e087669f260cda79d6a3b88ff11463f429cc952e6a.zip')
mood=B/'frozen-workflow-inputs-v1/mood.zip';colors=B/'html-colors-bundle-v1'/json.loads((B/'html-colors-bundle-v1/snapshot.json').read_text())['extensions'][0]['archive_cache_path']
assert sha(archive)=='fc420406a59251daee0c9af32e216026724631480b3a4e2f5d65d0c507b09a58'
assert sha(mood)=='46bd1d6f6598b3ecab43d6eac9778a8b4aba7e3d609859f90feceaf391b9b221'
assert sha(colors)=='8a346fd2bd4ed01ceeacdeb71462b458014faa539a1f2b563638983843bdf0fb'
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
idle();backup=B/'private-audit-final-evidence-preservation-v1';assert not backup.exists();backup.mkdir(mode=0o700)
folders=['extension-grid-evidence','readiness-evidence'];originals={};moved=[]
for folder in folders:
 root='files/'+folder;assert not absent(root) and not text('shell','run-as',app,'find',root,'-type','l')
 for name in text('shell','run-as',app,'find',root,'-type','f').splitlines():
  path=PurePosixPath(name);assert name.startswith(root+'/') and '..' not in path.parts
  data=raw('exec-out','run-as',app,'cat',name);dest=backup/path.relative_to('files');dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(data);dest.chmod(0o600)
  originals[str(path.relative_to('files'))]=hashlib.sha256(data).hexdigest()
assert len(originals)==136;(backup/'original-file-hashes.json').write_text(json.dumps(originals,indent=2)+'\n')
nonce=uuid.uuid4().hex;owner='files/markdown-detail-final-evidence-'+nonce
D='dev.tinycast.android.ExtensionDetailDeviceTest';I='dev.tinycast.android.InlinePaletteDeviceTest';R='dev.tinycast.android.ReadinessRegressionDeviceTest'
critical=','.join(D+'#'+m for m in ['listTouchDetailPreservesQuerySelectionExactCopyAndCaller','longMetadataUrlsTagsAndImageInformationRemainReachableByTouch'])
keys=[R+'#rapidExtensionListDownThenEnterRunsTheCurrentAction',I+'#categoryMenuIsInsidePaletteAndKeyboardReturnsFocus',I+'#actionSearchImeActivatesOnlyTheCurrentEnabledResultOutsideComposition',I+'#actionCapsuleAndSearchableActionsPreserveTheSelectedCommand']
profiles=[('tablet',1),('tablet',2),('phone',1),('phone',2),('landscape',1),('landscape',2)];critical_profiles=[('tablet',1),('phone',2),('landscape',2)]
groups=[('critical',critical,2,critical_profiles,[]),('detail',D,22,profiles,['--html-colors-archive',str(colors)]),
 ('markdown','dev.tinycast.android.ExtensionMarkdownDeviceTest',4,profiles,[]),
 ('json','dev.tinycast.android.ReviewedTextDeviceTest#selectedJsonRunsOnlyAfterReviewThenCopiesAndReturns',1,critical_profiles,[]),
 ('mood','dev.tinycast.android.ExtensionFormDeviceTest#frozenMoodStoresExactTypedValuesAndReloadsThemInSearch',1,critical_profiles,['--mood-archive',str(mood)]),
 ('keys',','.join(keys),4,[('tablet',1),('phone',1)],[]),('keys',','.join(keys[:3]),3,[('landscape',1)],[])]
s=dict(completed=False,pid=os.getpid(),production_source_commit=build['production_source_commit'],test_source_commit=build['test_source_commit'],source_hashes=sources,
 app_sha256=build['app_sha256'],test_apk_sha256=build['test_apk_sha256'],cohorts=[],preserved_evidence_files=136,
 preservation_checks={'original_inventory_copied_and_verified':{'passed':True}},
 scope='Fresh current-runtime app/test179-case matrix; all six Detail/Markdown100/200% profiles, frozen workflows and eleven100% shared-key checks. The known old/current category phone200% and empty-state landscape100% failed protocols remain separately bound to their historical binaries under #91.')
private=dict(owner=owner,owner_nonce=nonce,moved=moved,completed=False)
def save():STATE.write_text(json.dumps(s,indent=2)+'\n');(backup/'recovery-owner.json').write_text(json.dumps(private,indent=2)+'\n')
save();assert absent(owner);raw('shell','run-as',app,'mkdir',owner);raw('shell','run-as',app,'tee',owner+'/owner.json',data=(json.dumps({'nonce':nonce,'folders':folders})+'\n').encode())
error=None
try:
 for folder in folders:raw('shell','run-as',app,'mv','files/'+folder,owner+'/'+folder);moved.append(folder);save()
 assert all(absent('files/'+f) for f in folders);s['preservation_checks']['preserved_directories_absent_during_runs']={'passed':True};save()
 for group,classes,expected,layouts,extra in groups:
  for layout,font in layouts:
   if (B/'audit-final-native-v1-stop-after-cohort').exists():s['stopped_at_cohort_boundary']=True;save();raise SystemExit(3)
   idle();assert all(absent('files/'+f) for f in folders) and all(sha(p)==h for p,h in sources.items())
   out=B/f'audit-final-native-v1-{group}-{layout}{font*100}';assert not out.exists()
   command=['python3','scripts/test-reviewed-text-input.py','--serial','emulator-5580','--avd','Tinycast_Tablet_API_36','--layout',layout,'--font-scale',str(font),'--output',str(out),'--apk',str(pair/'app.apk'),'--test-apk',str(pair/'test.apk'),'--json-archive',str(archive),'--classes',classes,'--expected',str(expected),*extra]
   c=dict(group=group,layout=layout,font_scale=font,expected=expected,output=str(out),completed=False,command=command);s['cohorts'].append(c);save()
   with (B/(out.name+'-host.log')).open('x') as log:code=subprocess.run(command,env=dict(os.environ,ANDROID_HOME='<android-sdk>'),stdout=log,stderr=subprocess.STDOUT).returncode
   c['exit_code']=code;save();assert (out/'record.json').exists(),'Inspect preflight before repeating mutation'
   h=json.loads((out/'record.json').read_text());log=(out/'tests.log').read_text();records=[json.loads(p.read_text()) for p in (out/'captures').glob('*-record.json')]
   c.update(junit_passes=h['passes'],cleanup_checks=len(h['cleanup']),cleanup_all_passed=bool(h['cleanup']) and all(x['passed'] for x in h['cleanup'].values()),case_receipts=len(records));save()
   assert code==0 and h['completed'] and h['installed_apks_match'] and h['passes']==expected and c['cleanup_all_passed']
   assert not re.findall(r'^INSTRUMENTATION_STATUS_CODE: -(?:2|3|4)$',log,re.M)
   if group in ['critical','detail','markdown']:
    assert len(records)==expected and all(x['passed'] and x['owned_data_removed'] and x['preexisting_installations_and_data_preserved'] and x['installed_build_commit']==build['production_source_commit'] for x in records)
   c['completed']=True;save();print(json.dumps({k:c[k] for k in ['group','layout','font_scale','junit_passes','cleanup_checks']}),flush=True)
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
assert s['native_executions']==179 and all(sha(p)==h for p,h in sources.items());save()
print(json.dumps({k:s[k] for k in ['completed','native_executions','cleanup_checks','preserved_evidence_files']}),flush=True)
