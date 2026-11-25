from pathlib import Path
import unittest
JAVA=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class NotificationOnDemand(unittest.TestCase):
    def test_first_page_and_refresh_use_explicit_cache_mode(self):
        s=(JAVA/'main/NotificationsFragment.java').read_text()
        self.assertIn('PAGE_SIZE = 10',s)
        self.assertIn('pager.startCachedForForeground(900_000L)',s)
        self.assertIn('pager.refreshOnDemand()',s)
        self.assertNotIn('pager.startDelta()',s)
        self.assertNotIn('addSnapshotListener',s)
        self.assertIn('setItemPrefetchEnabled(false)',s)
        self.assertIn('pageGate.shouldLoad(dy',s)
        self.assertIn('isResumed() && !isHidden()',s)
    def test_mark_all_is_only_explicit_and_small_batched(self):
        s=(JAVA/'main/NotificationsFragment.java').read_text()
        self.assertIn('markAll.setOnClickListener(v -> markAllReadReal())',s)
        self.assertIn('MARK_READ_BATCH_SIZE = 10',s)
        self.assertNotIn('.limit(450)',s)
        self.assertIn('unread.startAfter(cursor)',s)
        self.assertIn('me == null || markingAll',s)
        self.assertIn('generation!=markReadGeneration',s)
        self.assertIn('pageGeneration++;markReadGeneration++;markingAll=false',s)
        self.assertIn('handler.removeCallbacks(deadline);markAllFailed(generation)',s)
        for method in ['public void onStart()', 'public void onHiddenChanged(boolean hidden)']:
            body=s.split(method,1)[1].split('\n    private ',1)[0]
            self.assertNotIn('markUnreadBatch(',body)
    def test_pager_cache_mode_is_opt_in_and_handles_empty_pages(self):
        s=(JAVA/'core/CursorPager.java').read_text()
        self.assertIn('reuseExactPages=true; startLocal(Long.MAX_VALUE,true)',s)
        self.assertIn('cached!=null && maxAgeMs>0 && head.hasFreshLocalPage(maxAgeMs)',s)
        self.assertIn('bounded.getCachedOrFetch(false) : bounded.get()',s)
        self.assertIn('isFromCache() && !reuseExactPages',s)
    def test_opening_same_notification_does_not_repeat_write(self):
        s=(JAVA/'main/NotificationsFragment.java').read_text()
        self.assertIn('!n.isRead && !confirmedReadIds.contains(n.id) && !failedReadIds.contains(n.id) && pendingReadIds.add(n.id)',s)
        self.assertIn('n.isRead=true',s)
        self.assertIn('pager.forget(n.id)',s)
