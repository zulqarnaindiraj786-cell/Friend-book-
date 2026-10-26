package com.friendbook.profile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.bluetick.BlueTickActivity;
import com.friendbook.chat.ChatActivity;
import com.friendbook.core.Admin;
import com.friendbook.core.Cloudinary;
import com.friendbook.core.FastCache;
import com.friendbook.core.Fb;
import com.friendbook.core.Format;
import com.friendbook.core.Icons;
import com.friendbook.core.Notify;
import com.friendbook.core.Presence;
import com.friendbook.core.Ui;
import com.friendbook.friends.FollowListActivity;
import com.friendbook.friends.UsersAdapter;
import com.friendbook.models.Models;
import com.friendbook.pay.PayRequestActivity;
import com.friendbook.post.CreatePostActivity;
import com.friendbook.post.FeedAdapter;
import com.friendbook.post.FullscreenMediaActivity;
import com.friendbook.post.PostDetailActivity;
import com.friendbook.settings.ArchiveTrashActivity;
import com.friendbook.social.PokesActivity;
import com.friendbook.story.AddStoryActivity;
import com.friendbook.story.StoryViewerActivity;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.WriteBatch;

import java.text.DateFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Authentic Facebook Lite v525 Pro Profile & Creator Hub:
 * - Top "Data mode" strip + Crisp White Header: [ ‹ ]  User Name  [ ✏️ ] [ 🔍 ] [ ••• ]
 * - Cover photo + bottom-right 📷 camera circle
 * - Left-aligned overlapping 106dp circular Avatar + 📷 camera circle + Right-side Name, Verified Tick & Bold Stats
 * - "🎬 Digital creator" category + Bio
 * - Full-width stacked "[ 📊 Creator Dashboard ]" + "[ ⊕ Add to story ]" buttons (own profile)
 * - Underline Tabs: All | Reels | Photos | Friends | About
 * - "Personal details" card inside All tab (📍 City 🔒 · 🎂 Formatted Birthday · 💞 Relationship · See more details)
 */
public class ProfileActivity extends Activity implements FeedAdapter.Host {

    private static final int REQ_PICK_AVATAR = 801;
    private static final int REQ_PICK_COVER = 802;

    private String uid;
    private boolean mine;
    private boolean viewAsPublic = false;
    private Models.User user;
    private String friendState = "none"; // none|friends|outgoing|incoming
    private String incomingRequestId = null;
    private boolean hasOut, hasIn;
    private int userPostCount = 0;
    private String profileSearchQuery = "";

    private TextView topTitleTv, editTopIcon, guardBadge;
    private TextView nameT, categoryT, bioT, statsT, presenceT;
    private ImageView nameTick, cover, avatar;
    private View coverCamBtn, avatarCamBtn;
    private LinearLayout ownActionsCol, visitorActionsRow, moneyRow;
    private TextView creatorDashBtn, addStoryBtn;
    private TextView primaryBtn, messageVisitorBtn, secondaryBtn;
    private TextView boostBtn, subscribeBtn, tickBtn;
    private ListenerRegistration subReg;
    private boolean subscribedActive;

    private final TextView[] tabLabels = new TextView[5];
    private final View[] tabBars = new View[5];
    private int activeTab = 0;

    private LinearLayout allTabBox, reelsBox, photosBox, friendsBox, aboutBox;
    private LinearLayout personalDetailsCard, highlightsWrap, highlightsRow, viewAsBanner, lockBanner;
    private View tabsRow;
    private TextView pinnedTag, searchFilterBanner;
    private FeedAdapter postsAdapter;
    private UsersAdapter friendsAdapter;
    private PhotosAdapter photosAdapter;
    private ReelsGridAdapter reelsAdapter;

    private final List<Models.Post> allLoadedPosts = new ArrayList<>();
    private ListenerRegistration userReg, friendsReg, outReg, inReg, postsReg, reelsReg;
    private Presence.Handle presenceH;
    private final List<String> friendIds = new ArrayList<>();
    private final Map<String, Models.User> userCache = new HashMap<>();

