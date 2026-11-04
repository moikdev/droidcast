from pathlib import Path, PurePosixPath
import hashlib, io, json, subprocess, tarfile

BASE = Path('artifacts/command-handoff')
ADB = ['<user>/Library/Android/sdk/platform-tools/adb', '-s', 'emulator-5580']
APP = 'dev.tinycast.android'

def require(value, message):
    if not value:
        raise RuntimeError(message)

def raw(*values):
    return subprocess.check_output(ADB + list(values), timeout=60, stderr=subprocess.STDOUT)

def text(*values):
    return raw(*values).decode().strip()

def absent(path):
    result = subprocess.run(ADB + ['shell', 'run-as', APP, 'test', '-e', path], capture_output=True, timeout=20)
    require(result.returncode in [0, 1] and not result.stdout and not result.stderr, 'Path absence is unknown')
    return result.returncode == 1

def idle():
    require(text('shell', 'getprop', 'ro.boot.qemu.avd_name') == 'Tinycast_Tablet_API_36', 'Wrong emulator')
    require(text('shell', 'id').startswith('uid=2000('), 'Privileged adbd')
    require('ActiveInstrumentation{' not in text('shell', 'dumpsys', 'activity', 'processes'), 'Instrumentation active')
    for path in ['files/reviewed-text-fixture-lease', 'files/reviewed-text-evidence', 'files/empty-view-fixture-lease', 'files/compatibility-batch-fixtures']:
        require(absent(path), 'Prior fixture ownership or evidence remains')

def hashes(roots):
    data = raw('exec-out', 'run-as', APP, 'tar', '-cf', '-', *roots)
    result = {}
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        for member in archive:
            path = PurePosixPath(member.name)
            require(not path.is_absolute() and '..' not in path.parts, 'Unsafe snapshot member')
            if member.isdir():
                continue
            require(member.isfile() and member.name not in result and any(member.name.startswith(root + '/') for root in roots), 'Unexpected snapshot member')
            result[member.name] = hashlib.sha256(archive.extractfile(member).read()).hexdigest()
    return result

def verify():
    idle()
    counts = {}
    for name in ['durable', 'evidence']:
        expected = json.loads((BASE / ('private-' + name + '-before-v1.json')).read_text())
        require(text('shell', 'cat', '/proc/sys/kernel/random/boot_id') == expected['boot_id'], 'Emulator boot changed')
        require(hashes(expected['roots']) == expected['hashes'], 'Pre-existing data/evidence changed')
        counts[name + '_file_count'] = len(expected['hashes'])
    return counts

if __name__ == '__main__':
    idle()
    parent = Path('<user>/.t3/scratch/droidcast-context-workflows-27caf3/artifacts/context-workflows')
    boot = json.loads((parent / 'private-durable-before-v1.json').read_text())['boot_id']
    require(text('shell', 'cat', '/proc/sys/kernel/random/boot_id') == boot, 'Parent boot changed')
    for name in ['durable', 'evidence']:
        destination = BASE / ('private-' + name + '-before-v1.json')
        expected = json.loads((parent / destination.name).read_text())
        current = hashes(expected['roots'])
        require(current == expected['hashes'], 'Parent preservation baseline does not match')
        snapshot = dict(boot_id=boot, roots=expected['roots'], hashes=current)
        if destination.exists():
            require(json.loads(destination.read_text()) == snapshot, 'Existing private baseline differs')
        else:
            with destination.open('x') as output:
                destination.chmod(0o600)
                json.dump(snapshot, output, indent=2)
                output.write('\n')
    print(json.dumps(dict(verified=True, **verify())))
