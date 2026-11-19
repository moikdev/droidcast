#!/usr/bin/env python3
"""Exercise real Linux key events on the dedicated emulator; restore data, consent and adbd.

UI dumps run between trials. Hardware events are injected only after the dump has
disconnected and the accessibility service is bound, outside UiAutomation.
"""
import argparse
import json
import os
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from shortcut_adbd import change_daemon
from html_viewer_evidence import VIEWER, visible_body_method

if not __debug__:
    raise SystemExit("Run without -O so device and restoration assertions are enabled.")

parser = argparse.ArgumentParser()
parser.add_argument("--adb", default=os.environ.get("ADB", "adb"))
parser.add_argument("--serial", default="emulator-5580")
parser.add_argument("--avd", default="Tinycast_Tablet_API_36", help="Exact dedicated emulator name; required when using an isolated clone")
parser.add_argument("--rounds", type=int, default=3)
parser.add_argument("--alt-q-only", action="store_true", help="Verify the reported Alt+Q activation chord from other apps")
parser.add_argument("--readiness", action="store_true", help="Also verify the inline settings check with real service-filtered Alt+Q")
parser.add_argument("--document-only", action="store_true", help="Only exercise the configured TXT shortcut and its viewer")
parser.add_argument("--settings-focus-run", type=Path, metavar="NEW_DIRECTORY",
                    help="Capture one measured Settings → palette → Settings hardware transition on the dedicated tablet")
args = parser.parse_args()
if args.settings_focus_run and (args.avd != "Tinycast_Tablet_API_36" or not re.fullmatch(r"emulator-[0-9]+", args.serial)
                                or args.document_only or args.alt_q_only or args.readiness):
    parser.error("--settings-focus-run requires the dedicated tablet and its own run mode")
if args.document_only and (args.alt_q_only or args.readiness):
    parser.error("--document-only cannot be combined with the keyboard readiness modes")
assert 1 <= args.rounds <= 10
base = [args.adb, "-s", args.serial]
APP = "dev.tinycast.android"
SERVICE = APP + "/" + APP + ".PaletteShortcutService"
suite = "shortcut-readiness-hardware" if args.readiness else "shortcut-hardware"
if args.document_only:
    suite = "shortcut-document-hardware"
output = Path(__file__).resolve().parents[1] / "artifacts" / suite
if args.settings_focus_run:
    output = args.settings_focus_run
    os.umask(0o077)
output.mkdir(parents=True, exist_ok=not args.settings_focus_run)
results = {"device": args.serial, "app_requires_root": False, "injection": "dedicated emulator Linux qwerty2 device", "cases": []}


def adb(*arguments):
    return subprocess.check_output(base + list(arguments), text=True, stderr=subprocess.STDOUT).strip()


def raw(*arguments):
    return subprocess.check_output(base + list(arguments), stderr=subprocess.STDOUT)


def wait(check, message, timeout=12):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(.15)
    raise AssertionError(message)


def focused():
    return next((line for line in adb("shell", "dumpsys", "window").splitlines() if "mCurrentFocus=" in line), "")


def app_window(activity):
    window = focused()
    component = re.escape(APP) + r"/(?:" + re.escape(APP) + r")?\." + re.escape(activity)
    return bool(re.search(r"\bu\d+ " + component + r"(?:\s|})", window))


def bound():
    return any("Bound services:" in line and "Service[label=Droidcast" in line for line in adb("shell", "dumpsys", "accessibility").splitlines())


def tree_bytes():
    adb("shell", "uiautomator", "dump", "/sdcard/droidcast-shortcut-test.xml")
    return raw("exec-out", "cat", "/sdcard/droidcast-shortcut-test.xml")


def tree():
    return ET.fromstring(tree_bytes())


def node(text, contains=False):
    found = None
    def find():
        nonlocal found
        for candidate in tree().iter("node"):
            actual = candidate.get("text", "") or candidate.get("content-desc", "")
            if (text in actual if contains else actual == text) and candidate.get("class") != "android.widget.EditText":
                found = candidate
                return True
        return False
    wait(find, "UI not found: " + text)
    return found


