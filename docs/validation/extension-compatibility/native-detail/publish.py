from pathlib import Path
import hashlib,html,json,re,shutil,xml.etree.ElementTree as ET

BASE=Path('artifacts/markdown-detail')
PLAN=json.loads((BASE/'publication-plan-v1.json').read_text())
OUT=Path(PLAN['public_validation']); IMAGES=Path(PLAN['public_images'])
sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
def read(name): return json.loads((BASE/name).read_text())
final=read(PLAN['final_native']); build=read(PLAN['final_candidate']); local=read('final-local-checks-v7-state.json')
assert final['completed'] and final['native_executions']==161 and build['completed'] and local['completed']
assert all(sha(p)==h for p,h in build['source_hashes'].items())
assert read('image-geometry-matched-v2-state.json')['completed']
assert not OUT.exists() and not IMAGES.exists(), 'Never overwrite a published evidence generation'

# Only controlled workflow fields are published. Original clipboard snapshots,
# preferences, owner IDs and raw restoration identities are never input files.
CASE_KEYS=set("""scope font_scale width_dp height_dp installed_build_commit fixture_mode fixture_command_sha256 fixture_manifest_sha256
owned_png_sha256 owned_svg_sha256 publication_witnesses contract_assertions_started passed owned_data_removed
preexisting_installations_and_data_preserved native_heading_emphasis_code_and_unicode_verified pointer_tag_activation_count
serialized_detail_flag_present hidden_detail_absent input_boundary root_type active_scheme_not_executed actual_tab_focus_and_enter
archive_sha256 axis_aware_scrolls closed_before_compose_disposal_rejected code_exact_text code_scroll_after code_scroll_before
code_table_setup exact_copy frozen_command_sha256 hard_line_exact_text html_metadata_lines image_pixels image_readiness
image_transition_scope ime_toggle_contract_started information_focused_ancestor_witnesses information_selection_handles
information_document_focused_ancestors intent_boundary keyboard_tab_count last_native_text_wait link_line_safe_area
linked_inline_image_pointer_activation_preserved list_search_actual_ime_open long_metadata_url_and_image_information_pointer_reachability
long_press_opens_information markdown_link_pointer native_lists_quotes_escapes_marks_verified old_close_cannot_dismiss_successor
overall_command paragraph_exact_text paragraph_line_witnesses paragraph_natural_line_count paragraph_reveal_method phase_outcomes
pointer_tag_calls primary_copy_not_invoked primary_ime_geometry query_selection_and_caller_preserved queued_callbacks remaining_scope
same_root_and_empty_markdown_preserved stale_link_callbacks supported_intents table_overflowed failure_capture_error_type
clipboard_unchanged_until_copy command_sha256 exact_input_sha256 exact_output_sha256 exact_unicode_output_copied
external_caller_uses_separate_package_and_uid native_code_exact_output_sha256 native_code_monospace native_format_contract_started
preview_no_execution_and_clipboard_unchanged probe_command_sha256 returned_to_external_caller source_text_and_selection_unchanged
caller_query_restored hidden_ime_window_absence_confirmed mood_detail_modes native_detail_false_true_false_true_verified pointer_taps
record_and_search_use_same_persisted_owned_data runtime_start_error saved_data_sha256 saved_owned_record settled_ime_geometry
test_boundary viewport_reveal_gestures window_soft_input_mode window_soft_input_state offscreen_block_initial_height_px
offscreen_block_height_px offscreen_view_bitmap_cleared offscreen_geometry_contract_started offscreen_geometry_and_returned_pixels_verified""".split())
HOST_KEYS=set("""completed avd layout font_scale classes expected runner_sha256 archive_sha256 typed_workflow_archive_sha256
cleanup_helper_sha256 clipboard_helper_sha256 document_helper_sha256 apk_sha256 test_apk_sha256 installed_apks_match
instrumentation_exit_code passes tests_completed owned_extension_count cleanup shared_regression_cohort""".split())
STATE_KEYS=set("""completed production_source_commit test_source_commit source_commit common_test_source_commit old_production_source_commit
app_sha256 test_apk_sha256 common_test_apk_sha256 benchmark_apk_sha256 benchmark_test_apk_sha256 source_hashes jvm lint exit_code
sources_unchanged scope native_executions positive_executions expected_negative_executions junit_passes cleanup_checks failure_type
production_and_jvm_sources_unchanged retained_jvm_cases positive_workflow_executions retained_partial_pipeline retained_completed_cohorts retained_native_executions fresh_native_executions preservation_checks preserved_evidence_files excluded_legacy_category excluded_legacy_action_search""".split())
COHORT_KEYS=set("""phase group layout font_scale completed host_completed expected output exit_code junit_passes cleanup_checks
cleanup_all_passed case_receipts expected_negative_executions expected_negative_verified production_source_commit app_sha256 test_apk_sha256 test_source_commit workflow paired_before_record""".split())

