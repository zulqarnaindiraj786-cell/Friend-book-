package com.friendbook;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.friendbook.auth.LoginActivity;
import com.friendbook.core.Fb;
import com.friendbook.core.Prefs;
import com.friendbook.core.Presence;
import com.friendbook.core.Ui;
import com.friendbook.main.MainActivity;

public class SplashActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BLUE_DARK);
        getWindow().setNavigationBarColor(Ui.BLUE);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BLUE);
        root.setGravity(Gravity.CENTER);

        TextView logo = Ui.tvBold(this, "friendbook", 38, 0xFFFFFFFF);
        logo.setGravity(Gravity.CENTER);
        root.addView(logo);
        TextView tag = Ui.tv(this, "lite • fast • connected", 14, 0xCFFFFFFF);
        tag.setGravity(Gravity.CENTER);
        tag.setPadding(0, Ui.dp(this, 8), 0, 0);
        root.addView(tag);

        setContentView(root);

        new Handler(Looper.getMainLooper()).postDelayed(this::route, 900);
    }

    private void route() {
        // 🔵 Capture referral invite links: https://friendbook.app/invite?ref=USER_ID
        android.net.Uri data = getIntent() != null ? getIntent().getData() : null;
        if (data != null && "friendbook.app".equals(data.getHost())) {
            String ref = data.getQueryParameter("ref");
            if (ref != null && !ref.isEmpty()) Prefs.setPendingReferral(this, ref);
            // 🔗 group invite links: https://friendbook.app/group?id=GROUP_ID
            String gid = data.getQueryParameter("id");
            if (gid != null && !gid.isEmpty() && Fb.signedIn()) {
                Intent g = new Intent(this, com.friendbook.groups.GroupDetailActivity.class);
                g.putExtra("group_id", gid);
                startActivity(g);
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
        finish();
    }
}
