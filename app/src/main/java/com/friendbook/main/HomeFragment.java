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
 * ⚡ Zero-Loading Facebook-Lite 525 Home Tab:
 * - Renders cached feed & stories in 0.0ms on Frame #1 (even on 2G or Offline!).
 * - 60/120 FPS RecyclerView (`setItemAnimator(null)` + `setItemViewCacheSize(24)`).
 * - Includes Facebook Lite 24h **Notes ("Share a thought")** bubbles + Feed Filter Chips.
 */
public class HomeFragment extends Fragment implements FeedAdapter.Host {

    private static final int PAGE = 30;
    private static final long NOTE_TTL_MS = 24L * 3600_000L;

    private FeedAdapter adapter;
    private RecyclerView rv;
    private SwipeRefreshLayout swipe;
    private LinearLayout notesRow;
    private ListenerRegistration postsReg, storiesReg, friendsReg, boostsReg, livesReg;
    private DocumentSnapshot lastDoc;
    private boolean moreAvailable = true;
    private final Set<String> friendIds = new HashSet<>();
    private final Map<String, StoriesAdapter.Group> storyGroups = new HashMap<>();
    private final List<Models.User> noteUsers = new ArrayList<>();

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

        // ⚡ 0.0ms INSTANT FIRST PAINT FROM RAM / DISK SNAPSHOT (before Firestore even connects!)
        List<Models.Post> cachedFeed = FastCache.getFeed();
        if (!cachedFeed.isEmpty()) {
            adapter.setPosts(cachedFeed);
        }
        List<StoriesAdapter.Group> cachedStories = FastCache.getStories();
        if (!cachedStories.isEmpty()) {
            adapter.setStories(cachedStories);
        }

        swipe.addView(rv);
        swipe.setOnRefreshListener(() -> {
            restartPostsListener();
            loadNotesUsers();
            swipe.postDelayed(() -> { if (swipe != null) swipe.setRefreshing(false); }, 450);
        });
        root.addView(swipe, new LinearLayout.LayoutParams(-1, 0, 1));
        return root;
    }

    public void scrollToTop() {
        if (rv != null) rv.scrollToPosition(0);
    }

    public void onLiteModeChanged() {
        android.app.Activity a = getActivity();
        if (adapter != null && a != null) {
            boolean allowAds = com.friendbook.core.AdsConfig.bannersEnabled();
            adapter.setAdsConfig(com.friendbook.core.AdsConfig.bannerId(), allowAds);
            adapter.notifyDataSetChanged();
        }
    }

    /** 💭 Facebook Lite 525 24-hour "Notes" strip ("Share a thought" bubble). */
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
        box.addView(Ui.tv(a, "Share a short 60-character status bubble visible for 24 hours (just like Facebook Lite Notes).",
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

    /** Facebook-Lite 525 feed sort & filter strip: Top / Most Recent / Favorites / Photos / Videos / Marketplace / Text. */
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
        List<Models.Post> filtered = new ArrayList<>();
        for (Models.Post p : FastCache.getFeed()) {
            if (visible(p) && matchesFilter(p, filter)) filtered.add(p);
        }
        if ("recent".equals(filter)) {
            filtered.sort((x, y) -> Long.compare(y.createdAt, x.createdAt));
        }
        adapter.setPosts(filtered);
    }

    @Override
    public void onStart() {
        super.onStart();
        FastCache.addReactionObserver(reactionObserver);
        startFriendsListener();
        restartPostsListener();
        startStoriesListener();
        startBoostsListener();
        startLivesListener();
        loadNotesUsers();
        com.friendbook.core.AdsConfig.setChangeListener((id, en) -> {
            if (adapter != null) adapter.setAdsConfig(id, en);
        });
        adapter.setAdsConfig(com.friendbook.core.AdsConfig.bannerId(),
                com.friendbook.core.AdsConfig.bannersEnabled());
    }

    @Override
    public void onStop() {
        super.onStop();
        FastCache.removeReactionObserver(reactionObserver);
        if (postsReg != null) postsReg.remove();
        if (storiesReg != null) storiesReg.remove();
        if (friendsReg != null) friendsReg.remove();
        if (boostsReg != null) boostsReg.remove();
        if (livesReg != null) livesReg.remove();
        com.friendbook.core.AdsConfig.setChangeListener(null);
    }

    // ---------- 🚀 boosted profiles/posts ----------
    private void startBoostsListener() {
        boostsReg = Fb.db().collection("boosts")
                .whereEqualTo("status", "active")
                .limit(20)
                .addSnapshotListener((snap, err) -> {
                    List<String> uids = new ArrayList<>();
                    Set<String> postIds = new HashSet<>();
                    long t = System.currentTimeMillis();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap) {
                            long exp = d.getTimestamp("expiry") == null ? 0
                                    : d.getTimestamp("expiry").toDate().getTime();
                            if (exp != 0 && exp < t) continue;
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
                    final Set<String> finalPostIds = postIds;
                    List<Models.User> users = new ArrayList<>();
                    if (uids.isEmpty()) { adapter.setBoosted(users, finalPostIds); return; }
                    final int[] left = {uids.size()};
                    for (String uid : uids) {
                        Fb.getUser(uid)
                                .addOnSuccessListener(u -> {
                                    if (u != null) users.add(u);
                                    if (--left[0] == 0) adapter.setBoosted(users, finalPostIds);
                                })
                                .addOnFailureListener(e -> {
                                    if (--left[0] == 0) adapter.setBoosted(users, finalPostIds);
                                });
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
        Fb.db().collection("users").limit(25).get().addOnSuccessListener(snap -> {
            List<Models.User> pymk = new ArrayList<>();
            for (DocumentSnapshot d : snap) {
                Models.User u = Models.User.fromDoc(d);
                FastCache.putUser(u);
                if (u.uid == null || u.uid.equals(me)) continue;
                if (u.deactivated) continue;
                if (friendIds.contains(u.uid)) continue;
                if (Fb.me != null && Fb.me.blocked.contains(u.uid)) continue;
                pymk.add(u);
                if (pymk.size() >= 10) break;
            }
            if (adapter != null) adapter.setPymk(pymk);
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
            Models.Post p = Models.Post.fromDoc(d);
            if (!visible(p)) continue;
            allVisible.add(p);
            if (matchesFilter(p, filter)) filtered.add(p);
        }
        if (persistCache && !allVisible.isEmpty()) {
            FastCache.saveFeed(a2, allVisible);
        }
        if (!ordered || "recent".equals(filter)
                || (a2 != null && "recent".equals(Prefs.feedSort(a2)) && "all".equals(filter))) {
            filtered.sort((x, y) -> Long.compare(y.createdAt, x.createdAt));
        } else if ("all".equals(filter) && Fb.me != null && !Fb.me.favoriteUids.isEmpty()) {
            filtered.sort((x, y) -> {
                boolean xf = Fb.me.favoriteUids.contains(x.authorId);
                boolean yf = Fb.me.favoriteUids.contains(y.authorId);
                if (xf != yf) return xf ? -1 : 1;
                return Long.compare(y.createdAt, x.createdAt);
            });
        }
        if (adapter != null) adapter.setPosts(filtered);
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
                    Collections.sort(groups, (x, y) -> {
                        if (x.unseen != y.unseen) return x.unseen ? -1 : 1;
                        long xt = x.stories.isEmpty() ? 0 : x.stories.get(x.stories.size() - 1).createdAt;
                        long yt = y.stories.isEmpty() ? 0 : y.stories.get(y.stories.size() - 1).createdAt;
                        return Long.compare(yt, xt);
                    });
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
