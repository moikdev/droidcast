"""Collect a bounded, content-free trace of Droidcast's Settings/palette keyboard transition."""
import json
import os
import re
import subprocess
import threading
import time

COMPONENTS = ("MainActivity", "SettingsActivity")
ROUTES = {name: re.compile(r'(?<![\w./])dev\.tinycast\.android(?:/(?:dev\.tinycast\.android)?)?\.' + name + r'(?![\w.])')
          for name in COMPONENTS}
EVENTS = ("wm_set_resumed_activity", "wm_on_resume_called", "wm_on_paused_called", "input_focus", "am_anr")


def route(value):
    if value is None:
        return "unreported"
    for name, pattern in ROUTES.items():
        if pattern.search(value):
            return name
    if value.strip() in ("null", "<none>", "<empty>"):
        return "none"
    return "other"


def field(data, pattern):
    match = re.search(pattern, data, re.M)
    return match.group(1) if match else None


def focus_slot(data, label):
    section = re.search(r'^  ' + label + r':([^\n]*)\n((?:    [^\n]*\n)*)', data + '\n', re.M)
    if not section:
        return "unreported"
    entry = field(section[2], r'^    displayId=0, name=(.*)$')
    if entry:
        return route(entry)
    if not section[2].strip() and section[1].strip() in ("", "<none>", "<empty>"):
        return "none"
    return "unreported"


def input_sample(data):
    return dict(application=focus_slot(data, "FocusedApplications"), window=focus_slot(data, "FocusedWindows"),
                enabled=field(data, r'^  DispatchEnabled: (true|false)$'),
                frozen=field(data, r'^  DispatchFrozen: (true|false)$'),
                filter=field(data, r'^  InputFilterEnabled: (true|false)$'),
                pending_empty=empty_field(data, "PendingEvent", "<none>"),
                inbound_empty=empty_field(data, "InboundQueue", "<empty>"))


def empty_field(data, name, empty):
    value = field(data, r'^  ' + name + r':(.*)$')
    return None if value is None else value.strip() == empty


def app_windows(data):
    windows = []
    for block in re.split(r'^  Window #\d+ ', data, flags=re.M)[1:]:
        kind = route(block.splitlines()[0])
        if kind in COMPONENTS:
            windows.append(dict(activity=kind, surface=field(block, r'mHasSurface=(true|false)'),
                                ready=field(block, r'isReadyForDisplay\(\)=(true|false)'),
                                visible=field(block, r'^    isVisible=(true|false)'),
                                draw=field(block, r'mDrawState=([A-Z_]+)')))
    return windows


def project_events(data, start, end):
    result = []
    for line in data.splitlines():
        match = re.match(r'\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+(\w+)\s*:(.*)', line)
        if not match or not start <= float(match[1]) <= end or match[2] not in EVENTS:
            continue
        payload = match[3]
        kind = route(payload)
        if match[2] == "am_anr":
            if not re.match(r'\s*\[\d+,\d+,dev\.tinycast\.android,', payload):
                continue
        elif kind not in COMPONENTS:
            continue
        state = next((x for x in ("request", "entering", "leaving") if "Focus " + x + " " in payload), None)
        result.append(dict(epoch_seconds=float(match[1]), event=match[2], activity=kind, focus=state,
                           no_focused_window="does not have a focused window" in payload.lower()))
    return result


