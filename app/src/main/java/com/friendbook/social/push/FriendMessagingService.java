package com.friendbook.social.push;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class FriendMessagingService extends FirebaseMessagingService {
    @Override public void onNewToken(String token) { PushManager.save(this, token); }
    @Override public void onMessageReceived(RemoteMessage message) { PushManager.show(this, message.getData()); }
}
