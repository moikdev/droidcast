from pathlib import Path
import argparse, hashlib, json, os, re, subprocess, sys
from preservation import verify, require, absent

base = Path('artifacts/paste-copy')
p = argparse.ArgumentParser()
p.add_argument('--name', required=True)
p.add_argument('--group', choices=['callers', 'handoff'], required=True)
p.add_argument('--layout', choices=['tablet', 'phone'], required=True)
a = p.parse_args()
require(re.fullmatch(r'[a-z0-9-]+', a.name), 'Invalid output name')
out = base / a.name
require(not out.exists(), 'Attempt exists; inspect before repeating')
read = lambda path: json.loads(Path(path).read_text())
sha = lambda path: hashlib.sha256(Path(path).read_bytes()).hexdigest()
build = read(base / 'candidate-v2/identity.json')
harness = read(base / 'owned-harness-v6/identity.json')
app = base / 'candidate-v2/app-debug.apk'
test = base / 'owned-harness-v6/app-debug-androidTest.apk'
require(sha(app) == harness['app_sha256'] and sha(test) == harness['test_apk_sha256'], 'Frozen APK differs')
for path, digest in harness['source_hashes'].items():
    if path.endswith('.py'):
        require(sha(path) == digest, 'Executed Python source differs')
if a.group == 'handoff':
    classes = 'dev.tinycast.android.ExtensionCommandHandoffDeviceTest'
    expected = 8
else:
    methods = {
        'ExtensionFormDeviceTest': ['nativeConfigurationEditsValidateCancelAndRun',
            'ownedNoViewReturnRejectsDisposedAndReplacedCallbacks',
            'ownedNoViewReturnAcceptsFocusedEnterAndPreservesEscape',
            'correctingAnErrorSubmitsExactUnicodeOnce'],
        'ExtensionDetailDeviceTest': ['listTouchDetailPreservesQuerySelectionExactCopyAndCaller',
            'recreationStartsAnewOwnerAndRejectsThePriorNativeCallback'],
        'ReviewedTextDeviceTest': ['selectedJsonRunsOnlyAfterReviewThenCopiesAndReturns',
            'ordinaryCommandDoesNotReceiveClipboardAsSelectedText'],
        'ReviewedTextLifecycleDeviceTest': ['resetCloseErrorAndOrdinaryLaunchRevokeInput',
            'staleRuntimeCannotPublishOrWriteAfterReplacement',
            'runtimeCancellationRegistrationCannotBeDetachedByAnOldRuntime',
            'rejectedAsyncAndSyncHostCallsReleaseApprovedInput'],
    }
    classes = ','.join('dev.tinycast.android.' + klass + '#' + method
        for klass, names in methods.items() for method in names)
    expected = sum(map(len, methods.values()))
before = verify()
out.mkdir(mode=0o700)
state = dict(completed=False, pid=os.getpid(), group=a.group, layout=a.layout,
    expected=expected, classes=classes, runtime_source_commit=build['source_commit'],
    test_source_commit=harness['test_source_commit'], app_sha256=sha(app),
    test_apk_sha256=sha(test), collector_sha256=sha(__file__), **before)
def save():
    (out / 'state.json').write_text(json.dumps(state, indent=2) + '\n')
save()
archive = '<related-checkout>/droidcast-command-catalog-27caf3/artifacts/extension-catalog/representative-001-bundles-v1/archives/223987eaca2c894e37ada9e087669f260cda79d6a3b88ff11463f429cc952e6a.zip'
command = [sys.executable, 'scripts/test-reviewed-text-input.py', '--serial', 'emulator-5580',
    '--avd', 'Tinycast_Tablet_API_36', '--layout', a.layout, '--output', str(out/'cohort'),
    '--apk', str(app), '--test-apk', str(test), '--classes', classes, '--expected', str(expected),
    '--json-archive', archive]
with (out / 'host.log').open('wb') as log:
    r = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT,
        env=dict(os.environ, ANDROID_HOME='<android-sdk>'))
receipt = read(out / 'cohort/record.json')
cleanup = receipt.get('cleanup', {})
state.update(runner_exit_code=r.returncode, passes=receipt.get('passes', 0),
    cleanup_checks=len(cleanup), cleanup_passed=bool(cleanup) and all(x.get('passed') for x in cleanup.values()))
save()
require(state['cleanup_passed'] and absent('files/reviewed-text-fixture-lease'), 'Cleanup failed; retain owner and inspect')
require(verify() == before, 'Preservation differs')
state.update(same_boot=True, durable_data_unchanged=True, pre_existing_evidence_unchanged=True,
    completed=r.returncode == 0 and receipt.get('completed') is True and state['passes'] == expected)
save()
print(json.dumps({k: state[k] for k in ['completed', 'group', 'layout', 'expected', 'passes', 'cleanup_checks']}, indent=2))
require(state['completed'], 'Regression cohort failed; retain its actual outcome')
