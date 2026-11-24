from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class LikeCommentActions(unittest.TestCase):
 def test_comment_mutations_pending_and_cooldown_and_actor_guard(self):
  s=(ROOT/'post/CommentsEngine.java').read_text()
  block=s.split('private static void d1Mutation(String collection,String id,org.json.JSONObject body,Runnable failed)',1)[1].split('public interface Listener',1)[0]
  for token in ['MUTATIONS.contains(key)','InteractionCooldown.acquire','MUTATIONS.add(key)','MUTATIONS.remove(key)','java.util.Objects.equals(actor,Fb.uid())','if(failed!=null)failed.run()']:self.assertIn(token,block)
  self.assertNotIn('"GET"',block)
 def test_hidden_handles_release_retry_and_destroy_releases_ownership(self):
  s=(ROOT/'post/CommentsEngine.java').read_text()
  self.assertIn('paused=true;pageAttempt++;loading=false;cancelConflictRetry();clearPageDeadline()',s)
  self.assertIn('D1_HANDLES.remove(this);listener=null',s)
  self.assertIn('elapsedRealtime()-pausedAt>=120_000L',s)
  for path in ['post/PostDetailActivity.java','reels/ReelCommentsActivity.java']:
   screen=(ROOT/path).read_text()
   self.assertIn('commentsHandle.pause()',screen)
   self.assertIn('commentsHandle.resume()',screen)
   self.assertIn('if(!currentCommentsActor()){finish();return;}',screen)
   self.assertIn('protected void onDestroy()',screen)
 def test_comment_reaction_rollback_and_ack_only_delete(self):
  s=(ROOT/'post/CommentsAdapter.java').read_text()
  self.assertIn('c.reactions=previousReactions;c.likedBy=previousLikedBy;c.likes=previousLikes',s)
  self.assertIn('InteractionCooldown.acquire(me,"comment-ui:',s)
  self.assertIn('if(!com.friendbook.social.core.D1Client.enabled(counterCollection))rows.removeIf',s)
 def test_comment_photo_cannot_write_for_switched_account(self):
  s=(ROOT/'post/PostDetailActivity.java').read_text()
  self.assertIn('if(!currentCommentsActor()){busy=false;return;}',s)
  self.assertIn('if(!currentCommentsActor() || !java.util.Objects.equals(me,Fb.uid()))',s)
 def test_post_like_existing_absolute_state_queue_and_guard_retained(self):
  s=(ROOT/'post/D1Reactions.java').read_text()
  self.assertIn('InteractionCooldown.acquire(uid,"reaction/"+key)',s)
  self.assertIn('if(current())sendTarget(target,1)',s)
  q=(ROOT/'post/ReactionWriteQueue.java').read_text()
  self.assertIn('pending.clear()',q)
  self.assertIn('if(active!=null)return null',q)
