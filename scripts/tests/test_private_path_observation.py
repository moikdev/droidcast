import subprocess
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from android_test_cleanup import CleanupError, private_path_absent


class PrivatePathObservationTest(unittest.TestCase):
    def test_silent_remote_test_distinguishes_existing_and_absent_paths(self):
        self.assertFalse(private_path_absent(subprocess.CompletedProcess(['test'], 0, b'', b'')))
        self.assertTrue(private_path_absent(subprocess.CompletedProcess(['test'], 1, b'', b'')))

    def test_adb_offline_closed_and_run_as_errors_cannot_certify_absence(self):
        for message in [b'adb: device offline\n', b'error: closed\n', b'run-as: unknown package\n']:
            for stdout, stderr in [(message, b''), (b'', message)]:
                with self.assertRaises(CleanupError):
                    private_path_absent(subprocess.CompletedProcess(['adb'], 1, stdout, stderr))

    def test_uncaptured_output_and_abnormal_exit_states_remain_unknown(self):
        for result in [subprocess.CompletedProcess(['adb'], 1), subprocess.CompletedProcess(['adb'], 255, b'', b''),
                       subprocess.CompletedProcess(['adb'], -9, b'', b''), subprocess.CompletedProcess(['adb'], True, b'', b''),
                       subprocess.CompletedProcess(['adb'], 0, b'error: device disconnected', b'')]:
            with self.assertRaises(CleanupError):
                private_path_absent(result)


if __name__ == '__main__':
    unittest.main()
