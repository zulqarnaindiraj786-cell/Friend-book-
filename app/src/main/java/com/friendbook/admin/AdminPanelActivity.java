package com.friendbook.admin;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.friendbook.admin.frag.AdminBoostsFragment;
import com.friendbook.admin.frag.AdminEarningsFragment;
import com.friendbook.admin.frag.AdminLiveFragment;
import com.friendbook.admin.frag.AdminPaymentsFragment;
import com.friendbook.admin.frag.AdminReferralsFragment;
import com.friendbook.admin.frag.AdminUsersFragment;
import com.friendbook.core.Admin;
import com.friendbook.core.Fb;
import com.friendbook.core.Icons;
import com.friendbook.core.Ui;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.WriteBatch;

import java.util.List;

/**
 * 🛡 Admin SUPER Dashboard — ONLY for zulqarnaindiraj786@gmail.com (or promoted admins).
 * Normal users never see it. Six tabs:
 *   1) Users    — every user + direct tick control (none/blue/gold/diamond), ban, promote
 *   2) Referrals— who invited how many
 *   3) Payments — every payment (ticks/boosts/subs) w/ screenshot + Approve/Reject
 *   4) Boosts   — approve/stop profile & post boosts
 *   5) Live     — all rooms, viewers, stars, end control
 *   6) Earnings — income by type, 70/30 split, monthly chart, tools
 */
public class AdminPanelActivity extends Activity {

    private FrameLayout container;
    private final ImageView[] tabIcons = new ImageView[6];
    private final TextView[] tabLabels = new TextView[6];
    private int active = -1;

    private static final String[] TAB_NAMES = {"Users", "Referrals", "Payments", "Boosts", "Live", "Earnings"};
    private static final int[] TAB_ICONS = {
            Icons.FRIENDS, Icons.USER_PLUS, Icons.BANKNOTE, Icons.BOOST, Icons.LIVE, Icons.CHART};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Admin.isAdmin()) { Ui.toast(this, "Admins only"); finish(); return; }
        Ui.statusBarBlue(this);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "🛡 Admin Panel"));
        root.addView(Ui.divider(this));

        container = new FrameLayout(this);
        container.setId(View.generateViewId());
        root.addView(container, new LinearLayout.LayoutParams(-1, 0, 1));

        // ---- 6 tabs (outline icons, blue active / gray idle — Facebook Lite look) ----
        LinearLayout tabs = Ui.row(this);
        tabs.setBackgroundColor(Ui.card(this));
        tabs.setElevation(Ui.dp(this, 6));
        Ui.pad(tabs, 0, 6, 0, 6);
        for (int i = 0; i < 6; i++) {
            final int tab = i;
            LinearLayout cell = Ui.col(this);
            cell.setGravity(Gravity.CENTER);
            ImageView ic = Icons.icon(this, TAB_ICONS[i], 22, Icons.GRAY);
            tabIcons[i] = ic;
            cell.addView(ic);
            TextView lab = Ui.tv(this, TAB_NAMES[i], 9, Ui.sub(this));
            lab.setGravity(Gravity.CENTER);
            lab.setPadding(0, Ui.dp(this, 2), 0, 0);
            tabLabels[i] = lab;
            cell.addView(lab);
            cell.setOnClickListener(v -> select(tab));
            tabs.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
        }
        root.addView(Ui.divider(this));
        root.addView(tabs);

        setContentView(root);
        select(0);
    }

    public void select(int tab) {
        if (tab == active) return;
        active = tab;
        Fragment f;
        switch (tab) {
            case 1: f = new AdminReferralsFragment(); break;
            case 2: f = new AdminPaymentsFragment(); break;
            case 3: f = new AdminBoostsFragment(); break;
            case 4: f = new AdminLiveFragment(); break;
            case 5: f = new AdminEarningsFragment(); break;
            default: f = new AdminUsersFragment(); break;
        }
        FragmentTransaction ft = getFragmentManager().beginTransaction();
        ft.replace(container.getId(), f);
        ft.commitAllowingStateLoss();
        for (int i = 0; i < 6; i++) {
            boolean on = i == tab;
            Icons.setTint(tabIcons[i], on ? Icons.BLUE : Icons.GRAY);
            tabLabels[i].setTextColor(on ? Icons.BLUE : Icons.GRAY);
        }
    }

    /** 📣 Broadcast: one notification doc per user, batched. Called from Earnings tab tools. */
    public void showBroadcastDialog() {
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 20, 10, 20, 0);
        EditText msg = Ui.multi(this, "Message for every friendbook user…", 3);
        box.addView(msg, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("📣 Broadcast to all users")
                .setMessage("Everyone with an account will get this in Notifications.")
                .setView(box)
                .setPositiveButton("Send to everyone", (d, w) -> {
                    String text = msg.getText().toString().trim();
                    if (!text.isEmpty()) sendBroadcast(text);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void sendBroadcast(String text) {
        Ui.toast(this, "Sending broadcast…");
        Fb.db().collection("users").limit(400).get().addOnSuccessListener(snap -> {
            List<com.google.firebase.firestore.DocumentSnapshot> docs = snap.getDocuments();
            int total = docs.size();
            java.util.List<java.util.List<com.google.firebase.firestore.DocumentSnapshot>> chunks =
                    Ui.chunks(docs, 400);
            for (java.util.List<com.google.firebase.firestore.DocumentSnapshot> chunk : chunks) {
                WriteBatch batch = Fb.db().batch();
                for (com.google.firebase.firestore.DocumentSnapshot u : chunk) {
                    java.util.Map<String, Object> n = Fb.map(
                            "toUserId", u.getId(),
                            "fromUserId", Fb.uid(),
                            "fromUserName", "friendbook Team",
                            "fromUserPhotoUrl", "",
                            "type", "announcement",
                            "text", "📣 " + text,
                            "isRead", false,
                            "createdAt", FieldValue.serverTimestamp());
                    batch.set(Fb.db().collection("notifications").document(), n);
                }
                batch.commit();
            }
            Ui.toast(this, "Broadcast sent to " + total + " users ✓");
        }).addOnFailureListener(e -> Ui.toast(this, "Failed: " + e.getMessage()));
    }
}
