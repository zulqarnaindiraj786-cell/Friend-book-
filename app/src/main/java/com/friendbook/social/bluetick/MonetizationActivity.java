package com.friendbook.social.bluetick;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Icons;
import com.friendbook.social.core.Ui;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

/**
 * ⭐ Creator Hub & Community Milestones (100% Free — Google Play Compliant):
 *  • Creator Program Eligibility (computed from real followers + reels counts)
 *  • Community Stars recognition balance (`users.stars`)
 *  • Free VIP Fan Club membership toggle on profile
 */
public class MonetizationActivity extends Activity {

    private TextView adsStatus, adsProgressT, starsT, subsT, subsCountT;
    private ProgressBar adsBar;
    private Switch subsSwitch;
    private ListenerRegistration subsCountReg;
    private ListenerRegistration meReg;
    private int followersCount = 0;

    private static final int FOLLOWERS_FOR_CREATOR = 100;
    private static final int REELS_FOR_CREATOR = 5;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "Creator Hub & Milestones"));

        ScrollView sv = new ScrollView(this);
        LinearLayout body = Ui.col(this);
        Ui.pad(body, 12, 10, 12, 24);

        // ---------- Creator Milestone ----------
        LinearLayout ads = Ui.col(this);
        ads.setBackground(Ui.round(this, Ui.card(this), 14));
        Ui.pad(ads, 14, 14, 14, 14);
        ads.addView(toolHeader(Icons.VIDEO, "Creator Partner Eligibility"));
        adsStatus = Ui.tvBold(this, "Checking milestones…", 15, Ui.txt(this));
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

        // ---------- Community Stars ----------
        LinearLayout stars = Ui.col(this);
        stars.setBackground(Ui.round(this, Ui.card(this), 14));
        Ui.pad(stars, 14, 14, 14, 14);
        stars.addView(toolHeader(Icons.STAR, "⭐ Community Stars"));
        starsT = Ui.tvBold(this, "Your Stars: 0 ⭐", 22, Ui.ORANGE);
        starsT.setPadding(0, Ui.dp(this, 8), 0, 0);
        stars.addView(starsT);
        TextView starsSub = Ui.tv(this,
                "Earn free Community Stars from daily streaks, referrals, reels & live broadcasts to climb the Leaderboard!",
                12, Ui.sub(this));
        starsSub.setPadding(0, Ui.dp(this, 4), 0, 0);
        stars.addView(starsSub);
        body.addView(stars);
        body.addView(sp(12));

        // ---------- Free VIP Fan Club ----------
        LinearLayout subs = Ui.col(this);
        subs.setBackground(Ui.round(this, Ui.card(this), 14));
        Ui.pad(subs, 14, 12, 14, 14);
        subs.addView(toolHeader(Icons.NOTIFICATIONS, "VIP Fan Club (Free)"));
        LinearLayout sr = Ui.row(this);
        sr.setPadding(0, Ui.dp(this, 8), 0, 0);
        subsT = Ui.tvBold(this, "Allow followers to join your Free VIP Fan Club", 13, Ui.txt(this));
        subsT.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        sr.addView(subsT);
        subsSwitch = new Switch(this);
        subsSwitch.setOnCheckedChangeListener(this::toggleSubs);
        sr.addView(subsSwitch);
        subs.addView(sr);
        subsCountT = Ui.tv(this, "VIP Fans: …", 12, Ui.sub(this));
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
        // Shares the session-wide users/{me} listener.
        Fb.observeMe(meObserver);
        Fb.db().collection("reels").whereEqualTo("authorId", me).limit(50).get()
                .addOnSuccessListener(snap -> checkEligibility(followersCount, snap.size()));
        if (subsCountReg != null) subsCountReg.remove();
        subsCountReg=com.friendbook.social.core.CountReads.once(Fb.db().collection("subscriptions")
                .whereEqualTo("creatorId",me).whereEqualTo("status","active"),total->
                    subsCountT.setText("Active VIP Fan Club members: "+total));
    }

    private final Fb.MeObserver meObserver = d -> {
        // The shared listener delivers the document; render from it, or from the cached profile
        // if no snapshot is available yet so the screen is never blank.
        java.util.Map<String, Object> map = d != null && d.exists()
                ? d.getData()
                : (Fb.me != null ? userToMap(Fb.me) : null);
        if (map == null) return;
        followersCount = Format.intOf(map, "followersCountCache");
        Object fol = map.get("followers");
        if (fol instanceof java.util.List) followersCount = ((java.util.List<?>) fol).size();
        long stars = Format.intOf(map, "stars");
        starsT.setText("Your Stars: " + Format.count(stars) + " \u2B50");
        boolean subsOn = Boolean.TRUE.equals(map.get("subscriptionsEnabled"));
        subsSwitch.setOnCheckedChangeListener(null);
        subsSwitch.setChecked(subsOn);
        subsSwitch.setOnCheckedChangeListener(this::toggleSubs);
        checkEligibility(followersCount, -1);
    };

    /** Minimal map view of a cached profile, for rendering before the first snapshot. */
    private static java.util.Map<String, Object> userToMap(Models.User u) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("stars", u.stars);
        if (u.followers != null) m.put("followers", u.followers);
        return m;
    }
    private void checkEligibility(int followers, int reels) {
        if (reels < 0) return;
        boolean ok1 = followers >= FOLLOWERS_FOR_CREATOR;
        boolean ok2 = reels >= REELS_FOR_CREATOR;
        boolean eligible = ok1 && ok2;
        adsStatus.setText(eligible ? "✓ Creator Partner Milestone Reached!" : "Keep growing your community");
        adsStatus.setTextColor(eligible ? Ui.GREEN : Ui.txt(this));
        int pct = Math.min(100, (int) ((followers * 50f / FOLLOWERS_FOR_CREATOR) + (reels * 50f / REELS_FOR_CREATOR)));
        adsBar.setProgress(pct);
        adsProgressT.setText(
                (ok1 ? "✓" : "•") + " " + followers + "/" + FOLLOWERS_FOR_CREATOR + " followers\n"
                        + (ok2 ? "✓" : "•") + " " + reels + "/" + REELS_FOR_CREATOR + " reels shared");
    }

    private void toggleSubs(CompoundButton btn, boolean on) {
        Fb.db().collection("users").document(Fb.uid())
                .set(Fb.map("subscriptionsEnabled", on), SetOptions.merge());
        Ui.toast(this, on ? "Free VIP Fan Club ON ⭐" : "VIP Fan Club turned off");
    }

    @Override
    protected void onStop() {
        Fb.removeMeObserver(meObserver);
        super.onStop();
        if (meReg != null) { meReg.remove(); meReg = null; }
        if (subsCountReg != null) { subsCountReg.remove(); subsCountReg = null; }
    }
}
