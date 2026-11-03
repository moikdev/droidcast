"""Stage repaired-runtime evidence; keep the initial Git-bound snapshot separate."""
from pathlib import Path
import hashlib, json, subprocess, sys
sys.path.insert(0, str(Path(__file__).parent))
from typed_publication_helpers_v2 import sha, public_host, write_json, copy_text as _copy_text, copy_raw as _copy_raw

b = Path('artifacts/form-pickers')
pipeline_path = b / 'completed-typed-audit-scope-v1.json'
assert not pipeline_path.exists()
prior_path = b / 'final-typed-native-auditrepairs-auditfinalv1-state.json'
fresh_path = b / 'final-typed-native-auditrepairs-auditdiagv1-state.json'
prior, fresh = json.loads(prior_path.read_text()), json.loads(fresh_path.read_text())
monitor = json.loads((b / 'date-audit-host-diagnostic-v1-state.json').read_text())
assert not prior['completed'] and [c['passes'] for c in prior['cohorts']] == [4,4,3]
assert fresh['completed'] and monitor['completed'] and monitor['engine_exit_code'] == 0
members = prior['cohorts'][:2] + fresh['cohorts']
assert len(members) == 3 and all(c['completed'] and c['passes'] == 4 and c['cleanup_all_passed'] for c in members)
assert prior['methods'] == fresh['methods']
assert prior['production_source_commit'] == fresh['production_source_commit']
assert prior['test_source_commit'] == fresh['test_source_commit']
assembled = dict(completed=True, production_source_commit=fresh['production_source_commit'],
    test_source_commit=fresh['test_source_commit'], methods=fresh['methods'], cohorts=members,
    source_controllers={prior_path.name:sha(prior_path), fresh_path.name:sha(fresh_path)},
    original_failed_cohort=prior['cohorts'][-1],
    boundary='Completed original tablet/phone cohorts plus one distinct fresh whole landscape4-case cohort on identical APKs. The prior group11/12 and landscape3/4 remain failed; no cause isolation or clipboard-helper/product repair is credited.')
assembled_path = b / 'final-typed-native-auditrepairs-auditverifiedv2-state.json'
assert not assembled_path.exists(); write_json(assembled_path, assembled)
groups = []
for group in ['real','typed','lifecycle','extended','files','legacy','auditrepairs','criticalfonts']:
    version = 'auditverifiedv1' if group == 'legacy' else 'auditverifiedv2' if group == 'auditrepairs' else 'auditfinalv1'
    name = f'final-typed-native-{group}-{version}-state.json'
    d = json.loads((b / name).read_text())
    assert d['completed'] and all(c['completed'] and c['cleanup_all_passed'] for c in d['cohorts'])
    groups.append(dict(group=group, state=name, state_sha256=sha(b / name),
        passes=sum(c['passes'] for c in d['cohorts']), cohorts=len(d['cohorts']),
        cleanup_checks=sum(c['cleanup_checks'] for c in d['cohorts'])))
assert sum(s['passes'] for s in groups) == 195 and sum(s['cohorts'] for s in groups) == 27
pipeline = dict(completed=True, groups=groups,
    boundary='195 completed current-APK executions; twelve same-body common-APK positive controls and twelve expected-negative executions remain separate. Legacy and audit-control groups explicitly assemble completed whole cohorts. Earlier failed pipelines are not relabeled as successful.',
    excluded_failed_groups=['final-typed-native-legacy-auditfinalv1-state.json','final-typed-native-auditrepairs-auditfinalv1-state.json'])
write_json(pipeline_path, pipeline)
build = json.loads((b / 'typed-build-audit-repair-v1-state.json').read_text())
assert build['completed'] and build['exit_code'] == 0 and build['sources_unchanged']
assert all(sha(p) == h for p, h in build['source_hashes'].items())
stage = b / 'publication-audit-repair-v2'
assert not stage.exists()
stage.mkdir(mode=0o700)
proof, images = stage / 'validation', stage / 'screenshots'
proof.mkdir(); images.mkdir()
def copy_text(source, destination):
    return {**_copy_text(source, destination), 'destination':str(Path(destination).relative_to(stage))}
def copy_raw(source, destination):
    return {**_copy_raw(source, destination), 'destination':str(Path(destination).relative_to(stage))}
record = dict(completed=False, production_source_commit=build['production_source_commit'],
    current_test_source_commit=build['test_source_commit'], current_source_hashes=build['source_hashes'],
    current_native_executions=195, current_cohorts=27,
    current_cleanup_checks=sum(s['cleanup_checks'] for s in pipeline['groups']),
    separate_matched_positive_executions=12, separate_matched_negative_executions=12,
    separate_matched_cleanup_checks=116, transformations=[], cohorts=[], captures=[])
state = stage / 'preparation-state.json'
def save():
    write_json(state, record)
