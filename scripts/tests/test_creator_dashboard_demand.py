from pathlib import Path
import unittest
JAVA=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class CreatorDashboardDemand(unittest.TestCase):
    def test_insights_are_requested_in_lifecycle_owned_partial_pages(self):
        code=(JAVA/'profile/CreatorDashboardDialog.java').read_text()
        for token in ['PagedLists.attach','10,"creator-dashboard"','Post metrics use loaded pages only','Level estimate uses loaded posts only','!dlg.isShowing()','!me.equals(Fb.uid())','onActivityStopped','insights[0].remove()','unregisterActivityLifecycleCallbacks']:
            self.assertIn(token,code)
        self.assertNotIn('.limit(150)',code)

    def test_professional_mode_single_flight_and_failure_rollback(self):
        code=(JAVA/'profile/CreatorDashboardDialog.java').read_text()
        for token in ['suppressSwitch[0]','btn.setEnabled(false)','addOnCompleteListener(task->','if(!me.equals(Fb.uid()))return;','if(!task.isSuccessful())','proSw.setChecked(previous)','btn.setEnabled(true)']:
            self.assertIn(token,code)
