from pathlib import Path
import hashlib, json, re, struct, subprocess, tempfile, xml.etree.ElementTree as ET, zipfile

B = Path('artifacts/menu-readability')
V = Path('docs/validation/extension-compatibility/compact-menus')
P = Path('docs/screenshots/extension-compatibility/compact-menus')
REPORT = Path('docs/COMPACT-MENU-READABILITY.md')
sha = lambda data: hashlib.sha256(data).hexdigest()
read = lambda path: json.loads(Path(path).read_text())
def require(value, message):
    if not value: raise RuntimeError(message)
require(not V.exists() and not P.exists(), 'Inspect existing publication; never overwrite it blindly')
build = read(B/'build-candidate-v6/state.json')
harness = read(B/'build-harness-v18/state.json')
matrix = read(B/'matrix-current-v18/state.json')
controls = read(B/'final-common-controls-v19/state.json')
require(build['completed'] and harness['completed'] and matrix['completed'] and controls['completed'], 'Incomplete final gates')
require(matrix['native_executions'] == 63 and matrix['cleanup_checks'] == 305 and matrix['retained_extension_menu_executions'] == 18 and matrix['retained_extension_menu_cleanup_checks'] == 116 and matrix['same_boot'] and matrix['durable_data_unchanged'], 'Matrix/preservation differs')
require(all(sha(Path(path).read_bytes()) == digest for path,digest in harness['source_hashes'].items()), 'Current sources changed')
require(harness['changed_sources_from_runtime'] == ['app/src/androidTest/java/dev/tinycast/android/InlinePaletteDeviceTest.kt'], 'Runtime/test split differs')
for name,key in [('app.apk','app_sha256'),('test.apk','test_apk_sha256'),('benchmark.apk','benchmark_app_sha256'),('benchmark-test.apk','benchmark_test_apk_sha256')]:
    require(sha((B/'build-harness-v18/candidate'/name).read_bytes()) == harness[key], 'Frozen APK changed')

HOST = ['completed','serial','avd','layout','font_scale','classes','expected','runner_sha256','archive_sha256',
    'cleanup_helper_sha256','clipboard_helper_sha256','document_helper_sha256','apk_sha256','test_apk_sha256',
    'installed_apks_match','instrumentation_exit_code','passes','tests_completed','shared_regression_cohort','cleanup']
CASE = ['launcher_keyboard_setup','scope','passed','font_scale','width_dp','height_dp','installed_build_commit','observations','pointer_taps','pointer_scrolls',
    'fixture_mode','fixture_command_sha256','fixture_manifest_sha256','owned_data_removed','preexisting_installations_and_data_preserved',
    'submit_route','submit_native_action_type','initial_submit_count','submit_menu_query','observed_submit_count',
    'observed_non_submit_actions','observed_dispatch_count','result_root_type','ordinary_action_preserved','settled_ime_geometry',
    'blocked-submit-option_line_probe','submit-long-0_line_probe','submit-long-1_line_probe','submit-long-2_line_probe']
PROBE = ['completed','candidate','case','source_commit','production_source_commit','test_source_commit','app_sha256','test_apk_sha256',
    'junit_passes','failed_cleanup_checks','restoration_pending','restoration_failure_type','original_evidence_restored_and_verified','preservation_owner_released','scope']
BUILD = ['completed','source_commit','test_source_commit','production_source_commit','source_hashes','sources_unchanged',
    'app_sha256','test_apk_sha256','benchmark_app_sha256','benchmark_test_apk_sha256','changed_sources_from_runtime',
    'debug_test_zip_payload_changes','benchmark_test_zip_payload_changes','jvm','jvm_task_reused','lint','commands','scope']
bindings=[]; capture_hashes={}; raw_sets=[]
prefixes=[(str(Path.cwd()),'<checkout>'),('<android-sdk>','<android-sdk>'),
    ('<jdk>','<jdk>'),
    (tempfile.gettempdir().rstrip('/'),'<temporary-directory>'),(str(Path.home()),'<user-home>')]
prefixes.sort(key=lambda x:len(x[0]),reverse=True)
def safe_text(data, xml=False):
    value=data.decode()
    for prefix,label in prefixes:
        value=value.replace(prefix,label.replace('<','&lt;').replace('>','&gt;') if xml else label)
    if xml:value=re.sub(r'hostname="[^"]*"','hostname="redacted-host"',value)
    require(not re.search(r'/(?:Users|private/var|var/folders)/.|-{5}BEGIN [A-Z ]*PRIVATE KEY-{5}|X-Amz-(?:Signature|Credential)=.',value,re.I),'Private publication marker')
    return value.encode()
