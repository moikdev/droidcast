"""Nonce-owned documents in the test APK's UID for actual Android picker tests."""
import hashlib
import json
import re
import subprocess

ROOT = 'files/form-document-fixture'
DOCUMENTS = {'data/text.bin': 'Owned file café 日本 🌱\n'.encode(),
             'data/binary.bin': bytes([0, 1, 2, 254, 255, 10]),
             'data/folder/nested.bin': 'Owned nested café 日本 🌱\n'.encode(),
             'data/oversize.bin': b'Owned declared-size negative control\n'}


def require(value, message):
    if not value:
        raise ValueError(message)


def validate_owner(value, expected):
    require(isinstance(value, dict) and set(value) == {'schema_version', 'fixture_kind', 'owner_nonce'}, 'Invalid document fixture lease')
    require(type(value['schema_version']) is int and value['schema_version'] == 1 and value['fixture_kind'] == 'form-document-provider-v1', 'Invalid document fixture kind')
    require(isinstance(expected, str) and re.fullmatch(r'[0-9a-f]{32}', expected) is not None and value['owner_nonce'] == expected, 'Document fixture belongs to another owner')
    return value


class FormDocumentsFixture:
    def __init__(self, raw, owner, package, record):
        validate_owner({'schema_version': 1, 'fixture_kind': 'form-document-provider-v1', 'owner_nonce': owner}, owner)
        self.raw, self.owner, self.package, self.record = raw, owner, package, record
        self.claimed = self.ready = False

    def exists(self):
        self.raw('shell', 'run-as', self.package, 'id')
        try:
            self.raw('shell', 'run-as', self.package, 'test', '-e', ROOT)
            return True
        except subprocess.CalledProcessError as failure:
            require(failure.returncode == 1, 'Cannot determine document fixture ownership')
            return False

    def metadata(self):
        self.raw('shell', 'run-as', self.package, 'test', '!', '-L', ROOT)
        return validate_owner(json.loads(self.raw('exec-out', 'run-as', self.package, 'cat', ROOT + '/owner.json')), self.owner)

    def setup(self):
        require(not self.exists(), 'An earlier document fixture exists; inspect it before continuing')
        self.raw('shell', 'run-as', self.package, 'mkdir', ROOT)
        self.claimed = True
        owner = {'schema_version': 1, 'fixture_kind': 'form-document-provider-v1', 'owner_nonce': self.owner}
        self.raw('shell', 'run-as', self.package, 'tee', ROOT + '/owner.json', data=(json.dumps(owner) + '\n').encode())
        self.raw('shell', 'run-as', self.package, 'mkdir', ROOT + '/data', ROOT + '/data/folder')
        for name, content in DOCUMENTS.items():
            self.raw('shell', 'run-as', self.package, 'tee', ROOT + '/' + name, data=content)
        self.verify()
        self.raw('shell', 'am', 'start', '-W', '-n', self.package + '/dev.tinycast.android.FormDocumentsFixtureActivity', '--es', 'owner_nonce', self.owner)
        self.ready = True
        self.record['document_fixture'] = dict(owner, source_sha256={name: hashlib.sha256(data).hexdigest() for name, data in DOCUMENTS.items()},
            provider_uid='test APK; system picker grants selected documents', declared_oversize_control=True)

    def verify(self):
        self.metadata()
        require(not self.raw('shell', 'run-as', self.package, 'find', ROOT, '-type', 'l', '-print').strip(), 'A linked fixture path appeared; retain ownership')
        expected = {'./owner.json', './data', './data/text.bin', './data/binary.bin', './data/oversize.bin', './data/folder', './data/folder/nested.bin'}
        listing = self.raw('shell', 'run-as', self.package, 'find', ROOT, '-mindepth', '1', '-print').decode().splitlines()
        require({'.' + name.removeprefix(ROOT) for name in listing} == expected, 'Unexpected document fixture data; retain ownership')
        for name, data in DOCUMENTS.items():
            require(self.raw('exec-out', 'run-as', self.package, 'cat', ROOT + '/' + name) == data, 'Original fixture document changed')

    def release(self):
        if not self.claimed:
            return
        self.verify()  # Validate the owner and every source before any deletion.
        self.raw('shell', 'run-as', self.package, 'rm', '-rf', ROOT)
        require(not self.exists(), 'Owned document fixture remains')
        self.record['document_fixture_restored'] = True
