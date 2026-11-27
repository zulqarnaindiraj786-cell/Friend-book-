from pathlib import Path
import unittest

JAVA = Path(__file__).resolve().parents[2] / 'app/src/main/java/com/friendbook/social'


class ProfileEngagementBundle(unittest.TestCase):
    def test_profile_consumes_its_existing_bundle_without_bind_time_summary_fanout(self):
        profile = (JAVA / 'profile/ProfileActivity.java').read_text()
        self.assertIn('postsAdapter.feedEngagementInPage = true;', profile)
        self.assertIn('.withFeedEngagement(),10,"profile-posts:"', profile)
        self.assertLess(profile.index('D1Reactions.seedFeed(this, d, requestedAt)'),
                        profile.index('D1Reactions.hydrate(p)'))
        adapter = (JAVA / 'post/FeedAdapter.java').read_text()
        self.assertIn('if (!feedEngagementInPage && com.friendbook.social.core.D1Client.enabled("posts"))', adapter)
        # Other surfaces without an engagement bundle retain the default load path.
        self.assertIn('public boolean feedEngagementInPage;', adapter)
        reactions = (JAVA / 'post/D1Reactions.java').read_text()
        self.assertIn('public static void invalidate(String collection,String id)', reactions)
        self.assertIn('GENERATION.put(key,GENERATION.getOrDefault(key,0)+1)', reactions)
