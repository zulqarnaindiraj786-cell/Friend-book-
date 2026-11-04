package com.friendbook.social.social;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.social.chat.ChatActivity;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Icons;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.friendbook.social.post.CreatePostActivity;
import com.friendbook.social.post.FeedAdapter;
import com.friendbook.social.post.PostDetailActivity;
import com.friendbook.social.profile.ProfileActivity;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 📄 PageDetailActivity — Full friendbook Page experience:
 * Page banner, avatar, category, follower count, Follow/Unfollow, Contact/Website,
 * Post-as-Page composer for Page owner, and live Page posts feed.
 */
public class PageDetailActivity extends Activity implements FeedAdapter.Host {
    private LinearLayout pageControls;

    private String pageId;
    private Map<String, Object> pageData;
    private String pageName = "Page", pagePhoto = "", ownerId = "";
    private boolean mine, following;

    private ImageView avatarIv;
    private TextView titleTv, catTv, aboutTv, statsTv, webTv, followBtn, postAsPageBtn, emptyTv;
    private LinearLayout ownerRow;
    private FeedAdapter adapter;
    private ListenerRegistration pageReg, postsReg;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        pageId = getIntent().getStringExtra("page_id");
        if (pageId == null || pageId.isEmpty()) { finish(); return; }

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));

        LinearLayout bar = Ui.pageBar(this, "Page");
        TextView qrBtn = Ui.roundChip(this, "QR", Ui.inputBg(this), Ui.txt(this), 13);
        Ui.pad(qrBtn, 10, 5, 10, 5);
        qrBtn.setOnClickListener(v -> Ui.showQrDialog(this, pageName, "Official Page on friendbook",
                com.friendbook.social.core.AdsConfig.OFFICIAL_WEBSITE_URL + "/?page=" + pageId));
        bar.addView(qrBtn);
        root.addView(bar);

        ScrollView sv = new ScrollView(this);
        LinearLayout body = Ui.col(this);

        // Gradient cover + centered Page avatar
        FrameLayout hero = new FrameLayout(this);
        View banner = new View(this);
        banner.setBackground(Ui.gradient(0xFF1877F2, 0xFF0A3D91, 0));
        hero.addView(banner, new FrameLayout.LayoutParams(-1, Ui.dp(this, 130)));

        avatarIv = new ImageView(this);
        Ui.avatar(this, avatarIv, null);
        FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(
                Ui.dp(this, 88), Ui.dp(this, 88), Gravity.BOTTOM | Gravity.START);
        ap.setMargins(Ui.dp(this, 16), 0, 0, Ui.dp(this, 10));
        hero.addView(avatarIv, ap);
        body.addView(hero, new LinearLayout.LayoutParams(-1, Ui.dp(this, 130)));

        // Page info card
        LinearLayout info = Ui.col(this);
        info.setBackgroundColor(Ui.card(this));
        Ui.pad(info, 16, 12, 16, 14);
        String initName = getIntent().getStringExtra("name");
        titleTv = Ui.tvBold(this, initName != null && !initName.isEmpty() ? initName : "Page", 21, Ui.txt(this));
        catTv = Ui.tvBold(this, "", 12, Ui.BLUE);
        statsTv = Ui.tv(this, "", 13, Ui.sub(this));
        statsTv.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 6));
        aboutTv = Ui.tv(this, "", 14, Ui.txt(this));
        webTv = Ui.tvBold(this, "", 13, Ui.BLUE);
        webTv.setPadding(0, Ui.dp(this, 4), 0, 0);
        webTv.setVisibility(View.GONE);

        info.addView(titleTv);
        info.addView(catTv);
        info.addView(statsTv);
        info.addView(aboutTv);
        info.addView(webTv);

        // Action buttons
        LinearLayout actions = Ui.row(this);
        actions.setPadding(0, Ui.dp(this, 12), 0, 0);
        followBtn = Ui.btn(this, "👍 Follow Page");
        followBtn.setOnClickListener(v -> toggleFollow());
        actions.addView(followBtn, new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1));

        TextView msgBtn = Ui.btnGrey(this, "💬 Message");
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1);
        mp.leftMargin = Ui.dp(this, 8);
        msgBtn.setOnClickListener(v -> {
            if (ownerId == null || ownerId.isEmpty() || ownerId.equals(Fb.uid())) {
                Ui.toast(this, "You own this Page 👑");
                return;
            }
            Intent ci = new Intent(this, ChatActivity.class);
            ci.putExtra("uid", ownerId);
            ci.putExtra("name", pageName);
            startActivity(ci);
        });
        actions.addView(msgBtn, mp);

        TextView shareBtn = Ui.btnGrey(this, "↗");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 40));
        sp.leftMargin = Ui.dp(this, 8);
        shareBtn.setOnClickListener(v -> {
            Intent s = new Intent(Intent.ACTION_SEND);
            s.setType("text/plain");
            s.putExtra(Intent.EXTRA_TEXT, "Follow " + pageName + " on friendbook!\n"
                    + com.friendbook.social.core.AdsConfig.OFFICIAL_WEBSITE_URL + "/?page=" + pageId);
            startActivity(Intent.createChooser(s, "Share Page"));
        });
        actions.addView(shareBtn, sp);
        info.addView(actions);

        // Owner management bar
        ownerRow = Ui.row(this);
        ownerRow.setPadding(0, Ui.dp(this, 10), 0, 0);
        ownerRow.setVisibility(View.GONE);
        postAsPageBtn = Ui.btnOutline(this, "📝 Create Page Post", Icons.BLUE);
        postAsPageBtn.setOnClickListener(v -> {
            Intent i = new Intent(this, CreatePostActivity.class);
            i.putExtra("page_id", pageId);
            i.putExtra("page_name", pageName);
            i.putExtra("page_photo", pagePhoto);
            startActivity(i);
        });
        ownerRow.addView(postAsPageBtn, new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1));

        TextView editBtn = Ui.btnGrey(this, "✏️ Edit Page");
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-2, Ui.dp(this, 38));
        ep.leftMargin = Ui.dp(this, 8);
        editBtn.setOnClickListener(v -> editPageDialog());
        ownerRow.addView(editBtn, ep);
        info.addView(ownerRow);

        body.addView(info);
        body.addView(Ui.divider(this));

        TextView feedHead = Ui.tvBold(this, "Page Posts", 15, Ui.txt(this));
        Ui.pad(feedHead, 14, 12, 14, 6);
        body.addView(feedHead);

        emptyTv = Ui.tv(this, "No posts on this Page yet.", 13, Ui.sub(this));
        emptyTv.setGravity(Gravity.CENTER);
        Ui.pad(emptyTv, 20, 24, 20, 24);
        body.addView(emptyTv);

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setItemAnimator(null);
        rv.setNestedScrollingEnabled(false);
        adapter = new FeedAdapter(this, this);
        adapter.showHeader = false;
        adapter.showFooter = false;
        rv.setAdapter(adapter);
        body.addView(rv, new LinearLayout.LayoutParams(-1, -2));

        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        pageControls=Ui.col(this);root.addView(pageControls);
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        pageReg = Fb.db().collection("pages").document(pageId)
                .addSnapshotListener((d, err) -> {
                    if (d == null || !d.exists()) return;
                    pageData = d.getData();
                    pageName = Format.strOr(pageData, "name", "Page");
                    String cat = Format.strOr(pageData, "category", "Community");
                    String about = Format.str(pageData, "about");
                    String web = Format.str(pageData, "website");
                    pagePhoto = Format.str(pageData, "photoUrl");
                    ownerId = Format.str(pageData, "ownerId");
                    List<String> followers = Format.listOf(pageData, "followers");
                    mine = Fb.uid() != null && Fb.uid().equals(ownerId);
                    following = Fb.uid() != null && followers.contains(Fb.uid());

                    Ui.avatar(this, avatarIv, pagePhoto);
                    titleTv.setText(pageName);
                    catTv.setText("📄 " + cat);
                    statsTv.setText(Format.count(followers.size()) + " followers"
                            + (mine ? " · 👑 Managed by you" : ""));
                    aboutTv.setText(about.isEmpty() ? "Welcome to our official Page on friendbook." : about);
                    if (!web.isEmpty()) {
                        webTv.setVisibility(View.VISIBLE);
                        webTv.setText("🌐 " + web);
                        webTv.setOnClickListener(v -> Ui.openUrl(this, web));
                    } else webTv.setVisibility(View.GONE);

                    followBtn.setText(following ? "✓ Following" : "👍 Follow Page");
                    followBtn.setBackground(Ui.round(this, following ? Ui.GREEN : Ui.BLUE, 8));
                    ownerRow.setVisibility(mine ? View.VISIBLE : View.GONE);
                });

        postsReg=com.friendbook.social.core.PagedLists.attach(this,pageControls,
                Fb.db().collection("posts").whereEqualTo("pageId",pageId),15,"page-posts:"+pageId,(snap,err)-> {
                    List<Models.Post> list = new ArrayList<>();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap) {
                            Models.Post p = Models.Post.fromDoc(d);
                            if (p.archived || p.trashedAt > 0) continue;
                            list.add(p);
                        }
                    }
                    Collections.sort(list, (a, b2) -> Long.compare(b2.createdAt, a.createdAt));
                    adapter.setPosts(list);
                    emptyTv.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (pageReg != null) pageReg.remove();
        if (postsReg != null) postsReg.remove();
    }

    private void toggleFollow() {
        String me = Fb.uid();
        if (me == null) return;
        Fb.db().collection("pages").document(pageId).update("followers",
                following ? FieldValue.arrayRemove(me) : FieldValue.arrayUnion(me));
    }

    private void editPageDialog() {
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 20, 10, 20, 0);
        EditText nIn = Ui.input(this, "Page name");
        nIn.setText(pageName);
        EditText cIn = Ui.input(this, "Category (e.g. Creator, Business, Brand)");
        cIn.setText(Format.str(pageData, "category"));
        EditText wIn = Ui.input(this, "Website URL (optional)");
        wIn.setText(Format.str(pageData, "website"));
        EditText aIn = Ui.multi(this, "About this Page…", 3);
        aIn.setText(Format.str(pageData, "about"));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 46));
        lp.bottomMargin = Ui.dp(this, 8);
        box.addView(nIn, lp);
        box.addView(cIn, lp);
        box.addView(wIn, lp);
        box.addView(aIn, new LinearLayout.LayoutParams(-1, -2));

        new AlertDialog.Builder(this)
                .setTitle("Edit Page")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    Map<String, Object> upd = new HashMap<>();
                    upd.put("name", nIn.getText().toString().trim());
                    upd.put("category", cIn.getText().toString().trim());
                    upd.put("website", wIn.getText().toString().trim());
                    upd.put("about", aIn.getText().toString().trim());
                    Fb.db().collection("pages").document(pageId).update(upd)
                            .addOnSuccessListener(x -> Ui.toast(this, "Page updated ✓"));
                })
                .setNeutralButton("🗑 Delete Page", (d, w) -> Ui.confirm(this, "Delete Page?",
                        "This removes the Page permanently.", "Delete", () -> {
                            Fb.db().collection("pages").document(pageId).delete();
                            finish();
                        }))
                .setNegativeButton("Cancel", null)
                .show();
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
    @Override public void openStoryGroup(com.friendbook.social.story.StoriesAdapter.Group g) { }
    @Override public void loadMore() { }
    @Override public boolean hasMore() { return false; }
}
