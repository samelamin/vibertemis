"""Package regressions. Fixtures contain actual ELF64 program/dynamic tables."""
import hashlib
import importlib.util
import os
from pathlib import Path
import struct
import tempfile
import unittest
import warnings
import zipfile

spec = importlib.util.spec_from_file_location('verify_apk_native', Path(__file__).parents[1] / 'verify-apk-native.py')
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


def elf(name, needed=()):
    strings = bytearray(b'\0')
    entries = []
    for tag, value in [(14, name)] + [(1, n) for n in needed]:
        entries.append((tag, len(strings)))
        strings.extend(value.encode('ascii') + b'\0')
    dynamic_offset = 64 + 2 * 56
    string_offset = dynamic_offset + (len(entries) + 3) * 16
    entries += [(5, string_offset), (10, len(strings)), (0, 0)]
    size = string_offset + len(strings)
    header = struct.pack('<16sHHIQQQIHHHHHH', b'\x7fELF\x02\x01\x01' + b'\0' * 9,
                         3, 183, 1, 0, 64, 0, 0, 64, 56, 2, 0, 0, 0)
    load = struct.pack('<IIQQQQQQ', 1, 4, 0, 0, 0, size, size, 8)
    dynamic = struct.pack('<IIQQQQQQ', 2, 4, dynamic_offset, dynamic_offset,
                          dynamic_offset, len(entries) * 16, len(entries) * 16, 8)
    return header + load + dynamic + b''.join(struct.pack('<qQ', *p) for p in entries) + strings


class PackageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.apk = Path(self.temp.name) / 'app.apk'
        self.libs = {n: elf(n, ['libc.so']) for n in verifier.REQUIRED}
        self.libs['libpyroclient.so'] = elf('libpyroclient.so', ['libc++_shared.so', 'libpyrowave-shared.so'])
        self.libs['libpyrowave-shared.so'] = elf('libpyrowave-shared.so', ['libc++_shared.so', 'libvulkan.so'])

    def check(self, extra=(), expected_hash=None):
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(self.apk, 'w') as archive:
                for name, data in self.libs.items():
                    archive.writestr('lib/arm64-v8a/' + name, data)
                for name, data in extra:
                    archive.writestr(name, data)
        return verifier.verify_apk(self.apk, expected_hash)

    def test_complete_dependency_closure(self):
        self.assertTrue(self.check()['ok'])

    def test_missing_cpp_runtime_and_transitive_dependency(self):
        del self.libs['libc++_shared.so']
        result = self.check()
        self.assertFalse(result['ok'])
        self.assertIn('libpyroclient.so: missing dependency libc++_shared.so', result['errors'])
        self.assertIn('libpyrowave-shared.so: missing dependency libc++_shared.so', result['errors'])

    def test_missing_other_private_dependency(self):
        self.libs['libpyroclient.so'] = elf('libpyroclient.so', ['libprivate.so'])
        self.assertIn('libpyroclient.so: missing dependency libprivate.so', self.check()['errors'])

    def test_dlopen_openxr_loader_is_required(self):
        del self.libs['libopenxr_loader.so']
        self.assertIn('required library missing: libopenxr_loader.so', self.check()['errors'])

    def test_duplicate_zip_library(self):
        self.assertFalse(self.check([('lib/arm64-v8a/libpyroclient.so', self.libs['libpyroclient.so'])])['ok'])

    def test_foreign_abi_rejected(self):
        self.assertFalse(self.check([('lib/x86_64/libextra.so', elf('libextra.so'))])['ok'])

    def test_wrong_machine_rejected(self):
        data = bytearray(self.libs['libpyroclient.so'])
        struct.pack_into('<H', data, 18, 62)
        self.libs['libpyroclient.so'] = data
        self.assertFalse(self.check()['ok'])

    def test_soname_cannot_hide_wrong_filename(self):
        self.libs['renamed.so'] = self.libs.pop('libc++_shared.so')
        self.assertFalse(self.check()['ok'])

    def test_malformed_elf_rejected(self):
        for data in [b'not an ELF', self.libs['libpyroclient.so'][:70], self.libs['libpyroclient.so'][:-1]]:
            with self.subTest(size=len(data)):
                self.libs['libpyroclient.so'] = data
                self.assertFalse(self.check()['ok'])

    def test_exact_runtime_hash(self):
        expected = hashlib.sha256(self.libs['libc++_shared.so']).hexdigest()
        self.assertTrue(self.check(expected_hash=expected)['ok'])
        self.assertFalse(self.check(expected_hash='0' * 64)['ok'])

    def test_preview9_package_regression_when_provided(self):
        apk = os.environ.get('VIBERTEMIS_RELEASED_APK')
        if not apk:
            self.skipTest('set VIBERTEMIS_RELEASED_APK for the historical artifact regression')
        result = verifier.verify_apk(apk)
        self.assertFalse(result['ok'])
        self.assertEqual(8, len(result['libraries']), result['errors'])
        self.assertEqual({'required library missing: libc++_shared.so',
                          'libpyroclient.so: missing dependency libc++_shared.so',
                          'libpyrowave-shared.so: missing dependency libc++_shared.so'}, set(result['errors']))


if __name__ == '__main__':
    unittest.main()
