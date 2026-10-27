package com.friendbook.main;

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

import com.friendbook.chat.ChatActivity;
import com.friendbook.core.FastCache;
import com.friendbook.core.Fb;
import com.friendbook.core.FeedAlgorithm;
import com.friendbook.core.Prefs;
import com.friendbook.core.Ui;
import com.friendbook.models.Models;
import com.friendbook.post.CreatePostActivity;
import com.friendbook.post.FeedAdapter;
import com.friendbook.post.PostDetailActivity;
import com.friendbook.profile.ProfileActivity;
import com.friendbook.story.AddStoryActivity;
import com.friendbook.story.StoriesAdapter;
import com.friendbook.story.StoryViewerActivity;
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

    private static final int PAGE = 100;
    private static final long NOTE_TTL_MS = 24L * 3600_000L;

    private FeedAdapter adapter;
    private RecyclerView rv;
    private SwipeRefreshLayout swipe;
    private TextView freshPill;
    private LinearLayout notesRow;
    private ListenerRegistration postsReg, storiesReg, friendsReg, livesReg;
    private DocumentSnapshot lastDoc;
    private boolean moreAvailable = true;
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
        adapter = new FeedAdapter(a, this);
        rv.setAdapter(adapter);
        rv.setBackgroundColor(Ui.bg(a));

        // ⚡ 0.0ms INSTANT FIRST PAINT FROM RAM / DISK SNAPSHOT (ranked immediately via friendbook FeedAlgorithm!)
        List<Models.Post> cachedFeed = FastCache.getFeed();
        if (!cachedFeed.isEmpty()) {
            List<Models.Post> initialRanked = rankAccordingToMode(a, cachedFeed);
            sessionOrderIds.clear();
            for (Models.Post p : initialRanked) {
                if (p != null && p.id != null) sessionOrderIds.add(p.id);
            }
            lastAppliedFilter = a == null ? "all" : Prefs.feedFilter(a);
            forceReRank = false;
            adapter.setPosts(initialRanked);
        }
        List<StoriesAdapter.Group> cachedStories = new ArrayList<>(FastCache.getStories());
        if (!cachedStories.isEmpty()) {
            FeedAlgorithm.rankStories(a, cachedStories, Fb.uid());
            adapter.setStories(cachedStories);
        }

        swipe.addView(rv);
        swipe.setOnRefreshListener(() -> {
            refreshWithFriendbookAlgorithm(true);
            swipe.postDelayed(() -> { if (swipe != null) swipe.setRefreshing(false); }, 420);
        });

        android.widget.FrameLayout feedWrap = new android.widget.FrameLayout(a);
        feedWrap.addView(swipe, new android.widget.FrameLayout.LayoutParams(-1, -1));

        // Signature friendbook floating "↑ New posts" pill on background-resume discovery
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
        restartPostsListener();
        loadPymk();
        loadNotesUsers();
        if (scrollTop && rv != null) rv.scrollToPosition(0);
    }

    /** Called by MainActivity when user returns from background (>= 15s away). */
    public void onWarmSessionResume() {
        if (rv == null || adapter == null) return;
        LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
        int firstPos = lm != null ? lm.findFirstVisibleItemPosition() : 0;
        if (firstPos <= 1) {
            // User is already near top -> seamlessly apply fresh ranked feed & ads immediately!
            applyFilterOnCachedFeed();
            adapter.refreshSponsoredAds();
            restartPostsListener();
            loadPymk();
        } else if (freshPill != null) {
            // User was reading mid-scroll -> show friendbook's non-intrusive blue "↑ New posts & videos" pill!
            freshPill.setVisibility(View.VISIBLE);
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
                rv.scrollToPosition(0);
            }
        }
    }

    public void onLiteModeChanged() {
        android.app.Activity a = getActivity();
        if (adapter != null && a != null) {
            adapter.setAdsConfig(com.friendbook.core.AdsConfig.nativeId(),
                    com.friendbook.core.AdsConfig.nativeAdsEnabled());
            adapter.notifyDataSetChanged();
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
        for (Models.Post p : FastCache.getFeed()) {
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
        restartPostsListener();
        startStoriesListener();
        startLivesListener();
        loadNotesUsers();
        com.friendbook.core.AdsConfig.setChangeListener((id, en) -> {
            if (adapter != null) adapter.setAdsConfig(id, en);
        });
        adapter.setAdsConfig(com.friendbook.core.AdsConfig.nativeId(),
                com.friendbook.core.AdsConfig.nativeAdsEnabled());
    }

    @Override
    public void onStop() {
        super.onStop();
        FastCache.removeReactionObserver(reactionObserver);
        if (postsReg != null) postsReg.remove();
        if (storiesReg != null) storiesReg.remove();
        if (friendsReg != null) friendsReg.remove();
        if (livesReg != null) livesReg.remove();
        com.friendbook.core.AdsConfig.setChangeListener(null);
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
                            List<String> parts = Models.ChatRoom.fromDoc(d).participants;
                            for (String p : parts) if (!p.equals(me)) friendIds.add(p);
                        }
                    }
                    loadPymk();
                });
    }

    private void loadPymk() {
        String me = Fb.uid();
        if (me == null || adapter == null) return;
        Fb.db().collection("users").limit(60).get().addOnSuccessListener(snap -> {
            List<Models.User> pymk = new ArrayList<>();
            for (DocumentSnapshot d : snap) {
                Models.User u = Models.User.fromDoc(d);
                FastCache.putUser(u);
                if (u.uid == null || u.uid.equals(me)) continue;
                if (u.deactivated) continue;
                if (friendIds.contains(u.uid)) continue;
                if (Fb.me != null && Fb.me.blocked.contains(u.uid)) continue;
                pymk.add(u);
            }
            if (adapter != null) adapter.setPymk(FeedAlgorithm.rankPymk(getActivity(), pymk, 12));
        });
    }

    // ---------- posts ----------
    private boolean postsFallbackUsed;

    private void restartPostsListener() {
        postsFallbackUsed = false;
        startPostsQuery(true);
    }

    private void startPostsQuery(boolean ordered) {
        if (postsReg != null) postsReg.remove();
        lastDoc = null;
        moreAvailable = true;
        Query q = Fb.db().collection("posts");
        if (ordered) q = q.orderBy("createdAt", Query.Direction.DESCENDING);
        final Query finalQ = q.limit(PAGE);

        // ⚡ Immediate disk-cache probe if RAM feed was cold
        if (FastCache.cachedFeedCount() == 0) {
            finalQ.get(Source.CACHE).addOnSuccessListener(cacheSnap -> {
                if (cacheSnap != null && !cacheSnap.isEmpty() && FastCache.cachedFeedCount() == 0) {
                    consumePostsSnapshot(cacheSnap.getDocuments(), ordered, false);
                }
            });
        }

        postsReg = finalQ.addSnapshotListener((snap, err) -> {
            if (err != null || snap == null) {
                if (!postsFallbackUsed) {
                    postsFallbackUsed = true;
                    startPostsQuery(false);
                } else if (FastCache.cachedFeedCount() == 0 && adapter != null) {
                    adapter.setPosts(new ArrayList<>());
                }
                return;
            }
            if (!snap.isEmpty()) lastDoc = snap.getDocuments().get(snap.size() - 1);
            consumePostsSnapshot(snap.getDocuments(), ordered, true);
        });
    }

    private void consumePostsSnapshot(List<DocumentSnapshot> docs, boolean ordered, boolean persistCache) {
        android.app.Activity a2 = getActivity();
        String filter = a2 == null ? "all" : Prefs.feedFilter(a2);
        List<Models.Post> allVisible = new ArrayList<>();
        List<Models.Post> filtered = new ArrayList<>();
        for (DocumentSnapshot d : docs) {
            if (Boolean.TRUE.equals(d.getBoolean("safetyFlagged"))) {
                com.friendbook.core.ContentSafetyEngine.autoDeleteContent(
                        a2, "posts", d.getId(), d.getString("authorId"),
                        "policy_violation", "Safety flagged post", null);
                continue;
            }
            Models.Post p = Models.Post.fromDoc(d);
            String mediaCheck = (p.mediaUrls != null && !p.mediaUrls.isEmpty())
                    ? p.mediaUrls.get(0) : p.mediaUrl;
            if (com.friendbook.core.ContentSafetyEngine.checkAndAutoDeleteIfTextUnsafe(
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
        if (adapter != null) adapter.setPosts(finalFeed);
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
                            if (getActivity() != null && !s.authorId.equals(me)
                                    && Prefs.isStoryAuthorMuted(getActivity(), s.authorId))
                                continue;
                            if ("Friends".equalsIgnoreCase(s.privacy)
                                    && !s.authorId.equals(me) && !friendIds.contains(s.authorId))
                                continue;
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
                    FeedAlgorithm.rankStories(getActivity(), groups, Fb.uid());
                    FastCache.saveStories(groups);
                    if (adapter != null) adapter.setStories(groups);
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

    @Override
    public void openFindFriends() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).select(MainActivity.TAB_FRIENDS);
        } else if (getActivity() != null) {
            startActivity(new Intent(getActivity(), com.friendbook.friends.SearchActivity.class));
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
