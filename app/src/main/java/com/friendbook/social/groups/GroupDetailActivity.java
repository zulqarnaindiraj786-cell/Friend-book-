package com.friendbook.social.groups;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.social.core.AdsConfig;
import com.friendbook.social.core.Cloudinary;
import com.friendbook.social.core.FastCache;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Icons;
import com.friendbook.social.core.Notify;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.friendbook.social.post.CreatePostActivity;
import com.friendbook.social.post.FeedAdapter;
import com.friendbook.social.post.PostDetailActivity;
import com.friendbook.social.profile.ProfileActivity;
import com.friendbook.social.story.StoriesAdapter;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 👥 Full Pro Community Group Detail Screen:
 *  - Cover Photo + Group Icon + Role Badge (👑 Admin / 🛡 Moderator / ✓ Member)
 *  - Invite Members picker (sends direct `group_invite` notification + share link + QR)
 *  - 🛡 Manage Group Hub (for Group Admins & Group Moderators):
 *      • Pending Posts Approval Queue (Approve ✓ / Decline ✕)
 *      • Member Join Requests Queue (Approve ✓ / Decline ✕)
 *      • Manage Members, Admins & Moderators (Make Admin, Make Moderator, Remove Role, Kick, Ban/Unban)
 *      • Full Group Settings (Name, About, Category, Privacy, Post Approval, Join Approval, Who Can Post, Rules, Cover/Icon, Pin Featured, Delete Group)
 *  - 4 Tabs: Discussion · Featured · Members · About & Rules
 */
public class GroupDetailActivity extends Activity implements FeedAdapter.Host {

    private static final int PICK_GROUP_ICON = 811;
    private static final int PICK_GROUP_COVER = 812;

    private String id;
    private Models.Group group;
    private ListenerRegistration gReg, pReg;

    // Header & Action UI
    private ImageView coverIv, groupIconIv;
    private TextView titleTv, metaTv, roleBadgeTv;
    private TextView joinBtn, inviteBtn, manageBtn;
    private LinearLayout pinnedCard;
    private TextView pinnedBodyTv;
    private LinearLayout composerCard;
    private TextView composerHintTv;
    private TextView tabDisc, tabFeat, tabMem, tabAbout;
    private int activeTab = 0; // 0 = Discussion, 1 = Featured, 2 = Members, 3 = About

    // Content containers
    private RecyclerView feedRv;
    private ScrollView extraScroll;
    private LinearLayout extraCol;

    private FeedAdapter adapter;
    private final List<Models.Post> approvedPosts = new ArrayList<>();
    private final List<Models.Post> pendingPosts = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        id = getIntent().getStringExtra("group_id");
        if (id == null || id.isEmpty()) { finish(); return; }

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        LinearLayout topBar = Ui.pageBar(this, "Community Group");
        TextView modPostBtn = Ui.roundChip(this, "⋯", Ui.inputBg(this), Ui.txt(this), 15);
        Ui.pad(modPostBtn, 12, 4, 12, 4);
        modPostBtn.setOnClickListener(this::showQuickGroupMenu);
        topBar.addView(modPostBtn);
        root.addView(topBar);
        root.addView(Ui.divider(this));

        // Hero Card (Cover + Avatar + Title + Action Pills + Tabs)
        LinearLayout hero = Ui.col(this);
        hero.setBackgroundColor(Ui.card(this));

        FrameLayout coverWrap = new FrameLayout(this);
        coverIv = new ImageView(this);
        coverIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverIv.setBackgroundColor(0xFF1A3A6E);
        coverWrap.addView(coverIv, new FrameLayout.LayoutParams(-1, Ui.dp(this, 118)));

        groupIconIv = new ImageView(this);
        int isz = Ui.dp(this, 60);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(isz, isz, Gravity.BOTTOM | Gravity.START);
        ilp.setMargins(Ui.dp(this, 14), 0, 0, Ui.dp(this, 10));
        coverWrap.addView(groupIconIv, ilp);

        roleBadgeTv = Ui.roundChip(this, "", 0xBB000000, Color.WHITE, 11);
        Ui.pad(roleBadgeTv, 10, 4, 10, 4);
        roleBadgeTv.setVisibility(View.GONE);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        rlp.setMargins(0, 0, Ui.dp(this, 12), Ui.dp(this, 10));
        coverWrap.addView(roleBadgeTv, rlp);
        hero.addView(coverWrap);