    private final FastCache.ReactionObserver reactionObserver = (postId, myReact, total) -> {
        if (postsAdapter != null) postsAdapter.onFastReactionUpdate(postId, myReact, total);
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarLight(this);
        uid = getIntent().getStringExtra("uid");
        if (uid == null) uid = Fb.uid();
        if (uid == null) { finish(); return; }
        mine = uid.equals(Fb.uid());

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));

        // 1. Optional Facebook Lite "Data mode / Buy data" top strip
        root.addView(Ui.dataModeStrip(this, null));

        // 2. Crisp White Facebook Lite Profile Header: [ ‹ ]  User Name   [ ✏️ ] [ 🔍 ] [ ••• ]
        LinearLayout topBar = Ui.row(this);
        topBar.setBackgroundColor(Ui.card(this));
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(topBar, 8, 8, 10, 8);

        TextView backBtn = Ui.tvBold(this, "‹", 30, Ui.txt(this));
        Ui.pad(backBtn, 10, 0, 14, 2);
        backBtn.setOnClickListener(v -> finish());
        topBar.addView(backBtn);

        topTitleTv = Ui.tvBold(this, "Profile", 18, Ui.txt(this));
        topTitleTv.setSingleLine(true);
        topBar.addView(topTitleTv, new LinearLayout.LayoutParams(0, -2, 1));

        editTopIcon = Ui.tv(this, "✎", 20, Ui.txt(this));
        Ui.pad(editTopIcon, 10, 4, 10, 4);
        editTopIcon.setVisibility(mine ? View.VISIBLE : View.GONE);
        editTopIcon.setOnClickListener(v -> startActivity(new Intent(this, EditProfileActivity.class)));
        topBar.addView(editTopIcon);

        ImageView searchBtn = Icons.icon(this, Icons.SEARCH, 21, Ui.txt(this));
        searchBtn.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        searchBtn.setOnClickListener(v -> showProfileSearchDialog());
        topBar.addView(searchBtn, new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 36)));

        TextView topMore = Ui.tvBold(this, "•••", 17, Ui.txt(this));
        Ui.pad(topMore, 10, 4, 8, 4);
        topMore.setOnClickListener(this::moreMenu);
        topBar.addView(topMore);

        root.addView(topBar);
        root.addView(Ui.divider(this));

        // View-As-Public Banner
        viewAsBanner = Ui.row(this);
        viewAsBanner.setBackgroundColor(Ui.BLUE);
        Ui.pad(viewAsBanner, 14, 8, 14, 8);
        viewAsBanner.setVisibility(View.GONE);
        TextView vat = Ui.tvBold(this, "👁 Viewing profile as Public", 13, Color.WHITE);
        viewAsBanner.addView(vat, new LinearLayout.LayoutParams(0, -2, 1));
        TextView exitVa = Ui.roundChip(this, "Exit View As", Color.WHITE, Ui.BLUE, 12);
        Ui.pad(exitVa, 10, 4, 10, 4);
        exitVa.setOnClickListener(v -> {
            viewAsPublic = false;
            viewAsBanner.setVisibility(View.GONE);
            if (user != null) bindUser();
            if (postsReg != null) { postsReg.remove(); startPostsWatcher(); }
        });
        viewAsBanner.addView(exitVa);
        root.addView(viewAsBanner);

        // NestedScrollView ensures 100% of profile posts, reels & photos measure and scroll cleanly!
        NestedScrollView sv = new NestedScrollView(this);
        sv.setFillViewport(true);
        LinearLayout body = Ui.col(this);
        body.setClipChildren(false);

        // 3. Cover Photo + Bottom-Right Circular Camera Button
        FrameLayout coverFrame = new FrameLayout(this);
        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(0xFFD0D7DE);
        coverFrame.addView(cover, new FrameLayout.LayoutParams(-1, Ui.dp(this, 168)));

        FrameLayout coverCamWrap = new FrameLayout(this);
        coverCamWrap.setBackground(Ui.roundStroke(this, Ui.card(this), 19, Ui.div(this), 1));
        ImageView ccIv = Icons.icon(this, Icons.CAMERA, 19, Ui.txt(this));
        coverCamWrap.addView(ccIv, new FrameLayout.LayoutParams(Ui.dp(this, 19), Ui.dp(this, 19), Gravity.CENTER));
        FrameLayout.LayoutParams cclp = new FrameLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38), Gravity.BOTTOM | Gravity.END);
        cclp.setMargins(0, 0, Ui.dp(this, 12), Ui.dp(this, 10));
        coverCamBtn = coverCamWrap;
        coverFrame.addView(coverCamWrap, cclp);
        body.addView(coverFrame, new LinearLayout.LayoutParams(-1, Ui.dp(this, 168)));

        // 4. Hero Info Card: Left Overlapping Circular Avatar + Right Name, Tick & Bold Stats!
        LinearLayout infoCard = Ui.col(this);
        infoCard.setBackgroundColor(Ui.card(this));
        infoCard.setClipChildren(false);
        Ui.pad(infoCard, 14, 0, 14, 14);

        LinearLayout avatarAndNameRow = Ui.row(this);
        avatarAndNameRow.setClipChildren(false);
        avatarAndNameRow.setGravity(Gravity.TOP);

        // Left-aligned overlapping 106dp circular avatar with 4dp white ring + 📷 badge
        FrameLayout avWrap = new FrameLayout(this);
        int avSize = Ui.dp(this, 106);
        LinearLayout.LayoutParams avWrapLp = new LinearLayout.LayoutParams(avSize, avSize);
        avWrapLp.topMargin = Ui.dp(this, -34);

        FrameLayout whiteRing = new FrameLayout(this);
        whiteRing.setBackground(Ui.circle(Ui.card(this)));
        int innerAv = Ui.dp(this, 98);
        avatar = new ImageView(this);
        whiteRing.addView(avatar, new FrameLayout.LayoutParams(innerAv, innerAv, Gravity.CENTER));
        avWrap.addView(whiteRing, new FrameLayout.LayoutParams(avSize, avSize));

        FrameLayout avCamWrap = new FrameLayout(this);
        avCamWrap.setBackground(Ui.roundStroke(this, Ui.inputBg(this), 16, Ui.card(this), 2));
        ImageView acIv = Icons.icon(this, Icons.CAMERA, 16, Ui.txt(this));
        avCamWrap.addView(acIv, new FrameLayout.LayoutParams(Ui.dp(this, 16), Ui.dp(this, 16), Gravity.CENTER));
        FrameLayout.LayoutParams aclp = new FrameLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32), Gravity.BOTTOM | Gravity.END);
        aclp.setMargins(0, 0, Ui.dp(this, 2), Ui.dp(this, 2));
        avatarCamBtn = avCamWrap;
        avWrap.addView(avCamWrap, aclp);

        guardBadge = Ui.tvBold(this, "🛡️", 13, Color.WHITE);
        guardBadge.setGravity(Gravity.CENTER);
        guardBadge.setBackground(Ui.roundStroke(this, Ui.BLUE, 15, Ui.card(this), 2));
        guardBadge.setVisibility(View.GONE);
        FrameLayout.LayoutParams gblp = new FrameLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28), Gravity.BOTTOM | Gravity.START);
        gblp.setMargins(Ui.dp(this, 2), 0, 0, Ui.dp(this, 2));
        avWrap.addView(guardBadge, gblp);
        avatarAndNameRow.addView(avWrap, avWrapLp);

        // Right of Avatar: Bold Name + Scalloped Blue Tick + Bold Stats ("8 followers · 0 following · 8 posts")
        LinearLayout rightNameCol = Ui.col(this);
        LinearLayout.LayoutParams rncLp = new LinearLayout.LayoutParams(0, -2, 1);
        rncLp.leftMargin = Ui.dp(this, 14);
        rncLp.topMargin = Ui.dp(this, 10);

        LinearLayout nameRow = Ui.row(this);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        nameT = Ui.tvBold(this, "", 20, Ui.txt(this));
        nameRow.addView(nameT);
        nameTick = new ImageView(this);
        nameTick.setImageResource(com.friendbook.R.drawable.ic_verified);
        nameTick.setVisibility(View.GONE);
        int nts = Ui.dp(this, 18);
        LinearLayout.LayoutParams ntp = new LinearLayout.LayoutParams(nts, nts);
        ntp.leftMargin = Ui.dp(this, 5);
        nameRow.addView(nameTick, ntp);
        rightNameCol.addView(nameRow);

        statsT = Ui.tv(this, "", 13.5f, Ui.sub(this));
        statsT.setPadding(0, Ui.dp(this, 4), 0, 0);
        rightNameCol.addView(statsT);

        presenceT = Ui.tv(this, "", 12, Ui.GREEN);
        presenceT.setVisibility(View.GONE);
        rightNameCol.addView(presenceT);

        avatarAndNameRow.addView(rightNameCol, rncLp);
        infoCard.addView(avatarAndNameRow);

        // Category ("🎬 Digital creator") + Bio
        categoryT = Ui.tv(this, "🎬 Digital creator", 14, Ui.txt(this));
        categoryT.setPadding(0, Ui.dp(this, 10), 0, 0);
        infoCard.addView(categoryT);

        bioT = Ui.tv(this, "", 14, Ui.txt(this));
        bioT.setPadding(0, Ui.dp(this, 4), 0, 0);
        bioT.setVisibility(View.GONE);
        infoCard.addView(bioT);

        // 5A. Own Profile Full-Width Stacked Buttons: [ 📊 Creator Dashboard ] + [ ⊕ Add to story ]
        ownActionsCol = Ui.col(this);
        ownActionsCol.setPadding(0, Ui.dp(this, 12), 0, 0);

        creatorDashBtn = Ui.tvBold(this, "📊  Creator Dashboard", 15, Color.WHITE);
        creatorDashBtn.setGravity(Gravity.CENTER);
        creatorDashBtn.setBackground(Ui.round(this, Ui.BLUE, 8));
        creatorDashBtn.setMinHeight(Ui.dp(this, 42));
        creatorDashBtn.setOnClickListener(v -> CreatorDashboardDialog.show(this, () -> {
            if (user != null) bindUser();
        }));
        ownActionsCol.addView(creatorDashBtn, new LinearLayout.LayoutParams(-1, Ui.dp(this, 42)));

        addStoryBtn = Ui.tvBold(this, "⊕  Add to story", 15, Ui.txt(this));
        addStoryBtn.setGravity(Gravity.CENTER);
        addStoryBtn.setBackground(Ui.round(this, Ui.inputBg(this), 8));
        addStoryBtn.setMinHeight(Ui.dp(this, 42));
        LinearLayout.LayoutParams asLp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 42));
        asLp.topMargin = Ui.dp(this, 8);
        addStoryBtn.setOnClickListener(v -> startActivity(new Intent(this, AddStoryActivity.class)));
        ownActionsCol.addView(addStoryBtn, asLp);
        infoCard.addView(ownActionsCol);

        // 5B. Visitor Profile Action Row: [ ＋ Add friend ] [ 💬 Message ] [ ＋ Follow ] [ ••• ]
        visitorActionsRow = Ui.row(this);
        visitorActionsRow.setGravity(Gravity.CENTER_VERTICAL);
        visitorActionsRow.setPadding(0, Ui.dp(this, 12), 0, 0);
        visitorActionsRow.setVisibility(View.GONE);

        primaryBtn = Ui.btn(this, "＋ Add friend");
        messageVisitorBtn = Ui.btnGrey(this, "💬 Message");
        secondaryBtn = Ui.btnGrey(this, "＋ Follow");
        TextView visitorMore = Ui.btnGrey(this, "•••");

        LinearLayout.LayoutParams vb1 = new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1.15f);
        LinearLayout.LayoutParams vb2 = new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1f);
        vb2.leftMargin = Ui.dp(this, 6);
        LinearLayout.LayoutParams vb3 = new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 0.9f);
        vb3.leftMargin = Ui.dp(this, 6);
        LinearLayout.LayoutParams vb4 = new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 40));
        vb4.leftMargin = Ui.dp(this, 6);

        primaryBtn.setOnClickListener(v -> primary());
        messageVisitorBtn.setOnClickListener(v -> message());
        secondaryBtn.setOnClickListener(v -> secondary());
        visitorMore.setOnClickListener(this::moreMenu);

        visitorActionsRow.addView(primaryBtn, vb1);
        visitorActionsRow.addView(messageVisitorBtn, vb2);
        visitorActionsRow.addView(secondaryBtn, vb3);
        visitorActionsRow.addView(visitorMore, vb4);
        infoCard.addView(visitorActionsRow);

        // Creator Monetization / Boost / Subscribe Quick Strip
        moneyRow = Ui.row(this);
        moneyRow.setGravity(Gravity.CENTER);
        moneyRow.setPadding(0, Ui.dp(this, 8), 0, 0);
        moneyRow.setVisibility(View.GONE);
        boostBtn = Ui.btnOutline(this, "🚀 Boost · Rs " + Admin.PRICE_BOOST_PROFILE, Icons.BLUE);
        boostBtn.setOnClickListener(v -> boostProfile());
        tickBtn = Ui.btnGrey(this, "✓ Get a tick");
        tickBtn.setOnClickListener(v -> startActivity(new Intent(this, BlueTickActivity.class)));
        subscribeBtn = Ui.btn(this, "");
        subscribeBtn.setOnClickListener(v -> subscribe());
        LinearLayout.LayoutParams m1 = new LinearLayout.LayoutParams(0, Ui.dp(this, 36), 1);
        LinearLayout.LayoutParams m2 = new LinearLayout.LayoutParams(0, Ui.dp(this, 36), 1);
        m2.leftMargin = Ui.dp(this, 8);
        moneyRow.addView(boostBtn, m1);
        moneyRow.addView(tickBtn, m2);
        moneyRow.addView(subscribeBtn, new LinearLayout.LayoutParams(0, Ui.dp(this, 36), 1));
        infoCard.addView(moneyRow);

        body.addView(infoCard);

        // 6. Crisp Underline Tabs: All | Reels | Photos | Friends | About
        LinearLayout tabsWrap = Ui.col(this);
        tabsWrap.setBackgroundColor(Ui.card(this));
        tabsWrap.addView(Ui.divider(this));
        LinearLayout tabs = Ui.row(this);
        tabs.setBackgroundColor(Ui.card(this));
        tabsRow = tabsWrap;
        String[] tNames = {"All", "Reels", "Photos", "Friends", "About"};
        for (int i = 0; i < tNames.length; i++) {
            final int tabIdx = i;
            LinearLayout cell = Ui.col(this);
            cell.setGravity(Gravity.CENTER);
            TextView tv = Ui.tvBold(this, tNames[i], 14, Ui.sub(this));
            Ui.pad(tv, 6, 11, 6, 9);
            View bar = new View(this);
            bar.setBackgroundColor(Ui.BLUE);
            bar.setVisibility(View.INVISIBLE);
            cell.addView(tv);
            cell.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 3)));
            tabLabels[i] = tv;
            tabBars[i] = bar;
            cell.setOnClickListener(v -> showTab(tabIdx));
            tabs.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
        }
        tabsWrap.addView(tabs);
        tabsWrap.addView(Ui.divider(this));
        body.addView(tabsWrap);

        // 🔒 Profile Lock Banner (for non-friends when profileLocked == true)
        lockBanner = Ui.col(this);
        lockBanner.setBackgroundColor(Ui.card(this));
        lockBanner.setGravity(Gravity.CENTER);
        Ui.pad(lockBanner, 20, 28, 20, 28);
        lockBanner.setVisibility(View.GONE);
        lockBanner.addView(Ui.tv(this, "🔒", 36, Ui.txt(this)));
        TextView lockTitle = Ui.tvBold(this, "This profile is locked", 16, Ui.txt(this));
        lockTitle.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        lockBanner.addView(lockTitle);
        TextView lockSub = Ui.tv(this,
                "Only friends can see what they share on their profile.", 13, Ui.sub(this));
        lockSub.setGravity(Gravity.CENTER);
        lockBanner.addView(lockSub);
        body.addView(lockBanner);

        // 7. TAB 0 ("All"): Personal details + Story Highlights + Pinned Post + All User Posts
        allTabBox = Ui.col(this);

        personalDetailsCard = Ui.col(this);
        personalDetailsCard.setBackgroundColor(Ui.card(this));
        Ui.pad(personalDetailsCard, 14, 12, 14, 12);
        allTabBox.addView(personalDetailsCard);

        highlightsWrap = Ui.col(this);
        highlightsWrap.setBackgroundColor(Ui.card(this));
        Ui.pad(highlightsWrap, 14, 4, 14, 12);
        highlightsWrap.setVisibility(View.GONE);
        highlightsWrap.addView(Ui.tvBold(this, "✨ Story Highlights", 14, Ui.txt(this)));
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        highlightsRow = Ui.row(this);
        highlightsRow.setPadding(0, Ui.dp(this, 8), 0, 0);
        hsv.addView(highlightsRow);
        highlightsWrap.addView(hsv);
        allTabBox.addView(highlightsWrap);

        allTabBox.addView(gap());

        if (mine) {
            LinearLayout profComposeCard = Ui.col(this);
            profComposeCard.setBackgroundColor(Ui.card(this));
            Ui.pad(profComposeCard, 14, 12, 14, 12);
            profComposeCard.addView(Ui.tvBold(this, "Posts", 16, Ui.txt(this)));

            LinearLayout cRow = Ui.row(this);
            cRow.setGravity(Gravity.CENTER_VERTICAL);
            cRow.setPadding(0, Ui.dp(this, 10), 0, 0);

            ImageView myAv = new ImageView(this);
            Ui.avatar(this, myAv, Fb.myPhoto());
            int cs = Ui.dp(this, 40);
            cRow.addView(myAv, new LinearLayout.LayoutParams(cs, cs));

            TextView cHint = Ui.tv(this, "What's on your mind?", 15, Ui.txt(this));
            cHint.setSingleLine(true);
            cHint.setBackground(Ui.round(this, Ui.inputBg(this), 22));
            cHint.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
            cHint.setOnClickListener(v -> startActivity(new Intent(this, CreatePostActivity.class)));
            LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(0, -2, 1);
            chp.leftMargin = Ui.dp(this, 10);
            chp.rightMargin = Ui.dp(this, 8);
            cRow.addView(cHint, chp);

            LinearLayout pCol = Ui.col(this);
            pCol.setGravity(Gravity.CENTER);
            pCol.setMinimumWidth(Ui.dp(this, 50));
            Ui.pad(pCol, 6, 4, 4, 4);
            ImageView pvIcon = new ImageView(this);
            pvIcon.setImageResource(com.friendbook.R.drawable.ic_fb_photo_video);
            int pvs = Ui.dp(this, 34);
            pCol.addView(pvIcon, new LinearLayout.LayoutParams(pvs, pvs));
            pCol.addView(Ui.tvBold(this, "Photo", 11, Ui.txt(this)));
            pCol.setOnClickListener(v -> {
                Intent i = new Intent(this, CreatePostActivity.class);
                i.putExtra("quick_mode", "photo_video");
                startActivity(i);
            });
            cRow.addView(pCol);

            profComposeCard.addView(cRow);
            allTabBox.addView(profComposeCard);
            allTabBox.addView(gap());
        }

        searchFilterBanner = Ui.tvBold(this, "", 13, Ui.BLUE);
        searchFilterBanner.setBackgroundColor(Ui.card(this));
        Ui.pad(searchFilterBanner, 14, 8, 14, 8);
        searchFilterBanner.setVisibility(View.GONE);
        searchFilterBanner.setOnClickListener(v -> {
            profileSearchQuery = "";
            searchFilterBanner.setVisibility(View.GONE);
            applyPostsFilter();
        });
        allTabBox.addView(searchFilterBanner);

        pinnedTag = Ui.tvBold(this, "📌 Pinned post", 12, Ui.sub(this));
        pinnedTag.setBackgroundColor(Ui.card(this));
        pinnedTag.setVisibility(View.GONE);
        Ui.pad(pinnedTag, 14, 8, 14, 2);
        allTabBox.addView(pinnedTag);

        RecyclerView postsRv = new RecyclerView(this);
        postsRv.setLayoutManager(new LinearLayoutManager(this));
        postsRv.setNestedScrollingEnabled(false);
        postsRv.setItemAnimator(null);
        postsAdapter = new FeedAdapter(this, this);
        postsAdapter.showHeader = false;
        postsAdapter.showFooter = false;
        postsRv.setAdapter(postsAdapter);
        allTabBox.addView(postsRv, new LinearLayout.LayoutParams(-1, -2));
        body.addView(allTabBox);

        // 8. TAB 1 ("Reels"): 3-Column User Reels Grid
        reelsBox = Ui.col(this);
        reelsBox.setBackgroundColor(Ui.card(this));
        RecyclerView reelsRv = new RecyclerView(this);
        reelsRv.setLayoutManager(new GridLayoutManager(this, 3));
        reelsRv.setNestedScrollingEnabled(false);
        reelsAdapter = new ReelsGridAdapter();
        reelsRv.setAdapter(reelsAdapter);
        reelsBox.addView(reelsRv, new LinearLayout.LayoutParams(-1, -2));
        body.addView(reelsBox);

        // 9. TAB 2 ("Photos"): 3-Column User Media Grid
        photosBox = Ui.col(this);
        photosBox.setBackgroundColor(Ui.card(this));
        RecyclerView prv = new RecyclerView(this);
        prv.setLayoutManager(new GridLayoutManager(this, 3));
        prv.setNestedScrollingEnabled(false);
        photosAdapter = new PhotosAdapter();
        prv.setAdapter(photosAdapter);
        photosBox.addView(prv, new LinearLayout.LayoutParams(-1, -2));
        body.addView(photosBox);

        // 10. TAB 3 ("Friends")
        friendsBox = Ui.col(this);
        friendsBox.setBackgroundColor(Ui.card(this));
        RecyclerView frv = new RecyclerView(this);
        frv.setLayoutManager(new LinearLayoutManager(this));
        frv.setNestedScrollingEnabled(false);
        friendsAdapter = new UsersAdapter(this, null);
        frv.setAdapter(friendsAdapter);
        friendsBox.addView(frv, new LinearLayout.LayoutParams(-1, -2));
        body.addView(friendsBox);

        // 11. TAB 4 ("About")
        aboutBox = Ui.col(this);
        body.addView(aboutBox);

        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        // Cover & Avatar taps + Direct Camera Upload Buttons!
        View.OnClickListener coverClick = v -> {
            if (mine && !viewAsPublic) {
                Ui.choose(this, "Cover photo", new String[]{
                        "👁 View full cover photo (HD)",
                        "🖼 Upload new cover photo"
                }, w -> {
                    if (w == 0) openFullscreenImage(user != null ? user.coverUrl : null, "Cover Photo", false);
                    else pickImage(REQ_PICK_COVER);
                });
            } else {
                openFullscreenImage(user != null ? user.coverUrl : null,
                        (user != null ? user.displayName + "'s Cover Photo" : "Cover Photo"), false);
            }
        };
        cover.setOnClickListener(v -> openFullscreenImage(user != null ? user.coverUrl : null,
                (user != null ? user.displayName + "'s Cover Photo" : "Cover Photo"), false));
        coverCamBtn.setOnClickListener(coverClick);

        avatar.setOnClickListener(v -> {
            if (mine && !viewAsPublic) {
                boolean guarded = user != null && user.profilePicGuard;
                Ui.choose(this, "Profile picture (DP)", new String[]{
                        "👁 View full profile picture (HD)",
                        "📷 Select new profile picture",
                        guarded ? "🛡️ Turn OFF Profile Picture Guard" : "🛡️ Turn ON Profile Picture Guard",
                        "📖 View active story"
                }, w -> {
                    if (w == 0) {
                        openFullscreenImage(user != null ? user.photoUrl : null,
                                (user != null ? user.displayName + "'s Profile Picture" : "Profile Picture"), false);
                    } else if (w == 1) {
                        pickImage(REQ_PICK_AVATAR);
                    } else if (w == 2) {
                        boolean next = !guarded;
                        Map<String, Object> up = new HashMap<>();
                        up.put("profilePicGuard", next);
                        Fb.patchMe(up);
                        if (user != null) { user.profilePicGuard = next; bindUser(); }
                        Ui.toast(this, next
                                ? "🛡️ Profile Picture Guard turned ON!"
                                : "Profile Picture Guard turned OFF");
                    } else {
                        Intent si = new Intent(this, StoryViewerActivity.class);
                        si.putExtra("author", uid);
                        startActivity(si);
                    }
                });
            } else {
                boolean guarded = user != null && user.profilePicGuard;
                openFullscreenImage(user != null ? user.photoUrl : null,
                        (user != null ? user.displayName + "'s Profile Picture" : "Profile Picture"),
                        guarded);
            }
        });
        avatarCamBtn.setOnClickListener(v -> {
            if (mine && !viewAsPublic) pickImage(REQ_PICK_AVATAR);
        });

        showTab(0);

        // ⚡ 0.0ms Instant Hydration from FastCache!
        Models.User cachedUser = mine ? Fb.me : FastCache.getUser(uid);
        if (cachedUser != null) {
            user = cachedUser;
            bindUser();
        }
        List<Models.Post> cachedPosts = new ArrayList<>();
        for (Models.Post p : FastCache.getFeed()) {
            if (p != null && uid.equals(p.authorId)) cachedPosts.add(p);
        }
        if (!cachedPosts.isEmpty()) {
            allLoadedPosts.addAll(cachedPosts);
            userPostCount = cachedPosts.size();
            postsAdapter.setPosts(cachedPosts);
            if (user != null) updateStatsLine();
        }
    }

    private void pickImage(int reqCode) {
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.setType("image/*");
        startActivityForResult(Intent.createChooser(pick, "Choose image"), reqCode);
    }

    private void openFullscreenImage(String url) {
        openFullscreenImage(url, user != null ? user.displayName : "Photo", false);
    }

    private void openFullscreenImage(String url, String title, boolean guarded) {
        if (url == null || url.isEmpty()) {
            if (mine) pickImage(REQ_PICK_AVATAR);
            else Ui.toast(this, "No profile photo uploaded yet");
            return;
        }
        Intent i = new Intent(this, FullscreenMediaActivity.class);
        i.putExtra("type", "image");
        i.putExtra("url", url);
        i.putExtra("title", title);
        i.putExtra("guarded", guarded);
        startActivity(i);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null || !mine) return;
        Uri uri = data.getData();
        boolean isAvatar = (req == REQ_PICK_AVATAR);
        Ui.toast(this, isAvatar ? "Uploading HD profile photo…" : "Uploading HD cover photo…");
        String path = (isAvatar ? "avatars/" : "covers/") + uid + "_" + System.currentTimeMillis() + ".jpg";
        Cloudinary.uploadOrFirebase(this, path, uri)
                .addOnSuccessListener(url -> {
                    Map<String, Object> up = new HashMap<>();
                    up.put(isAvatar ? "photoUrl" : "coverUrl", url);
                    Fb.patchMe(up);
                    if (user != null) {
                        if (isAvatar) user.photoUrl = url;
                        else user.coverUrl = url;
                        bindUser();
                    }
                    // Facebook Lite signature feature: Share full DP / Cover update onto News Feed!
                    if (com.friendbook.core.Prefs.shareDpToFeed(ProfileActivity.this)) {
                        Map<String, Object> dpPost = Fb.map(
                                "authorId", uid,
                                "authorName", Fb.myName(),
                                "authorPhotoUrl", isAvatar ? url : Fb.myPhoto(),
                                "authorVerified", Fb.me != null && Fb.me.isVerified,
                                "authorTickLevel", Fb.me != null ? Fb.me.tickLevel : "none",
                                "content", "",
                                "feeling", isAvatar ? "updated their profile picture" : "updated their cover photo",
                                "mediaType", "image",
                                "mediaUrl", url,
                                "privacy", "Public",
                                "likes", 0, "comments", 0, "shares", 0,
                                "savedBy", new ArrayList<String>(),
                                "createdAt", FieldValue.serverTimestamp());
                        Fb.db().collection("posts").add(dpPost);
                    }
                    Ui.toast(ProfileActivity.this, isAvatar
                            ? "Profile picture updated & shared in full HD ✓"
                            : "Cover photo updated ✓");
                })
                .addOnFailureListener(err ->
                        Ui.toast(ProfileActivity.this, "Upload failed: " + err.getMessage()));
    }

    private void showProfileSearchDialog() {
        EditText e = Ui.input(this, "Search posts on this profile…");
        e.setText(profileSearchQuery);
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 18, 12, 18, 4);
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("🔍 Search profile posts")
                .setView(box)
                .setPositiveButton("Search", (d, w) -> {
                    profileSearchQuery = e.getText().toString().trim();
                    showTab(0);
                    applyPostsFilter();
                })
                .setNeutralButton("Clear", (d, w) -> {
                    profileSearchQuery = "";
                    searchFilterBanner.setVisibility(View.GONE);
                    applyPostsFilter();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private View gap() {
        View v = new View(this);
        v.setBackgroundColor(Ui.bg(this));
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(this, 6)));
        return v;
    }

    private void showTab(int idx) {
        activeTab = idx;
        allTabBox.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        reelsBox.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        photosBox.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);
        friendsBox.setVisibility(idx == 3 ? View.VISIBLE : View.GONE);
        aboutBox.setVisibility(idx == 4 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < tabLabels.length; i++) {
            boolean on = i == idx;
            tabLabels[i].setTextColor(on ? Ui.BLUE : Ui.sub(this));
            tabBars[i].setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        }
        if (idx == 3) renderFriendsTab();
        if (idx == 4) renderAbout();
    }

    @Override
    protected void onStart() {
        super.onStart();
        FastCache.addReactionObserver(reactionObserver);
        userReg = Fb.db().collection("users").document(uid)
                .addSnapshotListener((d, err) -> {
                    if (d == null || !d.exists()) {
                        nameT.setText("User not found");
                        return;
                    }
                    user = Models.User.fromDoc(d);
                    FastCache.putUser(user);
                    if (mine) FastCache.saveMe(this, user);
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
                if (online) {
                    presenceT.setText("🟢 Active now");
                    presenceT.setVisibility(View.VISIBLE);
                } else if (lastSeen > 0) {
                    presenceT.setText("Active " + Format.ago(lastSeen) + " ago");
                    presenceT.setVisibility(View.VISIBLE);
                } else {
                    presenceT.setVisibility(View.GONE);
                }
            });
        } else if (mine && me != null) {
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
        startPostsWatcher();
        startReelsWatcher();
    }

    private void startPostsWatcher() {
        postsReg = Fb.db().collection("posts").whereEqualTo("authorId", uid)
                .addSnapshotListener((snap, err) -> {
                    allLoadedPosts.clear();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap) {
                            Models.Post p = Models.Post.fromDoc(d);
                            if (!canSee(p)) continue;
                            allLoadedPosts.add(p);
                        }
                    }
                    Collections.sort(allLoadedPosts, (a, b2) -> Long.compare(b2.createdAt, a.createdAt));
                    String pin = user != null ? user.pinnedPostId : null;
                    if (pin != null && !pin.isEmpty()) {
                        for (int i = 0; i < allLoadedPosts.size(); i++) {
                            if (pin.equals(allLoadedPosts.get(i).id)) {
                                Models.Post pp = allLoadedPosts.remove(i);
                                allLoadedPosts.add(0, pp);
                                break;
                            }
                        }
                    }
                    userPostCount = allLoadedPosts.size();
                    if (user != null) updateStatsLine();
                    applyPostsFilter();

                    List<String> media = new ArrayList<>();
                    for (Models.Post p : allLoadedPosts) {
                        if (p.mediaUrls != null) media.addAll(p.mediaUrls);
                        if (p.mediaUrl != null && !p.mediaUrl.isEmpty() && !media.contains(p.mediaUrl)) {
                            media.add(p.mediaUrl);
                        }
                    }
                    photosAdapter.media.clear();
                    photosAdapter.media.addAll(media);
                    photosAdapter.notifyDataSetChanged();
                });
    }

    private void applyPostsFilter() {
        List<Models.Post> shown = new ArrayList<>();
        String q = profileSearchQuery == null ? "" : profileSearchQuery.trim().toLowerCase();
        for (Models.Post p : allLoadedPosts) {
            if (q.isEmpty() || (p.content != null && p.content.toLowerCase().contains(q))) {
                shown.add(p);
            }
        }
        if (!q.isEmpty()) {
            searchFilterBanner.setText("🔍 Filtering by \"" + profileSearchQuery + "\" (" + shown.size() + ") · Tap to clear ✕");
            searchFilterBanner.setVisibility(View.VISIBLE);
        } else {
            searchFilterBanner.setVisibility(View.GONE);
        }
        String pin = user != null ? user.pinnedPostId : null;
        boolean hasPin = q.isEmpty() && pin != null && !pin.isEmpty() && !shown.isEmpty()
                && pin.equals(shown.get(0).id);
        if (pinnedTag != null) pinnedTag.setVisibility(hasPin ? View.VISIBLE : View.GONE);
        postsAdapter.setPosts(shown);
    }

    private void startReelsWatcher() {
        reelsReg = Fb.db().collection("reels").whereEqualTo("authorId", uid)
                .addSnapshotListener((snap, err) -> {
                    List<Models.Reel> list = new ArrayList<>();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap.getDocuments()) {
                            list.add(Models.Reel.fromDoc(d));
                        }
                    }
                    Collections.sort(list, (a, b) -> Long.compare(b.createdAt, a.createdAt));
                    reelsAdapter.reels.clear();
                    reelsAdapter.reels.addAll(list);
                    reelsAdapter.notifyDataSetChanged();
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        FastCache.removeReactionObserver(reactionObserver);
        if (userReg != null) userReg.remove();
        if (friendsReg != null) friendsReg.remove();
        if (outReg != null) outReg.remove();
        if (inReg != null) inReg.remove();
        if (postsReg != null) postsReg.remove();
        if (reelsReg != null) reelsReg.remove();
        if (subReg != null) subReg.remove();
        if (presenceH != null) presenceH.close();
    }

    private void updateStatsLine() {
        if (user == null) return;
        int followers = Math.max(user.followers != null ? user.followers.size() : 0, user.friendsCount);
        int following = user.following != null ? user.following.size() : 0;

        SpannableStringBuilder sb = new SpannableStringBuilder();
        appendBoldStat(sb, Format.count(followers), " followers · ");
        appendBoldStat(sb, Format.count(following), " following · ");
        appendBoldStat(sb, Format.count(userPostCount), userPostCount == 1 ? " post" : " posts");
        statsT.setText(sb);
        statsT.setOnClickListener(v -> Ui.choose(this, user.displayName, new String[]{
                "👥 Followers (" + Format.count(user.followers != null ? user.followers.size() : 0) + ")",
                "🚶 Following (" + Format.count(following) + ")",
                "🤝 Friends (" + Format.count(user.friendsCount) + ")"
        }, w -> {
            if (w == 2) {
                showTab(3);
            } else {
                Intent i = new Intent(this, FollowListActivity.class);
                i.putExtra("uid", uid);
                i.putExtra("mode", w == 0 ? "followers" : "following");
                startActivity(i);
            }
        }));
    }

    private void appendBoldStat(SpannableStringBuilder sb, String num, String label) {
        int start = sb.length();
        sb.append(num);
        sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(Ui.txt(this)), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(label);
    }

    private void bindUser() {
        if (user == null) return;
        String display = user.displayName != null && !user.displayName.isEmpty() ? user.displayName : "friendbook User";
        topTitleTv.setText(display);
        nameT.setText(display);

        // Verified tick (shows blue/gold/diamond tick, or verified status!)
        String tickLvl = user.tickLevel;
        if ((tickLvl == null || "none".equals(tickLvl)) && user.isVerified) tickLvl = "blue";
        Icons.setTickBadge(nameTick, tickLvl, true);

        updateStatsLine();

        boolean showCreatorCat = user.professionalMode || mine;
        categoryT.setVisibility(showCreatorCat ? View.VISIBLE : View.GONE);
        categoryT.setText(user.isAdmin ? "🎬 Digital creator · 🛡 Official" : "🎬 Digital creator");

        boolean hasBio = user.bio != null && !user.bio.isEmpty();
        bioT.setText(hasBio ? user.bio : "");
        bioT.setVisibility(hasBio ? View.VISIBLE : View.GONE);

        Ui.avatarLarge(this, avatar, user.photoUrl);
        if (guardBadge != null) {
            guardBadge.setVisibility(user.profilePicGuard ? View.VISIBLE : View.GONE);
        }
        if (user.coverUrl != null && !user.coverUrl.isEmpty()) {
            Ui.img(this, cover, user.coverUrl);
        } else {
            cover.setImageDrawable(Ui.gradient(0xFF2851A3, 0xFF1877F2, 0));
        }

        coverCamBtn.setVisibility(mine && !viewAsPublic ? View.VISIBLE : View.GONE);
        avatarCamBtn.setVisibility(mine && !viewAsPublic ? View.VISIBLE : View.GONE);
        editTopIcon.setVisibility(mine && !viewAsPublic ? View.VISIBLE : View.GONE);

        // 🔒 Profile lock check
        boolean stranger = (!mine || viewAsPublic) && !friendIds.contains(uid);
        boolean locked = user.profileLocked && stranger;
        lockBanner.setVisibility(locked ? View.VISIBLE : View.GONE);
        if (tabsRow != null) tabsRow.setVisibility(locked ? View.GONE : View.VISIBLE);

        renderPersonalDetailsAndHighlights(locked);
        if (locked) {
            allTabBox.setVisibility(View.GONE);
            reelsBox.setVisibility(View.GONE);
            photosBox.setVisibility(View.GONE);
            friendsBox.setVisibility(View.GONE);
            aboutBox.setVisibility(View.GONE);
        } else {
            showTab(activeTab);
        }

        if (mine && !viewAsPublic) {
            ownActionsCol.setVisibility(View.VISIBLE);
            visitorActionsRow.setVisibility(View.GONE);
            moneyRow.setVisibility(View.VISIBLE);
            boostBtn.setVisibility(View.VISIBLE);
            subscribeBtn.setVisibility(View.GONE);
            tickBtn.setVisibility(user.tickRank() >= 3 ? View.GONE : View.VISIBLE);
        } else {
            ownActionsCol.setVisibility(View.GONE);
            visitorActionsRow.setVisibility(View.VISIBLE);
            boostBtn.setVisibility(View.GONE);
            tickBtn.setVisibility(View.GONE);
            moneyRow.setVisibility(user.subscriptionsEnabled ? View.VISIBLE : View.GONE);
            subscribeBtn.setVisibility(user.subscriptionsEnabled ? View.VISIBLE : View.GONE);
            updateSubscribeButton();
            refreshButtons();
        }
    }

    /**
     * Exact Facebook Lite Screenshot 1 "Personal details" section inside the All tab:
     * Bold "Personal details" + ✏️ edit icon, 📍 City 🔒, 🎂 Formatted Birthday, 💞 Relationship, "See more details".
     */
    private void renderPersonalDetailsAndHighlights(boolean locked) {
        personalDetailsCard.removeAllViews();
        highlightsRow.removeAllViews();
        if (locked || user == null) {
            personalDetailsCard.setVisibility(View.GONE);
            highlightsWrap.setVisibility(View.GONE);
            return;
        }

        LinearLayout headerRow = Ui.row(this);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.addView(Ui.tvBold(this, "Personal details", 17, Ui.txt(this)),
                new LinearLayout.LayoutParams(0, -2, 1));
        if (mine && !viewAsPublic) {
            TextView pencil = Ui.tv(this, "✎", 19, Ui.txt(this));
            Ui.pad(pencil, 8, 2, 4, 2);
            pencil.setOnClickListener(v -> startActivity(new Intent(this, EditProfileActivity.class)));
            headerRow.addView(pencil);
        }
        personalDetailsCard.addView(headerRow);

        int rows = 0;
        String city = (user.currentCity != null && !user.currentCity.isEmpty())
                ? user.currentCity : user.hometown;
        if (city != null && !city.isEmpty()) {
            boolean privLock = !"Public".equalsIgnoreCase(user.contactInfoPrivacy);
            addPersonalDetailRow(personalDetailsCard, "📍", city + (privLock ? "  🔒" : ""));
            rows++;
        }
        if (user.work != null && !user.work.isEmpty()) {
            addPersonalDetailRow(personalDetailsCard, "💼", "Works at " + user.work);
            rows++;
        }
        if (user.education != null && !user.education.isEmpty()) {
            addPersonalDetailRow(personalDetailsCard, "🎓", "Studied at " + user.education);
            rows++;
        }
        boolean canSeeContact = (mine && !viewAsPublic)
                || "Public".equalsIgnoreCase(user.contactInfoPrivacy)
                || ("Friends".equalsIgnoreCase(user.contactInfoPrivacy) && "friends".equals(friendState) && !viewAsPublic);
        if (canSeeContact && user.birthday != null && !user.birthday.isEmpty()) {
            addPersonalDetailRow(personalDetailsCard, "🎂", formatPrettyBirthday(user.birthday));
            rows++;
        }
        if (user.relationship != null && !user.relationship.isEmpty()) {
            addPersonalDetailRow(personalDetailsCard, "💞", user.relationship);
            rows++;
        }
        if (user.website != null && !user.website.isEmpty()) {
            LinearLayout wRow = addPersonalDetailRow(personalDetailsCard, "🌐", user.website);
            wRow.setOnClickListener(v -> Ui.openUrl(this,
                    user.website.startsWith("http") ? user.website : "https://" + user.website));
            rows++;
        }
        if (rows == 0 && mine && !viewAsPublic) {
            LinearLayout addRow = addPersonalDetailRow(personalDetailsCard, "➕",
                    "Add city, birthday & relationship status");
            addRow.setOnClickListener(v -> startActivity(new Intent(this, EditProfileActivity.class)));
            rows++;
        }

        // "See more details" link (exact FB Lite Screenshot 1!)
        TextView seeMore = Ui.tv(this, "See more details", 14.5f, Ui.sub(this));
        seeMore.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 2));
        seeMore.setOnClickListener(v -> showTab(4));
        personalDetailsCard.addView(seeMore);
        personalDetailsCard.setVisibility(rows > 0 || (mine && !viewAsPublic) ? View.VISIBLE : View.GONE);

        // Story Highlights rail
        List<String> hiIds = user.highlightStoryIds != null ? user.highlightStoryIds : new ArrayList<>();
        if (hiIds.isEmpty() && (!mine || viewAsPublic)) {
            highlightsWrap.setVisibility(View.GONE);
            return;
        }
        highlightsWrap.setVisibility(View.VISIBLE);
        if (mine && !viewAsPublic) {
            LinearLayout addBubble = Ui.col(this);
            addBubble.setGravity(Gravity.CENTER);
            TextView plus = Ui.tvBold(this, "＋", 22, Ui.txt(this));
            plus.setGravity(Gravity.CENTER);
            plus.setBackground(Ui.roundStroke(this, Ui.inputBg(this), 31, Ui.div(this), 1));
            addBubble.addView(plus, new LinearLayout.LayoutParams(Ui.dp(this, 60), Ui.dp(this, 60)));
            addBubble.addView(Ui.tv(this, "New", 11, Ui.sub(this)));
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
            alp.rightMargin = Ui.dp(this, 10);
            addBubble.setOnClickListener(v -> startActivity(new Intent(this, ArchiveTrashActivity.class)));
            highlightsRow.addView(addBubble, alp);
        }
        for (final String sid : hiIds) {
            Fb.db().collection("stories").document(sid).get().addOnSuccessListener(sd -> {
                if (!sd.exists()) return;
                Models.Story s = Models.Story.fromDoc(sd);
                LinearLayout b = Ui.col(this);
                b.setGravity(Gravity.CENTER);
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                if (s.mediaUrl != null && !s.mediaUrl.isEmpty()) Ui.avatar(this, iv, s.mediaUrl);
                else iv.setBackground(Ui.circle(s.bgColor != 0 ? s.bgColor : Ui.BLUE));
                b.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 60), Ui.dp(this, 60)));
                TextView cap = Ui.tv(this, s.caption == null || s.caption.isEmpty() ? "Story" : s.caption, 11, Ui.txt(this));
                cap.setSingleLine(true);
                cap.setMaxWidth(Ui.dp(this, 68));
                b.addView(cap);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
                blp.rightMargin = Ui.dp(this, 10);
                b.setOnClickListener(v -> {
                    Intent in = new Intent(this, StoryViewerActivity.class);
                    in.putExtra("author", uid);
                    in.putExtra("story_id", sid);
                    startActivity(in);
                });
                highlightsRow.addView(b, blp);
            });
        }
    }

    private LinearLayout addPersonalDetailRow(LinearLayout parent, String icon, String text) {
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 10), 0, 0);
        TextView ic = Ui.tv(this, icon, 17, Ui.txt(this));
        ic.setWidth(Ui.dp(this, 30));
        row.addView(ic);
        TextView tv = Ui.tv(this, text, 15, Ui.txt(this));
        row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1));
        parent.addView(row);
        return row;
    }

    /** Formats "05/02/2004" or "2004-02-05" into "February 5, 2004" (exact Screenshot 1 style!). */
    private String formatPrettyBirthday(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        try {
            String[] months = new DateFormatSymbols().getMonths();
            if (s.contains("/")) {
                String[] p = s.split("/");
                if (p.length == 3) {
                    int d = Integer.parseInt(p[0].trim());
                    int m = Integer.parseInt(p[1].trim());
                    String y = p[2].trim();
                    if (m >= 1 && m <= 12) return months[m - 1] + " " + d + ", " + y;
                }
            } else if (s.contains("-")) {
                String[] p = s.split("-");
                if (p.length == 3 && p[0].length() == 4) {
                    int m = Integer.parseInt(p[1].trim());
                    int d = Integer.parseInt(p[2].trim());
                    if (m >= 1 && m <= 12) return months[m - 1] + " " + d + ", " + p[0].trim();
                }
            }
        } catch (Throwable ignored) { }
        return s;
    }

    private boolean canSee(Models.Post p) {
        if (p.archived || p.trashedAt > 0) return false;
        String me = Fb.uid();
        if (mine && !viewAsPublic) return true;
        if (viewAsPublic) return "Public".equalsIgnoreCase(p.privacy) || p.privacy == null || p.privacy.isEmpty();
        if (p.authorId != null && p.authorId.equals(me)) return true;
        if (user != null && user.restrictedUids != null && me != null && user.restrictedUids.contains(me)) {
            return "Public".equalsIgnoreCase(p.privacy) || p.privacy == null || p.privacy.isEmpty();
        }
        if ("Only me".equalsIgnoreCase(p.privacy)) return false;
        if ("Friends".equalsIgnoreCase(p.privacy)) return friendIds.contains(me) || "friends".equals(friendState);
        return true;
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
        Intent i = new Intent(this, PayRequestActivity.class);
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
                    Intent i = new Intent(this, PayRequestActivity.class);
                    i.putExtra("type", "boost_profile");
                    i.putExtra("amount", Admin.PRICE_BOOST_PROFILE);
                    i.putExtra("title", "Boost Profile — 24h");
                    startActivity(i);
                });
    }

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
                        DocumentSnapshot d = snap.getDocuments().get(0);
                        Map<String, Object> m = d.getData();
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
        if (user != null) bindUser();
    }

    private void refreshButtons() {
        if (mine || user == null) return;
        switch (friendState) {
            case "friends": primaryBtn.setText("✓ Friends"); break;
            case "outgoing": primaryBtn.setText("✓ Requested"); break;
            case "incoming": primaryBtn.setText("✔ Respond"); break;
            default: primaryBtn.setText("＋ Add friend"); break;
        }
        boolean following = user.followers != null && user.followers.contains(Fb.uid());
        secondaryBtn.setText(following ? "Following ✓" : "＋ Follow");
    }

    private void primary() {
        if (user == null) return;
        if (mine) {
            startActivity(new Intent(this, EditProfileActivity.class));
            return;
        }
        switch (friendState) {
            case "friends":
                Ui.choose(this, user.displayName, new String[]{"💬 Message", "Unfriend"}, w -> {
                    if (w == 0) message();
                    else unfriend();
                });
                break;
            case "outgoing":
                Ui.confirm(this, "Cancel friend request?", "", "Cancel request", () ->
                        Fb.db().collection("friend_requests")
                                .whereEqualTo("fromUid", Fb.uid()).whereEqualTo("toUid", uid)
                                .get().addOnSuccessListener(snap -> {
                                    for (DocumentSnapshot d : snap) d.getReference().delete();
                                }));
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
            startActivity(new Intent(this, AddStoryActivity.class));
            return;
        }
        String me = Fb.uid();
        if (me == null) return;
        boolean following = user.followers != null && user.followers.contains(me);
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
            boolean isFav = Fb.me != null && Fb.me.favoriteUids != null
                    && Fb.me.favoriteUids.contains(uid);
            pm.getMenu().add(isFav ? "✖ Remove from Favorites" : "⭐ Add to Favorites");
            boolean isRestr = Fb.me != null && Fb.me.restrictedUids != null
                    && Fb.me.restrictedUids.contains(uid);
            pm.getMenu().add(isRestr ? "🔓 Remove from Restricted list" : "🙈 Add to Restricted list");
            pm.getMenu().add("📱 Show QR code");
            if (Admin.isAdmin()) {
                pm.getMenu().add("🛡 ✓ Tick level (admin)…");
                pm.getMenu().add(user != null && user.banned
                        ? "🛡 ✓ Unban user" : "🛡 🚫 Ban user");
                pm.getMenu().add("🛡 ⭐ Adjust stars (admin)…");
                pm.getMenu().add("🛡 📢 Send official message (admin)…");
                pm.getMenu().add("🛡 📊 User stats (admin)…");
            }
        } else {
            pm.getMenu().add("✏️ Edit profile & personal details");
            pm.getMenu().add("📊 Creator Dashboard");
            pm.getMenu().add(user != null && user.professionalMode
                    ? "🎬 Turn off Professional Mode" : "🎬 Turn on Professional Mode");
            pm.getMenu().add("👁 View as Public");
            pm.getMenu().add(user != null && user.profileLocked ? "🔓 Unlock profile" : "🔒 Lock profile");
            pm.getMenu().add("📦 Archive & Recycle Bin");
            pm.getMenu().add("🔗 Copy profile link");
            pm.getMenu().add("📱 My Profile QR code");
            pm.getMenu().add("📷 Change profile photo");
            pm.getMenu().add("🖼 Change cover photo");
        }
        pm.setOnMenuItemClickListener(item -> {
            String t = item.getTitle().toString();
            if (t.contains("Message") && !t.contains("official")) message();
            else if (t.contains("Creator Dashboard")) CreatorDashboardDialog.show(this, this::bindUser);
            else if (t.contains("Professional Mode")) {
                boolean next = !(user != null && user.professionalMode);
                Fb.patchMe(Fb.map("professionalMode", next));
                Ui.toast(this, next ? "Professional Mode ON 🎬" : "Professional Mode OFF");
            } else if (t.contains("Poke")) PokesActivity.sendPoke(
                    this, uid, user != null ? user.displayName : "them");
            else if (t.contains("Unblock")) Fb.patchMe(Fb.map("blockedUsers", FieldValue.arrayRemove(uid)));
            else if (t.contains("Block") && !t.contains("Unblock")) {
                Ui.confirm(this, "Block " + (user != null ? user.displayName : "") + "?", "", "Block", () ->
                        Fb.patchMe(Fb.map("blockedUsers", FieldValue.arrayUnion(uid))));
            } else if (t.contains("See friendship")) showFriendship();
            else if (t.contains("Copy profile link")) {
                Ui.copy(this, com.friendbook.core.AdsConfig.OFFICIAL_WEBSITE_URL + "/?user=" + uid);
            } else if (t.contains("Tick level (admin)")) {
                Ui.choose(this, "🛡 Set tick for " + (user != null ? user.displayName : "user"),
                        new String[]{"✓ Blue tick", "✨ Gold tick", "💎 Diamond tick", "✕ Remove tick"},
                        w -> Admin.setTickLevel(this, uid,
                                user != null ? user.displayName : "user",
                                w == 0 ? "blue" : w == 1 ? "gold" : w == 2 ? "diamond" : "none"));
            } else if (t.contains("Ban user") || t.contains("Unban user")) {
                boolean unban = t.contains("Unban");
                String name = user != null ? user.displayName : "this user";
                Ui.confirm(this, unban ? "✓ Unban " + name + "?" : "🚫 Ban " + name + "?",
                        unban ? "They can use friendbook again."
                                : "They will be signed out and blocked from using friendbook.",
                        unban ? "Unban" : "Ban", () ->
                                Fb.db().collection("users").document(uid)
                                        .set(Fb.map("banned", !unban), SetOptions.merge())
                                        .addOnSuccessListener(x -> {
                                            Ui.toast(this, unban ? "Unbanned ✓" : "Banned 🚫");
                                            if (!unban) Notify.send(uid, "ban", "Your account was suspended by an admin.", null);
                                        })
                                        .addOnFailureListener(e -> Ui.toast(this, e.getMessage())));
            } else if (t.contains("Adjust stars")) adminAdjustStars();
            else if (t.contains("official message")) adminMessageDialog();
            else if (t.contains("User stats (admin)")) adminStats();
            else if (t.contains("Favorites")) toggleFavorite();
            else if (t.contains("Restricted list")) {
                boolean rem = Fb.me != null && Fb.me.restrictedUids != null && Fb.me.restrictedUids.contains(uid);
                Fb.db().collection("users").document(Fb.uid())
                        .update("restrictedUids", rem ? FieldValue.arrayRemove(uid) : FieldValue.arrayUnion(uid));
                Ui.toast(this, rem ? "Removed from Restricted list" : "Added to Restricted list");
            } else if (t.contains("QR code")) {
                Ui.showQrDialog(this, user != null ? user.displayName : "friendbook",
                        com.friendbook.core.AdsConfig.OFFICIAL_WEBSITE_URL + "/?user=" + uid);
            } else if (t.contains("View as Public")) {
                viewAsPublic = true;
                viewAsBanner.setVisibility(View.VISIBLE);
                if (user != null) bindUser();
                if (postsReg != null) { postsReg.remove(); startPostsWatcher(); }
            } else if (t.contains("Lock profile") || t.contains("Unlock profile")) {
                boolean next = !(user != null && user.profileLocked);
                Fb.patchMe(Fb.map("profileLocked", next));
                Ui.toast(this, next ? "Profile locked 🔒" : "Profile unlocked 🔓");
            } else if (t.contains("Archive & Recycle Bin")) {
                startActivity(new Intent(this, ArchiveTrashActivity.class));
            } else if (t.contains("Report")) {
                Fb.db().collection("reports").add(Fb.map("uid", uid, "reportedBy", Fb.uid(),
                        "type", "profile", "createdAt", FieldValue.serverTimestamp()));
                Ui.toast(this, "Reported");
            } else if (t.contains("Change profile photo")) {
                pickImage(REQ_PICK_AVATAR);
            } else if (t.contains("Change cover photo")) {
                pickImage(REQ_PICK_COVER);
            } else if (t.contains("Edit profile")) {
                startActivity(new Intent(this, EditProfileActivity.class));
            }
            return true;
        });
        pm.show();
    }

    private void toggleFavorite() {
        if (Fb.me == null) return;
        boolean removing = Fb.me.favoriteUids != null && Fb.me.favoriteUids.contains(uid);
        Fb.db().collection("users").document(Fb.uid())
                .update("favoriteUids", removing
                        ? FieldValue.arrayRemove(uid)
                        : FieldValue.arrayUnion(uid))
                .addOnSuccessListener(x -> {
                    if (removing) Fb.me.favoriteUids.remove(uid);
                    else Fb.me.favoriteUids.add(uid);
                    Ui.toast(this, removing ? "Removed from Favorites"
                            : "⭐ Favorites — " + firstOnly(user != null ? user.displayName : "")
                            + "'s posts show first");
                })
                .addOnFailureListener(e -> Ui.toast(this, e.getMessage()));
    }

    private void adminAdjustStars() {
        int cur = user != null ? user.stars : 0;
        EditText e = Ui.input(this, "e.g. 50 or -20 (current: " + cur + " ⭐)");
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 20, 10, 20, 0);
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("🛡 ⭐ Adjust stars")
                .setMessage("For " + (user != null ? user.displayName : "user")
                        + " · current balance " + cur + " ⭐")
                .setView(box)
                .setPositiveButton("Apply", (d, w) -> {
                    String s = e.getText().toString().trim();
                    int delta;
                    try { delta = Integer.parseInt(s); } catch (Exception ex) {
                        Ui.toast(this, "Enter a number like 50 or -20"); return;
                    }
                    if (delta == 0) return;
                    Fb.db().collection("users").document(uid)
                            .update("stars", FieldValue.increment(delta))
                            .addOnSuccessListener(x -> {
                                if (user != null) user.stars += delta;
                                Notify.send(uid, "stars", "⭐ " + delta + " Stars adjusted on your account.", null);
                                Ui.toast(this, "Stars updated ✓ (" + (cur + delta) + ")");
                            })
                            .addOnFailureListener(err -> Ui.toast(this, err.getMessage()));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void adminMessageDialog() {
        EditText e = Ui.multi(this, "Message from friendbook Team…", 3);
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 20, 10, 20, 0);
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("🛡 📢 Message user")
                .setMessage("Delivered to " + (user != null ? user.displayName : "user")
                        + "'s notifications as from friendbook Team.")
                .setView(box)
                .setPositiveButton("Send", (d, w) -> {
                    String text = e.getText().toString().trim();
                    if (text.isEmpty()) return;
                    Notify.send(uid, "team", "🛡 " + text, null);
                    Ui.toast(this, "Sent ✓");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void adminStats() {
        Ui.toast(this, "Counting posts…");
        Fb.db().collection("posts").whereEqualTo("authorId", uid).limit(100).get()
                .addOnSuccessListener(snap -> {
                    int pcount = snap.size();
                    Ui.info(this, "🛡 📊 " + (user != null ? user.displayName : "User"),
                            "• Posts: " + pcount + (pcount >= 100 ? "+" : "") + "\n"
                            + "• Friends: " + (user != null ? user.friendsCount : 0) + "\n"
                            + "• Followers: " + (user != null && user.followers != null
                                    ? user.followers.size() : 0) + "\n"
                            + "• Stars: " + (user != null ? user.stars : 0) + " ⭐\n"
                            + "• Tick: " + (user != null ? Admin.tickLabel(user.tickLevel) : "none") + "\n"
                            + "• ID: " + uid);
                })
                .addOnFailureListener(e -> Ui.toast(this, e.getMessage()));
    }

    private void showFriendship() {
        if (user == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(user.displayName).append(" & ").append(Fb.myName()).append("\n\n");
        int mutual = 0;
        if (Fb.me != null)
            for (String f : user.followers)
                if (Fb.me.following.contains(f) || Fb.me.followers.contains(f)) mutual++;
        sb.append("👥 ").append(mutual).append(" mutual connections\n");
        sb.append(friendState.equals("friends") ? "✓ You are friends\n" : "Not friends yet\n");
        if (user.createdAt > 0) sb.append("🗓 Joined ").append(Format.dateTime(user.createdAt));
        Ui.info(this, "💙 Friendship", sb.toString());
    }

    private void renderAbout() {
        aboutBox.removeAllViews();
        if (user == null) return;
        LinearLayout card = Ui.col(this);
        card.setBackgroundColor(Ui.card(this));
        Ui.pad(card, 14, 14, 14, 16);
        card.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));

        LinearLayout hRow = Ui.row(this);
        hRow.setGravity(Gravity.CENTER_VERTICAL);
        hRow.addView(Ui.tvBold(this, "About " + firstOnly(user.displayName), 17, Ui.txt(this)),
                new LinearLayout.LayoutParams(0, -2, 1));
        if (mine && !viewAsPublic) {
            TextView ed = Ui.tvBold(this, "Edit", 14, Ui.BLUE);
            ed.setOnClickListener(v -> startActivity(new Intent(this, EditProfileActivity.class)));
            hRow.addView(ed);
        }
        card.addView(hRow);

        addAboutRow(card, "🎬", user.professionalMode ? "Digital creator · Professional Mode" : "Personal Profile");
        addAboutRow(card, "📝", user.bio == null || user.bio.isEmpty() ? "No bio yet" : user.bio);
        if (user.pronouns != null && !user.pronouns.isEmpty()) addAboutRow(card, "💬", user.pronouns);
        if (user.work != null && !user.work.isEmpty()) addAboutRow(card, "💼", "Works at " + user.work);
        if (user.education != null && !user.education.isEmpty()) addAboutRow(card, "🎓", "Studied at " + user.education);
        if (user.currentCity != null && !user.currentCity.isEmpty()) addAboutRow(card, "📍", "Lives in " + user.currentCity);
        if (user.hometown != null && !user.hometown.isEmpty()) addAboutRow(card, "🏠", "From " + user.hometown);
        if (user.relationship != null && !user.relationship.isEmpty()) addAboutRow(card, "💞", user.relationship);
        if (user.website != null && !user.website.isEmpty()) addAboutRow(card, "🌐", user.website);
        addAboutRow(card, "👥", Format.count(user.friendsCount) + " friends");
        addAboutRow(card, "🚶", Format.count(user.followers.size()) + " followers · " + Format.count(user.following.size()) + " following");

        boolean canSeeContact = (mine && !viewAsPublic)
                || "Public".equalsIgnoreCase(user.contactInfoPrivacy)
                || ("Friends".equalsIgnoreCase(user.contactInfoPrivacy) && "friends".equals(friendState) && !viewAsPublic);
        if (canSeeContact && user.birthday != null && !user.birthday.isEmpty())
            addAboutRow(card, "🎂", formatPrettyBirthday(user.birthday));
        if (user.isAdmin) addAboutRow(card, "🛡", "friendbook Team");
        if (canSeeContact && user.email != null && !user.email.isEmpty())
            addAboutRow(card, "📧", user.email);
        if (user.createdAt > 0) addAboutRow(card, "🗓", "Joined " + Format.dateTime(user.createdAt));

        if (user.hobbies != null && !user.hobbies.isEmpty()) {
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout hrow = Ui.row(this);
            hrow.setPadding(0, Ui.dp(this, 10), 0, 0);
            for (String hb : user.hobbies) {
                TextView pill = Ui.roundChip(this, "🎯 " + hb, Ui.inputBg(this), Ui.txt(this), 12);
                Ui.pad(pill, 10, 5, 10, 5);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.rightMargin = Ui.dp(this, 6);
                hrow.addView(pill, lp);
            }
            hs.addView(hrow);
            card.addView(hs);
        }
        aboutBox.addView(card);
    }

    private void addAboutRow(LinearLayout card, String icon, String text) {
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 10), 0, 0);
        TextView e = Ui.tv(this, icon, 16, Ui.sub(this));
        e.setPadding(0, 0, Ui.dp(this, 12), 0);
        row.addView(e);
        TextView t = Ui.tv(this, text, 14.5f, Ui.txt(this));
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(row);
    }

    private void renderFriendsTab() {
        if (friendsBox == null || getApplicationContext() == null) return;
        List<UsersAdapter.Row> rows = new ArrayList<>();
        boolean canSeeFriends = (mine && !viewAsPublic)
                || user == null
                || "Public".equalsIgnoreCase(user.friendsListPrivacy)
                || ("Friends".equalsIgnoreCase(user.friendsListPrivacy) && "friends".equals(friendState) && !viewAsPublic);
        if (!canSeeFriends) {
            friendsAdapter.setData(rows);
            return;
        }
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

    /** 3-column media grid for the Photos tab. */
    private class PhotosAdapter extends RecyclerView.Adapter<PhotosAdapter.VH> {
        final List<String> media = new ArrayList<>();

        class VH extends RecyclerView.ViewHolder {
            ImageView iv;
            VH(View v) { super(v); iv = (ImageView) v; }
        }

        @NonNull
        @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            ImageView iv = new ImageView(p.getContext());
            int s = (getResources().getDisplayMetrics().widthPixels - Ui.dp(ProfileActivity.this, 6)) / 3;
            iv.setLayoutParams(new RecyclerView.LayoutParams(s, s));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            int gap = Ui.dp(ProfileActivity.this, 1);
            iv.setPadding(gap, gap, gap, gap);
            return new VH(iv);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int i) {
            String url = media.get(i);
            Ui.img(ProfileActivity.this, h.iv, url);
            h.iv.setOnClickListener(v -> openFullscreenImage(url));
        }

        @Override public int getItemCount() { return media.size(); }
    }

    /** 3-column Reels grid for the Reels tab on Profile! */
    private class ReelsGridAdapter extends RecyclerView.Adapter<ReelsGridAdapter.RVH> {
        final List<Models.Reel> reels = new ArrayList<>();

        class RVH extends RecyclerView.ViewHolder {
            ImageView thumb;
            TextView countTv;
            RVH(FrameLayout f, ImageView t, TextView c) {
                super(f); thumb = t; countTv = c;
            }
        }

        @NonNull
        @Override public RVH onCreateViewHolder(@NonNull ViewGroup p, int viewType) {
            int w = (getResources().getDisplayMetrics().widthPixels - Ui.dp(ProfileActivity.this, 6)) / 3;
            int h = (int) (w * 1.55f);
            FrameLayout cell = new FrameLayout(p.getContext());
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(w, h);
            lp.setMargins(1, 1, 1, 1);
            cell.setLayoutParams(lp);
            cell.setBackgroundColor(0xFF18191A);

            ImageView iv = new ImageView(p.getContext());
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cell.addView(iv, new FrameLayout.LayoutParams(-1, -1));

            TextView badge = Ui.tvBold(p.getContext(), "▶", 12, Color.WHITE);
            badge.setShadowLayer(4f, 0f, 1f, 0xCC000000);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
            blp.setMargins(Ui.dp(ProfileActivity.this, 6), 0, 0, Ui.dp(ProfileActivity.this, 6));
            cell.addView(badge, blp);
            return new RVH(cell, iv, badge);
        }

        @Override public void onBindViewHolder(@NonNull RVH h, int position) {
            Models.Reel r = reels.get(position);
            if (r.videoUrl != null && !r.videoUrl.isEmpty()) {
                Ui.img(ProfileActivity.this, h.thumb, r.videoUrl);
            }
            h.countTv.setText("▶ " + Format.count(Math.max(1, r.likes + r.comments)));
            h.itemView.setOnClickListener(v -> {
                Intent in = new Intent(ProfileActivity.this, com.friendbook.reels.ReelPlayerActivity.class);
                in.putExtra("reel_id", r.id);
                in.putExtra("is_post", r.isFromPost);
                in.putExtra("video_url", r.videoUrl);
                in.putExtra("author_id", r.authorId);
                in.putExtra("author_name", r.authorName);
                in.putExtra("author_photo", r.authorPhoto);
                in.putExtra("caption", r.caption);
                in.putExtra("likes", r.likes);
                in.putExtra("comments", r.comments);
                in.putExtra("shares", r.shares);
                startActivity(in);
            });
        }

        @Override public int getItemCount() { return reels.size(); }
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

    @Override public void createStory() {
        startActivity(new Intent(this, AddStoryActivity.class));
    }

    @Override public void openStoryGroup(com.friendbook.story.StoriesAdapter.Group g) { }
    @Override public void loadMore() { }
    @Override public boolean hasMore() { return false; }
}
