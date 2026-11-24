from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class FeedLifetimeContract(unittest.TestCase):
 def test_hide_stop_destroy_all_invalidate_pending_read_ownership(self):
  s=(ROOT/'main/HomeFragment.java').read_text()
  for method in ['void onHiddenChanged(boolean hidden)','void onStop()','void onDestroyView()']:
   self.assertIn('invalidateHomeReads()',s.split(method,1)[1][:400])
  self.assertIn('engagementHandler.removeCallbacksAndMessages(null)',s)
  self.assertIn('if(storyCursor==null && loadedStoryDocs.isEmpty())storyQuery=null',s)
 def test_notes_callbacks_check_epoch_before_changing_current_busy_flag(self):
  s=(ROOT/'main/HomeFragment.java').read_text().split('private void loadNotesUsers(boolean forced)',1)[1].split('private View filterChips',1)[0]
  success=s.split('addOnSuccessListener',1)[1]
  self.assertLess(success.index('activeHomeRead(actor,readGeneration)'),success.index('notesInFlight = false'))
  self.assertIn('activeHomeRead(actor,readGeneration) && notesReadAt==attempt',success)
  self.assertIn('forced ? suggestions.getCachedOrFetch(true)',s)
  self.assertIn('suggestions.getCachedOrFetchAutomatic(homeReadScope(),Long.MAX_VALUE)',s)
