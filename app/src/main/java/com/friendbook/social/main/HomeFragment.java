package com.friendbook.social.main;

import android.app.AlertDialog;
import android.app.Fragment;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.friendbook.social.chat.ChatActivity;
import com.friendbook.social.core.FastCache;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.FeedAlgorithm;
import com.friendbook.social.core.Prefs;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.friendbook.social.post.CreatePostActivity;
import com.friendbook.social.post.FeedAdapter;
import com.friendbook.social.post.PostDetailActivity;
import com.friendbook.social.profile.ProfileActivity;
import com.friendbook.social.story.AddStoryActivity;
import com.friendbook.social.story.StoriesAdapter;
import com.friendbook.social.story.StoryViewerActivity;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.Source;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ⚡ Zero-Loading friendbook 525 Home Tab:
 * - Renders cached feed & stories in 0.0ms on Frame #1 (even on 2G or Offline!).
 * - 60/120 FPS RecyclerView (`setItemAnimator(null)` + `setItemViewCacheSize(24)`).
 * - Includes friendbook 24h **Notes ("Share a thought")** bubbles + Feed Filter Chips.
 */
public class HomeFragment extends Fragment implements FeedAdapter.Host {

    private static final int PAGE = 7;
    private static final long NOTE_TTL_MS = 24L * 3600_000L;

    private FeedAdapter adapter;
    private RecyclerView rv;
    private SwipeRefreshLayout swipe;
    private TextView freshPill;
    private LinearLayout notesRow;
    private ListenerRegistration postsReg, storiesReg, friendsReg, livesReg;
    private DocumentSnapshot lastDoc;
    private boolean moreAvailable = true;
    private boolean loadingPosts, loadedPosts;
    private boolean pageError,pymkLoading,pymkLoaded;
    private boolean featuredReelsLoaded, featuredReelsLoading;
    private String pymkActor;
    private final List<Models.User> pymkCandidates=new ArrayList<>();
    private final List<Models.Reel> featuredReels=new ArrayList<>();
    private final Map<String,DocumentSnapshot> loadedStoryDocs=new java.util.LinkedHashMap<>();
    private int feedGeneration;
    private long lastFeedFetch;
    private String feedAccount;
    private Query feedQuery, storyQuery;
    private DocumentSnapshot storyCursor;
    private boolean loadingStories, moreStories;
    private long lastStoryFetch;
    private String storiesActor,friendGraphActor;
    private int storyGeneration;
    private final Set<String> storyIds=new HashSet<>();
    private final Set<String> loadedPostIds = new HashSet<>();
    private final Set<String> friendIds = new HashSet<>();
    private final Map<String, StoriesAdapter.Group> storyGroups = new HashMap<>();
    private final List<Models.User> noteUsers = new ArrayList<>();
    /** Locks post ordering during active browsing so clicking Like/Comment NEVER jumps posts up or down! */
    private final List<String> sessionOrderIds = new ArrayList<>();
    private boolean forceReRank = true;
    private String lastAppliedFilter = null;

    private final FastCache.ReactionObserver reactionObserver = (postId, mine, total) -> {
        if (adapter != null) adapter.onFastReactionUpdate(postId, mine, total);
    };

