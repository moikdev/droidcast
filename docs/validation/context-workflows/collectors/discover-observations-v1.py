from pathlib import Path
import hashlib, importlib.util, json, os, re, subprocess, sys
from preservation import verify, require, ADB

base = Path('artifacts/context-workflows')
out = base / 'discovery-v1'
require(not out.exists(), 'Discovery already exists; inspect before repeating')
identity = json.loads((base / 'build-observations-v3/state.json').read_text())
require(identity['completed'], 'Build incomplete')
before = verify()
sys.path.insert(0, 'scripts')
spec = importlib.util.spec_from_file_location('owned_runner', 'scripts/test-reviewed-text-input.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
out.mkdir()
state = dict(completed=False, execution_credit=0, source_commit=identity['source_commit'], runs=[])

def save():
    (out / 'record.json').write_text(json.dumps(state, indent=2) + '\n')

save()
for name in ['app-debug.apk', 'app-debug-androidTest.apk']:
    path = base / 'build-observations-v3/candidate' / name
    require(hashlib.sha256(path.read_bytes()).hexdigest() == identity['apk_hashes'][name], 'APK changed')
    result = subprocess.run(ADB + ['install', '-r', str(path)], capture_output=True, timeout=120)
    require(result.returncode == 0 and b'Success' in result.stdout, 'Install failed')
rows = [('default', 'dev.tinycast.android.ReviewedTextDeviceTest', False, False, 9),
        ('translation-opt-in', 'dev.tinycast.android.ReviewedTextDeviceTest', True, False, 10),
        ('explicit-menu', runner.MENU_BAR_OBSERVATION_CASE, False, True, 1)]
for name, classes, translation, weather, expected in rows:
    exclusions = runner.context_test_filter(classes, translation, weather)
    command = ADB + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'log', 'true',
                     '-e', 'class', classes, *exclusions,
                     'dev.tinycast.android.test/androidx.test.runner.AndroidJUnitRunner']
    with (out / (name + '.log')).open('wb') as log:
        process = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=120)
    text = (out / (name + '.log')).read_text()
    methods = sorted(set(re.findall(r'^INSTRUMENTATION_STATUS: test=(.+)$', text, re.M)))
    require(process.returncode == 0 and len(methods) == expected and 'FAILURES!!!' not in text, 'Unexpected discovery methods')
    if name == 'default':
        require('selectedTranslationRunsOnlyAfterReviewAndReportsExactCopyOrServiceFailure' not in methods and
                'desktopMenuBarIsUnavailableInReviewedChooserAndReturnsToSource' not in methods, 'Opt-in method included by default')
    if name == 'translation-opt-in':
        require('selectedTranslationRunsOnlyAfterReviewAndReportsExactCopyOrServiceFailure' in methods and
                'desktopMenuBarIsUnavailableInReviewedChooserAndReturnsToSource' not in methods, 'Translation filtering changed')
    state['runs'].append(dict(name=name, invocation=command, exit_code=process.returncode,
                              discovered_methods=methods, method_count=len(methods), execution_credit=0))
    save()
require(verify() == before, 'Preservation differs after discovery')
state.update(completed=True, durable_data_unchanged=True, pre_existing_evidence_unchanged=True,
             scope='AndroidJUnitRunner log-only discovery; no test bodies or service requests credited')
save()
print(json.dumps(dict(completed=True, execution_credit=0, method_counts=[row['method_count'] for row in state['runs']])))
