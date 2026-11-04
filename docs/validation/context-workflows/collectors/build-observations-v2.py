from pathlib import Path
import hashlib, json, os, shutil, subprocess, zipfile
import xml.etree.ElementTree as E

base = Path('artifacts/context-workflows')
out = base / 'build-observations-v2'
parent = Path('<user>/.t3/scratch/droidcast-offline-workflows-27caf3/artifacts/offline-workflows/build-harness-v7')

def require(value, message):
    if not value:
        raise RuntimeError(message)

def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()

head = git('rev-parse', 'HEAD')
prior = json.loads((parent / 'state.json').read_text())
runtime = prior['runtime_source_commit']
require(head == '900f3de6bde7dc40652442aa8237fecfa33bddc7', 'Unexpected source revision')
require(not git('status', '--porcelain=v1', '--untracked-files=all'), 'Unfrozen source')
require(not git('diff', '--name-only', runtime, head, '--', 'app/src/main', 'app/build.gradle.kts', 'build.gradle.kts', 'gradle'), 'Production or build source changed')
sources = {path: sha(path) for path in prior['source_hashes']}
changed = [path for path in sources if sources[path] != prior['source_hashes'][path]]
require(set(changed) == {
    'app/src/androidTest/java/dev/tinycast/android/ExtensionFormDeviceTest.kt',
    'app/src/androidTest/java/dev/tinycast/android/ReviewedTextDeviceTest.kt',
    'scripts/test-reviewed-text-input.py',
    'scripts/tests/test_reviewed_text_archives.py',
    'scripts/tests/test_reviewed_text_runner_guards.py',
}, 'Unexpected frozen source changes')
require(sha(parent / 'candidate/app-debug.apk') == 'de99960d7658740d8377877fe19287e9a34c3fce46394367f5ac5426c1d5e18d', 'Parent APK changed')
out.mkdir(exist_ok=False)
state = dict(completed=False, pid=os.getpid(), source_commit=head,
             test_source_commit=head, runtime_source_commit=runtime,
             source_hashes=sources, changed_sources_from_parent=changed,
             parent_test_source_commit=prior['test_source_commit'], commands=[], runs=[],
             runtime_checks_reused='Production/build sources equal verified parent; JVM278 retained, not rerun')

def save():
    (out / 'state.json').write_text(json.dumps(state, indent=2) + '\n')

save()
env = dict(os.environ, JAVA_HOME='<user>/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home',
           ANDROID_HOME='<user>/Library/Android/sdk')
for variant in ['debug', 'benchmark']:
    command = ['./gradlew', ':app:assemble' + variant.capitalize() + 'AndroidTest',
               ':app:lint' + variant.capitalize(), '-PdroidcastTestBuildType=' + variant,
               '-PdroidcastBuildCommit=' + runtime, '--console=plain']
    state['commands'].append(command)
    save()
    with (out / (variant + '-build.log')).open('wb') as log:
        result = subprocess.run(command, env=env, stdout=log, stderr=subprocess.STDOUT)
    state['runs'].append(dict(variant=variant, exit_code=result.returncode))
    save()
    require(result.returncode == 0, variant + ' harness compilation failed')
    target = out / ('candidate' if variant == 'debug' else 'benchmark')
    target.mkdir()
    name = 'app-' + variant + '-androidTest.apk'
    shutil.copyfile(Path('app/build/outputs/apk/androidTest') / variant / name, target / name)
    app_name = 'app-' + variant + '.apk'
    shutil.copyfile(parent / target.name / app_name, target / app_name)
    state['apk_hashes' if variant == 'debug' else 'benchmark_apk_hashes'] = {path.name: sha(path) for path in target.iterdir()}
    with zipfile.ZipFile(parent / target.name / name) as old, zipfile.ZipFile(target / name) as new:
        require(set(old.namelist()) == set(new.namelist()), 'APK paths differ')
        state[variant + '_test_apk_changed_payloads'] = [name for name in old.namelist() if old.read(name) != new.read(name)]
    save()
reports = out / 'reports'
reports.mkdir()
state['lint'] = {}
for variant in ['debug', 'benchmark']:
    path = Path('app/build/reports/lint-results-' + variant + '.xml')
    root = E.parse(path).getroot()
    counts = {severity: sum(row.get('severity') == severity for row in root.findall('issue')) for severity in ['Error', 'Fatal', 'Warning']}
    require(not counts['Error'] and not counts['Fatal'], 'Lint errors')
    state['lint'][variant] = counts
    shutil.copyfile(path, reports / path.name)
require(all(sha(path) == digest for path, digest in sources.items()), 'Sources changed during build')
require(head == git('rev-parse', 'HEAD') and not git('status', '--porcelain=v1', '--untracked-files=all'), 'Source identity changed')
state['completed'] = True
save()
print(json.dumps({key: state[key] for key in ['completed', 'source_commit', 'runtime_source_commit', 'apk_hashes', 'lint']}, indent=2))
