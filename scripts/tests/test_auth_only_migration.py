"""Offline inventory behavior + source guards; NOT Android upload/runtime tests."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from scripts.admin.inventory_backend_dependencies import inventory, without_comments

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'app/src/main/java/com/friendbook/social'


class BackendInventoryTest(unittest.TestCase):
    def make_root(self, folder):
        root = Path(folder)
        (root / 'app/src/main/java').mkdir(parents=True)
        (root / 'cloudflare/test').mkdir(parents=True)
        (root / 'worker.js').write_text('export default {};')
        return root

    def test_auth_fcm_allowed_but_mixed_file_is_not_exempt(self):
        with tempfile.TemporaryDirectory() as folder:
            root = self.make_root(folder)
            source = root / 'app/src/main/java/Client.java'
            source.write_text('import com.google.firebase.auth.FirebaseAuth;\n'
                              'import com.google.firebase.messaging.FirebaseMessaging;')
            result = inventory(root)
            self.assertEqual(result['source_reference_gate'], 'PASS')
            self.assertEqual(result['allowed_auth_and_delivery'][0]['services'],
                             ['fcm_delivery', 'firebase_auth'])
            self.assertFalse(result['safe_to_cutover'])
            self.assertFalse(result['capacity_verified'])
            source.write_text(source.read_text() + '\nFirebaseFirestore.getInstance().collection("chats");\n'
                              'Fb.rtdb().getReference("typing"); FirebaseRemoteConfig.getInstance();')
            result = inventory(root)
            self.assertEqual(result['source_reference_gate'], 'BLOCKED')
            self.assertEqual(result['literal_collection_names'], ['chats'])
            self.assertEqual(result['literal_rtdb_paths'], ['typing'])
            self.assertEqual(result['remaining_by_service']['firestore'], 1)
            self.assertEqual(result['remaining_by_service']['firebase_remote_config'], 1)

    def test_fb_db_is_exempt_only_when_production_d1_adapter_is_verified(self):
        with tempfile.TemporaryDirectory() as folder:
            root = self.make_root(folder)
            (root / 'app/src/main/java/Client.java').write_text('Fb.db().collection("users");')
            report = inventory(root)
            self.assertEqual(report['remaining_by_service']['firestore'], 1)

    def test_backend_firestore_and_supabase_are_in_scope_not_tests_or_comments(self):
        with tempfile.TemporaryDirectory() as folder:
            root = self.make_root(folder)
            (root / 'cloudflare/policy.js').write_text(
                'const url = "https://firestore.googleapis.com/v1/documents";\n'
                'const oldBackend = "https://example.supabase.co/functions/v1/send-fcm";')
            (root / 'cloudflare/test/private.test.js').write_text('FirebaseStorage.getInstance();')
            (root / 'cloudflare/notes.js').write_text('// FirebaseDatabase\n/* FirebaseStorage */')
            report = inventory(root)
            self.assertEqual(report['remaining_state_dependency_files'], 1)
            self.assertEqual(report['remaining'][0]['services'], ['firestore', 'supabase_backend'])
            self.assertEqual(report['remaining_by_service']['firebase_storage'], 0)
            self.assertEqual(without_comments('"https://example.invalid"; // hi\n'),
                             '"https://example.invalid"; \n')

    def test_missing_source_roots_cannot_get_a_false_pass(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(ValueError):
                inventory(Path(folder))

    def test_source_symlinks_cannot_read_outside_repo(self):
        with tempfile.TemporaryDirectory() as folder, tempfile.TemporaryDirectory() as outside:
            root = self.make_root(folder)
            private = Path(outside) / 'Private.java'
            private.write_text('FirebaseFirestore')
            (root / 'app/src/main/java/Escape.java').symlink_to(private)
            with self.assertRaises(ValueError):
                inventory(root)

    def test_cli_check_returns_nonzero_and_never_overwrites_a_report(self):
        with tempfile.TemporaryDirectory() as folder:
            root = self.make_root(folder)
            (root / 'app/src/main/java/Client.java').write_text('FirebaseFirestore.getInstance().collection("users");')
            report = root / 'inventory.json'
            command = [sys.executable, str(ROOT / 'scripts/admin/inventory_backend_dependencies.py'),
                       '--root', str(root), '--check', '--output', str(report)]
            run = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(run.returncode, 2)
            self.assertEqual(json.loads(report.read_text())['source_reference_gate'], 'BLOCKED')
            original = report.read_bytes()
            self.assertNotEqual(subprocess.run(command, capture_output=True).returncode, 0)
            self.assertEqual(report.read_bytes(), original)


class CloudinaryOnlySourceGuards(unittest.TestCase):
    def test_no_firebase_storage_sdk_or_silent_fallback_in_new_client_source(self):
        for source in JAVA.rglob('*.java'):
            text = source.read_text()
            self.assertNotIn('com.google.firebase.storage', text, str(source))
            self.assertNotIn('Fb.storage(', text, str(source))
            self.assertNotRegex(text, r'\b(?:StorageReference|UploadTask)\b', str(source))
        self.assertNotIn('com.google.firebase:firebase-storage', (ROOT / 'app/build.gradle').read_text())
        uploader = (JAVA / 'core/Cloudinary.java').read_text()
        wrapper = uploader[uploader.index('public static Task<String> uploadOrFirebaseWithProgress'):]
        self.assertIn('return uploadWithProgress(ctx, uri, progressListener)', wrapper)
        self.assertNotIn('addOnCompleteListener', wrapper)
        self.assertNotIn('putFile', wrapper)

    def test_broadcast_new_upload_cloudinary_old_urls_retained_account_guard_kept(self):
        source = (JAVA / 'admin/OfficialBroadcastActivity.java').read_text()
        for token in ['Cloudinary.uploadWithProgress', 'getApplicationContext()',
                      'Task<String> pendingUpload', 'if (!uid.equals(Fb.uid())',
                      'mediaUrl = state.getString("url", "")', 'if (pendingUpload != task) return;',
                      'uploadPath = state.getString("path", "")']:
            self.assertIn(token, source)
        self.assertNotIn('putFile(', source)
        self.assertNotIn('getDownloadUrl(', source)
        self.assertIn('queueOfficialBroadcast(jobId, text, mediaUrl, mediaType)', source)
