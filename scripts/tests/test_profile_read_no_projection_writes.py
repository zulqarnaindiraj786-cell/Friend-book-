from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]
JAVA=ROOT/'app/src/main/java/com/friendbook/social'
class ProfileReadNoProjectionWrites(unittest.TestCase):
 def test_profile_snapshot_has_no_second_network_write_path(self):
  source=(JAVA/'core/Fb.java').read_text()
  for removed in ['syncProfileToCloudflare(', 'CF_PROFILE_IO', 'lastCloudflareProfileKey', 'D1HttpTransport.send(']:
   self.assertNotIn(removed,source)
  self.assertIn('FastCache.saveMe(',source)
  self.assertIn('observer.changed(u)',source)
  self.assertIn('auth().signOut()',source)
  self.assertIn('.create(',source)
  self.assertIn('.set(fields, SetOptions.merge())',source)
 def test_backend_projection_compatibility_is_retained(self):
  worker=(ROOT/'worker.js').read_text()
  backend=(ROOT/'cloudflare/app-documents.js').read_text()
  self.assertIn("path === '/v1/me'",worker)
  self.assertIn('saveProfileProjection(env.DB,uid,displayName,avatarUrl)',worker)
  self.assertIn('export function saveProfileProjection(',backend)
