package com.example.frienbook.admin.frag;

import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Format;
import com.example.frienbook.core.Ui;
import com.example.frienbook.core.cloud.Timestamp;
import com.example.frienbook.core.cloud.DocumentSnapshot;
import com.example.frienbook.core.cloud.FieldValue;
import com.example.frienbook.core.cloud.ListenerRegistration;
import com.example.frienbook.core.cloud.Query;

import java.util.Date;
import java.util.Map;

/**
 * Admin Boosts tab — every boost request/live boost with Approve / Reject / Stop.
 * Approving syncs the matching payment doc too (payments.boostId == boost id).
 */
public class AdminBoostsFragment extends Fragment {

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
        countT = Ui.tv(a, "Loading boosts…", 12, Ui.sub(a));
        Ui.pad(countT, 2, 0, 2, 8);
        list.addView(countT);
        return sv;
    }

    @Override
    public void onStart() {
        super.onStart();
        reg = Fb.db().collection("boosts")
                .orderBy("createdAt", Query.Direction.DESCENDING).limit(100)
                .addSnapshotListener((snap, err) -> {
                    android.app.Activity a = getActivity();
                    if (a == null) return;
                    while (list.getChildCount() > 1) list.removeViewAt(1);
                    int n = snap == null ? 0 : snap.size();
                    countT.setText(n + " boost(s)");
                    if (n == 0) {
                        TextView e = Ui.tv(a, "🚀 Boost requests will appear here (profile 24h = Rs 50, post = Rs 30).",
                                13, Ui.sub(a));
                        Ui.pad(e, 4, 14, 4, 0);
                        list.addView(e);
                        return;
                    }
                    for (DocumentSnapshot d : snap.getDocuments()) list.addView(row(a, d));
                });
    }

    @Override
    public void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
    }

    private View row(android.app.Activity a, DocumentSnapshot d) {
        Map<String, Object> m = d.getData();
        String type = Format.str(m, "type");
        String status = Format.strOr(m, "status", "pending");
        String userId = Format.str(m, "userId");
        long exp = Format.ts(m == null ? null : m.get("expiry"));

        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.round(a, Ui.card(a), 14));
        Ui.pad(card, 12, 10, 12, 12);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(0, 0, 0, Ui.dp(a, 10));
        card.setLayoutParams(cp);

        LinearLayout top = Ui.row(a);
        ImageView ic = new ImageView(a);
        ic.setImageDrawable(com.example.frienbook.core.Icons.tinted(a,
                com.example.frienbook.core.Icons.BOOST, Ui.ORANGE));
        int s = Ui.dp(a, 34);
        top.addView(ic, new LinearLayout.LayoutParams(s, s));
        LinearLayout info = Ui.col(a);
        info.setPadding(Ui.dp(a, 12), 0, 0, 0);
        info.addView(Ui.tvBold(a, "profile".equals(type) ? "🚀 Profile Boost (24h)" : "🚀 Post Boost", 14, Ui.txt(a)));
        info.addView(Ui.tv(a, "uid: " + userId + (Format.str(m, "postId").isEmpty() ? "" : "\npost: " + Format.str(m, "postId")), 11, Ui.sub(a)));
        info.addView(Ui.tv(a, "Rs " + Format.intOf(m, "amount") + " · " + Format.ago(Format.ts(m == null ? null : m.get("createdAt"))), 11, Ui.sub(a)));
        top.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(top);

        boolean expired = exp != 0 && exp < System.currentTimeMillis();
        String shown = "pending".equals(status) ? "⏳ PENDING"
                : "active".equals(status) && expired ? "⌛ EXPIRED"
                : "active".equals(status) ? "✅ ACTIVE (till " + new Date(exp) + ")"
                : status.toUpperCase();
        TextView st = Ui.roundChip(a, shown,
                "active".equals(status) && !expired ? 0x2231A24C : 0x22F7B928,
                "active".equals(status) && !expired ? Ui.GREEN : Ui.ORANGE, 11);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.setMargins(0, Ui.dp(a, 8), 0, 0);
        card.addView(st, sp);

        LinearLayout btns = Ui.row(a);
        btns.setPadding(0, Ui.dp(a, 8), 0, 0);
        if ("pending".equals(status)) {
            TextView ap = Ui.btn(a, "✓ Approve & activate");
            ap.setOnClickListener(v -> activate(d));
            btns.addView(ap, new LinearLayout.LayoutParams(0, Ui.dp(a, 38), 1));
            TextView rj = Ui.btnGrey(a, "✕ Reject");
            rj.setOnClickListener(v -> d.getReference().update("status", "rejected"));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, Ui.dp(a, 38), 1);
            rp.leftMargin = Ui.dp(a, 8);
            btns.addView(rj, rp);
        } else if ("active".equals(status) && !expired) {
            TextView stop = Ui.btnGrey(a, "⏹ Stop boost");
            stop.setOnClickListener(v -> d.getReference().update("status", "stopped"));
            btns.addView(stop, new LinearLayout.LayoutParams(0, Ui.dp(a, 38), 1));
        }
        if (btns.getChildCount() > 0) card.addView(btns);
        return card;
    }

    private void activate(DocumentSnapshot boost) {
        boost.getReference().update(
                "status", "active",
                "expiry", new Timestamp(new Date(System.currentTimeMillis() + 24L * 3600 * 1000)));
        // approve the linked payment doc too so earnings stay consistent
        Fb.db().collection("payments").whereEqualTo("boostId", boost.getId()).limit(1).get()
                .addOnSuccessListener(snap -> {
                    for (DocumentSnapshot p : snap)
                        p.getReference().update("status", "approved",
                                "approvedAt", FieldValue.serverTimestamp());
                });
        Ui.toast(getActivity(), "Boost active for 24h ✓");
    }
}
