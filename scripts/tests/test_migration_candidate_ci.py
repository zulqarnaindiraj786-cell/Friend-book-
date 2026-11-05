import tempfile
from pathlib import Path
import unittest
from scripts.ci.verify_presence_android_tests import SUITES, verify

ROOT = Path(__file__).resolve().parents[2]


class MigrationCandidateCiTest(unittest.TestCase):
    def test_branch_build_cannot_clobber_public_production_release(self):
        source = (ROOT / '.github/workflows/build-apk.yml').read_text()
        publish = source[source.index('      - name: Publish Signed Release APK'):]
        self.assertIn("if: github.ref == 'refs/heads/main'", publish.split('        env:', 1)[0])
        self.assertIn('verify_presence_android_tests.py', source)
        self.assertIn('PresenceSocketControllerCheck', source)

    def write_suite(self, root, suite, counts='', cases='<testcase name="synthetic"/>'):
        (root / ('TEST-com.friendbook.social.core.' + suite + '.xml')).write_text(
            '<testsuite tests="1" ' + counts + '>' + cases + '</testsuite>')

    def test_all_required_android_suites_must_have_execution_evidence(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            with self.assertRaises(ValueError):
                verify(root)
            for name in SUITES:
                self.write_suite(root, name)
            self.assertEqual(verify(root), [(name, 1) for name in SUITES])
            self.write_suite(root, SUITES[0], 'skipped="1"')
            with self.assertRaises(ValueError):
                verify(root)
            self.write_suite(root, SUITES[0], 'failures="1"')
            with self.assertRaises(ValueError):
                verify(root)
            self.write_suite(root, SUITES[0], cases='')
            with self.assertRaises(ValueError):
                verify(root)
