#!/usr/bin/env python3
"""Opt-in startup baseline on one owned emulator; no physical latency claims."""
from pathlib import Path
import argparse, hashlib, json, os, platform, re, subprocess, time
from android_test_cleanup import Cleanup

p = argparse.ArgumentParser()
p.add_argument('--serial', required=True)
p.add_argument('--avd', required=True)
p.add_argument('--apk-sha256', required=True)
p.add_argument('--layout', choices=['tablet', 'phone'], required=True)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--samples', type=int, default=30)
p.add_argument('--package', choices=['dev.tinycast.android','dev.tinycast.android.benchmark'], default='dev.tinycast.android')
a = p.parse_args()
assert 5 <= a.samples <= 100 and re.fullmatch('[a-f0-9]{64}', a.apk_sha256)
assert a.serial.startswith('emulator-')
sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
if not sdk:
    sdk = next(x.removeprefix('sdk.dir=') for x in Path('local.properties').read_text().splitlines() if x.startswith('sdk.dir='))
adb = [str(Path(sdk)/'platform-tools/adb'), '-s', a.serial]
app = a.package
def raw(*args):
    return subprocess.check_output(adb + list(args), stderr=subprocess.STDOUT, timeout=60)
def shell(*args):
    return raw('shell', *args).decode().strip()
assert shell('getprop', 'ro.kernel.qemu') == '1'
assert shell('getprop', 'ro.boot.qemu.avd_name') == a.avd
assert shell('id').startswith('uid=2000')
assert 'ActiveInstrumentation{' not in shell('dumpsys', 'activity', 'processes')
size, density = shell('wm', 'size'), shell('wm', 'density')
assert 'Override' not in size + density
assert shell('settings', 'get', 'secure', 'enabled_accessibility_services') == 'null'
installed = shell('pm', 'path', app).removeprefix('package:')
assert hashlib.sha256(raw('exec-out', 'cat', installed)).hexdigest() == a.apk_sha256
if app == 'dev.tinycast.android':
    assert not shell('run-as', app, 'id').startswith(('uid=0', 'uid=1000(', 'uid=2000'))
a.output.mkdir(parents=True, exist_ok=False)
result = dict(serial=a.serial, avd=a.avd, layout=a.layout, apk_sha256=a.apk_sha256,
              samples_per_mode=a.samples, original_size=size, original_density=density,
              api=shell('getprop', 'ro.build.version.sdk'), abi=shell('getprop', 'ro.product.cpu.abi'),
              host_machine=platform.machine(), host_cpu_count=os.cpu_count(), host_load_start=os.getloadavg(),
              method='am start -W; force-stop process cold, retained-process Home/foreground hot; OS caches not evicted',
              completed=False, observations=[])
(a.output/'runner.py').write_bytes(Path(__file__).read_bytes())
try:
    if a.layout == 'phone':
        shell('wm', 'size', '1080x2340'); shell('wm', 'density', '420')
    result['effective_size'], result['effective_density'] = shell('wm', 'size'), shell('wm', 'density')
    for mode in ['cold', 'hot']:
        for i in range(a.samples + 3):
            before = shell('pidof', app) if mode == 'hot' else ''
            if mode == 'cold':
                shell('am', 'force-stop', app)
            else:
                shell('input', 'keyevent', 'KEYCODE_HOME')
            started = time.monotonic_ns()
            text = shell('am', 'start', '-W', '-n', app+'/.MainActivity')
            host_ms = (time.monotonic_ns()-started)/1e6
            (a.output/f'{mode}-{i:02d}.txt').write_text(text+'\n')
            assert 'Status: ok' in text and app+'/.MainActivity' in text, text
            values = {k: v.strip() for k,v in re.findall(r'^([A-Za-z]+):\s*(.*)$', text, re.M)}
            state = values.get('LaunchState')
            assert (state == 'COLD') if mode == 'cold' else (state == 'HOT' or (state == 'UNKNOWN (0)' and 'current task has been brought to the front' in text)), text
            after = shell('pidof', app)
            assert after and (mode == 'cold' or before == after)
            result['observations'].append(dict(mode=mode, warmup=i<3, launch_state=state,
                total_ms=int(values['TotalTime']) if 'TotalTime' in values else None, wait_ms=int(values['WaitTime']), host_command_ms=host_ms))
    result['memory_after'] = shell('dumpsys', 'meminfo', app)
    result['completed'] = True
finally:
    c = Cleanup(result)
    c.attempt('stop-app', lambda: shell('am', 'force-stop', app))
    if a.layout == 'phone':
        c.attempt('size', lambda: shell('wm', 'size', 'reset'))
        c.attempt('density', lambda: shell('wm', 'density', 'reset'))
    def verify():
        c.require(size == shell('wm', 'size') and density == shell('wm', 'density'), 'geometry')
        c.require(shell('id').startswith('uid=2000'), 'adbd')
        c.require(hashlib.sha256(raw('exec-out', 'cat', installed)).hexdigest() == a.apk_sha256, 'installed APK')
    c.attempt('verify', verify)
    result['host_load_end'] = os.getloadavg()
    result['completed'] = result['completed'] and not c.errors
    (a.output/'record.json').write_text(json.dumps(result, indent=2)+'\n')
    c.finish()
print(json.dumps({k:result[k] for k in ['completed','layout','samples_per_mode','apk_sha256']}))
