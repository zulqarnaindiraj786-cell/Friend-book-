"""Messenger source wiring checks; these do not substitute for device gestures/layout tests."""
import unittest
from pathlib import Path

JAVA = Path(__file__).resolve().parents[2] / 'app/src/main/java/com/friendbook/social'

class MessengerCacheAndFooter(unittest.TestCase):
    def test_footer_requires_user_bottom_and_real_lookahead(self):
        inbox = (JAVA / 'chat/ConversationsActivity.java').read_text()
        self.assertNotIn('inboxSpinner', inbox)
        self.assertIn('reachedInboxBottom=dy>0 && !list.canScrollVertically(1)', inbox)
        self.assertIn('query.limit(INBOX_PAGE_SIZE)', inbox)
        self.assertIn('inboxMore=snap.size()==INBOX_PAGE_SIZE', inbox)
        self.assertIn('inboxMoreButton.setOnClickListener(v -> loadInboxPage(false))', inbox)

    def test_cache_is_rendered_without_read_receipts_or_notification_side_effects(self):
        chat = (JAVA / 'chat/ChatActivity.java').read_text()
        preview = chat.split('if(snap.getMetadata().isFromCache()) {', 1)[1].split('return; // Display only', 1)[0]
        self.assertIn('adapter.setData(preview)', preview)
        self.assertIn('hidden.contains(message.id)', preview)
        for token in ('markVisibleReceipts', 'handOffInboxHead', 'playNewMessage', 'messageSnapshots.put'):
            self.assertNotIn(token, preview)

    def test_chat_reopens_with_exact_cached_revisions(self):
        store = (JAVA / 'd1/documents/D1DocumentStore.java').read_text()
        self.assertIn('restoreChatRevisions(entry,cached)', store)
        self.assertIn('chatRowsForCache(arr,entry.revisions)', store)
        self.assertIn('entry.revisions=known;entry.priorRows=rows', store)

    def test_inbox_rows_are_bounded_and_diffed(self):
        inbox = (JAVA / 'chat/ConversationsActivity.java').read_text()
        self.assertNotIn('notifyDataSetChanged', inbox)
        self.assertIn('DiffUtil.calculateDiff', inbox)
        self.assertIn('row.setClipChildren(true)', inbox)
        self.assertIn('last.setEllipsize(android.text.TextUtils.TruncateAt.END)', inbox)
