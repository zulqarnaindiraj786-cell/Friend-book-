package com.friendbook.social.post;

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

import com.friendbook.social.core.FastCache;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.FeedAlgorithm;
import com.friendbook.social.core.Prefs;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.friendbook.social.profile.ProfileActivity;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Source;

import java.util.ArrayList;
import java.util.List;

/** 📺 friendbook Watch: Videos feed with For You / Popular / Favorites filter chips & 0ms cache render. */
public class WatchActivity extends Activity implements FeedAdapter.Host {

    private FeedAdapter adapter;
    private ListenerRegistration reg;
    private String cursor;
    private boolean loading, more=true;
    private int generation;
    private SwipeRefreshLayout swipe;
    private final List<Models.Post> rawVideos = new ArrayList<>();
    private final List<String> sessionOrderIds = new ArrayList<>();
    private boolean forceReRank = true;
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
        adapter.showFooter = true;
        rv.setAdapter(adapter);
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView list,int dx,int dy) {
                LinearLayoutManager manager=(LinearLayoutManager)list.getLayoutManager();
                if(dy>0 && manager!=null && manager.findLastVisibleItemPosition()>=adapter.getItemCount()-3) loadMore();
            }
        });
        swipe.addView(rv);
        swipe.setOnRefreshListener(() -> {
            forceReRank = true;
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

        // A D1 read fills the video list when the cache has nothing. This was a Firestore
        // Source.CACHE read, which is empty once Firestore is off, so Watch opened blank.
        if (rawVideos.isEmpty()) {
            com.friendbook.social.core.D1Snap.read(
                    com.friendbook.social.core.DocQuery.of("posts")
                            .whereEqualTo("mediaType", "video").limit(10).build(),
                    snap -> { if (snap != null && !snap.isEmpty() && rawVideos.isEmpty()) consume(snap.getDocuments()); });
        }
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
                forceReRank = true;
                renderChips();
                applyFilter();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = Ui.dp(this, 8);
            chipBar.addView(ch, lp);
        }
    }

    private void consume(List<com.friendbook.social.core.Doc> docs) {
        rawVideos.clear();
        for (com.friendbook.social.core.Doc d : docs) {
            Models.Post p = Models.Post.fromMap(d.getId(), d.getData());
            if (Fb.me != null && Fb.me.blocked.contains(p.authorId)) continue;
            if (Prefs.isSnoozed(this, p.authorId)) continue;
            rawVideos.add(p);
        }
        applyFilter();
    }

    private void applyFilter() {
        List<Models.Post> filtered = new ArrayList<>();
        for (Models.Post p : rawVideos) {
            if ("favorites".equals(mode)
                    && (Fb.me == null || Fb.me.favoriteUids == null || !Fb.me.favoriteUids.contains(p.authorId))) {
                continue;
            }
            filtered.add(p);
        }
        List<Models.Post> out;
        if (!forceReRank && !sessionOrderIds.isEmpty()) {
            java.util.Map<String, Models.Post> byId = new java.util.LinkedHashMap<>();
            for (Models.Post p : filtered) {
                if (p != null && p.id != null) byId.put(p.id, p);
            }
            out = new ArrayList<>(filtered.size());
            List<String> updatedOrder = new ArrayList<>(filtered.size());
            for (String existingId : sessionOrderIds) {
                Models.Post p = byId.remove(existingId);
                if (p != null) {
                    out.add(p);
                    updatedOrder.add(p.id);
                }
            }
            for (Models.Post leftover : filtered) {
                if (leftover != null && leftover.id != null && byId.containsKey(leftover.id)) {
                    out.add(leftover);
                    updatedOrder.add(leftover.id);
                    byId.remove(leftover.id);
                }
            }
            sessionOrderIds.clear();
            sessionOrderIds.addAll(updatedOrder);
        } else {
            if ("popular".equals(mode)) {
                filtered.sort((a, b2) -> Integer.compare(b2.likes + b2.comments, a.likes + a.comments));
                out = filtered;
            } else {
                out = FeedAlgorithm.rankWatchVideos(this, outListCopy(filtered));
            }
            sessionOrderIds.clear();
            for (Models.Post p : out) {
                if (p != null && p.id != null) sessionOrderIds.add(p.id);
            }
            forceReRank = false;
        }
        adapter.setPosts(out);
    }

    private static List<Models.Post> outListCopy(List<Models.Post> src) {
        return new ArrayList<>(src);
    }

    @Override
    protected void onStart() {
        super.onStart();
        FastCache.addReactionObserver(reactionObserver);
        listen();
    }

    private void listen() {
        generation++; loading=false; cursor=null; more=true; loadMore();
    }

    @Override
    protected void onStop() {
        super.onStop();
        generation++; loading=false;
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
    @Override public void openStoryGroup(com.friendbook.social.story.StoriesAdapter.Group g) { }
    @Override public void loadMore() {
        if(loading) return;
        if(!more) {
            if(adapter!=null && !adapter.getPosts().isEmpty() && adapter.getPosts().size()<1200) {
                List<Models.Post> cur=adapter.getPosts();
                int n=Math.min(10,cur.size());
                adapter.addPosts(new ArrayList<>(cur.subList(0,n)));
            }
            return;
        }
        loading=true; final int request=generation; final String actor=Fb.uid();
        // Paging on D1: the Firestore query returned nothing, so "Load more" never added a video.
        com.friendbook.social.core.DocQuery.Builder vq=com.friendbook.social.core.DocQuery.of("posts")
                .whereEqualTo("mediaType","video")
                .orderBy("createdAt", com.friendbook.social.core.DocQuery.DESC).limit(10);
        boolean first=cursor==null; if(!first) vq.startAfter(cursor);
        com.friendbook.social.core.CloudflareStore.query(vq.build(), page -> {
            if(request!=generation || !java.util.Objects.equals(actor,Fb.uid()) || isFinishing()) return;
            loading=false; more=page.hasMore;
            if(first) rawVideos.clear();
            cursor=page.documents.isEmpty()?null:page.nextCursor;
            java.util.Set<String> ids=new java.util.HashSet<>(); for(Models.Post item:rawVideos) ids.add(item.id);
            for(java.util.Map<String,Object> row:page.documents) {
                String rowId=row.get("id")==null?"":String.valueOf(row.get("id"));
                Models.Post post=Models.Post.fromMap(rowId,row);
                if(Boolean.parseBoolean(String.valueOf(row.get("safetyFlagged"))) || (Fb.me!=null && Fb.me.blocked.contains(post.authorId)) || Prefs.isSnoozed(this,post.authorId)) continue;
                if(ids.add(post.id)) rawVideos.add(post);
            }
            applyFilter();
        }, () -> { if(request==generation) { loading=false; Ui.toast(this,"Couldn't load videos. Tap Load more to retry"); } });
    }
    @Override public boolean hasMore() { return more; }
}
