#!/usr/bin/env python3
"""Compare delivery of two public fixtures on the dedicated tablet. Requires installed, pinned debug APKs."""
import argparse
import hashlib
import itertools
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

from PIL import Image
from android_test_cleanup import Cleanup, transition_adbd

APP = "dev.tinycast.android"
TEST = APP + ".test"
SERVICE = f"{APP}/{APP}.PaletteShortcutService"
COMPONENT = f"{TEST}/{APP}.KeyboardObservationInstrumentation"
FIXTURES = {"short": "qwerty", "long": "menuKeyboardProbe271828"}
LINUX = {"m": 50, "e": 18, "n": 49, "u": 22, "k": 37, "y": 21, "b": 48, "o": 24, "a": 30,
         "r": 19, "d": 32, "p": 25, "2": 3, "7": 8, "1": 2, "8": 9, "q": 16, "w": 17, "t": 20}
SETTINGS = ("enabled_accessibility_services", "accessibility_enabled")
LEASE = "cache/keyboard-delivery-owner"
SHIFT = {"KEYCODE_SHIFT_LEFT", "KEYCODE_SHIFT_RIGHT"}


def require(condition, reason):
    if not condition:
        raise RuntimeError(reason)


def save(path, record):
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(record, indent=2) + "\n")
    tmp.replace(path)


def wait(check, reason):
    end = time.monotonic() + 45
    while time.monotonic() < end:
        if check():
            return
        time.sleep(0.1)
    raise TimeoutError(reason)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def ink(path, bounds):
    with Image.open(path) as im:
        left, top, right, bottom = bounds
        require(0 <= left < right <= im.width and 0 <= top < bottom <= im.height, "Invalid glyph bounds")
        histogram = im.crop(bounds).convert("L").histogram()
        return sum(histogram[:128])


def examine(case, folder, sent, expected, control):
    """Check the receiver, text model, accessibility node and sampled screenshot independently."""
    before, after, final = (case[k] for k in ("before_observer", "after_observer", "final"))
    events = final["pre_ime"]
    keys = {"downs": [], "ups": []}
    for event in events:
        require(event["action"] in (0, 1), "Unexpected key action")
        if event["key"] not in SHIFT:
            keys["downs" if event["action"] == 0 else "ups"].append(event["key"])
    order = ["KEYCODE_" + ch.upper() for ch in sent]
    xml = ET.parse(folder / "hierarchy.xml")
    labels = [node.get("text") for node in xml.iter("node") if node.get("editable") == "true"]
    pre_ink = ink(folder / "before-observer.png", before["glyph_bounds"])
    post_ink = ink(folder / "after-observer.png", after["glyph_bounds"])
    exact = expected == final["fixture"]
    return {
        "keys": keys["downs"] == order and keys["ups"] == order,
        "timing": all(e["down_ms"] <= e["event_ms"] <= e["received_ms"] and e["repeat"] == 0 for e in events),
        "fixture_owned": all(x["focused"] and not x["overflow"] for x in (before, after, final)),
        "before_text": before["observed"] == sent,
        "after_text": after["observed"] == final["observed"] == expected and final["exact"] == exact,
        "hierarchy": labels == [expected],
        "pixels": (pre_ink == 0 if control == "paint" else pre_ink >= 15) and post_ink >= 15,
    }, {"before_dark_pixels": pre_ink, "after_dark_pixels": post_ink, "hierarchy_editor_text": labels}


