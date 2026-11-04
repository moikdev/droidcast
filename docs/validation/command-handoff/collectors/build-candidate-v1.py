from pathlib import Path
import hashlib, json, os, shutil, subprocess, xml.etree.ElementTree as E

base = Path('artifacts/command-handoff')
out = base / 'build-candidate-v1'

def require(value, message):
    if not value:
        raise RuntimeError(message)

def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()

head = git('rev-parse', 'HEAD')
require(head == '10d8798e92dc0334f17f0eded4ab6fc5493326c6' and not git('status', '--porcelain=v1', '--untracked-files=all'), 'Source is not frozen')
parent = json.loads(Path('docs/validation/context-workflows/checkpoint.json').read_text())
paths = sorted(set(parent['source_hashes']) | {
    'app/src/main/java/dev/tinycast/android/extensions/ExtensionCommandLaunch.kt',
    'app/src/test/java/dev/tinycast/android/ExtensionCommandLaunchTest.kt',
})
sources = {path: sha(path) for path in paths}
require(not git('diff', '--name-only', '517a650ddb4d1eb8509ce887b6979a06b774b625', head, '--', 'app/src/main/assets'), 'Packaged runtime assets changed')
out.mkdir(exist_ok=False)
state = dict(completed=False, pid=os.getpid(), source_commit=head, runtime_source_commit=head, test_source_commit=head,
             source_hashes=sources, commands=[], runs=[])

def save():
    (out / 'state.json').write_text(json.dumps(state, indent=2) + '\n')

save()
env = dict(os.environ, JAVA_HOME='<user>/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home',
           ANDROID_HOME='<user>/Library/Android/sdk')
for variant in ['debug', 'benchmark']:
    tasks = [':app:assemble' + variant.capitalize(), ':app:assemble' + variant.capitalize() + 'AndroidTest', ':app:lint' + variant.capitalize()]
    if variant == 'debug':
        tasks.insert(0, ':app:testDebugUnitTest')
    command = ['./gradlew', *tasks, '-PdroidcastTestBuildType=' + variant, '-PdroidcastBuildCommit=' + head, '--console=plain']
    state['commands'].append(command)
    save()
    with (out / (variant + '-build.log')).open('wb') as log:
        process = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, env=env)
    state['runs'].append(dict(variant=variant, exit_code=process.returncode))
    save()
    require(process.returncode == 0, variant + ' build failed')
    target = out / ('candidate' if variant == 'debug' else 'benchmark')
    target.mkdir()
    for source in [Path('app/build/outputs/apk') / variant / ('app-' + variant + '.apk'),
                   Path('app/build/outputs/apk/androidTest') / variant / ('app-' + variant + '-androidTest.apk')]:
        shutil.copyfile(source, target / source.name)
    state['apk_hashes' if variant == 'debug' else 'benchmark_apk_hashes'] = {path.name: sha(path) for path in target.iterdir()}
    save()
reports = out / 'reports'
reports.mkdir()
state['lint'] = {}
for variant in ['debug', 'benchmark']:
    source = Path('app/build/reports/lint-results-' + variant + '.xml')
    root = E.parse(source).getroot()
    counts = {severity: sum(issue.get('severity') == severity for issue in root.findall('issue')) for severity in ['Error', 'Fatal', 'Warning']}
    require(not counts['Error'] and not counts['Fatal'], 'Lint errors')
    state['lint'][variant] = counts
    shutil.copyfile(source, reports / source.name)
jvm = reports / 'jvm'
jvm.mkdir()
cases = []
for source in sorted(Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')):
    root = E.parse(source).getroot()
    require(not root.findall('.//failure') and not root.findall('.//error') and not root.findall('.//skipped'), 'JVM cases failed or skipped')
    cases.extend(case.get('classname') + '#' + case.get('name') for case in root.findall('testcase'))
    shutil.copyfile(source, jvm / source.name)
require(len(cases) == 289 and len(cases) == len(set(cases)), 'Unexpected JVM case accounting')
state['jvm_cases'] = cases
require(all(sha(path) == digest for path, digest in sources.items()) and head == git('rev-parse', 'HEAD') and not git('status', '--porcelain=v1', '--untracked-files=all'), 'Sources changed during build')
state['completed'] = True
save()
print(json.dumps({key: state[key] for key in ['completed', 'source_commit', 'apk_hashes', 'lint']}, indent=2))
print('JVM cases:', len(cases))
