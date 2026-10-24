package com.example.frienbook.main;

import android.app.Fragment;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

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
    private ListenerRegistration postsReg, storiesReg, friendsReg;
    private DocumentSnapshot lastDoc;
    private boolean moreAvailable = true;
    private final Set<String> friendIds = new HashSet<>();
    private final Map<String, StoriesAdapter.Group> storyGroups = new HashMap<>();

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inf, ViewGroup container, Bundle b) {
        android.app.Activity a = getActivity();
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
        return swipe;
    }

    @Override
    public void onStart() {
        super.onStart();
        startFriendsListener();
        restartPostsListener();
        startStoriesListener();
    }

    @Override
    public void onStop() {
        super.onStop();
        if (postsReg != null) postsReg.remove();
        if (storiesReg != null) storiesReg.remove();
        if (friendsReg != null) friendsReg.remove();
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
                    List<Models.Post> list = new ArrayList<>();
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        Models.Post p = Models.Post.fromDoc(d);
                        if (visible(p)) list.add(p);
                    }
                    adapter.setPosts(list);
                });
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
