"""Bounded daemon transitions for the dedicated shortcut hardware test."""
import subprocess
import time


def _command(base, *args):
    try:
        result = subprocess.run([*base, *args], capture_output=True, timeout=3)
    except subprocess.TimeoutExpired:
        return None, b""
    return result.returncode, result.stdout.strip()


def _verified_shell_uid(base, avd):
    # One shell invocation binds the UID observation to the emulator identity.
    code, reply = _command(base, "shell", "getprop ro.kernel.qemu; getprop ro.boot.qemu.avd_name; id -u")
    if code != 0:
        return None
    fields = reply.splitlines()
    if len(fields) != 3:
        return None
    emulator, name, uid = fields
    if not name:
        # Older system images may only expose the name on the emulator console.
        code, console = _command(base, "emu", "avd", "name")
        if code != 0 or not console.splitlines():
            return None
        name = console.splitlines()[0]
    if emulator != b"1" or name != avd.encode():
        raise RuntimeError("Unexpected device during adbd transition")
    return uid


def change_daemon(base, avd, operation, report):
    if operation not in ("root", "unroot"):
        raise ValueError("Unknown adbd transition")
    target = b"0" if operation == "root" else b"2000"
    deadline = time.monotonic() + 30
    attempts = report.setdefault(operation + "_command_exit_codes", [])
    next_request = 0
    while time.monotonic() < deadline:
        uid = _verified_shell_uid(base, avd)
        if uid == target:
            return
        # Retry unroot only after observing the same AVD still running as root.
        # A root request is issued once. A disconnect can hide a successful restart.
        if uid in (b"0", b"2000") and time.monotonic() >= next_request:
            if not attempts or operation == "unroot":
                code, _ = _command(base, operation)
                attempts.append(code)
                report[operation + "_command_exit_code"] = code
                next_request = time.monotonic() + 3
        time.sleep(.25)
    raise RuntimeError(f"Could not verify the dedicated emulator UID after {operation}")
