package com.friendbook.bluetick;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.friendbook.core.Fb;
import com.friendbook.core.Format;
import com.friendbook.core.Icons;
import com.friendbook.core.Prefs;
import com.friendbook.core.Ui;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;

/**
 * Monetization (Facebook style, real data):
 *  • In-Stream Ads eligibility (computed from YOUR real followers + reels counts)
 *  • Stars balance (users.stars)
 *  • Fan Subscriptions toggle + price on your profile.
 */
public class MonetizationActivity extends Activity {

    private TextView adsStatus, adsProgressT, starsT, subsT;
    private ProgressBar adsBar;
    private Switch subsSwitch;
    private TextView subsCountT;
    private com.google.firebase.firestore.ListenerRegistration subsCountReg;
    private ListenerRegistration meReg;
    private int followersCount = 0;

    private static final int FOLLOWERS_FOR_ADS = 100;
    private static final int REELS_FOR_ADS = 5;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "Monetization"));

        ScrollView sv = new ScrollView(this);
        LinearLayout body = Ui.col(this);
        Ui.pad(body, 12, 10, 12, 24);

        // ---------- In-Stream Ads ----------
        LinearLayout ads = Ui.col(this);
        ads.setBackground(Ui.round(this, Ui.card(this), 14));
        Ui.pad(ads, 14, 14, 14, 14);
        ads.addView(toolHeader(Icons.VIDEO, "In-Stream Ads"));
        adsStatus = Ui.tvBold(this, "Checking eligibility…", 15, Ui.txt(this));
        adsStatus.setPadding(0, Ui.dp(this, 8), 0, 0);
        ads.addView(adsStatus);
        adsBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        adsBar.setMax(100);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, Ui.dp(this, 10));
        ap.topMargin = Ui.dp(this, 8);
        ads.addView(adsBar, ap);
        adsProgressT = Ui.tv(this, "", 12, Ui.sub(this));
        adsProgressT.setPadding(0, Ui.dp(this, 6), 0, 0);
        ads.addView(adsProgressT);
        body.addView(ads);
        body.addView(sp(12));

        // ---------- Stars ----------
        LinearLayout stars = Ui.col(this);
        stars.setBackground(Ui.round(this, Ui.card(this), 14));
        Ui.pad(stars, 14, 14, 14, 14);
        stars.addView(toolHeader(Icons.STAR, "⭐ Stars"));
        starsT = Ui.tvBold(this, "Your Stars: 0", 22, Ui.ORANGE);
        starsT.setPadding(0, Ui.dp(this, 8), 0, 0);
        stars.addView(starsT);
        TextView starsSub = Ui.tv(this,
                "Fans send Stars on your reels and lives. 100 Stars = redeemable earnings.",
                12, Ui.sub(this));
        starsSub.setPadding(0, Ui.dp(this, 4), 0, 0);
        stars.addView(starsSub);
        body.addView(stars);
        body.addView(sp(12));

        // ---------- Subscriptions ----------
        LinearLayout subs = Ui.col(this);
        subs.setBackground(Ui.round(this, Ui.card(this), 14));
        Ui.pad(subs, 14, 12, 14, 14);
        subs.addView(toolHeader(Icons.NOTIFICATIONS, "Fan Subscriptions"));
        LinearLayout sr = Ui.row(this);
        sr.setPadding(0, Ui.dp(this, 8), 0, 0);
        subsT = Ui.tvBold(this, "Allow fans to subscribe to your exclusive posts", 13, Ui.txt(this));
        subsT.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        sr.addView(subsT);
        subsSwitch = new Switch(this);
        subsSwitch.setOnCheckedChangeListener(this::toggleSubs);
        sr.addView(subsSwitch);
        subs.addView(sr);
        TextView priceRow = Ui.tvBold(this, "💵 Set monthly price", 14, Ui.BLUE);
        priceRow.setPadding(0, Ui.dp(this, 8), 0, 0);
        priceRow.setOnClickListener(v -> priceDialog());
        subs.addView(priceRow);
        subsCountT = Ui.tv(this, "Subscribers: …", 12, Ui.sub(this));
        subsCountT.setPadding(0, Ui.dp(this, 6), 0, 0);
        subs.addView(subsCountT);
        body.addView(subs);

        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private LinearLayout toolHeader(int icon, String title) {
        LinearLayout r = Ui.row(this);
        r.addView(Icons.chipIcon(this, icon, Icons.BLUE));
        TextView t = Ui.tvBold(this, title, 16, Ui.txt(this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.leftMargin = Ui.dp(this, 12);
        r.addView(t, lp);
        return r;
    }

    private View sp(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, Ui.dp(this, h)));
        return v;
    }

    @Override
    protected void onStart() {
        super.onStart();
        String me = Fb.uid();
        if (me == null) { finish(); return; }
        meReg = Fb.db().collection("users").document(me)
                .addSnapshotListener((d, err) -> {
                    if (d == null || !d.exists()) return;
                    followersCount = Format.intOf(d.getData(), "followersCountCache");
                    Object fol = d.get("followers");
                    if (fol instanceof java.util.List) followersCount = ((java.util.List<?>) fol).size();
                    long stars = Format.intOf(d.getData(), "stars");
                    starsT.setText("Your Stars: " + Format.count(stars) + " ⭐  (≈ Rs "
                            + Format.count(stars * com.friendbook.core.Admin.STAR_VALUE_PKR) + ")");
                    boolean subsOn = Boolean.TRUE.equals(d.getBoolean("subscriptionsEnabled"));
                    subsSwitch.setOnCheckedChangeListener(null);
                    subsSwitch.setChecked(subsOn);
                    subsSwitch.setOnCheckedChangeListener(this::toggleSubs);
                    Object price = d.get("subscriptionPrice");
                    int usd = 5;
                    if (price instanceof Number) usd = ((Number) price).intValue();
                    else if (price != null) { try { usd = Integer.parseInt(String.valueOf(price)); } catch (Exception ignored) { } }
                    subsT.setText("Allow subscriptions — $" + usd + "/month (you keep 70%)");
                    checkEligibility(followersCount, -1);
                });
        // real reels count for eligibility part 2
        Fb.db().collection("reels").whereEqualTo("authorId", me).limit(50).get()
                .addOnSuccessListener(snap -> checkEligibility(followersCount, snap.size()));
        // live subscribers count
        if (subsCountReg != null) subsCountReg.remove();
        subsCountReg = Fb.db().collection("subscriptions")
                .whereEqualTo("creatorId", me)
                .whereEqualTo("status", "active")
                .addSnapshotListener((snap, err) ->
                        subsCountT.setText("Subscribers: " + (snap == null ? 0 : snap.size())
                                + " active  ·  70% yours / 30% friendbook"));
    }

    private void checkEligibility(int followers, int reels) {
        if (reels < 0) return; // wait for reels query
        boolean ok1 = followers >= FOLLOWERS_FOR_ADS;
        boolean ok2 = reels >= REELS_FOR_ADS;
        boolean eligible = ok1 && ok2;
        adsStatus.setText(eligible ? "✓ Eligible for In-Stream Ads!" : "Not eligible yet");
        adsStatus.setTextColor(eligible ? Ui.GREEN : Ui.txt(this));
        int pct = Math.min(100, (int) ((followers * 50f / FOLLOWERS_FOR_ADS) + (reels * 50f / REELS_FOR_ADS)));
        adsBar.setProgress(pct);
        adsProgressT.setText(
                (ok1 ? "✓" : "•") + " " + followers + "/" + FOLLOWERS_FOR_ADS + " followers\n"
                        + (ok2 ? "✓" : "•") + " " + reels + "/" + REELS_FOR_ADS + " reels posted");
    }

    private void toggleSubs(CompoundButton btn, boolean on) {
        Fb.db().collection("users").document(Fb.uid())
                .set(Fb.map("subscriptionsEnabled", on), SetOptions.merge());
        Ui.toast(this, on ? "Subscriptions ON — fans can support you!" : "Subscriptions off");
    }

    private void priceDialog() {
        // Facebook-style tiers — creator keeps 70%, friendbook keeps 30%
        String[] tiers = {"$2 / month", "$5 / month", "$10 / month"};
        int[] values = {2, 5, 10};
        Ui.choose(this, "Monthly price — you keep 70%", tiers, w -> {
            int v = values[w];
            Fb.db().collection("users").document(Fb.uid())
                    .set(Fb.map("subscriptionPrice", v), SetOptions.merge());
            subsT.setText("Allow subscriptions — $" + v + "/month (you keep 70%)");
            Ui.toast(this, "Price saved ✓");
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (meReg != null) meReg.remove();
        if (subsCountReg != null) { subsCountReg.remove(); subsCountReg = null; }
    }
}
