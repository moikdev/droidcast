#!/usr/bin/env python3
"""Collect an owned-emulator font/viewport matrix without hiding baseline failures."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess
import sys
import time

PROFILES = {
    'tablet': ('tablet', None, None),
    'tablet-portrait': ('tablet', '1200x1920', 240),
    'phone': ('phone', None, None),
    'narrow': ('phone', '840x1600', 420),
    'phone-landscape': ('phone', '2340x1080', 420),
}
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True)
p.add_argument('--avd', required=True)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--apk', type=Path, required=True)
p.add_argument('--test-apk', type=Path, required=True)
p.add_argument('--profiles', default=','.join(PROFILES))
p.add_argument('--fonts', default='1.0,1.3,1.5,2.0')
p.add_argument('--baseline', action='store_true', help='Collect all stages after layout failures; still return a failing exit status')
a = p.parse_args()
profiles = a.profiles.split(',')
fonts = [float(value) for value in a.fonts.split(',')]
assert profiles and len(profiles) == len(set(profiles)) and all(value in PROFILES for value in profiles)
assert fonts and len(fonts) == len(set(fonts)) and all(1.0 <= value <= 2.0 for value in fonts)
assert a.avd == 'Tinycast_Tablet_API_36' and a.serial.startswith('emulator-')
sha = lambda path: hashlib.sha256(Path(path).read_bytes()).hexdigest()
runner = Path(__file__).with_name('test-extension-grid.py')
frozen = {str(path): sha(path) for path in [a.apk, a.test_apk, runner, Path(__file__)]}
a.output.mkdir(parents=True, exist_ok=False, mode=0o700)
record = {
    'pid': os.getpid(), 'started_at': time.time(),
    'kind': 'baseline' if a.baseline else 'candidate',
    'apk_sha256': sha(a.apk), 'test_apk_sha256': sha(a.test_apk),
    'frozen_inputs': frozen, 'runs': [], 'collection_complete': False,
    'all_suites_passed': False,
    'viewport_limit': 'Display overrides exercise layout; they do not certify Android split-screen gestures, physical devices or OEM behavior.',
}
def save():
    (a.output / 'matrix.json').write_text(json.dumps(record, indent=2) + '\n')
try:
    for profile in profiles:
        layout, size, density = PROFILES[profile]
        for font in fonts:
            assert all(sha(path) == value for path, value in frozen.items()), 'Matrix inputs changed'
            name = profile + '-font-' + str(font).replace('.', '-')
            record['current_stage'] = name
            save()
            args = [sys.executable, str(runner), '--serial', a.serial, '--avd', a.avd,
                    '--layout', layout, '--font-scale', str(font), '--output', str(a.output / name),
                    '--apk', str(a.apk), '--test-apk', str(a.test_apk),
                    '--classes', 'dev.tinycast.android.TouchReadabilityDeviceTest', '--expected', '6',
                    '--evidence-dir', 'readiness-evidence']
            if size is not None:
                args += ['--size', size, '--density', str(density)]
            with (a.output / (name + '.log')).open('wb') as log:
                result = subprocess.run(args, stdout=log, stderr=subprocess.STDOUT)
            path = a.output / name / 'record.json'
            outcome = json.loads(path.read_text())
            cleanup = bool(outcome.get('cleanup')) and all(value['passed'] for value in outcome['cleanup'].values())
            passed = result.returncode == 0 and outcome['completed'] and outcome.get('passes') == 6
            entry = {'stage': name, 'exit': result.returncode, 'passed': passed,
                     'passes': outcome.get('passes'), 'expected': 6, 'cleanup_verified': cleanup,
                     'record_sha256': sha(path)}
            record['runs'].append(entry)
            save()
            print(json.dumps(entry), flush=True)
            assert cleanup, 'Stop the matrix until restoration is verified'
            if not passed and not a.baseline:
                raise AssertionError('Candidate layout checks failed at ' + name)
    record['collection_complete'] = True
    record['all_suites_passed'] = all(value['passed'] for value in record['runs'])
    record['current_stage'] = 'completed'
    save()
except BaseException as error:
    record['failure_type'] = type(error).__name__
    save()
    raise
sys.exit(0 if record['all_suites_passed'] else 1)
