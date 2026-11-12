from pathlib import Path
import unittest
JAVA=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class ChatAutoplayUx(unittest.TestCase):
    def test_ten_chats_and_ten_latest_and_older_messages(self):
        inbox=(JAVA/'chat/ConversationsActivity.java').read_text();chat=(JAVA/'chat/ChatActivity.java').read_text()
        for token in ['cachedRooms++>=10','query.limit(10).addSnapshotListener','query.limit(10).get(com.google.firebase.firestore.Source.SERVER)']:
            self.assertIn(token,inbox)
        self.assertIn('.limitToLast(40)',chat);self.assertIn('startAfter(historyCursor)',chat);self.assertIn('older.limit(40)',chat)
    def test_metadata_does_not_render_again_and_read_dispatch_precedes_render(self):
        chat=(JAVA/'chat/ChatActivity.java').read_text();adapter=(JAVA/'chat/MessagesAdapter.java').read_text()
        self.assertIn('MetadataChanges.EXCLUDE).isEmpty()',chat)
        self.assertIn('if(oldKeys.equals(newKeys))return;',adapter)
        live=chat.split('.limitToLast(40)',1)[1].split('private void loadOlderMessages()',1)[0]
        self.assertLess(live.index('markVisibleReceipts();\n'),live.index('adapter.setData(list)'))
        for token in ['!chatVisible','receiptInFlight.contains(m.id)','receiptFailed.contains(m.id)',
                      'D1Write.write("messages", chatId + "_" + m.id, patch', 'receiptInFlight.removeAll(claimed)']:
            self.assertIn(token,chat)
    def test_history_restores_id_anchor_and_does_not_clear_on_each_resume(self):
        chat=(JAVA/'chat/ChatActivity.java').read_text()
        self.assertIn('String anchorId=adapter.idAt(first)',chat)
        self.assertIn('manager.scrollToPositionWithOffset(restored,top)',chat)
        self.assertIn('if(!java.util.Objects.equals(messageActor,Fb.uid()))',chat)
        self.assertIn('historyButton.setVisibility(View.GONE)',chat)
        self.assertIn('older.endBefore(historyEnd)',chat)
        self.assertIn('historyRanges.push(new HistoryResume',chat)
        self.assertIn('historyRanges.pop()',chat)
    def test_reel_selected_video_autoplays_but_no_offscreen_prefetch(self):
        reel=(JAVA/'reels/ReelsFragment.java').read_text()
        self.assertNotIn('userRequestedVideo',reel);self.assertNotIn('▶ Tap to play',reel)
        self.assertIn('this.wantPlay=autoPlayNow;',reel)
        self.assertIn('if(!visible.getAsBoolean()){pauseAll(pager);return;}',reel)
        self.assertIn('getBindingAdapterPosition()!=playingPos',reel)
        self.assertNotIn('ReelVideoCache.prefetchWindow(',reel)
