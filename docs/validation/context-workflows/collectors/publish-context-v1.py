from pathlib import Path
import hashlib, json, subprocess, xml.etree.ElementTree as E

base = Path('artifacts/context-workflows')
public = Path('docs/validation/context-workflows')
pictures = Path('docs/screenshots/context-workflows')
read = lambda p: json.loads(Path(p).read_text())
sha = lambda data: hashlib.sha256(data).hexdigest()

def require(value, message):
    if not value:
        raise RuntimeError(message)

baseline = read(base / 'baseline-pipeline-v4/record.json')
critical = read(base / 'critical-pipeline-v4/record.json')
continuation = read(base / 'continuation-v5/record.json')
require(baseline['completed'] and continuation['completed'], 'Verified continuation incomplete')
require(read(base / 'discovery-v1/record.json')['completed'], 'Discovery incomplete')
require(not (public / 'checkpoint.json').exists(), 'Publication complete; verify instead of republishing')
public.mkdir(parents=True, exist_ok=True)
pictures.mkdir(parents=True, exist_ok=True)
bindings, projections, cohorts, frames = [], [], [], []
capture_hashes = {}

def write(source, target, raw=False):
    original = Path(source).read_bytes()
    replacement = '&lt;user&gt;' if target.suffix == '.xml' else '<user>'
    data = original if raw else original.decode().replace('<user>', replacement).encode()
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        require(target.read_bytes() == data, 'Existing destination differs')
    else:
        target.write_bytes(data)
    bindings.append(dict(artifact=str(Path(source).relative_to(base)), public=str(target),
                         original_sha256=sha(original), public_sha256=sha(data),
                         transformation='identity' if data == original else 'UTF-8 host-home replacement only; XML-safe &lt;user&gt; in XML, <user> elsewhere'))

def project(source, target, keys):
    original = read(source)
    data = {key: original[key] for key in keys if key in original}
    if 'cleanup' in original:
        data['cleanup'] = {key: {'passed': row.get('passed') is True} for key, row in original['cleanup'].items()}
    target.parent.mkdir(parents=True, exist_ok=True)
    encoded = (json.dumps(data, indent=2) + '\n').encode()
    if target.exists():
        require(target.read_bytes() == encoded, 'Existing projection differs')
    else:
        target.write_bytes(encoded)
    projections.append(dict(artifact=str(Path(source).relative_to(base)), public=str(target), allowed_keys=keys,
                            cleanup_projection='passed boolean only' if 'cleanup' in original else None))
    return data

state_keys = ['completed', 'command_name', 'layout', 'font', 'runtime_source_commit', 'test_source_commit', 'app_sha256',
              'test_apk_sha256', 'method', 'klass', 'collector_sha256', 'preservation_helper_sha256', 'durable_file_count',
              'evidence_file_count', 'runner_exit_code', 'passes', 'cleanup_checks', 'cleanup_passed', 'native_completed',
              'durable_data_unchanged', 'pre_existing_evidence_unchanged', 'same_boot', 'case_receipt_passed', 'workflow_outcome',
              'extension_bytecode_executed']
runner_keys = ['completed', 'serial', 'avd', 'layout', 'font_scale', 'classes', 'expected', 'runner_sha256', 'archive_sha256',
               'typed_workflow_archive_sha256', 'cleanup_helper_sha256', 'clipboard_helper_sha256', 'document_helper_sha256',
               'instrumentation_excluded_tests', 'reviewed_json_command', 'reviewed_translation_command', 'expect_completion_return',
               'apk_sha256', 'test_apk_sha256', 'installed_apks_match', 'instrumentation_exit_code', 'passes', 'tests_completed', 'owned_extension_count']
final_names = continuation['verified_positive_cohorts']
require(len(final_names) == 27 and len(set(final_names)) == 27, 'Final cohort accounting differs')
historical = sorted(path.parent.name for pattern in ['baseline-*/state.json', 'critical-*/state.json']
                    for path in base.glob(pattern) if path.parent.name not in final_names)
names = historical + final_names
for name in names:
    root, dest = base / name, public / 'cohorts' / name
    original_state = read(root / 'state.json')
    keys = state_keys if original_state['completed'] else [key for key in state_keys if key not in ['workflow_outcome', 'extension_bytecode_executed']]
    state = project(root / 'state.json', dest / 'state.json', keys)
    runner = project(root / 'cohort/record.json', dest / 'runner-record.json', runner_keys)
    require(state['cleanup_passed'] and state['durable_data_unchanged'] and state['pre_existing_evidence_unchanged'] and state['same_boot'], 'Preservation failed')
    write(root / 'cohort/tests.log', dest / 'tests.log')
    for path in sorted((root / 'cohort/captures').iterdir()):
        require(path.suffix in ['.png', '.xml', '.json'], 'Unexpected capture path')
        capture_hashes[str(path.relative_to(base))] = sha(path.read_bytes())
        if path.name.endswith('-record.json'):
            write(path, dest / 'cases' / path.name, raw=True)
    cohorts.append(dict(name=name, category='verified-positive' if name in final_names else 'historical-pass' if state['completed'] else 'historical-failure',
                        passes=runner['passes'], expected=runner['expected'], cleanup_checks=len(runner['cleanup']),
                        completed=state['completed'], workflow_outcome=state.get('workflow_outcome', 'uncredited-failed-whole-workflow'),
                        app_sha256=state['app_sha256'], test_apk_sha256=state['test_apk_sha256']))

def frame(name, phase):
    root = base / name / 'cohort/captures'
    candidates = list(root.glob('*-' + phase + '.png'))
    require(len(candidates) == 1, 'Missing selected frame ' + name + '/' + phase)
    png = candidates[0]
    stem = png.stem
    if (name, stem) in frames:
        return
    for suffix in ['.png', '.xml', '-frame.json']:
        source = root / (stem + suffix)
        require(source.exists(), 'Incomplete raw frame triplet')
        target = pictures / name / source.name if suffix == '.png' else public / 'cohorts' / name / 'captures' / source.name
        write(source, target, raw=True)
    frames.append((name, stem))

