package com.friendbook.post;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.friendbook.core.FastCache;
import com.friendbook.core.Fb;
import com.friendbook.core.FeedAlgorithm;
import com.friendbook.core.Prefs;
import com.friendbook.core.Ui;
import com.friendbook.models.Models;
import com.friendbook.profile.ProfileActivity;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Source;

import java.util.ArrayList;
import java.util.List;

/** 📺 friendbook Watch: Videos feed with For You / Popular / Favorites filter chips & 0ms cache render. */
public class WatchActivity extends Activity implements FeedAdapter.Host {

    private FeedAdapter adapter;
    private ListenerRegistration reg;
    private SwipeRefreshLayout swipe;
    private final List<Models.Post> rawVideos = new ArrayList<>();
    private String mode = "all"; // all | popular | favorites
    private LinearLayout chipBar;

    private final FastCache.ReactionObserver reactionObserver = (pid, mine, total) -> {
        if (adapter != null) adapter.onFastReactionUpdate(pid, mine, total);
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "📺 Watch"));

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setBackgroundColor(Ui.card(this));
        chipBar = Ui.row(this);
        Ui.pad(chipBar, 8, 6, 8, 6);
        hsv.addView(chipBar);
        root.addView(hsv);
        root.addView(Ui.divider(this));
        renderChips();

        swipe = new SwipeRefreshLayout(this);
        swipe.setColorSchemeColors(Ui.BLUE);
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setItemAnimator(null);
        rv.setItemViewCacheSize(16);
        adapter = new FeedAdapter(this, this);
        adapter.showHeader = false;
        adapter.showFooter = false;
        rv.setAdapter(adapter);
        swipe.addView(rv);
        swipe.setOnRefreshListener(() -> {
            FeedAlgorithm.nextRefreshGeneration(this);
            adapter.refreshSponsoredAds();
            listen();
            swipe.postDelayed(() -> swipe.setRefreshing(false), 450);
        });
        root.addView(swipe, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        // ⚡ 0.0ms Instant Video Pre-Render from FastCache!
        for (Models.Post p : FastCache.getFeed()) {
            if (p != null && "video".equals(p.mediaType)) rawVideos.add(p);
        }
        if (!rawVideos.isEmpty()) applyFilter();

        Fb.db().collection("posts").whereEqualTo("mediaType", "video").limit(80)
                .get(Source.CACHE)
                .addOnSuccessListener(snap -> {
                    if (snap != null && !snap.isEmpty() && rawVideos.isEmpty()) {
                        consume(snap.getDocuments());
                    }
                });
    }

    private void renderChips() {
        if (chipBar == null) return;
        chipBar.removeAllViews();
        String[] labels = {"🎬 For You", "🔥 Popular", "⭐ Favorites"};
        String[] keys = {"all", "popular", "favorites"};
        for (int i = 0; i < labels.length; i++) {
            final String k = keys[i];
            boolean on = k.equals(mode);
            TextView ch = Ui.roundChip(this, labels[i], on ? Ui.BLUE : Ui.inputBg(this),
                    on ? 0xFFFFFFFF : Ui.sub(this), 12);
            Ui.pad(ch, 12, 6, 12, 6);
            ch.setOnClickListener(v -> {
                mode = k;
                renderChips();
                applyFilter();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = Ui.dp(this, 8);
            chipBar.addView(ch, lp);
        }
    }

    private void consume(List<DocumentSnapshot> docs) {
        rawVideos.clear();
        for (DocumentSnapshot d : docs) {
            Models.Post p = Models.Post.fromDoc(d);
            if (Fb.me != null && Fb.me.blocked.contains(p.authorId)) continue;
            if (Prefs.isSnoozed(this, p.authorId)) continue;
            rawVideos.add(p);
        }
        applyFilter();
    }

    private void applyFilter() {
        List<Models.Post> out = new ArrayList<>();
        for (Models.Post p : rawVideos) {
            if ("favorites".equals(mode)
                    && (Fb.me == null || Fb.me.favoriteUids == null || !Fb.me.favoriteUids.contains(p.authorId))) {
                continue;
            }
            out.add(p);
        }
        if ("popular".equals(mode)) {
            out.sort((a, b2) -> Integer.compare(b2.likes + b2.comments, a.likes + a.comments));
        } else {
            out = FeedAlgorithm.rankWatchVideos(this, out);
        }
        adapter.setPosts(out);
    }

    @Override
    protected void onStart() {
        super.onStart();
        FastCache.addReactionObserver(reactionObserver);
        listen();
    }

    private void listen() {
        if (reg != null) reg.remove();
        reg = Fb.db().collection("posts")
                .whereEqualTo("mediaType", "video")
                .limit(100)
                .addSnapshotListener((snap, err) -> {
                    if (snap != null) consume(snap.getDocuments());
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        FastCache.removeReactionObserver(reactionObserver);
        if (reg != null) reg.remove();
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
