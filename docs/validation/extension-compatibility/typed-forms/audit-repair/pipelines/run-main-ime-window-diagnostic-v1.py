from pathlib import Path
import json, os, re, subprocess, time

b = Path('artifacts/form-pickers')
root = b / 'main-ime-window-system-v1'
root.mkdir(mode=0o700, exist_ok=False)
state = b / 'main-ime-window-diagnostic-v1-state.json'
assert not state.exists()
record = dict(completed=False, pid=os.getpid(), samples=[],
    scope='Read-only input-method and WindowManager samples only during the active live-IME test in a complete fresh landscape cohort. Same app/test APK; no policy change, input retry or prior failure relabeling.')
def save():
    state.write_text(json.dumps(record, indent=2) + '\n')
def private(path, content):
    fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(fd, 'wb') as f:
        f.write(content)
with (root / 'engine.log').open('x') as log:
    command = ['python3', str(b / 'run-legacy-ime-window-diagnostic-v1.py'), 'legacy',
        'typed-build-audit-repair-v1-state.json', 'imewindowv1']
    process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
    record['engine_pid'] = process.pid
    save()
    index = 0
    engine = b / 'final-typed-native-legacy-imewindowv1-state.json'
    last = None
    while process.poll() is None:
        try:
            data = json.loads(engine.read_text()) if engine.exists() else {}
            cohort = data.get('cohorts', [])[-1] if data.get('cohorts') else None
        except json.JSONDecodeError:
            cohort = None
        tests = Path(cohort['output']) / 'tests.log' if cohort else None
        text = tests.read_text(errors='replace') if tests and tests.exists() else ''
        names = re.findall(r'^INSTRUMENTATION_STATUS: test=(.+)$', text, re.M)
        codes = re.findall(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)$', text, re.M)
        active = names and codes and names[-1] == 'liveImePointerSelectionSurvivesUnrelatedPublication' and codes[-1] == '1' and 'INSTRUMENTATION_CODE:' not in text
        if active and index < 35:
            started = time.time()
            outputs = {}
            for service in ['input_method', 'window']:
                args = ['windows'] if service == 'window' else []
                result = subprocess.run(['<home>/Library/Android/sdk/platform-tools/adb',
                    '-s', 'emulator-5580', 'shell', 'dumpsys', service, *args],
                    capture_output=True, timeout=15)
                private(root / f'private-{service}-{index:03}.txt', result.stdout + result.stderr)
                outputs[service] = result
            flags = [line.strip()[:180] for line in outputs['input_method'].stdout.decode(errors='replace').splitlines()
                if any(k in line for k in ['mVisibleBound=', 'mImeWindowVis=', 'mInputShown='])]
            row = dict(sample=index, time=started, active_test=names[-1], cohort=cohort['name'],
                input_method_exit_code=outputs['input_method'].returncode,
                window_exit_code=outputs['window'].returncode, flags=flags)
            record['samples'].append(row); save()
            if tuple(flags) != last:
                print(json.dumps(row), flush=True); last = tuple(flags)
            index += 1
        time.sleep(.6)
    record.update(completed=True, engine_exit_code=process.returncode)
    save()
    print(json.dumps(dict(completed=True, engine_exit_code=process.returncode, samples=index)), flush=True)
    raise SystemExit(process.returncode)
