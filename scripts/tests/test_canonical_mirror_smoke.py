import unittest
from pathlib import Path


class CanonicalMirrorSmokeHarnessTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root=Path(__file__).resolve().parents[2]
        cls.script=(cls.root/'scripts/loadtest/canonical_mirror_smoke.mjs').read_text()
        cls.workflow=(cls.root/'.github/workflows/canonical-mirror-smoke.yml').read_text()

    def test_only_disposable_random_firebase_identities_are_created(self):
        self.assertIn('cf-canonical-smoke-${randomUUID()}@example.com',self.script)
        self.assertIn("firebase('accounts:delete',{idToken:account.token})",self.script)
        self.assertIn('finally{',self.script)

    def test_cleanup_is_limited_to_generated_paths_and_verified(self):
        self.assertIn('DELETE FROM friendbook_cf_firestore_documents WHERE path IN (${marks})',self.script)
        self.assertIn('DELETE FROM friendbook_cf_documents WHERE path IN (${marks})',self.script)
        self.assertIn("d1CleanupOk=remaining.results?.[0]?.n===0",self.script)
        self.assertNotIn('DELETE FROM friendbook_cf_firestore_documents"',self.script)
        self.assertNotIn('DELETE FROM friendbook_cf_documents"',self.script)

    def test_no_user_data_or_tokens_are_logged_and_manual_workflow_is_confirmed(self):
        self.assertIn('Never logs emails, UIDs, tokens, or document data',self.script)
        self.assertIn('confirm_production_smoke',self.workflow)
        self.assertIn('inputs.confirm_production_smoke == true',self.workflow)
        self.assertIn('CLOUDFLARE_API_TOKEN: ${{ secrets.CLOUDFLARE_API_TOKEN }}',self.workflow)


if __name__=='__main__':unittest.main()
