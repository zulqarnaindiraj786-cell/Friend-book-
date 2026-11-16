package com.friendbook.social.admin.frag;

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

import com.friendbook.social.core.Admin;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Ui;
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
        countT = Ui.tv(a, "🔴 Live rooms", 12, Ui.sub(a));
        Ui.pad(countT, 2, 0, 2, 8);
        list.addView(countT);
        return sv;
    }

    @Override
    public void onStart() {
        super.onStart();
        com.friendbook.social.core.D1Snap.read(com.friendbook.social.core.DocQuery.of("lives").limit(50).build(),snap->{
                    android.app.Activity a = getActivity();
                    if (a == null) return;
                    while (list.getChildCount() > 1) list.removeViewAt(1);
                    java.util.List<com.friendbook.social.core.Doc> docs = snap == null
                            ? new java.util.ArrayList<>()
                            : new java.util.ArrayList<>(snap.getDocuments());
                    java.util.Collections.sort(docs, (d1, d2) -> Long.compare(
                            Format.longOf(d2.getData(), "startedAtMs"),
                            Format.longOf(d1.getData(), "startedAtMs")));
                    int n = docs.size();
                    countT.setText(n + " room(s) — newest first");
                    if (n == 0) {
                        TextView e = Ui.tv(a, "🔴 No live rooms yet.", 13, Ui.sub(a));
                        Ui.pad(e, 4, 14, 4, 0);
                        list.addView(e);
                        return;
                    }
                    for (DocumentSnapshot d : docs) list.addView(row(a, d));
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
        String photo = Format.str(m, "hostPhotoUrl");
        if (photo.isEmpty()) photo = Format.str(m, "hostPhoto");
        Ui.avatar(a, av, photo);
        int s = Ui.dp(a, 48);
        card.addView(av, new LinearLayout.LayoutParams(s, s));

        LinearLayout info = Ui.col(a);
        info.setPadding(Ui.dp(a, 12), 0, 0, 0);
        info.addView(Ui.tvBold(a, Format.strOr(m, "hostName", "Host"), 14, Ui.txt(a)));
        info.addView(Ui.tv(a, Format.str(m, "title"), 12, Ui.sub(a)));
        int vCnt = Math.max(Format.intOf(m, "viewers"), Format.intOf(m, "viewerCount"));
        int sCnt = Math.max(Format.intOf(m, "stars"), Format.intOf(m, "totalStars"));
        info.addView(Ui.tv(a, "👁 " + Format.count(vCnt)
                + "  ·  ⭐ " + Format.count(sCnt) + " community stars",
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
                            endLive(d.getId())));
            right.addView(end);
        }
        card.addView(right);
        card.setOnClickListener(v -> {
            android.content.Intent i = new android.content.Intent(a, com.friendbook.social.live.LiveActivity.class);
            i.putExtra("live_id", d.getId());
            i.putExtra("host_id", Format.str(m, "hostId"));
            a.startActivity(i);
        });
        return card;
    }

    /** Ends a broadcast by id; the id is all a Doc carries, so the write names it explicitly. */
    private void endLive(String liveId) {
        Fb.db().collection("lives").document(liveId)
                .update("status", "ended", "endedAt", FieldValue.serverTimestamp());
        com.friendbook.social.core.DataRouter.mirrorPut("lives", liveId,
                Fb.map("status", "ended", "endedAt", System.currentTimeMillis()));
    }
}
