from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]
class AndroidPrWorkflow(unittest.TestCase):
    def test_pr_compiles_and_tests_without_packaging_and_uses_verified_runtime(self):
        code=(ROOT/'.github/workflows/debug-apk.yml').read_text().split('  signed-candidate:',1)[0]
        self.assertIn("github.event_name == 'pull_request' ||",code)
        self.assertEqual(code.count("github.event_name != 'pull_request' &&"),2)
        self.assertIn('bash scripts/ci/fetch_robolectric_jars.sh',code)
        self.assertIn('robolectric.dependency.repo.url=https://repo.maven.apache.org/maven2',code)
        fetch=(ROOT/'scripts/ci/fetch_robolectric_jars.sh').read_text()
        for token in ['sha512sum --check --status','--retry-all-errors','15-robolectric','9-robolectric',
                      'repo1.maven.org/maven2','maven-central.storage-download.googleapis.com/maven2']:
            self.assertIn(token,fetch)
        self.assertIn('"$hash" "$jar.tmp" | sha512sum --check --status', fetch)
