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
