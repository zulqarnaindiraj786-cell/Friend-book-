"""Source guards; Java policies and staging/device checks are separate."""
from pathlib import Path
import unittest
JAVA=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class FriendsCursorPages(unittest.TestCase):
    def test_twenty_per_visible_section_and_no_list_listeners(self):
        code=(JAVA/'friends/FriendsFragment.java').read_text()
        for token in ['SEC_SENT = 3','new com.friendbook.social.core.CursorPager(query,20','NearEndPaging.attach(rv','if(hidden)closePage()','if(loading)return;','moreButton.setOnClickListener']:
            self.assertIn(token,code)
        self.assertNotIn('addSnapshotListener',code)
    def test_candidate_checks_bounded_guarded_and_fail_closed(self):
        code=(JAVA/'friends/FriendsFragment.java').read_text()
        for token in ['offset+=10','FriendCandidatePages.pairs(me,ids)','Tasks.whenAllSuccess','a.getMetadata().isFromCache()','a.size()==20','if(!current(token,me))return','failedCandidates.addAll(ids)','FriendCandidatePages.canOfferRequest']:
            self.assertIn(token,code)
    def test_action_targets_and_account_guards(self):
        code=(JAVA/'friends/FriendsFragment.java').read_text()
        for token in ['document(requestId).delete()','activePager.forget(requestId)','!requestWrites.add(target.uid)','cancelledRequestIds.contains(doc.getId())','removedFriendIds.contains(other)','dismissedReqIds.remove(rid)','if(!me.equals(Fb.uid())']:
            self.assertIn(token,code)
        self.assertNotIn('collection("friends").whereArrayContains("participants", me)\n                            .get()',code)
    def test_common_legacy_pages_do_not_rebuild_on_loading_event(self):
        code=(JAVA/'core/PagedLists.java').read_text();self.assertIn('if(!busy && (error.isEmpty() || !docs.isEmpty()))',code)
