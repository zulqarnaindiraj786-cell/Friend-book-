package com.friendbook.social.post;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import com.friendbook.social.chat.ChatActivity;
import com.friendbook.social.core.FastCache;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Icons;
import com.friendbook.social.core.Notify;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.friendbook.social.profile.ProfileActivity;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ⚡ Zero-Latency friendbook Pro Reactions: 👍 ❤️ 😂 😮 😢 😡
 * - 0.0ms optimistic Like/Reaction updates via {@link FastCache}
 * - Self-healing reaction counts synced with posts/{postId}/reactions
 * - Full "People who reacted" ("Kis kis ne like kiya") viewer for both Posts & Comments!
 */
public class Reactions {

    public static final String[] TYPES = {"like", "love", "haha", "wow", "sad", "angry"};
    public static final String[] EMOJI = {"👍", "❤️", "😂", "😮", "😢", "😡"};
    public static final String[] LABELS = {"Like", "Love", "Haha", "Wow", "Sad", "Angry"};

    public interface OnPick { void pick(String type); }

    public static class ReactorEntry {
        public String uid;
        public String name;
        public String photo;
        public String tickLevel;
        public String type;
        public long createdAt;
    }

    public static String emojiFor(String type) {
        for (int i = 0; i < TYPES.length; i++) if (TYPES[i].equals(type)) return EMOJI[i];
        return "👍";
    }

    public static String labelFor(String type) {
        for (int i = 0; i < TYPES.length; i++) if (TYPES[i].equals(type)) return LABELS[i];
        return "Like";
    }

    public static int colorFor(String type) {
        switch (type == null ? "like" : type) {
            case "love": return Ui.RED;
            case "haha": case "wow": case "sad": case "angry": return 0xFFE09B00;
            default: return Ui.BLUE;
        }
    }

