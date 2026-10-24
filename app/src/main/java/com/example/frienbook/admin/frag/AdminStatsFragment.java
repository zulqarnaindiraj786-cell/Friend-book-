package com.example.frienbook.admin.frag;

import android.app.AlertDialog;
import android.app.Fragment;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.example.frienbook.admin.AdminContentActivity;
import com.example.frienbook.admin.AdminFeedbackActivity;
import com.example.frienbook.admin.AdminPanelActivity;
import com.example.frienbook.admin.AdminReportsActivity;
import com.example.frienbook.core.Admin;
import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Format;
import com.example.frienbook.core.Icons;
import com.example.frienbook.core.Ui;
import com.google.firebase.firestore.AggregateSource;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;

/** Admin Tab 4: live stats (users, referrals, pending payments) + moderation shortcuts. */
public class AdminStatsFragment extends Fragment {

    private final TextView[] val = new TextView[4];

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inf, ViewGroup container, Bundle b) {
        android.app.Activity a = getActivity();
        LinearLayout body = Ui.col(a);
        Ui.pad(body, 12, 10, 12, 20);

        String[] labels = {"👥 Total users", "🎁 Total referrals", "💳 Pending payments", "✓ Verified users"};
        for (int i = 0; i < 4; i += 2) {
            LinearLayout row = Ui.row(a);
            row.addView(statCard(a, labels[i], i), new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.leftMargin = Ui.dp(a, 8);
            row.addView(statCard(a, labels[i + 1], i + 1), lp);
            body.addView(row);
        }

        body.addView(header(a, "Moderation"));
        body.addView(row(a, Icons.FLAG, "🚩", "Reports queue", "Review reported content",
                () -> startActivity(new Intent(a, AdminReportsActivity.class))));
        body.addView(row(a, Icons.IMAGE, "🗂", "Content moderation", "Posts, reels, stories, comments",
                () -> startActivity(new Intent(a, AdminContentActivity.class))));
        body.addView(row(a, Icons.MESSAGES, "📮", "Feedback inbox", "Shake reports from users",
                () -> startActivity(new Intent(a, AdminFeedbackActivity.class))));
        body.addView(header(a, "Payments config"));
        body.addView(row(a, Icons.BANKNOTE, "💳", "JazzCash / Easypaisa numbers",
                "Where users send the Rs 100 blue-tick fee", this::editPaymentNumbers));
        body.addView(row(a, Icons.MEGAPHONE, "📣", "Broadcast to all users", "Notification for everyone",
                () -> ((AdminPanelActivity) a).showBroadcastDialog()));
        return body;
    }

    private TextView header(android.app.Activity a, String t) {
        TextView h = Ui.tvBold(a, t.toUpperCase(), 12, Ui.sub(a));
        h.setPadding(Ui.dp(a, 4), Ui.dp(a, 14), 0, Ui.dp(a, 6));
        return h;
    }

    private LinearLayout statCard(android.app.Activity a, String label, int idx) {
        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.round(a, Ui.card(a), 10));
        Ui.pad(card, 14, 12, 14, 12);
        TextView num = Ui.tvBold(a, "…", 26, Ui.BLUE);
        TextView lab = Ui.tv(a, label, 12, Ui.sub(a));
        card.addView(num);
        card.addView(lab);
        val[idx] = num;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(a, 8);
        card.setLayoutParams(lp);
        return card;
    }

    private View row(android.app.Activity a, int icon, String fallback, String title, String sub, Runnable action) {
        LinearLayout row = Ui.row(a);
        row.setBackground(Ui.round(a, Ui.card(a), 10));
        Ui.pad(row, 12, 12, 12, 12);
        row.addView(Icons.chipIcon(a, icon, Icons.GRAY));
        LinearLayout mid = Ui.col(a);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1);
        mp.leftMargin = Ui.dp(a, 12);
        mid.addView(Ui.tvBold(a, title, 15, Ui.txt(a)));
        mid.addView(Ui.tv(a, sub, 12, Ui.sub(a)));
        row.addView(mid, mp);
        row.addView(Ui.tv(a, "›", 20, Ui.sub(a)));
        row.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(a, 8);
        row.setLayoutParams(lp);
        return row;
    }

    @Override
    public void onStart() {
        super.onStart();
        count("users", 0, null);
        count("referrals", 1, null);
        count("blueTickPayments", 2, "pending");
        countVerified(3);
    }

    private void count(String col, int idx, String pendingOnly) {
        com.google.firebase.firestore.Query q = Fb.db().collection(col);
        if ("pending".equals(pendingOnly)) q = q.whereEqualTo("status", "pending");
        q.count().get(AggregateSource.SERVER)
                .addOnSuccessListener(s -> val[idx].setText(Format.count(s.getCount())))
                .addOnFailureListener(e -> val[idx].setText("—"));
    }

    private void countVerified(int idx) {
        Fb.db().collection("users").whereEqualTo("isVerified", true).count()
                .get(AggregateSource.SERVER)
                .addOnSuccessListener(s -> val[idx].setText(Format.count(s.getCount())))
                .addOnFailureListener(e -> val[idx].setText("—"));
    }

    /** Edit the JazzCash/Easypaisa numbers users see (stored in config/payments). */
    private void editPaymentNumbers() {
        android.app.Activity a = getActivity();
        if (a == null) return;
        Fb.db().collection("config").document("payments").get().addOnSuccessListener(d -> {
            LinearLayout box = Ui.col(a);
            Ui.pad(box, 18, 10, 18, 0);
            EditText jazz = Ui.input(a, "JazzCash number");
            jazz.setText(Format.strOr(d.getData(), "jazzcash", "0301-2345678"));
            EditText ep = Ui.input(a, "Easypaisa number");
            ep.setText(Format.strOr(d.getData(), "easypaisa", "0345-6789012"));
            box.addView(jazz, new LinearLayout.LayoutParams(-1, Ui.dp(a, 46)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(a, 46));
            lp.topMargin = Ui.dp(a, 10);
            box.addView(ep, lp);
            new AlertDialog.Builder(a)
                    .setTitle("💳 Payment numbers")
                    .setView(box)
                    .setPositiveButton("Save", (dlg, w) -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("jazzcash", jazz.getText().toString().trim());
                        m.put("easypaisa", ep.getText().toString().trim());
                        m.put("updatedAt", FieldValue.serverTimestamp());
                        Fb.db().collection("config").document("payments").set(m, SetOptions.merge());
                        Ui.toast(a, "Payment numbers updated ✓");
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }
}
