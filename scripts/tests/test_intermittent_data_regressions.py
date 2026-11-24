from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class IntermittentDataTests(unittest.TestCase):
 def test_profile_does_not_clear_on_temporary_failure_or_cached_miss(self):
  s=(ROOT/'profile/ProfileActivity.java').read_text()
  self.assertIn('if (user == null) nameT.setText',s)
  self.assertIn('if (d.getMetadata().isFromCache()) return;',s)
  self.assertIn('if (snap==null) return; // keep the last confirmed relationship',s)
  self.assertGreaterEqual(s.count('.getCachedOrFetchAutomatic(profileReadScope,300_000L)'),3)
  self.assertIn('if(!activeProfile(actor,generation))return;',s)
  self.assertIn('Math.max(user.followerCount, user.friendsCount)',s)
 def test_feed_and_candidate_timeouts_ignore_stale_callbacks(self):
  home=(ROOT/'main/HomeFragment.java').read_text()
  friends=(ROOT/'friends/FriendsFragment.java').read_text()
  first=home.split('private void startPostsQuery',1)[1].split('private void consumePostsSnapshot',1)[0]
  self.assertIn('loadingPosts=false;pageError=true;updatePagingUi();releaseHomeSecondary();',first)
  self.assertIn('if(!offline && !snapshot.isEmpty()) lastDoc=',first)
  self.assertIn('Query pageQuery=feedQuery.startAfter(lastDoc).limit(PAGE)',home)
  self.assertIn('pageQuery.getCachedOrFetchAutomatic(scope,Long.MAX_VALUE) : pageQuery.getCachedOrFetch(false)',home)
  self.assertNotIn('feedGeneration++',first)
  self.assertNotIn('Ui.toast',first)
  self.assertIn('feedGeneration++;loadingPosts=false;pageError=true;closePageReadScope();updatePagingUi();',home)
  self.assertIn('if(!current(token,me) || !checkingUsers.containsAll(ids))return;',friends)
  self.assertIn('checkingUsers.removeAll(ids);failedCandidates.addAll(ids);requestRender();',friends)
 def test_reel_engagement_avoids_periodic_reads_and_recursive_cached_render(self):
  reactions=(ROOT/'post/D1Reactions.java').read_text()
  reels=(ROOT/'reels/ReelsFragment.java').read_text()
  self.assertIn('fetch(context.getApplicationContext(),collection,reel.id,false,render)',reactions)
  self.assertIn('if(renderCached)ready.run()',reactions)
  self.assertNotIn('engagementRefresh',reels)
  self.assertIn('D1Reactions.reel(',reels)
 def test_token_rejection_retries_once_not_all_failures(self):
  doc=(ROOT/'d1/documents/D1DocumentTransport.java').read_text()
  self.assertIn('Code.UNAUTHENTICATED',doc)
  self.assertIn('requestOnce(method,pathAndQuery,body,actor,true)',doc)
  client=(ROOT/'core/D1Client.java').read_text()
  self.assertIn('failure.status!=401',client)
  self.assertIn('user.getIdToken(true)',client)
 def test_counts_retry_and_chat_transient_errors_do_not_close_room(self):
  s=(ROOT/'core/CountReads.java').read_text()
  self.assertIn('handler.postDelayed(this,delay)',s)
  self.assertIn('handler.removeCallbacks(retry)',s)
  chat=(ROOT/'chat/ChatActivity.java').read_text()
  self.assertIn('return; // a transient read failure is not a deleted room',chat)
  self.assertIn('if (!d.getMetadata().isFromCache()) finish(); return;',chat)
  self.assertIn('D1DocumentStoreException.Code.PERMISSION_DENIED) finish()',chat)
if __name__=='__main__':unittest.main()
