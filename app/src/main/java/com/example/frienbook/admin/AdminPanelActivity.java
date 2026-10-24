package com.example.frienbook.admin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.example.frienbook.core.Admin;
import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Ui;
import com.google.firebase.firestore.AggregateSource;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.WriteBatch;

import java.util.List;

/**
 * Admin Panel — visible ONLY for Admin.OWNER_EMAIL (zulqarnaindiraj786@gmail.com)
 * or users flagged isAdmin=true in Firestore.
 */
public class AdminPanelActivity extends Activity {

    private final TextView[] countViews = new TextView[8];
    private static final String[] COLS = {
            "users", "posts", "reels", "stories", "chats", "comments", "reports", "feedback"};
    private static final String[] LABELS = {
            "👥 Users", "📝 Posts", "🎞 Reels", "📖 Stories", "💬 Chats", "💭 Comments", "🚩 Reports", "📮 Feedback"};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Admin.isAdmin()) { Ui.toast(this, "Admins only"); finish(); return; }
        Ui.statusBarBlue(this);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "🛡 Admin Panel"));

        ScrollView sv = new ScrollView(this);
        LinearLayout body = Ui.col(this);
        Ui.pad(body, 12, 10, 12, 20);

        // ---- stats grid ----
        body.addView(header("Live statistics"));
        for (int i = 0; i < LABELS.length; i += 2) {
            LinearLayout row = Ui.row(this);
            body.addView(row);
            row.addView(statCard(LABELS[i], i), weightLp());
            LinearLayout.LayoutParams lp2 = weightLp();
            lp2.leftMargin = Ui.dp(this, 8);
            if (i + 1 < LABELS.length) row.addView(statCard(LABELS[i + 1], i + 1), lp2);
        }

        body.addView(header("Management"));
        body.addView(row("👥", "Manage users", "Search, promote, ban or remove accounts",
                () -> startActivity(new Intent(this, AdminUsersActivity.class))));
        body.addView(row("🗂", "Content moderation", "Posts, reels, stories, comments — review & delete",
                () -> startActivity(new Intent(this, AdminContentActivity.class))));
        body.addView(row("🚩", "Reports queue", "Act on reported posts, comments, users",
                () -> startActivity(new Intent(this, AdminReportsActivity.class))));
        body.addView(row("📮", "Feedback inbox", "What users shake-reported",
                () -> startActivity(new Intent(this, AdminFeedbackActivity.class))));
        body.addView(row("📣", "Broadcast notification", "Send a notification to EVERY user",
                this::broadcastDialog));
        body.addView(row("👑", "Admin account", Admin.OWNER_EMAIL,
                () -> Ui.info(this, "Admin access",
                        "Owner: " + Admin.OWNER_EMAIL + "\n\nPromote others from Manage users → Make admin.\n"
                                + "Banned users are signed out at login and blocked from the app.")));

        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        loadCounts();
    }

    private LinearLayout.LayoutParams weightLp() {
        return new LinearLayout.LayoutParams(0, -2, 1);
    }

    private TextView header(String t) {
        TextView h = Ui.tvBold(this, t.toUpperCase(), 12, Ui.sub(this));
        h.setPadding(Ui.dp(this, 4), Ui.dp(this, 14), 0, Ui.dp(this, 6));
        return h;
    }

    private LinearLayout statCard(String label, int idx) {
        LinearLayout card = Ui.col(this);
        card.setBackground(Ui.round(this, Ui.card(this), 10));
        Ui.pad(card, 14, 12, 14, 12);
        TextView num = Ui.tvBold(this, "…", 24, Ui.BLUE);
        TextView lab = Ui.tv(this, label, 13, Ui.sub(this));
        card.addView(num);
        card.addView(lab);
        countViews[idx] = num;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(this, 8);
        card.setLayoutParams(lp);
        return card;
    }

    private void loadCounts() {
        for (int i = 0; i < COLS.length; i++) {
            final int idx = i;
            Fb.db().collection(COLS[i]).count().get(AggregateSource.SERVER)
                    .addOnSuccessListener(snapshot ->
                            countViews[idx].setText(com.example.frienbook.core.Format.count(snapshot.getCount())))
                    .addOnFailureListener(e ->
                            // fallback: naive fetch (small collections)
                            Fb.db().collection(COLS[idx]).limit(200).get()
                                    .addOnSuccessListener(s2 ->
                                            countViews[idx].setText(s2.size() >= 200 ? "200+" : String.valueOf(s2.size())))
                                    .addOnFailureListener(e2 -> countViews[idx].setText("—")));
        }
    }

    private View row(String emoji, String title, String sub, Runnable action) {
        LinearLayout row = Ui.row(this);
        row.setBackground(Ui.round(this, Ui.card(this), 10));
        Ui.pad(row, 14, 12, 14, 12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(this, 8);
        row.setLayoutParams(lp);
        TextView e = Ui.tv(this, emoji, 20, Ui.txt(this));
        row.addView(e);
        LinearLayout mid = Ui.col(this);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1);
        mp.leftMargin = Ui.dp(this, 12);
        mid.addView(Ui.tvBold(this, title, 15, Ui.txt(this)));
        TextView s = Ui.tv(this, sub, 12, Ui.sub(this));
        mid.addView(s);
        row.addView(mid, mp);
        TextView arrow = Ui.tv(this, "›", 20, Ui.sub(this));
        row.addView(arrow);
        row.setOnClickListener(v -> action.run());
        return row;
    }

    /** Broadcast: one notification doc per user, batched. */
    private void broadcastDialog() {
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 20, 10, 20, 0);
        EditText msg = Ui.multi(this, "Message for every FriendBook user…", 3);
        box.addView(msg, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("📣 Broadcast to all users")
                .setMessage("Everyone with an account will get this in Notifications.")
                .setView(box)
                .setPositiveButton("Send to everyone", (d, w) -> {
                    String text = msg.getText().toString().trim();
                    if (text.isEmpty()) return;
                    sendBroadcast(text);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void sendBroadcast(String text) {
        Ui.toast(this, "Sending broadcast…");
        Fb.db().collection("users").limit(400).get().addOnSuccessListener(snap -> {
            List<com.google.firebase.firestore.DocumentSnapshot> docs = snap.getDocuments();
            int total = docs.size();
            int sent = 0;
            java.util.List<java.util.List<com.google.firebase.firestore.DocumentSnapshot>> chunks =
                    Ui.chunks(docs, 400);
            for (java.util.List<com.google.firebase.firestore.DocumentSnapshot> chunk : chunks) {
                WriteBatch batch = Fb.db().batch();
                for (com.google.firebase.firestore.DocumentSnapshot u : chunk) {
                    java.util.Map<String, Object> n = Fb.map(
                            "toUserId", u.getId(),
                            "fromUserId", Fb.uid(),
                            "fromUserName", "FriendBook Team",
                            "fromUserPhotoUrl", "",
                            "type", "announcement",
                            "text", "📣 " + text,
                            "isRead", false,
                            "createdAt", FieldValue.serverTimestamp());
                    batch.set(Fb.db().collection("notifications").document(), n);
                }
                batch.commit();
                sent += chunk.size();
            }
            Ui.toast(this, "Broadcast sent to " + sent + " users ✓");
        }).addOnFailureListener(e -> Ui.toast(this, "Failed: " + e.getMessage()));
    }
}