def normalize(text,xml=False):
    text=re.sub(r'/Users/[^/\s]+/\.t3/(?:scratch|projects)/[^/\s\"<>]+','<checkout>',text)
    text=re.sub(r'/Users/[^/\s]+/Library/Android/sdk','<android-sdk>',text)
    text=re.sub(r'/Users/[^/\s]+/Library/Java/JavaVirtualMachines/[^/\s]+/Contents/Home','<java-home>',text)
    text=re.sub(r'/Users/[^/\s]+/\.gradle','<gradle-cache>',text)
    text=re.sub(r'/Users/[^/\s\"<>]+','<user-home>',text)
    text=re.sub(r'(?:/private)?/var/folders/[^\s\"<>]+','<temporary>',text)
    if xml:
        text=re.sub(r'<(checkout|android-sdk|java-home|gradle-cache|user-home|temporary)>',r'&lt;\1&gt;',text)
    return text

def clean(value):
    if isinstance(value,str):return normalize(value)
    if isinstance(value,list):return [clean(x) for x in value]
    if isinstance(value,dict):return {k:clean(v) for k,v in value.items()}
    return value

states=['before-controls-v1-state.json','matched-controls-v2-state.json','link-safe-before-v1-state.json','link-safe-after-v1-state.json',
 'extended-native-v2-state.json','real-workflows-v1-state.json','renderer-finishing-before-v1-state.json',
 'renderer-keyboard-before-v1-state.json','renderer-finishing-after-v2-state.json','touch-finish-matched-v2-state.json',
 'image-readiness-diagnostic-v1-state.json','image-visibility-control-after-v1-state.json','image-geometry-before-v1-state.json',
 'image-geometry-after-v1-state.json','image-geometry-matched-v2-state.json',PLAN['final_native']]
# Failed/incomplete records retain their status; no failed cohort is added to final credit.
excluded=['model-jvm-v1-state.json','first-positive-controls-v1-state.json','interaction-candidate-v1-state.json',
 'primary-conformance-v1-state.json','primary-conformance-v2-state.json','extended-native-v1-state.json',
 'renderer-finishing-after-v1-state.json','final-native-v1-state.json','touch-finish-before-v1-state.json',
 'touch-finish-after-v1-state.json','final-native-v3-state.json','final-native-v4-state.json','final-native-v5-state.json','final-native-v7-state.json','final-native-v8-state.json','final-native-v9-state.json']
research_records=['parser-0.32.0-v1/dependency-record.json', 'androidsvg-1.4-v1/dependency-record.json',
 'material3-tooltip-1.3.1-v1/record.json', 'compose-image-visibility-1.7.6-v1/record.json',
 'commonmark-conformance-0.31.2-v1/record.json']
# Check every selected source before creating this immutable public generation.
for name in states+excluded: read(name)
for reference in research_records:
    raw=(BASE/'research'/reference).read_bytes(); json.loads(raw)
    assert normalize(raw.decode())==raw.decode()
for entry in PLAN['frames']:
    origin=BASE/entry['origin_cohort']/'captures'
    for extension in ['.png','.xml','-frame.json']:
        source=origin/(entry['basename']+extension);assert source.is_file(),source
        if extension=='.xml':ET.fromstring(source.read_bytes())
        if extension=='-frame.json':json.loads(source.read_bytes())

OUT.mkdir(parents=True); IMAGES.mkdir(parents=True)
def write_json(path,value):
    path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(clean(value),indent=2,ensure_ascii=False)+'\n')

cohorts={}; omitted={}; bindings=[]
for name in states+excluded:
    data=read(name); projected={k:v for k,v in data.items() if k in STATE_KEYS}
    projected['cohorts']=[{k:v for k,v in c.items() if k in COHORT_KEYS} for c in data.get('cohorts',[])]
    write_json(OUT/'pipelines'/name,projected)
    for c in data.get('cohorts',[]):
        if c.get('output') and (Path(c['output'])/'record.json').exists():cohorts[Path(c['output']).name]=Path(c['output'])
