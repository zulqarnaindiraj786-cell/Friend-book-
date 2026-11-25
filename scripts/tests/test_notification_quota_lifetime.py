from pathlib import Path
import unittest
JAVA=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class NotificationQuotaLifetime(unittest.TestCase):
 def test_bind_does_not_start_author_badge_requests(self):
  s=(JAVA/'main/NotificationsFragment.java').read_text().split('public void onBindViewHolder',1)[1]
  self.assertNotIn('TickCache.attach',s)
  self.assertIn('TickCache.peek',s)
  self.assertNotIn('Fb.db()',s)
 def test_lifecycle_and_pull_do_not_replace_pending_pager(self):
  s=(JAVA/'main/NotificationsFragment.java').read_text()
  self.assertIn('pager.isLoading()',s)
  self.assertIn('activeNotifications(me,generation)',s)
  self.assertIn('public void onDestroyView()',s)
  self.assertIn('notificationsStarted=false;stopPages()',s)
  self.assertNotIn('Load 20 more',s)
 def test_bulk_completion_repaints_without_reloading_list(self):
  s=(JAVA/'main/NotificationsFragment.java').read_text().split('private void finishMarkAll',1)[1].split('private void markAllFailed',1)[0]
  self.assertNotIn('startPages(',s)
  self.assertIn('markAllReadOptimisticNow()',s)
 def test_badge_bootstrap_uses_ten_rows_and_sixty_second_cache(self):
  s=(JAVA/'main/MainActivity.java').read_text().split('private void startBadgeListeners()',1)[1].split('// The badge only needs the rooms',1)[0]
  self.assertIn('.limit(10)',s)
  self.assertIn('.getCachedOrFetchAutomatic(badgeReadScope,900_000L)',s)
 def test_stable_ack_replay_has_no_scheduled_retry(self):
  s=(JAVA/'core/Notify.java').read_text()
  self.assertIn('ACKED_EVENTS.get(pendingKey)!=null',s)
  self.assertIn('FAILED_EVENTS.get(pendingKey)',s)
  self.assertNotIn('postDelayed',s)
