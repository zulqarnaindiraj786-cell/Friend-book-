"""Source guards; Java policies and staging/device checks are separate."""
from pathlib import Path
import unittest
JAVA=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class FriendsCursorPages(unittest.TestCase):
    def test_ten_per_visible_section_and_cached_request_head(self):
        code=(JAVA/'friends/FriendsFragment.java').read_text()
        for token in ['SEC_SENT = 3','new com.friendbook.social.core.CursorPager(query,10','NearEndPaging.attach(rv','if(hidden){ closePage(false); }','if(loading){requestRender();return;}','swipe.setOnRefreshListener(()->{forceSectionRefresh=true;startSection();})','People you may know','suggestionPager.startCachedFor(300_000L)']:
            self.assertIn(token,code)
        self.assertNotIn('addSnapshotListener',code)
        self.assertIn('activePager.startCachedFor(300_000L)',code)
        self.assertNotIn('activePager.startDelta()',code)
    def test_sent_uses_manual_pages_without_profile_requests(self):
        code=(JAVA/'friends/FriendsFragment.java').read_text()
        sent=code.split('}else if(selected==SEC_SENT){',1)[1].split('else if(selected==SEC_FRIENDS)',1)[0]
        self.assertIn('if(outgoing.add(other))sentIds.put(other,doc.getId())',sent)
        self.assertNotIn('ensureUser',sent)
        self.assertIn('section!=SEC_SENT && activePager!=null',code)
        self.assertIn('Load more sent requests',code)
    def test_candidate_checks_bounded_guarded_and_fail_closed(self):
        code=(JAVA/'friends/FriendsFragment.java').read_text()
        for token in ['offset+=10','Fb.db().friendshipFacts(ids)','Tasks.whenAllSuccess','a.size()!=ids.size()','b.size()==10','if(!current(token,me) || !checkingUsers.containsAll(ids))return','},20000L)','failedCandidates.addAll(ids)','FriendCandidatePages.canOfferRequest']:
            self.assertIn(token,code)
    def test_action_targets_and_account_guards(self):
        code=(JAVA/'friends/FriendsFragment.java').read_text()
        for token in ['document(requestId).delete()','activePager.forget(requestId)','!requestWrites.add(target.uid)','cancelledRequestIds.contains(doc.getId())','removedFriendIds.contains(other)','dismissedReqIds.remove(rid)','if(!me.equals(Fb.uid())']:
            self.assertIn(token,code)
        self.assertNotIn('collection("friends").whereArrayContains("participants", me)\n                            .get()',code)
    def test_common_legacy_pages_do_not_rebuild_on_loading_event(self):
        code=(JAVA/'core/PagedLists.java').read_text();self.assertIn('if(!busy && (error.isEmpty() || !docs.isEmpty()))',code)