for name in final_names:
    state = read(base / name / 'state.json')
    command = state['command_name']
    if command == 'lorem-ai':
        phases = ['ai-capability-denied', 'ai-denial-caller-return']
    elif command == 'menu-bar':
        phases = ['menu-bar-unavailable', 'menu-bar-source-return']
    elif command == 'quick-translate':
        phases = ['translation-runtime-boundary', 'translation-context-return']
    elif state['workflow_outcome'] == 'working-observed-path':
        phases = ['translation-native-result', 'translation-exact-copy', 'translation-context-return']
        if list((base / name / 'cohort/captures').glob('*-translation-results-return.png')):
            phases.append('translation-results-return')
    elif state['workflow_outcome'] == 'partial-paste-unsupported':
        phases = ['translation-paste-unavailable', 'translation-context-return']
    elif state['workflow_outcome'] == 'working-display-observed-path':
        phases = ['translation-visible-feedback', 'translation-context-return']
    else:
        phases = ['translation-uncompleted-observation', 'translation-context-return']
    for phase in phases:
        frame(name, phase)
for name, phase in [('baseline-quick-tablet100-v1', 'failure'),
                    ('baseline-menu-bar-tablet100-v2', 'menu-bar-unavailable'),
                    ('baseline-quick-translate-view-phone100-v3', 'failure'),
                    ('critical-translate-landscape200-v4', 'failure')]:
    frame(name, phase)
for version in [1, 2, 3, 4, 5]:
    folder = 'build-observations-v' + str(version)
    state = read(base / folder / 'state.json')
    project(base / folder / 'state.json', public / 'identities' / (folder + '.json'), [key for key in state if key != 'pid'])
    for path in sorted((base / folder).glob('*.log')):
        write(path, public / 'checks' / folder / path.name)
for path in sorted((base / 'build-observations-v5/reports').iterdir()):
    write(path, public / 'checks/lint' / path.name)
for name in ['python-guards-working-v1.log', 'python-guards-optimized-v1.log', 'python-checks-v1.json',
             'observation-contracts-v1.json', 'parent-merge-v1.json', 'startup-harness-gap-v1.json',
             'menu-foreground-harness-gap-v2.json', 'native-detail-return-harness-gap-v3.json', 'header-reveal-harness-gap-v4.json', 'test-apk-transitions-v2.json']:
    write(base / name, public / 'checks' / name)
for folder in ['baseline-pipeline-v2', 'baseline-pipeline-v3', 'baseline-pipeline-v4', 'critical-pipeline-v4', 'continuation-v5', 'discovery-v1']:
    value = read(base / folder / 'record.json')
    if folder == 'discovery-v1':
        write(base / folder / 'record.json', public / 'pipelines' / (folder + '.json'))
    else:
        project(base / folder / 'record.json', public / 'pipelines' / (folder + '.json'), [key for key in value if key != 'pid'])
    for path in sorted((base / folder).glob('*.log')):
        if folder == 'discovery-v1':
            write(path, public / 'checks/discovery' / path.name)
for path in sorted(base.glob('*.py')):
    if path.name == 'preservation.py' or path.name.startswith(('build-observations-', 'run-observation-', 'run-baseline-', 'run-critical-', 'run-final-sequence-', 'run-continuation-', 'discover-observations-', 'publish-context-', 'verify-context-')):
        write(path, public / 'collectors' / path.name)
for path in public.rglob('*.xml'):
    E.parse(path)
for root in [public, pictures]:
    for path in root.rglob('*'):
        if path.is_file() and path.suffix != '.png':
            require((b'/' + b'Users' + b'/') not in path.read_bytes(), 'Host path in public evidence')
files = {str(path): sha(path.read_bytes()) for root in [public, pictures] for path in sorted(root.rglob('*')) if path.is_file()}
require(set(files) == {row['public'] for row in bindings + projections}, 'Unlisted public files exist')
current = [row for row in cohorts if row['category'] == 'verified-positive']
checkpoint = dict(schema_version=1, scope='Seven frozen declarations: phase-specific observed workflows/boundaries; collector success is not compatibility success; no production changes or whole-Store parity',
                  source_head=subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
                  source_hashes=read(base / 'build-observations-v5/state.json')['source_hashes'], cohorts=cohorts,
                  original_public_bindings=bindings, allowlisted_projections=projections, retained_capture_hashes=capture_hashes,
                  selected_frame_sets=[dict(cohort=name, stem=stem) for name, stem in frames], public_files=files,
                  verified_native_executions=sum(row['passes'] for row in current), verified_cleanup_checks=sum(row['cleanup_checks'] for row in current),
                  matrix_cohorts=continuation['matrix_cohorts'], focused_regression_cohorts=continuation['regression_cohorts'],
                  harness_scope='24 retained c262fdf positives plus three fresh 77bec8f positives on the identical production APK; separate test APKs, not stamp-only equivalents',
                  historical_native_executions=sum(row['expected'] for row in cohorts if row['category'] != 'verified-positive'),
                  historical_native_passes=sum(row['passes'] for row in cohorts if row['category'] != 'verified-positive'))
(public / 'checkpoint.json').write_text(json.dumps(checkpoint, indent=2) + '\n')
print(json.dumps(dict(public_files=len(files), selected_frames=len(frames), retained_capture_files=len(capture_hashes),
                      verified_executions=checkpoint['verified_native_executions'], verified_cleanup_checks=checkpoint['verified_cleanup_checks']), indent=2))
