package com.friendbook.admin.frag;

import android.app.AlertDialog;
import android.app.Fragment;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.friendbook.core.Admin;
import com.friendbook.core.Fb;
import com.friendbook.core.Format;
import com.friendbook.core.Icons;
import com.friendbook.core.Ui;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.WriteBatch;

import java.util.Date;

/**
 * Admin Payments tab — ONE unified list of every payment type:
 *   tick_blue / tick_gold / tick_diamond  → grant that tick level
 *   boost_profile / boost_post            → activate boost for 24h
 *   subscription                          → activate subscription for 30 days
 * Screenshot thumbnail tappable, Approve / Reject buttons.
 */
public class AdminPaymentsFragment extends Fragment {

    private LinearLayout list;
    private TextView countT;
    private ListenerRegistration reg;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inf, ViewGroup container, Bundle b) {
        android.app.Activity a = getActivity();
        ScrollView sv = new ScrollView(a);
        list = Ui.col(a);
        list.setBackgroundColor(Ui.bg(a));
        Ui.pad(list, 12, 10, 12, 24);
        sv.addView(list);

        countT = Ui.tv(a, "✓ Badge & Verification Requests", 12, Ui.sub(a));
        Ui.pad(countT, 2, 0, 2, 8);
        list.addView(countT);
        return sv;
    }

    @Override
    public void onStart() {
        super.onStart();
        reg = Fb.db().collection("payments")
                .orderBy("date", Query.Direction.DESCENDING).limit(100)
                .addSnapshotListener((snap, err) -> {
                    android.app.Activity a = getActivity();
                    if (a == null) return;
                    while (list.getChildCount() > 1) list.removeViewAt(1);
                    if (snap == null || snap.isEmpty()) {
                        countT.setText("0 requests yet");
                        TextView empty = Ui.tv(a, "✓ Free creator badge & verification requests will appear here.",
                                13, Ui.sub(a));
                        Ui.pad(empty, 4, 14, 4, 0);
                        list.addView(empty);
                        return;
                    }
                    countT.setText(snap.size() + " request(s) — newest first");
                    for (DocumentSnapshot d : snap.getDocuments()) list.addView(row(a, d));
                });
    }

    @Override
    public void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
    }

    private View row(android.app.Activity a, DocumentSnapshot d) {
        java.util.Map<String, Object> m = d.getData();
        String userId = Format.str(m, "userId");
        String email = Format.str(m, "userEmail");
        String type = Format.str(m, "type");
        String status = Format.strOr(m, "status", "pending");
        String shot = Format.str(m, "screenshotUrl");
        long amount = Format.intOf(m, "amount");
        String currency = Format.strOr(m, "currency", "PKR");
        long date = Format.ts(m == null ? null : m.get("date"));

        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.round(a, Ui.card(a), 14));
        Ui.pad(card, 12, 10, 12, 10);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(0, 0, 0, Ui.dp(a, 10));
        card.setLayoutParams(cp);

        LinearLayout top = Ui.row(a);
        ImageView thumb = new ImageView(a);
        int ts = Ui.dp(a, 64);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ts, ts);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(Ui.round(a, Ui.inputBg(a), 10));
        thumb.setClipToOutline(true);
        Ui.img(a, thumb, shot);
        thumb.setOnClickListener(v -> {
            Intent i = new Intent(a, com.friendbook.post.FullscreenMediaActivity.class);
            i.putExtra("url", shot);
            a.startActivity(i);
        });
        top.addView(thumb, tp);

        LinearLayout info = Ui.col(a);
        info.setPadding(Ui.dp(a, 12), 0, 0, 0);
        info.addView(Ui.tvBold(a, typeLabel(type) + "  ·  Free Community Review",
                14, Ui.txt(a)));
        info.addView(Ui.tv(a, email, 12, Ui.sub(a)));
        info.addView(Ui.tv(a, "uid: " + userId, 10, Ui.sub(a)));
        info.addView(Ui.tv(a, Format.ago(date), 11, Ui.sub(a)));
        top.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(top);

        TextView statusT = Ui.roundChip(a,
                "pending".equals(status) ? "⏳ PENDING" : "approved".equals(status) ? "✅ APPROVED" : "❌ REJECTED",
                "pending".equals(status) ? 0x22F7B928 : "approved".equals(status) ? 0x2231A24C : 0x22E41E3F,
                "pending".equals(status) ? Ui.ORANGE : "approved".equals(status) ? Ui.GREEN : Ui.RED, 11);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.setMargins(0, Ui.dp(a, 8), 0, 0);
        card.addView(statusT, sp);

        if ("pending".equals(status)) {
            LinearLayout btns = Ui.row(a);
            btns.setPadding(0, Ui.dp(a, 10), 0, 0);
            TextView approve = Ui.btn(a, "✓ Approve");
            approve.setOnClickListener(v -> confirm(a, "Approve " + typeLabel(type) + "?",
                    "The feature activates instantly for this user.", () -> approve(d, m)));
            TextView reject = Ui.btnGrey(a, "✕ Reject");
            reject.setOnClickListener(v -> confirm(a, "Reject?", "User will need to repay with a valid receipt.",
                    () -> d.getReference().update("status", "rejected",
                            "rejectedBy", Fb.uid(), "rejectedAt", FieldValue.serverTimestamp())));
            LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, Ui.dp(a, 38), 1);
            LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, Ui.dp(a, 38), 1);
            b2.leftMargin = Ui.dp(a, 8);
            btns.addView(approve, b1);
            btns.addView(reject, b2);
            card.addView(btns);
        }
        return card;
    }

    private String typeLabel(String type) {
        if (type == null) return "Payment";
        switch (type) {
            case "tick_blue": return "✓ Blue Tick";
            case "tick_gold": return "✨ Gold Tick";
            case "tick_diamond": return "💎 Diamond Tick";
            case "subscription": return "⭐ Subscription";
            default: return type;
        }
    }

    private void confirm(android.app.Activity a, String t, String msg, Runnable go) {
        new AlertDialog.Builder(a).setTitle(t).setMessage(msg)
                .setPositiveButton("Yes", (d, w) -> go.run())
                .setNegativeButton("Cancel", null).show();
    }

    /** Approve = write payment approved + activate the purchased thing, in one batch. */
    private void approve(DocumentSnapshot d, java.util.Map<String, Object> m) {
        android.app.Activity a = getActivity();
        String type = Format.str(m, "type");
        String uid = Format.str(m, "userId");
        WriteBatch batch = Fb.db().batch();
        batch.update(d.getReference(), "status", "approved",
                "approvedBy", Fb.uid(), "approvedAt", FieldValue.serverTimestamp());

        if (type != null && type.startsWith("tick_")) {
            String level = type.substring(5);
            batch.set(Fb.db().collection("users").document(uid),
                    Fb.map("tickLevel", level, "isVerified", true), SetOptions.merge());
        } else if ("subscription".equals(type)) {
            String subId = Format.str(m, "subscriptionId");
            if (!subId.isEmpty()) {
                DocumentReference su = Fb.db().collection("subscriptions").document(subId);
                batch.update(su, "status", "active",
                        "expiry", new com.google.firebase.Timestamp(
                                new Date(System.currentTimeMillis() + 30L * 24 * 3600 * 1000)));
            }
        }
        batch.commit().addOnSuccessListener(x -> {
            if (a == null) return;
            if (type != null && type.startsWith("tick_")) {
                Admin.syncAuthorTickLevel(uid, type.substring(5));
            }
            notifyUser(uid, type);
            Ui.toast(a, "Approved ✓ — activated");
        }).addOnFailureListener(e -> { if (a != null) Ui.toast(a, "Error: " + e.getMessage()); });
    }

    private void notifyUser(String uid, String type) {
        String text;
        if (type != null && type.startsWith("tick_"))
            text = "✓ Approved! Your " + Admin.tickLabel(type.substring(5)) + " is now live 🎉";
        else text = "⭐ Your request was approved. Enjoy!";
        java.util.Map<String, Object> n = Fb.map(
                "toUserId", uid,
                "fromUserId", Ui.TEAM_USER_ID,
                "fromUserName", "friendbook Team",
                "fromUserPhotoUrl", Ui.TEAM_AVATAR_TOKEN,
                "type", "payment",
                "text", text,
                "isRead", false,
                "createdAt", FieldValue.serverTimestamp());
        Fb.db().collection("notifications").add(n);
    }
}
