from pathlib import Path
import hashlib, json, re, subprocess, xml.etree.ElementTree as E

base = Path('artifacts/context-workflows')
public = Path('docs/validation/context-workflows')
read = lambda path: json.loads(Path(path).read_text())
sha = lambda data: hashlib.sha256(data).hexdigest()

def require(value, message):
    if not value:
        raise RuntimeError(message)

checkpoint = read(public / 'checkpoint.json')
for name, expected in checkpoint['public_files'].items():
    require(sha(Path(name).read_bytes()) == expected, 'Public bytes differ: ' + name)
for name, expected in checkpoint['source_hashes'].items():
    require(sha(Path(name).read_bytes()) == expected, 'Current source differs: ' + name)
    frozen = subprocess.check_output(['git', 'show', '77bec8fcfcc501c58707db2d939236943a14f479:' + name])
    require(sha(frozen) == expected, 'Frozen source differs: ' + name)
for binding in checkpoint['original_public_bindings']:
    source = base / binding['artifact']
    require(not any(part.startswith('private-') for part in source.parts), 'Private original in bindings')
    original = source.read_bytes()
    target = Path(binding['public'])
    require(sha(original) == binding['original_sha256'], 'Original binding differs')
    if binding['transformation'] == 'identity':
        reconstructed = original
    else:
        replacement = '&lt;user&gt;' if target.suffix == '.xml' else '<user>'
        reconstructed = original.decode().replace('<user>', replacement).encode()
    require(reconstructed == target.read_bytes() and sha(reconstructed) == binding['public_sha256'], 'Published transformation differs')
for projection in checkpoint['allowlisted_projections']:
    source = read(base / projection['artifact'])
    reconstructed = {key: source[key] for key in projection['allowed_keys'] if key in source}
    if projection['cleanup_projection']:
        reconstructed['cleanup'] = {key: {'passed': row.get('passed') is True} for key, row in source['cleanup'].items()}
    require(reconstructed == read(projection['public']), 'Allowlisted projection differs')
for name, expected in checkpoint['retained_capture_hashes'].items():
    require(sha((base / name).read_bytes()) == expected, 'Retained capture differs')
total_passes = total_expected = total_cleanup = 0
for row in checkpoint['cohorts']:
    root = public / 'cohorts' / row['name']
    state = read(root / 'state.json')
    runner = read(root / 'runner-record.json')
    log = (root / 'tests.log').read_text()
    passes = len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: 0$', log, re.M))
    require(passes == row['passes'] == runner['passes'], 'Case accounting differs')
    require(runner['installed_apks_match'] and runner['apk_sha256'] == row['app_sha256'] and runner['test_apk_sha256'] == row['test_apk_sha256'], 'Installed binary receipt differs')
    require(all(value['passed'] for value in runner['cleanup'].values()), 'Failed restoration')
    total_passes += passes
    total_expected += runner['expected']
    total_cleanup += len(runner['cleanup'])
    if row['category'] == 'verified-positive':
        require(state['completed'] and state['case_receipt_passed'] and state['native_completed'] and passes == runner['expected'] == 1, 'Incomplete final cohort')
        require(state['test_source_commit'] in ['c262fdf3adb7eb4ab7f249007aa72142190a283f', '77bec8fcfcc501c58707db2d939236943a14f479'], 'Verified test source differs')
        cases = list((root / 'cases').glob('*-record.json'))
        require(len(cases) == 1, 'Missing final native case')
        case = read(cases[0])
        require(case['passed'] and case['observation_collector_passed'] and case['workflow_outcome'] == state['workflow_outcome'], 'Phase classification differs')
        if state['command_name'] == 'menu-bar':
            require(case['extension_bytecode_executed'] is False and case['clipboard_unchanged_before_source_return'], 'Unsupported-mode observation changed')
        elif state['command_name'] != 'lorem-ai':
            require(case['source_text_and_selection_unchanged'] and case['returned_to_external_caller'], 'External source return missing')
            require(all(item['bounds'][3] - item['bounds'][1] + 1 >= item['minimum_px'] and
                        item['bounds'][2] - item['bounds'][0] + 1 >= item['minimum_px'] for item in case['context_pointer_targets']), 'Incomplete touch target')
        if state['workflow_outcome'] == 'working-observed-path':
            require(case['copy_exact'] is True and case['network_observation']['decodeErrors'] == 0, 'Missing exact provider/Copy witness')
for version in [1, 2, 3, 4, 5]:
    identity = read(public / 'identities' / ('build-observations-v' + str(version) + '.json'))
    for name, expected in identity['source_hashes'].items():
        frozen = subprocess.check_output(['git', 'show', identity['source_commit'] + ':' + name])
        require(sha(frozen) == expected, 'Historical source manifest differs')
    for name, expected in identity['apk_hashes'].items():
        require(sha((base / ('build-observations-v' + str(version)) / 'candidate' / name).read_bytes()) == expected, 'APK hash differs')
    require(identity['runtime_source_commit'] == 'ed3831303a8319a194f0ae137830acba1f3580d5', 'Production source differs')
xmls = list(public.rglob('*.xml'))
for path in xmls:
    E.parse(path)
for frame in checkpoint['selected_frame_sets']:
    xml = public / 'cohorts' / frame['cohort'] / 'captures' / (frame['stem'] + '.xml')
    require(b'\r\n' in xml.read_bytes(), 'Raw XML line endings changed')
require(checkpoint['verified_native_executions'] == 27, 'Verified count differs')
print(json.dumps(dict(verified=True, public_files=len(checkpoint['public_files']), source_files=len(checkpoint['source_hashes']),
                      original_bindings=len(checkpoint['original_public_bindings']), projections=len(checkpoint['allowlisted_projections']),
                      retained_capture_files=len(checkpoint['retained_capture_hashes']), selected_frames=len(checkpoint['selected_frame_sets']),
                      parseable_xml_files=len(xmls), total_executions=total_expected, total_passes=total_passes,
                      total_failures=total_expected-total_passes, total_cleanup_checks=total_cleanup,
                      verified_executions=checkpoint['verified_native_executions'], verified_cleanup_checks=checkpoint['verified_cleanup_checks']), indent=2))
