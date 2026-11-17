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
        # Mark-all must still exist and still be an explicit action. The write is a D1 batch now
        # (the Firestore update was refused with Firestore off, which is why the badge came back),
        # and the read that feeds it is a D1 query too - the old Firestore get() read the local
        # disk cache, so mark-all-read silently skipped notifications never cached on device.
        self.assertIn('batch.update("notifications", String.valueOf(idObj), Fb.map("isRead", true))', source)
        self.assertIn('DocQuery.of("notifications")', source)
        self.assertIn('markAll.setOnClickListener(v -> markAllReadReal())', source)
        self.assertNotIn('markFriendRequestsSeen', (JAVA/'friends/FriendsFragment.java').read_text())
    def test_cloudflare_websocket_presence_replaces_rtdb(self):
        """Presence now uses Cloudflare WebSocket + Firestore heartbeat, RTDB only for activeCall."""
        source=(JAVA/'core/Presence.java').read_text()
        # Cloudflare WebSocket presence
        self.assertIn('CloudPresenceSocket', source)
        self.assertIn('foreground(activeStatusVisible)', source)
        self.assertIn('cloudSocket.background()', source)
        # Firestore heartbeat (persisted, survives app kill)
        self.assertIn('FIRESTORE_HEARTBEAT', source)
        self.assertIn('writeFirestore(uid, true)', source)
        # RTDB kept ONLY for serverTimeOffset (call signaling sync) and activeCall
        self.assertIn('.info/serverTimeOffset', source)
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
        # New implementation: uses cached user.lastSeen, NOT live presence listeners
        self.assertIn('long lastSeen = u.lastSeen',strip)
        self.assertIn('boolean online = lastSeen > 0 && (now - lastSeen) < 300_000',strip)
        self.assertIn('if (!online && !recentlyOnline) continue',strip)
        self.assertNotIn('u.isOnline', strip)
        self.assertNotIn('online && r.unread == 0',source)
        self.assertIn('h.dot.setVisibility(online ? View.VISIBLE : View.GONE)',source)
        self.assertIn('h.lastActive.setVisibility(!online && seen > 0',source)
        # onStop no longer has handle.close() because no presence listeners in chat list
        stop=method(source,'protected void onStop()')
        self.assertIn('chatsListenerAttached = false', stop)
        self.assertIn('friendsLoaded = false', stop)
    def test_chat_profile_use_the_shared_presence_policy(self):
        for file in ['chat/ChatActivity.java','profile/ProfileActivity.java']:
            self.assertIn('Presence.watch(', (JAVA/file).read_text())
            self.assertIn('Presence.formatActiveStatus(', (JAVA/file).read_text())
if __name__=='__main__':unittest.main()
