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

import com.example.frienbook.core.Admin;
import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Format;
import com.example.frienbook.core.Ui;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import java.util.Map;

/** Admin Live tab — all rooms (live + recent ended), viewers, ⭐ stars, End control. */
public class AdminLiveFragment extends Fragment {

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
        countT = Ui.tv(a, "Loading lives…", 12, Ui.sub(a));
        Ui.pad(countT, 2, 0, 2, 8);
        list.addView(countT);
        return sv;
    }

    @Override
    public void onStart() {
        super.onStart();
        reg = Fb.db().collection("lives")
                .orderBy("startedAt", Query.Direction.DESCENDING).limit(50)
                .addSnapshotListener((snap, err) -> {
                    android.app.Activity a = getActivity();
                    if (a == null) return;
                    while (list.getChildCount() > 1) list.removeViewAt(1);
                    int n = snap == null ? 0 : snap.size();
                    countT.setText(n + " room(s) — newest first");
                    if (n == 0) {
                        TextView e = Ui.tv(a, "🔴 No live rooms yet.", 13, Ui.sub(a));
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
        boolean live = "live".equals(Format.str(m, "status"));

        LinearLayout card = Ui.row(a);
        card.setBackground(Ui.round(a, Ui.card(a), 14));
        Ui.pad(card, 12, 10, 12, 10);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(0, 0, 0, Ui.dp(a, 10));
        card.setLayoutParams(cp);

        ImageView av = new ImageView(a);
        Ui.avatar(a, av, Format.str(m, "hostPhotoUrl"));
        int s = Ui.dp(a, 48);
        card.addView(av, new LinearLayout.LayoutParams(s, s));

        LinearLayout info = Ui.col(a);
        info.setPadding(Ui.dp(a, 12), 0, 0, 0);
        info.addView(Ui.tvBold(a, Format.strOr(m, "hostName", "Host"), 14, Ui.txt(a)));
        info.addView(Ui.tv(a, Format.str(m, "title"), 12, Ui.sub(a)));
        info.addView(Ui.tv(a, "👁 " + Format.count(Format.intOf(m, "viewers"))
                + "  ·  ⭐ " + Format.count(Format.intOf(m, "stars"))
                + " (≈ Rs " + Format.count(Format.intOf(m, "stars") * Admin.STAR_VALUE_PKR) + ")",
                11, Ui.sub(a)));
        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout right = Ui.col(a);
        TextView chip = Ui.roundChip(a, live ? "🔴 LIVE" : "ENDED",
                live ? 0xFFE41E3F : 0x3365676B, 0xFFFFFFFF, 10);
        right.addView(chip);
        if (live) {
            TextView end = Ui.tvBold(a, "End", 12, Ui.RED);
            end.setPadding(Ui.dp(a, 8), Ui.dp(a, 8), 0, 0);
            end.setOnClickListener(v -> Ui.confirm(a, "End this live?",
                    "Viewers will see it end immediately.", "End live", () ->
                            d.getReference().update("status", "ended", "endedAt", FieldValue.serverTimestamp())));
            right.addView(end);
        }
        card.addView(right);
        card.setOnClickListener(v -> {
            android.content.Intent i = new android.content.Intent(a, com.example.frienbook.live.LiveActivity.class);
            i.putExtra("live_id", d.getId());
            i.putExtra("host_id", Format.str(m, "hostId"));
            a.startActivity(i);
        });
        return card;
    }
}
