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
    def test_removed_status_code_and_call_wiring(self):
        for file in JAVA.rglob('*.java'):
            text=file.read_text().lower()
            self.assertNotIn('presence', text, str(file))
            self.assertNotIn('typing', text, str(file))
        chat=(JAVA/'chat/ChatActivity.java').read_text()
        self.assertIn('CallActivity.maybeHandleIncomingFromRoomDoc', chat)
        self.assertIn('RealtimeSoundGate(System.currentTimeMillis())', chat)
        self.assertIn('setOnRefreshListener', chat)
        for file in ['chat/NewChatActivity.java', 'live/LivePkAndGiftsUi.java']:
            self.assertNotIn('Active now', (JAVA/file).read_text())

    def test_background_start_does_not_start_profile_listener(self):
        source=(JAVA/'App.java').read_text()
        before=method(source,'public void onCreate()').split('registerActivityLifecycleCallbacks',1)[0]
        self.assertNotIn('Fb.startMeListener()',before)
        self.assertNotIn('Fb.ensureMeFresh()',before)
        self.assertIn('Fb.ensureMeFresh()',method(source,'public void onActivityStarted(Activity a)'))

    def test_manual_inbox_is_on_demand(self):
        source=(JAVA/'chat/ConversationsActivity.java').read_text()
        self.assertNotIn('addSnapshotListener',source)
        self.assertNotIn('Fb.getUser(',source)
        self.assertIn('inboxSwipe.setOnRefreshListener',source)
if __name__=='__main__':unittest.main()
