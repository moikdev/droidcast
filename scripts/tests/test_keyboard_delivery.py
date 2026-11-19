import copy
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from PIL import Image

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location("keyboard_delivery", SCRIPTS / "test-keyboard-delivery.py")
delivery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(delivery)


class KeyboardDeliveryTest(unittest.TestCase):
    def test_separate_assertions_detect_missing_delivery_stale_pixels_and_stale_tree(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            for name in ("before-observer", "after-observer"):
                Image.new("L", (4, 4), 0).save(folder / (name + ".png"))
            xml = folder / "hierarchy.xml"
            xml.write_text('<node editable="true" text="Q"/>')
            events = [{"key": key, "action": action, "repeat": 0, "down_ms": 0, "event_ms": 0, "received_ms": 1}
                      for key, action in (("KEYCODE_SHIFT_LEFT", 0), ("KEYCODE_Q", 0), ("KEYCODE_Q", 1), ("KEYCODE_SHIFT_LEFT", 1))]
            sample = {"fixture": "Q", "pre_ime": events, "glyph_bounds": [0, 0, 4, 4],
                      "focused": True, "overflow": False, "exact": True, "observed": "Q"}
            case = {name: copy.deepcopy(sample) for name in ("before_observer", "after_observer", "final")}

            def checked(control="none"):
                return delivery.examine(case, folder, "Q", "Q", control)[0]

            self.assertTrue(all(checked().values()))
            case["final"]["pre_ime"].pop()
            self.assertFalse(checked()["keys"])
            self.assertFalse(checked()["delivery_before_attachment"])
            case["final"] = copy.deepcopy(sample)
            self.assertFalse(checked("paint")["pixels"])
            Image.new("L", (4, 4), 255).save(folder / "before-observer.png")
            self.assertTrue(checked("paint")["pixels"])
            xml.write_text('<node editable="true" text=""/>')
            self.assertFalse(checked("paint")["hierarchy"])

    def test_root_opt_in_and_capture_mode_guards_precede_device_access(self):
        result = subprocess.run([sys.executable, "-O", str(SCRIPTS / "test-keyboard-delivery.py"),
                                 "--adb", "missing-adb-for-guard", "--serial", "emulator-5580", "--app-apk", "absent",
                                 "--test-apk", "absent", "--output", "unused"], capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Requires owned emulator hardware injection", result.stderr)
        common = [str(SCRIPTS / "test-modifier-taps-hardware.py"), "--serial", "emulator-5580",
                  "--avd", "Tinycast_Tablet_API_36", "--output", "unused", "--pre-observer-frame"]
        for options, message in (([], "requires --typing-only"), (["-O"], "Run without -O")):
            result = subprocess.run([sys.executable, *options, *common], capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn(message, result.stderr)