def tap(candidate):
    left, top, right, bottom = map(int, re.findall(r"\d+", candidate.get("bounds")))
    assert right > left and bottom > top
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))


def click(text):
    tap(node(text))


def type_text(text):
    adb("shell", "input text " + shlex.quote(text.replace(" ", "%s")))


def snapshot(name, marker):
    node(marker, contains=True)
    (output / (name + ".png")).write_bytes(raw("exec-out", "screencap", "-p"))


def palette_snapshot(name):
    assert APP + "/" in focused()
    assert any(item.get("class") == "android.widget.EditText" and item.get("package") == APP for item in tree().iter("node"))
    (output / (name + ".png")).write_bytes(raw("exec-out", "screencap", "-p"))


def passed(name):
    results["cases"].append({"name": name, "passed": True})
    (output / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    print("PASS " + name, flush=True)


def prefs():
    data = raw("exec-out", "run-as", APP, "cat", "shared_prefs/palette.xml")
    return {entry.get("name"): entry.text for entry in ET.fromstring(data)}


assert adb("shell", "getprop", "ro.kernel.qemu") == "1", "Use an emulator"
assert "uid=2000" in adb("shell", "id"), "Start with unprivileged adbd"
assert adb("emu", "avd", "name").splitlines()[0] == args.avd, "Use the specified dedicated AVD"
instrumentation_state = adb("shell", "dumpsys", "activity", "processes")
assert "Unknown command" not in instrumentation_state, "Cannot verify instrumentation state"
assert "ActiveInstrumentation{" not in instrumentation_state, "Finish instrumentation before hardware trials"
original_services = adb("shell", "settings", "get", "secure", "enabled_accessibility_services")
original_enabled = adb("shell", "settings", "get", "secure", "accessibility_enabled")
adb("shell", "input", "keyevent", "KEYCODE_HOME")
tree()  # Let onStop flush pending preference writes before taking the private snapshot.
original_xml = raw("exec-out", "run-as", APP, "cat", "shared_prefs/palette.xml")
(output / "original-palette.xml").write_bytes(original_xml)  # Ignored, never published.
state_changes_started = False
keyboard = None
initial = [] if original_enabled != "1" or original_services == "null" else original_services.split(":")
other_services = [service for service in initial if service != SERVICE]


def emit(events):
    commands = "\n".join(f"sendevent {keyboard} {kind} {code} {value}" for kind, code, value in events)
    adb("shell", commands)


def chord(key, modifiers=(29, 56), repeats=0):
    wait(bound, "Shortcut service is not bound")
    wait(lambda: "InputFilterEnabled: true" in adb("shell", "dumpsys", "input"), "Accessibility input filter is not installed")
    events = []
    for modifier in modifiers:
        events += [(1, modifier, 1), (0, 0, 0)]
    events += [(1, key, 1), (0, 0, 0)]
    for _ in range(repeats):
        events += [(1, key, 2), (0, 0, 0)]
    events += [(1, key, 0), (0, 0, 0)]
    for modifier in reversed(modifiers):
        events += [(1, modifier, 0), (0, 0, 0)]
    emit(events)


def palette(query):
    adb("shell", "am", "start", "-W", "-n", APP + "/.MainActivity")
    wait(lambda: APP + "/" in focused(), "Palette did not open")
    assert any(item.get("class") == "android.widget.EditText" for item in tree().iter("node"))
    type_text(query)
    tree()


def settings():
    adb("shell", "am", "start", "-W", "-a", "android.settings.SETTINGS", "--activity-clear-top")
    wait(lambda: "com.android.settings/" in focused(), "Settings did not open")


def open_shortcut_recorder(query):
    palette(query)
    click("Actions")
    # The software keyboard can leave only the first action visible. Filter
    # the menu before tapping so an offscreen row cannot select its neighbor.
    wait(lambda: any(n.get("resource-id") == "inline-menu-search" and n.get("focused") == "true"
                     for n in tree().iter("node")), "Action search did not receive focus")
    type_text("shortcut")
    click("Set keyboard shortcut")
    node("Record a shortcut")


def assign(query, key, label):
    open_shortcut_recorder(query)
    chord(key)
    node(label)
    click("Save shortcut")
    node("Manage command aliases and keyboard shortcuts in one place.")


def verify_document_shortcut():
    assert any(item["title"] == "tinycast-soak-document.txt" for item in json.loads(prefs().get("files", "[]"))), "Run the document fixture setup before this dedicated test"
    assign("tinycast-soak-document.txt", 25, "Ctrl + Alt + P")
    if args.document_only:
        # Re-enter an existing palette from Settings, as in the full shortcut run.
        adb("shell", "am", "start", "-W", "-n", APP + "/.MainActivity")
        wait(lambda: APP + "/" in focused(), "Palette did not open before the shortcut")
    else:
        chord(57)
    settings()
    chord(25)
    visible = tree()
    if any(item.get("text", "").startswith("Open with") for item in visible.iter("node")):
        choice = next((item for item in visible.iter("node") if item.get("text") == "HTML Viewer"), None)
        if choice is not None:
            tap(choice)
        # Android may already name the remembered viewer in the chooser title.
        click("Just once")

    def document_visible():
        if not any(token.startswith(VIEWER + "/") for token in focused().split()):
            return False
        xml = tree_bytes()
        screenshot = raw("exec-out", "screencap", "-p")
        if not any(token.startswith(VIEWER + "/") for token in focused().split()):
            return False
        method = visible_body_method(ET.fromstring(xml), screenshot, "tinycast-soak-document.txt",
                                     "Tinycast extended text file test.")
        if method is None:
            return False
        (output / "document-viewer.xml").write_bytes(xml)
        (output / "document-viewer.png").write_bytes(screenshot)
        results["document_body_evidence"] = method
        return True

    wait(document_visible, "HTML Viewer did not show the exact TXT title and body")
    passed("recent-document binding opens the persisted TXT fixture")


def restore_setting(name, value):
    if value == "null":
        adb("shell", "settings", "delete", "secure", name)
    else:
        adb("shell", f"settings put secure {name} {shlex.quote(value)}")


def set_services(services):
    if services:
        adb("shell", "settings", "put", "secure", "enabled_accessibility_services", ":".join(services))
    else:
        adb("shell", "settings", "delete", "secure", "enabled_accessibility_services")


def check_button():
    # Dump before arming: UiAutomator may temporarily suppress accessibility services.
    found = None
    for _ in range(7):
        hierarchy = tree()
        for item in hierarchy.iter("node"):
            if item.get("resource-id") == "settings-shortcut-test":
                bounds = list(map(int, re.findall(r"\d+", item.get("bounds"))))
                if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                    found = item
                    break
        if found is not None:
            break
        # Scroll inside the current app viewport, which excludes a visible IME.
        # Fixed screen coordinates can swipe-type into Gboard instead.
        viewport = next(item for item in hierarchy.iter("node") if item.get("resource-id") == "settings-viewport")
        left, top, right, bottom = map(int, re.findall(r"\d+", viewport.get("bounds")))
        x = left + (right - left) * 3 // 4
        start = bottom - 24
        end = max(top + 80, start - 500)
        assert start > end
        adb("shell", "input", "swipe", str(x), str(start), str(x), str(end), "250")
    assert found is not None, "Inline shortcut check was not reachable"
    wait(bound, "Keyboard service must reconnect after the hierarchy dump")
    wait(lambda: "InputFilterEnabled: true" in adb("shell", "dumpsys", "input"), "Input filter did not reconnect")
    time.sleep(.25)  # Let the connected callback render the enabled button.
    return found


def check_snapshot(name, marker):
    # This verification dump can suppress the service. It verifies the retained
    # result, not post-rebind connection state; the later PNG is labelled separately.
    hierarchy = tree()
    text = " ".join(item.get("text", "") for item in hierarchy.iter("node"))
    assert marker in text, "Inline result was not rendered: " + marker
    (output / (name + "-verification.xml")).write_bytes(ET.tostring(hierarchy))
    wait(bound, "Keyboard service must reconnect after result verification")
    time.sleep(.25)
    (output / (name + "-after-rebind.png")).write_bytes(raw("exec-out", "screencap", "-p"))


def configure_global_shortcut():
    palette("Keyboard shortcuts")
    adb("shell", "input", "keyevent", "KEYCODE_ENTER")
    click("Reset shortcuts…"); click("Reset shortcuts")
    click("Record")
    node("Record a shortcut")
    chord(57)
    node("Ctrl + Alt + Space")
    chord(57, (29,))
    node("Ctrl + Space")
    node("Record a shortcut")  # The existing global chord must record, not dismiss.
    click("Save shortcut")
    passed("recording pauses the existing global shortcut")
    click("Record")
    node("Record a shortcut")
    chord(57)
    node("Ctrl + Alt + Space")
    snapshot("record-global", "Record a shortcut")
    click("Save shortcut")
    snapshot("configured-global", "Ctrl + Alt + Space")
    passed("record and persist Ctrl + Alt + Space")


def settings_keyboard_route(trace=None):
    if trace:
        trace.mark("before_ctrl_f")
    chord(33, (29,))
    assert any(item.get("resource-id") == "settings-search" and item.get("focused") == "true" for item in tree().iter("node"))
    passed("Ctrl+F focuses settings search while the service is enabled")
    if trace:
        trace.mark("before_global_chord")
    chord(57)
    wait(lambda: app_window("MainActivity"), "Global chord did not return to the palette")
    if trace:
        trace.mark("before_ctrl_comma")
    chord(51, (29,))
    if trace:
        trace.mark("ctrl_comma_injection_returned")
    node("Make Droidcast fit the way you work.")
    if trace:
        # Verify the destination in the exact hierarchy kept with the terminal frame.
        view = tree_bytes()
        nodes = ET.fromstring(view).iter("node")
        labels = [n.get("text", "") or n.get("content-desc", "") for n in nodes if n.get("package") == APP]
        assert "Make Droidcast fit the way you work." in labels and any("Global shortcut" in label for label in labels)
        assert app_window("SettingsActivity"), "Expected focused Settings before the capture"
        pixels = raw("exec-out", "screencap", "-p")
        assert app_window("SettingsActivity"), "Settings lost focus during the capture"
        (output / "settings-keyboard-route.xml").write_bytes(view)
        (output / "settings-keyboard-route.png").write_bytes(pixels)
    else:
        snapshot("settings-keyboard-route", "Global shortcut")
    if trace:
        trace.mark("destination_verified")
    passed("Ctrl+Comma opens the separate settings workspace with the service enabled")



try:
    # Also unroot if the transport drops during the root/reconnect attempt.
    change_daemon(base, args.avd, "root", results)
    state_changes_started = True
    enabled_services = ":".join(dict.fromkeys(initial + [SERVICE]))
    adb("shell", "am", "force-stop", APP)
    adb("shell", "am", "start", "-W", "-n", APP + "/.MainActivity")
    adb("shell", "settings", "put", "secure", "enabled_accessibility_services", enabled_services)
    adb("shell", "settings", "put", "secure", "accessibility_enabled", "1")
    wait(bound, "Keyboard service did not bind")
    devices = adb("shell", "getevent", "-pl")
    match = re.search(r'add device \d+: (/dev/input/event\d+)\s+name:\s+"qwerty2"', devices)
    assert match, "Dedicated qwerty2 device not found"
    keyboard = match.group(1)

    if args.settings_focus_run:
        from shortcut_focus_evidence import SettingsFocusTrace
        configure_global_shortcut()
        assert app_window("SettingsActivity"), "Missing starting Settings workspace"
        with SettingsFocusTrace(base, output / "focus.json") as trace:
            settings_keyboard_route(trace)
            assert app_window("SettingsActivity"), "Missing Settings destination window"
    elif args.document_only:
        verify_document_shortcut()
    elif args.alt_q_only or args.readiness:
        palette("Keyboard shortcuts")
        adb("shell", "input", "keyevent", "KEYCODE_ENTER")
        click("Reset shortcuts…"); click("Reset shortcuts")
        click("Record")
        node("Record a shortcut")
        chord(16, (56,))  # Linux Q + left Alt, delivered through Android's input filter.
        node("Alt + Q")
        click("Save shortcut")
        snapshot("alt-q-configured", "Alt + Q")
        assert json.loads(prefs()["shortcuts"])["bindings"]["global:palette"] == {"keyCode": 45, "modifiers": 2}
        passed("record and persist Alt + Q")
        for label in ("Settings", "Home"):
            if label == "Settings": settings()
            else: adb("shell", "input", "keyevent", "KEYCODE_HOME")
            wait(lambda: APP + "/" not in focused(), "Could not leave app before Alt+Q")
            chord(16, (56,))
            wait(lambda: APP + "/" in focused(), "Alt+Q did not summon from " + label)
            palette_snapshot("alt-q-from-" + label.lower())
            chord(16, (56,))
            wait(lambda: APP + "/" not in focused(), "Alt+Q did not dismiss to " + label)
            passed("Alt + Q summons and dismisses from " + label)
        set_services(other_services)
        wait(lambda: not bound(), "Service did not disconnect")
        adb("shell", "input", "keyevent", "KEYCODE_HOME")
        emit([(1, 56, 1), (0, 0, 0), (1, 16, 1), (0, 0, 0), (1, 16, 0), (1, 56, 0), (0, 0, 0)])
        tree(); assert APP + "/" not in focused()
        passed("Alt + Q cannot activate globally without service consent")
        adb("shell", "settings", "put", "secure", "enabled_accessibility_services", enabled_services)
        adb("shell", "settings", "put", "secure", "accessibility_enabled", "1")
        wait(bound, "Service did not reconnect")
        chord(16, (56,))
        wait(lambda: APP + "/" in focused(), "Alt+Q failed after reconnect")
        palette_snapshot("alt-q-reconnected")
        passed("Alt + Q persists across service disable and reconnect")
        if args.readiness:
            type_text("Droidcast preferences")
            adb("shell", "input", "keyevent", "KEYCODE_ENTER")
            wait(lambda: ".SettingsActivity" in focused(), "Settings workspace did not open")
            button = check_button()
            tap(button)
            time.sleep(.15)
            chord(16, (56,))
            time.sleep(.3)
            assert ".SettingsActivity" in focused(), "Inline check must not open a second palette"
            check_snapshot("received-alt-q", "Received Alt + Q in Settings")
            passed("inline check receives real filtered Alt + Q without leaving Settings")

            button = check_button()
            tap(button)
            # Do not attach UiAutomation while listening: it could disconnect the
            # service and correctly cancel the check. Wait for the real deadline.
            time.sleep(16)
            check_snapshot("check-timeout", "Nothing received")
            passed("inline check times out without claiming receipt")
            chord(16, (56,))
            wait(lambda: ".MainActivity" in focused(), "Expired check swallowed normal global activation")
            palette_snapshot("activation-after-timeout")
            passed("expired inline check does not intercept ordinary global activation")

            # No hierarchy dump while a probe is armed: dumping could itself
            # disconnect the service and hide the cancellation regression.
            chord(30, (29,))  # Select any palette query before replacing it.
            type_text("Droidcast preferences")
            adb("shell", "input", "keyevent", "KEYCODE_ENTER")
            wait(lambda: ".SettingsActivity" in focused(), "Settings workspace did not open")
            button = check_button(); tap(button); time.sleep(.15)
            chord(33, (29,))  # Ctrl+F enters Settings search while Alt+Q is global.
            type_text("backup")
            chord(16, (56,))
            wait(lambda: ".MainActivity" in focused(), "Hidden Settings search probe swallowed Alt+Q")
            palette_snapshot("activation-after-settings-search")
            passed("Settings search cancels the hidden check and Alt+Q activates normally")

            chord(30, (29,))
            type_text("Droidcast preferences")
            adb("shell", "input", "keyevent", "KEYCODE_ENTER")
            wait(lambda: ".SettingsActivity" in focused(), "Settings workspace did not open")
            click("Change")  # General's global-hotkey action arms its inline recorder.
            node("Record a shortcut"); chord(33, (29,)); node("Ctrl + F"); click("Save shortcut")
            wait(lambda: json.loads(prefs()["shortcuts"])["bindings"]["global:palette"] == {"keyCode": 34, "modifiers": 1}, "Ctrl+F was not persisted")
            click("General")
            chord(33, (29,))
            hierarchy = tree()
            assert any(item.get("resource-id") == "settings-search" and item.get("focused") == "true" for item in hierarchy.iter("node")), "Unarmed Ctrl+F lost Settings navigation"
            passed("configured Ctrl+F retains normal Settings navigation when no check is armed")
            click("General")  # Clear search focus and dismiss its IME before arming.
            button = check_button(); tap(button); time.sleep(.15)
            chord(33, (29,))
            assert ".SettingsActivity" in focused(), "Armed Ctrl+F check must stay in Settings"
            check_snapshot("received-navigation-ctrl-f", "Received Ctrl + F in Settings")
            passed("explicitly armed Ctrl+F reaches the filtered check instead of Settings navigation")
    else:
        configure_global_shortcut()
        settings_keyboard_route()

        for index in range(args.rounds):
            for origin in ("Settings", "Home"):
                if origin == "Settings": settings()
                else: adb("shell", "input", "keyevent", "KEYCODE_HOME")
                wait(lambda: APP + "/" not in focused(), "Did not leave palette")
                chord(57)
                wait(lambda: APP + "/" in focused(), "Configured global chord did not summon")
                if index == 0 and origin == "Settings": palette_snapshot("summon-from-settings")
                chord(57)
                wait(lambda: APP + "/" not in focused(), "Repeated summon should hide the visible palette")
                passed(f"round {index + 1}: summon/toggle from {origin}")

        settings(); chord(57, (29,)); tree()
        assert APP + "/" not in focused(), "Old Ctrl + Space still activated"
        passed("old global chord stops activating after reassignment")

        assign("wifi", 17, "Ctrl + Alt + W")
        open_shortcut_recorder("Calculator")
        chord(17)
        snapshot("conflict", "Already assigned to Wi-Fi settings")
        chord(1, ())
        assert "calculator" not in json.loads(prefs()["shortcuts"])["bindings"]
        passed("live conflict names its owner and cannot overwrite it")
        before = json.loads(prefs().get("usage", "{}" )).get("system:wifi", 0)
        chord(57)
        settings(); chord(17, repeats=8)
        node("Wi-Fi")
        assert "com.android.settings/" in focused()
        snapshot("wifi-destination", "Wi-Fi")
        assert json.loads(prefs()["usage"])["system:wifi"] == before + 1, "Repeat events dispatched more than once"
        passed("Wi-Fi command uses normal destination and suppresses repeats")

        assign("@Files", 33, "Ctrl + Alt + F")
        chord(57)
        settings(); chord(33)
        wait(lambda: "com.google.android.documentsui/" in focused(), "App binding did not launch Files")
        assert any(item.get("package") == "com.google.android.documentsui" for item in tree().iter("node"))
        (output / "files-app.png").write_bytes(raw("exec-out", "screencap", "-p"))
        passed("installed-app binding launches real Files app")

        verify_document_shortcut()

        assign("Calculator", 46, "Ctrl + Alt + C")
        chord(57)
        settings(); chord(46)
        wait(lambda: APP + "/" in focused(), "Calculator binding did not open palette mode")
        assert any(item.get("class") == "android.widget.EditText" and item.get("text", "").startswith("=") for item in tree().iter("node"))
        type_text("12*7")
        snapshot("calculator", "84")
        passed("calculator binding enters the same calculator mode")
        chord(46); tree()
        assert APP + "/" not in focused(), "Repeated calculator shortcut should close its existing mode"
        passed("calculator shortcut toggles its existing mode")

        assign("Create a note", 49, "Ctrl + Alt + N")
        chord(57)
        settings(); chord(49)
        snapshot("new-note", "A place for the thought")
        passed("note command binding opens the normal native editor")

        settings()
        search = next(item for item in tree().iter("node") if (item.get("text", "") or item.get("content-desc", "")).casefold() == "search settings")
        tap(search); tree()
        for key in (16, 17, 18, 19, 20, 21): chord(key, ())
        assert any(item.get("class") == "android.widget.EditText" and item.get("text") == "qwerty" for item in tree().iter("node")), "Unrelated typing was lost"
        search_package = re.search(r"u\d+ ([^/\s]+)/", focused()).group(1)
        assert search_package in ("com.android.settings", "com.google.android.settings.intelligence")
        chord(44); tree()
        assert search_package + "/" in focused(), "Unbound chord changed the focused search app"
        assert any(item.get("class") == "android.widget.EditText" and item.get("text") == "qwerty" for item in tree().iter("node")), "Unbound chord changed the search text"
        passed("unrelated typing and an unbound chord pass through")

        set_services(other_services)
        wait(lambda: not bound(), "Service did not disconnect")
        adb("shell", "input", "keyevent", "KEYCODE_HOME")
        # Without the service, this raw chord cannot invoke the app.
        emit([(1, 29, 1), (1, 56, 1), (0, 0, 0), (1, 57, 1), (0, 0, 0), (1, 57, 0), (1, 56, 0), (1, 29, 0), (0, 0, 0)])
        tree(); assert APP + "/" not in focused()
        adb("shell", "settings", "put", "secure", "enabled_accessibility_services", enabled_services)
        adb("shell", "settings", "put", "secure", "accessibility_enabled", "1")
        wait(bound, "Service did not reconnect")
        chord(57)
        wait(lambda: APP + "/" in focused(), "Persisted chord failed after reconnect")
        palette_snapshot("reconnected")
        passed("service disable/reconnect respects persisted configuration")
except BaseException as failure:
    results["suite_failure"] = type(failure).__name__
    if state_changes_started and isinstance(failure, Exception):
        try:
            (output / "failure.png").write_bytes(raw("exec-out", "screencap", "-p"))
            (output / "failure.xml").write_bytes(ET.tostring(tree()))
        except Exception as capture_failure:
            results["failure_capture_error"] = type(capture_failure).__name__
    raise
finally:
    results["state_changes_started"] = state_changes_started
    try:
        if state_changes_started:
            if keyboard:
                emit([(1, code, 0) for code in (1, 16, 17, 25, 29, 30, 33, 42, 44, 46, 49, 56, 57, 125)] + [(0, 0, 0)])
            set_services(other_services)
            adb("shell", "am", "force-stop", APP)
            adb("shell", "run-as", APP, "rm", "-f", "shared_prefs/palette.xml.bak")
            subprocess.run(base + ["shell", "run-as dev.tinycast.android sh -c 'cat > shared_prefs/palette.xml'"], input=original_xml, check=True, capture_output=True)
            adb("shell", "am", "start", "-W", "-n", APP + "/.MainActivity")
            restore_setting("enabled_accessibility_services", original_services)
            restore_setting("accessibility_enabled", original_enabled)
            results["original_service_consent_restored"] = (
                adb("shell", "settings", "get", "secure", "enabled_accessibility_services") == original_services
                and adb("shell", "settings", "get", "secure", "accessibility_enabled") == original_enabled)
            assert results["original_service_consent_restored"]
            results["original_preferences_restored"] = raw("exec-out", "run-as", APP, "cat", "shared_prefs/palette.xml") == original_xml
            assert results["original_preferences_restored"]
    except Exception as cleanup_failure:
        results["state_cleanup_error"] = type(cleanup_failure).__name__
        raise
    finally:
        try:
            change_daemon(base, args.avd, "unroot", results)
            results["adbd_unprivileged"] = True
            if state_changes_started:
                results["app_process"] = [line for line in adb("shell", "ps", "-A", "-o", "USER,NAME").splitlines() if line.endswith(APP)]
                results["app_unprivileged"] = bool(results["app_process"]) and all(line.split()[0] not in ("root", "system", "shell") for line in results["app_process"])
                assert results["app_unprivileged"]
        except Exception as identity_failure:
            results.setdefault("adbd_unprivileged", False)
            results["identity_check_error"] = type(identity_failure).__name__
            raise
        finally:
            (output / "results.json").write_text(json.dumps(results, indent=2) + "\n")
print(json.dumps({"passed_cases": len(results["cases"]), "data_restored": results.get("original_preferences_restored"), "adbd_unprivileged": results.get("adbd_unprivileged")}, indent=2))
