"""Wiring guards complement deterministic Java time/presence policy tests."""
import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT/'app/src/main/java/com/friendbook/social'
def method(source, name):
    body = source.split(name,1)[1]; body=body[body.index('{')+1:]; depth=1
    for i,c in enumerate(body):
        depth += (c=='{')-(c=='}')
        if depth==0:return body[:i]
    raise AssertionError('unbalanced method')
class PresenceBadgeTest(unittest.TestCase):
    def test_badge_clears_never_mutate_entities(self):
        source = (JAVA/'main/MainActivity.java').read_text()
        for name in ['clearNotificationsBadgeNow()', 'clearFriendsBadgeNow()', 'clearMessagesBadgeNow()']:
            body=method(source, 'public static void '+name)
            for forbidden in ['Fb.db()', 'batch.update', 'markAllReadOptimisticNow', 'seenByReceiver', '"isRead"', '"isSeen"', 'unreadCount']:
                self.assertNotIn(forbidden, body, (name, forbidden))
        self.assertIn('Prefs.dismissFriendRequestBadge', source)
    def test_notification_tab_is_not_mark_all_action(self):
        source=(JAVA/'main/NotificationsFragment.java').read_text()
        for name in ['public void onStart()', 'public void onHiddenChanged(boolean hidden)']:
            self.assertNotIn('markAllReadOptimisticNow', method(source,name))
        self.assertIn('document(n.id).update("isRead", true)', source)
        self.assertIn('markAll.setOnClickListener(v -> markAllReadReal())', source)
        self.assertNotIn('markFriendRequestsSeen', (JAVA/'friends/FriendsFragment.java').read_text())
    def test_cloudflare_websocket_presence_replaces_rtdb(self):
        """Presence now uses Cloudflare WebSocket + Firestore heartbeat, RTDB only for activeCall."""
        source=(JAVA/'core/Presence.java').read_text()
        # Cloudflare WebSocket presence
        self.assertIn('CloudPresenceSocket', source)
        self.assertIn('foreground(activeStatusVisible)', source)
        self.assertIn('cloudSocket.background()', source)
        # D1-backed document heartbeat persists profile presence.
        self.assertIn('FIRESTORE_HEARTBEAT', source)
        self.assertIn('writeFirestore(uid, true)', source)
        # Call signaling is also stored in the D1-backed chat document; RTDB is absent.
        self.assertNotIn('.info/serverTimeOffset', source)
        self.assertIn('D1-backed chats document', source)
        self.assertIn('startCallSignal', source)
        self.assertIn('setCallSignal', source)
        # Old RTDB presence patterns REMOVED
        self.assertNotIn('onDisconnect().updateChildren(status(false)', source)
        self.assertNotIn('disconnectArmed', source)
        self.assertNotIn('statusRef', source)
        self.assertNotIn('connListener', source)
        self.assertNotIn('m.put("lastSeen", ServerValue.TIMESTAMP)', source)
    def test_active_strip_uses_live_presence_not_cached_flags(self):
        source=(JAVA/'chat/ConversationsActivity.java').read_text()
        strip=method(source,'private void renderActiveNow()')
        self.assertIn('Boolean.TRUE.equals(onlineUsers.get(u.uid))',strip)
        self.assertIn('if (!online) continue',strip)
        self.assertNotIn('u.isOnline', strip)
        self.assertNotIn('online && r.unread == 0',source)
        self.assertIn('h.dot.setVisibility(online ? View.VISIBLE : View.GONE)',source)
        self.assertIn('h.lastActive.setVisibility(!online && seen > 0',source)
        self.assertIn('handle.close()',method(source,'protected void onStop()'))
    def test_chat_profile_use_the_shared_presence_policy(self):
        for file in ['chat/ChatActivity.java','profile/ProfileActivity.java']:
            self.assertIn('Presence.watch(', (JAVA/file).read_text())
            self.assertIn('Presence.formatActiveStatus(', (JAVA/file).read_text())
if __name__=='__main__':unittest.main()