def write(target,data):
    target.parent.mkdir(parents=True,exist_ok=True)
    with target.open('xb') as stream:stream.write(data)
def publish(source,target,role,fields=None,xml=False,raw=False):
    original=source.read_bytes()
    if fields is not None:
        value=json.loads(original); converted=(json.dumps({k:value[k] for k in fields if k in value},indent=2,ensure_ascii=False)+'\n').encode()
        kind='allowlisted JSON fields'
    elif raw: converted=original;kind='raw bytes'
    else: converted=safe_text(original,xml);kind='XML-safe prefix and hostname-attribute replacements' if xml else 'plain-text prefix replacement'
    if xml:ET.fromstring(converted)
    write(target,converted)
    item=dict(source_id=role,original_filename=source.name,original_sha256=sha(original),published_path=str(target),transformation=kind)
    if fields is not None:item['fields']=fields
    bindings.append(item)

require((B/'final-profile-dimensions-v18.json').is_file(), 'Record final observed profile dimensions first')
V.mkdir(parents=True);P.mkdir(parents=True)
def cohort(folder,role,current=False,expected_test=None):
    root=B/folder; host=read(root/'cohort/record.json'); outer=read(root/'probe.json')
    require(outer.get('original_evidence_restored_and_verified') and outer.get('preservation_owner_released'), 'Unrestored original evidence')
    if current:
        require(host['completed'] and host['installed_apks_match'] and all(v['passed'] for v in host['cleanup'].values()), 'Failed current native cohort')
        require(host['apk_sha256']==harness['app_sha256'] and host['test_apk_sha256']==(expected_test or harness['test_apk_sha256']), 'Current binary identity differs')
    publish(root/'cohort/record.json',V/role/'host.json',folder,HOST)
    publish(root/'probe.json',V/role/'outer.json',folder,PROBE)
    publish(root/'cohort/tests.log',V/role/'tests.log',folder)
    recovery=root/'recovery/public-recovery.json'
    if recovery.exists():
        publish(recovery,V/role/'separate-recovery.json',folder,[
            'completed','scope','original_failure_retained','legacy_test_teardown_verified','owned_fixture_ids_empty',
            'copied_test_evidence_retained_and_device_copies_absent','no_audit_list_cache_or_support_remains',
            'preferences_and_settings_match_private_originals','clipboard_restored_and_verified_independently','application_lease_released_last'])
    for name in ['captures','extension-grid-evidence','readiness-evidence']:
        for source in sorted((root/'cohort'/name).glob('*')):
            if source.is_file():capture_hashes[folder+'/'+name+'/'+source.name]=sha(source.read_bytes())
        for source in sorted((root/'cohort'/name).glob('*-record.json')):
            publish(source,V/role/'cases'/source.name,folder,CASE)
        for source in sorted((root/'cohort'/name).glob('caller-*-proof.json')):
            publish(source,V/role/'caller-proofs'/source.name,folder,['scope','full_target_verified','minimum_touch_dp','pointer_drags','visible_bounds_root','layout_size','ime_visible'])

for entry in matrix['cohorts']:
    require(entry['completed'],'Incomplete matrix row')
    folder=Path(entry['output']).name
    cohort(folder,'current/'+folder.removeprefix('candidate-current-').removesuffix('-current-v18'),True)

retained_form_folders = {Path(c['output']).name for c in matrix['retained_extension_menu_cohorts']}
for entry in matrix['retained_extension_menu_cohorts']:
    folder = Path(entry['output']).name
    cohort(folder,'retained-form/'+folder,True,entry['test_apk_sha256'])

historical=[p.name for p in sorted(B.iterdir()) if p.is_dir() and p.name.startswith(('boundary-','candidate-')) and (p/'cohort/record.json').exists() and p.name not in ({Path(c['output']).name for c in matrix['cohorts']} | retained_form_folders)]
for folder in historical:cohort(folder,'retained/'+folder)
for version in ['v8','v10','v11','v12','v14','v18']:
    publish(B/('matrix-current-'+version)/'state.json',V/'pipelines'/('matrix-'+version+'.json'),'matrix-'+version,
        ['completed','production_source_commit','test_source_commit','app_sha256','test_apk_sha256','source_hashes',
         'native_executions','cleanup_checks','same_boot','durable_data_unchanged','durable_file_count','failure_type',
         'retained_failed_collector_sha256','retained_failed_collector_scope','retained_extension_menu_cohorts','retained_extension_menu_executions','retained_extension_menu_cleanup_checks','scope'])
