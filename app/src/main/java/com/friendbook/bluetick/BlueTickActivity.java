package com.friendbook.bluetick;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.friendbook.core.Admin;
import com.friendbook.core.Fb;
import com.friendbook.core.Format;
import com.friendbook.core.Icons;
import com.friendbook.core.Ui;
import com.friendbook.pay.PayRequestActivity;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

/**
 * ✓ Tick Levels — three badges, two ways each:
 *   Blue    ✓  → 5 invites FREE   or Rs 100
 *   Gold    ✓  → 20 invites FREE  or Rs 500
 *   Diamond 💎 → 50 invites FREE  or Rs 1000 (animated badge)
 * Referral progress is live; payment screenshots go through PayRequestActivity
 * into the unified `payments` collection for admin approval.
 */
public class BlueTickActivity extends Activity {

    private static final String[] LEVELS = {"blue", "gold", "diamond"};
    private static final int[] NEED = {Admin.REFERRALS_BLUE, Admin.REFERRALS_GOLD, Admin.REFERRALS_DIAMOND};
    private static final int[] PRICE = {Admin.PRICE_BLUE, Admin.PRICE_GOLD, Admin.PRICE_DIAMOND};

    private final ProgressBar[] bars = new ProgressBar[3];
    private final TextView[] progT = new TextView[3];
    private final LinearLayout[] ctaBox = new LinearLayout[3];

