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
        // Post the notification before attempting any optional network refresh.
        PushManager.show(this, data);
        String actor=com.friendbook.social.core.Fb.uid();
        if (com.friendbook.social.App.isForeground() && actor != null && actor.equals(data.get("toUserId"))) {
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
            if ("message".equals(type)) store.refreshChatFromPush(data.get("chatId"));
            if (data.get("notificationId") != null || !"message".equals(type)) store.refreshInboxFromPush(type);
        }
    }
}
