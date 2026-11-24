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
    def test_live_reaction_writes_share_actor_room_cooldown(self):
        code=(JAVA/'live/LiveActivity.java').read_text()
        heart=code.split('private void sendHeartLike()',1)[1].split('private void sendReaction',1)[0]
        other=code.split('private void sendReaction',1)[1].split('private boolean allowLiveReaction',1)[0]
        self.assertIn('if (!allowLiveReaction()) return;',heart)
        self.assertIn('if (!allowLiveReaction()) return;',other)
        gate=code.split('private boolean allowLiveReaction()',1)[1].split('private void spawnFloatingEmoji',1)[0]
        for token in ['liveScreenStarted','Objects.equals(liveActor,Fb.uid())','InteractionCooldown.acquire(liveActor,"live-reaction:"+liveId)']:
            self.assertIn(token,gate)
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
    def test_friend_invites_use_selected_friend_pages_not_arbitrary_user_scan(self):
        code=(JAVA/'live/LivePkAndGiftsUi.java').read_text()
        drawer=code.split('public static void showInviteFriendsDrawer',2)[2].split('public static void sendDirectLiveInviteToUser',1)[0]
        for token in ['FriendPicker.multi','selected.keySet()','!me.equals(Fb.uid())','InteractionCooldown.acquire','live-invite:','Fb.me.blocked.contains(uid)']:
            self.assertIn(token,drawer)
        self.assertNotIn('collection("users").limit(50)',drawer)
        self.assertNotIn('Send Live Video Invite to All Friends',drawer)
    def test_friend_picker_cancels_owned_reads_on_close_or_activity_stop(self):
        code=(JAVA/'core/FriendPicker.java').read_text()
        for token in ['PagedLists.attach','whereArrayContains("participants",actor)','onActivityStopped','dialog.dismiss()','generation[0]++;reg.remove()','unregisterActivityLifecycleCallbacks','Objects.equals(actor,Fb.uid())']:
            self.assertIn(token,code)
    def test_pk_room_discovery_is_demand_paged_and_scoped_to_dialog(self):
        code=(JAVA/'live/LivePkAndGiftsUi.java').read_text()
        pk=code.split('public static void showPkChallengeDrawer',1)[1].split('private static com.friendbook.social.d1.documents.Query pkPeoplePrefix',1)[0]
        for token in ['PagedLists.attach','10,"live-discovery-pk:"','LIVE CREATORS IN LOADED ROOMS','!dialog.isShowing()','!me.equals(Fb.uid())','onActivityStopped','livePages[0].remove()','searchVersion[0]++']:
            self.assertIn(token,pk)
        self.assertNotIn('.limit(25)',pk)
        prefix=code.split('private static com.friendbook.social.d1.documents.Query pkPeoplePrefix',1)[1].split('private static LinearLayout buildOpponentRow',1)[0]
        self.assertIn('.limit(10)',prefix)
    def test_main_screen_does_not_prefetch_reels_for_an_unseen_badge(self):
        code=(JAVA/'main/MainActivity.java').read_text().split('private void startBadgeListeners()',1)[1].split('private final android.os.Handler notificationPermissionHandler',1)[0]
        self.assertNotIn('collection("reels")',code)
        self.assertIn('Ui.setBadge(reelsBadge,0)',code)
