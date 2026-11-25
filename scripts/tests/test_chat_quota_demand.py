from pathlib import Path
import unittest
JAVA=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class ChatQuotaDemand(unittest.TestCase):
 def test_live_invite_bind_has_no_database_or_blob_hydration(self):
  s=(JAVA/'chat/MessagesAdapter.java').read_text()
  bind=s.split('private void bindLiveInviteVideoCard',1)[1].split('View.OnClickListener openLiveClick',1)[0]
  self.assertNotIn('Fb.db()',bind)
  self.assertNotIn('liveFrameB64',s)
  self.assertIn('InteractionCooldown.acquire(actor,"chat-live-invite:',s)
 def test_resume_does_not_reset_confirmed_unread_state(self):
  s=(JAVA/'chat/ChatActivity.java').read_text()
  resume=s.split('@Override protected void onResume()',1)[1].split('@Override protected void onPause()',1)[0]
  self.assertIn('if(unreadResetFailed){unreadResetOnce=false;unreadResetFailed=false;}',resume)
  self.assertIn('activeChatRead(chatActor,chatGeneration)',s)
  self.assertIn('older.limit(10)',s)
 def test_group_member_render_and_people_pickers_do_not_scan_profiles(self):
  s=(JAVA/'chat/GroupChatInfoActivity.java').read_text()
  row=s.split('private View buildMemberRow',1)[1].split('private void showMemberMenu',1)[0]
  self.assertNotIn('Fb.getUser(',row)
  self.assertNotIn('.limit(200)',s)
  self.assertIn('"chat-people:add-members:"',s)
  create=(JAVA/'chat/CreateGroupChatActivity.java').read_text()
  self.assertNotIn('.limit(250)',create)
  self.assertIn('"chat-people:create-group"',create)
  self.assertIn('peopleReg.remove()',create)
 def test_inbox_deadline_is_local_unlock_not_polling(self):
  s=(JAVA/'chat/ConversationsActivity.java').read_text()
  deadline=s.split('final Runnable deadline=',1)[1].split('inboxHandler.postDelayed',1)[0]
  self.assertNotIn('loadInboxPage',deadline)
  self.assertIn('inboxGeneration++',deadline)
  self.assertIn('activeInboxRead(me,generation)',s)