def trace_checks(record):
    """An observed UI pass can earn trace credit only when its diagnostic witnesses are present."""
    events = record.get("events", [])
    samples = record["samples"]
    checks = {
        "route_completed": record["route_assertions_completed"],
        "complete_collection": bool(samples) and not record["sampler_limit_reached"]
        and "event_error" not in record and all("error" not in sample for sample in samples),
        "both_chords_marked": [m["phase"] for m in record["marks"]] == [
            "before_ctrl_f", "before_global_chord", "before_ctrl_comma",
            "ctrl_comma_injection_returned", "destination_verified"],
        "no_app_anr_event_in_interval": not any(e["event"] == "am_anr" for e in events),
    }
    for tag in ("input_focus", "wm_on_resume_called"):
        activities = [e["activity"] for e in events if e["event"] == tag
                      and (tag != "input_focus" or e["focus"] == "entering")]
        checks[tag + "_palette_then_settings"] = any(
            activity == "MainActivity" and "SettingsActivity" in activities[index + 1:]
            for index, activity in enumerate(activities))
    for activity in COMPONENTS:
        checks[activity + "_resumed"] = any(sample.get("resumed") == activity for sample in samples)
        checks[activity + "_drawn"] = any(
            window["activity"] == activity and window["ready"] == "true"
            and window["surface"] == "true" and window["draw"] == "HAS_DRAWN"
            for sample in samples for window in sample.get("windows", []))
        checks[activity + "_input"] = any(
            info.get("application") == info.get("window") == activity
            and info.get("enabled") == info.get("filter") == "true" and info.get("frozen") == "false"
            for info in (sample.get("dispatch", {}) for sample in samples))
    return checks


class SettingsFocusTrace:
    def __init__(self, adb, output):
        self.adb = adb
        self.output = output
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self.collect, daemon=True)
        self.samples = []
        self.marks = []
        self.truncated = False
        self.origin = time.monotonic_ns()

    def ms(self):
        return (time.monotonic_ns() - self.origin) // 1_000_000

    def read(self, *arguments):
        return subprocess.check_output(self.adb + list(arguments), text=True, stderr=subprocess.DEVNULL, timeout=5)

    def epoch(self):
        return float(self.read("shell", "date", "+%s.%N").strip())

    def mark(self, phase):
        self.marks.append({"phase": phase, "host_ms": self.ms()})

    def __enter__(self):
        self.start = self.epoch()
        self.thread.start()
        return self

    def collect(self):
        while not self.stop.is_set() and len(self.samples) < 160:
            sample = dict(start_ms=self.ms(), host_load_1m=os.getloadavg()[0])
            try:
                value = self.read("shell", "dumpsys", "input")
                sample.update(dispatch=input_sample(value), input_done_ms=self.ms())
                value = self.read("shell", "dumpsys", "activity", "activities")
                sample.update(resumed=route(field(value, r'^\s*topResumedActivity=(.*)$')),
                              window_focus=route(field(value, r'^\s*mCurrentFocus=(.*)$')), activity_done_ms=self.ms())
                sample["windows"] = app_windows(self.read("shell", "dumpsys", "window", "windows"))
                sample["device_load_1m"] = float(self.read("shell", "cat", "/proc/loadavg").split()[0])
            except (subprocess.SubprocessError, ValueError, IndexError) as error:
                sample["error"] = type(error).__name__
            sample["end_ms"] = self.ms()
            self.samples.append(sample)
            self.stop.wait(.25)
        self.truncated = len(self.samples) == 160 and not self.stop.is_set()

    def __exit__(self, exc_type, error, tb):
        self.stop.set()
        self.thread.join()
        record = dict(marks=self.marks, samples=self.samples, sampler_limit_reached=self.truncated,
                      route_assertions_completed=exc_type is None)
        try:
            end = self.epoch()
            # Only export an allowlisted projection of system events for the two app windows.
            logs = self.read("logcat", "-b", "events", "-d", "-v", "epoch", "-s", *EVENTS)
            record.update(epoch_bounds=[self.start, end], events=project_events(logs, self.start, end))
        except (ValueError, subprocess.SubprocessError) as failure:
            record["event_error"] = type(failure).__name__
        checks = trace_checks(record)
        record["checks"] = checks
        self.output.write_text(json.dumps(record, indent=2) + "\n")
        if exc_type is None and not all(checks.values()):
            raise AssertionError("Incomplete Settings focus evidence: " + ", ".join(k for k, ok in checks.items() if not ok))