        LinearLayout info = Ui.col(this);
        Ui.pad(info, 14, 10, 14, 8);
        titleTv = Ui.tvBold(this, "Loading group…", 19, Ui.txt(this));
        metaTv = Ui.tv(this, "", 12.5f, Ui.sub(this));
        metaTv.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 8));
        info.addView(titleTv);
        info.addView(metaTv);

        // Action Buttons Row: [ Join / Joined ]  [ ➕ Invite ]  [ 🛡 Manage ]
        LinearLayout actions = Ui.row(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        joinBtn = Ui.btn(this, "Join Group");
        joinBtn.setGravity(Gravity.CENTER);
        Ui.pad(joinBtn, 12, 8, 12, 8);
        joinBtn.setOnClickListener(v -> onJoinOrLeaveClicked());
        actions.addView(joinBtn, new LinearLayout.LayoutParams(0, -2, 1));

        inviteBtn = Ui.btnOutline(this, "➕ Invite", Ui.BLUE);
        inviteBtn.setGravity(Gravity.CENTER);
        Ui.pad(inviteBtn, 12, 8, 12, 8);
        LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(0, -2, 1);
        ivLp.leftMargin = Ui.dp(this, 6);
        inviteBtn.setOnClickListener(v -> showInviteMembersDialog());
        actions.addView(inviteBtn, ivLp);

        manageBtn = Ui.roundChip(this, "🛡 Manage", Ui.ORANGE, Color.WHITE, 12.5f);
        manageBtn.setGravity(Gravity.CENTER);
        Ui.pad(manageBtn, 12, 8, 12, 8);
        manageBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams mgLp = new LinearLayout.LayoutParams(0, -2, 1);
        mgLp.leftMargin = Ui.dp(this, 6);
        manageBtn.setOnClickListener(v -> showManageGroupHub());
        actions.addView(manageBtn, mgLp);
        info.addView(actions);
        hero.addView(info);

        // 4 Tabs: Discussion · Featured · Members · About
        LinearLayout tabs = Ui.row(this);
        Ui.pad(tabs, 10, 4, 10, 8);
        tabDisc = makeTab("Discussion", 0);
        tabFeat = makeTab("Featured 📌", 1);
        tabMem = makeTab("Members", 2);
        tabAbout = makeTab("About", 3);
        tabs.addView(tabDisc, new LinearLayout.LayoutParams(0, -2, 1));
        tabs.addView(tabFeat, new LinearLayout.LayoutParams(0, -2, 1));
        tabs.addView(tabMem, new LinearLayout.LayoutParams(0, -2, 1));
        tabs.addView(tabAbout, new LinearLayout.LayoutParams(0, -2, 1));
        hero.addView(tabs);
        root.addView(hero);
        root.addView(Ui.divider(this));

        // Pinned Featured Announcement Banner
        pinnedCard = Ui.row(this);
        pinnedCard.setGravity(Gravity.CENTER_VERTICAL);
        pinnedCard.setBackground(Ui.roundStroke(this, Ui.card(this), 10, Ui.BLUE, 1));
        Ui.pad(pinnedCard, 12, 8, 12, 8);
        pinnedCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.setMargins(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), 0);
        pinnedBodyTv = Ui.tvBold(this, "", 12.5f, Ui.BLUE);
        pinnedBodyTv.setMaxLines(2);
        pinnedCard.addView(pinnedBodyTv, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(pinnedCard, plp);

        // Composer strip
        composerCard = Ui.row(this);
        composerCard.setGravity(Gravity.CENTER_VERTICAL);
        composerCard.setBackgroundColor(Ui.card(this));
        Ui.pad(composerCard, 12, 8, 12, 8);
        ImageView myAv = new ImageView(this);
        Ui.avatar(this, myAv, Fb.myPhoto());
        int ms = Ui.dp(this, 36);
        composerCard.addView(myAv, new LinearLayout.LayoutParams(ms, ms));
        composerHintTv = Ui.tv(this, "Write something in this group…", 13.5f, Ui.sub(this));
        composerHintTv.setBackground(Ui.round(this, Ui.inputBg(this), 20));
        Ui.pad(composerHintTv, 14, 9, 14, 9);
        LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(0, -2, 1);
        chLp.leftMargin = Ui.dp(this, 8);
        composerCard.addView(composerHintTv, chLp);
        composerCard.setOnClickListener(v -> openGroupPostComposer());
        root.addView(composerCard);
        root.addView(Ui.divider(this));

        // Content Frame: Feed RecyclerView (Discussion/Featured) + ScrollView (Members/About)
        FrameLayout contentFrame = new FrameLayout(this);
        feedRv = new RecyclerView(this);
        feedRv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FeedAdapter(this, this);
        adapter.showHeader = false;
        adapter.showFooter = false;
        feedRv.setAdapter(adapter);
        contentFrame.addView(feedRv, new FrameLayout.LayoutParams(-1, -1));

        extraScroll = new ScrollView(this);
        extraScroll.setVisibility(View.GONE);
        extraCol = Ui.col(this);
        Ui.pad(extraCol, 12, 10, 12, 26);
        extraScroll.addView(extraCol);
        contentFrame.addView(extraScroll, new FrameLayout.LayoutParams(-1, -1));

        root.addView(contentFrame, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        updateTabStyles();
    }

    private TextView makeTab(String label, final int idx) {
        TextView t = Ui.tvBold(this, label, 12, Ui.sub(this));
        t.setGravity(Gravity.CENTER);
        Ui.pad(t, 6, 7, 6, 7);
        t.setOnClickListener(v -> {
            activeTab = idx;
            updateTabStyles();
            refreshActiveTabView();
        });
        return t;
    }

    private void updateTabStyles() {
        TextView[] arr = {tabDisc, tabFeat, tabMem, tabAbout};
        for (int i = 0; i < arr.length; i++) {
            boolean on = i == activeTab;
            arr[i].setBackground(Ui.round(this, on ? Ui.BLUE : Color.TRANSPARENT, 16));
            arr[i].setTextColor(on ? Color.WHITE : Ui.sub(this));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        gReg = Fb.db().collection("groups").document(id)
                .addSnapshotListener((d, err) -> {
                    if (d == null || !d.exists()) { finish(); return; }
                    group = Models.Group.fromDoc(d);
                    renderGroupHeader();
                    refreshActiveTabView();
                });

        // Single-field whereEqualTo("groupId", id) so ZERO manual composite index is needed!
        pReg = Fb.db().collection("posts")
                .whereEqualTo("groupId", id)
                .limit(120)
                .addSnapshotListener((snap, err) -> {
                    approvedPosts.clear();
                    pendingPosts.clear();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap.getDocuments()) {
                            Models.Post p = Models.Post.fromDoc(d);
                            if (p.archived || p.trashedAt > 0) continue;
                            if ("pending".equals(p.groupApprovalStatus)) {
                                pendingPosts.add(p);
                            } else if (!"declined".equals(p.groupApprovalStatus)) {
                                approvedPosts.add(p);
                            }
                        }
                    }
                    Collections.sort(approvedPosts, (a, b2) -> Long.compare(b2.createdAt, a.createdAt));
                    Collections.sort(pendingPosts, (a, b2) -> Long.compare(b2.createdAt, a.createdAt));
                    renderGroupHeader();
                    refreshActiveTabView();
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (gReg != null) gReg.remove();
        if (pReg != null) pReg.remove();
    }

    private void renderGroupHeader() {
        if (group == null) return;
        String me = Fb.uid();
        boolean isMember = me != null && group.members.contains(me);
        boolean isPending = me != null && group.pendingMemberIds.contains(me);
        boolean isAdmin = group.isGroupAdmin(me);
        boolean isMod = group.isGroupModerator(me);
        boolean canManage = group.canManageGroup(me);

        titleTv.setText(group.name);
        String priv = "Private".equalsIgnoreCase(group.privacy) ? "🔒 Private Group" : "🌍 Public Group";
        metaTv.setText(priv + " · " + group.members.size() + " members · " + group.category);

        if (group.coverUrl != null && !group.coverUrl.isEmpty()) {
            Ui.img(this, coverIv, group.coverUrl);
        } else {
            coverIv.setImageDrawable(null);
            coverIv.setBackgroundColor(0xFF1A3A6E);
        }
        coverIv.setOnClickListener(v -> {
            if (isAdmin) pickImage(PICK_GROUP_COVER);
        });

        if (group.photoUrl != null && !group.photoUrl.isEmpty()) {
            groupIconIv.setPadding(0, 0, 0, 0);
            groupIconIv.setBackground(null);
            Ui.avatar(this, groupIconIv, group.photoUrl);
        } else {
            groupIconIv.setBackground(Ui.circle(Ui.BLUE));
            int p = Ui.dp(this, 14);
            groupIconIv.setPadding(p, p, p, p);
            groupIconIv.setImageDrawable(Icons.tinted(this, Icons.GROUPS, Color.WHITE));
        }
        groupIconIv.setOnClickListener(v -> {
            if (isAdmin) pickImage(PICK_GROUP_ICON);
        });

        if (isAdmin) {
            roleBadgeTv.setVisibility(View.VISIBLE);
            roleBadgeTv.setText("👑 Group Admin");
        } else if (isMod) {
            roleBadgeTv.setVisibility(View.VISIBLE);
            roleBadgeTv.setText("🛡 Moderator");
        } else if (isMember) {
            roleBadgeTv.setVisibility(View.VISIBLE);
            roleBadgeTv.setText("✓ Member");
        } else {
            roleBadgeTv.setVisibility(View.GONE);
        }

        if (isMember) {
            joinBtn.setText("✓ Joined");
            joinBtn.setBackground(Ui.round(this, Ui.inputBg(this), 8));
            joinBtn.setTextColor(Ui.txt(this));
        } else if (isPending) {
            joinBtn.setText("⏳ Requested");
            joinBtn.setBackground(Ui.round(this, Ui.inputBg(this), 8));
            joinBtn.setTextColor(Ui.sub(this));
        } else {
            boolean reqNeeded = "Private".equalsIgnoreCase(group.privacy) || group.requireJoinApproval;
            joinBtn.setText(reqNeeded ? "🔒 Request Join" : "＋ Join Group");
            joinBtn.setBackground(Ui.round(this, Ui.BLUE, 8));
            joinBtn.setTextColor(Color.WHITE);
        }

        if (canManage) {
            int queue = pendingPosts.size() + group.pendingMemberIds.size();
            manageBtn.setVisibility(View.VISIBLE);
            manageBtn.setText(queue > 0 ? "🛡 Manage (" + queue + ")" : "🛡 Manage");
        } else {
            manageBtn.setVisibility(View.GONE);
        }

        // Pinned post banner
        Models.Post pinned = findPinnedPost();
        if (pinned != null && activeTab == 0) {
            pinnedCard.setVisibility(View.VISIBLE);
            String txt = (pinned.content == null || pinned.content.isEmpty()) ? "(Media announcement)" : pinned.content;
            pinnedBodyTv.setText("📌 Featured · " + pinned.authorName + ": " + txt);
            pinnedCard.setOnClickListener(v -> startActivity(
                    new Intent(this, PostDetailActivity.class).putExtra("post_id", pinned.id)));
        } else {
            pinnedCard.setVisibility(View.GONE);
        }
    }

    private Models.Post findPinnedPost() {
        if (group == null || group.pinnedPostId == null || group.pinnedPostId.isEmpty()) return null;
        for (Models.Post p : approvedPosts) {
            if (group.pinnedPostId.equals(p.id)) return p;
        }
        return null;
    }

    private void refreshActiveTabView() {
        if (group == null) return;
        String me = Fb.uid();
        boolean isMember = me != null && group.members.contains(me);
        boolean isPrivateLocked = "Private".equalsIgnoreCase(group.privacy) && !isMember && !group.canManageGroup(me);

        boolean canPost = isMember && (!"Admins & Moderators".equals(group.postPermission) || group.canManageGroup(me));
        composerCard.setVisibility(activeTab == 0 && canPost ? View.VISIBLE : View.GONE);
        if (group.requirePostApproval && !group.canManageGroup(me)) {
            composerHintTv.setText("Write something (Admin/Mod approval on)…");
        } else {
            composerHintTv.setText("Write something in " + group.name + "…");
        }

        if (isPrivateLocked && (activeTab == 0 || activeTab == 1)) {
            feedRv.setVisibility(View.GONE);
            extraScroll.setVisibility(View.VISIBLE);
            extraCol.removeAllViews();
            LinearLayout lockCard = Ui.col(this);
            lockCard.setGravity(Gravity.CENTER);
            lockCard.setBackground(Ui.round(this, Ui.card(this), 14));
            Ui.pad(lockCard, 24, 34, 24, 34);
            lockCard.addView(Ui.tvBold(this, "🔒 This Group is Private", 17, Ui.txt(this)));
            TextView sub = Ui.tv(this, "Join this group to view or participate in discussions.", 13, Ui.sub(this));
            sub.setGravity(Gravity.CENTER);
            sub.setPadding(0, Ui.dp(this, 6), 0, 0);
            lockCard.addView(sub);
            extraCol.addView(lockCard);
            return;
        }

        if (activeTab == 0) {
            feedRv.setVisibility(View.VISIBLE);
            extraScroll.setVisibility(View.GONE);
            adapter.setPosts(approvedPosts);
        } else if (activeTab == 1) {
            feedRv.setVisibility(View.VISIBLE);
            extraScroll.setVisibility(View.GONE);
            Models.Post pinned = findPinnedPost();
            List<Models.Post> feat = new ArrayList<>();
            if (pinned != null) feat.add(pinned);
            adapter.setPosts(feat);
        } else if (activeTab == 2) {
            feedRv.setVisibility(View.GONE);
            extraScroll.setVisibility(View.VISIBLE);
            renderMembersTab();
        } else {
            feedRv.setVisibility(View.GONE);
            extraScroll.setVisibility(View.VISIBLE);
            renderAboutTab();
        }
    }

    private void openGroupPostComposer() {
        if (group == null || Fb.uid() == null) return;
        boolean needsAppr = group.requirePostApproval && !group.canManageGroup(Fb.uid());
        Intent i = new Intent(this, CreatePostActivity.class);
        i.putExtra("group_id", id);
        i.putExtra("group_needs_approval", needsAppr);
        startActivity(i);
    }

    // ==================== JOIN / INVITE / QUICK MENU ====================

    private void onJoinOrLeaveClicked() {
        String me = Fb.uid();
        if (me == null || group == null) return;
        if (group.bannedMemberIds.contains(me)) {
            Ui.toast(this, "You are banned from this group");
            return;
        }
        boolean isMember = group.members.contains(me);
        boolean isPending = group.pendingMemberIds.contains(me);
        boolean isInvited = group.invitedUids.contains(me);

        if (isMember) {
            if (me.equals(group.adminId) && group.adminIds.size() <= 1 && group.members.size() > 1) {
                Ui.toast(this, "Promote another member to Group Admin before leaving");
                return;
            }
            Ui.confirm(this, "Leave \"" + group.name + "\"?",
                    "Leave this community group?", "Leave", () -> {
                        Map<String, Object> up = new HashMap<>();
                        up.put("members", FieldValue.arrayRemove(me));
                        up.put("adminIds", FieldValue.arrayRemove(me));
                        up.put("moderatorIds", FieldValue.arrayRemove(me));
                        Fb.db().collection("groups").document(id).update(up);
                        Ui.toast(this, "You left " + group.name);
                    });
        } else if (isPending) {
            Fb.db().collection("groups").document(id)
                    .update("pendingMemberIds", FieldValue.arrayRemove(me));
            Ui.toast(this, "Join request cancelled");
        } else if (!isInvited && ("Private".equalsIgnoreCase(group.privacy) || group.requireJoinApproval)) {
            Fb.db().collection("groups").document(id)
                    .update("pendingMemberIds", FieldValue.arrayUnion(me));
            Ui.toast(this, "⏳ Join request sent to Group Admins & Moderators");
        } else {
            Map<String, Object> up = new HashMap<>();
            up.put("members", FieldValue.arrayUnion(me));
            up.put("invitedUids", FieldValue.arrayRemove(me));
            Fb.db().collection("groups").document(id).update(up);
            Ui.toast(this, "Joined " + group.name + " 🎉");
        }
    }

    private void showInviteMembersDialog() {
        if (group == null) return;
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 14, 10, 14, 10);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        TextView shareLinkBtn = Ui.btnOutline(this, "🔗 Copy & Share Group Invite Link", Ui.BLUE);
        shareLinkBtn.setOnClickListener(v -> {
            String link = AdsConfig.OFFICIAL_WEBSITE_URL + "/?group=" + id;
            Intent s = new Intent(Intent.ACTION_SEND);
            s.setType("text/plain");
            s.putExtra(Intent.EXTRA_TEXT, "👥 Join our group \"" + group.name + "\" on friendbook!\n" + link);
            startActivity(Intent.createChooser(s, "Invite to Group"));
        });
        box.addView(shareLinkBtn, new LinearLayout.LayoutParams(-1, -2));

        TextView pickLabel = Ui.tvBold(this, "Select people to send an in-app Invite:", 13, Ui.sub(this));
        pickLabel.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        box.addView(pickLabel);

        final LinearLayout peopleCol = Ui.col(this);
        box.addView(peopleCol);
        final Set<String> selected = new LinkedHashSet<>();

        Fb.db().collection("users").limit(200).get().addOnSuccessListener(snap -> {
            if (snap == null || isFinishing()) return;
            for (DocumentSnapshot d : snap.getDocuments()) {
                Models.User u = Models.User.fromDoc(d);
                if (group.members.contains(u.uid) || group.bannedMemberIds.contains(u.uid) || u.banned) continue;
                LinearLayout row = Ui.row(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                Ui.pad(row, 4, 6, 4, 6);
                ImageView av = new ImageView(this);
                Ui.avatar(this, av, u.photoUrl);
                int s = Ui.dp(this, 36);
                row.addView(av, new LinearLayout.LayoutParams(s, s));
                String suffix = group.invitedUids.contains(u.uid) ? " (Invited)" : "";
                TextView nm = Ui.tvBold(this, u.displayName + suffix, 13.5f, Ui.txt(this));
                nm.setPadding(Ui.dp(this, 10), 0, 0, 0);
                row.addView(nm, new LinearLayout.LayoutParams(0, -2, 1));
                CheckBox cb = new CheckBox(this);
                cb.setOnCheckedChangeListener((b, ch) -> {
                    if (ch) selected.add(u.uid); else selected.remove(u.uid);
                });
                row.addView(cb);
                row.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));
                peopleCol.addView(row);
            }
        });

        new AlertDialog.Builder(this)
                .setTitle("➕ Invite Members to " + group.name)
                .setView(sv)
                .setPositiveButton("Send Invites", (d, w) -> {
                    if (selected.isEmpty()) return;
                    Fb.db().collection("groups").document(id)
                            .update("invitedUids", FieldValue.arrayUnion(selected.toArray()));
                    for (String toUid : selected) {
                        Notify.sendGroupInvite(toUid, id, group.name);
                    }
                    Ui.toast(this, "Sent " + selected.size() + " group invitation(s) 📨");
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showQuickGroupMenu(View anchor) {
        if (group == null) return;
        String me = Fb.uid();
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "➕ Invite people");
        pm.getMenu().add(0, 2, 0, "🔗 Copy group link");
        pm.getMenu().add(0, 3, 0, "📱 Group QR code");
        if (group.canManageGroup(me)) {
            pm.getMenu().add(0, 4, 0, "🛡 Manage group & approvals");
            pm.getMenu().add(0, 5, 0, "📌 Pin / Remove group posts");
        }
        if (group.isGroupAdmin(me)) {
            pm.getMenu().add(0, 6, 0, "⚙️ Group settings");
        }
        pm.setOnMenuItemClickListener(it -> {
            int item = it.getItemId();
            if (item == 1) showInviteMembersDialog();
            else if (item == 2) Ui.copy(this, AdsConfig.OFFICIAL_WEBSITE_URL + "/?group=" + id);
            else if (item == 3) Ui.showQrDialog(this, group.name, AdsConfig.OFFICIAL_WEBSITE_URL + "/?group=" + id);
            else if (item == 4) showManageGroupHub();
            else if (item == 5) showModerateGroupPostsDialog();
            else if (item == 6) showGroupSettingsDialog();
            return true;
        });
        pm.show();
    }

    // ==================== 🛡 MANAGE GROUP HUB (ADMINS & MODERATORS) ====================

    private void showManageGroupHub() {
        if (group == null) return;
        String me = Fb.uid();
        boolean isAdmin = group.isGroupAdmin(me);

        List<String> items = new ArrayList<>();
        items.add("📝 Pending Posts Approval (" + pendingPosts.size() + ")");
        items.add("🙋 Member Join Requests (" + group.pendingMemberIds.size() + ")");
        items.add("📌 Moderate / Pin Group Posts (" + approvedPosts.size() + ")");
        items.add("👥 Manage Members, Admins & Moderators (" + group.members.size() + ")");
        items.add("🚫 Banned Users (" + group.bannedMemberIds.size() + ")");
        if (isAdmin) {
            items.add("⚙️ Group Settings, Privacy & Rules");
            items.add("🖼 Change Cover Photo");
            items.add("📷 Change Group Icon");
        }
        if (me != null && me.equals(group.adminId)) {
            items.add("🗑 Delete Group Permanently");
        }

        String[] arr = items.toArray(new String[0]);
        Ui.choose(this, "🛡 Manage " + group.name, arr, w -> {
            String sel = arr[w];
            if (sel.startsWith("📝")) showPendingPostsDialog();
            else if (sel.startsWith("🙋")) showJoinRequestsDialog();
            else if (sel.startsWith("📌")) showModerateGroupPostsDialog();
            else if (sel.startsWith("👥")) { activeTab = 2; updateTabStyles(); refreshActiveTabView(); }
            else if (sel.startsWith("🚫")) showBannedMembersDialog();
            else if (sel.startsWith("⚙️")) showGroupSettingsDialog();
            else if (sel.startsWith("🖼")) pickImage(PICK_GROUP_COVER);
            else if (sel.startsWith("📷")) pickImage(PICK_GROUP_ICON);
            else if (sel.startsWith("🗑")) {
                Ui.confirm(this, "Delete \"" + group.name + "\"?",
                        "This permanently deletes the group.", "Delete Group", () ->
                                Fb.db().collection("groups").document(id).delete()
                                        .addOnSuccessListener(x -> {
                                            Ui.toast(this, "Group deleted");
                                            finish();
                                        }));
            }
        });
    }

    private void showPendingPostsDialog() {
        if (pendingPosts.isEmpty()) {
            Ui.info(this, "📝 Pending Posts", "No pending posts waiting for approval ✓");
            return;
        }
        LinearLayout list = Ui.col(this);
        Ui.pad(list, 14, 10, 14, 10);
        ScrollView sv = new ScrollView(this);
        sv.addView(list);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("📝 Pending Posts (" + pendingPosts.size() + ")")
                .setView(sv)
                .setNegativeButton("Close", null)
                .show();

        for (final Models.Post p : pendingPosts) {
            LinearLayout card = Ui.col(this);
            card.setBackground(Ui.roundStroke(this, Ui.card(this), 12, Ui.div(this), 1));
            Ui.pad(card, 12, 10, 12, 10);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Ui.dp(this, 8);
            card.setLayoutParams(lp);

            card.addView(Ui.tvBold(this, p.authorName + " · " + Format.ago(p.createdAt), 13.5f, Ui.txt(this)));
            String body = (p.content == null || p.content.isEmpty()) ? "(" + p.mediaType + " attachment)" : p.content;
            TextView tv = Ui.tv(this, body, 13, Ui.sub(this));
            tv.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
            card.addView(tv);

            LinearLayout btns = Ui.row(this);
            TextView appr = Ui.btn(this, "✓ Approve");
            Ui.pad(appr, 12, 6, 12, 6);
            appr.setOnClickListener(v -> {
                Fb.db().collection("posts").document(p.id).update("groupApprovalStatus", "approved");
                Notify.send(p.authorId, "group", "approved your post in \"" + group.name + "\" ✓", p.id);
                card.setVisibility(View.GONE);
                Ui.toast(this, "Post approved ✓");
            });
            TextView decl = Ui.btnOutline(this, "✕ Decline", Ui.RED);
            Ui.pad(decl, 12, 6, 12, 6);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
            dlp.leftMargin = Ui.dp(this, 8);
            decl.setOnClickListener(v -> {
                Fb.db().collection("posts").document(p.id).delete();
                card.setVisibility(View.GONE);
                Ui.toast(this, "Post declined & removed");
            });
            btns.addView(appr);
            btns.addView(decl, dlp);
            card.addView(btns);
            list.addView(card);
        }
    }

    private void showJoinRequestsDialog() {
        if (group == null || group.pendingMemberIds.isEmpty()) {
            Ui.info(this, "🙋 Join Requests", "No pending join requests right now ✓");
            return;
        }
        LinearLayout list = Ui.col(this);
        Ui.pad(list, 14, 10, 14, 10);
        ScrollView sv = new ScrollView(this);
        sv.addView(list);

        new AlertDialog.Builder(this)
                .setTitle("🙋 Member Join Requests (" + group.pendingMemberIds.size() + ")")
                .setView(sv)
                .setNegativeButton("Close", null)
                .show();

        for (final String reqUid : new ArrayList<>(group.pendingMemberIds)) {
            final LinearLayout row = Ui.row(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            Ui.pad(row, 4, 8, 4, 8);
            final ImageView av = new ImageView(this);
            int s = Ui.dp(this, 38);
            row.addView(av, new LinearLayout.LayoutParams(s, s));
            final TextView nm = Ui.tvBold(this, "Member", 14, Ui.txt(this));
            nm.setPadding(Ui.dp(this, 10), 0, 0, 0);
            row.addView(nm, new LinearLayout.LayoutParams(0, -2, 1));

            TextView ok = Ui.btn(this, "Approve");
            Ui.pad(ok, 10, 5, 10, 5);
            ok.setOnClickListener(v -> {
                Map<String, Object> up = new HashMap<>();
                up.put("pendingMemberIds", FieldValue.arrayRemove(reqUid));
                up.put("members", FieldValue.arrayUnion(reqUid));
                Fb.db().collection("groups").document(id).update(up);
                Notify.sendGroupInvite(reqUid, id, group.name);
                row.setVisibility(View.GONE);
                Ui.toast(this, "Approved ✓");
            });

            TextView no = Ui.btnOutline(this, "✕", Ui.RED);
            Ui.pad(no, 10, 5, 10, 5);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
            nlp.leftMargin = Ui.dp(this, 6);
            no.setOnClickListener(v -> {
                Fb.db().collection("groups").document(id)
                        .update("pendingMemberIds", FieldValue.arrayRemove(reqUid));
                row.setVisibility(View.GONE);
            });
            row.addView(ok);
            row.addView(no, nlp);
            list.addView(row);

            Fb.getUser(reqUid).addOnSuccessListener(u -> {
                if (u != null && !isFinishing()) {
                    nm.setText(u.displayName);
                    Ui.avatar(this, av, u.photoUrl);
                }
            });
        }
    }

    private void showModerateGroupPostsDialog() {
        if (approvedPosts.isEmpty()) {
            Ui.info(this, "📌 Group Posts", "No published posts in this group yet.");
            return;
        }
        LinearLayout list = Ui.col(this);
        Ui.pad(list, 14, 10, 14, 10);
        ScrollView sv = new ScrollView(this);
        sv.addView(list);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("📌 Pin or Remove Group Posts")
                .setView(sv)
                .setNegativeButton("Close", null)
                .show();

        for (final Models.Post p : approvedPosts) {
            LinearLayout card = Ui.col(this);
            card.setBackground(Ui.roundStroke(this, Ui.card(this), 10, Ui.div(this), 1));
            Ui.pad(card, 10, 8, 10, 8);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Ui.dp(this, 6);
            card.setLayoutParams(lp);

            boolean pinned = p.id.equals(group.pinnedPostId);
            card.addView(Ui.tvBold(this, (pinned ? "📌 PINNED · " : "") + p.authorName, 13, Ui.txt(this)));
            String body = (p.content == null || p.content.isEmpty()) ? "(Media post)" : p.content;
            TextView tv = Ui.tv(this, body, 12, Ui.sub(this));
            tv.setMaxLines(2);
            tv.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 6));
            card.addView(tv);

            LinearLayout btns = Ui.row(this);
            TextView pinB = Ui.btnOutline(this, pinned ? "Unpin" : "📌 Pin to Featured", Ui.BLUE);
            Ui.pad(pinB, 10, 5, 10, 5);
            pinB.setOnClickListener(v -> {
                Fb.db().collection("groups").document(id).update("pinnedPostId", pinned ? "" : p.id);
                dlg.dismiss();
                Ui.toast(this, pinned ? "Unpinned" : "Pinned to Featured 📌");
            });

            TextView delB = Ui.btnOutline(this, "🗑 Remove Post", Ui.RED);
            Ui.pad(delB, 10, 5, 10, 5);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
            dlp.leftMargin = Ui.dp(this, 8);
            delB.setOnClickListener(v -> {
                Fb.db().collection("posts").document(p.id).delete();
                card.setVisibility(View.GONE);
                Ui.toast(this, "Removed from group 🗑");
            });
            btns.addView(pinB);
            btns.addView(delB, dlp);
            card.addView(btns);
            list.addView(card);
        }
    }

    private void showBannedMembersDialog() {
        if (group == null || group.bannedMemberIds.isEmpty()) {
            Ui.info(this, "🚫 Banned Users", "No users are currently banned from this group.");
            return;
        }
        LinearLayout list = Ui.col(this);
        Ui.pad(list, 14, 10, 14, 10);
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        new AlertDialog.Builder(this)
                .setTitle("🚫 Banned from Group")
                .setView(sv)
                .setNegativeButton("Close", null)
                .show();
        for (final String bUid : new ArrayList<>(group.bannedMemberIds)) {
            final LinearLayout row = Ui.row(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            Ui.pad(row, 4, 8, 4, 8);
            final TextView nm = Ui.tvBold(this, bUid, 14, Ui.txt(this));
            row.addView(nm, new LinearLayout.LayoutParams(0, -2, 1));
            TextView unban = Ui.btnOutline(this, "Unban", Ui.GREEN);
            Ui.pad(unban, 10, 5, 10, 5);
            unban.setOnClickListener(v -> {
                Fb.db().collection("groups").document(id)
                        .update("bannedMemberIds", FieldValue.arrayRemove(bUid));
                row.setVisibility(View.GONE);
                Ui.toast(this, "Unbanned ✓");
            });
            row.addView(unban);
            list.addView(row);
            Fb.getUser(bUid).addOnSuccessListener(u -> { if (u != null) nm.setText(u.displayName); });
        }
    }

    private void showGroupSettingsDialog() {
        if (group == null || !group.isGroupAdmin(Fb.uid())) return;
        ScrollView sv = new ScrollView(this);
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 18, 12, 18, 10);
        sv.addView(box);

        EditText nameIn = Ui.input(this, "Group Name");
        nameIn.setText(group.name);
        EditText catIn = Ui.input(this, "Category (e.g. Gaming, Community)");
        catIn.setText(group.category);
        EditText aboutIn = Ui.multi(this, "Group Description…", 3);
        aboutIn.setText(group.about);
        EditText rulesIn = Ui.multi(this, "Group Rules…", 4);
        rulesIn.setText(group.rules);

        final String[] priv = {group.privacy};
        TextView privBtn = Ui.btnGrey(this, "Privacy: " + ("Private".equalsIgnoreCase(priv[0]) ? "🔒 Private" : "🌍 Public"));
        privBtn.setOnClickListener(v -> {
            priv[0] = "Private".equalsIgnoreCase(priv[0]) ? "Public" : "Private";
            privBtn.setText("Privacy: " + ("Private".equalsIgnoreCase(priv[0]) ? "🔒 Private" : "🌍 Public"));
        });

        final String[] postPerm = {group.postPermission};
        TextView permBtn = Ui.btnGrey(this, "Who Can Post: " + postPerm[0]);
        permBtn.setOnClickListener(v -> {
            postPerm[0] = "Everyone".equals(postPerm[0]) ? "Admins & Moderators" : "Everyone";
            permBtn.setText("Who Can Post: " + postPerm[0]);
        });

        Switch swPostAppr = addSwitchRow(box, "🛡 Require Post Approval", group.requirePostApproval);
        Switch swJoinAppr = addSwitchRow(box, "🙋 Require Join Request Approval", group.requireJoinApproval);

        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2);
        gap.topMargin = Ui.dp(this, 8);
        box.addView(Ui.tvBold(this, "Group Name", 12, Ui.sub(this)));
        box.addView(nameIn);
        box.addView(Ui.tvBold(this, "Category", 12, Ui.sub(this)), gap);
        box.addView(catIn);
        box.addView(privBtn, gap);
        box.addView(permBtn, gap);
        box.addView(Ui.tvBold(this, "About", 12, Ui.sub(this)), gap);
        box.addView(aboutIn);
        box.addView(Ui.tvBold(this, "Community Rules", 12, Ui.sub(this)), gap);
        box.addView(rulesIn);

        new AlertDialog.Builder(this)
                .setTitle("⚙️ Group Settings")
                .setView(sv)
                .setPositiveButton("Save Settings", (d, w) -> {
                    String n = nameIn.getText().toString().trim();
                    if (n.isEmpty()) return;
                    Map<String, Object> up = new HashMap<>();
                    up.put("name", n);
                    up.put("category", catIn.getText().toString().trim());
                    up.put("about", aboutIn.getText().toString().trim());
                    up.put("rules", rulesIn.getText().toString().trim());
                    up.put("privacy", priv[0]);
                    up.put("postPermission", postPerm[0]);
                    up.put("requirePostApproval", swPostAppr.isChecked());
                    up.put("requireJoinApproval", swJoinAppr.isChecked());
                    Fb.db().collection("groups").document(id).set(up, SetOptions.merge())
                            .addOnSuccessListener(x -> Ui.toast(this, "Group settings saved ✓"));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private Switch addSwitchRow(LinearLayout box, String label, boolean checked) {
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(r, 0, 8, 0, 6);
        r.addView(Ui.tvBold(this, label, 13.5f, Ui.txt(this)), new LinearLayout.LayoutParams(0, -2, 1));
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        r.addView(sw);
        box.addView(r);
        return sw;
    }

    // ==================== MEMBERS & ABOUT TABS ====================

    private void renderMembersTab() {
        extraCol.removeAllViews();
        String me = Fb.uid();
        boolean amAdmin = group.isGroupAdmin(me);
        boolean amMod = group.isGroupModerator(me);

        TextView staffHead = Ui.tvBold(this, "👑 Admins & 🛡 Moderators", 14.5f, Ui.txt(this));
        Ui.pad(staffHead, 4, 4, 4, 6);
        extraCol.addView(staffHead);

        Set<String> staffShown = new LinkedHashSet<>();
        for (String aUid : group.adminIds) {
            staffShown.add(aUid);
            extraCol.addView(memberCard(aUid, amAdmin, amMod));
        }
        for (String mUid : group.moderatorIds) {
            if (staffShown.contains(mUid)) continue;
            staffShown.add(mUid);
            extraCol.addView(memberCard(mUid, amAdmin, amMod));
        }

        TextView memHead = Ui.tvBold(this, "👥 Members (" + group.members.size() + ")", 14.5f, Ui.txt(this));
        Ui.pad(memHead, 4, 14, 4, 6);
        extraCol.addView(memHead);

        for (String uid : group.members) {
            if (staffShown.contains(uid)) continue;
            extraCol.addView(memberCard(uid, amAdmin, amMod));
        }
    }

    private View memberCard(final String memberUid, final boolean amAdmin, final boolean amMod) {
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.round(this, Ui.card(this), 12));
        Ui.pad(row, 12, 10, 12, 10);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(this, 6);
        row.setLayoutParams(lp);

        final ImageView av = new ImageView(this);
        int s = Ui.dp(this, 42);
        row.addView(av, new LinearLayout.LayoutParams(s, s));

        LinearLayout mid = Ui.col(this);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1);
        mp.leftMargin = Ui.dp(this, 10);
        LinearLayout nr = Ui.row(this);
        nr.setGravity(Gravity.CENTER_VERTICAL);
        final TextView nm = Ui.tvBold(this, "Member", 14, Ui.txt(this));
        nr.addView(nm);

        final ImageView tk = new ImageView(this);
        int ts = Ui.dp(this, 14);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ts, ts);
        tlp.leftMargin = Ui.dp(this, 4);
        nr.addView(tk, tlp);

        boolean isCreator = memberUid.equals(group.adminId);
        boolean isTargetAdmin = group.isGroupAdmin(memberUid);
        boolean isTargetMod = group.isGroupModerator(memberUid);
        if (isCreator || isTargetAdmin || isTargetMod) {
            String lbl = isCreator ? "👑 Creator · Admin" : (isTargetAdmin ? "👑 Admin" : "🛡 Moderator");
            int col = isTargetAdmin ? Ui.ORANGE : Ui.BLUE;
            TextView b = Ui.roundChip(this, lbl, col, Color.WHITE, 9.5f);
            Ui.pad(b, 7, 2, 7, 2);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
            blp.leftMargin = Ui.dp(this, 6);
            nr.addView(b, blp);
        }
        mid.addView(nr);
        mid.addView(Ui.tv(this, (amAdmin || amMod) && !isCreator ? "Tap to manage role / moderation" : "Tap to view profile",
                11.5f, Ui.sub(this)));
        row.addView(mid, mp);

        TextView dots = Ui.tvBold(this, "⋯", 18, Ui.sub(this));
        row.addView(dots);

        Fb.getUser(memberUid).addOnSuccessListener(u -> {
            if (u == null || isFinishing()) return;
            FastCache.putUser(u);
            nm.setText(u.displayName);
            Ui.avatar(this, av, u.photoUrl);
            Icons.setTickBadge(tk, u.tickLevel, false);
        });

        row.setOnClickListener(v -> showGroupMemberRoleMenu(v, memberUid, nm.getText().toString(), amAdmin, amMod));
        return row;
    }

    private void showGroupMemberRoleMenu(View anchor, String targetUid, String targetName, boolean amAdmin, boolean amMod) {
        String me = Fb.uid();
        if (me == null || me.equals(targetUid)) {
            startActivity(new Intent(this, ProfileActivity.class).putExtra("uid", targetUid));
            return;
        }
        boolean targetCreator = targetUid.equals(group.adminId);
        boolean targetAdmin = group.isGroupAdmin(targetUid);
        boolean targetMod = group.isGroupModerator(targetUid);

        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "👤 View " + targetName + "'s Profile");
        if (amAdmin && !targetCreator) {
            pm.getMenu().add(0, 2, 0, targetAdmin ? "➖ Remove Group Admin" : "👑 Make Group Admin");
            pm.getMenu().add(0, 3, 0, targetMod ? "➖ Remove Group Moderator" : "🛡 Make Group Moderator");
            pm.getMenu().add(0, 4, 0, "🚪 Remove from Group");
            pm.getMenu().add(0, 5, 0, "🚫 Ban from Group");
        } else if (amMod && !targetAdmin && !targetMod) {
            pm.getMenu().add(0, 4, 0, "🚪 Remove from Group");
            pm.getMenu().add(0, 5, 0, "🚫 Ban from Group");
        }
        pm.setOnMenuItemClickListener(it -> {
            int act = it.getItemId();
            if (act == 1) {
                startActivity(new Intent(this, ProfileActivity.class).putExtra("uid", targetUid));
            } else if (act == 2) {
                Fb.db().collection("groups").document(id).update("adminIds",
                        targetAdmin ? FieldValue.arrayRemove(targetUid) : FieldValue.arrayUnion(targetUid));
                Ui.toast(this, targetAdmin ? "Removed Admin role" : targetName + " is now a Group Admin 👑");
            } else if (act == 3) {
                Fb.db().collection("groups").document(id).update("moderatorIds",
                        targetMod ? FieldValue.arrayRemove(targetUid) : FieldValue.arrayUnion(targetUid));
                Ui.toast(this, targetMod ? "Removed Moderator role" : targetName + " is now a Group Moderator 🛡");
            } else if (act == 4) {
                Map<String, Object> up = new HashMap<>();
                up.put("members", FieldValue.arrayRemove(targetUid));
                up.put("adminIds", FieldValue.arrayRemove(targetUid));
                up.put("moderatorIds", FieldValue.arrayRemove(targetUid));
                Fb.db().collection("groups").document(id).update(up);
                Ui.toast(this, "Removed " + targetName);
            } else if (act == 5) {
                Map<String, Object> up = new HashMap<>();
                up.put("members", FieldValue.arrayRemove(targetUid));
                up.put("adminIds", FieldValue.arrayRemove(targetUid));
                up.put("moderatorIds", FieldValue.arrayRemove(targetUid));
                up.put("bannedMemberIds", FieldValue.arrayUnion(targetUid));
                Fb.db().collection("groups").document(id).update(up);
                Ui.toast(this, "Banned " + targetName + " from group 🚫");
            }
            return true;
        });
        pm.show();
    }

    private void renderAboutTab() {
        extraCol.removeAllViews();
        LinearLayout aboutCard = Ui.col(this);
        aboutCard.setBackground(Ui.round(this, Ui.card(this), 14));
        Ui.pad(aboutCard, 16, 14, 16, 14);
        aboutCard.addView(Ui.tvBold(this, "About This Group", 16, Ui.txt(this)));
        TextView desc = Ui.tv(this,
                group.about == null || group.about.isEmpty() ? "Welcome to " + group.name + "!" : group.about,
                14, Ui.txt(this));
        desc.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));
        aboutCard.addView(desc);

        aboutCard.addView(Ui.tv(this,
                ("Private".equalsIgnoreCase(group.privacy)
                        ? "🔒 Private · Only members can see who's in the group and what they post."
                        : "🌍 Public · Anyone can see who's in the group and what they post.")
                        + "\n🏷 Category: " + group.category
                        + "\n✍ Posting: " + group.postPermission
                        + "\n🛡 Post Approval: " + (group.requirePostApproval ? "Required (Admins & Moderators)" : "Instant publish"),
                12.5f, Ui.sub(this)));
        extraCol.addView(aboutCard);

        LinearLayout rulesCard = Ui.col(this);
        rulesCard.setBackground(Ui.round(this, Ui.card(this), 14));
        Ui.pad(rulesCard, 16, 14, 16, 14);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = Ui.dp(this, 10);
        rulesCard.setLayoutParams(rlp);
        rulesCard.addView(Ui.tvBold(this, "📜 Group Rules from Admins", 16, Ui.txt(this)));
        String rulesTxt = (group.rules == null || group.rules.isEmpty())
                ? "1. Be kind and courteous to everyone.\n2. No hate speech, spam, or unsafe content.\n3. Respect member privacy."
                : group.rules;
        TextView rt = Ui.tv(this, rulesTxt, 13.5f, Ui.txt(this));
        rt.setPadding(0, Ui.dp(this, 8), 0, 0);
        rulesCard.addView(rt);
        extraCol.addView(rulesCard);
    }

    private void pickImage(int code) {
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.setType("image/*");
        startActivityForResult(pick, code);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        boolean cover = req == PICK_GROUP_COVER;
        if (req != PICK_GROUP_ICON && !cover) return;
        Ui.toast(this, "Uploading " + (cover ? "cover photo…" : "group icon…"));
        String path = "groups/" + id + "_" + (cover ? "cover_" : "icon_") + System.currentTimeMillis() + ".jpg";
        Cloudinary.uploadOrFirebase(this, path, uri)
                .addOnSuccessListener(url -> Fb.db().collection("groups").document(id)
                        .update(cover ? "coverUrl" : "photoUrl", url.toString())
                        .addOnSuccessListener(x -> Ui.toast(this, "Updated ✓")))
                .addOnFailureListener(e -> Ui.toast(this, "Upload failed"));
    }

    @Override public void openComments(Models.Post p) {
        startActivity(new Intent(this, PostDetailActivity.class).putExtra("post_id", p.id));
    }
    @Override public void openProfile(String uid) {
        startActivity(new Intent(this, ProfileActivity.class).putExtra("uid", uid));
    }
    @Override public void editPost(Models.Post p) {
        startActivity(new Intent(this, CreatePostActivity.class).putExtra("edit_id", p.id));
    }
    @Override public void createStory() {}
    @Override public void openStoryGroup(StoriesAdapter.Group g) {}
    @Override public void loadMore() {}
    @Override public boolean hasMore() { return false; }
}
