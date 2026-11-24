"""Source ownership guards, complemented by actual Handle conflict/cancellation JVM tests."""
from pathlib import Path
import unittest
JAVA=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class CommentLoopLifecycle(unittest.TestCase):
 def test_destroy_releases_handles_even_when_not_finishing(self):
  s=(JAVA/'post/PostDetailActivity.java').read_text().split('protected void onDestroy()',1)[1].split('private void send()',1)[0]
  self.assertNotIn('if (isFinishing())',s)
  for text in ['postReg.remove(); postReg = null','commentsHandle.remove(); commentsHandle = null','FastCache.removeReactionObserver','super.onDestroy()']:self.assertIn(text,s)
 def test_conflict_has_no_immediate_recursive_read_and_dispose_cancels_wait(self):
  s=(JAVA/'post/CommentsEngine.java').read_text()
  conflict=s.split('if(requestedAtRevision!=revision)',1)[1].split('if(detailBundle && first)',1)[0]
  self.assertIn('conflictAttempts>=3',conflict)
  self.assertIn('pageHandler.postDelayed(conflictRetry,2_000L)',conflict)
  self.assertNotIn('d1Page(first,background)',conflict)
  self.assertIn('closed=true;cancelConflictRetry();clearPageDeadline()',s)
