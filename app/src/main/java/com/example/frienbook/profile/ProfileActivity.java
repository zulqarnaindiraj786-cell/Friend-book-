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
import com.example.frienbook.core.Admin;
import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Format;
import com.example.frienbook.core.Icons;
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
    private android.widget.ImageView nameTick;
    private android.widget.LinearLayout moneyRow;
    private android.widget.TextView boostBtn, subscribeBtn, tickBtn;
    private com.google.firebase.firestore.ListenerRegistration subReg;
    private boolean subscribedActive;
    private ImageView cover, avatar;
    private TextView postsTab, aboutTab, friendsTab, photosTab;
    private LinearLayout postsBox, aboutBox, friendsBox, photosBox;
    private TextView pinnedTag;
    private PhotosAdapter photosAdapter;
    private LinearLayout lockBanner;
    private View tabsRow;
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
        LinearLayout nameRow = Ui.row(this);
        nameRow.setGravity(Gravity.CENTER);
        nameRow.addView(nameT);
        nameTick = new android.widget.ImageView(this);
        nameTick.setImageResource(com.example.frienbook.R.drawable.ic_verified);
        nameTick.setVisibility(View.GONE);
        int nts = Ui.dp(this, 19);
        LinearLayout.LayoutParams ntp = new LinearLayout.LayoutParams(nts, nts);
        ntp.leftMargin = Ui.dp(this, 6);
        nameRow.addView(nameTick, ntp);
        infoCol.addView(nameRow);
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

        // monetization row: boost (own profile) / subscribe (creator profile) / get-a-tick
        moneyRow = Ui.row(this);
        moneyRow.setGravity(Gravity.CENTER);
        moneyRow.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), 0);
        moneyRow.setVisibility(View.GONE);
        boostBtn = Ui.btnOutline(this, "🚀 Boost Profile · 24h · Rs " + Admin.PRICE_BOOST_PROFILE, Icons.BLUE);
        boostBtn.setOnClickListener(v -> boostProfile());
        subscribeBtn = Ui.btn(this, "");
        subscribeBtn.setOnClickListener(v -> subscribe());
        tickBtn = Ui.btnGrey(this, "✓ Get a tick");
        tickBtn.setOnClickListener(v -> startActivity(
                new Intent(this, com.example.frienbook.bluetick.BlueTickActivity.class)));
        LinearLayout.LayoutParams m1 = new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1);
        LinearLayout.LayoutParams m2 = new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1);
        m2.leftMargin = Ui.dp(this, 8);
        moneyRow.addView(boostBtn, m1);
        moneyRow.addView(tickBtn, m2);
        moneyRow.addView(subscribeBtn, new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1));
        infoCol.addView(moneyRow);
        infoCol.addView(actions);
        more.setOnClickListener(this::moreMenu);
        body.addView(infoCol);
        body.addView(gap());

        // 🔒 lock banner (visible when this profile is locked for the viewer)
        lockBanner = Ui.col(this);
        lockBanner.setBackground(Ui.round(this, Ui.card(this), 14));
        lockBanner.setGravity(Gravity.CENTER);
        Ui.pad(lockBanner, 20, 26, 20, 26);
        lockBanner.setVisibility(View.GONE);
        lockBanner.addView(Ui.tv(this, "🔒", 36, Ui.txt(this)));
        TextView lockTitle = Ui.tvBold(this, "This profile is locked", 16, Ui.txt(this));
        lockTitle.setPadding(0, Ui.dp(this, 8), 0, 0);
        lockBanner.addView(lockTitle);
        TextView lockSub = Ui.tv(this,
                "Sirf friends is profile ki posts aur details dekh sakte hain — jaisi Facebook 'Profile lock' setting.", 12, Ui.sub(this));
        lockSub.setGravity(Gravity.CENTER);
        lockBanner.addView(lockSub);
        body.addView(lockBanner);

        // tabs
        LinearLayout tabs = Ui.row(this);
        tabs.setBackgroundColor(Ui.card(this));
        Ui.pad(tabs, 12, 10, 12, 10);
        tabsRow = tabs;
        postsTab = tabChip("Posts", tabs, 0);
        aboutTab = tabChip("About", tabs, 1);
        friendsTab = tabChip("Friends", tabs, 2);
        photosTab = tabChip("Photos", tabs, 3);
        body.addView(tabs);
        body.addView(gap());

        // content boxes
        postsBox = Ui.col(this);
        pinnedTag = Ui.tvBold(this, "📌 Pinned post", 12, Ui.sub(this));
        pinnedTag.setVisibility(View.GONE);
        Ui.pad(pinnedTag, 14, 8, 14, 0);
        postsBox.addView(pinnedTag);
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

        // 📷 Photos grid (all media posts of this user — FB Lite profile Photos tab)
        photosBox = Ui.col(this);
        androidx.recyclerview.widget.RecyclerView prv = new androidx.recyclerview.widget.RecyclerView(this);
        prv.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(this, 3));
        prv.setNestedScrollingEnabled(false);
        photosAdapter = new PhotosAdapter();
        prv.setAdapter(photosAdapter);
        photosBox.addView(prv, new LinearLayout.LayoutParams(-1, -2));
        body.addView(photosBox);

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
        photosBox.setVisibility(idx == 3 ? View.VISIBLE : View.GONE);
        TextView[] all = {postsTab, aboutTab, friendsTab, photosTab};
        for (int i = 0; i < 4; i++) {
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
        watchSubscription();
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
                    // 📌 pinned post floats to the very top of the Posts tab
                    String pin = user != null ? user.pinnedPostId : null;
                    if (pin != null && !pin.isEmpty()) {
                        for (int i = 0; i < list.size(); i++) {
                            if (pin.equals(list.get(i).id)) {
                                Models.Post pp = list.remove(i);
                                list.add(0, pp);
                                break;
                            }
                        }
                    }
                    boolean hasPin = pin != null && !pin.isEmpty() && !list.isEmpty()
                            && pin.equals(list.get(0).id);
                    if (pinnedTag != null) pinnedTag.setVisibility(hasPin ? View.VISIBLE : View.GONE);
                    postsAdapter.setPosts(list);
                    // photos grid: every image/video from this user's posts
                    List<String> media = new ArrayList<>();
                    for (Models.Post p : list) {
                        if (p.mediaUrls != null) media.addAll(p.mediaUrls);
                        if (p.mediaUrl != null && !p.mediaUrl.isEmpty()
                                && !media.contains(p.mediaUrl)) media.add(p.mediaUrl);
                    }
                    photosAdapter.media.clear();
                    photosAdapter.media.addAll(media);
                    photosAdapter.notifyDataSetChanged();
                });
    }

    /** 3-column media grid for the Photos tab. */
    private class PhotosAdapter extends RecyclerView.Adapter<PhotosAdapter.VH> {
        final List<String> media = new ArrayList<>();

        class VH extends RecyclerView.ViewHolder {
            ImageView iv;
            VH(View v) {
                super(v);
                iv = (ImageView) v;
            }
        }

        @androidx.annotation.NonNull
        @Override public VH onCreateViewHolder(@androidx.annotation.NonNull android.view.ViewGroup p, int t) {
            ImageView iv = new ImageView(p.getContext());
            int s = (getResources().getDisplayMetrics().widthPixels - Ui.dp(ProfileActivity.this, 8)) / 3;
            iv.setLayoutParams(new RecyclerView.LayoutParams(s, s));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            int gap = Ui.dp(ProfileActivity.this, 1);
            iv.setPadding(gap, gap, gap, gap);
            return new VH(iv);
        }

        @Override public void onBindViewHolder(@androidx.annotation.NonNull VH h, int i) {
            String url = media.get(i);
            Ui.img(ProfileActivity.this, h.iv, url);
            h.iv.setOnClickListener(v -> {
                Intent in = new Intent(ProfileActivity.this, FullscreenMediaActivity.class);
                in.putExtra("url", url);
                startActivity(in);
            });
        }

        @Override public int getItemCount() { return media.size(); }
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
        if (subReg != null) subReg.remove();
        if (presenceH != null) presenceH.close();
    }

    private void bindUser() {
        nameT.setText(user.displayName);
        Icons.setTickBadge(nameTick, user.tickLevel, true);
        bioT.setText(user.bio);
        bioT.setVisibility(user.bio == null || user.bio.isEmpty() ? View.GONE : View.VISIBLE);
        statsT.setText(Format.count(user.friendsCount) + " friends · "
                + Format.count(user.followers.size()) + " followers · "
                + Format.count(user.following.size()) + " following");
        statsT.setOnClickListener(v -> {
            if (user.followers.isEmpty() && user.following.isEmpty()) {
                Ui.toast(this, "No followers yet");
                return;
            }
            Ui.choose(this, "Open list", new String[]{"Followers", "Following"}, w -> {
                Intent i = new Intent(this, com.example.frienbook.friends.FollowListActivity.class);
                i.putExtra("uid", uid);
                i.putExtra("mode", w == 0 ? "followers" : "following");
                startActivity(i);
            });
        });
        Ui.avatar(this, avatar, user.photoUrl);
        if (user.coverUrl != null && !user.coverUrl.isEmpty()) Ui.img(this, cover, user.coverUrl);
        else cover.setBackground(Ui.round(this, Ui.div(this), 0));

        // 🔒 Profile lock (FB Lite): friends/owner see everything — strangers get the lock card
        boolean stranger = !mine && !friendIds.contains(uid);
        boolean locked = user.profileLocked && stranger;
        lockBanner.setVisibility(locked ? View.VISIBLE : View.GONE);
        if (tabsRow != null) tabsRow.setVisibility(locked ? View.GONE : View.VISIBLE);
        if (locked) {
            postsBox.setVisibility(View.GONE);
            aboutBox.setVisibility(View.GONE);
            friendsBox.setVisibility(View.GONE);
            photosBox.setVisibility(View.GONE);
        } else {
            showTab(0);
        }

        if (mine) {
            primaryBtn.setText("✏️ Edit profile");
            secondaryBtn.setText("＋ Add to story");
            presenceT.setText("");
            moneyRow.setVisibility(View.VISIBLE);
            boostBtn.setVisibility(View.VISIBLE);
            subscribeBtn.setVisibility(View.GONE);
            tickBtn.setVisibility(user.tickRank() >= 3 ? View.GONE : View.VISIBLE);
        } else {
            boostBtn.setVisibility(View.GONE);
            tickBtn.setVisibility(View.GONE);
            moneyRow.setVisibility(user.subscriptionsEnabled ? View.VISIBLE : View.GONE);
            subscribeBtn.setVisibility(user.subscriptionsEnabled ? View.VISIBLE : View.GONE);
            updateSubscribeButton();
        }
        refreshButtons();
    }

    private void updateSubscribeButton() {
        subscribeBtn.setText(subscribedActive ? "✓ Subscribed"
                : "⭐ Subscribe $" + (user.subscriptionPrice > 0 ? user.subscriptionPrice : 5) + "/mo");
    }

    private void subscribe() {
        if (subscribedActive) {
            Ui.toast(this, "You're already subscribed to " + user.displayName);
            return;
        }
        int price = user.subscriptionPrice > 0 ? user.subscriptionPrice : 5;
        Intent i = new Intent(this, com.example.frienbook.pay.PayRequestActivity.class);
        i.putExtra("type", "subscription");
        i.putExtra("target_id", uid);
        i.putExtra("amount", price);
        i.putExtra("currency", "USD");
        i.putExtra("title", "Subscribe to " + user.displayName);
        startActivity(i);
    }

    private void boostProfile() {
        Ui.confirm(this, "🚀 Boost Profile",
                "Your profile will be pinned on TOP of the Home feed for 24 hours.\nCost: Rs "
                        + Admin.PRICE_BOOST_PROFILE + " (JazzCash / Easypaisa).",
                "Continue", () -> {
                    Intent i = new Intent(this, com.example.frienbook.pay.PayRequestActivity.class);
                    i.putExtra("type", "boost_profile");
                    i.putExtra("amount", Admin.PRICE_BOOST_PROFILE);
                    i.putExtra("title", "Boost Profile — 24h");
                    startActivity(i);
                });
    }

    /** live subscription state for viewers of this profile */
    private void watchSubscription() {
        if (subReg != null) subReg.remove();
        if (mine || Fb.uid() == null) return;
        subReg = Fb.db().collection("subscriptions")
                .whereEqualTo("creatorId", uid)
                .whereEqualTo("subscriberId", Fb.uid())
                .limit(1)
                .addSnapshotListener((snap, err) -> {
                    subscribedActive = false;
                    if (snap != null && !snap.isEmpty()) {
                        com.google.firebase.firestore.DocumentSnapshot d = snap.getDocuments().get(0);
                        java.util.Map<String, Object> m = d.getData();
                        boolean active = "active".equals(Format.str(m, "status"));
                        long exp = Format.ts(m == null ? null : m.get("expiry"));
                        subscribedActive = active && (exp == 0 || exp > System.currentTimeMillis());
                    }
                    if (user != null && user.subscriptionsEnabled) updateSubscribeButton();
                });
    }

    private void computeFriendState() {
        if (mine) return;
        if (friendIds.contains(uid)) friendState = "friends";
        else if (hasOut) friendState = "outgoing";
        else if (hasIn) friendState = "incoming";
        else friendState = "none";
        refreshButtons();
        if (user != null) bindUser(); // re-apply profile-lock gate once friendships resolved
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

    private static String firstOnly(String n) {
        if (n == null) return "them";
        int i = n.indexOf(' ');
        return i > 0 ? n.substring(0, i) : n;
    }

    private void moreMenu(View v) {
        PopupMenu pm = new PopupMenu(this, v);
        if (!mine) {
            boolean blocked = Fb.me != null && Fb.me.blocked.contains(uid);
            pm.getMenu().add("💬 Message");
            pm.getMenu().add("🖐 Poke " + (user != null ? firstOnly(user.displayName) : "them"));
            pm.getMenu().add(blocked ? "🔓 Unblock user" : "🚫 Block user");
            pm.getMenu().add("💙 See friendship");
            pm.getMenu().add("🔗 Copy profile link");
            pm.getMenu().add("🚩 Report profile");
            // 🛡 ADMIN: open ANY profile → grant/remove ticks, ban/unban right here
            if (com.example.frienbook.core.Admin.isAdmin()) {
                pm.getMenu().add("🛡 ✓ Tick level (admin)…");
                pm.getMenu().add(user != null && user.banned
                        ? "🛡 ✓ Unban user" : "🛡 🚫 Ban user");
            }
        } else {
            pm.getMenu().add("📷 Change profile photo");
            pm.getMenu().add("🖼 Change cover photo");
            pm.getMenu().add("✏️ Edit details");
        }
        pm.setOnMenuItemClickListener(item -> {
            String t = item.getTitle().toString();
            if (t.contains("Message")) message();
            else if (t.contains("Poke")) com.example.frienbook.social.PokesActivity.sendPoke(
                    this, uid, user != null ? user.displayName : "them");
            else if (t.contains("Unblock")) Fb.patchMe(Fb.map("blockedUsers", FieldValue.arrayRemove(uid)));
            else if (t.contains("Block") && !t.contains("Unblock")) {
                Ui.confirm(this, "Block " + (user != null ? user.displayName : "") + "?", "", "Block", () ->
                        Fb.patchMe(Fb.map("blockedUsers", FieldValue.arrayUnion(uid))));
            } else if (t.contains("See friendship")) {
                showFriendship();
            } else if (t.contains("Copy profile link")) {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("profile",
                        "https://friendbook.app/user/" + uid));
                Ui.toast(this, "Profile link copied 🔗");
            } else if (t.contains("Tick level (admin)")) {
                Ui.choose(this, "🛡 Set tick for " + (user != null ? user.displayName : "user"),
                        new String[]{"✓ Blue tick", "✨ Gold tick", "💎 Diamond tick", "✕ Remove tick"},
                        w -> com.example.frienbook.core.Admin.setTickLevel(this, uid,
                                user != null ? user.displayName : "user",
                                w == 0 ? "blue" : w == 1 ? "gold" : w == 2 ? "diamond" : "none"));
            } else if (t.contains("Ban user") || t.contains("Unban user")) {
                boolean unban = t.contains("Unban");
                String name = user != null ? user.displayName : "this user";
                Ui.confirm(this, unban ? "✓ Unban " + name + "?" : "🚫 Ban " + name + "?",
                        unban ? "They can use friendbook again."
                                : "They will be signed out and blocked from using friendbook.",
                        unban ? "Unban" : "Ban", () -> {
                            Fb.db().collection("users").document(uid)
                                    .set(Fb.map("banned", !unban),
                                            com.google.firebase.firestore.SetOptions.merge())
                                    .addOnSuccessListener(x -> {
                                        Ui.toast(this, unban ? "Unbanned ✓" : "Banned 🚫");
                                        if (!unban) com.example.frienbook.core.Notify.send(
                                                uid, "ban", "Your account was suspended by an admin.", null);
                                    })
                                    .addOnFailureListener(e -> Ui.toast(this, e.getMessage()));
                        });
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

    /** 💙 See friendship — FB Lite style: since when + mutual friends count. */
    private void showFriendship() {
        if (user == null) return;
        String me = Fb.uid();
        StringBuilder sb = new StringBuilder();
        sb.append(user.displayName).append(" & ").append(Fb.myName()).append("\n\n");
        int mutual = 0;
        if (Fb.me != null)
            for (String f : user.followers) if (Fb.me.following.contains(f) || Fb.me.followers.contains(f)) mutual++;
        sb.append("👥 ").append(mutual).append(" mutual connections\n");
        sb.append(friendState.equals("friends") ? "✓ You are friends\n" : "Not friends yet\n");
        if (user.createdAt > 0) sb.append("🗓 They joined ").append(Format.dateTime(user.createdAt));
        Ui.info(this, "💙 Friendship", sb.toString());
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
        if (user.birthday != null && !user.birthday.isEmpty())
            addAboutRow(card, "🎂", "Birthday: " + user.birthday);
        if (user.isAdmin) addAboutRow(card, "🛡", "friendbook Team");
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