for name in ['boundary-protocol-identity-v1','category-boundary-diagnosis-v1','action-boundary-current-diagnosis-v1','caller-range-control-diagnosis-v1','caller-diagnostic-protocol-identity-v1','caller-runtime-source-identity-v1','caller-boundary-diagnosis-v1','control-classifier-correction-v1','matrix-v8-accounting-diagnosis-v1']:
    publish(B/(name+'.json'),V/'diagnoses'/(name+'.json'),name,raw=True)
for folder in ['build-boundary-v1','build-candidate-v2','build-candidate-v3','build-candidate-v6','build-harness-v4','build-harness-v5','build-harness-v7','build-harness-v8','build-harness-v9','build-harness-v10','build-harness-v12','build-harness-v14','build-harness-v16','build-harness-v18']:
    publish(B/folder/'state.json',V/'identities'/(folder+'.json'),folder,BUILD)
publish(B/'final-common-controls-v19/state.json',V/'pipelines/common-controls.json','common-controls',
    ['completed','cohorts','scope','common_test_apk_sha256','same_boot','durable_data_unchanged','durable_file_count',
     'final_current_app_installed_and_verified','final_current_app_sha256','final_current_smoke_passes','old_caller_reveal_passes','collector_sha256','collector_source_unchanged','retained_prior_collector_sha256','retained_prior_collector_scope'])
publish(B/'final-common-controls-v18/state.json',V/'pipelines/failed-control-collector-v18.json','failed-control-collector-v18',['completed','common_test_apk_sha256','cohorts','scope'])
for source in sorted((B/'build-candidate-v6/jvm').glob('*.xml')):publish(source,V/'checks/jvm'/source.name,'runtime-build',xml=True)
for source in sorted((B/'build-candidate-v6/lint').glob('*.xml')):publish(source,V/'checks/lint'/source.name,'runtime-build',xml=True)
for folder in ['build-candidate-v2','build-candidate-v3','build-candidate-v6','build-harness-v10','build-harness-v12','build-harness-v14','build-harness-v16','build-harness-v18']:
    for source in sorted((B/folder).glob('build-*.log')):publish(source,V/'checks'/folder/source.name,folder)
publish(B/'primary-sources/foundation-source.json',V/'foundation-source.json','pinned-foundation',raw=True)
publish(B/'final-profile-dimensions-v18.json',V/'profiles.json','current-frame-profile-dimensions',raw=True)
for name in ['run-candidate-probe-v18.py','run-current-matrix-v18.py','run-final-common-controls-v19.py','build-harness-v18.py','publish-v1.py']:
    publish(B/name,V/'collectors'/name,'retained-collector-source')

def frame(label,folder,stem,caption):
    root=B/folder/'cohort'
    choices=[root/name for name in ['captures','extension-grid-evidence','readiness-evidence'] if (root/name/(stem+'.png')).exists()]
    require(len(choices)==1,'Missing/ambiguous selected frame')
    location=choices[0];png=location/(stem+'.png');xml=location/(stem+'.xml');receipt=location/(stem+'-frame.json')
    data=png.read_bytes();require(data[:8]==b'\x89PNG\r\n\x1a\n','Invalid PNG');width,height=struct.unpack('>II',data[16:24])
    xml_data=xml.read_bytes();ET.fromstring(xml_data)
    require(b'\r\n' in xml_data and not re.search(b'(?<!\r)\n',xml_data),'Raw XML bytes changed')
    publish(png,P/(label+'.png'),folder,raw=True);publish(xml,V/'frames'/(label+'.xml'),folder,raw=True)
    publish(receipt,V/'frames'/(label+'.json'),folder,raw=True)
    host=read(root/'record.json')
    raw_sets.append(dict(name=label,caption=caption,source_id=folder,source_stem=stem,width_px=width,height_px=height,
        app_sha256=host['apk_sha256'],test_apk_sha256=host['test_apk_sha256'],png=str(P/(label+'.png'))))

