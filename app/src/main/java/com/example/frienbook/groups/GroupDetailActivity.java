package com.example.frienbook.groups;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Ui;
import com.example.frienbook.models.Models;
import com.example.frienbook.post.CreatePostActivity;
import com.example.frienbook.post.FeedAdapter;
import com.example.frienbook.post.PostDetailActivity;
import com.example.frienbook.profile.ProfileActivity;
import com.example.frienbook.core.cloud.DocumentSnapshot;
import com.example.frienbook.core.cloud.FieldValue;
import com.example.frienbook.core.cloud.ListenerRegistration;
import com.example.frienbook.core.cloud.SetOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Group page: header + join/leave + the group's own realtime feed. */
public class GroupDetailActivity extends Activity implements FeedAdapter.Host {

    private String groupId;
    private Models.Group group;
    private FeedAdapter adapter;
    private ListenerRegistration groupReg, postsReg;
    private TextView title, sub, joinBtn, composer;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        groupId = getIntent().getStringExtra("group_id");
        if (groupId == null) { finish(); return; }

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "Group"));

        // group header
        LinearLayout head = Ui.col(this);
        head.setBackgroundColor(Ui.card(this));
        Ui.pad(head, 14, 12, 14, 12);
        title = Ui.tvBold(this, getIntent().getStringExtra("name") != null
                ? getIntent().getStringExtra("name") : "Group", 20, Ui.txt(this));
        head.addView(title);
        sub = Ui.tv(this, "", 13, Ui.sub(this));
        sub.setPadding(0, Ui.dp(this, 4), 0, 0);
        head.addView(sub);
        joinBtn = Ui.btn(this, "Join group");
        LinearLayout.LayoutParams jp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 40));
        jp.topMargin = Ui.dp(this, 8);
        head.addView(joinBtn, jp);
        joinBtn.setOnClickListener(v -> toggleJoin());
        // 🔗 invite friends with a link (friendbook.app/group?id=…)
        TextView invite = Ui.tvBold(this, "🔗 Copy invite link", 13, Ui.BLUE);
        invite.setPadding(0, Ui.dp(this, 10), 0, 0);
        invite.setOnClickListener(v -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("group",
                    "https://friendbook.app/group?id=" + groupId));
            Ui.toast(this, "Group invite link copied 🔗");
        });
        head.addView(invite);
        root.addView(head);

        // member-only composer
        composer = Ui.tvBold(this, "＋ Post something to this group…", 13, Ui.sub(this));
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
        adapter = new FeedAdapter(this, this);
        adapter.showHeader = false;
        adapter.showFooter = false;
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        groupReg = Fb.db().collection("groups").document(groupId)
                .addSnapshotListener((d, err) -> {
                    if (d == null || !d.exists()) { Ui.toast(this, "Group was deleted"); finish(); return; }
                    group = Models.Group.fromDoc(d);
                    title.setText(group.name);
                    boolean member = group.members.contains(Fb.uid());
                    sub.setText(group.members.size() + " members"
                            + (group.about == null || group.about.isEmpty() ? "" : " · " + group.about)
                            + (group.adminId.equals(Fb.uid()) ? " · you are admin" : ""));
                    joinBtn.setText(member ? "✓ Joined — tap to leave" : "Join group");
                    joinBtn.setBackground(Ui.round(this, member ? Ui.inputBg(this) : Ui.BLUE, 8));
                    joinBtn.setTextColor(member ? Ui.txt(this) : 0xFFFFFFFF);
                    composer.setVisibility(member ? View.VISIBLE : View.GONE);
                });
        postsReg = Fb.db().collection("posts").whereEqualTo("groupId", groupId)
                .addSnapshotListener((snap, err) -> {
                    List<Models.Post> list = new ArrayList<>();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap) list.add(Models.Post.fromDoc(d));
                    }
                    Collections.sort(list, (a, b2) -> Long.compare(b2.createdAt, a.createdAt));
                    adapter.setPosts(list);
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (groupReg != null) groupReg.remove();
        if (postsReg != null) postsReg.remove();
    }

    private void toggleJoin() {
        if (group == null) return;
        String me = Fb.uid();
        if (me == null) return;
        boolean member = group.members.contains(me);
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
    @Override public void openStoryGroup(com.example.frienbook.story.StoriesAdapter.Group g) { }
    @Override public void loadMore() { }
    @Override public boolean hasMore() { return false; }
}
