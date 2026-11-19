from pathlib import Path
import unittest

JAVA = Path(__file__).resolve().parents[2] / 'app/src/main/java/com/friendbook/social'

class PkAndInboxCacheTest(unittest.TestCase):
    def test_home_tab_switch_preserves_open_inline_suggestions(self):
        friends = (JAVA / 'friends/FriendsFragment.java').read_text()
        self.assertIn('stopRequestWatch(); closePage(true);', friends)
        self.assertIn('!keepInline && suggestionPager!=null', friends)
        self.assertIn('me.equals(pageActor)', friends)
        self.assertIn('.orderBy("createdAt",Query.Direction.DESCENDING)', friends)

    def test_notification_head_is_cache_first_and_one_server_query(self):
        notifications = (JAVA / 'main/NotificationsFragment.java').read_text()
        start = notifications.split('private void startPages()', 1)[1].split('private void setUnreadOnly', 1)[0]
        self.assertIn('pager.startObserved()', start)
        self.assertIn('pager.acceptObservedFirstPage(snapshot)', start)
        self.assertNotIn('pager.start()', start)
        self.assertEqual(start.count('addSnapshotListener'), 1)

    def test_pk_search_is_bounded_and_guest_pause_uses_guest_engine(self):
        sheet = (JAVA / 'live/LivePkAndGiftsUi.java').read_text()
        self.assertIn('pkPeoplePrefix(typed).get()', sheet)
        self.assertIn('name + "\\uf8ff").limit(12)', sheet)
        live = (JAVA / 'live/LiveActivity.java').read_text()
        toggle = live.split('private void toggleActiveUserCameraPause()', 1)[1].split('private void toggleHostMic', 1)[0]
        self.assertIn('pkGuestEngine.setCameraPaused(paused)', toggle)
        self.assertIn('toggleHostCameraPause()', toggle)
        self.assertNotIn('setMicMuted', toggle)
