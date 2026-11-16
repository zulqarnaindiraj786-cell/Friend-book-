package com.friendbook.social.admin;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.friendbook.social.core.Admin;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Icons;
import com.friendbook.social.core.Notify;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.friendbook.social.post.PostDetailActivity;
import com.friendbook.social.profile.ProfileActivity;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Strictly-scoped Global Moderator Desk!
 * Accessible ONLY to appointed Moderators (or Admins).
 * Provides ONLY two actions:
 *   1. Delete rule-breaking posts (non-admin authors)
 *   2. Ban / Unban non-admin users
 * Zero access to Admin Panel, ticks, stars, broadcasts, or payments!
 */
public class ModeratorDeskActivity extends Activity {

    private int activeTab = 0; // 0 = Reported & Recent Posts, 1 = Ban / Unban Users
    private TextView tabPosts, tabUsers;
    private EditText searchBox;
    private LinearLayout listCol;
    private String query = "";

    private final List<Models.Post> posts = new ArrayList<>();
    private final List<Models.User> users = new ArrayList<>();
    private ListenerRegistration postsReg, usersReg;

    @Override
    protected void onCreate(@Nullable Bundle b) {
        super.onCreate(b);
        if (!Admin.canModerate()) {
            Ui.toast(this, "Moderator access required");
            finish();
            return;
        }

        Ui.statusBarBlue(this);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "🛡 Moderator Desk"));
        root.addView(Ui.divider(this));

        LinearLayout banner = Ui.col(this);
        banner.setBackgroundColor(Ui.card(this));
        Ui.pad(banner, 14, 10, 14, 10);
        banner.addView(Ui.tvBold(this, "🛡 Community Safety Moderator", 14, Ui.BLUE));
        banner.addView(Ui.tv(this,
                "Scope: Delete rule-breaking posts & ban/unban abusive accounts.", 12, Ui.sub(this)));
        root.addView(banner);

        LinearLayout tabs = Ui.row(this);
        tabs.setBackgroundColor(Ui.card(this));
        Ui.pad(tabs, 12, 8, 12, 8);
        tabPosts = Ui.roundChip(this, "🗑 Moderate Posts", Ui.BLUE, 0xFFFFFFFF, 13);
        tabUsers = Ui.roundChip(this, "🚫 Ban / Unban Users", Ui.inputBg(this), Ui.txt(this), 13);
        Ui.pad(tabPosts, 14, 8, 14, 8);
        Ui.pad(tabUsers, 14, 8, 14, 8);
        tabPosts.setOnClickListener(v -> switchTab(0));
        tabUsers.setOnClickListener(v -> switchTab(1));
        LinearLayout.LayoutParams t1 = new LinearLayout.LayoutParams(0, -2, 1);
        t1.rightMargin = Ui.dp(this, 6);
        tabPosts.setGravity(Gravity.CENTER);
        tabUsers.setGravity(Gravity.CENTER);
        tabs.addView(tabPosts, t1);
        tabs.addView(tabUsers, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(tabs);

        LinearLayout searchWrap = Ui.col(this);
        searchWrap.setBackgroundColor(Ui.card(this));
        Ui.pad(searchWrap, 12, 4, 12, 10);
        searchBox = Ui.input(this, "🔍 Search posts or users…");
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            @Override public void onTextChanged(CharSequence s, int st, int bf, int c) {
                query = s.toString().trim().toLowerCase(Locale.US);
                render();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        searchWrap.addView(searchBox, new LinearLayout.LayoutParams(-1, -2));
        root.addView(searchWrap);
        root.addView(Ui.divider(this));

        ScrollView sv = new ScrollView(this);
        listCol = Ui.col(this);
        Ui.pad(listCol, 12, 10, 12, 24);
        sv.addView(listCol);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        com.friendbook.social.core.D1Snap.read(
                com.friendbook.social.core.DocQuery.of("posts")
                        .orderBy("createdAt", com.friendbook.social.core.DocQuery.DESC)
                        .limit(100).build(), snap -> {
                    posts.clear();
                    for (com.friendbook.social.core.Doc d : snap.getDocuments()) {
                        posts.add(Models.Post.fromMap(d.getId(), d.getData()));
                    }
                    render();
                });
        com.friendbook.social.core.D1Snap.read(com.friendbook.social.core.DocQuery.of("users").limit(250).build(),snap->{
                    users.clear();
                    if (snap != null) {
                        for (com.friendbook.social.core.Doc d : snap.getDocuments()) {
                            users.add(Models.User.fromMap(d.getId(), d.getData()));
                        }
                    }
                    render();
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (postsReg != null) postsReg.remove();
        if (usersReg != null) usersReg.remove();
    }

    private void switchTab(int idx) {
        activeTab = idx;
        tabPosts.setBackground(Ui.round(this, idx == 0 ? Ui.BLUE : Ui.inputBg(this), 20));
        tabPosts.setTextColor(idx == 0 ? 0xFFFFFFFF : Ui.txt(this));
        tabUsers.setBackground(Ui.round(this, idx == 1 ? Ui.BLUE : Ui.inputBg(this), 20));
        tabUsers.setTextColor(idx == 1 ? 0xFFFFFFFF : Ui.txt(this));
        render();
    }

    private boolean isProtectedUid(String uid) {
        for (Models.User u : users) {
            if (uid != null && uid.equals(u.uid)) return Admin.isProtectedStaff(u);
        }
        return false;
    }

    private void render() {
        if (listCol == null) return;
        listCol.removeAllViews();
        if (activeTab == 0) {
            for (Models.Post p : posts) {
                if (isProtectedUid(p.authorId)) continue;
                if (!query.isEmpty()) {
                    boolean m = (p.content != null && p.content.toLowerCase(Locale.US).contains(query))
                            || (p.authorName != null && p.authorName.toLowerCase(Locale.US).contains(query));
                    if (!m) continue;
                }
                listCol.addView(postCard(p));
            }
        } else {
            for (Models.User u : users) {
                if (Admin.isProtectedStaff(u) || (Fb.uid() != null && Fb.uid().equals(u.uid))) continue;
                if (!query.isEmpty()) {
                    boolean m = u.displayName.toLowerCase(Locale.US).contains(query);
                    if (!m) continue;
                }
                listCol.addView(userCard(u));
            }
        }
    }

    private View postCard(Models.Post p) {
        LinearLayout card = Ui.col(this);
        card.setBackground(Ui.round(this, Ui.card(this), 12));
        Ui.pad(card, 12, 10, 12, 10);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(this, 8);
        card.setLayoutParams(lp);

        LinearLayout head = Ui.row(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView av = new ImageView(this);
        Ui.avatar(this, av, p.authorPhoto);
        int s = Ui.dp(this, 36);
        head.addView(av, new LinearLayout.LayoutParams(s, s));

        LinearLayout mid = Ui.col(this);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1);
        mp.leftMargin = Ui.dp(this, 10);
        mid.addView(Ui.tvBold(this, p.authorName, 14, Ui.txt(this)));
        mid.addView(Ui.tv(this, Format.ago(p.createdAt) + " · " + p.mediaType, 11, Ui.sub(this)));
        head.addView(mid, mp);

        TextView delBtn = Ui.btnOutline(this, "🗑 Delete Post", Ui.RED);
        Ui.pad(delBtn, 10, 6, 10, 6);
        delBtn.setOnClickListener(v -> Ui.confirm(this, "Delete post by " + p.authorName + "?",
                "This permanently deletes the violating post.", "Delete", () ->
                        Fb.db().collection("posts").document(p.id).delete()
                                .addOnSuccessListener(x -> Ui.toast(this, "Post deleted 🗑"))
                                .addOnFailureListener(e -> Ui.toast(this, e.getMessage()))));
        head.addView(delBtn);
        card.addView(head);

        String body = (p.content == null || p.content.isEmpty()) ? "(Media post)" : p.content;
        TextView txt = Ui.tv(this, body, 13, Ui.txt(this));
        txt.setMaxLines(4);
        txt.setPadding(0, Ui.dp(this, 8), 0, 0);
        card.addView(txt);
        card.setOnClickListener(v -> startActivity(
                new Intent(this, PostDetailActivity.class).putExtra("post_id", p.id)));
        return card;
    }

    private View userCard(Models.User u) {
        LinearLayout row = Ui.row(this);
        row.setBackground(Ui.round(this, Ui.card(this), 12));
        row.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(row, 12, 10, 12, 10);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(this, 6);
        row.setLayoutParams(lp);

        ImageView av = new ImageView(this);
        Ui.avatar(this, av, u.photoUrl);
        int s = Ui.dp(this, 42);
        row.addView(av, new LinearLayout.LayoutParams(s, s));

        LinearLayout mid = Ui.col(this);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1);
        mp.leftMargin = Ui.dp(this, 10);
        LinearLayout nr = Ui.row(this);
        nr.setGravity(Gravity.CENTER_VERTICAL);
        nr.addView(Ui.tvBold(this, u.displayName, 14, Ui.txt(this)));
        ImageView tk = new ImageView(this);
        Icons.setTickBadge(tk, u.tickLevel, false);
        int ts = Ui.dp(this, 14);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ts, ts);
        tlp.leftMargin = Ui.dp(this, 4);
        nr.addView(tk, tlp);
        if (u.banned) {
            TextView b = Ui.roundChip(this, "BANNED", Ui.RED, 0xFFFFFFFF, 9);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
            blp.leftMargin = Ui.dp(this, 6);
            nr.addView(b, blp);
        }
        if (u.isSuspendedNow()) {
            TextView sp = Ui.roundChip(this, "⏳ SUSPENDED", 0xFFD97706, 0xFFFFFFFF, 9);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
            slp.leftMargin = Ui.dp(this, 6);
            nr.addView(sp, slp);
        }
        mid.addView(nr);
        String subStatus = u.banned ? "Banned account"
                : u.isSuspendedNow() ? ("Suspended (" + u.suspensionStatusLabel() + ")")
                : "Active user";
        mid.addView(Ui.tv(this, subStatus, 11, Ui.sub(this)));
        row.addView(mid, mp);

        TextView suspendBtn = u.isSuspendedNow()
                ? Ui.btnOutline(this, "✅ Unsuspend", Ui.GREEN)
                : Ui.btnOutline(this, "⏳ Suspend", 0xFFD97706);
        Ui.pad(suspendBtn, 10, 6, 10, 6);
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(-2, -2);
        sblp.rightMargin = Ui.dp(this, 6);
        suspendBtn.setOnClickListener(v -> Admin.showSuspendUserDialog(this, u, this::render));
        row.addView(suspendBtn, sblp);

        TextView banBtn = u.banned
                ? Ui.btnOutline(this, "✓ Unban", Ui.GREEN)
                : Ui.btnOutline(this, "🚫 Ban", Ui.RED);
        Ui.pad(banBtn, 12, 6, 12, 6);
        banBtn.setOnClickListener(v -> {
            boolean nextBan = !u.banned;
            Ui.confirm(this, (nextBan ? "🚫 Ban " : "✓ Unban ") + u.displayName + "?",
                    nextBan ? "This user will be blocked from using friendbook." : "Restore account access?",
                    nextBan ? "Ban" : "Unban", () ->
                            Fb.db().collection("users").document(u.uid)
                                    .set(Fb.map("banned", nextBan), SetOptions.merge())
                                    .addOnSuccessListener(x -> {
                                        if (nextBan) {
                                            Notify.sendOfficialTeamMessage(u.uid, "ban",
                                                    "Your account was suspended by a friendbook Moderator.", false);
                                        }
                                        Ui.toast(this, nextBan ? "Banned 🚫" : "Unbanned ✓");
                                    })
                                    .addOnFailureListener(e -> Ui.toast(this, e.getMessage())));
        });
        row.addView(banBtn);

        row.setOnClickListener(v -> startActivity(
                new Intent(this, ProfileActivity.class).putExtra("uid", u.uid)));
        return row;
    }
}