save()

initial_commit = 'd67f71cd5402197f19ffcecd622dee8c343f26aa'
initial_path = 'docs/validation/extension-compatibility/typed-forms/checkpoint.json'
initial_bytes = subprocess.check_output(['git', 'show', initial_commit + ':' + initial_path])
initial = json.loads(initial_bytes)
assert hashlib.sha256(initial_bytes).hexdigest() == 'db56c558034336c8e976908f4dd9b811c2902a5e055bc15a3955ab9e1027760c'
ordered = sorted(initial['published_files'])
raw = subprocess.check_output(['git', 'cat-file', '--batch'],
    input=('\n'.join(initial_commit + ':' + p for p in ordered) + '\n').encode())
position = 0
for path in ordered:
    end = raw.index(b'\n', position); header = raw[position:end].split()
    assert len(header) == 3 and header[1] == b'blob'
    size = int(header[2]); data = raw[end + 1:end + 1 + size]
    assert hashlib.sha256(data).hexdigest() == initial['published_files'][path]
    position = end + 1 + size + 1
assert position == len(raw)
(stage / 'initial-checkpoint.json').write_bytes(initial_bytes)
record['initial_snapshot'] = dict(commit=initial_commit, original_path=initial_path,
    sha256=hashlib.sha256(initial_bytes).hexdigest(), listed_files_verified_against_git=742,
    boundary='This immutable initial manifest is checked against its original Git commit, not rewritten as current-runtime evidence.')
save()

def add_cohort(root, controller, kind, expected_cases):
    root = Path(root)
    host = json.loads((root / 'record.json').read_text())
    dest = proof / kind / root.name
    write_json(dest / 'record.json', public_host(host))
    record['transformations'].append(copy_text(root / 'tests.log', dest / 'tests.log'))
    cases = list((root / 'captures').glob('form-*-record.json'))
    assert len(cases) == expected_cases
    for p in cases:
        case = json.loads(p.read_text())
        assert case['owned_data_removed'] and case['preexisting_installations_and_data_preserved']
        record['transformations'].append(copy_raw(p, dest / 'cases' / p.name))
    record['cohorts'].append(dict(name=root.name, controller=controller, kind=kind,
        native_completed=host['completed'], passes=host['passes'], cases=expected_cases,
        cleanup_checks=len(host['cleanup']), cleanup_all_passed=all(c['passed'] for c in host['cleanup'].values())))

controller_names = []
for step in pipeline['groups']:
    name = step['state']; controller_names.append(name)
    d = json.loads((b / name).read_text())
    assert d['completed'] and d['production_source_commit'] == build['production_source_commit']
    assert d['test_source_commit'] == build['test_source_commit']
    for c in d['cohorts']:
        assert c['completed'] and c['cleanup_all_passed']
        add_cohort(c['output'], name, 'cohorts', c['passes'])
for phase in ['before', 'after']:
    for layout in ['tablet', 'phone', 'landscape']:
        name = f'date-audit-controls-{phase}-{layout}-v1-state.json'
        controller_names.append(name)
        d = json.loads((b / name).read_text())
        assert d['completed'] and d['cohorts'][0]['cleanup_all_passed']
        if phase == 'before':
            assert d['expected_negative_verified'] and d['junit_passes'] == 0
        else:
            assert d['junit_passes'] == 4 and d['production_source_commit'] == build['production_source_commit']
        add_cohort(d['cohorts'][0]['output'], name, 'matched-controls', 4)

for group in ['legacy','auditrepairs']:
    name = f'final-typed-native-{group}-auditfinalv1-state.json'
    d = json.loads((b / name).read_text())
    assert not d['completed'] and all(c['cleanup_all_passed'] for c in d['cohorts'])
    controller_names.append(name)
    selected = d['cohorts'][-1:]
    for c in selected:
        add_cohort(c['output'], name, 'excluded-cohorts', len(d['methods']))
record['excluded_boundaries'] = dict(
    legacy='Original landscape11/12 fails before value entry while showing the IME. A separate full12-case diagnostic pass is credited only to its own cohort; cause and product fix remain unestablished.',
    auditrepairs='Original current-APK group11/12 includes a landscape clipboard-dismissal deadline in @Before. Completed total retains the original successful tablet/phone members plus one distinct fresh whole landscape4-case diagnostic pass. The failed landscape3/4 remains excluded. Common-APK12/12 positive controls remain separate; no helper/product repair or exact stamp-only APK equivalence is claimed.')
for name in controller_names + ['completed-typed-audit-scope-v1.json',
        'final-typed-pipeline-v6-state.json', 'final-typed-pipeline-v7-state.json',
        'final-typed-native-legacy-imewindowv1-state.json', 'final-typed-native-auditrepairs-auditdiagv1-state.json', 'typed-build-audit-repair-v1-state.json',
        'audit-common-pair-v1-state.json', 'file-workflow-harness-v29-state.json']:
    record['transformations'].append(copy_text(b / name, proof / 'pipelines' / name))