    private ImageView heroBadge;
    private TextView heroStatus, reviewT;
    private ListenerRegistration refsReg, meReg, payReg;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "✓ Tick levels"));
        root.addView(Ui.divider(this));

        ScrollView sv = new ScrollView(this);
        LinearLayout body = Ui.col(this);
        Ui.pad(body, 14, 14, 14, 24);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        // ---- hero: my current tick ----
        LinearLayout hero = Ui.col(this);
        hero.setGravity(Gravity.CENTER);
        hero.setBackground(Ui.round(this, Ui.card(this), 16));
        Ui.pad(hero, 12, 18, 12, 18);
        heroBadge = new ImageView(this);
        int hs = Ui.dp(this, 54);
        hero.addView(heroBadge, new LinearLayout.LayoutParams(hs, hs));
        heroStatus = Ui.tvBold(this, "No tick yet", 18, Ui.txt(this));
        heroStatus.setPadding(0, Ui.dp(this, 6), 0, 0);
        hero.addView(heroStatus);
        reviewT = Ui.tv(this, "", 12, Ui.ORANGE);
        reviewT.setGravity(Gravity.CENTER);
        reviewT.setPadding(0, Ui.dp(this, 4), 0, 0);
        hero.addView(reviewT);
        body.addView(hero, Ui.lpw(-2));

        TextView how = Ui.tv(this,
                "TWO WAYS FOR EVERY LEVEL — share your invite link and earn it FREE, or pay once and upload the receipt. ✓ shows next to your name everywhere.",
                12, Ui.sub(this));
        Ui.pad(how, 4, 12, 4, 4);
        body.addView(how);

        for (int i = 0; i < 3; i++) body.addView(levelCard(i));

        setContentView(root);
    }

    private LinearLayout levelCard(int i) {
        String level = LEVELS[i];
        LinearLayout card = Ui.col(this);
        card.setBackground(Ui.round(this, Ui.card(this), 16));
        Ui.pad(card, 14, 14, 14, 14);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(0, Ui.dp(this, 12), 0, 0);

        LinearLayout head = Ui.row(this);
        ImageView badge = new ImageView(this);
        Icons.setTickBadge(badge, level, "diamond".equals(level));
        int bs = Ui.dp(this, 34);
        head.addView(badge, new LinearLayout.LayoutParams(bs, bs));
        LinearLayout tt = Ui.col(this);
        tt.setPadding(Ui.dp(this, 10), 0, 0, 0);
        tt.addView(Ui.tvBold(this, Admin.tickLabel(level), 17, Ui.txt(this)));
        tt.addView(Ui.tv(this, "diamond".equals(level) ? "The ultimate badge — animated on your profile"
                : "gold".equals(level) ? "Golden badge — top creator status"
                : "The classic verified check", 12, Ui.sub(this)));
        head.addView(tt, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(head);

        // Option A — referrals
        TextView oa = Ui.tvBold(this, "🅰 FREE — invite friends", 13, Icons.BLUE);
        oa.setPadding(0, Ui.dp(this, 12), 0, 0);
        card.addView(oa);
        bars[i] = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bars[i].setMax(NEED[i]);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.setMargins(0, Ui.dp(this, 6), 0, 0);
        card.addView(bars[i], bp);
        progT[i] = Ui.tv(this, "0/" + NEED[i] + " invited", 12, Ui.sub(this));
        card.addView(progT[i]);
        TextView share = Ui.btnOutline(this, "🔗 Share my invite link", Icons.BLUE);
        LinearLayout.LayoutParams shp = new LinearLayout.LayoutParams(-1, -2);
        shp.setMargins(0, Ui.dp(this, 6), 0, 0);
        share.setOnClickListener(v -> {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT,
                    "Join friendbook! Sign up with my link and I earn a tick ✓\n" + Admin.inviteLink(Fb.uid()));
            startActivity(Intent.createChooser(send, "Invite friends"));
        });
        card.addView(share, shp);

        // Option B — pay
        TextView ob = Ui.tvBold(this, "🅱 Pay once — Rs " + PRICE[i], 13, Icons.BLUE);
        ob.setPadding(0, Ui.dp(this, 12), 0, 0);
        card.addView(ob);
        ctaBox[i] = Ui.col(this);
        TextView pay = Ui.btn(this, "💳 Pay Rs " + PRICE[i] + " (JazzCash / Easypaisa)");
        pay.setOnClickListener(v -> {
            Intent in = new Intent(this, PayRequestActivity.class);
            in.putExtra("type", "tick_" + LEVELS[i]);
            in.putExtra("amount", PRICE[i]);
            in.putExtra("title", Admin.tickLabel(LEVELS[i]));
            startActivity(in);
        });
        ctaBox[i].addView(pay, new LinearLayout.LayoutParams(-1, -2));
        card.addView(ctaBox[i]);
        card.setLayoutParams(cp);
        return card;
    }

    @Override
    protected void onStart() {
        super.onStart();
        // my profile → hero badge live
        meReg = Fb.db().collection("users").document(Fb.uid())
                .addSnapshotListener((d, err) -> {
                    if (d == null || !d.exists()) return;
                    String level = Format.strOr(d.getData(), "tickLevel",
                            Format.boolOf(d.getData(), "isVerified") ? "blue" : "none");
                    Icons.setTickBadge(heroBadge, level, true);
                    heroStatus.setText(Icons.tickRank(level) > 0
                            ? "You have the " + Admin.tickLabel(level) + " 🎉" : "No tick yet");
                    int rank = Icons.tickRank(level);
                    for (int i = 0; i < 3; i++)
                        ctaBox[i].setVisibility(rank >= Icons.tickRank(LEVELS[i])
                                ? android.view.View.GONE : android.view.View.VISIBLE);
                });
        // referral count → progress bars live
        refsReg = Fb.db().collection("referrals")
                .whereEqualTo("refBy", Fb.uid())
                .addSnapshotListener((snap, err) -> {
                    int count = snap == null ? 0 : snap.size();
                    for (int i = 0; i < 3; i++) {
                        bars[i].setProgress(Math.min(count, NEED[i]));
                        progT[i].setText(Math.min(count, NEED[i]) + "/" + NEED[i] + " invited"
                                + (count >= NEED[i] ? " — tick unlocked! 🎉" : ""));
                    }
                });
        // any payment of mine under review?
        payReg = Fb.db().collection("payments")
                .whereEqualTo("userId", Fb.uid())
                .whereEqualTo("status", "pending")
                .orderBy("date", Query.Direction.DESCENDING).limit(1)
                .addSnapshotListener((snap, err) -> {
                    if (snap == null || snap.isEmpty()) { reviewT.setText(""); return; }
                    String type = Format.str(snap.getDocuments().get(0).getData(), "type");
                    String label = type != null && type.startsWith("tick_")
                            ? "Your " + Admin.tickLabel(type.substring(5)) + " payment"
                            : "Your payment";
                    reviewT.setText("⏳ " + label + " is under review — admin approves shortly");
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (refsReg != null) refsReg.remove();
        if (meReg != null) meReg.remove();
        if (payReg != null) payReg.remove();
    }
}
