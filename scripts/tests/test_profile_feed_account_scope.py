from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class ProfileFeedScope(unittest.TestCase):
 def test_cache_discards_other_account_and_keeps_follow_counts(self):
  cache=(ROOT/'core/FastCache.java').read_text()
  self.assertIn('USERS.clear();',cache)
  self.assertIn('u.followerCount=u.followers.size()',cache)
  self.assertIn('String actor=ensureUserScope()',cache)
  fb=(ROOT/'core/Fb.java').read_text()
  self.assertIn('if (me != null && !uid.equals(me.uid)) me=null;',fb)
  self.assertIn('public static Task<Models.User> getUserFresh',fb)
  self.assertIn('Objects.equals(actor,Fb.uid())',fb)
 def test_friends_rechecks_actor_relationships_and_profiles_on_visit(self):
  s=(ROOT/'friends/FriendsFragment.java').read_text()
  self.assertIn('checkedCandidates.clear();candidateFriends.clear();candidateIncoming.clear();candidateOutgoing.clear();failedCandidates.clear();userCache.clear();',s)
  self.assertIn('loadingProfiles=new HashSet<>()',s)
  self.assertIn('Fb.getUserFresh(uid)',s)
  self.assertIn('this::loadInlineSuggestions',s)
  self.assertIn('if(req.fromName.isEmpty())ensureUser(req.fromUid)',s)
  self.assertNotIn('suggestionPager.start()',s.split('private void loadInlineSuggestions',1)[0])
 def test_offline_page_does_not_replace_confirmed_rows(self):
  s=(ROOT/'core/CursorPager.java').read_text()
  self.assertIn('if(cursor==null && !offline)records.clear()',s)
  self.assertIn('if(!offline || !records.containsKey(doc.getId()))records.put',s)
 def test_feed_refreshes_author_even_when_post_has_old_name(self):
  s=(ROOT/'post/FeedAdapter.java').read_text()
  self.assertIn('Fb.getUserFresh(p.authorId)',s)
if __name__=='__main__':unittest.main()
