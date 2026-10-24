package com.example.frienbook.main;

import android.app.Fragment;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Ui;
import com.example.frienbook.models.Models;
import com.example.frienbook.post.CreatePostActivity;
import com.example.frienbook.post.FeedAdapter;
import com.example.frienbook.post.PostDetailActivity;
import com.example.frienbook.profile.ProfileActivity;
import com.example.frienbook.story.AddStoryActivity;
import com.example.frienbook.story.StoriesAdapter;
import com.example.frienbook.story.StoryViewerActivity;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Home tab: realtime stories rail + realtime news feed. */
public class HomeFragment extends Fragment implements FeedAdapter.Host {

    private static final int PAGE = 30;

    private FeedAdapter adapter;
    private SwipeRefreshLayout swipe;
    private ListenerRegistration postsReg, storiesReg, friendsReg, boostsReg, livesReg;
    private DocumentSnapshot lastDoc;
    private boolean moreAvailable = true;
    private final Set<String> friendIds = new HashSet<>();
    private final Map<String, StoriesAdapter.Group> storyGroups = new HashMap<>();

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inf, ViewGroup container, Bundle b) {
        android.app.Activity a = getActivity();
        LinearLayout root = Ui.col(a);
        root.setBackgroundColor(Ui.bg(a));
        root.addView(filterChips(a));
        swipe = new SwipeRefreshLayout(a);
        RecyclerView rv = new RecyclerView(a);
        rv.setLayoutManager(new LinearLayoutManager(a));
        adapter = new FeedAdapter(a, this);
        rv.setAdapter(adapter);
        rv.setBackgroundColor(Ui.bg(a));
        swipe.addView(rv);
        swipe.setOnRefreshListener(() -> {
            restartPostsListener();
            swipe.postDelayed(() -> swipe.setRefreshing(false), 900);
        });
        root.addView(swipe, new LinearLayout.LayoutParams(-1, 0, 1));
        return root;
    }

    /** Facebook-Lite style feed filters: All / Photos / Videos / Text-only. */
    private View filterChips(android.app.Activity a) {
        HorizontalScrollView sc = new HorizontalScrollView(a);
        sc.setHorizontalScrollBarEnabled(false);
        LinearLayout row = Ui.row(a);
        row.setPadding(Ui.dp(a, 4), Ui.dp(a, 6), Ui.dp(a, 4), Ui.dp(a, 6));
        String[] labels = {"🏠 All", "🖼 Photos", "🎬 Videos", "📝 Text posts"};
        String[] keys = {"all", "photos", "videos", "text"};
        String cur = com.example.frienbook.core.Prefs.feedFilter(a);
        for (int i = 0; i < labels.length; i++) {
            final String key = keys[i];
            boolean on = key.equals(cur);
            TextView chip = Ui.roundChip(a, labels[i], on ? Ui.BLUE : Ui.card(a),
                    on ? 0xFFFFFFFF : Ui.sub(a), 12);
            chip.setBackground(Ui.roundStroke(a, on ? Ui.BLUE : Ui.card(a), 14, Ui.div(a), 1));
            Ui.pad(chip, 12, 6, 12, 6);
            chip.setOnClickListener(v -> {
                com.example.frienbook.core.Prefs.setFeedFilter(a, key);
                restartPostsListener();
                // rebuild chip row to refresh selected state
                android.view.View parent = (android.view.View) v.getParent().getParent().getParent();
                if (parent instanceof LinearLayout) {
                    ((LinearLayout) parent).removeViewAt(0);
                    ((LinearLayout) parent).addView(filterChips(a), 0);
                }
            });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
            cp.setMargins(Ui.dp(a, 4), 0, Ui.dp(a, 4), 0);
            row.addView(chip, cp);
        }
        sc.addView(row);
        sc.setBackgroundColor(Ui.bg(a));
        return sc;
    }

    @Override
    public void onStart() {
        super.onStart();
        startFriendsListener();
        restartPostsListener();
        startStoriesListener();
        startBoostsListener();
        startLivesListener();
        // 📢 live banner config (admin edits apply instantly)
        com.example.frienbook.core.AdsConfig.setChangeListener((id, en) -> {
            if (adapter != null) adapter.setAdsConfig(id, en);
        });
        adapter.setAdsConfig(com.example.frienbook.core.AdsConfig.bannerId(),
                com.example.frienbook.core.AdsConfig.bannersEnabled());
    }

    @Override
    public void onStop() {
        super.onStop();
        if (postsReg != null) postsReg.remove();
        if (storiesReg != null) storiesReg.remove();
        if (friendsReg != null) friendsReg.remove();
        if (boostsReg != null) boostsReg.remove();
        if (livesReg != null) livesReg.remove();
        com.example.frienbook.core.AdsConfig.setChangeListener(null);
    }

    // ---------- 🚀 boosted profiles/posts (paid → pinned on top of Home) ----------
    private void startBoostsListener() {
        long now = System.currentTimeMillis();
        boostsReg = Fb.db().collection("boosts")
                .whereEqualTo("status", "active")
                .limit(30)
                .addSnapshotListener((snap, err) -> {
                    java.util.List<String> uids = new ArrayList<>();
                    java.util.Set<String> postIds = new HashSet<>();
                    long t = System.currentTimeMillis();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap) {
                            long exp = d.getTimestamp("expiry") == null ? 0
                                    : d.getTimestamp("expiry").toDate().getTime();
                            if (exp != 0 && exp < t) continue; // expired live in-place
                            String type = d.getString("type");
                            if ("post".equals(type)) {
                                String pid = d.getString("postId");
                                if (pid != null && !pid.isEmpty()) postIds.add(pid);
                            } else {
                                String uid = d.getString("userId");
                                if (uid != null) uids.add(uid);
                            }
                        }
                    }
                    final java.util.Set<String> finalPostIds = postIds;
                    java.util.List<Models.User> users = new ArrayList<>();
                    if (uids.isEmpty()) { adapter.setBoosted(users, finalPostIds); return; }
                    final java.util.List<String> fuids = uids;
                    final int[] left = {fuids.size()};
                    for (String uid : fuids) {
                        Fb.db().collection("users").document(uid).get()
                                .addOnSuccessListener(d -> {
                                    if (d.exists()) users.add(Models.User.fromDoc(d));
                                    if (--left[0] == 0) adapter.setBoosted(users, finalPostIds);
                                })
                                .addOnFailureListener(e -> { if (--left[0] == 0) adapter.setBoosted(users, finalPostIds); });
                    }
                });
    }

    // ---------- 🔴 active lives strip ----------
    private void startLivesListener() {
        livesReg = Fb.db().collection("lives")
                .whereEqualTo("status", "live")
                .limit(10)
                .addSnapshotListener((snap, err) -> {
                    List<Map<String, Object>> lives = new ArrayList<>();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap) {
                            Map<String, Object> m = new HashMap<>(d.getData());
                            m.put("_id", d.getId());
                            lives.add(m);
                        }
                    }
                    adapter.setLives(lives);
                });
    }

    // ---------- friends (for privacy filtering) ----------
    private void startFriendsListener() {
        String me = Fb.uid();
        if (me == null) return;
        friendIds.clear();
        friendIds.add(me);
        friendsReg = Fb.db().collection("friends").whereArrayContains("participants", me)
                .addSnapshotListener((snap, err) -> {
                    friendIds.clear();
                    friendIds.add(me);
                    if (snap != null) {
                        for (DocumentSnapshot d : snap) {
                            List<String> parts = com.example.frienbook.models.Models.ChatRoom.fromDoc(d).participants;
                            for (String p : parts) if (!p.equals(me)) friendIds.add(p);
                        }
                    }
                });
    }

    // ---------- posts ----------
    private void restartPostsListener() {
        if (postsReg != null) postsReg.remove();
        lastDoc = null;
        moreAvailable = true;
        postsReg = Fb.db().collection("posts")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(PAGE)
                .addSnapshotListener((snap, err) -> {
                    if (snap == null) return;
                    if (!snap.isEmpty()) lastDoc = snap.getDocuments().get(snap.size() - 1);
                    android.app.Activity a2 = getActivity();
                    String filter = a2 == null ? "all" : com.example.frienbook.core.Prefs.feedFilter(a2);
                    List<Models.Post> list = new ArrayList<>();
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        Models.Post p = Models.Post.fromDoc(d);
                        if (!visible(p)) continue;
                        if (!matchesFilter(p, filter)) continue;
                        list.add(p);
                    }
                    adapter.setPosts(list);
                });
    }

    /** Feed chip filter: photos / videos / text-only. */
    private boolean matchesFilter(Models.Post p, String filter) {
        if (filter == null || "all".equals(filter)) return true;
        if ("photos".equals(filter)) return "image".equals(p.mediaType) || "photo".equals(p.mediaType);
        if ("videos".equals(filter)) return "video".equals(p.mediaType);
        if ("text".equals(filter)) return p.mediaType == null || p.mediaType.isEmpty() || "none".equals(p.mediaType);
        return true;
    }

    private boolean visible(Models.Post p) {
        String me = Fb.uid();
        if (p.authorId == null) return false;
        if (Fb.me != null && Fb.me.blocked.contains(p.authorId)) return false;
        android.app.Activity ctx = getActivity();
        if (ctx != null) {
            if (com.example.frienbook.core.Prefs.isPostHidden(ctx, p.id)) return false;
            if (com.example.frienbook.core.Prefs.isSnoozed(ctx, p.authorId)) return false;
        }
        if (p.authorId.equals(me)) return true;
        if ("Only me".equalsIgnoreCase(p.privacy)) return false;
        if ("Friends".equalsIgnoreCase(p.privacy)) return friendIds.contains(p.authorId);
        return true;
    }

    // ---------- stories ----------
    private void startStoriesListener() {
        storiesReg = Fb.db().collection("stories")
                .whereGreaterThan("expiresAt", Timestamp.now())
                .addSnapshotListener((snap, err) -> {
                    storyGroups.clear();
                    if (snap != null) {
                        String me = Fb.uid();
                        for (QueryDocumentSnapshot d : snap) {
                            Models.Story s = Models.Story.fromDoc(d);
                            if (s.authorId == null || s.authorId.isEmpty()) continue;
                            if (Fb.me != null && Fb.me.blocked.contains(s.authorId)) continue;
                            StoriesAdapter.Group g = storyGroups.get(s.authorId);
                            if (g == null) {
                                g = new StoriesAdapter.Group();
                                g.authorId = s.authorId;
                                g.authorName = s.authorName;
                                g.authorPhoto = s.authorPhoto;
                                storyGroups.put(s.authorId, g);
                            }
                            g.stories.add(s);
                            if (me != null && !s.authorId.equals(me) && !s.viewedBy.contains(me)) g.unseen = true;
                        }
                    }
                    List<StoriesAdapter.Group> groups = new ArrayList<>(storyGroups.values());
                    for (StoriesAdapter.Group g : groups)
                        Collections.sort(g.stories, (x, y) -> Long.compare(x.createdAt, y.createdAt));
                    Collections.sort(groups, (x, y) -> {
                        if (x.unseen != y.unseen) return x.unseen ? -1 : 1;
                        long xt = x.stories.isEmpty() ? 0 : x.stories.get(x.stories.size() - 1).createdAt;
                        long yt = y.stories.isEmpty() ? 0 : y.stories.get(y.stories.size() - 1).createdAt;
                        return Long.compare(yt, xt);
                    });
                    adapter.setStories(groups);
                });
    }

    // ---------- Host ----------
    @Override
    public void openComments(Models.Post post) {
        Intent i = new Intent(getActivity(), PostDetailActivity.class);
        i.putExtra("post_id", post.id);
        startActivity(i);
    }

    @Override
    public void openProfile(String uid) {
        if (uid == null || uid.isEmpty()) return;
        Intent i = new Intent(getActivity(), ProfileActivity.class);
        i.putExtra("uid", uid);
        startActivity(i);
    }

    @Override
    public void editPost(Models.Post post) {
        Intent i = new Intent(getActivity(), CreatePostActivity.class);
        i.putExtra("edit_id", post.id);
        startActivity(i);
    }

    @Override
    public void createStory() {
        startActivity(new Intent(getActivity(), AddStoryActivity.class));
    }

    @Override
    public void openStoryGroup(StoriesAdapter.Group g) {
        Intent i = new Intent(getActivity(), StoryViewerActivity.class);
        i.putExtra("author", g.authorId);
        startActivity(i);
    }

    @Override
    public void loadMore() {
        if (lastDoc == null || !moreAvailable) return;
        Fb.db().collection("posts")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .startAfter(lastDoc)
                .limit(PAGE)
                .get()
                .addOnSuccessListener(snap -> {
                    if (snap == null) return;
                    if (snap.size() < PAGE) moreAvailable = false;
                    if (!snap.isEmpty()) lastDoc = snap.getDocuments().get(snap.size() - 1);
                    List<Models.Post> list = new ArrayList<>();
                    for (DocumentSnapshot d : snap) {
                        Models.Post p = Models.Post.fromDoc(d);
                        if (visible(p)) list.add(p);
                    }
                    adapter.addPosts(list);
                });
    }

    @Override
    public boolean hasMore() { return moreAvailable; }
}
