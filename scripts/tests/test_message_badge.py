"""Regression guard: a badge dismissal must never mark conversations or messages read."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

class MessageBadgeTest(unittest.TestCase):
    def test_clear_is_local_only(self):
        source = (ROOT / 'app/src/main/java/com/friendbook/social/main/MainActivity.java').read_text()
        clear = source.split('public static void clearMessagesBadgeNow() {', 1)[1].split('/**', 1)[0]
        self.assertIn('Ui.setBadge(inst.msgBadge, 0)', clear)
        self.assertIn('Prefs.setChatBadgeClearedAt', clear)
        for forbidden in ['Fb.db()', 'unreadCount', 'isSeen', 'isDelivered', '.commit()', '.update(', '.set(', '.get()']:
            self.assertNotIn(forbidden, clear)

    def test_inbox_retains_server_unread_counts(self):
        source = (ROOT / 'app/src/main/java/com/friendbook/social/chat/ConversationsActivity.java').read_text()
        self.assertIn('MainActivity.clearMessagesBadgeNow()', source)
        self.assertIn('r.unread = ((Number) n).intValue()', source)

    def test_seen_receipts_stay_in_individual_chat(self):
        source = (ROOT / 'app/src/main/java/com/friendbook/social/chat/ChatActivity.java').read_text()
        self.assertIn('patch.put("isSeen", true)', source)
        self.assertIn('document(chatId)', source)

if __name__ == '__main__':
    unittest.main()
