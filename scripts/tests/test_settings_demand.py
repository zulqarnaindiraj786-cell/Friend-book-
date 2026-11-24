from pathlib import Path
import unittest
JAVA=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class SettingsDemand(unittest.TestCase):
    def test_creator_insights_are_explicit_partial_and_lifecycle_owned(self):
        code=(JAVA/'settings/SettingsActivity.java').read_text()
        insights=code.split('private void verifiedInsights()',1)[1].split('private void helpDialog()',1)[0]
        for token in ['PagedLists.attach','10,"creator-insights"','loaded posts only, not total','dialog.isShowing()','Objects.equals(actor,Fb.uid())','insightsReg.remove()']:
            self.assertIn(token,insights)
        self.assertNotIn('.limit(300)',insights)
        stop=code.split('@Override protected void onStop()',1)[1].split('private void storageDialog()',1)[0]
        self.assertIn('insightsDialog.dismiss()',stop)
        self.assertIn('insightsReg.remove()',stop)
