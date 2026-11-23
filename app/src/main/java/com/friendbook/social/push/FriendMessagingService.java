package com.friendbook.social.push;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class FriendMessagingService extends FirebaseMessagingService {
    @Override public void onNewToken(String token) { PushManager.save(this, token); }
    @Override public void onMessageReceived(RemoteMessage message) {
        PushTrace.stage("fcm_received", "event=" + PushTrace.id(message.getData().get("eventId"))
                + " receiver=" + PushTrace.id(message.getData().get("toUserId"))
                + " type=" + PushTrace.label(message.getData().get("type")));
        // A push wakes only an already-open chat listener; it never supplies message
        // content or bypasses the Worker's authenticated membership check.
        java.util.Map<String,String> data=message.getData();
        String actor=com.friendbook.social.core.Fb.uid();
        if (actor != null && actor.equals(data.get("toUserId")) && "message".equals(data.get("type")))
            com.friendbook.social.d1.documents.D1DocumentStore.getInstance().refreshChatFromPush(data.get("chatId"));
        // Badge cost = zero reads: the pill is bumped locally from the push itself.
        if (actor != null && actor.equals(data.get("toUserId")))
            com.friendbook.social.main.MainActivity.onPushArrived(data.get("type"));
        PushManager.show(this, data);
    }
}
