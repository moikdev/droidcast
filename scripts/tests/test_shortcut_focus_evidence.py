import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from shortcut_focus_evidence import app_windows, focus_slot, project_events, route, trace_checks


class SettingsTraceTest(unittest.TestCase):
    def test_default_display_empty_unknown_and_nearby_packages(self):
        self.assertEqual("none", focus_slot("  FocusedWindows:\n  Next section:\n", "FocusedWindows"))
        self.assertEqual("unreported", focus_slot("  FocusedWindows: unexpected format\n", "FocusedWindows"))
        self.assertEqual("unreported", focus_slot("  FocusedWindows:\n    displayId=1, name=private\n", "FocusedWindows"))
        self.assertEqual("other", route("example.dev.tinycast.android/.MainActivity"))
        self.assertEqual("MainActivity", route("u0 dev.tinycast.android/dev.tinycast.android.MainActivity}"))

    def test_content_stays_out_of_projected_events_and_window_samples(self):
        events = project_events(
            " 100.001 123 456 I input_focus: [Focus entering abcd dev.tinycast.android/.MainActivity,secret-note]\n"
            " 100.002 123 456 I am_anr: [0,1,unrelated,0,dev.tinycast.android/.MainActivity]\n"
            " 100.003 123 456 I am_anr: [0,1,dev.tinycast.android,0,Application does not have a focused window. secret-note]\n"
            " 109.000 123 456 I input_focus: [Focus entering dev.tinycast.android/.SettingsActivity]\n",
            100, 105)
        self.assertEqual([("input_focus", False), ("am_anr", True)], [(e["event"], e["no_focused_window"]) for e in events])
        windows = app_windows("  Window #1 Window{abcd dev.tinycast.android/.SettingsActivity}:\n"
                              "    mHasSurface=true isReadyForDisplay()=false private-text\n"
                              "    mDrawState=DRAW_PENDING\n  Window #2 other:\n    mHasSurface=false\n")
        self.assertEqual(("SettingsActivity", "true", "false", "DRAW_PENDING"), tuple(windows[0][k] for k in ("activity","surface","ready","draw")))
        self.assertNotIn("secret-note", json.dumps(events))
        self.assertNotIn("private-text", json.dumps(windows))

    def test_missing_reversed_or_anr_evidence_fails_independently_of_ui_success(self):
        record = dict(samples=[], marks=[], route_assertions_completed=True, sampler_limit_reached=False,
                      events=[dict(event="input_focus", focus="entering", activity=a) for a in ("SettingsActivity","MainActivity")])
        checks = trace_checks(record)
        self.assertTrue(checks["route_completed"])
        self.assertFalse(checks["complete_collection"])
        self.assertFalse(checks["input_focus_palette_then_settings"])
        record["events"].append(dict(event="input_focus", focus="entering", activity="SettingsActivity"))
        self.assertTrue(trace_checks(record)["input_focus_palette_then_settings"])
        record["events"].append(dict(event="am_anr"))
        self.assertFalse(trace_checks(record)["no_app_anr_event_in_interval"])