    /** Long-press popup with the 6 friendbook reactions + Care/Emoji bounce. */
    public static void show(View anchor, OnPick cb) {
        Context c = anchor.getContext();
        LinearLayout row = Ui.row(c);
        row.setBackground(Ui.roundStroke(c, Ui.card(c), 28, Ui.div(c), 1));
        row.setElevation(Ui.dp(c, 10));
        int pad = Ui.dp(c, 8);
        row.setPadding(pad, Ui.dp(c, 6), pad, Ui.dp(c, 6));
        final PopupWindow pop = new PopupWindow(row, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pop.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        pop.setElevation(Ui.dp(c, 14));
        for (int i = 0; i < TYPES.length; i++) {
            final String type = TYPES[i];
            LinearLayout col = Ui.col(c);
            col.setGravity(Gravity.CENTER);
            int p = Ui.dp(c, 5);
            col.setPadding(p, Ui.dp(c, 2), p, Ui.dp(c, 2));
            TextView e = Ui.tv(c, EMOJI[i], 28, Color.BLACK);
            TextView lbl = Ui.tv(c, LABELS[i], 9, Ui.sub(c));
            col.addView(e);
            col.addView(lbl);
            col.setOnClickListener(v -> {
                Ui.haptic(v);
                pop.dismiss();
                cb.pick(type);
            });
            row.addView(col);
        }
        row.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int y = Math.max(Ui.dp(c, 48), loc[1] - row.getMeasuredHeight() - Ui.dp(c, 6));
        pop.showAtLocation(anchor, Gravity.NO_GRAVITY, Math.max(Ui.dp(c, 8), loc[0]), y);
    }

    /** Computes active reaction types ordered by frequency straight from post.reactionsMap. */
    public static List<String> computeTopTypesFromMap(Map<String, Map<String, Object>> reactionsMap) {
        if (reactionsMap == null || reactionsMap.isEmpty()) return new ArrayList<>();
        Map<String, Integer> freq = new LinkedHashMap<>();
        for (String t : TYPES) freq.put(t, 0);
        for (Map.Entry<String, Map<String, Object>> e : reactionsMap.entrySet()) {
            if (e.getValue() == null) continue;
            String t = Format.strOr(e.getValue(), "type", "like");
            if (!freq.containsKey(t)) t = "like";
            freq.put(t, freq.get(t) + 1);
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(freq.entrySet());
        Collections.sort(entries, (a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : entries) {
            if (e.getValue() > 0) out.add(e.getKey());
        }
        return out;
    }

    /** Returns ONLY the reaction given by `currentUserId` (`Fb.uid()`), or `null` if `currentUserId` has not reacted. */
    public static String resolveCurrentUserReaction(Models.Post post) {
        String me = Fb.uid();
        if (me == null || me.isEmpty() || post == null || post.id == null) return null;
        if (!FastCache.isLocalReactionInFlight(post.id)
                && post.reactionsMap != null && !post.reactionsMap.isEmpty()) {
            Map<String, Object> mineMap = post.reactionsMap.get(me);
            if (mineMap == null) return null;
            return Models.normalizeReactionType(mineMap.get("type"), "like");
        }
        return FastCache.getMyReaction(post.id);
    }

    /** 1-tap Like toggle: removes `currentUserId`'s reaction if present, otherwise immediately adds "like". */
    public static void tapLike(Context c, Models.Post post) {
        if (post == null || post.id == null) return;
        hydrateFromPostInstant(post);
        String prev = resolveCurrentUserReaction(post);
        apply(c, post, prev != null ? null : "like");
    }

    /**
     * ⚡ 0.0ms Optimistic Reaction Apply / Switch / Remove strictly scoped to `currentUserId` (`Fb.uid()`).
     */
    public static void apply(Context c, Models.Post post, String type) {
        String me = Fb.uid();
        if (me == null || me.isEmpty() || post == null || post.id == null) return;

        hydrateFromPostInstant(post);
        String prev = resolveCurrentUserReaction(post);
        int curTotal = FastCache.getReactionTotal(post.id, post.likes);

        final String nextType;
        final int delta;
        if (prev != null) {
            if (type == null || type.equals(prev)) {
                nextType = null;
                delta = -1;
            } else {
                nextType = Models.normalizeReactionType(type, "like");
                delta = 0;
            }
        } else if (type != null) {
            nextType = Models.normalizeReactionType(type, "like");
            delta = 1;
        } else {
            return;
        }

        // Update post.reactionsMap FIRST under `currentUserId` (`me`) so total & types stay 100% per-user!
        if (post.reactionsMap == null) post.reactionsMap = new HashMap<>();
        Map<String, Object> myData = null;
        if (nextType == null) {
            post.reactionsMap.remove(me);
        } else {
            myData = new HashMap<>();
            myData.put("userId", me);
            myData.put("userName", Fb.myName());
            myData.put("userPhoto", Fb.myPhoto());
            myData.put("userTickLevel", Fb.me != null ? Fb.me.tickLevel : "none");
            myData.put("type", nextType);
            myData.put("createdAt", System.currentTimeMillis());
            post.reactionsMap.put(me, myData);
        }

        int nextTotal = Math.max(post.reactionsMap.size(), Math.max(0, curTotal + delta));
        post.likes = nextTotal;
        FastCache.markLocalReactionTap(post.id);

        if (nextTotal <= 0) {
            FastCache.setReactionTypes(post.id, Collections.emptyList());
        } else {
            List<String> updatedTypes = computeTopTypesFromMap(post.reactionsMap);
            if (updatedTypes.isEmpty()) {
                updatedTypes = new ArrayList<>();
                if (nextType != null) updatedTypes.add(nextType);
                if (nextTotal > (nextType != null ? 1 : 0) && !updatedTypes.contains("like")) {
                    updatedTypes.add("like");
                }
            }
            FastCache.setReactionTypes(post.id, updatedTypes);
        }

        // 0.0ms immediate UI update strictly for `currentUserId` across Feed, Fullscreen Photo, Watch, Profile & Detail!
        FastCache.setReactionState(c, post.id, nextType, nextTotal, true);

        DocumentReference postRef = Fb.db().collection("posts").document(post.id);
        DocumentReference reactRef = postRef.collection("reactions").document(me);

        if (nextType == null) {
            reactRef.delete();
            Map<String, Object> dot = new HashMap<>();
            dot.put("reactionsMap." + me, FieldValue.delete());
            dot.put("reactions." + me, FieldValue.delete());
            if (delta != 0) dot.put("likes", FieldValue.increment(delta));
            postRef.update(dot).addOnFailureListener(err -> {
                Map<String, Object> up = new HashMap<>();
                Map<String, Object> rmDel = new HashMap<>();
                rmDel.put(me, FieldValue.delete());
                up.put("reactionsMap", rmDel);
                Map<String, Object> rxDel = new HashMap<>();
                rxDel.put(me, FieldValue.delete());
                up.put("reactions", rxDel);
                if (delta != 0) up.put("likes", FieldValue.increment(delta));
                postRef.set(up, SetOptions.merge());
            });
        } else {
            final Map<String, Object> data = myData;
            reactRef.set(data).addOnSuccessListener(v -> {
                // Counters are updated atomically below; do not scan 250 reactions after each tap.
                com.friendbook.social.push.PushTrace.stage("reaction_write_ok",
                        "post=" + com.friendbook.social.push.PushTrace.id(post.id)
                        + " actor=" + com.friendbook.social.push.PushTrace.id(me)
                        + " receiver=" + com.friendbook.social.push.PushTrace.id(post.authorId)
                        + " self=" + me.equals(post.authorId) + " delta=" + delta);
                if (me.equals(Fb.uid()) && delta > 0 && post.authorId != null && !post.authorId.isEmpty() && !me.equals(post.authorId)) {
                    Notify.send(post.authorId, "like", Fb.myName() + " liked your post", post.id);
                } else {
                    com.friendbook.social.push.PushTrace.stage("reaction_push_skipped",
                            "reason=" + (!me.equals(Fb.uid()) ? "account_changed" : delta <= 0 ? "not_new_reaction"
                                    : post.authorId == null || post.authorId.isEmpty() ? "missing_owner" : "self_like"));
                }
            }).addOnFailureListener(e -> com.friendbook.social.push.PushTrace.failure("reaction_write_failed", e));

            Map<String, Object> dot = new HashMap<>();
            dot.put("reactionsMap." + me, data);
            if (delta != 0) dot.put("likes", FieldValue.increment(delta));
            postRef.update(dot).addOnFailureListener(err -> {
                Map<String, Object> nested = new HashMap<>();
                Map<String, Object> rmap = new HashMap<>();
                rmap.put(me, data);
                nested.put("reactionsMap", rmap);
                if (delta != 0) nested.put("likes", FieldValue.increment(delta));
                postRef.set(nested, SetOptions.merge());
            });

        }
    }

    /**
     * 0.0ms synchronous hydration from embedded post.reactionsMap BEFORE FeedAdapter binds UI!
     * Strictly checks `currentUserId = Fb.uid()`: if `currentUserId` has NOT reacted, sets `"none"` (uncolored)!
     */
    public static void hydrateFromPostInstant(final Models.Post post) {
        if (post == null || post.id == null) return;
        FastCache.ensureUserScope();
        if (FastCache.isLocalReactionInFlight(post.id)) return;
        String currentUserId = Fb.uid();
        if (post.reactionsMap != null && !post.reactionsMap.isEmpty()) {
            String myType = null;
            if (currentUserId != null && !currentUserId.isEmpty() && post.reactionsMap.containsKey(currentUserId)) {
                myType = Models.normalizeReactionType(post.reactionsMap.get(currentUserId), "like");
            }
            List<String> topTypes = computeTopTypesFromMap(post.reactionsMap);
            FastCache.setReactionTypes(post.id, topTypes);
            int count = Math.max(post.likes, post.reactionsMap.size());
            FastCache.seedReactionFromPostMap(post.id, myType != null ? myType : "none", count);
        } else if (post.likes <= 0) {
            FastCache.setReactionTypes(post.id, Collections.emptyList());
            FastCache.seedReactionFromPostMap(post.id, "none", 0);
        } else if (!FastCache.isReactionSynced(post.id)) {
            // Legacy post with likes > 0 but no reactionsMap yet: keep uncolored until subcollection verifies `currentUserId`!
            FastCache.seedReactionFromPostMap(post.id, "none", post.likes);
        }
    }

    /**
     * Syncs BOTH the current user's reaction AND the exact reaction count + top emoji stack
     * from post.reactionsMap + posts/{postId}/reactions, healing posts/{postId}.likes if out-of-sync!
     */
    public static void syncPostReactions(final Context c, final Models.Post post) {
        if (post == null || post.id == null) return;
        hydrateFromPostInstant(post);
        final String postId = post.id;
        if (FastCache.isReactionSynced(postId) || FastCache.isLocalReactionInFlight(postId)) return;
        FastCache.markReactionSynced(postId);
        reconcileFromCollection(c, postId, post.likes);
    }

    public static void reconcileFromCollection(final Context c, final String postId, final int storedLikes) {
        if (postId == null || postId.isEmpty()) return;
        final String me = Fb.uid();
        final Long tapBeforeFetch = FastCache.isLocalReactionInFlight(postId) ? System.currentTimeMillis() : null;
        Fb.db().collection("posts").document(postId).collection("reactions")
                .limit(250)
                .get()
                .addOnSuccessListener((QuerySnapshot snap) -> {
                    if (snap == null) return;
                    // Never let an older in-flight snapshot overwrite a fresh optimistic tap!
                    if (tapBeforeFetch == null && FastCache.isLocalReactionInFlight(postId)) return;
                    int realCount = snap.size();
                    String myType = null;
                    Map<String, Integer> freq = new LinkedHashMap<>();
                    for (String t : TYPES) freq.put(t, 0);
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        String uid = d.getString("userId");
                        if (uid == null || uid.isEmpty()) uid = d.getId();
                        String t = d.getString("type");
                        if (t == null || t.isEmpty() || !freq.containsKey(t)) t = "like";
                        freq.put(t, freq.get(t) + 1);
                        if (me != null && (me.equals(d.getId()) || me.equals(uid))) {
                            myType = t;
                        }
                    }
                    List<Map.Entry<String, Integer>> entries = new ArrayList<>(freq.entrySet());
                    Collections.sort(entries, (a, b) -> Integer.compare(b.getValue(), a.getValue()));
                    List<String> topTypes = new ArrayList<>();
                    for (Map.Entry<String, Integer> e : entries) {
                        if (e.getValue() > 0) topTypes.add(e.getKey());
                    }
                    FastCache.setReactionTypes(postId, topTypes);
                    int bestTotal = snap.size() < 250 ? realCount : Math.max(realCount, storedLikes);
                    // Self-heal posts/{postId}.likes when the subcollection count differs!
                    if (snap.size() < 250 && storedLikes != realCount) {
                        Map<String, Object> heal = new HashMap<>();
                        heal.put("likes", realCount);
                        Fb.db().collection("posts").document(postId).set(heal, SetOptions.merge());
                    }
                    FastCache.setReactionState(c, postId, myType, bestTotal, true);
                });
    }

    /**
     * 👥 friendbook Pro "People who reacted" ("Kis kis ne like kiya hai") Viewer for Posts!
     */
    public static void showWhoReacted(final Activity activity, final String postId) {
        if (activity == null || activity.isFinishing() || postId == null || postId.isEmpty()) return;

        LinearLayout root = Ui.col(activity);
        root.setBackgroundColor(Ui.card(activity));
        Ui.pad(root, 14, 12, 14, 8);

        // Header bar
        LinearLayout top = Ui.row(activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleTv = Ui.tvBold(activity, "People who reacted", 17, Ui.txt(activity));
        top.addView(titleTv, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(top);

        // Filter chips strip (All, 👍, ❤️, 😂, 😮, 😢, 😡)
        HorizontalScrollView filterScroll = new HorizontalScrollView(activity);
        filterScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout filterRow = Ui.row(activity);
        Ui.pad(filterRow, 0, 10, 0, 8);
        filterScroll.addView(filterRow);
        root.addView(filterScroll);
        root.addView(Ui.divider(activity));

        // Scrollable list of reactors
        ScrollView sv = new ScrollView(activity);
        LinearLayout listCol = Ui.col(activity);
        Ui.pad(listCol, 0, 6, 0, 10);
        TextView loading = Ui.tv(activity, "Loading reactions…", 14, Ui.sub(activity));
        Ui.pad(loading, 4, 18, 4, 18);
        loading.setGravity(Gravity.CENTER);
        listCol.addView(loading);
        sv.addView(listCol);
        root.addView(sv, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 360)));

        final AlertDialog dlg = new AlertDialog.Builder(activity)
                .setView(root)
                .setNegativeButton("Close", null)
                .create();
        dlg.show();

        final Map<String, ReactorEntry> byUid = new LinkedHashMap<>();
        final Runnable renderMerged = () -> {
            if (activity.isFinishing()) return;
            List<ReactorEntry> all = new ArrayList<>(byUid.values());
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String t : TYPES) counts.put(t, 0);
            for (ReactorEntry r : all) {
                counts.put(r.type, (counts.get(r.type) == null ? 0 : counts.get(r.type)) + 1);
            }
            Collections.sort(all, (a, b) -> Long.compare(b.createdAt, a.createdAt));
            titleTv.setText("People who reacted · " + all.size());
            renderReactorView(activity, dlg, filterRow, listCol, all, counts, "all");
        };

        // 1) Read from posts/{postId}.reactionsMap (100% readable across all users!)
        Fb.db().collection("posts").document(postId).get()
                .addOnSuccessListener(doc -> {
                    if (doc != null && doc.exists()) {
                        Models.Post p = Models.Post.fromDoc(doc);
                        for (Map.Entry<String, Map<String, Object>> e : p.reactionsMap.entrySet()) {
                            Map<String, Object> m = e.getValue();
                            ReactorEntry r = new ReactorEntry();
                            r.uid = e.getKey();
                            r.name = Format.str(m, "userName");
                            r.photo = Format.str(m, "userPhoto");
                            r.tickLevel = Format.str(m, "userTickLevel");
                            r.type = Format.strOr(m, "type", "like");
                            r.createdAt = Format.ts(m.get("createdAt"));
                            byUid.put(r.uid, r);
                        }
                        renderMerged.run();
                    }
                });

        // 2) Also merge from posts/{postId}/reactions subcollection
        Fb.db().collection("posts").document(postId).collection("reactions")
                .limit(200)
                .get()
                .addOnSuccessListener(snap -> {
                    if (snap != null) {
                        for (DocumentSnapshot d : snap.getDocuments()) {
                            ReactorEntry r = new ReactorEntry();
                            r.uid = Format.str(d.getData(), "userId");
                            if (r.uid.isEmpty()) r.uid = d.getId();
                            r.name = Format.str(d.getData(), "userName");
                            r.photo = Format.str(d.getData(), "userPhoto");
                            r.tickLevel = Format.str(d.getData(), "userTickLevel");
                            r.type = Format.strOr(d.getData(), "type", "like");
                            r.createdAt = Format.ts(d.get("createdAt"));
                            byUid.put(r.uid, r);
                        }
                    }
                    renderMerged.run();
                })
                .addOnFailureListener(e -> {
                    if (!byUid.isEmpty()) renderMerged.run();
                });
    }

    /**
     * 👥 friendbook Pro "People who reacted to Reel" Viewer!
     */
    public static void showReelReactors(final Activity activity, final Models.Reel reel) {
        if (activity == null || activity.isFinishing() || reel == null) return;
        final List<ReactorEntry> all = new ArrayList<>();
        final Map<String, Integer> counts = new LinkedHashMap<>();
        for (String t : TYPES) counts.put(t, 0);

        Map<String, String> reacts = new LinkedHashMap<>();
        if (reel.reactions != null) reacts.putAll(reel.reactions);
        if (reel.likedBy != null) {
            for (String u : reel.likedBy) {
                if (u != null && !u.isEmpty() && !reacts.containsKey(u)) reacts.put(u, "love");
            }
        }
        for (Map.Entry<String, String> e : reacts.entrySet()) {
            ReactorEntry r = new ReactorEntry();
            r.uid = e.getKey();
            r.type = (e.getValue() == null || e.getValue().isEmpty()) ? "love" : e.getValue();
            Models.User cached = FastCache.getUser(r.uid);
            if (cached != null) {
                r.name = cached.displayName;
                r.photo = cached.photoUrl;
                r.tickLevel = cached.tickLevel;
            }
            all.add(r);
            counts.put(r.type, (counts.get(r.type) == null ? 0 : counts.get(r.type)) + 1);
        }

        LinearLayout root = Ui.col(activity);
        root.setBackgroundColor(Ui.card(activity));
        Ui.pad(root, 14, 12, 14, 8);
        TextView titleTv = Ui.tvBold(activity, "Reel reactions · " + Math.max(all.size(), reel.likes), 17, Ui.txt(activity));
        root.addView(titleTv);

        HorizontalScrollView filterScroll = new HorizontalScrollView(activity);
        filterScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout filterRow = Ui.row(activity);
        Ui.pad(filterRow, 0, 10, 0, 8);
        filterScroll.addView(filterRow);
        root.addView(filterScroll);
        root.addView(Ui.divider(activity));

        ScrollView sv = new ScrollView(activity);
        LinearLayout listCol = Ui.col(activity);
        Ui.pad(listCol, 0, 6, 0, 10);
        sv.addView(listCol);
        root.addView(sv, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 320)));

