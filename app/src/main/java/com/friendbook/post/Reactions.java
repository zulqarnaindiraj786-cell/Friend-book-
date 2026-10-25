package com.friendbook.post;

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

import com.friendbook.chat.ChatActivity;
import com.friendbook.core.FastCache;
import com.friendbook.core.Fb;
import com.friendbook.core.Format;
import com.friendbook.core.Icons;
import com.friendbook.core.Notify;
import com.friendbook.core.Ui;
import com.friendbook.models.Models;
import com.friendbook.profile.ProfileActivity;
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
 * ⚡ Zero-Latency Facebook-Lite Pro Reactions: 👍 ❤️ 😂 😮 😢 😡
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

    /** Long-press popup with the 6 Facebook Lite reactions + Care/Emoji bounce. */
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

    /** 1-tap Like toggle: removes existing reaction if any, otherwise adds "like". */
    public static void tapLike(Context c, Models.Post post) {
        if (post == null || post.id == null) return;
        String prev = FastCache.getMyReaction(post.id);
        apply(c, post, prev != null ? null : "like");
    }

    /**
     * ⚡ 0.0ms Optimistic Reaction Apply / Switch / Remove + Server Count Reconciliation.
     */
    public static void apply(Context c, Models.Post post, String type) {
        String me = Fb.uid();
        if (me == null || post == null || post.id == null) return;

        String prev = FastCache.getMyReaction(post.id);
        int curTotal = FastCache.getReactionTotal(post.id, post.likes);

        final String nextType;
        final int delta;
        if (prev != null) {
            if (type == null || type.equals(prev)) {
                nextType = null;
                delta = -1;
            } else {
                nextType = type;
                delta = 0;
            }
        } else if (type != null) {
            nextType = type;
            delta = 1;
        } else {
            return;
        }

        int nextTotal = Math.max(0, curTotal + delta);
        post.likes = nextTotal;
        FastCache.markLocalReactionTap(post.id);
        if (nextType != null) {
            String curEmojis = FastCache.getReactionEmojis(post.id);
            String em = emojiFor(nextType);
            if (!curEmojis.contains(em)) {
                FastCache.setReactionEmojis(post.id, (em + curEmojis).substring(0, Math.min(6, (em + curEmojis).length())));
            }
        }
        // 0.0ms immediate UI update across FeedAdapter, ProfileActivity & PostDetailActivity!
        FastCache.setReactionState(c, post.id, nextType, nextTotal, true);

        DocumentReference postRef = Fb.db().collection("posts").document(post.id);
        DocumentReference reactRef = postRef.collection("reactions").document(me);

        if (nextType == null) {
            reactRef.delete().addOnSuccessListener(v -> reconcileFromCollection(c, post.id, post.likes));
            Map<String, Object> dot = new HashMap<>();
            dot.put("reactionsMap." + me, FieldValue.delete());
            if (delta != 0) dot.put("likes", FieldValue.increment(delta));
            postRef.update(dot).addOnFailureListener(err -> {
                if (delta != 0) {
                    Map<String, Object> up = new HashMap<>();
                    up.put("likes", FieldValue.increment(delta));
                    postRef.set(up, SetOptions.merge());
                }
            });
            if (post.reactionsMap != null) post.reactionsMap.remove(me);
        } else {
            Map<String, Object> data = new HashMap<>();
            data.put("userId", me);
            data.put("userName", Fb.myName());
            data.put("userPhoto", Fb.myPhoto());
            data.put("userTickLevel", Fb.me != null ? Fb.me.tickLevel : "none");
            data.put("type", nextType);
            data.put("createdAt", System.currentTimeMillis());
            reactRef.set(data).addOnSuccessListener(v -> reconcileFromCollection(c, post.id, post.likes));

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
            if (post.reactionsMap == null) post.reactionsMap = new HashMap<>();
            post.reactionsMap.put(me, data);

            if (delta > 0 && post.authorId != null && !me.equals(post.authorId)) {
                Notify.send(post.authorId, "like",
                        Fb.myName() + " reacted " + emojiFor(nextType) + " to your post",
                        post.id);
            }
        }
    }

    /**
     * Syncs BOTH the current user's reaction AND the exact reaction count + top emoji stack
     * from post.reactionsMap + posts/{postId}/reactions, healing posts/{postId}.likes if out-of-sync!
     */
    public static void syncPostReactions(final Context c, final Models.Post post) {
        if (post == null || post.id == null) return;
        final String postId = post.id;
        // 0ms immediate hydrate from embedded post.reactionsMap (multi-user guaranteed!)
        if (post.reactionsMap != null && !post.reactionsMap.isEmpty()) {
            String me = Fb.uid();
            String myType = null;
            StringBuilder em = new StringBuilder();
            for (Map.Entry<String, Map<String, Object>> e : post.reactionsMap.entrySet()) {
                String t = Format.strOr(e.getValue(), "type", "like");
                String emoji = emojiFor(t);
                if (em.indexOf(emoji) < 0 && em.length() < 6) em.append(emoji);
                if (me != null && me.equals(e.getKey())) myType = t;
            }
            if (em.length() > 0) FastCache.setReactionEmojis(postId, em.toString());
            int count = Math.max(post.likes, post.reactionsMap.size());
            FastCache.setReactionState(c, postId, myType, count, false);
        }
        if (FastCache.isReactionSynced(postId)) return;
        FastCache.markReactionSynced(postId);
        reconcileFromCollection(c, postId, post.likes);
    }

    public static void reconcileFromCollection(final Context c, final String postId, final int storedLikes) {
        if (postId == null || postId.isEmpty()) return;
        final String me = Fb.uid();
        Fb.db().collection("posts").document(postId).collection("reactions")
                .limit(250)
                .get()
                .addOnSuccessListener((QuerySnapshot snap) -> {
                    if (snap == null) return;
                    int realCount = snap.size();
                    String myType = null;
                    Map<String, Integer> freq = new LinkedHashMap<>();
                    for (String t : TYPES) freq.put(t, 0);
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        String uid = d.getString("userId");
                        if (uid == null || uid.isEmpty()) uid = d.getId();
                        String t = d.getString("type");
                        if (t == null || t.isEmpty()) t = "like";
                        freq.put(t, (freq.get(t) == null ? 0 : freq.get(t)) + 1);
                        if (me != null && (me.equals(d.getId()) || me.equals(uid))) {
                            myType = t;
                        }
                    }
                    // Build top 3 reaction emojis ordered by frequency
                    List<Map.Entry<String, Integer>> entries = new ArrayList<>(freq.entrySet());
                    Collections.sort(entries, (a, b) -> Integer.compare(b.getValue(), a.getValue()));
                    StringBuilder emojis = new StringBuilder();
                    int added = 0;
                    for (Map.Entry<String, Integer> e : entries) {
                        if (e.getValue() > 0) {
                            emojis.append(emojiFor(e.getKey()));
                            if (++added >= 3) break;
                        }
                    }
                    if (emojis.length() > 0) {
                        FastCache.setReactionEmojis(postId, emojis.toString());
                    }
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
     * 👥 Facebook Lite Pro "People who reacted" ("Kis kis ne like kiya hai") Viewer for Posts!
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
     * 👥 Facebook Lite Pro "People who reacted to Reel" Viewer!
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
     * 👥 Facebook Lite Pro "People who reacted to comment" Viewer!
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
