"""Static lifecycle and paging guards; does not represent production billing."""
from pathlib import Path
import unittest
JAVA=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'

class LiveScreenDemand(unittest.TestCase):
    def test_live_discovery_and_login_history_are_ten_item_user_pages(self):
        for path in ('live/LiveHubActivity.java','settings/SessionsActivity.java'):
            source=(JAVA/path).read_text()
            self.assertIn('PagedLists.attach',source)
            self.assertIn('10,',source)
            self.assertIn('onStop()',source)
            self.assertNotIn('.addSnapshotListener(',source)
        policy=(JAVA/'core/PagedLists.java').read_text()
        self.assertIn('key.startsWith("live-discovery"))current[0].startCachedFor(0L)',policy)
        self.assertIn('current[0].refreshOnDemand()',policy)
    def test_live_room_observers_only_belong_to_started_screen(self):
        code=(JAVA/'live/LiveActivity.java').read_text()
        attach=code.split('private void attachRoomListeners()',1)[1].split('private void onLiveDocumentSnapshot',1)[0]
        for token in ['!liveScreenStarted','Objects.equals(liveActor,Fb.uid())','liveDocReg != null','chatColReg != null']:
            self.assertIn(token,attach)
        stop=code.split('protected void onStop()',1)[1].split('protected void onDestroy()',1)[0]
        for token in ['liveScreenStarted=false','liveDocReg.remove()','chatColReg.remove()','removeCallbacks(pollPkRoom)','Objects.equals(liveActor,Fb.uid())']:
            self.assertIn(token,stop)
        start=code.split('protected void onStart()',1)[1].split('protected void onStop()',1)[0]
        for token in ['liveScreenStarted=true','liveRelayReady','attachRoomListeners()','uiHandler.post(pollPkRoom)']:
            self.assertIn(token,start)
        destroy=code.split('protected void onDestroy()',1)[1]
        self.assertIn('Objects.equals(liveActor,Fb.uid()) && liveActor!=null',destroy)
    def test_start_and_finish_do_not_add_redundant_heartbeat_write(self):
        code=(JAVA/'live/LiveActivity.java').read_text()
        start=code.split('docRef.set(doc).addOnSuccessListener',1)[1].split('private void notifyFollowersGoingLive()',1)[0]
        self.assertNotIn('publishHostHeartbeat();',start)
        stop=code.split('protected void onStop()',1)[1].split('protected void onDestroy()',1)[0]
        self.assertIn('!isFinishing() && java.util.Objects.equals(liveActor,Fb.uid())',stop)
        self.assertIn('if (!viewersBeforeSnapshot.equals(activeViewerIds)) publishHostHeartbeat();',code)
    def test_hidden_pk_read_callback_does_not_render_or_start_new_demand(self):
        code=(JAVA/'live/LiveActivity.java').read_text()
        pk=code.split('private void refreshCloudPk()',1)[1].split('private void applyCloudPk',1)[0]
        self.assertGreaterEqual(pk.count('!liveScreenStarted'),2)
        self.assertGreaterEqual(pk.count('Objects.equals(liveActor,Fb.uid())'),2)
