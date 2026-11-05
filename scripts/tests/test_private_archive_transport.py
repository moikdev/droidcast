import importlib.util
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
spec = importlib.util.spec_from_file_location('owned_archive_runner', ROOT / 'test-webview-engine.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class PrivateArchiveTransportTests(unittest.TestCase):
    def transport(self, folder, body):
        path = folder / 'fake_adb.py'
        path.write_text(body)
        return [sys.executable, str(path)]

    def test_large_capture_completes_while_parent_stdin_remains_open(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            command = self.transport(folder, """import io,sys,tarfile
assert sys.stdin.buffer.read()==b''
with tarfile.open(fileobj=sys.stdout.buffer,mode='w|') as archive:
 data=b'owned-snapshot-'*600000
 info=tarfile.TarInfo('files/owned.bin');info.size=len(data)
 archive.addfile(info,io.BytesIO(data))
""")
            destination = folder / 'private-before.tar'
            driver = """import importlib.util,json,pathlib,stat,sys
sys.path.insert(0,sys.argv[1])
spec=importlib.util.spec_from_file_location('runner',pathlib.Path(sys.argv[1])/'test-webview-engine.py')
r=importlib.util.module_from_spec(spec);spec.loader.exec_module(r)
files=r.private_app_archive(json.loads(sys.argv[2]),pathlib.Path(sys.argv[3]),timeout=5)
print(json.dumps({'length':len(files['files/owned.bin']),'mode':stat.S_IMODE(pathlib.Path(sys.argv[3]).stat().st_mode)}))
"""
            process = subprocess.Popen([sys.executable, '-c', driver, str(ROOT), json.dumps(command), str(destination)],
                                       stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            try:
                # Keep the parent's input pipe open: inheriting it would hang the sender.
                process.wait(timeout=10)
                output = process.stdout.read(); error = process.stderr.read()
                self.assertEqual(0, process.returncode, error.decode())
                self.assertEqual({'length': 9000000, 'mode': 0o600}, json.loads(output))
                self.assertFalse(destination.with_name(destination.name + '.partial').exists())
            finally:
                if process.poll() is None:
                    process.kill(); process.wait()
                for pipe in (process.stdin, process.stdout, process.stderr):
                    pipe.close()

    def test_malformed_capture_never_becomes_a_complete_snapshot(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary); destination = folder / 'private-before.tar'
            command = self.transport(folder, "import sys;sys.stdout.buffer.write(b'not an archive')")
            with self.assertRaises(Exception):
                runner.private_app_archive(command, destination)
            self.assertFalse(destination.exists())
            partial = destination.with_name(destination.name + '.partial')
            self.assertEqual(0o600, stat.S_IMODE(partial.stat().st_mode))
            with self.assertRaisesRegex(ValueError, 'existing private snapshot'):
                runner.private_app_archive(command, destination)

    def test_failed_and_timed_out_transfers_retain_only_private_partials(self):
        for body, timeout, error in [("import sys;sys.stdout.write('partial');sys.exit(7)", 5, ValueError),
                                     ("import sys,time;sys.stdout.write('partial');sys.stdout.flush();time.sleep(10)", .1, subprocess.TimeoutExpired)]:
            with self.subTest(error=error), tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary); destination = folder / 'private-before.tar'
                command = self.transport(folder, body)
                with self.assertRaises(error):
                    runner.private_app_archive(command, destination, timeout=timeout)
                self.assertFalse(destination.exists())
                self.assertEqual(0o600, stat.S_IMODE(destination.with_name(destination.name + '.partial').stat().st_mode))


if __name__ == '__main__':
    unittest.main()