cohorts['category-menu-baseline-v1-phone200']=BASE/'category-menu-baseline-v1-phone200'
cohorts['action-search-baseline-v1-landscape100']=BASE/'action-search-baseline-v1-landscape100'
for name,origin in sorted(cohorts.items()):
    host=json.loads((origin/'record.json').read_text())
    write_json(OUT/'cohorts'/name/'record.json',{k:v for k,v in host.items() if k in HOST_KEYS})
    log=origin/'tests.log'
    if log.exists():
        dest=OUT/'cohorts'/name/'tests.log';dest.write_text(normalize(log.read_text()))
    for case in sorted((origin/'captures').glob('*-record.json')):
        data=json.loads(case.read_text());keep={k:v for k,v in data.items() if k in CASE_KEYS or re.fullmatch(r'(metadata-(title|value|url)|image-information)-(line-witnesses|natural-lines)',k)}
        omitted[name+'/'+case.name]=sorted(set(data)-set(keep))
        write_json(OUT/'cohorts'/name/case.name,keep)

captures=[]
for entry in PLAN['frames']:
    origin=BASE/entry['origin_cohort']/'captures'
    for extension in ['.png','.xml','-frame.json']:
        source=origin/(entry['basename']+extension);assert source.is_file(),source
        dest=(IMAGES if extension=='.png' else OUT/'frames')/(entry['name']+extension);dest.parent.mkdir(parents=True,exist_ok=True)
        raw=source.read_bytes()
        if extension=='.xml':ET.fromstring(raw)
        if extension!='.png':assert re.search(rb'/Users/[A-Za-z0-9_.-]+/',raw) is None and re.search(rb'[?&](?:X-Amz-Signature|X-Goog-Signature|Signature)=',raw,re.I) is None
        dest.write_bytes(raw);assert sha(dest)==sha(source)
        bindings.append(dict(public=str(dest),origin=entry['origin_cohort']+'/captures/'+source.name,sha256=sha(source),transformation='none'))
    captures.append(entry)
write_json(OUT/'frames.json',captures)
write_json(OUT/'publication-plan.json',dict(PLAN,status='Published only after final checkpoint completion'))
for reference in research_records:
    source=BASE/'research'/reference
    raw=source.read_bytes(); data=json.loads(raw)
    assert normalize(raw.decode())==raw.decode(), source
    dest=OUT/'research'/reference;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(raw)
    bindings.append(dict(public=str(dest),origin=str(source.relative_to(BASE)),sha256=sha(source),transformation='none'))
boundary=read('deadline-boundary-v1.json')
write_json(OUT/'pipelines/deadline-boundary-v1.json',{k:v for k,v in boundary.items() if k in ['completed','reason','output','controller_state','controller_stopped','device_runner_signaled','host_completed','host_junit_passes','host_cleanup_checks','host_cleanup_all_passed','controller_termination_reason']})
source=BASE/'category-menu-boundary-v1.json';dest=OUT/'pipelines'/source.name;dest.write_bytes(source.read_bytes())
bindings.append(dict(public=str(dest),origin=source.name,sha256=sha(source),transformation='none'))
source=BASE/'action-search-boundary-v1.json';dest=OUT/'pipelines'/source.name;dest.write_bytes(source.read_bytes())
bindings.append(dict(public=str(dest),origin=source.name,sha256=sha(source),transformation='none'))

build_native=read('renderer-finishing-candidate-v6-state.json')
checks=[BASE/'renderer-finishing-candidate-v6.log',BASE/'final-native-harness-v7.log',BASE/'final-local-checks-v7.log',
 BASE/'final-local-checks-v7-debug.log',BASE/'renderer-python-v1-normal.log',BASE/'renderer-python-v1-optimized.log',
 BASE/'renderer-node-v1-focus.log',BASE/'renderer-node-v1-submit.log',BASE/'final-native-v7-keys-tablet100-host.log',
 BASE/'run-final-keys-v8.py',BASE/'run-final-keys-v9.py',BASE/'recover-legacy-cohort-v1.py',
 BASE/'category-menu-baseline-v1-phone200-host.log',BASE/'action-search-baseline-v1-landscape100-host.log',
 BASE/'run-final-keys-v10.py']
