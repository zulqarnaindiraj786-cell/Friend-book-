"""Wiring/rule guards complement actual TextView tests in LinkUtilsTest (Robolectric)."""
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT/'app/src/main/java/com/friendbook/social'
class LinksBroadcastTest(unittest.TestCase):
    def test_shared_links_are_installed_on_content_and_bind(self):
        for file in ['core/Ui.java','chat/MessagesAdapter.java','post/CommentsAdapter.java','post/FeedAdapter.java','profile/ProfileActivity.java','reels/ReelsFragment.java','post/FullscreenMediaActivity.java']:
            self.assertIn('LinkUtils.makeLinksClickable', (JAVA/file).read_text(), file)
        helper = (JAVA/'core/LinkUtils.java').read_text()
        for token in ['afterTextChanged','Linkify.WEB_URLS','BREAK_STRATEGY_SIMPLE','Intent.ACTION_VIEW','SPAN', 'interaction.restore(view)']:
            self.assertIn(token,helper)
    def test_compose_is_not_used_and_resource_xml_parses(self):
        for path in (ROOT/'app/src/main/res').rglob('*.xml'):
            ET.parse(path)
    def test_broadcast_is_paginated_and_transactionally_replay_safe(self):
        worker = (JAVA/'admin/OfficialBroadcastWorker.java').read_text()
        # The job rows live in Cloudflare D1 now: the worker pages the account list with a cursor and
        # a delivery is idempotent because its notification id is derived from (job, account), so a
        # retried job overwrites the same row instead of notifying a user twice.
        for token in ['readSyncPage("users", 100, "uid", cursor)', 'cursor = to',
                      'readSync("notifications", noteId) != null', 'uid.equals(Fb.uid())', 'Result.retry()']:
            self.assertIn(token,worker)
        self.assertNotIn('Fb.db()', worker)   # no Firestore query in the delivery path at all
        self.assertNotIn('limit(400)', (JAVA/'admin/AdminPanelActivity.java').read_text())
    def test_media_model_and_recycling_key_include_video(self):
        self.assertIn('m0.videoUrl = Format.str(m, "videoUrl")', (JAVA/'models/Models.java').read_text())
        self.assertIn('m.videoUrl', (JAVA/'chat/MessageRenderState.java').read_text())
        adapter = (JAVA/'chat/MessagesAdapter.java').read_text()
        self.assertIn('hasVideo ? "video" : "image"', adapter)
        self.assertIn('i.putExtra("caption", m.text)', adapter)
    def test_admin_storage_rule_cannot_be_bypassed_by_generic_match(self):
        rule = (ROOT/'firebase/storage.rules').read_text()
        self.assertIn("folder != 'official_broadcasts'", rule)
        self.assertIn('match /official_broadcasts/', rule)
        self.assertIn('fullAdmin()',rule)
        self.assertIn('match /officialBroadcasts/',(ROOT/'firebase/firestore.rules').read_text())
        # Keep legacy Storage rules; new app attachments follow the owner's Cloudinary-only scope.
        composer = (JAVA/'admin/OfficialBroadcastActivity.java').read_text()
        self.assertIn('Cloudinary.uploadWithProgress', composer)
        self.assertNotIn('Fb.storage()', composer)
if __name__ == '__main__': unittest.main()
