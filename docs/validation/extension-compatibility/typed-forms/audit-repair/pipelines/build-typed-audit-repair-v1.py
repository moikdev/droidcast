from pathlib import Path
import hashlib, json, os, shutil, subprocess, xml.etree.ElementTree as ET

b = Path('artifacts/form-pickers')
state = b / 'typed-build-audit-repair-v1-state.json'
assert not state.exists()
assert not subprocess.check_output(['git', 'status', '--porcelain=v1'], text=True).strip()
head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
sha = lambda p: hashlib.sha256(Path(p).read_bytes()).hexdigest()
old = json.loads((b / 'typed-build-ime-layout-v4-state.json').read_text())
common = json.loads((b / 'file-workflow-harness-v29-state.json').read_text())
assert old['completed'] and common['completed'] and common['exit_code'] == 0
files = subprocess.check_output(['git', 'ls-files', 'app/src', 'scripts', 'app/build.gradle.kts',
    'build.gradle.kts', 'gradle.properties', 'settings.gradle.kts'], text=True).splitlines()
hashes = {p: sha(p) for p in files}
expected = {
    'app/src/androidTest/java/dev/tinycast/android/ExtensionFormDeviceTest.kt',
    'app/src/main/java/dev/tinycast/android/extensions/ExtensionFormDateUi.kt',
    'app/src/main/java/dev/tinycast/android/extensions/ExtensionFormUi.kt',
}
assert {p for p, h in old['source_hashes'].items() if sha(p) != h} == expected
assert {p for p, h in common['source_hashes'].items() if sha(p) != h} == expected - {
    'app/src/androidTest/java/dev/tinycast/android/ExtensionFormDeviceTest.kt'}
command = ['./gradlew', '--no-daemon', '-PdroidcastBuildCommit=' + head,
    ':app:testDebugUnitTest', ':app:assembleDebug', ':app:assembleDebugAndroidTest',
    ':app:assembleBenchmark', ':app:lintDebug', ':app:lintBenchmark']
record = dict(completed=False, pid=os.getpid(), source_commit=head, production_source_commit=head,
    test_source_commit=head, source_hashes=hashes, wrapper_sha256=sha(__file__), command=command,
    changed_sources_since_full_build=sorted(expected),
    retained_node_python_checks={name: old['checks'][name] for name in ['node-focus', 'node-submit', 'python', 'python-optimized']},
    retained_check_scope='25 Node and 77 Python cases per mode; every corresponding source is unchanged, not rerun.',
    common_test_source_commit=common['test_source_commit'], common_test_apk_sha256=common['test_apk_sha256'])
def save():
    state.write_text(json.dumps(record, indent=2) + '\n')
save()
log = b / 'typed-build-audit-repair-v1-gradle.log'
try:
    with log.open('x') as output:
        code = subprocess.run(command, env=dict(os.environ,
            JAVA_HOME='<home>/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home',
            ANDROID_HOME='<home>/Library/Android/sdk'), stdout=output, stderr=subprocess.STDOUT).returncode
    record.update(exit_code=code, log_sha256=sha(log))
    save()
    assert code == 0
    assert all(sha(p) == h for p, h in hashes.items())
    out = b / ('candidate-' + head[:7] + '-audit-repair-v1')
    out.mkdir(exist_ok=False)
    for source, name in [('app/build/outputs/apk/debug/app-debug.apk', 'app.apk'),
                         ('app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk', 'test.apk'),
                         ('app/build/outputs/apk/benchmark/app-benchmark.apk', 'benchmark.apk')]:
        shutil.copyfile(source, out / name)
    checks = out / 'checks'
    checks.mkdir()
    shutil.copytree('app/build/test-results/testDebugUnitTest', checks / 'jvm')
    totals = {k: 0 for k in ['tests', 'failures', 'errors', 'skipped']}
    for p in (checks / 'jvm').glob('TEST-*.xml'):
        suite = ET.parse(p).getroot()
        for k in totals:
            totals[k] += int(suite.get(k, '0'))
    assert totals == dict(tests=229, failures=0, errors=0, skipped=0)
    lint = {}
    for variant in ['debug', 'benchmark']:
        p = Path('app/build/reports/lint-results-' + variant + '.xml')
        shutil.copyfile(p, checks / p.name)
        rows = ET.parse(p).getroot().findall('issue')
        lint[variant] = {k: sum(r.get('severity') == k for r in rows) for k in ['Error', 'Fatal', 'Warning']}
        assert lint[variant]['Error'] == lint[variant]['Fatal'] == 0
    record.update(completed=True, sources_unchanged=True, candidate_directory=str(out),
        app_sha256=sha(out / 'app.apk'), frozen_app_sha256=sha(out / 'app.apk'),
        test_apk_sha256=sha(out / 'test.apk'), benchmark_apk_sha256=sha(out / 'benchmark.apk'),
        jvm=totals, jvm_task_lines=[line for line in log.read_text().splitlines() if line.startswith('> Task :app:testDebugUnitTest')],
        lint=lint, test_apk_equal_common=sha(out / 'test.apk') == common['test_apk_sha256'])
    (out / 'identity.json').write_text(json.dumps(record, indent=2) + '\n')
    save()
    print(json.dumps({k: record[k] for k in ['completed', 'source_commit', 'candidate_directory', 'jvm', 'jvm_task_lines', 'lint', 'test_apk_equal_common']}, indent=2))
except BaseException as failure:
    record.update(completed=True, exit_code=1, failure_type=type(failure).__name__,
        sources_unchanged=all(sha(p) == h for p, h in hashes.items()))
    save()
    raise
