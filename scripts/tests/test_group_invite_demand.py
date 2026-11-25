from pathlib import Path
import unittest
JAVA=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class GroupInviteDemand(unittest.TestCase):
 def test_invites_are_paged_and_selection_survives_page_repaint(self):
  s=(JAVA/'groups/GroupDetailActivity.java').read_text().split('private void showInviteMembersDialog()',1)[1].split('private void showQuickGroupMenu',1)[0]
  self.assertNotIn('.limit(200)',s)
  for text in ['10,"group-invite:"+id','ownedPager.startCachedFor(300_000L)','cb.setChecked(selected.contains(u.uid))','ownedDialog.setOnDismissListener','ownedPager.close()','inviteActor,Fb.uid()','inviteDialog.isShowing()']:self.assertIn(text,s)
 def test_reactor_row_render_has_no_individual_profile_request(self):
  s=(JAVA/'post/Reactions.java').read_text().split('private static void renderReactorPage',1)[1].split('private static void hydrateReactorProfiles',1)[0]
  self.assertNotIn('Fb.getUser(',s)
  self.assertIn('listCol.getTag()==paintToken',s)
