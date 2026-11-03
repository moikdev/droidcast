"""Stage source-bound public receipts after every final native group completes."""
from pathlib import Path
import hashlib,json,subprocess,sys,shutil
sys.path.insert(0,str(Path(__file__).parent))
from typed_publication_helpers_v2 import sha,public_host,redact,write_json,copy_text,copy_raw
b=Path('artifacts/form-pickers')
pipeline=json.loads((b/'final-typed-pipeline-v5-state.json').read_text())
assert pipeline['completed'], 'Final pipeline is incomplete; do not publish a clearance'
source=json.loads((b/'file-workflow-harness-v28-state.json').read_text())
assert source['completed'] and source['exit_code']==0 and source['sources_unchanged']
assert all(sha(p)==h for p,h in source['source_hashes'].items())
current_states=[f'final-typed-native-{g}-v4-state.json' for g in ['real','typed','lifecycle','extended','files']]+['final-typed-native-legacy-legacymonitoredv1-state.json','final-typed-native-criticalfonts-fontfinalv1-state.json']
current=[]
for name in current_states:
 d=json.loads((b/name).read_text()); expected_test = pipeline['retained_test_source_commit'] if '-v4-' in name else pipeline['current_test_source_commit']; assert d['completed'] and d['production_source_commit']==source['production_source_commit'] and d['test_source_commit']==expected_test
 for c in d['cohorts']:
  assert c['completed'] and c['cleanup_all_passed'];current.append((name,c))
assert sum(c['passes'] for name,c in current)==183
staging=b/'publication-v5';assert not staging.exists(), 'Inspect prior publication state before resuming'
staging.mkdir(mode=0o700);proof=staging/'validation';images=staging/'screenshots';proof.mkdir();images.mkdir()
record={'completed':False,'production_source_commit':source['production_source_commit'],'test_source_commit':source['test_source_commit'],
        'source_hashes':source['source_hashes'],'retained_source_hashes':pipeline['retained_source_hashes'],'retained_test_source_commit':pipeline['retained_test_source_commit'],'source_binding':'Five completed groups retain v26 and byte-identical method bodies; only checkboxLabelTogglesExactlyOnce changed, and the whole legacy group plus critical fonts use v28; legacy includes read-only monitoring of the active IME case and earlier unmonitored IME failures remain unresolved. All183 use the same55e app.','current_native_executions':183,'current_cohorts':len(current),
        'current_cleanup_checks':sum(c['cleanup_checks'] for name,c in current),'transformations':[],'cohorts':[]}
def save():write_json(staging/'preparation-state.json',record)
save()
# Hosts are allowlisted, not copied or hashed in full: they contain private recovery metadata.
for state_name,c in current:
 root=Path(c['output']);host=json.loads((root/'record.json').read_text());destination=proof/'cohorts'/root.name;destination.mkdir(parents=True)
 write_json(destination/'record.json',public_host(host))
 record['transformations'].append(copy_text(root/'tests.log',destination/'tests.log'))
 cases=list((root/'captures').glob('form-*-record.json'));assert len(cases)==c['passes']
 for case_path in cases:
  case=json.loads(case_path.read_text());assert case['passed'] and case['installed_build_commit']==source['production_source_commit'] and case['owned_data_removed'] and case['preexisting_installations_and_data_preserved']
  record['transformations'].append(copy_raw(case_path,destination/'cases'/case_path.name))
 record['cohorts'].append({'name':root.name,'controller_state':state_name,'test_source_commit':pipeline['retained_test_source_commit'] if '-v4-' in state_name else pipeline['current_test_source_commit'],'passes':c['passes'],'cleanup_checks':c['cleanup_checks'],'cleanup_all_passed':True})
for name in current_states+['final-typed-pipeline-v5-state.json','file-workflow-harness-v28-state.json','file-workflow-harness-v26-state.json','typed-build-ime-layout-v4-state.json','publication-transform-check-v2.json']:
 record['transformations'].append(copy_text(b/name,proof/'pipelines'/name))
for name in ['run-final-typed-pipeline-v5.py','run-final-typed-native-v2.py','run-critical-font-native-v1.py','final-typed-native-plan-v2.json','critical-font-native-plan-v1.json','typed_publication_helpers_v2.py','prepare-typed-evidence-v5.py']:
 record['transformations'].append(copy_text(b/name,proof/'pipelines'/name))
build=json.loads((b/'typed-build-ime-layout-v4-state.json').read_text());root=Path(build['candidate_directory'])
for p in sorted((root/'checks').rglob('*')):
 if p.is_file() and p.suffix=='.xml':record['transformations'].append(copy_text(p,proof/'checks'/p.relative_to(root/'checks')))
for name,check in build['checks'].items():
 p=Path(check['log']);record['transformations'].append(copy_text(p,proof/'checks'/(name+'.log')))
# A later pass adds selected byte-exact raw triplets, historical matched controls,
# their build bindings, and a manifest of all retained capture hashes. This stage
# deliberately never copies APKs, private originals, metadata URLs or snapshots.
record.update(completed=True,scope='Prepared final current-runtime receipts only; publication requires historical controls and raw-image manifest before commit.')
save();print(json.dumps({k:record[k] for k in ['completed','current_native_executions','current_cohorts','current_cleanup_checks']},indent=2))
