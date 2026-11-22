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
        # Hidden-status startup relies on goOffline for the current user; an
        # additional write is only required after switching bound accounts.
        init=method(source,'public static void init()')
        self.assertIn('boolean oldAccountBound = boundUid != null && !uid.equals(boundUid)',init)
        self.assertIn('if (oldAccountBound) writeOfflineInternal(uid)',init)
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
    def test_background_process_does_not_start_presence_or_profile_listener(self):
        source=(JAVA/'App.java').read_text()
        on_create=method(source,'public void onCreate()')
        before_lifecycle=on_create.split('registerActivityLifecycleCallbacks',1)[0]
        self.assertNotIn('Presence.init()',before_lifecycle)
        self.assertNotIn('Fb.startMeListener()',before_lifecycle)
        self.assertNotIn('Fb.ensureMeFresh()',before_lifecycle)
        started=method(source,'public void onActivityStarted(Activity a)')
        self.assertIn('startedCount == 0 && Fb.uid() != null',started)
        self.assertIn('Presence.init()',started)
        self.assertIn('Fb.ensureMeFresh()',started)
        stopped=method(source,'public void onActivityStopped(Activity a)')
        self.assertIn('timeHandler.postDelayed(deferredOffline, 500L)',stopped)
        self.assertIn('Presence.goOffline()',source.split('private final Runnable deferredOffline',1)[1].split('private final Runnable timeTick',1)[0])
        self.assertIn('PushManager.init(this)',before_lifecycle)
    def test_online_status_and_typing_are_off_without_disabling_calls(self):
        gradle=(ROOT/'app/build.gradle').read_text()
        self.assertIn("buildConfigField 'boolean', 'ONLINE_STATUS_ENABLED', 'false'",gradle)
        self.assertIn("buildConfigField 'boolean', 'TYPING_ENABLED', 'false'",gradle)
        self.assertIn("buildConfigField 'boolean', 'CLOUDFLARE_PRESENCE_ENABLED', 'true'",gradle)
        source=(JAVA/'core/Presence.java').read_text()
        for name in ['public static void init()', 'public static void goOffline()', 'public static void setActiveStatusVisible(boolean visible)']:
            body=method(source,name)
            self.assertIn('if (!BuildConfig.ONLINE_STATUS_ENABLED) return;',body)
        watch=method(source,'public static Handle watchPresence(String uid, PresenceCallback cb)')
        self.assertLess(watch.index('if (!BuildConfig.ONLINE_STATUS_ENABLED)'),watch.index('Fb.db()'))
        self.assertIn('cb.onPresence(false, 0L)',watch)
        write=method(source,'public static void setTyping(String chatId, boolean typing)')
        self.assertLess(write.index('if (!BuildConfig.TYPING_ENABLED) return;'),write.index('Fb.uid()'))
        typing=method(source,'public static Handle watchTyping(')
        self.assertLess(typing.index('if (!BuildConfig.TYPING_ENABLED)'),typing.index('Fb.db()'))
        self.assertIn('cb.accept(false)',typing)
        chat=(JAVA/'chat/ChatActivity.java').read_text()
        self.assertNotIn('Presence.setTyping(',chat)
        self.assertNotIn('Presence.watchTyping(',chat)
        self.assertIn('startCallSignal',source)
        self.assertIn('BuildConfig.ONLINE_STATUS_ENABLED && u.showActiveStatus', (JAVA/'models/Models.java').read_text())
        self.assertNotIn('Active status (Online green dot)',(JAVA/'settings/SettingsActivity.java').read_text())
    def test_manual_inbox_has_no_background_presence_reads(self):
        source=(JAVA/'chat/ConversationsActivity.java').read_text()
        self.assertNotIn('Presence.watch(',source)
        self.assertNotIn('addSnapshotListener',source)
        self.assertNotIn('Fb.getUser(',source)
        self.assertIn('inboxSwipe.setOnRefreshListener',source)
    def test_chat_profile_use_the_shared_presence_policy(self):
        for file in ['chat/ChatActivity.java','profile/ProfileActivity.java']:
            self.assertIn('Presence.watch(', (JAVA/file).read_text())
            self.assertIn('Presence.formatActiveStatus(', (JAVA/file).read_text())
if __name__=='__main__':unittest.main()