class Tablet:
    def __init__(self, args):
        self.adb = [args.adb, "-s", args.serial]
        self.keyboard = None

    def raw(self, *args):
        return subprocess.check_output(self.adb + list(args), timeout=45)

    def shell(self, *args):
        return self.raw("shell", shlex.join(args)).decode().strip()

    def private(self, *args):
        return self.shell("run-as", TEST, *args)

    def installed(self, package):
        path = self.shell("pm", "path", package).removeprefix("package:")
        require(path.startswith("/data/app/") and "\n" not in path, "Expected one installed APK")
        return sha(self.raw("exec-out", "cat", path))

    def consent(self):
        return [self.shell("settings", "get", "secure", key) for key in SETTINGS]

    def bound(self):
        return any("Bound services:" in line and "Service[label=Droidcast" in line
                   for line in self.shell("dumpsys", "accessibility").splitlines())

    def focus_is_receiver(self):
        return any("mCurrentFocus=" in line and TEST + "/" in line and "KeyboardObservationActivity" in line
                   for line in self.shell("dumpsys", "window").splitlines())

    def set_service(self, enabled):
        if enabled:
            self.shell("settings", "put", "secure", SETTINGS[0], SERVICE)
        else:
            self.shell("settings", "delete", "secure", SETTINGS[0])
        self.shell("settings", "put", "secure", SETTINGS[1], str(int(enabled)))
        wait(lambda: self.bound() == enabled, "Service binding did not settle")
        if enabled:
            wait(lambda: "InputFilterEnabled: true" in self.shell("dumpsys", "input"), "Missing input filter")

    def emit(self, pairs):
        require(self.keyboard and re.fullmatch(r"/dev/input/event[0-9]+", self.keyboard), "Missing hardware input")
        commands = ["set -e"]
        for code, value in pairs:
            require(code in LINUX.values() or code == 42, "Key outside synthetic fixtures")
            commands.extend((f"sendevent {self.keyboard} 1 {code} {value}", f"sendevent {self.keyboard} 0 0 0"))
        self.raw("shell", "\n".join(commands))

    def inject(self, transport, payload):
        if transport == "adb":
            self.shell("input", "text", payload)
            return
        events = []
        for ch in payload:
            if ch.isupper():
                events.append((42, 1))
            key = LINUX[ch.lower()]
            events.extend(((key, 1), (key, 0)))
            if ch.isupper():
                events.append((42, 0))
        self.emit(events)

    def snapshot(self, path):
        require(self.focus_is_receiver(), "Fixture lost focus before screenshot")
        data = self.raw("exec-out", "screencap", "-p")
        require(self.focus_is_receiver(), "Fixture lost focus during screenshot")
        path.write_bytes(data)


