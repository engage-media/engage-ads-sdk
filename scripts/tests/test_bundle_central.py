import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('bundle_central', Path(__file__).parents[1] / 'bundle-central.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class CentralBundleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'repo'
        self.output = Path(self.temp.name) / 'bundle.zip'
        self.folder = self.root / 'com/github/engage-media/engage-ads-core/2.0.0-alpha.1'
        self.folder.mkdir(parents=True)
        prefix = 'engage-ads-core-2.0.0-alpha.1'
        self.pom = self.folder / f'{prefix}.pom'
        self.pom.write_text('''<project xmlns="http://maven.apache.org/POM/4.0.0">
<groupId>com.github.engage-media</groupId><artifactId>engage-ads-core</artifactId>
<version>2.0.0-alpha.1</version><name>Engage</name><description>SDK</description>
<url>https://github.com/engage-media/engage-ads-sdk</url>
<licenses><license><name>Fixture terms</name><url>https://example.invalid/license</url></license></licenses><developers/><scm/>
</project>''')
        for suffix in ('.aar', '-sources.jar', '-javadoc.jar', '.module'):
            (self.folder / f'{prefix}{suffix}').write_bytes(b'artifact')
        for path in list(self.folder.iterdir()):
            path.with_name(path.name + '.asc').write_bytes(b'-----BEGIN PGP SIGNATURE-----\nfixture\n-----END PGP SIGNATURE-----')

    def test_bundle_has_signatures_and_recalculated_checksums(self):
        self.assertEqual(module.bundle(self.root, self.output), 1)
        first = self.output.read_bytes()
        with zipfile.ZipFile(self.output) as archive:
            names = archive.namelist()
            self.assertTrue(any(name.endswith('.aar.asc') for name in names))
            self.assertTrue(any(name.endswith('.pom.sha256') for name in names))
            self.assertFalse(any(name.startswith('/') for name in names))
        module.bundle(self.root, self.output)
        self.assertEqual(first, self.output.read_bytes())

    def test_unsigned_artifacts_are_rejected_before_output(self):
        self.pom.with_name(self.pom.name + '.asc').unlink()
        with self.assertRaisesRegex(ValueError, 'signature'):
            module.bundle(self.root, self.output)
        self.assertFalse(self.output.exists())

    def test_missing_sources_are_rejected(self):
        next(self.folder.glob('*-sources.jar')).unlink()
        with self.assertRaisesRegex(ValueError, 'artifact'):
            module.bundle(self.root, self.output)

    def test_snapshot_is_rejected(self):
        self.pom.write_text(self.pom.read_text().replace('2.0.0-alpha.1', '2.0.0-SNAPSHOT'))
        with self.assertRaisesRegex(ValueError, 'release coordinates'):
            module.bundle(self.root, self.output)

    def test_wrong_namespace_is_rejected(self):
        self.pom.write_text(self.pom.read_text().replace('com.github.engage-media', 'other.group'))
        with self.assertRaisesRegex(ValueError, 'namespace'):
            module.bundle(self.root, self.output)

    def test_empty_repository_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'No Maven'):
            module.bundle(self.root / 'empty', self.output)

    def test_unspecified_license_is_rejected(self):
        self.pom.write_text(self.pom.read_text().replace(
            '<license><name>Fixture terms</name><url>https://example.invalid/license</url></license>', ''))
        with self.assertRaisesRegex(ValueError, 'release license'):
            module.bundle(self.root, self.output)


if __name__ == '__main__':
    unittest.main()
