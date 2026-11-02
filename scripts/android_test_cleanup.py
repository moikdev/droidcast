"""Independent best-effort restoration for guarded, owned-emulator test runners."""
import subprocess
import time
import re


class CleanupError(RuntimeError):
    pass


def reviewed_text_fixture_ids(value, owner_nonce, *, process_recreation=False):
    """Validate the whole selected-text lease before removing any owned path."""
    if type(process_recreation) is not bool:
        raise CleanupError('Invalid reviewed-text fixture mode')
    if not isinstance(owner_nonce, str) or not re.fullmatch(r'[0-9a-f]{32}', owner_nonce):
        raise CleanupError('Invalid reviewed-text lease owner')
    if not isinstance(value, dict) or set(value) != {'schema_version', 'fixture_kind', 'owner_nonce', 'owned_extension_ids'}:
        raise CleanupError('Invalid reviewed-text lease schema')
    expected_kind = 'reviewed-text-process-recreation-v1' if process_recreation else 'reviewed-text-device-test-v1'
    if type(value['schema_version']) is not int or value['schema_version'] != 1 or value['fixture_kind'] != expected_kind or value['owner_nonce'] != owner_nonce:
        raise CleanupError('Reviewed-text lease belongs to another run')
    identities = value['owned_extension_ids']
    if not isinstance(identities, list) or len(identities) > 100:
        raise CleanupError('Invalid reviewed-text fixture count')
    if any(not isinstance(item, str) or not re.fullmatch(r'reviewed-text-[0-9a-f]{32}', item) for item in identities):
        raise CleanupError('Unsafe reviewed-text fixture identity')
    if len(set(identities)) != len(identities):
        raise CleanupError('Duplicate reviewed-text fixture identity')
    return list(identities)


def empty_view_fixture_ids(value, owner_nonce):
    """Validate the complete lease before a runner removes any fixture path."""
    if not isinstance(owner_nonce, str) or not re.fullmatch(r'[0-9a-f]{32}', owner_nonce):
        raise CleanupError('Invalid empty-view lease owner')
    if not isinstance(value, dict) or set(value) != {'schema_version', 'fixture_kind', 'owner_nonce', 'owned_extension_ids'}:
        raise CleanupError('Invalid empty-view lease schema')
    if type(value['schema_version']) is not int or value['schema_version'] != 1 or value['fixture_kind'] != 'empty-view-device-test-v1' or value['owner_nonce'] != owner_nonce:
        raise CleanupError('Empty-view lease belongs to another run')
    ids = value['owned_extension_ids']
    if not isinstance(ids, list) or len(ids) > 100:
        raise CleanupError('Invalid empty-view fixture count')
    if any(not isinstance(item, str) or not re.fullmatch(r'empty-view-[0-9a-f]{32}', item) for item in ids):
        raise CleanupError('Unsafe empty-view fixture identity')
    if len(set(ids)) != len(ids):
        raise CleanupError('Duplicate empty-view fixture identity')
    return list(ids)


class Cleanup:
    def __init__(self, record):
        self.results = record.setdefault('cleanup', {})
        self.errors = []

    def attempt(self, name, action):
        try:
            value = action()
            self.results[name] = {'passed': True}
            return value
        except BaseException as error:
            self.results[name] = {'passed': False, 'error_type': type(error).__name__}
            self.errors.append(name)
            return None

    def require(self, condition, message):
        if not condition:
            raise CleanupError(message)

    def finish(self):
        if self.errors:
            raise CleanupError('Restoration failed: ' + ', '.join(self.errors))


def transition_adbd(adb, rooted, timeout=15):
    """ADB may disconnect before returning from root/unroot; wait for the actual UID."""
    command = 'root' if rooted else 'unroot'
    expected = b'uid=0' if rooted else b'uid=2000'
    try:
        subprocess.run(adb + [command], capture_output=True, timeout=5)
    except subprocess.TimeoutExpired:
        pass
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            state = subprocess.run(adb + ['shell', 'id'], capture_output=True, timeout=3)
            if state.returncode == 0 and state.stdout.startswith(expected):
                return
        except subprocess.TimeoutExpired:
            pass
        time.sleep(.15)
    raise CleanupError('adbd did not reach the required UID after ' + command)
