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
import com.friendbook.social.d1.documents.DocumentSnapshot;
import com.friendbook.social.d1.documents.FieldValue;
import com.friendbook.social.d1.documents.ListenerRegistration;

import java.util.HashMap;
import java.util.Map;

/** 👋 Pokes — classic friendbook fun. See who poked you, poke back, clear. */
public class PokesActivity extends Activity {
    private LinearLayout pageControls;

    private LinearLayout list;
    private ListenerRegistration reg;

    /** Send a poke 👋 to someone (rate-limited: once per 24h between the same two). */
    public static void sendPoke(final Activity a, final String toUid, final String toName) {
        final String me = Fb.uid();
        if (me == null || toUid == null || toUid.equals(me)) return;
        com.friendbook.social.core.QueryActions.anyRecent(Fb.db().collection("pokes")
                .whereEqualTo("from",me).whereEqualTo("to",toUid),System.currentTimeMillis()-24L*3600_000L)
                .addOnSuccessListener(recent->{
                    if(!me.equals(Fb.uid()))return;
                    if(recent){Ui.toast(a,"You already poked "+toName+" — wait a bit");return;}
                    // A deterministic per-pair document prevents concurrent taps bypassing the 24h gate.
                    String pair=java.util.UUID.nameUUIDFromBytes((me+"|"+toUid).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                    com.friendbook.social.d1.documents.DocumentReference ref=Fb.db().collection("pokes").document("pair_"+pair);
                    Fb.db().runTransaction(tx->{
                        DocumentSnapshot current=tx.get(ref);
                        if(current.exists() && System.currentTimeMillis()-Format.ts(current.get("at"))<24L*3600_000L)return false;
                        tx.set(ref,Fb.map("from",me,"fromName",Fb.myName(),"fromPhotoUrl",Fb.myPhoto(),"to",toUid,"at",FieldValue.serverTimestamp()));return true;
                    }).addOnSuccessListener(written->{
                        if(!me.equals(Fb.uid()))return;
                        if(written){Ui.toast(a,"You poked "+toName+"!");Notify.send(toUid,"poke","poked you",null);}
                        else Ui.toast(a,"Already poked — wait a bit");
                    }).addOnFailureListener(e->Ui.toast(a,"Couldn't send poke. Reconnect and retry."));
                }).addOnFailureListener(e->Ui.toast(a,"Couldn't check the last poke. Please retry."));
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
        reg=com.friendbook.social.core.PagedLists.attach(this,pageControls,
                Fb.db().collection("pokes").whereEqualTo("to",me),20,"pokes",(snap,err)-> {
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
            d.getReference().delete().addOnSuccessListener(v2->list.removeView(r));
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
