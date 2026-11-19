from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import shortcut_adbd


class ShortcutDaemonTest(unittest.TestCase):
    def transition(self, operation, responses, report):
        now = [0.0]

        def advance(seconds):
            now[0] += seconds

        with patch.object(shortcut_adbd.time, "monotonic", side_effect=lambda: now[0]), \
             patch.object(shortcut_adbd.time, "sleep", side_effect=advance), \
             patch.object(shortcut_adbd, "_command", side_effect=responses) as command:
            shortcut_adbd.change_daemon(["adb", "-s", "emulator-test"], "test", operation, report)
            return [call.args[1] for call in command.call_args_list]

    def test_root_disconnect_can_precede_verified_restart(self):
        responses = [(0, b"1\ntest\n2000"), (1, b""), (1, b""), (0, b"1\ntest\n0")]
        report = {}
        self.assertEqual(self.transition("root", responses, report), ["shell", "root", "shell", "shell"])
        self.assertEqual(report["root_command_exit_codes"], [1])

    def test_unroot_retries_after_reconnecting_to_still_rooted_emulator(self):
        replies = iter([1, 0])
        uid = [b"0"]

        def respond(base, verb, *args):
            if verb == "unroot":
                code = next(replies)
                if code == 0:
                    uid[0] = b"2000"
                return code, b""
            return 0, b"1\ntest\n" + uid[0]

        report = {}
        calls = self.transition("unroot", respond, report)
        self.assertEqual(calls.count("unroot"), 2)
        self.assertEqual(report["unroot_command_exit_codes"], [1, 0])

    def test_unavailable_transport_expires_without_sending_privileged_commands(self):
        report = {}
        with self.assertRaisesRegex(RuntimeError, "Could not verify"):
            self.transition("root", lambda *args: (1, b""), report)
        self.assertEqual(report["root_command_exit_codes"], [])

    def test_mismatched_device_is_rejected_before_transition(self):
        report = {}
        with self.assertRaisesRegex(RuntimeError, "Unexpected device"):
            self.transition("root", [(0, b"1\nother\n2000")], report)
        self.assertEqual(report["root_command_exit_codes"], [])

    def test_older_image_can_verify_name_via_console(self):
        calls = self.transition("unroot", [(0, b"1\n\n2000"), (0, b"test\nOK")], {})
        self.assertEqual(calls, ["shell", "emu"])
