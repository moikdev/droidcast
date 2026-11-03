"""Add final checks and explicit, public-only workflow and monitor identities."""
from pathlib import Path
import json, sys
sys.path.insert(0, str(Path(__file__).parent))
from typed_publication_helpers_v2 import sha, write_json, copy_text

b = Path('artifacts/form-pickers')
stage = b / 'publication-v5'
proof = stage / 'validation'
assert json.loads((stage / 'preparation-state.json').read_text())['completed']
assert json.loads((stage / 'history-preparation-state.json').read_text())['completed']
state = stage / 'supplement-preparation-state.json'
assert not state.exists()
final = json.loads((b / 'final-typed-checks-v3-state.json').read_text())
assert final['completed'] and final['exit_code'] == 0 and final['sources_unchanged']
assert final['rebuilt_apks_equal_frozen']
assert all(sha(p) == h for p, h in final['source_hashes'].items())
record = dict(completed=False, transformations=[])
write_json(state, record)

for name in ['final-typed-checks-v3-state.json', 'run-final-typed-checks-v3.py',
             'raw-capture-owner-review-v1.json', 'prepare-typed-history-v4.py',
             'prepare-typed-supplement-v2.py', 'run-main-ime-group-monitor-v1.py']:
    record['transformations'].append(copy_text(b / name, proof / 'pipelines' / name))
record['transformations'].append(copy_text(b / 'final-typed-checks-v3-gradle.log',
                                         proof / 'checks-final' / 'gradle.log'))
for p in sorted((b / 'final-typed-checks-v3').rglob('*')):
    if p.is_file() and p.suffix == '.xml':
        record['transformations'].append(copy_text(p, proof / 'checks-final' / p.relative_to(b / 'final-typed-checks-v3')))

monitor = json.loads((b / 'main-ime-group-monitor-v1-state.json').read_text())
assert monitor['completed'] and monitor['engine_exit_code'] == 0
public_monitor = {key: monitor[key] for key in ['completed', 'scope', 'engine_exit_code', 'samples']}
public_monitor['boundary'] = ('Read-only observations coupled to the active legacy IME case. '
    'Raw system input-method dumps and process identifiers stay private. '
    'The pass does not isolate the cause of earlier unmonitored setup timeouts; no source or policy fix is claimed.')
write_json(proof / 'legacy-ime-monitor.json', public_monitor)

snapshot = json.loads((b / 'typed-real-bundles-v1' / 'snapshot.json').read_text())
bundles = []
for extension in snapshot['extensions']:
    row = {key: extension[key] for key in ['directory', 'title', 'archive_bytes', 'archive_sha256',
        'manifest_sha256', 'file_inventory_sha256', 'store_reported_commit_sha']}
    row['commands'] = [{key: command[key] for key in ['name', 'title', 'mode', 'file_bytes', 'file_sha256']}
                       for command in extension['commands']
                       if command['name'] in {'record-mood', 'search-moods', 'hashfile'}]
    bundles.append(row)
write_json(proof / 'bundle-identities.json', dict(
    scope='Two workflow scenarios spanning three frozen commands. Listing projection changes only owned fixture namespace/title; command JavaScript is unchanged. Store metadata and the pinned source inventory are separate populations.',
    extensions=bundles))
record['transformations'].append(copy_text(b / 'typed-real-workflows-final-v1.json',
                                         proof / 'real-workflow-verification.json'))
record['completed'] = True
write_json(state, record)
print(json.dumps(dict(completed=True, transformations=len(record['transformations']))))
