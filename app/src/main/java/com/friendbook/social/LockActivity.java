package com.friendbook.social;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.friendbook.social.core.Prefs;
import com.friendbook.social.core.Presence;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Ui;
import com.friendbook.social.main.MainActivity;

/** App Lock — pro privacy feature. Shown when a PIN is configured. */
public class LockActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Prefs.pin(this).isEmpty()) { go(); return; }

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.setGravity(Gravity.CENTER);
        Ui.pad(root, 32, 0, 32, 0);

        TextView lock = Ui.tv(this, "🔒", 46, Ui.txt(this));
        lock.setGravity(Gravity.CENTER);
        root.addView(lock);

        TextView title = Ui.tvBold(this, "friendbook is locked", 20, Ui.txt(this));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 18));
        root.addView(title);

        EditText pin = Ui.input(this, "Enter your PIN");
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setGravity(Gravity.CENTER);
        root.addView(pin, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));

        TextView btn = Ui.btn(this, "Unlock");
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 46));
        bp.topMargin = Ui.dp(this, 16);
        root.addView(btn, bp);

        btn.setOnClickListener(v -> {
            if (pin.getText().toString().trim().equals(Prefs.pin(this))) {
                Prefs.setUnlockedSession(this, true);
                go();
            } else {
                Ui.haptic(v);
                pin.setText("");
                Ui.toast(this, "Wrong PIN");
            }
        });
        pin.setOnEditorActionListener((v, a, e) -> { btn.performClick(); return true; });

        setContentView(root);
    }

    private void go() {
        Fb.startMeListener();
        Presence.init();
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override
    public void onBackPressed() {
        // Locked: leave the app instead of bypassing the PIN.
        finishAffinity();
    }
}
