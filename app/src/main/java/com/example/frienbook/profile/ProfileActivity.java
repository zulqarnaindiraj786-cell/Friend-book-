package com.example.frienbook.profile;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.PopupMenu;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.frienbook.chat.ChatActivity;
import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Format;
import com.example.frienbook.core.Notify;
import com.example.frienbook.core.Presence;
import com.example.frienbook.core.Ui;
import com.example.frienbook.friends.UsersAdapter;
import com.example.frienbook.models.Models;
import com.example.frienbook.post.CreatePostActivity;
import com.example.frienbook.post.FeedAdapter;
import com.example.frienbook.post.FullscreenMediaActivity;
import com.example.frienbook.post.PostDetailActivity;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.WriteBatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Profile page: cover, avatar, actions (friend/follow/message/block), tabs. */
public class ProfileActivity extends Activity implements FeedAdapter.Host {

    private String uid;
    private boolean mine;
    private Models.User user;
    private String friendState = "none"; // none|friends|outgoing|incoming
    private String incomingRequestId = null;
    private boolean hasOut, hasIn;

    private TextView nameT, bioT, statsT, presenceT, primaryBtn, secondaryBtn;
    private ImageView cover, avatar;
    private TextView postsTab, aboutTab, friendsTab;
    private LinearLayout postsBox, aboutBox, friendsBox;
    private FeedAdapter postsAdapter;
    private UsersAdapter friendsAdapter;
    private ListenerRegistration userReg, friendsReg, outReg, inReg, postsReg;
    private Presence.Handle presenceH;
    private final List<String> friendIds = new ArrayList<>();
    private final java.util.Map<String, Models.User> userCache = new java.util.HashMap<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        uid = getIntent().getStringExtra("uid");
        if (uid == null) uid = Fb.uid();
        if (uid == null) { finish(); return; }
        mine = uid.equals(Fb.uid());

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "Profile"));

        ScrollView sv = new ScrollView(this);
        LinearLayout body = Ui.col(this);

        // cover + avatar
        FrameLayout hero = new FrameLayout(this);
        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.addView(cover, new FrameLayout.LayoutParams(-1, Ui.dp(this, 170)));
        FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(Ui.dp(this, 104), Ui.dp(this, 104), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ap.bottomMargin = Ui.dp(this, -52);
        avatar = new ImageView(this);
        avatar.setBackground(Ui.roundStroke(this, 0xFFFFFFFF, 52, Ui.card(this), 3));
        avatar.setClipToOutline(true);
        hero.addView(avatar, ap);
        body.addView(hero, new LinearLayout.LayoutParams(-1, Ui.dp(this, 170)));

        LinearLayout infoCol = Ui.col(this);
        infoCol.setBackgroundColor(Ui.card(this));
        infoCol.setGravity(Gravity.CENTER_HORIZONTAL);
        infoCol.setPadding(0, Ui.dp(this, 60), 0, Ui.dp(this, 12));
        nameT = Ui.tvBold(this, "", 22, Ui.txt(this));
        presenceT = Ui.tv(this, "", 12, Ui.GREEN);
        bioT = Ui.tv(this, "", 14, Ui.sub(this));
        bioT.setGravity(Gravity.CENTER);
        statsT = Ui.tv(this, "", 13, Ui.sub(this));
        statsT.setPadding(0, Ui.dp(this, 4), 0, 0);
        infoCol.addView(nameT);
        infoCol.addView(presenceT);
        infoCol.addView(bioT);
        infoCol.addView(statsT);

        LinearLayout actions = Ui.row(this);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), 0);
        primaryBtn = Ui.btn(this, "");
        secondaryBtn = Ui.btnGrey(this, "");
        TextView more = Ui.btnGrey(this, "•••");
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1);
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1);
        b2.leftMargin = Ui.dp(this, 8);
        LinearLayout.LayoutParams b3 = new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 40));
        b3.leftMargin = Ui.dp(this, 8);
        actions.addView(primaryBtn, b1);
        actions.addView(secondaryBtn, b2);
        actions.addView(more, b3);
        infoCol.addView(actions);
        more.setOnClickListener(this::moreMenu);
        body.addView(infoCol);
        body.addView(gap());

        // tabs
        LinearLayout tabs = Ui.row(this);
        tabs.setBackgroundColor(Ui.card(this));
        Ui.pad(tabs, 12, 10, 12, 10);
        postsTab = tabChip("Posts", tabs, 0);
        aboutTab = tabChip("About", tabs, 1);
        friendsTab = tabChip("Friends", tabs, 2);
        body.addView(tabs);
        body.addView(gap());

        // content boxes
        postsBox = Ui.col(this);
        RecyclerView postsRv = new RecyclerView(this);
        postsRv.setLayoutManager(new LinearLayoutManager(this));
        postsRv.setNestedScrollingEnabled(false);
        postsAdapter = new FeedAdapter(this, this);
        postsAdapter.showHeader = false;
        postsAdapter.showFooter = false;
        postsRv.setAdapter(postsAdapter);
        postsBox.addView(postsRv, new LinearLayout.LayoutParams(-1, -2));
        body.addView(postsBox);

        aboutBox = Ui.col(this);
        body.addView(aboutBox);

        friendsBox = Ui.col(this);
        RecyclerView frv = new RecyclerView(this);
        frv.setLayoutManager(new LinearLayoutManager(this));
        frv.setNestedScrollingEnabled(false);
        friendsAdapter = new UsersAdapter(this, null);
        frv.setAdapter(friendsAdapter);
        friendsBox.addView(frv, new LinearLayout.LayoutParams(-1, -2));
        body.addView(friendsBox);

        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        cover.setOnClickListener(v -> {
            if (user != null && user.coverUrl != null && !user.coverUrl.isEmpty()) {
                Intent i = new Intent(this, FullscreenMediaActivity.class);
                i.putExtra("type", "image");
                i.putExtra("url", user.coverUrl);
                startActivity(i);
            }
        });
        avatar.setOnClickListener(v -> {
            if (user != null && user.photoUrl != null && !user.photoUrl.isEmpty()) {
                Intent i = new Intent(this, FullscreenMediaActivity.class);
                i.putExtra("type", "image");
                i.putExtra("url", user.photoUrl);
                startActivity(i);
            }
        });
        primaryBtn.setOnClickListener(v -> primary());
        secondaryBtn.setOnClickListener(v -> secondary());
        showTab(0);
    }

    private View gap() {
        View v = new View(this);
        v.setBackgroundColor(Ui.bg(this));
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(this, 8)));
        return v;
    }

    private TextView tabChip(String label, LinearLayout parent, int idx) {
        TextView t = Ui.tvBold(this, label, 14, Ui.sub(this));
        t.setGravity(Gravity.CENTER);
        Ui.pad(t, 8, 8, 8, 8);
        parent.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        t.setOnClickListener(v -> showTab(idx));
        return t;
    }

    private void showTab(int idx) {
        postsBox.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        aboutBox.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        friendsBox.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);
        TextView[] all = {postsTab, aboutTab, friendsTab};
        for (int i = 0; i < 3; i++) {
            all[i].setTextColor(i == idx ? Ui.BLUE : Ui.sub(this));
            all[i].setBackground(Ui.round(this, i == idx ? Ui.unread(this) : 0x00000000, 8));
        }
        if (idx == 1) renderAbout();
        if (idx == 2) renderFriendsTab();
    }

    @Override
    protected void onStart() {
        super.onStart();
        userReg = Fb.db().collection("users").document(uid)
                .addSnapshotListener((d, err) -> {
                    if (d == null || !d.exists()) {
                        nameT.setText("User not found");
                        return;
                    }
                    user = Models.User.fromDoc(d);
                    bindUser();
                });
        String me = Fb.uid();
        if (me != null && !mine) {
            friendsReg = Fb.db().collection("friends").whereArrayContains("participants", me)
                    .addSnapshotListener((snap, err) -> {
                        friendIds.clear();
                        if (snap != null) {
                            for (DocumentSnapshot d : snap) {
                                List<String> parts = Models.ChatRoom.fromDoc(d).participants;
                                if (!parts.contains(me)) continue;
                                for (String p : parts) if (!p.equals(me)) friendIds.add(p);
                            }
                        }
                        computeFriendState();
                        renderFriendsTab();
                        for (String f : friendIds) ensureUser(f);
                    });
            outReg = Fb.db().collection("friend_requests")
                    .whereEqualTo("fromUid", me).whereEqualTo("toUid", uid).whereEqualTo("status", "pending")
                    .addSnapshotListener((snap, err) -> {
                        hasOut = snap != null && !snap.isEmpty();
                        computeFriendState();
                    });
            inReg = Fb.db().collection("friend_requests")
                    .whereEqualTo("fromUid", uid).whereEqualTo("toUid", me).whereEqualTo("status", "pending")
                    .addSnapshotListener((snap, err) -> {
                        hasIn = snap != null && !snap.isEmpty();
                        incomingRequestId = hasIn ? snap.getDocuments().get(0).getId() : null;
                        computeFriendState();
                    });
            presenceH = Presence.watch(uid, (online, lastSeen) -> {
                if (online) presenceT.setText("🟢 Active now");
                else if (lastSeen > 0) presenceT.setText("Active " + Format.ago(lastSeen) + " ago");
                else presenceT.setText("");
            });
        } else if (mine) {
            friendsReg = Fb.db().collection("friends").whereArrayContains("participants", me)
                    .addSnapshotListener((snap, err) -> {
                        friendIds.clear();
                        if (snap != null) {
                            for (DocumentSnapshot d : snap)
                                for (String p : Models.ChatRoom.fromDoc(d).participants)
                                    if (!p.equals(me)) friendIds.add(p);
                        }
                        renderFriendsTab();
                        for (String f : friendIds) ensureUser(f);
                    });
        }
        postsReg = Fb.db().collection("posts").whereEqualTo("authorId", uid)
                .addSnapshotListener((snap, err) -> {
                    List<Models.Post> list = new ArrayList<>();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap) {
                            Models.Post p = Models.Post.fromDoc(d);
                            if (!canSee(p)) continue;
                            list.add(p);
                        }
                    }
                    Collections.sort(list, (a, b2) -> Long.compare(b2.createdAt, a.createdAt));
                    postsAdapter.setPosts(list);
                });
    }

    private boolean canSee(Models.Post p) {
        String me = Fb.uid();
        if (mine || p.authorId.equals(me)) return true;
        if ("Only me".equalsIgnoreCase(p.privacy)) return false;
        if ("Friends".equalsIgnoreCase(p.privacy)) return friendIds.contains(me) || "friends".equals(friendState);
        return true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (userReg != null) userReg.remove();
        if (friendsReg != null) friendsReg.remove();
        if (outReg != null) outReg.remove();
        if (inReg != null) inReg.remove();
        if (postsReg != null) postsReg.remove();
        if (presenceH != null) presenceH.close();
    }

    private void bindUser() {
        nameT.setText(user.displayName);
        bioT.setText(user.bio);
        bioT.setVisibility(user.bio == null || user.bio.isEmpty() ? View.GONE : View.VISIBLE);
        statsT.setText(Format.count(user.friendsCount) + " friends · "
                + Format.count(user.followers.size()) + " followers · "
                + Format.count(user.following.size()) + " following");
        Ui.avatar(this, avatar, user.photoUrl);
        if (user.coverUrl != null && !user.coverUrl.isEmpty()) Ui.img(this, cover, user.coverUrl);
        else cover.setBackground(Ui.round(this, Ui.div(this), 0));

        if (mine) {
            primaryBtn.setText("✏️ Edit profile");
            secondaryBtn.setText("＋ Add to story");
            presenceT.setText("");
        }
        refreshButtons();
    }

    private void computeFriendState() {
        if (mine) return;
        if (friendIds.contains(uid)) friendState = "friends";
        else if (hasOut) friendState = "outgoing";
        else if (hasIn) friendState = "incoming";
        else friendState = "none";
        refreshButtons();
    }

    private void refreshButtons() {
        if (mine || user == null) return;
        switch (friendState) {
            case "friends": primaryBtn.setText("✓ Friends"); break;
            case "outgoing": primaryBtn.setText("✓ Requested"); break;
            case "incoming": primaryBtn.setText("✔ Respond"); break;
            default: primaryBtn.setText("＋ Add friend"); break;
        }
        boolean following = user.followers.contains(Fb.uid());
        secondaryBtn.setText(following ? "Following ✓" : "＋ Follow");
    }

    private void primary() {
        if (user == null) return;
        if (mine) {
            startActivity(new Intent(this, EditProfileActivity.class));
            return;
        }
        String me = Fb.uid();
        switch (friendState) {
            case "friends":
                Ui.choose(this, user.displayName, new String[]{"💬 Message", "Unfriend"}, w -> {
                    if (w == 0) message();
                    else unfriend();
                });
                break;
            case "outgoing":
                Ui.confirm(this, "Cancel friend request?", "", "Cancel request", () -> {
                    Fb.db().collection("friend_requests")
                            .whereEqualTo("fromUid", Fb.uid()).whereEqualTo("toUid", uid)
                            .get().addOnSuccessListener(snap -> {
                                for (DocumentSnapshot d : snap) d.getReference().delete();
                            });
                });
                break;
            case "incoming":
                Ui.choose(this, "Respond to request", new String[]{"✔ Confirm", "Delete"}, w -> {
                    if (incomingRequestId == null) return;
                    if (w == 0) {
                        WriteBatch batch = Fb.db().batch();
                        batch.update(Fb.db().collection("friend_requests").document(incomingRequestId), "status", "accepted");
                        batch.set(Fb.db().collection("friends").document(), Fb.map(
                                "participants", java.util.Arrays.asList(Fb.uid(), uid),
                                "createdAt", FieldValue.serverTimestamp()));
                        batch.update(Fb.db().collection("users").document(Fb.uid()), "friendsCount", FieldValue.increment(1));
                        batch.update(Fb.db().collection("users").document(uid), "friendsCount", FieldValue.increment(1));
                        batch.commit().addOnSuccessListener(x -> {
                            Notify.send(uid, "friend_accept", "accepted your friend request");
                            Ui.toast(this, "You're now friends 🎉");
                        });
                    } else {
                        Fb.db().collection("friend_requests").document(incomingRequestId).delete();
                    }
                });
                break;
            default:
                Map<String, Object> m = Fb.map(
                        "fromUid", Fb.uid(), "toUid", uid,
                        "fromUserName", Fb.myName(), "fromUserPhotoUrl", Fb.myPhoto(),
                        "status", "pending", "createdAt", FieldValue.serverTimestamp());
                Fb.db().collection("friend_requests").add(m).addOnSuccessListener(x -> {
                    Notify.send(uid, "friend_request", "sent you a friend request");
                    Ui.toast(this, "Friend request sent ✓");
                });
                break;
        }
    }

    private void secondary() {
        if (user == null) return;
        if (mine) {
            startActivity(new Intent(this, com.example.frienbook.story.AddStoryActivity.class));
            return;
        }
        boolean following = user.followers.contains(Fb.uid());
        String me = Fb.uid();
        Fb.db().collection("users").document(uid)
                .set(Fb.map("followers", following ? FieldValue.arrayRemove(me) : FieldValue.arrayUnion(me)), SetOptions.merge());
        Fb.db().collection("users").document(me)
                .set(Fb.map("following", following ? FieldValue.arrayRemove(uid) : FieldValue.arrayUnion(uid)), SetOptions.merge());
        if (!following) Notify.send(uid, "follow", "started following you");
    }

    private void message() {
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra("uid", uid);
        i.putExtra("name", user != null ? user.displayName : "");
        startActivity(i);
    }

    private void unfriend() {
        Ui.confirm(this, "Unfriend " + user.displayName + "?", "", "Unfriend", () -> {
            String me = Fb.uid();
            Fb.db().collection("friends").whereArrayContains("participants", me)
                    .get().addOnSuccessListener(snap -> {
                        for (DocumentSnapshot d : snap) {
                            List<String> parts = Models.ChatRoom.fromDoc(d).participants;
                            if (parts.contains(uid)) {
                                d.getReference().delete();
                                Fb.db().collection("users").document(me).update("friendsCount", FieldValue.increment(-1));
                                Fb.db().collection("users").document(uid).update("friendsCount", FieldValue.increment(-1));
                            }
                        }
                    });
        });
    }

    private void moreMenu(View v) {
        PopupMenu pm = new PopupMenu(this, v);
        if (!mine) {
            boolean blocked = Fb.me != null && Fb.me.blocked.contains(uid);
            pm.getMenu().add("💬 Message");
            pm.getMenu().add(blocked ? "🔓 Unblock user" : "🚫 Block user");
            pm.getMenu().add("🚩 Report profile");
        } else {
            pm.getMenu().add("📷 Change profile photo");
            pm.getMenu().add("🖼 Change cover photo");
            pm.getMenu().add("✏️ Edit details");
        }
        pm.setOnMenuItemClickListener(item -> {
            String t = item.getTitle().toString();
            if (t.contains("Message")) message();
            else if (t.contains("Unblock")) Fb.patchMe(Fb.map("blockedUsers", FieldValue.arrayRemove(uid)));
            else if (t.contains("Block") && !t.contains("Unblock")) {
                Ui.confirm(this, "Block " + (user != null ? user.displayName : "") + "?", "", "Block", () ->
                        Fb.patchMe(Fb.map("blockedUsers", FieldValue.arrayUnion(uid))));
            } else if (t.contains("Report")) {
                Fb.db().collection("reports").add(Fb.map("uid", uid, "reportedBy", Fb.uid(),
                        "type", "profile", "createdAt", FieldValue.serverTimestamp()));
                Ui.toast(this, "Reported");
            } else if (t.contains("profile photo") || t.contains("cover") || t.contains("details")) {
                startActivity(new Intent(this, EditProfileActivity.class));
            }
            return true;
        });
        pm.show();
    }

    private void renderAbout() {
        aboutBox.removeAllViews();
        if (user == null) return;
        LinearLayout card = Ui.col(this);
        card.setBackgroundColor(Ui.card(this));
        Ui.pad(card, 14, 12, 14, 14);
        card.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        TextView title = Ui.tvBold(this, "About", 17, Ui.txt(this));
        card.addView(title);
        addAboutRow(card, "📝", user.bio == null || user.bio.isEmpty() ? "No bio yet" : user.bio);
        addAboutRow(card, "👥", Format.count(user.friendsCount) + " friends");
        addAboutRow(card, "🚶", Format.count(user.followers.size()) + " followers · " + Format.count(user.following.size()) + " following");
        if (mine) addAboutRow(card, "📧", user.email);
        if (user.createdAt > 0) addAboutRow(card, "🗓", "Joined " + Format.dateTime(user.createdAt));
        aboutBox.addView(card);
    }

    private void addAboutRow(LinearLayout card, String icon, String text) {
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 8), 0, 0);
        TextView e = Ui.tv(this, icon, 16, Ui.sub(this));
        e.setPadding(0, 0, Ui.dp(this, 12), 0);
        row.addView(e);
        TextView t = Ui.tv(this, text, 14, Ui.txt(this));
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(row);
    }

    private void renderFriendsTab() {
        if (friendsBox == null || getApplicationContext() == null) return;
        List<UsersAdapter.Row> rows = new ArrayList<>();
        for (String fid : friendIds) {
            Models.User u = userCache.get(fid);
            if (u == null) continue;
            UsersAdapter.Row r = new UsersAdapter.Row();
            r.user = u;
            r.subtitle = u.isOnline ? "🟢 Active now" : "Friend";
            rows.add(r);
        }
        friendsAdapter.setData(rows);
    }

    private void ensureUser(String fuid) {
        if (userCache.containsKey(fuid)) return;
        Fb.getUser(fuid).addOnSuccessListener(u -> {
            if (u != null) { userCache.put(fuid, u); renderFriendsTab(); }
        });
    }

    // ---------- FeedAdapter.Host ----------
    @Override public void openComments(Models.Post post) {
        Intent i = new Intent(this, PostDetailActivity.class);
        i.putExtra("post_id", post.id);
        startActivity(i);
    }

    @Override public void openProfile(String uid2) {
        if (uid2 == null || uid2.equals(uid)) return;
        Intent i = new Intent(this, ProfileActivity.class);
        i.putExtra("uid", uid2);
        startActivity(i);
    }

    @Override public void editPost(Models.Post post) {
        Intent i = new Intent(this, CreatePostActivity.class);
        i.putExtra("edit_id", post.id);
        startActivity(i);
    }

    @Override public void createStory() { }
    @Override public void openStoryGroup(com.example.frienbook.story.StoriesAdapter.Group g) { }
    @Override public void loadMore() { }
    @Override public boolean hasMore() { return false; }
}
