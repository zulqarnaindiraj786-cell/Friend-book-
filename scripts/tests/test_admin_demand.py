"""Admin paging/action wiring guards; ModerationGateTest tests the actual gate."""
from pathlib import Path
import unittest
JAVA=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'

class AdminDemand(unittest.TestCase):
    def test_reports_feedback_use_visible_ten_item_pages(self):
        for name,collection in [('AdminReportsActivity.java','reports'),('AdminFeedbackActivity.java','feedback')]:
            code=(JAVA/'admin'/name).read_text()
            for token in ['PagedLists.attach','10,"admin-'+collection+'"','reg.remove()','started=false','screenActor.equals(Fb.uid())']:
                self.assertIn(token,code,name)
            self.assertNotIn('.limit(100)',code)
            self.assertNotIn('.addSnapshotListener(',code)
    def test_admin_default_tab_uses_one_observed_head_and_on_demand_pages(self):
        code=(JAVA/'admin/frag/AdminUsersFragment.java').read_text()
        for token in ['new com.friendbook.social.core.CursorPager(query,10', 'userPages.startObserved()', 'query.limit(10).addSnapshotListener', 'acceptObservedFirstPage(snap)', 'userPages.next()', 'userPages.close()', 'h.removeCallbacks(pending)', 'loaded users']:
            self.assertIn(token,code)
        self.assertNotIn('.limit(300)',code)
    def test_content_tab_pages_only_visible_collection_and_guards_delete(self):
        code=(JAVA/'admin/AdminContentActivity.java').read_text()
        for token in ['PagedLists.attach','10,"admin-content:"+collection','if(started)listen()', 'reg.remove()', 'items.clear();adapter.notifyDataSetChanged()', 'ModerationGate.SHARED.begin(actor,collection,it.id)', 'task.isSuccessful()', 'removed.add(java.util.Arrays.asList(collection,it.id))']:
            self.assertIn(token,code)
        self.assertNotIn('.limit(100)',code)
    def test_confirmed_delete_and_account_scoped_inflight_guards(self):
        for name,collection in [('AdminReportsActivity.java','reports'),('AdminFeedbackActivity.java','feedback')]:
            code=(JAVA/'admin'/name).read_text()
            for token in ['ModerationGate.SHARED.begin(actor,"'+collection+'",id)', 'ModerationGate.SHARED.end(actor,"'+collection+'",id)', 'task.isSuccessful()', 'removed.add(id)', '!removed.contains(d.getId())','Objects.equals(actor,Fb.uid())']:
                self.assertIn(token,code,name)
        report=(JAVA/'admin/AdminReportsActivity.java').read_text()
        self.assertIn('actionCompleted.put(id,success)',report)
        self.assertIn('actionCompleted.containsKey(id)',report)
        self.assertIn('report kept in queue',report)

    def test_referrals_page_and_names_are_lifecycle_scoped(self):
        code=(JAVA/'admin/frag/AdminReferralsFragment.java').read_text()
        for token in ['PagedLists.attach','10,"admin-referrals"','loaded referrals','loadGeneration++','generation!=loadGeneration','addOnCompleteListener(task ->','Objects.equals(owner,Fb.uid())']:
            self.assertIn(token,code)
        self.assertNotIn('.limit(400)',code)
        self.assertNotIn('total referral signups',code)

    def test_admin_live_pages_and_confirms_end_once(self):
        code=(JAVA/'admin/frag/AdminLiveFragment.java').read_text()
        for token in ['new com.friendbook.social.core.CursorPager(query,10','pages.startObserved()','query.limit(10).addSnapshotListener','pages.next()','pages.close()','pendingEnds.add(id)','if(task.isSuccessful())endedIds.add(id)','Objects.equals(owner,Fb.uid())']:
            self.assertIn(token,code)
        self.assertNotIn('.limit(50)',code)

    def test_earnings_figures_label_partial_pages_and_never_fetch_thousands(self):
        code=(JAVA/'admin/frag/AdminEarningsFragment.java').read_text()
        for token in ['Figures and chart cover loaded records only, not full totals', '10,"approved-payments"','10,"live-gifts"', 'if(!payReady || !giftReady)', 'Waiting for loaded pages; not a total', 'payReg.remove()', 'giftReg.remove()']:
            self.assertIn(token,code)
        self.assertNotIn('.limit(1000)',code)
        self.assertNotIn('.addSnapshotListener(',code)

    def test_payment_review_pages_and_cross_author_denied_fanout(self):
        code=(JAVA/'admin/frag/AdminPaymentsFragment.java').read_text()
        for token in ['new com.friendbook.social.core.CursorPager(query,10','pages.startObserved()','query.limit(10).addSnapshotListener','pages.next()','pages.close()','pending.add(id)','confirmed.put(id,"approved")','confirmed.put(id,"rejected")','owner.equals(uid))Admin.syncAuthorTickLevel']:
            self.assertIn(token,code)
        self.assertNotIn('.limit(100)',code)