for name in ['run-final-typed-pipeline-v6.py', 'run-final-typed-pipeline-v7.py',
        'run-date-audit-host-diagnostic-v1.py', 'run-date-audit-landscape-diagnostic-v1.py', 'date-audit-landscape-diagnostic-plan-v1.json',
        'run-main-ime-window-diagnostic-v1.py', 'run-legacy-ime-window-diagnostic-v1.py', 'legacy-ime-window-diagnostic-plan-v1.json', 'run-final-typed-native-v3.py', 'final-typed-native-plan-v3.json',
        'run-critical-font-native-v1.py', 'critical-font-native-plan-v1.json',
        'run-date-audit-controls-v1.py', 'build-typed-audit-repair-v1.py',
        'run-main-ime-group-monitor-v2.py', 'prepare-typed-audit-evidence-v2.py', 'verify-typed-audit-evidence-v1.py', 'typed_publication_helpers_v2.py']:
    record['transformations'].append(copy_text(b / name, proof / 'pipelines' / name))
record['transformations'].append(copy_text(b / 'typed-build-audit-repair-v1-gradle.log', proof / 'checks' / 'gradle.log'))
for p in sorted((Path(build['candidate_directory']) / 'checks').rglob('*.xml')):
    record['transformations'].append(copy_text(p, proof / 'checks' / p.relative_to(Path(build['candidate_directory']) / 'checks')))
monitor = json.loads((b / 'main-ime-group-monitor-auditfinalv1-state.json').read_text())
assert monitor['completed'] and monitor['engine_exit_code'] == 1
write_json(proof / 'legacy-ime-failed-monitor.json', {k: monitor[k] for k in ['completed', 'scope', 'engine_exit_code', 'samples']})
monitor = json.loads((b / 'main-ime-window-diagnostic-v1-state.json').read_text())
assert monitor['completed'] and monitor['engine_exit_code'] == 0
write_json(proof / 'legacy-ime-window-diagnostic.json', {k: monitor[k] for k in ['completed', 'scope', 'engine_exit_code', 'samples']})

monitor = json.loads((b / 'date-audit-host-diagnostic-v1-state.json').read_text())
assert monitor['completed'] and monitor['engine_exit_code'] == 0
write_json(proof / 'date-audit-host-diagnostic.json', {k: monitor[k] for k in ['completed', 'scope', 'engine_exit_code', 'samples']})

selection = json.loads((b / 'audit-repair-capture-selection-v1.json').read_text())
assert len(selection['captures']) == 34
for item in selection['captures']:
    root = b / item['source_root']
    source = root / 'captures' / item['source_file']
    host = json.loads((root / 'record.json').read_text())
    case_path = source.parent / ('form-' + source.name.split('-', 2)[1] + '-record.json')
    case = json.loads(case_path.read_text())
    entries = []
    for src, dest in [(source, images / (item['name'] + '.png')),
            (source.with_suffix('.xml'), proof / 'captures' / (item['name'] + '.xml')),
            (source.with_name(source.stem + '-frame.json'), proof / 'captures' / (item['name'] + '-frame.json'))]:
        entries.append(copy_raw(src, dest))
    record['transformations'] += entries
    record['captures'].append({**item, 'installed_build_commit':case['installed_build_commit'],
        'app_sha256':host['apk_sha256'], 'test_apk_sha256':host['test_apk_sha256'],
        'case_passed':case.get('passed', False), 'case_source_sha256':sha(case_path), 'raw_files':entries})
write_json(proof / 'capture-manifest.json', dict(scope='34 byte-exact raw sets. Initial images remain historical; current/negative/common/current-APK bindings are explicit.', captures=record['captures']))
retained = {}
for directory in b.rglob('captures'):
    if any(part.startswith('publication-') for part in directory.parts):
        continue
    for p in directory.iterdir():
        if p.is_file() and p.suffix in ['.png', '.xml', '.json']:
            retained[str(p)] = sha(p)
manual = b / 'file-matrix-v2-tablet100' / 'manual-recovery-v1'
for p in manual.rglob('*'):
    if p.is_file() and p.suffix in ['.png', '.xml']:
        retained[str(p)] = sha(p)
write_json(proof / 'retained-capture-hashes.json', dict(scope='Native captures only; private full host/original/recovery snapshots and raw input-method dumps are excluded.', files=retained))
record.update(completed=True, selected_raw_sets=34, retained_capture_files=len(retained))
save()
print(json.dumps({k:record[k] for k in ['completed','current_native_executions','current_cohorts','current_cleanup_checks','selected_raw_sets','retained_capture_files']}, indent=2))