frame('before-category-phone200','boundary-baseline-category-v1','menu-category-legacy','Old runtime; retained cached-frame assertion failure. Current native bounds contain the menu. No production containment repair is claimed.')
frame('before-actions-landscape100','boundary-baseline-actions-v1','menu-actions-no-match-legacy','Old runtime with actual IME; zero-height choices viewport hides the empty message.')
frame('diagnostic-category-phone200','boundary-current-category-v1','menu-category-legacy','Diagnostic runtime reproduces the original cached-frame failure with the same instrumentation.')
frame('diagnostic-actions-landscape100','boundary-current-actions-v1','menu-actions-no-match-legacy','Diagnostic runtime reproduces the zero-height choices viewport with the same instrumentation.')
frame('common-before-actions-landscape100','candidate-baseline-legacy-actions-landscape100-current-v18','menu-actions-no-match-legacy',
    'Final unchanged strengthened instrumentation on the old app: the original no-match path fails with the IME visible.')
frame('common-after-actions-landscape100','candidate-current-legacy-actions-landscape100-current-v18','menu-actions-no-match-legacy',
    'The same final strengthened instrumentation and legacy fixture on the repaired app: the original no-match path passes with the IME visible.')
for layout,font in [('landscape',100),('landscape',200),('phone',200),('tablet',200)]:
    folder=f'candidate-current-touch-{layout}{font}-current-v18'
    for suffix,stem,caption in [
        ('empty-ime','menu-empty-touch-ime','Actual menu IME open. The 48 dp hide-keyboard control remains reachable; a fully readable empty message may require hiding the keyboard.'),
        ('empty-revealed','menu-empty-touch-revealed','Actual pointer hides the keyboard; complete natural empty text, exact menu query/range retained.'),
        ('long-action','menu-long-action-touch','Selected Set keyboard shortcut action; complete natural label and full semantics, before filtering to Copy.'),
        ('category','menu-category-touch-open','Category menu after starting with the launcher IME visible; bounded scroll and pointer selection.'),
        ('destination','menu-category-destination-open','Exact owned Notes destination after category pointer selection; original keyboard policy observed.')]:
        frame(f'{suffix}-{layout}{font}',folder,stem,caption)
for layout in ['landscape','phone']:
    frame('disabled-submit-'+layout+'200',f'candidate-current-extension-menus-{layout}200-current-v10',
        'form-blockedsubmitfeedbackstaysreachableatlargefontsandimetransitions-blocked-submit-option-bottom',
        'Owned real React Form; blocked Submit remains disabled and every natural label line is reachable. This is not a Store/service certification.')
for label,folder,stem in [
    ('caller-range-negative','candidate-current-touch-actions-landscape100-v5','menu-action-caller-after-open-0'),
    ('caller-range-repaired-common','candidate-current-touch-actions-landscape100-common-v6','menu-action-caller-after-open-0')]:
    frame(label,folder,stem,'Identical 84dfe9c instrumentation and matching after-open stage: intermediate app loses caller range, repaired app preserves it. Later Copy/return assertions are separate from this frame; no atomic screenshot claim.')

frame('retained-launcher-ime-timeout','candidate-current-touch-phone100-current-v10','menu-touch-category-failed','Earlier phone100 setup timeout before menu entry: actual launcher IME remains visible. Separate recovery verified; a passing revised setup establishes no original cause or production repair.')

frame('retained-wide-policy-conflict','candidate-current-touch-landscape100-current-v12','menu-touch-category-failed','Source-traced fixture conflict: direct IME show bypasses the launcher wide-mode request. Native IME later disappears, consistent with its existing hide policy. Revised setup uses the normal Show control; no production repair is claimed.')

