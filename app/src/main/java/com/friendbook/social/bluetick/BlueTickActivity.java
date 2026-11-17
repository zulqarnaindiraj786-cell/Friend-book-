package com.friendbook.social.bluetick;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.friendbook.social.core.Admin;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Icons;
import com.friendbook.social.core.Ui;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.HashMap;
import java.util.Map;

/**
 * ✓ Verification Badges (100% Free Community & Creator Milestones — Google Play Compliant):
 *   Blue    ✓  → 5 invites FREE   or Creator Identity Review
 *   Gold    ✓  → 20 invites FREE  or Top Creator Review
 *   Diamond 💎 → 50 invites FREE  or Notable Community Leader Review
 * Zero fees, zero off-Play payments — earned 100% free via community activity & invites!
 */
public class BlueTickActivity extends Activity {

    private static final String[] LEVELS = {"blue", "gold", "diamond"};
    private static final int[] NEED = {Admin.REFERRALS_BLUE, Admin.REFERRALS_GOLD, Admin.REFERRALS_DIAMOND};

    private final ProgressBar[] bars = new ProgressBar[3];
    private final TextView[] progT = new TextView[3];
    private final LinearLayout[] ctaBox = new LinearLayout[3];

    private ImageView heroBadge;
    private TextView heroStatus, reviewT;
    private ListenerRegistration refsReg, meReg, reqReg;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "✓ Verification Badges"));
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
        heroStatus = Ui.tvBold(this, "No badge yet", 18, Ui.txt(this));
        heroStatus.setPadding(0, Ui.dp(this, 6), 0, 0);
        hero.addView(heroStatus);
        reviewT = Ui.tv(this, "", 12, Ui.ORANGE);
        reviewT.setGravity(Gravity.CENTER);
        reviewT.setPadding(0, Ui.dp(this, 4), 0, 0);
        hero.addView(reviewT);
        body.addView(hero, Ui.lpw(-2));

        TextView how = Ui.tv(this,
                "100% FREE VERIFICATION — invite friends to unlock badges automatically, or submit a free Creator Verification Review request.",
                12, Ui.sub(this));
        Ui.pad(how, 4, 12, 4, 4);
        body.addView(how);

        for (int i = 0; i < 3; i++) body.addView(levelCard(i));

        setContentView(root);
    }

    private LinearLayout levelCard(int i) {
        final String level = LEVELS[i];
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
        tt.addView(Ui.tv(this, "diamond".equals(level) ? "Ultimate community leader badge — animated on profile"
                : "gold".equals(level) ? "Golden creator badge — top contributor status"
                : "Classic verified check for active community members", 12, Ui.sub(this)));
        head.addView(tt, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(head);

        // Option A — referrals
        TextView oa = Ui.tvBold(this, "🅰 Automatic Unlock — Invite " + NEED[i] + " Friends (Free)", 13, Icons.BLUE);
        oa.setPadding(0, Ui.dp(this, 12), 0, 0);
        card.addView(oa);
        bars[i] = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bars[i].setMax(NEED[i]);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.setMargins(0, Ui.dp(this, 6), 0, 0);
        card.addView(bars[i], bp);
        progT[i] = Ui.tv(this, "0/" + NEED[i] + " invited", 12, Ui.sub(this));
        card.addView(progT[i]);
        TextView share = Ui.btnOutline(this, "🔗 Share friendbook", Icons.BLUE);
        LinearLayout.LayoutParams shp = new LinearLayout.LayoutParams(-1, -2);
        shp.setMargins(0, Ui.dp(this, 6), 0, 0);
        share.setOnClickListener(v -> {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            // ALWAYS share Play Store link
            send.putExtra(Intent.EXTRA_TEXT, "Check out friendbook!\n" + Ui.PLAY_STORE_LINK);
            startActivity(Intent.createChooser(send, "Share friendbook"));
        });
        card.addView(share, shp);

        // Option B — Free Creator Verification Application
        TextView ob = Ui.tvBold(this, "🅱 Free Creator Verification Review", 13, Icons.BLUE);
        ob.setPadding(0, Ui.dp(this, 12), 0, 0);
        card.addView(ob);
        ctaBox[i] = Ui.col(this);
        TextView apply = Ui.btn(this, "✓ Apply Free for " + Admin.tickLabel(level));
        apply.setOnClickListener(v -> submitFreeVerificationRequest(level));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.topMargin = Ui.dp(this, 6);
        ctaBox[i].addView(apply, ap);
        card.addView(ctaBox[i]);
        card.setLayoutParams(cp);
        return card;
    }

    private void submitFreeVerificationRequest(String level) {
        String uid = Fb.uid();
        if (uid == null) return;
        String email = Fb.auth().getCurrentUser() != null ? Fb.auth().getCurrentUser().getEmail() : "";
        Map<String, Object> req = new HashMap<>();
        req.put("userId", uid);
        req.put("userEmail", email == null ? "" : email);
        req.put("type", "tick_" + level);
        req.put("amount", 0);
        req.put("currency", "FREE");
        req.put("status", "pending");
        req.put("screenshotUrl", Fb.myPhoto());
        req.put("date", FieldValue.serverTimestamp());
        com.friendbook.social.core.DataRouter.add("payments", req, (id, error) -> {
            if (error != null) { Ui.toast(this, "Couldn't submit request right now"); return; }
            Ui.info(this, "✓ Free Verification Submitted",
                    "Your free " + Admin.tickLabel(level) + " verification request has been submitted to our community review team.");
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        String uid = Fb.uid();
        if (uid == null) { finish(); return; }
        // Shares the session-wide users/{me} listener instead of opening another on the same doc.
        Fb.observeMe(meObserver);
        refsReg=com.friendbook.social.core.CountReads.once(
                com.friendbook.social.core.DocQuery.of("referrals").whereEqualTo("refBy",uid).build(),total->{
                    int count=(int)Math.min(Integer.MAX_VALUE,total);
                    for (int i = 0; i < 3; i++) {
                        bars[i].setProgress(Math.min(count, NEED[i]));
                        progT[i].setText(Math.min(count, NEED[i]) + "/" + NEED[i] + " invited"
                                + (count >= NEED[i] ? " — badge unlocked! 🎉" : ""));
                    }
                    if (count >= Admin.REFERRALS_BLUE) {
                        Admin.autoGrantByReferrals(this, count);
                    }
                });
        // One read: whether a review is pending changes only when an admin acts, and that is
        // rare enough that a subscription (or a timer) costs far more than it saves.
        com.friendbook.social.core.D1Snap.read(
                com.friendbook.social.core.DocQuery.of("payments")
                        .whereEqualTo("userId", uid)
                        .whereEqualTo("status", "pending")
                        .limit(1).build(), snap -> {
                    if (snap.isEmpty()) { reviewT.setText(""); return; }
                    String type = Format.str(snap.getDocuments().get(0).getData(), "type");
                    String label = type != null && type.startsWith("tick_")
                            ? "Your free " + Admin.tickLabel(type.substring(5)) + " review"
                            : "Your verification review";
                    reviewT.setText("⏳ " + label + " is under review");
                });
    }

    private final Fb.MeObserver meObserver = d -> {
                    if (d == null || !d.exists()) { if (Fb.me != null) renderTick(Fb.me.tickLevel); return; }
                    String level = Format.strOr(d.getData(), "tickLevel",
                            Format.boolOf(d.getData(), "isVerified") ? "blue" : "none");
                    Icons.setTickBadge(heroBadge, level, true);
                    heroStatus.setText(Icons.tickRank(level) > 0
                            ? "You have the " + Admin.tickLabel(level) + " 🎉" : "No badge yet");
                    int rank = Icons.tickRank(level);
                    for (int i = 0; i < 3; i++) {
                        ctaBox[i].setVisibility(rank >= Icons.tickRank(LEVELS[i]) ? View.GONE : View.VISIBLE);
                    }
    };

    @Override
    protected void onStop() {
        Fb.removeMeObserver(meObserver);
        super.onStop();
        if (refsReg != null) refsReg.remove();
        if (meReg != null) meReg.remove();
        if (reqReg != null) reqReg.remove();
    }


    /** Renders the badge hero from a tick level; used by the shared observer. */
    private void renderTick(String level) {
        if (level == null) return;
        Icons.setTickBadge(heroBadge, level, true);
        heroStatus.setText(Icons.tickRank(level) > 0
                ? "You have the " + Admin.tickLabel(level) : "No badge yet");
        int rank = Icons.tickRank(level);
        for (int i = 0; i < 3; i++) {
            ctaBox[i].setVisibility(rank >= Icons.tickRank(LEVELS[i]) ? View.GONE : View.VISIBLE);
        }
    }

}
