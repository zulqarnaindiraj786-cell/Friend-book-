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
 def test_feed_refreshes_author_even_when_post_has_old_name(self):
  s=(ROOT/'post/FeedAdapter.java').read_text()
  self.assertIn('Fb.getUserFresh(p.authorId)',s)
if __name__=='__main__':unittest.main()
