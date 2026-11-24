package com.friendbook.social.settings;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Ui;
import com.friendbook.social.d1.documents.DocumentSnapshot;
import com.friendbook.social.d1.documents.FieldValue;
import com.friendbook.social.d1.documents.ListenerRegistration;
import com.friendbook.social.d1.documents.Query;
import com.friendbook.social.d1.documents.WriteBatch;

import java.util.HashMap;
import java.util.Map;

/**
 * 🔐 Where you're logged in — friendbook security page:
 * list of recent login records and local device sign-out. Login records are not revocable sessions.
 * Firestore: users/{uid}/sessions/{auto} {device, model, at, appVersion}.
 */
public class SessionsActivity extends Activity {

    private LinearLayout list;
    private LinearLayout records, pageControls;
    private ListenerRegistration reg;

    /** Call after every successful login. */
    public static void record() {
        try {
            String uid = Fb.uid();
            if (uid == null) return;
            Map<String, Object> s = new HashMap<>();
            s.put("device", (Build.MANUFACTURER + " " + Build.MODEL).trim());
            s.put("model", "Android " + Build.VERSION.RELEASE);
            s.put("at", FieldValue.serverTimestamp());
            s.put("app", "friendbook");
            Fb.db().collection("users").document(uid).collection("sessions").add(s);
        } catch (Throwable ignored) { }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "🔐 Where you're logged in"));
        root.addView(Ui.divider(this));

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        list = Ui.col(this);
        Ui.pad(list, 14, 10, 14, 24);
        list.setBackgroundColor(Ui.bg(this));
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView logoutAll = Ui.btn(this, "🚪 Log out on this phone");
        logoutAll.setOnClickListener(v -> Ui.confirm(this, "Log out on this phone?",
                "Other devices remain signed in. Change your password to secure your account.",
                "Log out", () -> {
                    com.friendbook.social.core.FastCache.clearAll(this);
                    com.friendbook.social.auth.GoogleAuthHelper.signOutGoogle(this);
                    com.friendbook.social.push.PushManager.unregister(com.friendbook.social.App.instance);
                    Fb.auth().signOut();
                    Fb.me = null;
                    finishAffinity();
                }));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 44));
        lp.setMargins(0, 0, 0, Ui.dp(this, 12));
        list.addView(logoutAll, lp);

        TextView info = Ui.tv(this,
                "Agar koi device aapka nahi lagta — foran password change karo (Settings → Account).",
                12, Ui.sub(this));
        Ui.pad(info, 2, 0, 2, 10);
        list.addView(info);
        records=Ui.col(this);list.addView(records);
        pageControls=Ui.col(this);list.addView(pageControls);

        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        String me = Fb.uid();
        if (me == null) { finish(); return; }
        reg = com.friendbook.social.core.PagedLists.attach(this,pageControls,
                Fb.db().collection("users").document(me).collection("sessions")
                .orderBy("at", Query.Direction.DESCENDING),10,"login-history",(snap, err) -> {
                    records.removeAllViews();
                    if (err != null) {
                        records.addView(Ui.tv(this, "Couldn't load login history. Please try again.", 13, Ui.sub(this)));
                        return;
                    }
                    if (snap == null || snap.isEmpty()) {
                        TextView e = Ui.tv(this, "No login records yet.", 13, Ui.sub(this));
                        records.addView(e);
                        return;
                    }
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        Map<String, Object> m = d.getData();
                        LinearLayout card = Ui.row(this);
                        card.setBackground(Ui.round(this, Ui.card(this), 12));
                        Ui.pad(card, 12, 10, 12, 10);
                        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
                        cp.setMargins(0, 0, 0, Ui.dp(this, 8));
                        card.setLayoutParams(cp);
                        LinearLayout t = Ui.col(this);
                        t.addView(Ui.tvBold(this, "📱 " + Format.strOr(m, "device", "Device"), 14, Ui.txt(this)));
                        t.addView(Ui.tv(this, Format.str(m, "model") + " · "
                                + Format.ago(Format.ts(m == null ? null : m.get("at"))), 12, Ui.sub(this)));
                        card.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
                        records.addView(card);
                    }
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) { reg.remove();reg=null; }
    }
}
