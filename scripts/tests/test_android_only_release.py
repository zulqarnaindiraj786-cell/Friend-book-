"""Keep the Android publishing workflow limited to the requested APK/AAB release."""
from pathlib import Path
import unittest

WORKFLOW = Path(__file__).parents[2] / '.github/workflows/build-apk.yml'


class AndroidOnlyReleaseTests(unittest.TestCase):
    def test_no_website_build_or_pages_deployment(self):
        workflow = WORKFLOW.read_text()
        for forbidden in ('friendbook-website.zip', 'docs/friendbook.apk',
                          'upload-pages-artifact', 'configure-pages', 'deploy-pages',
                          'deploy-website:', 'pages: write', 'id-token: write'):
            self.assertNotIn(forbidden, workflow)

    def test_signed_apk_aab_tests_and_mapping_are_retained(self):
        workflow = WORKFLOW.read_text()
        for required in ('testReleaseUnitTest bundleRelease assembleRelease',
                         'friendbook-playstore.aab', 'friendbook.apk',
                         'friendbook-mapping.txt', 'FriendBook-release-mapping',
                         'Reject private credentials in Android sources',
                         'Publish Signed Release APK + Play Store AAB'):
            self.assertIn(required, workflow)
        self.assertIn('contents: write', workflow)
