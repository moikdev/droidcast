import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location('reviewed_text_runner', Path(__file__).parents[1] / 'test-reviewed-text-input.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class FrozenFormArchiveTest(unittest.TestCase):
    def test_exact_binary_archive_bytes_survive(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'owned.zip'
            data = b'owned\0\xff' + 'café 日本'.encode()
            path.write_bytes(data)
            self.assertEqual(data, runner.frozen_form_archive(path, hashlib.sha256(data).hexdigest()))

    def test_an_archive_with_changed_bytes_is_rejected_before_device_work(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'owned.zip'
            path.write_bytes(b'changed owned fixture')
            with self.assertRaisesRegex(ValueError, 'archive changed'):
                runner.frozen_form_archive(path, hashlib.sha256(b'original owned fixture').hexdigest())

    def test_an_oversized_sparse_archive_is_rejected_without_reading_it(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'owned.zip'
            with path.open('wb') as stream:
                stream.seek(32 * 1024 * 1024)
                stream.write(b'x')
            with self.assertRaisesRegex(ValueError, 'size bound'):
                runner.frozen_form_archive(path, '0' * 64)
