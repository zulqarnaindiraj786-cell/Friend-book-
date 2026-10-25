package com.friendbook.groups;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.core.FastCache;
import com.friendbook.core.Fb;
import com.friendbook.core.Ui;
import com.friendbook.models.Models;
import com.friendbook.post.CreatePostActivity;
import com.friendbook.post.FeedAdapter;
import com.friendbook.post.PostDetailActivity;
import com.friendbook.profile.ProfileActivity;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Group page: header + privacy + rules + QR invite + search + 0ms optimistic join/leave + realtime feed. */
public class GroupDetailActivity extends Activity implements FeedAdapter.Host {

    private String groupId;
    private Models.Group group;
    private FeedAdapter adapter;
    private ListenerRegistration groupReg, postsReg;
    private TextView title, sub, joinBtn, composer;
    private final List<Models.Post> allPosts = new ArrayList<>();
    private String postSearch = "";

    private final FastCache.ReactionObserver reactionObserver = (pid, mine, total) -> {
        if (adapter != null) adapter.onFastReactionUpdate(pid, mine, total);
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        groupId = getIntent().getStringExtra("group_id");
        if (groupId == null) { finish(); return; }

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "Group"));

        LinearLayout head = Ui.col(this);
        head.setBackgroundColor(Ui.card(this));
        Ui.pad(head, 14, 12, 14, 10);
        title = Ui.tvBold(this, getIntent().getStringExtra("name") != null
                ? getIntent().getStringExtra("name") : "Group", 20, Ui.txt(this));
        head.addView(title);
        sub = Ui.tv(this, "", 13, Ui.sub(this));
        sub.setPadding(0, Ui.dp(this, 4), 0, 0);
        head.addView(sub);

        joinBtn = Ui.btn(this, "Join group");
        LinearLayout.LayoutParams jp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 38));
        jp.topMargin = Ui.dp(this, 8);
        head.addView(joinBtn, jp);
        joinBtn.setOnClickListener(v -> toggleJoin());

        // Action pills: Invite link · QR code · Rules · Search
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout actions = Ui.row(this);
        actions.setPadding(0, Ui.dp(this, 8), 0, 0);

        TextView invite = actionPill("🔗 Invite Link", () -> {
            Ui.copy(this, "https://friendbook.app/group?id=" + groupId);
            Ui.toast(this, "Group invite link copied 🔗");
        });
        TextView qr = actionPill("📱 Group QR", () ->
                Ui.showQrDialog(this, group == null ? "Group" : group.name,
                        "https://friendbook.app/group?id=" + groupId));
        TextView rulesBtn = actionPill("📜 Group Rules", this::showRulesDialog);
        actions.addView(invite);
        actions.addView(qr);
        actions.addView(rulesBtn);
        hsv.addView(actions);
        head.addView(hsv);

        EditText searchIn = Ui.input(this, "🔎 Search posts in this group…");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 36));
        sp.topMargin = Ui.dp(this, 8);
        searchIn.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {
                postSearch = s.toString().trim().toLowerCase();
                filterPosts();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        head.addView(searchIn, sp);
        root.addView(head);

        composer = Ui.tvBold(this, "＋ Write something in this group…", 13, Ui.sub(this));
        composer.setBackground(Ui.round(this, Ui.card(this), 20));
        Ui.pad(composer, 14, 10, 14, 10);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), 0);
        composer.setLayoutParams(cp);
        composer.setOnClickListener(v -> {
            Intent i = new Intent(this, CreatePostActivity.class);
            i.putExtra("group_id", groupId);
            startActivity(i);
        });
        root.addView(composer);

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setItemAnimator(null);
        rv.setItemViewCacheSize(18);
        adapter = new FeedAdapter(this, this);
        adapter.showHeader = false;
        adapter.showFooter = false;
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private TextView actionPill(String label, Runnable r) {
        TextView t = Ui.roundChip(this, label, Ui.inputBg(this), Ui.BLUE, 12);
        Ui.pad(t, 10, 6, 10, 6);
        t.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = Ui.dp(this, 8);
        t.setLayoutParams(lp);
        return t;
    }

    private void showRulesDialog() {
        if (group == null) return;
        boolean isAdmin = group.adminId != null && group.adminId.equals(Fb.uid());
        String curRules = (group.rules == null || group.rules.isEmpty())
                ? "1. Be kind & courteous\n2. No hate speech or spam\n3. Respect everyone's privacy"
                : group.rules;
        if (!isAdmin) {
            new AlertDialog.Builder(this)
                    .setTitle("📜 " + group.name + " Rules")
                    .setMessage(curRules)
                    .setPositiveButton("Got it", null)
                    .show();
            return;
        }
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 18, 10, 18, 4);
        EditText aboutIn = Ui.multi(this, "Group description", 2);
        aboutIn.setText(group.about == null ? "" : group.about);
        box.addView(aboutIn);
        EditText rulesIn = Ui.multi(this, "Group rules", 4);
        rulesIn.setText(curRules);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = Ui.dp(this, 8);
        box.addView(rulesIn, rp);
        new AlertDialog.Builder(this)
                .setTitle("👑 Edit Group Info & Rules")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    Fb.db().collection("groups").document(groupId).set(Fb.map(
                            "about", aboutIn.getText().toString().trim(),
                            "rules", rulesIn.getText().toString().trim()), SetOptions.merge());
                    Ui.toast(this, "Group rules updated ✓");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void filterPosts() {
        if (postSearch.isEmpty()) {
            adapter.setPosts(allPosts);
            return;
        }
        List<Models.Post> out = new ArrayList<>();
        for (Models.Post p : allPosts) {
            String hay = ((p.authorName == null ? "" : p.authorName) + " "
                    + (p.content == null ? "" : p.content)).toLowerCase();
            if (hay.contains(postSearch)) out.add(p);
        }
        adapter.setPosts(out);
    }

    @Override
    protected void onStart() {
        super.onStart();
        FastCache.addReactionObserver(reactionObserver);
        groupReg = Fb.db().collection("groups").document(groupId)
                .addSnapshotListener((d, err) -> {
                    if (d == null || !d.exists()) return;
                    group = Models.Group.fromDoc(d);
                    renderGroupHeader();
                });
        postsReg = Fb.db().collection("posts").whereEqualTo("groupId", groupId)
                .addSnapshotListener((snap, err) -> {
                    allPosts.clear();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap) allPosts.add(Models.Post.fromDoc(d));
                    }
                    Collections.sort(allPosts, (a, b2) -> Long.compare(b2.createdAt, a.createdAt));
                    filterPosts();
                });
    }

    private void renderGroupHeader() {
        if (group == null) return;
        title.setText(group.name);
        boolean member = group.members.contains(Fb.uid());
        String pBadge = "Private".equalsIgnoreCase(group.privacy) ? "🔒 Private" : "🌐 Public";
        sub.setText(pBadge + " · " + group.members.size() + " members"
                + (group.about == null || group.about.isEmpty() ? "" : " · " + group.about)
                + (group.adminId != null && group.adminId.equals(Fb.uid()) ? " · 👑 Admin" : ""));
        joinBtn.setText(member ? "✓ Joined — tap to leave" : "Join group");
        joinBtn.setBackground(Ui.round(this, member ? Ui.inputBg(this) : Ui.BLUE, 8));
        joinBtn.setTextColor(member ? Ui.txt(this) : 0xFFFFFFFF);
        composer.setVisibility(member ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        FastCache.removeReactionObserver(reactionObserver);
        if (groupReg != null) groupReg.remove();
        if (postsReg != null) postsReg.remove();
    }

    private void toggleJoin() {
        if (group == null) return;
        String me = Fb.uid();
        if (me == null) return;
        boolean member = group.members.contains(me);
        // ⚡ 0.0ms optimistic join/leave
        if (member) group.members.remove(me); else group.members.add(me);
        renderGroupHeader();
        java.util.Map<String, Object> m = Fb.map(
                "members", member ? FieldValue.arrayRemove(me) : FieldValue.arrayUnion(me),
                "membersCount", FieldValue.increment(member ? -1 : 1));
        Fb.db().collection("groups").document(groupId).set(m, SetOptions.merge());
        Ui.toast(this, member ? "Left group" : "Joined " + group.name + " 🎉");
    }

    @Override
    public void openComments(Models.Post post) {
        Intent i = new Intent(this, PostDetailActivity.class);
        i.putExtra("post_id", post.id);
        startActivity(i);
    }

    @Override
    public void openProfile(String uid) {
        Intent i = new Intent(this, ProfileActivity.class);
        i.putExtra("uid", uid);
        startActivity(i);
    }

    @Override
    public void editPost(Models.Post post) {
        Intent i = new Intent(this, CreatePostActivity.class);
        i.putExtra("edit_id", post.id);
        startActivity(i);
    }

    @Override public void createStory() { }
    @Override public void openStoryGroup(com.friendbook.story.StoriesAdapter.Group g) { }
    @Override public void loadMore() { }
    @Override public boolean hasMore() { return false; }
}
