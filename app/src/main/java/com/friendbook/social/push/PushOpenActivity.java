package com.friendbook.social.push;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Prefs;
import com.friendbook.social.main.MainActivity;

/** Internal notification router. Pending target survives sign-in and the app PIN screen. */
public class PushOpenActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String account = getIntent().getStringExtra("toUserId");
        if (!Fb.signedIn() || (!Prefs.pin(this).isEmpty() && !Prefs.unlockedSession(this))) {
            android.content.SharedPreferences.Editor e = getSharedPreferences("push_target", 0).edit().clear();
            if (getIntent().getExtras() != null) for (String k : getIntent().getExtras().keySet())
                e.putString(k, getIntent().getStringExtra(k));
            e.apply();
            startActivity(new Intent(this, com.friendbook.social.SplashActivity.class));
            finish(); return;
        }
        if (!Fb.uid().equals(account)) { finish(); return; }
        String type = getIntent().getStringExtra("type");
        String post = getIntent().getStringExtra("postId");
        Intent target = new Intent(this, MainActivity.class);
        if ("push_test".equals(type)) {
            target = new Intent(this, MainActivity.class);
        } else if ("message".equals(type)) {
            target = new Intent(this, com.friendbook.social.chat.ChatActivity.class);
            target.putExtra("uid", getIntent().getStringExtra("fromUserId"));
            target.putExtra("name", getIntent().getStringExtra("title"));
            target.putExtra("chat_id", getIntent().getStringExtra("chatId"));
            target.putExtra("is_group", "true".equals(getIntent().getStringExtra("isGroup")));
        } else if ("friend_request".equals(type)) {
            target.putExtra("push_friends", true);
        } else if (post != null && !post.isEmpty()) {
            boolean reel = type != null && (type.startsWith("reel_") || "reel".equals(getIntent().getStringExtra("contentType")));
            target = new Intent(this, reel ? com.friendbook.social.reels.ReelPlayerActivity.class : com.friendbook.social.post.PostDetailActivity.class);
            target.putExtra(reel ? "reel_id" : "post_id", post);
        } else {
            target = new Intent(this, com.friendbook.social.profile.ProfileActivity.class);
            target.putExtra("uid", getIntent().getStringExtra("fromUserId"));
        }
        String nid = getIntent().getStringExtra("notificationId");
        if (nid != null && !nid.isEmpty()) Fb.db().collection("notifications").document(nid).update("isRead", true);
        startActivities(new Intent[]{new Intent(this, MainActivity.class), target});
        finish();
    }
    public static void resumePending(Activity a) {
        android.content.SharedPreferences p = a.getSharedPreferences("push_target", 0);
        if (!p.contains("toUserId")) return;
        Intent i = new Intent(a, PushOpenActivity.class);
        for (java.util.Map.Entry<String, ?> e : p.getAll().entrySet()) i.putExtra(e.getKey(), String.valueOf(e.getValue()));
        p.edit().clear().apply();
        a.startActivity(i);
    }
}
