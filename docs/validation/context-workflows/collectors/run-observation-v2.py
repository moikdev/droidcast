from pathlib import Path
import argparse, hashlib, json, os, re, subprocess, sys
from preservation import verify, require, absent

base = Path('artifacts/context-workflows')
parser = argparse.ArgumentParser()
parser.add_argument('--command', choices=['lorem-ai', 'menu-bar', 'translate', 'quick-translate', 'quick-translate-view', 'instant-translate-paste', 'instant-translate-view'], required=True)
parser.add_argument('--layout', choices=['tablet', 'phone', 'landscape'], required=True)
parser.add_argument('--font', type=float, default=1)
parser.add_argument('--name', required=True)
parser.add_argument('--build', default='build-observations-v2')
args = parser.parse_args()
require(re.fullmatch(r'[a-z0-9][a-z0-9-]{0,100}', args.name) and re.fullmatch(r'build-[a-z0-9-]+', args.build), 'Invalid artifact name')
require(1 <= args.font <= 2, 'Invalid font scale')
out = base / args.name
require(not out.exists(), 'Attempt already exists; inspect it before repeating')

def read(path):
    return json.loads(path.read_text())

def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

identity = read(base / args.build / 'state.json')
require(identity['completed'], 'Build incomplete')
for path, digest in identity['source_hashes'].items():
    if path.endswith('.py'):
        require(sha(path) == digest, 'Executed Python source differs from the build identity')
apk = base / args.build / 'candidate/app-debug.apk'
test = base / args.build / 'candidate/app-debug-androidTest.apk'
require(all(sha(path) == identity['apk_hashes'][path.name] for path in [apk, test]), 'APK identity differs')
preserved = verify()
out.mkdir(mode=0o700)
cache = Path('<user>/.t3/scratch/droidcast-command-catalog-27caf3/artifacts/extension-catalog/representative-001-bundles-v1/archives')
if args.command == 'lorem-ai':
    klass, method = 'ExtensionFormDeviceTest', 'frozenLoremAiCapabilityDenialKeepsClipboardAndReturns'
    extra = ['--lorem-archive', str(cache / '2803877a52b0cb51da9647dd0c31661a1d8e7714c4b748ec39440da57a5b5d24.zip')]
elif args.command == 'menu-bar':
    klass, method = 'ReviewedTextDeviceTest', 'desktopMenuBarIsUnavailableInReviewedChooserAndReturnsToSource'
    extra = ['--weather-archive', str(cache / 'e5e72beb4e3c6926d3dc9e3e2ef7833ba50cd919c2460a782b244fd071e920de.zip')]
else:
    klass, method = 'ReviewedTextDeviceTest', 'selectedTranslationRunsOnlyAfterReviewAndReportsExactCopyOrServiceFailure'
    extra = ['--translate-archive', str(cache / '909c8a3916ca91612bb610da257f5718af2ef25f303ea95371e907eaa1cf7d69.zip'), '--reviewed-translation-command', args.command]
command = [sys.executable, 'scripts/test-reviewed-text-input.py', '--serial', 'emulator-5580',
           '--avd', 'Tinycast_Tablet_API_36', '--layout', args.layout, '--font-scale', str(args.font),
           '--output', str(out / 'cohort'), '--apk', str(apk), '--test-apk', str(test),
           '--json-archive', str(cache / '223987eaca2c894e37ada9e087669f260cda79d6a3b88ff11463f429cc952e6a.zip'),
           '--classes', 'dev.tinycast.android.' + klass + '#' + method, '--expected', '1', *extra]
state = dict(completed=False, pid=os.getpid(), command_name=args.command, layout=args.layout, font=args.font,
             runtime_source_commit=identity['runtime_source_commit'], test_source_commit=identity['test_source_commit'],
             app_sha256=sha(apk), test_apk_sha256=sha(test), method=method, klass=klass,
             collector_sha256=sha(__file__), preservation_helper_sha256=sha(Path(__file__).with_name('preservation.py')),
             invocation=command, **preserved)

def save():
    (out / 'state.json').write_text(json.dumps(state, indent=2) + '\n')

save()
with (out / 'host.log').open('wb') as log:
    process = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT,
                             env=dict(os.environ, ANDROID_HOME='<user>/Library/Android/sdk'))
state['runner_exit_code'] = process.returncode
save()
receipt = read(out / 'cohort/record.json')
cleanup = receipt.get('cleanup', {})
state.update(passes=receipt.get('passes', 0), cleanup_checks=len(cleanup),
             cleanup_passed=bool(cleanup) and all(row.get('passed') for row in cleanup.values()),
             native_completed=receipt.get('completed') is True)
save()
require(state['cleanup_passed'] and absent('files/reviewed-text-fixture-lease'), 'Cleanup failed; retain recovery ownership and inspect')
require(verify() == preserved, 'Preservation counts changed')
state.update(durable_data_unchanged=True, pre_existing_evidence_unchanged=True, same_boot=True)
save()
prefix = 'form-' if klass == 'ExtensionFormDeviceTest' else 'text-'
paths = list((out / 'cohort/captures').glob(prefix + method.lower() + '-record.json'))
require(len(paths) == 1, 'Missing native case receipt')
case = read(paths[0])
state['case_receipt_passed'] = case.get('passed') is True
state['workflow_outcome'] = case.get('workflow_outcome', 'unclassified')
state['extension_bytecode_executed'] = args.command != 'menu-bar' if case.get('observation_collector_passed') is True else None
state['completed'] = process.returncode == 0 and state['passes'] == 1 and state['native_completed'] and state['case_receipt_passed']
save()
require(sha(__file__) == state['collector_sha256'], 'Collector changed during execution')
print(json.dumps({key: state[key] for key in ['completed', 'command_name', 'layout', 'font', 'passes', 'workflow_outcome', 'cleanup_checks', 'cleanup_passed', 'durable_data_unchanged', 'pre_existing_evidence_unchanged']}, indent=2))
require(state['completed'], 'Native observation failed; inspect the retained attempt before continuing')
