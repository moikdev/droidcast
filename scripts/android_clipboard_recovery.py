"""Private clipboard recovery outside the Android instrumentation process."""
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess
import time
import uuid

ROOT = 'no_backup/reviewed-text-clipboard-recovery'


def public_receipt(value):
    # Even hashes of unrelated clipboard contents stay in private artifacts.
    return {key: value[key] for key in ['schema_version', 'owner_nonce', 'request_id', 'phase']}


def require(value, message):
    if not value:
        raise ValueError(message)


def validate_receipt(value, owner, request, phase, snapshot=None):
    require(isinstance(value, dict), 'Invalid clipboard receipt')
    require(type(value.get('schema_version')) is int and value['schema_version'] == 1,
            'Invalid clipboard receipt schema')
    require(value.get('owner_nonce') == owner and value.get('request_id') == request,
            'Clipboard receipt belongs to another operation')
    require(value.get('phase') == phase, 'Clipboard recovery did not complete')
    for key in ['snapshot_sha256', 'content_sha256']:
        require(isinstance(value.get(key), str) and re.fullmatch(r'[0-9a-f]{64}', value[key]),
                'Invalid clipboard recovery identity')
    require(snapshot is None or value['snapshot_sha256'] == snapshot, 'Clipboard snapshot identity changed')
    return value


class ClipboardRecovery:
    def __init__(self, raw, output, owner, package, record):
        require(re.fullmatch(r'[0-9a-f]{32}', owner) is not None, 'Invalid clipboard recovery owner')
        self.raw, self.output, self.owner, self.package, self.record = raw, Path(output), owner, package, record
        self.attempted = self.ready = self.permitted = False
        self.identity = None
        self.restored_request = None

    def read_json(self, name):
        return json.loads(self.raw('exec-out', 'run-as', self.package, 'cat', ROOT + '/' + name))

    def owner_metadata(self):
        value = self.read_json('owner.json')
        require(value.get('owner_nonce') == self.owner and type(value.get('schema_version')) is int
                and value['schema_version'] == 1, 'Clipboard recovery belongs to another owner')
        return value

    def exists(self):
        # Establish that run-as works before interpreting test's exit status.
        self.raw('shell', 'run-as', self.package, 'id')
        try:
            self.raw('shell', 'run-as', self.package, 'test', '-e', ROOT)
            return True
        except subprocess.CalledProcessError as failure:
            require(failure.returncode == 1, 'Cannot determine clipboard recovery ownership')
            return False

    def operation(self, action):
        request = uuid.uuid4().hex
        self.raw('shell', 'am', 'start', '-W', '-n', self.package + '/dev.tinycast.android.ClipboardRecoveryActivity',
                 '--es', 'owner_nonce', self.owner, '--es', 'request_id', request,
                 '--es', 'recovery_action', action)
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            try:
                receipt = self.read_json('receipt.json')
            except (subprocess.CalledProcessError, json.JSONDecodeError, UnicodeDecodeError):
                # exec-out can return success while cat reports a not-yet-created
                # file. An incomplete receipt is never success; wait only within
                # this operation's bounded deadline for the exact request/owner.
                receipt = {}
            if receipt.get('request_id') == request:
                value = validate_receipt(receipt, self.owner, request,
                                         'snapshot-ready' if action == 'snapshot' else 'restored', self.identity)
                return request, value
            time.sleep(.1)
        raise RuntimeError('Clipboard recovery did not produce its owned receipt')

    def snapshot(self):
        require(not self.exists(), 'An earlier private clipboard recovery exists; inspect it first')
        self.attempted = True
        _, receipt = self.operation('snapshot')
        data = self.raw('exec-out', 'run-as', self.package, 'cat', ROOT + '/snapshot.bin')
        require(0 < len(data) <= 1024 * 1024 and hashlib.sha256(data).hexdigest() == receipt['snapshot_sha256'],
                'Private clipboard backup differs')
        path = self.output / 'private-clipboard-snapshot.bin'
        with path.open('xb') as backup:
            path.chmod(0o600)
            backup.write(data)
            backup.flush()
            os.fsync(backup.fileno())
        self.identity = receipt['snapshot_sha256']
        self.ready = True
        private = self.output / 'private-clipboard-recovery.json'
        private.write_text(json.dumps(receipt, indent=2) + '\n'); private.chmod(0o600)
        self.record['clipboard_recovery'] = dict(public_receipt(receipt), private_backup_saved=True,
                                               identities_retained_privately=True)

    def allow_mutations(self):
        require(self.ready, 'Clipboard snapshot must be durable before instrumentation starts')
        self.permitted = True
        self.record['clipboard_mutation_permitted'] = True

    def restore(self):
        if not self.permitted:
            self.record['clipboard_mutation_permitted'] = False
            return  # No instrumentation ran; snapshot itself never mutates it.
        require(self.ready and self.owner_metadata().get('snapshot_sha256') == self.identity,
                'Clipboard recovery snapshot is unavailable')
        self.restored_request, receipt = self.operation('restore')
        private = self.output / 'private-clipboard-restoration.json'
        private.write_text(json.dumps(receipt, indent=2) + '\n'); private.chmod(0o600)
        self.record['clipboard_restoration'] = dict(public_receipt(receipt), identities_retained_privately=True)

    def inject_restore_failure(self):
        require(self.permitted and self.ready, 'Control requires a durable private original')
        # The original remains in the host's mode-0600 private backup. The
        # negative control must retain both recovery owners until manual repair.
        self.raw('shell', 'run-as', self.package, 'tee', ROOT + '/snapshot.bin',
                 data=b'Owned negative-control corrupt snapshot')
        self.record['injected_clipboard_restore_failure'] = True

    def release(self):
        if not self.attempted:
            return
        self.owner_metadata()
        if self.permitted:
            require(self.restored_request is not None, 'Retain clipboard recovery after failed restoration')
            validate_receipt(self.read_json('receipt.json'), self.owner, self.restored_request, 'restored', self.identity)
        self.raw('shell', 'run-as', self.package, 'rm', '-rf', ROOT)
        self.raw('shell', 'run-as', self.package, 'test', '!', '-e', ROOT)
