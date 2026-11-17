package com.friendbook.social.social;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Notify;
import com.friendbook.social.core.Ui;
import com.friendbook.social.profile.ProfileActivity;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.HashMap;
import java.util.Map;

/** 👋 Pokes — classic friendbook fun. See who poked you, poke back, clear. */
public class PokesActivity extends Activity {
    private LinearLayout pageControls;

    private LinearLayout list;
    private ListenerRegistration reg;
    private com.friendbook.social.core.DocPager pager;

    /** Send a poke 👋 to someone (rate-limited: once per 24h between the same two). */
    public static void sendPoke(final Activity a, final String toUid, final String toName) {
        final String me = Fb.uid();
        if (me == null || toUid == null || toUid.equals(me)) return;
        // One document per pair, so two people tapping at the same moment collide on the same row
        // instead of creating two pokes. The gate is a D1 read of that row, not a Firestore query:
        // the query answered nothing once Firestore was off, so every poke looked allowed and
        // then the transaction behind it failed, so no poke was ever sent at all.
        final String pair = java.util.UUID.nameUUIDFromBytes(
                (me + "|" + toUid).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        final String docId = "pair_" + pair;
        com.friendbook.social.core.CloudflareStore.get("pokes", docId, existing -> {
            if (!me.equals(Fb.uid())) return;
            android.app.Activity act = a;
            if (act == null || act.isFinishing()) return;
            if (existing != null) {
                long at = Format.ts(existing.get("at"));
                if (at > 0 && System.currentTimeMillis() - at < 24L * 3600_000L) {
                    Ui.toast(act, "You already poked " + toName + " — wait a bit");
                    return;
                }
            }
            java.util.Map<String, Object> poke = Fb.map(
                    "from", me, "fromName", Fb.myName(), "fromPhotoUrl", Fb.myPhoto(),
                    "to", toUid, "at", System.currentTimeMillis());
            com.friendbook.social.core.DataRouter.batch().set("pokes", docId, poke)
                    .commit((ok, error) -> {
                        if (!me.equals(Fb.uid())) return;
                        android.app.Activity current = a;
                        if (current == null || current.isFinishing()) return;
                        if (!ok) { Ui.toast(current, "Couldn't send poke. Reconnect and retry."); return; }
                        Ui.toast(current, "You poked " + toName + "!");
                        Notify.send(toUid, "poke", "poked you", null);
                    });
        });
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "👋 Pokes"));
        root.addView(Ui.divider(this));

        TextView sub = Ui.tv(this, "See who poked you — poke them back! 👋", 13, Ui.sub(this));
        Ui.pad(sub, 16, 8, 16, 4);
        root.addView(sub);

        list = Ui.col(this);
        Ui.pad(list, 12, 6, 12, 24);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        pageControls=Ui.col(this);root.addView(pageControls);
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        String me = Fb.uid();
        if (me == null) return;
        // Incoming pokes read from D1. This was a Firestore query, so the screen said "No pokes
        // yet" no matter how many people had poked you.
        if (pager != null) pager.close();
        list.removeAllViews();
        pager = new com.friendbook.social.core.DocPager(
                com.friendbook.social.core.DocQuery.of("pokes")
                        .whereEqualTo("to", me)
                        .orderBy("at", com.friendbook.social.core.DocQuery.DESC)
                        .build(), 20,
                (docs, more, loading, error) -> {
                    list.removeAllViews();
                    if (loading) return;
                    if (docs.isEmpty()) {
                        TextView e = Ui.tv(this, error.isEmpty()
                                ? "No pokes yet 😴\nVisit a friend's profile and poke them!"
                                : "Couldn't load pokes. Pull to refresh or try again.", 14, Ui.sub(this));
                        Ui.pad(e, 0, 24, 0, 0);
                        list.addView(e);
                        return;
                    }
                    for (Map<String, Object> d : docs) list.addView(row(d));
                });
        pager.start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
        if (pager != null) { pager.close(); pager = null; }
    }

    private View row(Map<String, Object> d) {
        String name = Format.strOr(d, "fromName", "Someone");
        String photo = Format.str(d, "fromPhotoUrl");
        String fromUid = Format.str(d, "from");
        long when = Format.ts(d.get("at"));
        final String rowId = Format.str(d, "id");

        LinearLayout r = Ui.row(this);
        r.setBackground(Ui.round(this, Ui.card(this), 12));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.bottomMargin = Ui.dp(this, 8);
        r.setLayoutParams(rp);
        Ui.pad(r, 12, 10, 12, 10);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);

        ImageView av = new ImageView(this);
        int s = Ui.dp(this, 44);
        r.addView(av, new LinearLayout.LayoutParams(s, s));
        Ui.avatar(this, av, photo);

        LinearLayout ncol = Ui.col(this);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, -2, 1);
        np.leftMargin = Ui.dp(this, 10);
        ncol.addView(Ui.tvBold(this, name + " poked you 👋", 14, Ui.txt(this)));
        ncol.addView(Ui.tv(this, Format.ago(when), 12, Ui.sub(this)));
        r.addView(ncol, np);

        TextView back = Ui.roundChip(this, "👋 Poke back", Ui.BLUE, 0xFFFFFFFF, 12);
        back.setOnClickListener(v -> {
            sendPoke(this, fromUid, name);
            // Clearing the incoming poke is a D1 delete. It was a Firestore delete, so poking
            // back left the original poke on the list forever.
            com.friendbook.social.core.DataRouter.batch().delete("pokes", rowId)
                    .commit((ok, error) -> { if (ok) list.removeView(r); });
        });
        r.addView(back);

        View prof = r;
        prof.setOnClickListener(v -> {
            Intent i = new Intent(this, ProfileActivity.class);
            i.putExtra("uid", fromUid);
            startActivity(i);
        });
        return r;
    }
}
