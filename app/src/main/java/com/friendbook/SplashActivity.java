package com.friendbook;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.friendbook.auth.LoginActivity;
import com.friendbook.core.FastCache;
import com.friendbook.core.Fb;
import com.friendbook.core.Prefs;
import com.friendbook.core.Presence;
import com.friendbook.main.MainActivity;

/**
 * ⚡ Zero-delay friendbook launcher:
 * Routes immediately in onCreate (0ms wait — NO artificial splash sleep!)
 * with crisp zero-lag transition.
 */
public class SplashActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FastCache.init(this);
        route();
    }

    private void route() {
        // 🔵 Capture referral & group invite deep links (friendbook:// or official GitHub Pages)
        android.net.Uri data = getIntent() != null ? getIntent().getData() : null;
        if (data != null) {
            String ref = data.getQueryParameter("ref");
            if (ref != null && !ref.isEmpty()) Prefs.setPendingReferral(this, ref);
            String gid = data.getQueryParameter("id");
            if (gid != null && !gid.isEmpty() && Fb.signedIn()) {
                Intent g = new Intent(this, com.friendbook.groups.GroupDetailActivity.class);
                g.putExtra("group_id", gid);
                startActivity(g);
                overridePendingTransition(0, 0);
                finish();
                return;
            }
        }
        if (!Fb.signedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
        } else if (!Prefs.pin(this).isEmpty() && !Prefs.unlockedSession(this)) {
            startActivity(new Intent(this, LockActivity.class));
        } else {
            Fb.startMeListener();
            Presence.init();
            startActivity(new Intent(this, MainActivity.class));
        }
        overridePendingTransition(0, 0);
        finish();
    }
}
