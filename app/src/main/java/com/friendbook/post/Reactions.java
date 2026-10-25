package com.friendbook.post;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.friendbook.core.FastCache;
import com.friendbook.core.Fb;
import com.friendbook.core.Notify;
import com.friendbook.core.Ui;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;

import java.util.HashMap;
import java.util.Map;

/**
 * ⚡ Zero-Latency Facebook-Lite Reactions: 👍 ❤️ 😂 😮 😢 😡
 * Updates UI optimistically in 0.0ms via {@link FastCache} and syncs via Firestore's
 * offline-capable write queue (zero blocking server transactions — works on 2G & offline!).
 */
public class Reactions {

    public static final String[] TYPES = {"like", "love", "haha", "wow", "sad", "angry"};
    public static final String[] EMOJI = {"👍", "❤️", "😂", "😮", "😢", "😡"};
    public static final String[] LABELS = {"Like", "Love", "Haha", "Wow", "Sad", "Angry"};

    public interface OnPick { void pick(String type); }

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
            case "haha": case "wow": case "sad": case "angry": return 0xFFF7B928;
            default: return Ui.BLUE;
        }
    }

    /** Long-press popup with the 6 reactions. */
    public static void show(View anchor, OnPick cb) {
        Context c = anchor.getContext();
        LinearLayout row = Ui.row(c);
        row.setBackground(Ui.round(c, Ui.card(c), 28));
        row.setElevation(Ui.dp(c, 8));
        int pad = Ui.dp(c, 8);
        row.setPadding(pad, pad, pad, pad);
        final PopupWindow pop = new PopupWindow(row, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pop.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        pop.setElevation(Ui.dp(c, 12));
        for (int i = 0; i < TYPES.length; i++) {
            final String type = TYPES[i];
            TextView e = Ui.tv(c, EMOJI[i], 30, Color.BLACK);
            int p = Ui.dp(c, 5);
            e.setPadding(p, p, p, p);
            e.setOnClickListener(v -> {
                Ui.haptic(v);
                pop.dismiss();
                cb.pick(type);
            });
            row.addView(e);
        }
        row.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        pop.showAtLocation(anchor, Gravity.NO_GRAVITY,
                loc[0], loc[1] - row.getMeasuredHeight() - Ui.dp(c, 4));
    }

    /**
     * ⚡ 0.0ms Optimistic Reaction Apply / Switch / Remove:
     * Updates FastCache + notifies UI immediately, then writes asynchronously through Firestore offline queue.
     */
    public static void apply(Context c, com.friendbook.models.Models.Post post, String type) {
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
        // 0.0ms immediate UI update across FeedAdapter & PostDetailActivity!
        FastCache.setReactionState(c, post.id, nextType, nextTotal, true);

        DocumentReference postRef = Fb.db().collection("posts").document(post.id);
        DocumentReference reactRef = postRef.collection("reactions").document(me);

        if (nextType == null) {
            reactRef.delete();
            if (delta != 0) postRef.update("likes", FieldValue.increment(delta));
        } else {
            Map<String, Object> data = new HashMap<>();
            data.put("userId", me);
            data.put("type", nextType);
            data.put("createdAt", FieldValue.serverTimestamp());
            reactRef.set(data);
            if (delta != 0) postRef.update("likes", FieldValue.increment(delta));
            if (delta > 0 && post.authorId != null && !post.authorId.equals(me)) {
                Notify.like(c, post.authorId, post.id);
            }
        }
    }

    /** Toggle plain "like" (0.0ms instant tap on the Like button). */
    public static void tapLike(Context c, com.friendbook.models.Models.Post post) {
        if (post == null || post.id == null) return;
        String prev = FastCache.getMyReaction(post.id);
        apply(c, post, prev != null ? null : "like");
    }
}
