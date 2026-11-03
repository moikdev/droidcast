from pathlib import Path
import hashlib,json,subprocess,os,re
B=Path('artifacts/markdown-detail');STATE=B/'audit-after-controls-v1-state.json';assert not STATE.exists()
build=json.loads((B/'audit-repair-build-v2-state.json').read_text());assert build['completed'] and build['sources_unchanged']
before=json.loads((B/'audit-before-controls-v1-state.json').read_text());assert before['completed']
assert before['test_apk_sha256']==build['common_test_apk_sha256']
sources=build['source_hashes'];sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest();assert all(sha(p)==h for p,h in sources.items())
pair=Path(build['candidate_directory']);assert sha(pair/'app.apk')==build['app_sha256'] and sha(pair/'common-test.apk')==build['common_test_apk_sha256']
D='dev.tinycast.android.ExtensionDetailDeviceTest'
groups=[('parser',['compactPaddedTableBecomesUnavailableAndTheNextDocumentStillWorks']),
 ('object-tag',['unchangedObjectTagCallbackSurvivesEquivalentNativePublication','objectTagKeepsActualKeyboardFocusAcrossUnrelatedReactPublication']),
 ('glyph',['longMetadataUrlsTagsAndImageInformationRemainReachableByTouch'])]
archive=Path('<checkout>/artifacts/extension-catalog/representative-001-bundles-v1/archives/223987eaca2c894e37ada9e087669f260cda79d6a3b88ff11463f429cc952e6a.zip')
s=dict(completed=False,pid=os.getpid(),production_source_commit=build['production_source_commit'],common_test_source_commit=build['common_test_source_commit'],
 app_sha256=build['app_sha256'],test_apk_sha256=build['common_test_apk_sha256'],source_hashes=sources,paired_before_record='audit-before-controls-v1-state.json',cohorts=[],
 scope='Repaired runtime with exactly the same test APK as the12 intended old-app failures. Current-runtime full-harness matrix is separate.')
def save():STATE.write_text(json.dumps(s,indent=2)+'\n')
save()
try:
 for group,methods in groups:
  for layout,font in [('tablet',1),('phone',2),('landscape',2)]:
   assert all(sha(p)==h for p,h in sources.items());out=B/f'audit-after-v1-{group}-{layout}{font*100}';assert not out.exists()
   command=['python3','scripts/test-reviewed-text-input.py','--serial','emulator-5580','--avd','Tinycast_Tablet_API_36','--layout',layout,'--font-scale',str(font),'--output',str(out),'--apk',str(pair/'app.apk'),'--test-apk',str(pair/'common-test.apk'),'--json-archive',str(archive),'--classes',','.join(D+'#'+m for m in methods),'--expected',str(len(methods))]
   c=dict(group=group,layout=layout,font_scale=font,expected=len(methods),output=str(out),completed=False,command=command);s['cohorts'].append(c);save()
   with (B/(out.name+'-host.log')).open('x') as log:code=subprocess.run(command,env=dict(os.environ,ANDROID_HOME='<android-sdk>'),stdout=log,stderr=subprocess.STDOUT).returncode
   c['exit_code']=code;save();assert (out/'record.json').exists(),'Inspect preflight before repeating mutation'
   h=json.loads((out/'record.json').read_text());log=(out/'tests.log').read_text();records=[json.loads(p.read_text()) for p in (out/'captures').glob('*-record.json')]
   c.update(junit_passes=h['passes'],cleanup_checks=len(h['cleanup']),cleanup_all_passed=bool(h['cleanup']) and all(x['passed'] for x in h['cleanup'].values()),case_receipts=len(records));save()
   assert code==0 and h['completed'] and h['installed_apks_match'] and h['passes']==len(methods) and c['cleanup_all_passed']
   assert not re.findall(r'^INSTRUMENTATION_STATUS_CODE: -(?:2|3|4)$',log,re.M)
   assert len(records)==len(methods) and all(x['passed'] and x['owned_data_removed'] and x['preexisting_installations_and_data_preserved'] and x['installed_build_commit']==build['production_source_commit'] for x in records)
   if group=='glyph':
    probes=records[0]['native_text_edge_glyph_pixels'];assert probes and all(p['expected_core_pixels']>0 and p['missing_core_pixels']==0 for p in probes)
    assert {p['phase'] for p in probes}=={'metadata-title','metadata-url'}
   c['completed']=True;save();print(json.dumps({k:c[k] for k in ['group','layout','font_scale','junit_passes','cleanup_checks']}),flush=True)
 s.update(completed=True,positive_executions=sum(c['junit_passes'] for c in s['cohorts']),cleanup_checks=sum(c['cleanup_checks'] for c in s['cohorts']));assert s['positive_executions']==12;save()
except BaseException as failure:s['failure_type']=type(failure).__name__;save();raise
