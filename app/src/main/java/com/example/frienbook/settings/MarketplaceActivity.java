package com.example.frienbook.settings;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Prefs;
import com.example.frienbook.core.Ui;
import com.example.frienbook.models.Models;
import com.example.frienbook.post.FeedAdapter;
import com.example.frienbook.post.PostDetailActivity;
import com.example.frienbook.profile.ProfileActivity;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Marketplace: public posts that mention a price ("Rs 500", "$20", "#forsale"…)
 * become sellable listings, Facebook-Marketplace style — real data only.
 */
public class MarketplaceActivity extends Activity implements FeedAdapter.Host {

    private FeedAdapter adapter;
    private ListenerRegistration reg;
    private TextView empty;

    private static final String[] KEYS =
            {"rs", "rs.", "₨", "$", "price", "for sale", "sell", "buy", "deal", "discount", "#market"};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "Marketplace"));

        LinearLayout tip = Ui.col(this);
        tip.setBackgroundColor(Ui.card(this));
        Ui.pad(tip, 14, 8, 14, 8);
        TextView t = Ui.tv(this,
                "💡 Post anything with a price (\"Rs 500\", \"$20\") or \"for sale\" — it appears here automatically.",
                12, Ui.sub(this));
        tip.addView(t);
        root.addView(tip);

        empty = Ui.tv(this, "No listings yet — be the first to sell something!", 14, Ui.sub(this));
        empty.setGravity(Gravity.CENTER);
        Ui.pad(empty, 24, 60, 24, 0);

        SwipeRefreshLayout swipe = new SwipeRefreshLayout(this);
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new GridLayoutManager(this, 1));
        adapter = new FeedAdapter(this, this);
        adapter.showHeader = false;
        adapter.showFooter = false;
        rv.setAdapter(adapter);
        swipe.addView(rv);
        swipe.setOnRefreshListener(() -> {
            listen();
            swipe.postDelayed(() -> swipe.setRefreshing(false), 800);
        });
        LinearLayout body = Ui.col(this);
        body.addView(empty);
        body.addView(swipe, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        listen();
    }

    private void listen() {
        if (reg != null) reg.remove();
        reg = Fb.db().collection("posts")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(200)
                .addSnapshotListener((snap, err) -> {
                    List<Models.Post> list = new ArrayList<>();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap) {
                            Models.Post p = Models.Post.fromDoc(d);
                            if (p.authorId == null) continue;
                            if (Fb.me != null && Fb.me.blocked.contains(p.authorId)) continue;
                            if (Prefs.isPostHidden(this, p.id)) continue;
                            String c = p.content == null ? "" : p.content.toLowerCase(Locale.ROOT);
                            boolean match = false;
                            for (String k : KEYS) if (c.contains(k)) { match = true; break; }
                            if (match) list.add(p);
                        }
                    }
                    adapter.setPosts(list);
                    empty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
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
    public void editPost(Models.Post post) { }

    @Override public void createStory() { }
    @Override public void openStoryGroup(com.example.frienbook.story.StoriesAdapter.Group g) { }
    @Override public void loadMore() { }
    @Override public boolean hasMore() { return false; }
}
