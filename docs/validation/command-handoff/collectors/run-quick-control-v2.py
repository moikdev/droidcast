from pathlib import Path
import argparse, hashlib, json, os, re, subprocess, sys
from preservation import verify, require, absent

base = Path('artifacts/command-handoff')
p = argparse.ArgumentParser()
p.add_argument('--app', choices=['old', 'new'], required=True)
p.add_argument('--layout', choices=['tablet', 'phone', 'landscape'], required=True)
p.add_argument('--font', type=float, default=1)
p.add_argument('--name', required=True)
args = p.parse_args()
require(re.fullmatch(r'[a-z0-9][a-z0-9-]{0,100}', args.name) and 1 <= args.font <= 2, 'Invalid cohort arguments')
out = base / args.name
require(not out.exists(), 'Attempt exists; inspect before repeating')
read = lambda path: json.loads(Path(path).read_text())
sha = lambda path: hashlib.sha256(Path(path).read_bytes()).hexdigest()
candidate = read(base / 'build-candidate-v2/state.json')
common = read(base / 'common-v5/identity.json')
require(candidate['completed'], 'Candidate build incomplete')
for path, digest in candidate['source_hashes'].items():
    if path.endswith('.py'):
        require(sha(path) == digest, 'Executed Python sources differ')
apk = base / ('common-v5' if args.app == 'old' else 'build-candidate-v2/candidate') / 'app-debug.apk'
test = base / 'common-v5/app-debug-androidTest.apk'
app_identity = common if args.app == 'old' else candidate
require(sha(apk) == app_identity['apk_hashes'][apk.name] and sha(test) == common['apk_hashes'][test.name], 'APK identity mismatch')
before = verify()
out.mkdir(mode=0o700)
cache = Path('<user>/.t3/scratch/droidcast-command-catalog-27caf3/artifacts/extension-catalog/representative-001-bundles-v1/archives')
method = 'selectedTranslationRunsOnlyAfterReviewAndReportsExactCopyOrServiceFailure'
command = [sys.executable, 'scripts/test-reviewed-text-input.py', '--serial', 'emulator-5580', '--avd', 'Tinycast_Tablet_API_36',
           '--layout', args.layout, '--font-scale', str(args.font), '--output', str(out / 'cohort'), '--apk', str(apk), '--test-apk', str(test),
           '--classes', 'dev.tinycast.android.ReviewedTextDeviceTest#' + method, '--expected', '1',
           '--reviewed-translation-command', 'quick-translate',
           '--json-archive', str(cache / '223987eaca2c894e37ada9e087669f260cda79d6a3b88ff11463f429cc952e6a.zip'),
           '--translate-archive', str(cache / '909c8a3916ca91612bb610da257f5718af2ef25f303ea95371e907eaa1cf7d69.zip')]
state = dict(completed=False, pid=os.getpid(), app=args.app, layout=args.layout, font=args.font,
             runtime_source_commit=app_identity['runtime_source_commit'], test_source_commit=common['test_source_commit'],
             app_sha256=sha(apk), test_apk_sha256=sha(test), collector_sha256=sha(__file__),
             preservation_helper_sha256=sha(Path(__file__).with_name('preservation.py')), **before)

def save():
    (out / 'state.json').write_text(json.dumps(state, indent=2) + '\n')

save()
with (out / 'host.log').open('wb') as log:
    result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, env=dict(os.environ, ANDROID_HOME='<user>/Library/Android/sdk'))
receipt = read(out / 'cohort/record.json')
cleanup = receipt.get('cleanup', {})
state.update(runner_exit_code=result.returncode, passes=receipt.get('passes', 0), cleanup_checks=len(cleanup),
             cleanup_passed=bool(cleanup) and all(row.get('passed') for row in cleanup.values()))
save()
require(state['cleanup_passed'] and absent('files/reviewed-text-fixture-lease'), 'Cleanup failed; retain ownership and inspect')
require(verify() == before, 'Preservation differs')
state.update(same_boot=True, durable_data_unchanged=True, pre_existing_evidence_unchanged=True)
save()
records = list((out / 'cohort/captures').glob('text-' + method.lower() + '-record.json'))
require(len(records) == 1, 'Missing native case receipt')
case = read(records[0])
state['phase_outcome'] = case.get('workflow_outcome', 'unclassified')
expected = 'blocked-command-handoff' if args.app == 'old' else 'working-observed-path'
state['expected_phase'] = expected
state['compatible_listed_workflow'] = args.app == 'new' and state['phase_outcome'] == expected
state['completed'] = result.returncode == 0 and receipt.get('completed') is True and state['passes'] == 1 and case.get('passed') is True and state['phase_outcome'] == expected
if state['completed']:
    require(case['returned_to_external_caller'] and case['source_text_and_selection_unchanged'], 'Source return missing')
    if args.app == 'old':
        require('launchCommand is not supported on Android' in case['observed_runtime_error'], 'Wrong old boundary')
        state['negative_capability_observed'] = True
    else:
        require(case['handoff_target'] == 'quick-translate-view' and case['child_raw_input_grant_absent'] is True and case['copy_exact'] is True, 'Missing handoff/input/Copy witness')
save()
require(sha(__file__) == state['collector_sha256'], 'Collector changed')
print(json.dumps({key: state[key] for key in ['completed', 'app', 'layout', 'font', 'passes', 'phase_outcome', 'compatible_listed_workflow', 'cleanup_checks', 'cleanup_passed']}, indent=2))
require(state['completed'], 'Control did not establish the required phase; inspect retained attempt')
