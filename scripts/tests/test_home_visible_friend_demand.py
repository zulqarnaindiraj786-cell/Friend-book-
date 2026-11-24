from pathlib import Path
import unittest
JAVA=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class HomeVisibleFriendDemand(unittest.TestCase):
    def test_friend_facts_only_probe_submitted_authors_and_fail_closed(self):
        code=(JAVA/'main/HomeFragment.java').read_text()
        for token in ['FRIEND_FACT_TTL_MS=300_000L','resolveVisibleFriends(visibleAuthors)','resolveVisibleFriends(authors)','FriendCandidatePages.pairs(me,ids)','whereIn("participants",pairs).limit(10).get()','friendFactsPending.contains(id)','friendFactsFailed.contains(id)','snap.size()<10','friendFactsFailed.add(id)','factsToken!=friendFactsGeneration','activeHomeRead(me,homeToken)','friendFactsPending.containsAll(ids)','20_000L','consumePostsSnapshot(lastPostDocs,loadedPosts)','isConfirmedFriend(p.authorId)','isConfirmedFriend(story.authorId)','hasFreshFriendFact(u.uid)','addPostFriendDemand(visibleAuthors,doc)','addPostFriendDemand(visibleAuthors,post)','addStoryFriendDemand(authors,doc)','StoryAudience.needsFriendship']:
            self.assertIn(token,code)
        self.assertNotIn('OnDemandLoads.FRIEND_GRAPH_LIMIT',code)
        self.assertNotIn('putStringSet("friendGraph:"',code)
        self.assertIn('post!=null && visible(post)',code)
