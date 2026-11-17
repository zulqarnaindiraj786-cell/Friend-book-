package com.friendbook.social.profile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.friendbook.social.bluetick.BlueTickActivity;
import com.friendbook.social.core.Admin;
import com.friendbook.social.core.FastCache;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Icons;
import com.friendbook.social.core.Ui;
import com.friendbook.social.live.LiveActivity;
import com.friendbook.social.models.Models;
import com.friendbook.social.post.CreatePostActivity;
import com.friendbook.social.reels.AddReelActivity;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;

/**
 * 📊 friendbook Pro Creator Dashboard (Professional Mode Insights & Monetization Hub):
 * Live post performance, total likes/reactions, comments, followers, Creator Level progress,
 * Professional Mode switch, Stars earnings, Verification Tick & Profile Boost!
 */
public final class CreatorDashboardDialog {

    private CreatorDashboardDialog() { }

    public static void show(final Activity a, final Runnable onUpdated) {
        if (a == null || a.isFinishing()) return;
        final String me = Fb.uid();
        if (me == null) return;
        final Models.User u = Fb.me != null ? Fb.me : FastCache.getMe();

        ScrollView sv = new ScrollView(a);
        LinearLayout root = Ui.col(a);
        root.setBackgroundColor(Ui.bg(a));
        sv.addView(root);

        // Hero Blue Banner
        LinearLayout hero = Ui.col(a);
        hero.setBackground(Ui.gradient(0xFF1877F2, 0xFF0A4EB2, 0));
        Ui.pad(hero, 18, 18, 18, 18);

        LinearLayout heroTop = Ui.row(a);
        heroTop.setGravity(Gravity.CENTER_VERTICAL);
        ImageView av = new ImageView(a);
        int avs = Ui.dp(a, 48);
        heroTop.addView(av, new LinearLayout.LayoutParams(avs, avs));
        Ui.avatar(a, av, Fb.myPhoto());

        LinearLayout heroMid = Ui.col(a);
        LinearLayout.LayoutParams hmp = new LinearLayout.LayoutParams(0, -2, 1);
        hmp.leftMargin = Ui.dp(a, 12);
        heroMid.addView(Ui.tvBold(a, "📊 Creator Dashboard", 19, Color.WHITE));
        final TextView levelSub = Ui.tv(a,
                (u != null && u.professionalMode ? "🎬 Digital creator · Professional Mode ON"
                        : "Personal Profile · Turn on Professional Mode below"),
                12, 0xFFDCEBFF);
        heroMid.addView(levelSub);
        heroTop.addView(heroMid, hmp);
        hero.addView(heroTop);
        root.addView(hero);

        // Performance Insights Section
        LinearLayout perfCard = Ui.col(a);
        perfCard.setBackgroundColor(Ui.card(a));
        Ui.pad(perfCard, 14, 14, 14, 14);
        perfCard.addView(Ui.tvBold(a, "Performance overview", 16, Ui.txt(a)));
        perfCard.addView(Ui.tv(a, "Live insights calculated from your posts & audience", 12, Ui.sub(a)));

        LinearLayout row1 = Ui.row(a);
        row1.setPadding(0, Ui.dp(a, 10), 0, 0);
        final TextView vReach = statValueView(a);
        final TextView vLikes = statValueView(a);
        row1.addView(statBox(a, "Post reach", vReach, "Est. impressions"), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams r1Right = new LinearLayout.LayoutParams(0, -2, 1);
        r1Right.leftMargin = Ui.dp(a, 8);
        row1.addView(statBox(a, "Reactions", vLikes, "Total 👍❤️😂"), r1Right);
        perfCard.addView(row1);

        LinearLayout row2 = Ui.row(a);
        row2.setPadding(0, Ui.dp(a, 8), 0, 0);
        final TextView vComments = statValueView(a);
        final TextView vFollowers = statValueView(a);
        int followerCount = u != null && u.followers != null ? u.followers.size() : 0;
        int friendCount = u != null ? u.friendsCount : 0;
        vFollowers.setText(Format.count(followerCount + friendCount));
        row2.addView(statBox(a, "Engagement", vComments, "Comments & shares"), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams r2Right = new LinearLayout.LayoutParams(0, -2, 1);
        r2Right.leftMargin = Ui.dp(a, 8);
        row2.addView(statBox(a, "Audience", vFollowers, followerCount + " followers · " + friendCount + " friends"), r2Right);
        perfCard.addView(row2);
        root.addView(perfCard);

        // Creator Level Progress Card
        LinearLayout lvlCard = Ui.col(a);
        lvlCard.setBackgroundColor(Ui.card(a));
        LinearLayout.LayoutParams lvlLp = new LinearLayout.LayoutParams(-1, -2);
        lvlLp.topMargin = Ui.dp(a, 8);
        Ui.pad(lvlCard, 14, 12, 14, 12);
        final TextView lvlTitle = Ui.tvBold(a, "🏆 Creator Level 1 · Rising Star", 14, Ui.BLUE);
        final TextView lvlDesc = Ui.tv(a, "Publish posts & reels to unlock higher reach!", 12, Ui.sub(a));
        lvlCard.addView(lvlTitle);
        lvlCard.addView(lvlDesc);

        // Professional Mode Toggle
        LinearLayout proRow = Ui.row(a);
        proRow.setGravity(Gravity.CENTER_VERTICAL);
        proRow.setPadding(0, Ui.dp(a, 10), 0, 0);
        LinearLayout proTxt = Ui.col(a);
        proTxt.addView(Ui.tvBold(a, "🎬 Professional Mode (Digital creator)", 14, Ui.txt(a)));
        proTxt.addView(Ui.tv(a, "Shows Digital creator badge & Creator Dashboard on your profile", 12, Ui.sub(a)));
        proRow.addView(proTxt, new LinearLayout.LayoutParams(0, -2, 1));

        Switch proSw = new Switch(a);
        proSw.setChecked(u == null || u.professionalMode);
        proSw.setOnCheckedChangeListener((btn, checked) -> {
            if (u != null) u.professionalMode = checked;
            if (Fb.me != null) Fb.me.professionalMode = checked;
            levelSub.setText(checked
                    ? "🎬 Digital creator · Professional Mode ON"
                    : "Personal Profile · Professional Mode OFF");
            Map<String, Object> up = new HashMap<>();
            up.put("professionalMode", checked);
            com.friendbook.social.core.DataRouter.mirrorPut("users", me, up);
            com.friendbook.social.core.DataRouter.batch().set("users", me, up).commit();
            if (onUpdated != null) onUpdated.run();
        });
        proRow.addView(proSw);
        lvlCard.addView(proRow);
        root.addView(lvlCard, lvlLp);

        // Monetization & Creator Tools Card
        LinearLayout toolsCard = Ui.col(a);
        toolsCard.setBackgroundColor(Ui.card(a));
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(-1, -2);
        tLp.topMargin = Ui.dp(a, 8);
        Ui.pad(toolsCard, 14, 12, 14, 14);
        toolsCard.addView(Ui.tvBold(a, "Creator tools & monetization", 15, Ui.txt(a)));

        final AlertDialog dlg = new AlertDialog.Builder(a)
                .setView(sv)
                .setNegativeButton("Close", null)
                .create();

        int stars = u != null ? u.stars : 0;
        addToolRow(a, toolsCard, "⭐ Stars & Creator Balance",
                stars + " Stars earned · Enable fan subscriptions", () -> {
                    dlg.dismiss();
                    a.startActivity(new Intent(a, EditProfileActivity.class));
                });
        addToolRow(a, toolsCard, "✓ Verification Badges (Blue · Gold · Diamond)",
                u != null && u.tickRank() > 0 ? "Active: " + Admin.tickLabel(u.tickLevel) : "Apply for your verified tick",
                () -> {
                    dlg.dismiss();
                    a.startActivity(new Intent(a, BlueTickActivity.class));
                });
        addToolRow(a, toolsCard, "🎬 Upload a Reel",
                "Reach new followers across Reels & Watch", () -> {
                    dlg.dismiss();
                    a.startActivity(new Intent(a, AddReelActivity.class));
                });
        addToolRow(a, toolsCard, "🔴 Go Live now",
                "Broadcast real live video & voice, invite Co-Host & earn Stars", () -> {
                    dlg.dismiss();
                    Intent li = new Intent(a, LiveActivity.class);
                    li.putExtra("host", true);
                    a.startActivity(li);
                });
        addToolRow(a, toolsCard, "📝 Publish a new Post",
                "Share photos, polls, feelings or check-ins", () -> {
                    dlg.dismiss();
                    a.startActivity(new Intent(a, CreatePostActivity.class));
                });
        root.addView(toolsCard, tLp);

        dlg.show();

        // Live query of all user's posts to compute exact reactions, comments, shares & reach!
        Fb.db().collection("posts").whereEqualTo("authorId", me).limit(150).get()
                .addOnSuccessListener(snap -> {
                    if (a.isFinishing() || snap == null) return;
                    int posts = snap.size();
                    int totalLikes = 0;
                    int totalComments = 0;
                    int totalShares = 0;
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        Models.Post p = Models.Post.fromDoc(d);
                        totalLikes += FastCache.getReactionTotal(p.id, p.likes);
                        totalComments += Math.max(0, p.comments);
                        totalShares += Math.max(0, p.shares);
                    }
                    int estReach = posts * 18 + totalLikes * 9 + totalComments * 14 + (followerCount + friendCount) * 5;
                    vReach.setText(Format.count(estReach));
                    vLikes.setText(Format.count(totalLikes));
                    vComments.setText(Format.count(totalComments + totalShares));

                    int score = posts + totalLikes + totalComments;
                    if (score >= 50) {
                        lvlTitle.setText("💎 Creator Level 3 · Pro Creator (" + posts + " posts)");
                        lvlDesc.setText("Top engagement tier unlocked! Keep posting daily.");
                    } else if (score >= 12) {
                        lvlTitle.setText("🔥 Creator Level 2 · Growing Creator (" + posts + " posts)");
                        lvlDesc.setText((50 - score) + " more engagements to reach Level 3 Pro!");
                    } else {
                        lvlTitle.setText("🏆 Creator Level 1 · Starter (" + posts + " posts)");
                        lvlDesc.setText((12 - score) + " more engagements to unlock Level 2!");
                    }
                });
    }

    private static TextView statValueView(Activity a) {
        return Ui.tvBold(a, "…", 21, Ui.txt(a));
    }

    private static LinearLayout statBox(Activity a, String title, TextView valueTv, String sub) {
        LinearLayout box = Ui.col(a);
        box.setBackground(Ui.roundStroke(a, Ui.inputBg(a), 10, Ui.div(a), 1));
        Ui.pad(box, 12, 10, 12, 10);
        box.addView(Ui.tv(a, title, 12, Ui.sub(a)));
        valueTv.setPadding(0, Ui.dp(a, 2), 0, Ui.dp(a, 2));
        box.addView(valueTv);
        box.addView(Ui.tv(a, sub, 11, Ui.GREEN));
        return box;
    }

    private static void addToolRow(Activity a, LinearLayout parent, String title, String sub, Runnable click) {
        LinearLayout r = Ui.row(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(r, 4, 10, 4, 10);
        LinearLayout col = Ui.col(a);
        col.addView(Ui.tvBold(a, title, 14, Ui.txt(a)));
        col.addView(Ui.tv(a, sub, 12, Ui.sub(a)));
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(Ui.tvBold(a, "›", 20, Ui.sub(a)));
        r.setOnClickListener(v -> click.run());
        parent.addView(r);
    }
}