for label,folder,stem,caption in [
    ('retained-old-editor-hidden','candidate-baseline-caller-diagnostics-landscape100-current-v16','caller-editor-save-before-display-assert','Old app, unchanged original protocol with diagnostic-only capture; Save is offscreen under the actual IME.'),
    ('retained-old-editor-sliver','candidate-baseline-caller-diagnostics-landscape100-current-v16','caller-editor-ime-before-containment-assert','Old app: only 22 of 126 Save pixels visible, with original containment operands retained. This is not a newly introduced menu defect.'),
    ('retained-current-editor-sliver','candidate-current-caller-diagnostics-landscape100-current-v16','caller-editor-ime-before-containment-assert','Same diagnostic instrumentation on the candidate: matching existing editor limit before user reveal.'),
    ('retained-alias-diagnostic','candidate-current-caller-diagnostics-landscape100-current-v16','caller-alias-error-before-display-assert','Diagnostic capture shows the complete alias error; this passing observation establishes neither the cause nor a repair of the earlier uncaptured failure.'),
    ('current-editor-full-save','candidate-current-inline-landscape100-current-v18','caller-editor-save-revealed','Same candidate app after measured pointer reveal: complete 48 dp Save target. No production editor repair is claimed.'),
    ('current-editor-ime-full-save','candidate-current-inline-landscape100-current-v18','caller-editor-ime-save-revealed','Complete 48 dp Save above the actual IME after recorded pointer scrolling; subsequent pointer activation verifies exact owned note persistence.'),
    ('current-alias-natural-error','candidate-current-inline-landscape100-current-v18','caller-alias-error-revealed','Complete natural alias error checked in its scroll viewport; this adds reachability evidence without explaining the old immediate assertion failure.'),
    ('old-editor-ime-after-reveal','candidate-baseline-caller-diagnostics-landscape100-current-v18','caller-editor-ime-save-revealed','Old runtime passes the strengthened existing-scroll protocol too; the matched native IME/48dp target is an existing capability, not a candidate app fix.')]:
    frame(label,folder,stem,caption)

identity=dict(runtime={k:build[k] for k in BUILD if k in build},instrumentation={k:harness[k] for k in BUILD if k in harness},
    source_scope='Current 220 hashes differ from frozen runtime only in InlinePaletteDeviceTest; current instrumentation compiled independently for both variants.',
    common_range_control_test_apk_sha256='84dfe9cdf944782ebb54076248fef74030d7cac9127566979f755249db5d3213')
write(V/'identity.json',(json.dumps(identity,indent=2)+'\n').encode())
write(V/'retained-capture-hashes.json',(json.dumps(capture_hashes,indent=2)+'\n').encode())
write(V/'bindings.json',(json.dumps(bindings,indent=2,ensure_ascii=False)+'\n').encode())
write(V/'selected-frames.json',(json.dumps(raw_sets,indent=2,ensure_ascii=False)+'\n').encode())
require(not REPORT.exists(),'Inspect an existing report before rewriting it')
write(REPORT,(B/'report-ready-template.md').read_bytes())
public={str(p):sha(p.read_bytes()) for root in [V,P] for p in sorted(root.rglob('*')) if p.is_file()}
public[str(REPORT)]=sha(REPORT.read_bytes())
checkpoint=dict(schema_version=1,issue=91,base_commit='9b2bafee64230015a5d716665cde34d50c942b5f',
    production_source_commit=harness['production_source_commit'],test_source_commit=harness['test_source_commit'],
    source_hashes=harness['source_hashes'],current_native_executions=63,current_cohorts=15,current_cleanup_checks=305,
    retained_unchanged_form_executions=18,retained_form_cohorts=6,retained_form_cleanup_checks=116,current_app_executions_across_two_instrumentation_pairs=81,
    same_boot=matrix['same_boot'],durable_data_unchanged=matrix['durable_data_unchanged'],durable_file_count=matrix['durable_file_count'],
    preexisting_evidence_files=136,raw_capture_sets=raw_sets,retained_capture_hashes=len(capture_hashes),
    case_fields=CASE,host_fields=HOST,public_files=public,
    scope='Compact menu layout, explicit touch keyboard reveal, caller selection preservation; old observer errors/failed protocols remain distinct. No whole-Store/OEM/accessibility/production clearance.')
write(V/'checkpoint.json',(json.dumps(checkpoint,indent=2,ensure_ascii=False)+'\n').encode())
for root in [V,P]:
    for path in root.rglob('*'):
        if not path.is_file() or path.suffix=='.png':continue
        data=path.read_bytes()
        require(not re.search(rb'/(?:Users|private/var|var/folders)/.|-{5}BEGIN [A-Z ]*PRIVATE KEY-{5}|X-Amz-(?:Signature|Credential)=.',data,re.I), 'Unredacted private publication marker')
        if path.suffix=='.xml':ET.fromstring(data)
print(json.dumps(dict(public_files=len(public)+1,raw_capture_sets=len(raw_sets),retained_capture_hashes=len(capture_hashes))))