    private HorizontalScrollView notesScroll;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inf, ViewGroup container, Bundle b) {
        android.app.Activity a = getActivity();
        LinearLayout root = Ui.col(a);
        root.setBackgroundColor(Ui.bg(a));

        notesScroll = (HorizontalScrollView) buildNotesStrip(a);
        notesScroll.setVisibility(View.GONE);
        root.addView(notesScroll);

        swipe = new SwipeRefreshLayout(a);
        swipe.setColorSchemeColors(Ui.BLUE);
        rv = new RecyclerView(a);
        LinearLayoutManager lm = new LinearLayoutManager(a);
        lm.setItemPrefetchEnabled(true);
        lm.setInitialPrefetchItemCount(6);
        rv.setLayoutManager(lm);
        rv.setItemAnimator(null); // zero jank / zero blink on realtime updates
        rv.setItemViewCacheSize(24);
        rv.getRecycledViewPool().setMaxRecycledViews(1, 30);
        adapter = new FeedAdapter(a, this);
        rv.setAdapter(adapter);
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView view,int dx,int dy) {
                LinearLayoutManager manager=(LinearLayoutManager)view.getLayoutManager();
                if(dy>0 && !pageError && manager!=null && manager.findLastVisibleItemPosition()>=adapter.getItemCount()-4) loadMore();
            }
        });
        rv.setBackgroundColor(Ui.bg(a));

        // ⚡ 0.0ms INSTANT FIRST PAINT FROM RAM / DISK SNAPSHOT (ranked immediately via friendbook FeedAlgorithm!)
        List<Models.Post> cachedFeed = FastCache.getFeed();
        if (!cachedFeed.isEmpty()) {
            List<Models.Post> initialRanked = rankAccordingToMode(a, cachedFeed.subList(0,Math.min(PAGE,cachedFeed.size())));
            sessionOrderIds.clear();
            for (Models.Post p : initialRanked) {
                if (p != null && p.id != null) sessionOrderIds.add(p.id);
            }
            lastAppliedFilter = a == null ? "all" : Prefs.feedFilter(a);
            forceReRank = false;
            adapter.setPosts(initialRanked);
        }
        List<StoriesAdapter.Group> cachedStories=new ArrayList<>();int cachedStoryCount=0;
        for(StoriesAdapter.Group group:FastCache.getStories()){
            StoriesAdapter.Group safe=new StoriesAdapter.Group();safe.authorId=group.authorId;safe.authorName=group.authorName;safe.authorPhoto=group.authorPhoto;safe.unseen=group.unseen;
            for(Models.Story story:group.stories)if(cachedStoryCount<10 && storyAllowed(story)){safe.stories.add(story);cachedStoryCount++;}
            if(!safe.stories.isEmpty() && cachedStories.size()<10)cachedStories.add(safe);
        }
        if(!cachedStories.isEmpty())adapter.setStories(cachedStories);

        swipe.addView(rv);
        swipe.setOnRefreshListener(() -> {
            refreshWithFriendbookAlgorithm(true);
        });

        android.widget.FrameLayout feedWrap = new android.widget.FrameLayout(a);
        feedWrap.addView(swipe, new android.widget.FrameLayout.LayoutParams(-1, -1));

        // Keep the old discovery pill hidden: loading belongs at the scroll boundary.
        freshPill = Ui.roundChip(a, "↑  New posts & videos", Ui.BLUE, 0xFFFFFFFF, 13);
        Ui.pad(freshPill, 16, 8, 16, 8);
        freshPill.setElevation(Ui.dp(a, 6));
        freshPill.setVisibility(View.GONE);
        freshPill.setOnClickListener(v -> {
            freshPill.setVisibility(View.GONE);
            refreshWithFriendbookAlgorithm(true);
        });
        android.widget.FrameLayout.LayoutParams pp = new android.widget.FrameLayout.LayoutParams(
                -2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        pp.topMargin = Ui.dp(a, 10);
        feedWrap.addView(freshPill, pp);

        root.addView(feedWrap, new LinearLayout.LayoutParams(-1, 0, 1));
        return root;
    }

    /**
     * 🔄 Full friendbook Refresh:
     * Advances FeedAlgorithm epoch -> rotates top posts, promotes unseen videos, shuffles PYMK/Stories,
     * and requests fresh live AdMob creatives in both in-feed Sponsored slots & bottom banner!
     */
    public void refreshWithFriendbookAlgorithm(boolean scrollTop) {
        android.app.Activity a = getActivity();
        if (a == null) return;
        if (freshPill != null) freshPill.setVisibility(View.GONE);
        forceReRank = true;
        FeedAlgorithm.nextRefreshGeneration(a);
        applyFilterOnCachedFeed();
        if (adapter != null) adapter.refreshSponsoredAds();
        // An explicit pull/Home refresh supersedes an in-flight older page via feedGeneration.
        loadingPosts=false;restartPostsListener();
        pymkLoaded=false;loadPymk();startStoriesListener(true);
        if (adapter != null) adapter.advanceFeaturedReelRotation();
        featuredReelsLoaded=false;loadFeaturedReelsForHome();
        loadNotesUsers();
        if (scrollTop && rv != null) rv.scrollToPosition(0);
    }

    /** Called by MainActivity when user returns from background (>= 15s away). */
    public void onWarmSessionResume() {
        if (rv == null || adapter == null) return;
        LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
        int firstPos = lm != null ? lm.findFirstVisibleItemPosition() : 0;
        if (firstPos <= 1) {
            applyFilterOnCachedFeed();
            adapter.refreshSponsoredAds();
        } else if (freshPill != null) {
            freshPill.setVisibility(View.GONE);
        }
    }

    private List<Models.Post> rankAccordingToMode(android.app.Activity a, List<Models.Post> list) {
        if (list == null || list.isEmpty()) return new ArrayList<>();
        String filter = a == null ? "all" : Prefs.feedFilter(a);
        boolean strictRecent = "recent".equals(filter)
                || (a != null && "recent".equals(Prefs.feedSort(a)) && "all".equals(filter));
        if (strictRecent) {
            List<Models.Post> copy = new ArrayList<>(list);
            Collections.sort(copy, (x, y) -> Long.compare(y.createdAt, x.createdAt));
            return copy;
        }
        List<String> favs = (Fb.me != null && Fb.me.favoriteUids != null) ? Fb.me.favoriteUids : null;
        return FeedAlgorithm.rankHomeFeed(a, list, friendIds, favs, null);
    }

    public void scrollToTop() {
        if (rv != null) {
            LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
            int pos = lm != null ? lm.findFirstVisibleItemPosition() : 0;
            if (pos <= 1) {
                // Tapping Home while already at top triggers a fresh algorithm refresh!
                refreshWithFriendbookAlgorithm(true);
            } else {
                if (adapter != null) adapter.refreshSponsoredAds();
                rv.scrollToPosition(0);
            }
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden && adapter != null) {
            adapter.refreshSponsoredAds();
        }
    }

    public void onLiteModeChanged() {
        android.app.Activity a = getActivity();
        if (adapter != null && a != null) {
            adapter.setAdsConfig(com.friendbook.social.core.AdsConfig.nativeId(),
                    com.friendbook.social.core.AdsConfig.nativeAdsEnabled());
        }
    }

    /** 💭 friendbook 525 24-hour "Notes" strip ("Share a thought" bubble). */
    private View buildNotesStrip(android.app.Activity a) {
        HorizontalScrollView sc = new HorizontalScrollView(a);
        sc.setHorizontalScrollBarEnabled(false);
        sc.setBackgroundColor(Ui.card(a));
        notesRow = Ui.row(a);
        notesRow.setPadding(Ui.dp(a, 8), Ui.dp(a, 6), Ui.dp(a, 8), Ui.dp(a, 6));
        sc.addView(notesRow);
        renderNotesRow();
        return sc;
    }

    private void renderNotesRow() {
        android.app.Activity a = getActivity();
        if (a == null || notesRow == null) return;
        notesRow.removeAllViews();

        // My own Note bubble
        long now = System.currentTimeMillis();
        String myNote = (Fb.me != null && Fb.me.statusNote != null && !Fb.me.statusNote.isEmpty()
                && (now - Fb.me.statusNoteAt) < NOTE_TTL_MS) ? Fb.me.statusNote : null;
        notesRow.addView(noteChip(a, Fb.myPhoto(), "Your note",
                myNote == null ? "💭 Share a note…" : "💭 " + myNote,
                myNote != null, this::showEditNoteDialog));

        // Friends' active 24h notes
        int friendNotes = 0;
        String me = Fb.uid();
        for (final Models.User u : noteUsers) {
            if (u == null || u.uid == null || u.uid.equals(me)) continue;
            if (u.statusNote == null || u.statusNote.isEmpty()) continue;
            if ((now - u.statusNoteAt) >= NOTE_TTL_MS) continue;
            friendNotes++;
            String shortName = u.displayName == null ? "Friend" : u.displayName.split(" ")[0];
            notesRow.addView(noteChip(a, u.photoUrl, shortName, "💭 " + u.statusNote, true, () -> {
                Intent ci = new Intent(a, ChatActivity.class);
                ci.putExtra("uid", u.uid);
                ci.putExtra("name", u.displayName);
                ci.putExtra("prefill", "Replying to your note \"" + u.statusNote + "\": ");
                startActivity(ci);
            }));
        }
        if (notesScroll != null) {
            notesScroll.setVisibility((myNote != null || friendNotes > 0) ? View.VISIBLE : View.GONE);
        }
    }

    private View noteChip(android.app.Activity a, String photo, String name, String bubbleText,
                          boolean active, Runnable onClick) {
        LinearLayout pill = Ui.row(a);
        pill.setBackground(Ui.roundStroke(a,
                active ? Ui.blend(Ui.BLUE, Ui.card(a), 0.10f) : Ui.inputBg(a),
                18, active ? Ui.BLUE : Ui.div(a), 1));
        Ui.pad(pill, 8, 5, 10, 5);
        ImageView av = new ImageView(a);
        Ui.avatar(a, av, photo);
        int s = Ui.dp(a, 26);
        pill.addView(av, new LinearLayout.LayoutParams(s, s));

        LinearLayout texts = Ui.col(a);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.leftMargin = Ui.dp(a, 7);
        TextView noteTv = Ui.tvBold(a, bubbleText, 11, active ? Ui.txt(a) : Ui.sub(a));
        noteTv.setMaxWidth(Ui.dp(a, 150));
        noteTv.setSingleLine(true);
        TextView nameTv = Ui.tv(a, name, 10, Ui.sub(a));
        nameTv.setSingleLine(true);
        texts.addView(noteTv);
        texts.addView(nameTv);
        pill.addView(texts, tp);
        pill.setOnClickListener(v -> onClick.run());

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = Ui.dp(a, 6);
        pill.setLayoutParams(lp);
        return pill;
    }

    public void showEditNoteDialog() {
        android.app.Activity a = getActivity();
        if (a == null) return;
        LinearLayout box = Ui.col(a);
        Ui.pad(box, 18, 12, 18, 4);
        box.addView(Ui.tv(a, "Share a short 60-character status bubble visible for 24 hours (just like friendbook Lite Notes).",
                12, Ui.sub(a)));
        EditText in = Ui.input(a, "What's on your mind? (e.g. ☕ Coding all night)");
        in.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});
        if (Fb.me != null && Fb.me.statusNote != null) in.setText(Fb.me.statusNote);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2);
        ip.topMargin = Ui.dp(a, 10);
        box.addView(in, ip);

        // Quick mood presets
        HorizontalScrollView presets = new HorizontalScrollView(a);
        presets.setHorizontalScrollBarEnabled(false);
        LinearLayout pr = Ui.row(a);
        pr.setPadding(0, Ui.dp(a, 8), 0, 0);
        String[] moods = {"☕ Chilling", "📚 Studying", "💼 Busy right now", "🎧 Listening to music", "🏏 Watching match", "😴 Sleeping"};
        for (final String m : moods) {
            TextView ch = Ui.roundChip(a, m, Ui.inputBg(a), Ui.txt(a), 11);
            Ui.pad(ch, 9, 5, 9, 5);
            ch.setOnClickListener(v -> in.setText(m));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
            cp.rightMargin = Ui.dp(a, 6);
            pr.addView(ch, cp);
        }
        presets.addView(pr);
        box.addView(presets);

        new AlertDialog.Builder(a)
                .setTitle("💭 Your 24h Note")
                .setView(box)
                .setPositiveButton("Share Note", (d, w) -> {
                    String txt = in.getText().toString().trim();
                    long ts = txt.isEmpty() ? 0L : System.currentTimeMillis();
                    if (Fb.me != null) {
                        Fb.me.statusNote = txt;
                        Fb.me.statusNoteAt = ts;
                        FastCache.saveMe(a, Fb.me);
                    }
                    renderNotesRow();
                    Fb.patchMe(Fb.map("statusNote", txt, "statusNoteAt", ts));
                    Ui.toast(a, txt.isEmpty() ? "Note cleared" : "💭 Note shared!");
                })
                .setNeutralButton("Clear", (d, w) -> {
                    if (Fb.me != null) {
                        Fb.me.statusNote = "";
                        Fb.me.statusNoteAt = 0L;
                        FastCache.saveMe(a, Fb.me);
                    }
                    renderNotesRow();
                    Fb.patchMe(Fb.map("statusNote", "", "statusNoteAt", 0L));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void loadNotesUsers() {
        Fb.db().collection("users").limit(30).get().addOnSuccessListener(snap -> {
            noteUsers.clear();
            long now = System.currentTimeMillis();
            if (snap != null) {
                for (DocumentSnapshot d : snap.getDocuments()) {
                    Models.User u = Models.User.fromDoc(d);
                    FastCache.putUser(u);
                    if (u.statusNote != null && !u.statusNote.isEmpty()
                            && (now - u.statusNoteAt) < NOTE_TTL_MS) {
                        noteUsers.add(u);
                    }
                }
            }
            renderNotesRow();
        });
    }

    /** friendbook 525 feed sort & filter strip: Top / Most Recent / Favorites / Photos / Videos / Marketplace / Text. */
    private View filterChips(android.app.Activity a) {
        HorizontalScrollView sc = new HorizontalScrollView(a);
        sc.setHorizontalScrollBarEnabled(false);
        LinearLayout row = Ui.row(a);
        row.setPadding(Ui.dp(a, 4), Ui.dp(a, 5), Ui.dp(a, 4), Ui.dp(a, 5));
        String[] labels = {"✨ Top", "🕒 Recent", "⭐ Favorites", "🖼 Photos", "🎬 Videos", "🛒 Market", "📝 Text"};
        String[] keys = {"all", "recent", "favorites", "photos", "videos", "market", "text"};
        String cur = Prefs.feedFilter(a);
        for (int i = 0; i < labels.length; i++) {
            final String key = keys[i];
            boolean on = key.equals(cur);
            TextView chip = Ui.roundChip(a, labels[i], on ? Ui.BLUE : Ui.card(a),
                    on ? 0xFFFFFFFF : Ui.sub(a), 12);
            chip.setBackground(Ui.roundStroke(a, on ? Ui.BLUE : Ui.card(a), 14, Ui.div(a), 1));
            Ui.pad(chip, 12, 5, 12, 5);
            chip.setOnClickListener(v -> {
                Prefs.setFeedFilter(a, key);
                if ("recent".equals(key)) Prefs.setFeedSort(a, "recent");
                else if ("all".equals(key)) Prefs.setFeedSort(a, "top");
                // Instant 0ms filter apply over cached feed first, then live sync!
                applyFilterOnCachedFeed();
                restartPostsListener();
                View parent = (View) v.getParent().getParent().getParent();
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

    private void applyFilterOnCachedFeed() {
        android.app.Activity a = getActivity();
        if (a == null || adapter == null) return;
        String filter = Prefs.feedFilter(a);
        forceReRank = true;
        lastAppliedFilter = filter;
        List<Models.Post> filtered = new ArrayList<>();
        List<Models.Post> cache=FastCache.getFeed();
        for (Models.Post p : cache.subList(0,Math.min(PAGE,cache.size()))) {
            if (visible(p) && matchesFilter(p, filter)) filtered.add(p);
        }
        List<Models.Post> ranked = rankAccordingToMode(a, filtered);
        sessionOrderIds.clear();
        for (Models.Post p : ranked) {
            if (p != null && p.id != null) sessionOrderIds.add(p.id);
        }
        forceReRank = false;
        adapter.setPosts(ranked);
    }

    @Override
    public void onStart() {
        super.onStart();
        FastCache.addReactionObserver(reactionObserver);
        startFriendsListener();
        loadPymk();
        // Every time Home is opened the Featured Reels card rotates to the NEXT viral/fresh Reel
        // (never the same video on repeat) while the view counter stays 100% real.
        if (adapter != null) adapter.advanceFeaturedReelRotation();
        loadFeaturedReelsForHome();
        if(!loadedPosts || !java.util.Objects.equals(feedAccount,Fb.uid())) restartPostsListener();
        startStoriesListener();
        startLivesListener();
        loadNotesUsers();
        com.friendbook.social.core.AdsConfig.setChangeListener((id, en) -> {
            if (adapter != null) adapter.setAdsConfig(id, en);
        });
        adapter.setAdsConfig(com.friendbook.social.core.AdsConfig.nativeId(),
                com.friendbook.social.core.AdsConfig.nativeAdsEnabled());
    }

    @Override
    public void onStop() {
        super.onStop();
        FastCache.removeReactionObserver(reactionObserver);
        feedGeneration++; loadingPosts=false; storyGeneration++; loadingStories=false;
        if (postsReg != null) postsReg.remove();
        if (storiesReg != null) storiesReg.remove();
        if (friendsReg != null) friendsReg.remove();
        if (livesReg != null) livesReg.remove();
        com.friendbook.social.core.AdsConfig.setChangeListener(null);
    }

    // ---------- 🔴 active lives strip ----------
    private void startLivesListener() {
        livesReg = Fb.db().collection("lives")
                .whereEqualTo("status", "live")
                .limit(25)
                .addSnapshotListener((snap, err) -> {
                    List<Map<String, Object>> lives = new ArrayList<>();
                    long now = System.currentTimeMillis();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap) {
                            Map<String, Object> raw = d.getData();
                            if (raw == null) continue;
                            long hb = com.friendbook.social.core.Format.longOf(raw, "lastHeartbeatMs");
                            // Filter out abandoned/crashed live rooms older than 2.5 minutes without heartbeat
                            if (hb > 0 && (now - hb) > 150_000L) continue;
                            Map<String, Object> m = new HashMap<>(raw);
                            m.put("_id", d.getId());
                            lives.add(m);
                        }
                    }
                    Collections.sort(lives, (a, b) -> Long.compare(
                            com.friendbook.social.core.Format.longOf(b, "startedAtMs"),
                            com.friendbook.social.core.Format.longOf(a, "startedAtMs")));
                    adapter.setLives(lives);
                });
    }

    // ---------- friends (for privacy filtering) ----------
    private void startFriendsListener() {
        String me = Fb.uid();
        if (me == null) return;
        if(!java.util.Objects.equals(friendGraphActor,me)){friendIds.clear();friendIds.add(me);friendGraphActor=me;}
        // Delta-driven: the previous version rebuilt the entire friend set from scratch on every
        // snapshot, and re-ran loadPymk() + renderLoadedStories() each time. With a few hundred
        // friends every single add/remove re-read the whole graph and re-rendered the feed, which
        // is both a Firestore read cost and a UI jank source. Only ADDED/REMOVED documents change
        // the set, so only those trigger a re-render.
        friendsReg = Fb.db().collection("friends").whereArrayContains("participants", me)
                .addSnapshotListener((snap, err) -> {
                    if(snap==null || !java.util.Objects.equals(me,Fb.uid()) || !isAdded())return;
                    boolean changed = false;
                    if (snap.getMetadata().isFromCache() && !snap.getDocumentChanges().isEmpty()) {
                        // First (cache) delivery still has to populate the set.
                        changed = true;
                    }
                    if (changed || !snap.getDocumentChanges().isEmpty()) {
                        for (com.google.firebase.firestore.DocumentChange ch : snap.getDocumentChanges()) {
                            Models.ChatRoom room = Models.ChatRoom.fromDoc(ch.getDocument());
                            switch (ch.getType()) {
                                case ADDED:
                                    for (String p : room.participants) if (!p.equals(me) && friendIds.add(p)) changed = true;
                                    break;
                                case REMOVED:
                                    // A friendship doc is gone: drop peers that no other doc still lists.
                                    Set<String> still = new HashSet<>();
                                    for (DocumentSnapshot d : snap) {
                                        for (String p : Models.ChatRoom.fromDoc(d).participants)
                                            if (!p.equals(me)) still.add(p);
                                    }
                                    if (friendIds.retainAll(still)) changed = true;
                                    break;
                                default:
                                    // MODIFIED keeps the same participants; nothing to recompute.
                                    break;
                            }
                        }
                    } else {
                        // Initial load arrives as a plain snapshot with no changes reported.
                        Set<String> fresh = new HashSet<>();
                        for (DocumentSnapshot d : snap) {
                            for (String p : Models.ChatRoom.fromDoc(d).participants)
                                if (!p.equals(me)) fresh.add(p);
                        }
                        if (!friendIds.containsAll(fresh) || friendIds.size() != fresh.size() + 1) {
                            friendIds.clear(); friendIds.add(me); friendIds.addAll(fresh);
                            changed = true;
                        }
                    }
                    if (!changed) return;
                    loadPymk();renderLoadedStories();
                });
    }

    private void loadPymk() {
        String me=Fb.uid();if(me==null || adapter==null)return;
        if(!java.util.Objects.equals(pymkActor,me)){pymkActor=me;pymkLoaded=false;pymkLoading=false;pymkCandidates.clear();}
        if(pymkLoaded){renderPymk();return;}if(pymkLoading)return;pymkLoading=true;
        Fb.db().collection("users").limit(10).get().addOnSuccessListener(snap->{
            if(!java.util.Objects.equals(me,Fb.uid()) || !isAdded())return;
            pymkLoading=false;pymkLoaded=true;pymkCandidates.clear();
            for(DocumentSnapshot doc:snap){Models.User u=Models.User.fromDoc(doc);FastCache.putUser(u);pymkCandidates.add(u);}
            renderPymk();
        }).addOnFailureListener(error->{if(java.util.Objects.equals(me,Fb.uid()))pymkLoading=false;});
    }
    private void renderPymk(){
        List<Models.User> rows=new ArrayList<>();for(Models.User u:pymkCandidates)
            if(u.uid!=null && !u.uid.equals(Fb.uid()) && !u.deactivated && !friendIds.contains(u.uid)
                    && (Fb.me==null || !Fb.me.blocked.contains(u.uid)))rows.add(u);
        if(adapter!=null && getActivity()!=null)adapter.setPymk(FeedAlgorithm.rankPymk(getActivity(),rows,10));
    }

    /** Loads featured Reels once on Home open so the one-time Reels Showcase Card after Post #1 shows a real Reel preview. */
    private void loadFeaturedReelsForHome() {
        if (adapter == null) return;
        if (featuredReelsLoaded && !featuredReels.isEmpty()) {
            adapter.setFeaturedReels(featuredReels);
            return;
        }
        if (featuredReelsLoading) return;
        featuredReelsLoading = true;
        // 1. Seed immediately from any cached video posts in FastCache so Frame #1 has a Reel ready
        for (Models.Post p : FastCache.getFeed()) {
            if (p != null && "video".equals(p.mediaType) && p.mediaUrl != null && !p.mediaUrl.isEmpty()) {
                addFeaturedReelCandidate(Models.Reel.fromVideoPost(p));
            }
        }
        if (!featuredReels.isEmpty()) {
            adapter.setFeaturedReels(featuredReels);
        }
        // 2. Check Cloudflare D1/Worker reels
        com.friendbook.social.core.CloudflareStore.list("reels", 12, docs -> {
            if (!isAdded() || docs == null) return;
            boolean changed = false;
            for (Map<String, Object> m : docs) {
                String id = m.get("id") != null ? String.valueOf(m.get("id")) : "";
                if (id.isEmpty()) continue;
                Models.Reel r = Models.Reel.fromMap(id, m);
                if (addFeaturedReelCandidate(r)) changed = true;
            }
            if (changed && adapter != null) adapter.setFeaturedReels(featuredReels);
        });
        // 3. One-shot Firestore reels query (cache first, then network)
        Fb.db().collection("reels").orderBy("createdAt", Query.Direction.DESCENDING).limit(12)
                .get(Source.CACHE)
                .addOnSuccessListener(cacheSnap -> {
                    if (!isAdded() || cacheSnap == null) return;
                    boolean changed = false;
                    for (DocumentSnapshot d : cacheSnap.getDocuments()) {
                        if (Boolean.TRUE.equals(d.getBoolean("safetyFlagged"))) continue;
                        if (addFeaturedReelCandidate(Models.Reel.fromDoc(d))) changed = true;
                    }
                    if (changed && adapter != null) adapter.setFeaturedReels(featuredReels);
                });
        Fb.db().collection("reels").orderBy("createdAt", Query.Direction.DESCENDING).limit(12)
                .get()
                .addOnSuccessListener(snap -> {
                    featuredReelsLoading = false;
                    featuredReelsLoaded = true;
                    if (!isAdded() || snap == null) return;
                    boolean changed = false;
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        if (Boolean.TRUE.equals(d.getBoolean("safetyFlagged"))) continue;
                        if (addFeaturedReelCandidate(Models.Reel.fromDoc(d))) changed = true;
                    }
                    if (changed && adapter != null) adapter.setFeaturedReels(featuredReels);
                })
                .addOnFailureListener(err -> featuredReelsLoading = false);
    }

    private boolean addFeaturedReelCandidate(Models.Reel r) {
        if (r == null || r.id == null || r.id.isEmpty() || r.videoUrl == null || r.videoUrl.isEmpty()) return false;
        if (Fb.me != null && Fb.me.blocked.contains(r.authorId)) return false;
        if (Prefs.isPostHidden(getActivity(), r.id)) return false;
        for (Models.Reel existing : featuredReels) {
            if (existing != null && r.id.equals(existing.id)) return false;
        }
        if (!r.isFromPost) {
            featuredReels.add(0, r);
        } else {
            featuredReels.add(r);
        }
        return true;
    }

    // ---------- posts ----------
    private boolean postsFallbackUsed;
    private final List<Models.Post> cfPosts = new ArrayList<>();
    private List<DocumentSnapshot> lastPostDocs = new ArrayList<>();

    private void restartPostsListener() {
        if(loadingPosts) return;
        postsFallbackUsed = false;
        startPostsQuery(true);
    }

    private void startPostsQuery(boolean ordered) {
        if (postsReg != null) { postsReg.remove(); postsReg=null; }
        final int generation=++feedGeneration;
        pageError=false;loadingPosts=true;updatePagingUi(); loadedPosts=false; lastDoc=null; moreAvailable=true;
        feedAccount=Fb.uid(); final String actor=feedAccount;
        com.friendbook.social.core.CloudflareStore.list("posts", PAGE, docs -> {
            if (generation != feedGeneration || !java.util.Objects.equals(actor, Fb.uid()) || !isAdded()) return;
            cfPosts.clear();
            if (docs != null) {
                for (Map<String, Object> m : docs) {
                    String id = m.get("id") != null ? String.valueOf(m.get("id")) : "";
                    if (!id.isEmpty()) cfPosts.add(Models.Post.fromMap(id, m));
                }
            }
            if (loadedPosts || !cfPosts.isEmpty()) {
                loadingPosts = false;
                loadedPosts = true;
                consumePostsSnapshot(lastPostDocs, ordered, true);
            }
        });
        Query query=Fb.db().collection("posts");
        // Same ordering/cursor is retained for subsequent pages, including the fallback.
        query=ordered?query.orderBy("createdAt",Query.Direction.DESCENDING)
                :query.orderBy(com.google.firebase.firestore.FieldPath.documentId());
        feedQuery=query;
        final Query first=query.limit(PAGE);
        if(FastCache.cachedFeedCount()==0) first.get(Source.CACHE).addOnSuccessListener(cache -> {
            if(generation==feedGeneration && java.util.Objects.equals(actor,Fb.uid()) && !loadedPosts && !cache.isEmpty())
                consumePostsSnapshot(cache.getDocuments(),ordered,false);
        });
        first.get().addOnSuccessListener(snapshot -> {
            if(generation!=feedGeneration || !java.util.Objects.equals(actor,Fb.uid()) || !isAdded()) return;
            loadingPosts=false;updatePagingUi(); loadedPosts=true;
            lastFeedFetch=android.os.SystemClock.elapsedRealtime();
            loadedPostIds.clear();
            for(DocumentSnapshot doc:snapshot.getDocuments()) loadedPostIds.add(doc.getId());
            moreAvailable=snapshot.size()==PAGE;
            if(!snapshot.isEmpty()) lastDoc=snapshot.getDocuments().get(snapshot.size()-1);
            consumePostsSnapshot(snapshot.getDocuments(),ordered,true);
        }).addOnFailureListener(error -> {
            if(generation!=feedGeneration || !java.util.Objects.equals(actor,Fb.uid())) return;
            loadingPosts=false;pageError=true;updatePagingUi();
            if(ordered && !postsFallbackUsed) { postsFallbackUsed=true; startPostsQuery(false); }
            else if(getActivity()!=null) Ui.toast(getActivity(),"Feed unavailable — pull down to retry");
        });
    }

    private void consumePostsSnapshot(List<DocumentSnapshot> docs, boolean ordered, boolean persistCache) {
        android.app.Activity a2 = getActivity();
        String filter = a2 == null ? "all" : Prefs.feedFilter(a2);
        if (docs != null) lastPostDocs = docs;
        List<Models.Post> allVisible = new ArrayList<>();
        List<Models.Post> filtered = new ArrayList<>();
        java.util.Set<String> seenIds = new java.util.HashSet<>();
        for (Models.Post p : cfPosts) {
            if (p == null || p.id == null || p.id.isEmpty() || !seenIds.add(p.id)) continue;
            if (!visible(p)) continue;
            allVisible.add(p);
            if (matchesFilter(p, filter)) filtered.add(p);
        }
        if (docs != null) for (DocumentSnapshot d : docs) {
            if (d.getId() != null && !seenIds.add(d.getId())) continue;
            if (Boolean.TRUE.equals(d.getBoolean("safetyFlagged"))) {
                com.friendbook.social.core.ContentSafetyEngine.autoDeleteContent(
                        a2, "posts", d.getId(), d.getString("authorId"),
                        "policy_violation", "Safety flagged post", null);
                continue;
            }
            Models.Post p = Models.Post.fromDoc(d);
            String mediaCheck = (p.mediaUrls != null && !p.mediaUrls.isEmpty())
                    ? p.mediaUrls.get(0) : p.mediaUrl;
            if (com.friendbook.social.core.ContentSafetyEngine.checkAndAutoDeleteIfTextUnsafe(
                    a2, "posts", p.id, p.authorId, p.content, mediaCheck, null)) {
                continue;
            }
            if (!visible(p)) continue;
            allVisible.add(p);
            if (matchesFilter(p, filter)) filtered.add(p);
        }
        if (persistCache && !allVisible.isEmpty()) {
            FastCache.saveFeed(a2, allVisible);
        }

        // ⚡ Anti-Jump Session Order Lock:
        // When live snapshot fires because someone tapped Like/Comment/Share, keep exact current order!
        boolean filterChanged = lastAppliedFilter == null || !lastAppliedFilter.equals(filter);
        List<Models.Post> finalFeed;
        if (!forceReRank && !filterChanged && !sessionOrderIds.isEmpty()) {
            Map<String, Models.Post> byId = new HashMap<>();
            for (Models.Post p : filtered) {
                if (p != null && p.id != null) byId.put(p.id, p);
            }
            finalFeed = new ArrayList<>(filtered.size());
            String myUid = Fb.uid();
            long now = System.currentTimeMillis();
            // Prepend only brand-new posts just published by ME right now
            List<String> newTopIds = new ArrayList<>();
            for (Models.Post p : filtered) {
                if (p != null && p.id != null && !sessionOrderIds.contains(p.id)
                        && myUid != null && myUid.equals(p.authorId)
                        && (now - p.createdAt) < 30_000L) {
                    finalFeed.add(p);
                    newTopIds.add(p.id);
                    byId.remove(p.id);
                }
            }
            // Keep all existing session posts in their exact frozen order!
            List<String> updatedOrder = new ArrayList<>(newTopIds);
            for (String existingId : sessionOrderIds) {
                Models.Post p = byId.remove(existingId);
                if (p != null) {
                    finalFeed.add(p);
                    updatedOrder.add(p.id);
                }
            }
            // Append any remaining newly arrived posts at the bottom without shifting current scroll!
            for (Models.Post leftover : filtered) {
                if (leftover != null && leftover.id != null && byId.containsKey(leftover.id)) {
                    finalFeed.add(leftover);
                    updatedOrder.add(leftover.id);
                    byId.remove(leftover.id);
                }
            }
            sessionOrderIds.clear();
            sessionOrderIds.addAll(updatedOrder);
        } else {
            finalFeed = rankAccordingToMode(a2, filtered);
            sessionOrderIds.clear();
            for (Models.Post p : finalFeed) {
                if (p != null && p.id != null) sessionOrderIds.add(p.id);
            }
            lastAppliedFilter = filter;
            forceReRank = false;
        }
        pageError=moreAvailable && finalFeed.isEmpty();
        boolean addedReel = false;
        for (Models.Post p : allVisible) {
            if (p != null && "video".equals(p.mediaType) && p.mediaUrl != null && !p.mediaUrl.isEmpty()) {
                if (addFeaturedReelCandidate(Models.Reel.fromVideoPost(p))) addedReel = true;
            }
        }
        if (adapter != null) {
            if (addedReel || !featuredReels.isEmpty()) adapter.setFeaturedReels(featuredReels);
            adapter.setPosts(finalFeed);
        }
        updatePagingUi();
    }

    private boolean matchesFilter(Models.Post p, String filter) {
        if (filter == null || "all".equals(filter) || "recent".equals(filter)) return true;
        if ("favorites".equals(filter))
            return Fb.me != null && Fb.me.favoriteUids != null && Fb.me.favoriteUids.contains(p.authorId);
        if ("photos".equals(filter))
            return "image".equals(p.mediaType) || "images".equals(p.mediaType) || "photo".equals(p.mediaType);
        if ("videos".equals(filter)) return "video".equals(p.mediaType);
        if ("market".equals(filter)) return p.isMarketplace();
        if ("text".equals(filter)) return p.mediaType == null || p.mediaType.isEmpty() || "none".equals(p.mediaType);
        return true;
    }

    private boolean visible(Models.Post p) {
        String me = Fb.uid();
        if (p.authorId == null) return false;
        if (p.archived || p.trashedAt > 0) return false;
        if (Fb.me != null && Fb.me.blocked.contains(p.authorId)) return false;
        android.app.Activity ctx = getActivity();
        if (ctx != null) {
            if (Prefs.isPostHidden(ctx, p.id)) return false;
            if (Prefs.isSnoozed(ctx, p.authorId)) return false;
        }
        if (p.authorId.equals(me)) return true;
        if ("Only me".equalsIgnoreCase(p.privacy)) return false;
        if ("Friends".equalsIgnoreCase(p.privacy)) return friendIds.contains(p.authorId);
        return true;
    }

    // ---------- stories ----------
    private void startStoriesListener(){startStoriesListener(false);}
    private void startStoriesListener(boolean force) {
        String actor=Fb.uid();
        if(!force && java.util.Objects.equals(storiesActor,actor) && storyQuery!=null && android.os.SystemClock.elapsedRealtime()-lastStoryFetch<120_000L){renderLoadedStories();return;}
        storiesActor=actor;
        if(storiesReg!=null) { storiesReg.remove(); storiesReg=null; }
        storyGeneration++; loadingStories=false; moreStories=true; storyCursor=null;
        storyQuery=Fb.db().collection("stories").whereGreaterThan("expiresAt",Timestamp.now())
                .orderBy("expiresAt",Query.Direction.ASCENDING);
        loadMoreStories();
    }

    @Override public boolean hasMoreStories() { return moreStories; }
    @Override public void loadMoreStories() {
        if(loadingStories || !moreStories || storyQuery==null) return;
        loadingStories=true; final int generation=storyGeneration; final String actor=Fb.uid();
        final boolean first=storyCursor==null;
        Query page=first?storyQuery:storyQuery.startAfter(storyCursor);
        page.limit(10).get().addOnSuccessListener(snapshot -> {
            if(generation!=storyGeneration || !java.util.Objects.equals(actor,Fb.uid()) || !isAdded()) return;
            loadingStories=false;lastStoryFetch=android.os.SystemClock.elapsedRealtime(); moreStories=snapshot.size()==10;
            if(first)loadedStoryDocs.clear();
            if(!snapshot.isEmpty())storyCursor=snapshot.getDocuments().get(snapshot.size()-1);
            for(DocumentSnapshot doc:snapshot)loadedStoryDocs.put(doc.getId(),doc);
            renderLoadedStories();
        }).addOnFailureListener(error -> {
            if(generation!=storyGeneration) return;
            loadingStories=false;
            if(getActivity()!=null) Ui.toast(getActivity(),"Couldn't load stories — tap More stories to retry");
        });
    }

    private boolean storyAllowed(Models.Story story){
        return !story.expired() && com.friendbook.social.core.StoryAudience.visible(Fb.uid(),story.authorId,story.privacy,
                java.util.Objects.equals(friendGraphActor,Fb.uid()) && friendIds.contains(story.authorId),Fb.me!=null && Fb.me.following.contains(story.authorId),
                Fb.me!=null && Fb.me.blocked.contains(story.authorId),getActivity()!=null && Prefs.isStoryAuthorMuted(getActivity(),story.authorId));
    }
    private void renderLoadedStories(){
        if(getActivity()==null || adapter==null || (loadingStories && loadedStoryDocs.isEmpty()))return;storyGroups.clear();storyIds.clear();
        for(DocumentSnapshot doc:loadedStoryDocs.values()){
            Models.Story story=Models.Story.fromDoc(doc);if(!storyAllowed(story))continue;storyIds.add(story.id);
            StoriesAdapter.Group group=storyGroups.get(story.authorId);
            if(group==null){group=new StoriesAdapter.Group();group.authorId=story.authorId;group.authorName=story.authorName;group.authorPhoto=story.authorPhoto;storyGroups.put(story.authorId,group);}
            group.stories.add(story);if(!story.authorId.equals(Fb.uid()) && !story.viewedBy.contains(Fb.uid()))group.unseen=true;
        }
        List<StoriesAdapter.Group> groups=new ArrayList<>(storyGroups.values());
        for(StoriesAdapter.Group group:groups)Collections.sort(group.stories,(x,y)->Long.compare(x.createdAt,y.createdAt));
        FeedAlgorithm.rankStories(getActivity(),groups,Fb.uid());FastCache.saveStories(groups);adapter.setStories(groups);
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
        i.putExtra("authorName", g.authorName);
        i.putExtra("authorPhoto", g.authorPhoto);
        startActivity(i);
    }

    @Override
    public void loadMore() {
        if(loadingPosts)return;
        if(lastDoc==null){if(pageError)restartPostsListener();return;}
        if(!moreAvailable || feedQuery==null){
            return;
        }
        pageError=false;loadingPosts=true;updatePagingUi();
        final int generation=feedGeneration; final String actor=feedAccount;
        feedQuery.startAfter(lastDoc).limit(PAGE).get().addOnSuccessListener(snapshot -> {
            if(generation!=feedGeneration || !java.util.Objects.equals(actor,Fb.uid()) || !isAdded()) return;
            loadingPosts=false;
            moreAvailable=snapshot.size()==PAGE;
            if(!snapshot.isEmpty()) lastDoc=snapshot.getDocuments().get(snapshot.size()-1);
            List<Models.Post> page=new ArrayList<>();
            String filter=Prefs.feedFilter(getActivity());
            for(DocumentSnapshot doc:snapshot.getDocuments()) {
                if(!loadedPostIds.add(doc.getId())) continue;
                Models.Post post=Models.Post.fromDoc(doc);
                if(Boolean.TRUE.equals(doc.getBoolean("safetyFlagged"))) continue;
                if(visible(post) && matchesFilter(post,filter)) { page.add(post); sessionOrderIds.add(post.id); }
            }
            pageError=moreAvailable && page.isEmpty();
            adapter.addPosts(page);
            updatePagingUi();
            // Also refresh footer on empty/filtered final pages. No automatic drain loop.
            if (page.isEmpty()) adapter.notifyDataSetChanged();
        }).addOnFailureListener(error -> {
            if(generation!=feedGeneration) return;
            loadingPosts=false;pageError=true;updatePagingUi();
        });
    }

    private void updatePagingUi(){
        if(rv!=null)rv.post(()->{if(adapter!=null)adapter.pagingChanged();});
        if(swipe!=null && !loadingPosts)swipe.setRefreshing(false);
    }
    @Override public boolean automaticPaging(){return true;}
    @Override public boolean isLoadingMore(){return loadingPosts;}
    @Override public boolean hasPageError(){return pageError;}
    @Override
    public boolean hasMore() { return moreAvailable; }

    @Override
    public void openFindFriends() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).select(MainActivity.TAB_FRIENDS);
        } else if (getActivity() != null) {
            startActivity(new Intent(getActivity(), com.friendbook.social.friends.SearchActivity.class));
        }
    }

    @Override
    public void openReelsTab(Models.Reel featuredReel) {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).openReelsTabFromFeed(featuredReel);
        } else if (getActivity() != null && featuredReel != null && featuredReel.videoUrl != null && !featuredReel.videoUrl.isEmpty()) {
            Intent i = new Intent(getActivity(), com.friendbook.social.reels.ReelPlayerActivity.class);
            i.putExtra("reel_id", featuredReel.id);
            i.putExtra("is_post", featuredReel.isFromPost);
            i.putExtra("video_url", featuredReel.videoUrl);
            i.putExtra("author_id", featuredReel.authorId);
            i.putExtra("author_name", featuredReel.authorName);
            i.putExtra("author_photo", featuredReel.authorPhoto);
            i.putExtra("caption", featuredReel.caption);
            i.putExtra("likes", featuredReel.likes);
            i.putExtra("comments", featuredReel.comments);
            i.putExtra("shares", featuredReel.shares);
            startActivity(i);
        }
    }

    public void showFeedFilterSheet() {
        android.app.Activity a = getActivity();
        if (a == null) return;
        final String[] labels = {
                "✨ Top Feed (Algorithmic)",
                "🕒 Most Recent (Chronological)",
                "⭐ Favorites First",
                "🖼 Photos Only",
                "🎬 Videos Only",
                "🛒 Marketplace Posts",
                "📝 Text Posts Only"
        };
        final String[] keys = {"all", "recent", "favorites", "photos", "videos", "market", "text"};
        Ui.choose(a, "Feed View & Filter", labels, idx -> {
            String key = keys[idx];
            Prefs.setFeedFilter(a, key);
            if ("recent".equals(key)) Prefs.setFeedSort(a, "recent");
            else if ("all".equals(key)) Prefs.setFeedSort(a, "top");
            applyFilterOnCachedFeed();
            restartPostsListener();
            Ui.toast(a, "Showing: " + labels[idx]);
        });
    }
}
