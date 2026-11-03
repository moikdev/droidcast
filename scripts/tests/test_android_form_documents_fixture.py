import json
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from android_form_documents_fixture import DOCUMENTS, ROOT, FormDocumentsFixture, validate_owner


class FormDocumentOwnershipTest(unittest.TestCase):
    owner = 'a' * 32
    package = 'dev.tinycast.android.test'

    def metadata(self):
        return {'schema_version': 1, 'fixture_kind': 'form-document-provider-v1', 'owner_nonce': self.owner}

    def fixture(self, changed_owner=False, changed_source=False, linked=False, unexpected=False):
        events = []
        metadata = self.metadata()
        if changed_owner:
            metadata['owner_nonce'] = 'b' * 32
        listing = [ROOT + '/owner.json', ROOT + '/data', ROOT + '/data/folder'] + [ROOT + '/' + name for name in DOCUMENTS]
        if unexpected:
            listing.append(ROOT + '/unrelated.txt')
        def raw(*args, data=None):
            events.append(args)
            if args == ('exec-out', 'run-as', self.package, 'cat', ROOT + '/owner.json'):
                return json.dumps(metadata).encode()
            if args == ('shell', 'run-as', self.package, 'find', ROOT, '-type', 'l', '-print'):
                return (ROOT + '/data/link\n').encode() if linked else b''
            if args == ('shell', 'run-as', self.package, 'find', ROOT, '-mindepth', '1', '-print'):
                return ('\n'.join(listing) + '\n').encode()
            if args[:4] == ('exec-out', 'run-as', self.package, 'cat'):
                key = args[4].removeprefix(ROOT + '/')
                return b'Changed source' if changed_source else DOCUMENTS[key]
            return b''
        result = FormDocumentsFixture(raw, self.owner, self.package, {})
        result.claimed = True
        result.exists = Mock(return_value=False)
        return result, events

    def test_foreign_malformed_and_boolean_leases_cannot_authorize_cleanup(self):
        for bad in [None, [], {}, dict(self.metadata(), owner_nonce='b' * 32), dict(self.metadata(), schema_version=True),
                    dict(self.metadata(), fixture_kind='other'), dict(self.metadata(), extra='ignored')]:
            with self.assertRaises(ValueError):
                validate_owner(bad, self.owner)

    def test_changed_owner_is_rejected_before_any_source_read_or_deletion(self):
        fixture, events = self.fixture(changed_owner=True)
        with self.assertRaises(ValueError): fixture.release()
        self.assertFalse(any('rm' in event for event in events))
        self.assertFalse(any(event[-1].endswith('/data/text.bin') for event in events))

    def test_changed_original_or_unexpected_data_retains_the_fixture(self):
        for options in [dict(changed_source=True), dict(unexpected=True), dict(linked=True)]:
            fixture, events = self.fixture(**options)
            with self.assertRaises(ValueError): fixture.release()
            self.assertFalse(any('rm' in event for event in events))

    def test_release_reverifies_the_owner_and_exact_originals_before_fixed_namespace_deletion(self):
        fixture, events = self.fixture()
        fixture.release()
        self.assertEqual(('shell', 'run-as', self.package, 'rm', '-rf', ROOT), events[-1])
        self.assertEqual(len(DOCUMENTS), sum(event[:4] == ('exec-out', 'run-as', self.package, 'cat') and '/data/' in event[-1] for event in events))
        self.assertTrue(fixture.record['document_fixture_restored'])

    def test_an_unclaimed_fixture_cannot_delete_an_earlier_owner(self):
        fixture, events = self.fixture()
        fixture.claimed = False
        fixture.release()
        self.assertEqual([], events)

    def test_setup_never_reuses_an_existing_namespace(self):
        fixture, events = self.fixture()
        fixture.exists = Mock(return_value=True)
        with self.assertRaises(ValueError): fixture.setup()
        self.assertEqual([], events)

    def test_setup_validates_the_standard_parent_before_the_atomic_fixture_claim(self):
        fixture, events = self.fixture()
        fixture.verify = Mock()
        fixture.setup()
        parent = ('shell', 'run-as', self.package, 'test', '!', '-L', 'files')
        claim = ('shell', 'run-as', self.package, 'mkdir', ROOT)
        self.assertLess(events.index(parent), events.index(claim))
        self.assertTrue(fixture.ready)
        fixture.verify.assert_called_once()


if __name__ == '__main__':
    unittest.main()
