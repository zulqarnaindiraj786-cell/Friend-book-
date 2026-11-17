package com.friendbook.social.post;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.friendbook.social.profile.ProfileActivity;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 🔖 Saved items — friendbook filterable Saved hub (All · Posts · Videos · Marketplace). */
public class SavedPostsActivity extends Activity implements FeedAdapter.Host {

    private FeedAdapter adapter;
    private ListenerRegistration reg;
    private com.friendbook.social.core.DocPager pages;
    private TextView empty;
    private String filter = "all";
    private final TextView[] chips = new TextView[4];
    private final List<Models.Post> allSaved = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "Saved"));

        String[] labels = {"All", "📝 Posts", "🎬 Videos", "🛒 Marketplace"};
        String[] keys = {"all", "posts", "videos", "market"};
        LinearLayout row = Ui.row(this);
        row.setBackgroundColor(Ui.card(this));
        Ui.pad(row, 10, 8, 10, 8);
        for (int i = 0; i < 4; i++) {
            final String k = keys[i];
            TextView c = Ui.tvBold(this, labels[i], 12, Ui.sub(this));
            c.setGravity(Gravity.CENTER);
            Ui.pad(c, 8, 6, 8, 6);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) lp.leftMargin = Ui.dp(this, 6);
            row.addView(c, lp);
            chips[i] = c;
            c.setOnClickListener(v -> { filter = k; refreshChips(); applyFilter(); });
        }
        root.addView(row);
        root.addView(Ui.divider(this));
        refreshChips();

        empty = Ui.tv(this, "Nothing saved here yet.\nTap ••• on any post → Save post.", 14, Ui.sub(this));
        empty.setGravity(Gravity.CENTER);
        Ui.pad(empty, 24, 60, 24, 0);
        root.addView(empty);

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setItemAnimator(null);
        rv.setItemViewCacheSize(16);
        adapter = new FeedAdapter(this, this);
        adapter.showHeader = false;
        adapter.showFooter = true;
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        // ⚡ 0.0ms Instant Saved Hydration from FastCache!
        String me0 = Fb.uid();
        if (me0 != null) {
            for (Models.Post p : com.friendbook.social.core.FastCache.getFeed()) {
                if (p != null && p.savedBy != null && p.savedBy.contains(me0) && allSaved.size()<15) allSaved.add(p);
            }
            if (!allSaved.isEmpty()) applyFilter();
        }
    }

    private void refreshChips() {
        String[] keys = {"all", "posts", "videos", "market"};
        for (int i = 0; i < 4; i++) {
            boolean on = keys[i].equals(filter);
            chips[i].setBackground(Ui.round(this, on ? Ui.BLUE : Ui.inputBg(this), 16));
            chips[i].setTextColor(on ? Color.WHITE : Ui.sub(this));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        String me = Fb.uid();
        if (me == null) return;
        if(pages!=null)pages.close();
        // Saved posts read D1. This was a Firestore query on savedBy, so the Saved screen was
        // empty even for posts the user had just saved.
        pages=new com.friendbook.social.core.DocPager(
                com.friendbook.social.core.DocQuery.of("posts")
                        .whereArrayContains("savedBy",me).build(),15,
                (docs,more,busy,error)-> {
                    if(busy && docs.isEmpty())return;
                    if(!error.isEmpty()){Ui.toast(this,error);return;}
                    allSaved.clear();
                    {
                        for (java.util.Map<String,Object> row : docs) {
                            String rowId = row.get("id")==null?"":String.valueOf(row.get("id"));
                            Models.Post p = Models.Post.fromMap(rowId, row);
                            if (p.trashedAt > 0) continue;
                            if (Fb.me != null && Fb.me.blocked.contains(p.authorId)) continue;
                            allSaved.add(p);
                        }
                    }
                    Collections.sort(allSaved, (a, b2) -> Long.compare(b2.createdAt, a.createdAt));
                    applyFilter();
                });pages.start();
    }

    private void applyFilter() {
        List<Models.Post> list = new ArrayList<>();
        for (Models.Post p : allSaved) {
            if ("videos".equals(filter) && !"video".equals(p.mediaType)) continue;
            if ("market".equals(filter) && !p.isMarketplace()) continue;
            if ("posts".equals(filter) && ("video".equals(p.mediaType) || p.isMarketplace())) continue;
            list.add(p);
        }
        adapter.setPosts(list);
        empty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
        if(pages!=null){pages.close();pages=null;}
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
    @Override public void loadMore() { if(pages!=null)pages.next(); }
    @Override public boolean hasMore() { return pages==null || pages.hasMore(); }
}
