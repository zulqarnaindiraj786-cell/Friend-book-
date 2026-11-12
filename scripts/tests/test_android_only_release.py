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
                         'friendbook-mapping.txt',
                         'Reject private credentials in Android sources',
                         'Publish Signed Release APK + Play Store AAB'):
            self.assertIn(required, workflow)
        self.assertIn('contents: write', workflow)
        # The three binaries are still produced and published - as GitHub Release assets, because the
        # account's Actions artifact storage is exhausted and a failed convenience upload used to turn
        # a successful compile into a red run. A release asset cannot be lost that way.
        self.assertIn('gh release', workflow)
        self.assertIn('publish release upload "$TAG" friendbook-playstore.aab friendbook.apk friendbook-mapping.txt --clobber', workflow)
        # Test lines publish a PRE-release with their own tag: the production "latest" APK that
        # in-app updates serve is only ever written from main.
        self.assertIn('--prerelease --latest=false', workflow)
        self.assertIn('REF_NAME', workflow)

    def test_no_actions_artifact_upload_can_block_the_build(self):
        workflow = WORKFLOW.read_text()
        self.assertNotIn('uses: actions/upload-artifact', workflow)
        self.assertNotIn('retention-days:', workflow)
