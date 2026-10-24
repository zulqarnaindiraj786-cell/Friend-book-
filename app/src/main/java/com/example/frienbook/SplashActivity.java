package com.example.frienbook;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.frienbook.auth.LoginActivity;
import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Prefs;
import com.example.frienbook.core.Presence;
import com.example.frienbook.core.Ui;
import com.example.frienbook.main.MainActivity;

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
