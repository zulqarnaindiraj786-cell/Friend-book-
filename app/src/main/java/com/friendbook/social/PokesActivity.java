package com.friendbook.social;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.friendbook.core.Fb;
import com.friendbook.core.Format;
import com.friendbook.core.Notify;
import com.friendbook.core.Ui;
import com.friendbook.profile.ProfileActivity;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.HashMap;
import java.util.Map;

/** 👋 Pokes — classic friendbook fun. See who poked you, poke back, clear. */
public class PokesActivity extends Activity {

    private LinearLayout list;
    private ListenerRegistration reg;

    /** Send a poke 👋 to someone (rate-limited: once per 24h between the same two). */
    public static void sendPoke(final Activity a, final String toUid, final String toName) {
        final String me = Fb.uid();
        if (me == null || toUid == null || toUid.equals(me)) return;
        Fb.db().collection("pokes")
                .whereEqualTo("from", me).whereEqualTo("to", toUid)
                .get().addOnSuccessListener(snap -> {
                    long now = System.currentTimeMillis();
                    for (DocumentSnapshot d : snap) {
                        Object at = d.get("at");
                        long t = at instanceof com.google.firebase.Timestamp
                                ? ((com.google.firebase.Timestamp) at).toDate().getTime()
                                : at instanceof Number ? ((Number) at).longValue() : 0;
                        if (now - t < 24L * 3600_000L) {
                            Ui.toast(a, "You already poked " + toName + " — wait a bit 😄");
                            return;
                        }
                    }
                    Map<String, Object> p = new HashMap<>();
                    p.put("from", me);
                    p.put("fromName", Fb.myName());
                    p.put("fromPhotoUrl", Fb.myPhoto());
                    p.put("to", toUid);
                    p.put("at", FieldValue.serverTimestamp());
                    Fb.db().collection("pokes").add(p)
                            .addOnSuccessListener(x -> {
                                Ui.toast(a, "👋 You poked " + toName + "!");
                                Notify.send(toUid, "poke", "poked you 👋", null);
                            })
                            .addOnFailureListener(e -> Ui.toast(a, e.getMessage()));
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
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        String me = Fb.uid();
        if (me == null) return;
        reg = Fb.db().collection("pokes").whereEqualTo("to", me)
                .addSnapshotListener((snap, err) -> {
                    list.removeAllViews();
                    if (snap == null || snap.isEmpty()) {
                        TextView e = Ui.tv(this, "No pokes yet 😴\nVisit a friend's profile and poke them!", 14, Ui.sub(this));
                        Ui.pad(e, 0, 24, 0, 0);
                        list.addView(e);
                        return;
                    }
                    for (DocumentSnapshot d : snap) list.addView(row(d));
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
    }

    private View row(DocumentSnapshot d) {
        String name = Format.strOr(d.getData(), "fromName", "Someone");
        String photo = Format.str(d.getData(), "fromPhotoUrl");
        String fromUid = Format.str(d.getData(), "from");
        Object at = d.get("at");
        long when = at instanceof com.google.firebase.Timestamp
                ? ((com.google.firebase.Timestamp) at).toDate().getTime() : 0;

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
            d.getReference().delete();
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
