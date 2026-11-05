import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('package_apks', Path(__file__).with_name('package-apks.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PackagingTests(unittest.TestCase):
    def fixture(self, root):
        source = root / 'input'
        source.mkdir()
        entries = []
        for abi in ['arm64-v8a', 'x86_64', 'universal']:
            name = f'app-{abi}-debug.apk'
            (source / name).write_bytes(abi.encode())
            entries.append({'versionName': '0.2.2-test', 'outputFile': name,
                            'filters': [] if abi == 'universal' else [{'filterType': 'ABI', 'value': abi}]})
        (source / 'output-metadata.json').write_text(json.dumps({'elements': entries}))
        return source, entries

    def test_complete_release_and_checksums(self):
        import hashlib
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source, _ = self.fixture(root)
            output = module.package(source, root / 'dist')
            self.assertEqual(len(list(output.glob('Luma-Music-0.2.2-test-*.apk'))), 3)
            for line in (output / 'SHA256SUMS.txt').read_text().splitlines():
                digest, name = line.split('  ')
                self.assertEqual(digest, hashlib.sha256((output / name).read_bytes()).hexdigest())
            self.assertTrue((source / 'app-universal-debug.apk').exists())

    def test_incomplete_or_unsafe_release_is_rejected_before_writing(self):
        for mode in ['missing_abi', 'mixed_version', 'unsafe_version', 'outside_path']:
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                source, entries = self.fixture(root)
                if mode == 'missing_abi': entries.pop()
                if mode == 'mixed_version': entries[0]['versionName'] = 'other'
                if mode == 'unsafe_version':
                    for entry in entries: entry['versionName'] = '../escape'
                if mode == 'outside_path':
                    (root / 'outside.apk').write_bytes(b'outside')
                    entries[0]['outputFile'] = '../outside.apk'
                (source / 'output-metadata.json').write_text(json.dumps({'elements': entries}))
                with self.assertRaises(ValueError): module.package(source, root / 'dist')
                self.assertFalse((root / 'dist').exists())


if __name__ == '__main__':
    unittest.main()
