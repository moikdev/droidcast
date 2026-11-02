import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


RUNNER = Path(__file__).resolve().parents[1] / 'test-extension-workflows.py'
FAKE_ADB = '''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
args=sys.argv[1:]
with Path(os.environ['DROIDCAST_GUARD_LOG']).open('a') as out:
 out.write(json.dumps(args)+'\\n')
command=args[2:]
mode=os.environ['DROIDCAST_GUARD_CASE']
responses={
 ('shell','getprop','ro.kernel.qemu'):'1',
 ('shell','getprop','ro.boot.qemu.avd_name'):'Other_AVD' if mode=='wrong-avd' else 'Tinycast_Tablet_API_36',
 ('shell','id'):'uid=0(root)' if mode=='root-adbd' else 'uid=2000(shell)',
 ('shell','dumpsys','activity','processes'):'ActiveInstrumentation{owned-by-another-run}' if mode=='live-instrumentation' else '',
}
if tuple(command) not in responses:
 sys.exit('Unexpected command past the ownership guard')
print(responses[tuple(command)])
'''


class WorkflowGuardTests(unittest.TestCase):
    def run_guard(self, mode, serial='emulator-5580', avd='Tinycast_Tablet_API_36'):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sdk = root / 'fake-sdk'
            tools = sdk / 'platform-tools'
            tools.mkdir(parents=True)
            adb = tools / 'adb'
            adb.write_text(FAKE_ADB)
            adb.chmod(0o700)
            log = root / 'calls.jsonl'
            output = root / 'output'
            command = [sys.executable, '-O', str(RUNNER), '--serial', serial, '--avd', avd,
                       '--layout', 'tablet', '--output', str(output), '--bundle-snapshot', str(root / 'missing.json'),
                       '--apk', str(root / 'app.apk'), '--test-apk', str(root / 'test.apk')]
            result = subprocess.run(command, capture_output=True, text=True, timeout=15,
                                    env=dict(os.environ, ANDROID_HOME=str(sdk), DROIDCAST_GUARD_LOG=str(log), DROIDCAST_GUARD_CASE=mode))
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(output.exists(), 'Ownership rejection precedes output/data mutation')
            calls = [json.loads(line)[2:] for line in log.read_text().splitlines()] if log.exists() else []
            allowed = [['shell', 'getprop', 'ro.kernel.qemu'], ['shell', 'getprop', 'ro.boot.qemu.avd_name'],
                       ['shell', 'id'], ['shell', 'dumpsys', 'activity', 'processes']]
            self.assertTrue(all(call in allowed for call in calls), calls)
            return calls, result.stderr

    def test_wrong_target_is_rejected_without_even_calling_adb_under_optimization(self):
        calls, error = self.run_guard('normal', serial='physical-device', avd='Other_AVD')
        self.assertEqual([], calls)
        self.assertIn('Only the dedicated emulator', error)

    def test_another_avd_is_read_only_and_rejected_under_optimization(self):
        calls, error = self.run_guard('wrong-avd')
        self.assertEqual(2, len(calls))
        self.assertIn('Emulator identity changed', error)

    def test_root_adbd_cannot_reach_install_or_fixture_mutation_under_optimization(self):
        calls, error = self.run_guard('root-adbd')
        self.assertEqual(3, len(calls))
        self.assertIn('adbd must be unprivileged', error)

    def test_live_instrumentation_cannot_be_stopped_or_replaced_under_optimization(self):
        calls, error = self.run_guard('live-instrumentation')
        self.assertEqual(4, len(calls))
        self.assertIn('Another instrumentation owns the emulator', error)


if __name__ == '__main__':
    unittest.main()
