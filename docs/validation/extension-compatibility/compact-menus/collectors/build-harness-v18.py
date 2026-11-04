from pathlib import Path
import hashlib, json, os, shutil, subprocess, zipfile

root = Path('artifacts/menu-readability/build-harness-v18'); root.mkdir(parents=True, exist_ok=False, mode=0o700)
sha = lambda data: hashlib.sha256(data).hexdigest()
previous = Path('artifacts/menu-readability/build-candidate-v6')
old = json.loads((previous / 'state.json').read_text())
if not old['completed']:
    raise RuntimeError('Prior runtime/checks incomplete')
commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
if commit != '14b8bd832b5e6bedfaa97b77de99cee02c897a6f':
    raise RuntimeError('Unexpected harness source')
sources = {path: sha(Path(path).read_bytes()) for path in old['source_hashes']}
changed = [path for path, digest in sources.items() if old['source_hashes'][path] != digest]
if changed != ['app/src/androidTest/java/dev/tinycast/android/InlinePaletteDeviceTest.kt']:
    raise RuntimeError('Only the inspected instrumentation correction may differ')
state = dict(completed=False, pid=os.getpid(), source_commit=commit, test_source_commit=commit,
    production_source_commit=old['source_commit'], source_hashes=sources, changed_sources_from_runtime=changed,
    retained_check_record=str(previous / 'state.json'), commands=[],
    scope='Fresh debug/benchmark instrumentation compilation only. Frozen runtime APKs and matching production/JVM/build/script sources retain their original bdbfc56 checks.')
def save():
    (root / 'state.json').write_text(json.dumps(state, indent=2) + '\n')
save(); pair = root / 'candidate'; pair.mkdir()
for name, key in [('app.apk', 'app_sha256'), ('benchmark.apk', 'benchmark_app_sha256')]:
    source = previous / 'candidate' / name
    if sha(source.read_bytes()) != old[key]:
        raise RuntimeError('Frozen runtime changed')
    shutil.copyfile(source, pair / name); state[key] = old[key]
for variant, extra, source, target, key in [
    ('debug', [], 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk', 'test.apk', 'test_apk_sha256'),
    ('benchmark', ['-PdroidcastTestBuildType=benchmark'], 'app/build/outputs/apk/androidTest/benchmark/app-benchmark-androidTest.apk', 'benchmark-test.apk', 'benchmark_test_apk_sha256')]:
    args = ['./gradlew', '--no-daemon', '-PdroidcastBuildCommit=' + old['source_commit'], *extra, ':app:assemble' + variant.title() + 'AndroidTest']
    command = dict(variant=variant, args=args, completed=False); state['commands'].append(command); save()
    with (root / ('build-' + variant + '.log')).open('x') as log:
        command['exit_code'] = subprocess.run(args, env=dict(os.environ,
            JAVA_HOME='<jdk>', ANDROID_HOME='<android-sdk>'),
            stdout=log, stderr=subprocess.STDOUT).returncode
    command['completed'] = command['exit_code'] == 0; save()
    if not command['completed']:
        raise RuntimeError('Inspect failed harness compilation')
    shutil.copyfile(source, pair / target); state[key] = sha((pair / target).read_bytes())
    with zipfile.ZipFile(previous / 'candidate' / target) as before, zipfile.ZipFile(pair / target) as after:
        names = set(before.namelist()) | set(after.namelist())
        state[variant + '_test_zip_payload_changes'] = sorted(name for name in names if
            (sha(before.read(name)) if name in before.namelist() else None) != (sha(after.read(name)) if name in after.namelist() else None))
    save()
state['sources_unchanged'] = all(sha(Path(path).read_bytes()) == digest for path, digest in sources.items())
state['completed'] = state['sources_unchanged'] and all(c['completed'] for c in state['commands'])
save(); print(json.dumps({k: v for k, v in state.items() if k not in ['source_hashes', 'commands']}, indent=2), flush=True)
if not state['completed']:
    raise RuntimeError('Harness identity changed')
