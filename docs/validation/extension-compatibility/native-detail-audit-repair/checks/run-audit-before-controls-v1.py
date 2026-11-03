from pathlib import Path
import hashlib,json,os,subprocess,re
B=Path('artifacts/markdown-detail');STATE=B/'audit-before-controls-v1-state.json';assert not STATE.exists()
build=json.loads((B/'audit-controls-build-v4-state.json').read_text());assert build['completed'] and build['sources_unchanged']
sources=build['source_hashes'];sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest();assert all(sha(p)==h for p,h in sources.items())
pair=Path(build['candidate_directory']);assert sha(pair/'app.apk')==build['app_sha256'] and sha(pair/'test.apk')==build['test_apk_sha256']
archive=Path('<checkout>/artifacts/extension-catalog/representative-001-bundles-v1/archives/223987eaca2c894e37ada9e087669f260cda79d6a3b88ff11463f429cc952e6a.zip')
D='dev.tinycast.android.ExtensionDetailDeviceTest'
groups=[('parser',['compactPaddedTableBecomesUnavailableAndTheNextDocumentStillWorks']),
 ('object-tag',['unchangedObjectTagCallbackSurvivesEquivalentNativePublication','objectTagKeepsActualKeyboardFocusAcrossUnrelatedReactPublication']),
 ('glyph',['longMetadataUrlsTagsAndImageInformationRemainReachableByTouch'])]
s=dict(completed=False,pid=os.getpid(),production_source_commit=build['production_source_commit'],common_test_source_commit=build['common_test_source_commit'],
 app_sha256=build['app_sha256'],test_apk_sha256=build['test_apk_sha256'],source_hashes=sources,cohorts=[],
 scope='Old4bb10aa runtime with identical strengthened harness for the three audit findings. Expected failures are not passing JUnit or repaired behavior.')
def save():STATE.write_text(json.dumps(s,indent=2)+'\n')
save()
try:
 for group,methods in groups:
  for layout,font in [('tablet',1),('phone',2),('landscape',2)]:
   assert all(sha(p)==h for p,h in sources.items());out=B/f'audit-before-v1-{group}-{layout}{font*100}';assert not out.exists()
   command=['python3','scripts/test-reviewed-text-input.py','--serial','emulator-5580','--avd','Tinycast_Tablet_API_36','--layout',layout,'--font-scale',str(font),'--output',str(out),'--apk',str(pair/'app.apk'),'--test-apk',str(pair/'test.apk'),'--json-archive',str(archive),'--classes',','.join(D+'#'+m for m in methods),'--expected',str(len(methods))]
   c=dict(group=group,layout=layout,font_scale=font,output=str(out),completed=False,expected_negative_executions=len(methods),command=command);s['cohorts'].append(c);save()
   with (B/(out.name+'-host.log')).open('x') as log:code=subprocess.run(command,env=dict(os.environ,ANDROID_HOME='<android-sdk>'),stdout=log,stderr=subprocess.STDOUT).returncode
   c['exit_code']=code;save();assert (out/'record.json').exists(),'Preflight must be inspected before repeating mutation'
   h=json.loads((out/'record.json').read_text());log=(out/'tests.log').read_text();records=[json.loads(p.read_text()) for p in (out/'captures').glob('*-record.json')]
   c.update(junit_passes=h['passes'],case_receipts=len(records),cleanup_checks=len(h['cleanup']),cleanup_all_passed=bool(h['cleanup']) and all(x['passed'] for x in h['cleanup'].values()));save()
   assert code!=0 and not h['completed'] and h['passes']==0 and h['installed_apks_match'] and c['cleanup_all_passed']
   assert not re.findall(r'^INSTRUMENTATION_STATUS_CODE: -(?:3|4)$',log,re.M),'Skips are not intended-negative controls'
   assert len(records)==len(methods) and all(not x.get('passed',False) for x in records)
   if group=='parser':
    assert 'maximum number of cells reached' in log and records[0]['contract_assertions_started'] and records[0]['table_limit_source_units']==8008
   elif group=='object-tag':
    assert 'Equivalent object-valued visuals must not invalidate the visible callback' in log
    assert 'An unchanged object-valued tag keeps its composed control' in log
    assert all(x['object_tag_binding_before']==x['object_tag_binding_after'] for x in records)
   else:
    assert 'Visible metadata link glyphs must survive ancestor clipping' in log
    probes=records[0]['native_text_edge_glyph_pixels']
    assert any(p['phase']=='metadata-title' and p['expected_core_pixels']>0 and p['missing_core_pixels']==0 for p in probes),'Unclipped title calibrates the alpha-mask probe'
    assert any(p['phase']=='metadata-url' and p['missing_core_pixels']>0 for p in probes),'Expected clipping must occur in the link, not calibration'
   c['completed']=True;save();print(json.dumps({k:c[k] for k in ['group','layout','font_scale','expected_negative_executions','junit_passes','cleanup_checks']}),flush=True)
 s.update(completed=True,expected_negative_executions=sum(c['expected_negative_executions'] for c in s['cohorts']),junit_passes=0,cleanup_checks=sum(c['cleanup_checks'] for c in s['cohorts']));assert s['expected_negative_executions']==12;save()
except BaseException as failure:s['failure_type']=type(failure).__name__;save();raise