checks+=list((Path(build_native['candidate_directory'])/'jvm').glob('TEST-*.xml'))
checks+=list(Path(local['candidate_directory']).glob('lint-*.xml'))
for source in checks:
    dest=OUT/'checks'/source.name;dest.parent.mkdir(parents=True,exist_ok=True)
    text=normalize(source.read_text(),xml=source.suffix=='.xml');dest.write_text(text)
    if source.suffix=='.xml':ET.fromstring(dest.read_bytes())
    bindings.append(dict(public=str(dest),origin=str(source.relative_to(BASE)),sha256=sha(source),public_sha256=sha(dest),transformation='normalize-host-prefixes-xml-safe-v1'))
write_json(OUT/'final-source-identity.json',build)
# Full reuse identities keep their original execution record/source revision.
write_json(OUT/'local-checks.json',local)
for name in ['renderer-python-v1-state.json','renderer-node-v1-state.json','renderer-finishing-candidate-v6-state.json','final-native-harness-v7-state.json']:
    data=read(name);write_json(OUT/'checks'/name,{k:v for k,v in data.items() if k in STATE_KEYS or k in ['runs','python_reuse','node_reuse','retained_jvm','debug_build_and_lint_record','current_debug_lint']})

for label,reference in [('candidate','private-keyboard-recovery-v8'),('baseline','private-category-menu-baseline-recovery-v1'),
 ('landscape-candidate','private-landscape-keys-recovery-v9'),('landscape-baseline','private-action-search-baseline-recovery-v1')]:
    source=BASE/reference/'public-recovery.json';raw=source.read_bytes();data=json.loads(raw)
    assert data['completed'] and normalize(raw.decode())==raw.decode()
    dest=OUT/'recovery'/(label+'-legacy-recovery.json');dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(raw)
    bindings.append(dict(public=str(dest),origin=str(source.relative_to(BASE)),sha256=sha(source),transformation='none'))
retained=[]
for name,origin in sorted(cohorts.items()):
    for source in sorted(p for folder in ['captures','extension-grid-evidence','readiness-evidence'] for p in (origin/folder).glob('*')):
        if source.is_file() and (source.suffix in ['.png','.xml'] or source.name.endswith('-frame.json')):
            retained.append(dict(origin=name+'/'+str(source.relative_to(origin)),sha256=sha(source)))
write_json(OUT/'retained-capture-hashes.json',retained)
write_json(OUT/'publication-bindings.json',bindings)
write_json(OUT/'case-projection.json',dict(root_keys_kept=sorted(CASE_KEYS),root_keys_omitted=omitted,clipboard_originals_and_fingerprints='Excluded; private backups/recovery snapshots are never read or hashed by publication.'))
shutil.copyfile(__file__,OUT/'publish.py')
for p in OUT.rglob('*.xml'):ET.fromstring(p.read_bytes())
files=[p for root in [OUT,IMAGES] for p in root.rglob('*') if p.is_file()]
for p in files:
    if p.suffix in ['.json','.xml','.log','.py']:
        raw=p.read_bytes();assert re.search(rb'/Users/[A-Za-z0-9_.-]+/',raw) is None,(p,'unredacted host path')
        assert re.search(rb'[?&](?:X-Amz-Signature|X-Goog-Signature|Signature)=',raw,re.I) is None,(p,'signed metadata')
write_json(OUT/'checkpoint.json',dict(schema_version=1,issue=89,base_commit='f1939f81e916224285732d3ea180ceffba8fef47',
 implementation_commit=build['production_source_commit'],test_source_commit=build['test_source_commit'],app_sha256=build['app_sha256'],test_apk_sha256=build['test_apk_sha256'],
 final_native_executions=final['native_executions'],final_native_cohorts=len(final['cohorts']),final_native_cleanup_checks=final['cleanup_checks'],
 retained_native_executions=final['retained_native_executions'],fresh_native_executions=final['fresh_native_executions'],
 separate_preservation_checks=len(final['preservation_checks']),preserved_evidence_files=final['preserved_evidence_files'],
 selected_raw_capture_sets=len(captures),retained_capture_hashes=len(retained),source_hashes=build['source_hashes'],remaining_limits=PLAN['remaining_limits'],
 scope='Scoped native Markdown/metadata/Detail milestone; retained failures and older binary identities remain distinct. No full Store/platform/OEM/accessibility/production clearance.',
 public_files={str(p):sha(p) for p in sorted(files)}))
print(json.dumps(dict(public_files=len(files)+1,selected_raw_capture_sets=len(captures),retained_capture_hashes=len(retained)),indent=2))