def trial(tablet, out, enabled, observer, transport, fixture, control):
    tablet.set_service(enabled)
    requested = FIXTURES[fixture]
    sent = {"drop": requested[:-1], "extra-r": "qwerrty"}.get(control, requested)
    expected = requested[:-1] if control == "editor-loss" else sent
    session = uuid.uuid4().hex
    folder = "cache/keyboard-observation-" + session
    tablet.private("mkdir", folder)
    name = "-".join(("on" if enabled else "off", observer, transport, fixture, control))
    dest = out / name
    dest.mkdir()
    with (dest / "instrumentation.log").open("wb") as log:
        command = ["shell", "am", "instrument", "-w", "-r", "-e", "session", session, "-e", "fixture", fixture,
                   "-e", "observer", observer, "-e", "control", control, COMPONENT]
        process = subprocess.Popen(tablet.adb + command, stdout=log, stderr=subprocess.STDOUT)
        def marker(file):
            require(process.poll() is None, "Instrumentation exited before " + file)
            return file in tablet.private("ls", folder).splitlines()
        try:
            wait(lambda: marker("ready"), "Receiver startup timed out")
            require(tablet.focus_is_receiver() and tablet.bound() == enabled, "Wrong initial receiver or service")
            start = time.monotonic_ns()
            tablet.inject(transport, sent)
            end = time.monotonic_ns()
            tablet.snapshot(dest / "before-observer.png")
            captured = time.monotonic_ns()
            tablet.private("touch", folder + "/attach")
            wait(lambda: marker("attached"), "Observer attachment timed out")
            while_attached = tablet.bound()
            tablet.snapshot(dest / "after-observer.png")
            tablet.private("touch", folder + "/finish")
            process.wait(35)
            require(process.returncode == 0 and "INSTRUMENTATION_CODE: -1" in (dest / "instrumentation.log").read_text(),
                    "Instrumentation did not complete")
            case = json.loads(tablet.private("cat", folder + "/record.json"))
            require(case["session"] == session and case["final"]["fixture"] == requested, "Mismatched fixture receipt")
            (dest / "hierarchy.xml").write_bytes(tablet.raw("exec-out", "run-as", TEST, "cat", folder + "/hierarchy.xml"))
            case.update(bound_before=enabled, bound_attached=while_attached, transport=transport, control=control,
                        submitted=sent, inject_start_ns=start, inject_end_ns=end, prescreen_end_ns=captured)
            save(dest / "case.json", case)
            checks, samples = examine(case, dest, sent, expected, control)
            checks["service"] = while_attached == (enabled and observer == "preserve")
            case.update(checks=checks, samples=samples)
            save(dest / "case.json", case)
            return {"name": name, "passed": all(checks.values())}
        finally:
            if process.poll() is None:
                # This test process is the sole user of the checked and pinned test APK.
                tablet.shell("am", "force-stop", TEST)
                process.wait(15)
            # mkdir acquired this unique trial directory before instrumentation started.
            tablet.private("rm", "-r", folder)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    parser.add_argument("--serial", required=True)
    parser.add_argument("--app-apk", type=Path, required=True)
    parser.add_argument("--test-apk", type=Path, required=True)
    parser.add_argument("--allow-adbd-root", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    require(args.allow_adbd_root and re.fullmatch(r"emulator-[0-9]+", args.serial), "Requires owned emulator hardware injection")
    tablet = Tablet(args)
    require(tablet.shell("getprop", "ro.kernel.qemu") == "1"
            and tablet.raw("emu", "avd", "name").decode().splitlines()[0] == "Tinycast_Tablet_API_36"
            and tablet.shell("getprop", "ro.product.cpu.abi") == "arm64-v8a", "Expected the dedicated API 36 arm64 tablet")
    require(tablet.shell("id", "-u") == "2000" and tablet.consent() == ["null", "0"] and not tablet.bound(),
            "Start with unprivileged adbd and consent off")
    require("ActiveInstrumentation{" not in tablet.shell("dumpsys", "activity", "processes"), "Instrumentation already active")
    record = {"app_sha256": tablet.installed(APP), "test_sha256": tablet.installed(TEST),
              "runner_sha256": sha(Path(__file__).read_bytes()), "serial": args.serial, "cases": []}
    require(record["app_sha256"] == sha(args.app_apk.read_bytes()) and record["test_sha256"] == sha(args.test_apk.read_bytes()),
            "Installed APK identity mismatch")
    require(COMPONENT + " (target=" + TEST + ")" in tablet.shell("pm", "list", "instrumentation"), "Missing isolated observer")
    os.umask(0o077)
    args.output.mkdir(exist_ok=False, parents=True)
    # The receiver never changes palette preferences. Keep the original private for independent verification/recovery.
    prefs = ("exec-out", "run-as", APP, "cat", "shared_prefs/palette.xml")
    original = tablet.raw(*prefs)
    (args.output / "private-palette-original.xml").write_bytes(original)
    nonce = uuid.uuid4().hex
    owner = LEASE + "/" + nonce
    (args.output / "private-owner").write_text(nonce)
    tablet.private("mkdir", LEASE)
    tablet.private("touch", owner)
    def owned():
        tablet.private("test", "-f", owner)
    try:
        transition_adbd(tablet.adb, True)
        devices = tablet.shell("getevent", "-pl")
        keyboard = re.search(r'add device \d+: (/dev/input/event\d+)\s+name:\s+"qwerty2"', devices)
        require(keyboard, "Dedicated tablet keyboard missing")
        tablet.keyboard = keyboard.group(1)
        cases = [(*cell, "none") for cell in itertools.product((False, True), ("preserve", "suppress"), ("adb", "linux"), FIXTURES)]
        cases += [(True, "preserve", "linux", "short", ctrl) for ctrl in ("drop", "extra-r", "editor-loss", "paint")]
        for cell in cases:
            owned()
            row = trial(tablet, args.output, *cell)
            record["cases"].append(row)
            print("PASS" if row["passed"] else "FAIL", row["name"], flush=True)
            save(args.output / "results.json", record)
        require(all(row["passed"] for row in record["cases"]), "One or more fixture assertions failed")
    finally:
        cleanup = Cleanup(record)
        def release_keys():
            owned()
            if tablet.keyboard:
                tablet.emit((key, 0) for key in sorted(set(LINUX.values()) | {42}))
        def restore_consent():
            owned()
            tablet.set_service(False)
        def release_lease():
            owned()
            tablet.private("rm", owner)
            tablet.private("rmdir", LEASE)
        cleanup.attempt("key_release", release_keys)
        cleanup.attempt("service_disconnected", restore_consent)
        cleanup.attempt("original_consent", lambda: require(tablet.consent() == ["null", "0"], "Consent differs"))
        cleanup.attempt("original_preferences", lambda: require(tablet.raw(*prefs) == original, "Preferences differ"))
        cleanup.attempt("adbd_unprivileged", lambda: transition_adbd(tablet.adb, False))
        cleanup.attempt("app_unprivileged", lambda: require(int(tablet.shell("run-as", APP, "id", "-u")) >= 10000, "App UID"))
        if not cleanup.errors:
            cleanup.attempt("lease_release", release_lease)
        save(args.output / "results.json", record)
        cleanup.finish()


if __name__ == "__main__":
    main()
