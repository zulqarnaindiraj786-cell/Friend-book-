from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2] / 'cloudflare'


class CanonicalListAuthorization(unittest.TestCase):
    def test_feed_and_compatibility_paths_carry_only_internal_raw_snapshots(self):
        source = (ROOT / 'firestore-documents.js').read_text()
        self.assertIn("const canonicalContentSnapshot=Symbol('canonicalContentSnapshot')", source)
        helper = source.split('function canonicalContentDocument(row)', 1)[1].split('async function readRootStorageRow', 1)[0]
        self.assertIn('toFirestoreFields(row.document_json)', helper)
        self.assertIn('doc.parent===parts[0]', helper)
        self.assertIn('doc.path===`${doc.parent}/${doc.id}`', helper)
        self.assertIn('odd.map(canonicalContentDocument)', source)
        self.assertIn('(canonical.results||[]).map(canonicalContentDocument)', source)
        self.assertEqual(source.count("rows.map(row=>['posts','reels','stories'].includes(parent)?canonicalContentDocument(row):rowToDocument(row))"), 2)
        self.assertIn('includeArchivedOwner,doc[canonicalContentSnapshot])', source)

    def test_old_endpoint_uses_loaded_raw_content_but_keeps_fresh_dependency_checks(self):
        source = (ROOT / 'app-documents.js').read_text()
        self.assertIn('canonicalContentReads.set(docPath,toFirestoreFields(row.document_json))', source)
        self.assertIn('userReads,false,canonicalContentReads.get(`${collection}/${doc.id}`)', source)
        policy = (ROOT / 'firebase-post-access.js').read_text()
        self.assertIn('const post = canonicalFields ?? await fields(`${collection}/${id}`)', policy)
        for check in ['me.banned', 'author.banned', "list(author, 'blocked').includes(uid)", 'trustedGroupOwner(db, groupPath)', 'hasD1Friendship(db, uid, owner)']:
            self.assertIn(check, policy)