        final AlertDialog dlg = new AlertDialog.Builder(activity)
                .setView(root)
                .setNegativeButton("Close", null)
                .create();
        dlg.show();

        renderReactorView(activity, dlg, filterRow, listCol, all, counts, "all");
    }

    /**
     * 👥 friendbook Pro "People who reacted to comment" Viewer!
     */
    public static void showCommentReactors(final Activity activity, final Models.Comment comment) {
        if (activity == null || activity.isFinishing() || comment == null) return;
        Map<String, String> reacts = comment.reactions != null ? comment.reactions : new HashMap<>();
        final List<ReactorEntry> all = new ArrayList<>();
        final Map<String, Integer> counts = new LinkedHashMap<>();
        for (String t : TYPES) counts.put(t, 0);

        for (Map.Entry<String, String> e : reacts.entrySet()) {
            if (e.getKey() == null || e.getKey().isEmpty()) continue;
            ReactorEntry r = new ReactorEntry();
            r.uid = e.getKey();
            r.type = (e.getValue() == null || e.getValue().isEmpty()) ? "like" : e.getValue();
            Models.User cached = FastCache.getUser(r.uid);
            if (cached != null) {
                r.name = cached.displayName;
                r.photo = cached.photoUrl;
                r.tickLevel = cached.tickLevel;
            }
            all.add(r);
            counts.put(r.type, (counts.get(r.type) == null ? 0 : counts.get(r.type)) + 1);
        }

        LinearLayout root = Ui.col(activity);
        root.setBackgroundColor(Ui.card(activity));
        Ui.pad(root, 14, 12, 14, 8);
        TextView titleTv = Ui.tvBold(activity, "Comment reactions · " + Math.max(all.size(), comment.likes), 17, Ui.txt(activity));
        root.addView(titleTv);

        HorizontalScrollView filterScroll = new HorizontalScrollView(activity);
        filterScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout filterRow = Ui.row(activity);
        Ui.pad(filterRow, 0, 10, 0, 8);
        filterScroll.addView(filterRow);
        root.addView(filterScroll);
        root.addView(Ui.divider(activity));

        ScrollView sv = new ScrollView(activity);
        LinearLayout listCol = Ui.col(activity);
        Ui.pad(listCol, 0, 6, 0, 10);
        sv.addView(listCol);
        root.addView(sv, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 320)));

        final AlertDialog dlg = new AlertDialog.Builder(activity)
                .setView(root)
                .setNegativeButton("Close", null)
                .create();
        dlg.show();

        renderReactorView(activity, dlg, filterRow, listCol, all, counts, "all");
    }

    private static void renderReactorView(
            final Activity activity,
            final AlertDialog dlg,
            final LinearLayout filterRow,
            final LinearLayout listCol,
            final List<ReactorEntry> all,
            final Map<String, Integer> counts,
            final String activeFilter) {

        filterRow.removeAllViews();
        addFilterChip(activity, filterRow, "All " + all.size(), "all".equals(activeFilter), () ->
                renderReactorView(activity, dlg, filterRow, listCol, all, counts, "all"));
        for (int i = 0; i < TYPES.length; i++) {
            final String t = TYPES[i];
            Integer c = counts.get(t);
            if (c != null && c > 0) {
                addFilterChip(activity, filterRow, EMOJI[i] + " " + c, t.equals(activeFilter), () ->
                        renderReactorView(activity, dlg, filterRow, listCol, all, counts, t));
            }
        }

        listCol.removeAllViews();
        List<ReactorEntry> shown = new ArrayList<>();
        for (ReactorEntry r : all) {
            if ("all".equals(activeFilter) || activeFilter.equals(r.type)) shown.add(r);
        }
        if (shown.isEmpty()) {
            TextView empty = Ui.tv(activity, "No reactions yet — be the first to like!", 14, Ui.sub(activity));
            empty.setGravity(Gravity.CENTER);
            Ui.pad(empty, 8, 28, 8, 28);
            listCol.addView(empty);
            return;
        }

        for (final ReactorEntry r : shown) {
            LinearLayout row = Ui.row(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            Ui.pad(row, 4, 8, 4, 8);

            // Avatar + overlaid reaction emoji badge
            FrameLayout avWrap = new FrameLayout(activity);
            int avSize = Ui.dp(activity, 44);
            final ImageView av = new ImageView(activity);
            avWrap.addView(av, new FrameLayout.LayoutParams(avSize, avSize));

            TextView badge = Ui.tv(activity, emojiFor(r.type), 12, Color.BLACK);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(Ui.roundStroke(activity, Ui.card(activity), 11, Ui.div(activity), 1));
            int bs = Ui.dp(activity, 21);
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(bs, bs, Gravity.BOTTOM | Gravity.END);
            avWrap.addView(badge, bp);
            row.addView(avWrap, new LinearLayout.LayoutParams(avSize + Ui.dp(activity, 4), avSize + Ui.dp(activity, 2)));

            // Name + tick + time
            LinearLayout mid = Ui.col(activity);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1);
            mp.leftMargin = Ui.dp(activity, 10);

            LinearLayout nameRow = Ui.row(activity);
            nameRow.setGravity(Gravity.CENTER_VERTICAL);
            final TextView nameTv = Ui.tvBold(activity,
                    (r.name != null && !r.name.isEmpty()) ? r.name : "Loading…", 14, Ui.txt(activity));
            nameRow.addView(nameTv);
            final ImageView tickIv = new ImageView(activity);
            int ts = Ui.dp(activity, 15);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ts, ts);
            tp.leftMargin = Ui.dp(activity, 4);
            Icons.setTickBadge(tickIv, r.tickLevel, false);
            nameRow.addView(tickIv, tp);
            mid.addView(nameRow);

            String sub = labelFor(r.type) + (r.createdAt > 0 ? " · " + Format.ago(r.createdAt) : "");
            if (r.uid != null && r.uid.equals(Fb.uid())) sub = "You · " + sub;
            mid.addView(Ui.tv(activity, sub, 12, Ui.sub(activity)));
            row.addView(mid, mp);

            // Action button: View Profile or Message
            boolean isMe = r.uid != null && r.uid.equals(Fb.uid());
            TextView act = Ui.roundChip(activity, isMe ? "You" : "Profile",
                    isMe ? Ui.inputBg(activity) : 0xFFE7F3FF,
                    isMe ? Ui.sub(activity) : Ui.BLUE, 12);
            Ui.pad(act, 12, 6, 12, 6);
            row.addView(act);

            if (r.photo != null && !r.photo.isEmpty()) {
                Ui.avatar(activity, av, r.photo);
            } else {
                Ui.avatar(activity, av, null);
            }

            // Hydrate missing user profile details from FastCache or Firestore!
            if (r.uid != null && (r.name == null || r.name.isEmpty())) {
                Models.User cached = FastCache.getUser(r.uid);
                if (cached != null) {
                    r.name = cached.displayName;
                    r.photo = cached.photoUrl;
                    r.tickLevel = cached.tickLevel;
                    nameTv.setText(r.name);
                    Ui.avatar(activity, av, r.photo);
                    Icons.setTickBadge(tickIv, r.tickLevel, false);
                } else {
                    Fb.getUser(r.uid).addOnSuccessListener(u -> {
                        if (u == null || activity.isFinishing()) return;
                        FastCache.putUser(u);
                        r.name = u.displayName;
                        r.photo = u.photoUrl;
                        r.tickLevel = u.tickLevel;
                        nameTv.setText(u.displayName != null && !u.displayName.isEmpty() ? u.displayName : "friendbook User");
                        Ui.avatar(activity, av, u.photoUrl);
                        Icons.setTickBadge(tickIv, u.tickLevel, false);
                    });
                }
            }

            View.OnClickListener openProf = v -> {
                if (r.uid == null || r.uid.isEmpty()) return;
                dlg.dismiss();
                Intent i = new Intent(activity, ProfileActivity.class);
                i.putExtra("uid", r.uid);
                activity.startActivity(i);
            };
            row.setOnClickListener(openProf);
            act.setOnClickListener(openProf);
            if (!isMe && r.uid != null && !r.uid.isEmpty()) {
                row.setOnLongClickListener(v -> {
                    dlg.dismiss();
                    Intent chat = new Intent(activity, ChatActivity.class);
                    chat.putExtra("uid", r.uid);
                    chat.putExtra("name", r.name != null ? r.name : "");
                    activity.startActivity(chat);
                    return true;
                });
            }
            listCol.addView(row);
        }
    }

    private static void addFilterChip(Context c, LinearLayout row, String label, boolean active, Runnable click) {
        TextView chip = Ui.roundChip(c, label,
                active ? Ui.BLUE : Ui.inputBg(c),
                active ? Color.WHITE : Ui.txt(c), 13);
        Ui.pad(chip, 13, 6, 13, 6);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = Ui.dp(c, 6);
        chip.setOnClickListener(v -> click.run());
        row.addView(chip, lp);
    }
}
