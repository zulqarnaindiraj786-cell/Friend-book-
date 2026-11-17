"""Source guards; Java policies and staging/device checks are separate."""
from pathlib import Path
import unittest
JAVA=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class FriendsCursorPages(unittest.TestCase):
    def test_twenty_per_visible_section_and_no_list_listeners(self):
        code=(JAVA/'friends/FriendsFragment.java').read_text()
        # The sections page D1 now (DocPager + DocQuery), not Firestore (CursorPager + Query).
        for token in ['SEC_SENT = 3','new com.friendbook.social.core.DocPager(qb.build(),selected==SEC_SUGGESTIONS?10:20','NearEndPaging.attach(rv','if(hidden)closePage()','if(loading){requestRender();return;}','swipe.setOnRefreshListener(this::startSection)','People you may know','suggestionDocPager.start()']:
            self.assertIn(token,code)
        self.assertNotIn('addSnapshotListener',code)
    def test_candidate_checks_bounded_guarded_and_fail_closed(self):
        code=(JAVA/'friends/FriendsFragment.java').read_text()
        # The probes are D1 queries now. The bounded batch, the fail-closed behaviour and the
        # "only offer a request when nothing exists" rule are unchanged; what changed is which
        # store answers, because Firestore answering nothing is what printed "Pull down to retry
        # connection check" under every single suggestion.
        for token in ['offset+=10','if(!current(token,me))return','failedCandidates.addAll(ids)','FriendCandidatePages.canOfferRequest',
                      '.whereArrayContainsAny("participants", ids)','.whereIn("fromUid", ids)','.whereIn("toUid", ids)','done.accept(false)']:
            self.assertIn(token,code)
    def test_action_targets_and_account_guards(self):
        code=(JAVA/'friends/FriendsFragment.java').read_text()
        for token in ['activeDocPager.forget(requestId)','!requestWrites.add(target.uid)','cancelledRequestIds.contains(reqId)','removedFriendIds.contains(other)','dismissedReqIds.remove(rid)','if(!me.equals(Fb.uid())',
                      # Every write goes to D1 and is reported by D1 - not mirrored alongside a
                      # Firestore write that was the real one. A mirror is exactly what left a new
                      # account unable to send a request: the write the user waited on was refused.
                      '.set("friend_requests", newId, payload)','.delete("friend_requests", requestId)',
                      '.delete("friend_requests", rid)','.ops("users", me,','.set("friends", friendId,']:
            self.assertIn(token,code)
        self.assertNotIn('collection("friends").whereArrayContains("participants", me)\n                            .get()',code)
    def test_common_legacy_pages_do_not_rebuild_on_loading_event(self):
        code=(JAVA/'core/PagedLists.java').read_text();self.assertIn('if(!busy && (error.isEmpty() || !docs.isEmpty()))',code)
