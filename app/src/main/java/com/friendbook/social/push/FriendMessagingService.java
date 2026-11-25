package com.friendbook.social.push;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class FriendMessagingService extends FirebaseMessagingService {
    private static final PushRefreshBurstGate REFRESH_BURSTS = new PushRefreshBurstGate();
    @Override public void onNewToken(String token) { PushManager.save(this, token); }
    @Override public void onMessageReceived(RemoteMessage message) {
        PushTrace.stage("fcm_received", "event=" + PushTrace.id(message.getData().get("eventId"))
                + " receiver=" + PushTrace.id(message.getData().get("toUserId"))
                + " type=" + PushTrace.label(message.getData().get("type")));
        // A push wakes a visible chat or inbox; it never supplies message
        // content or bypasses the Worker's authenticated membership check.
        java.util.Map<String,String> data=message.getData();
        // Schedule authenticated message reads before building the notification.
        // This does not block on network or use push text as a stored message.
        String actor=com.friendbook.social.core.Fb.uid();
        boolean currentActor=actor!=null && actor.equals(data.get("toUserId"));
        // Keep display independent. Only duplicate foreground invalidations
        // share this short transport-ID window; calls always remain immediate.
        boolean refreshAllowed=currentActor && com.friendbook.social.App.isForeground()
                && ("incoming_call".equals(data.get("type"))
                    || REFRESH_BURSTS.allow(actor,message.getMessageId(),android.os.SystemClock.elapsedRealtime()));
        if (refreshAllowed && "message".equals(data.get("type"))) {
            com.friendbook.social.d1.documents.D1DocumentStore.getInstance().refreshChatFromPush(data.get("chatId"));
        // Badge cost = zero reads: the pill is bumped locally from the push itself.
        if (actor != null && actor.equals(data.get("toUserId")))
            com.friendbook.social.main.MainActivity.onPushArrived(data.get("type"));
            com.friendbook.social.chat.ConversationsActivity.refreshVisibleInboxFromPush(actor);
        }
        PushManager.show(this, data);
        if (refreshAllowed && actor.equals(com.friendbook.social.core.Fb.uid())) {
            com.friendbook.social.d1.documents.D1DocumentStore store = com.friendbook.social.d1.documents.D1DocumentStore.getInstance();
            String type = data.get("type");
            if ("incoming_call".equals(type)) {
                String room=data.get("chatId");
                if(room!=null && room.matches("[A-Za-z0-9_-]{1,200}"))
                    com.friendbook.social.core.Fb.db().collection("chats").document(room).get(com.friendbook.social.d1.documents.Source.SERVER)
                        .addOnSuccessListener(doc -> {
                            if(com.friendbook.social.App.isForeground() && actor.equals(com.friendbook.social.core.Fb.uid()))
                                com.friendbook.social.chat.CallActivity.maybeHandleIncomingFromRoomDoc(this,doc);
                        });
                return;
            }
            if (data.get("notificationId") != null || !"message".equals(type)) store.refreshInboxFromPush(type);
        }
    }
}
