package com.example.frienbook.post;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Notify;
import com.example.frienbook.core.Ui;
import com.example.frienbook.core.cloud.DocumentReference;
import com.example.frienbook.core.cloud.DocumentSnapshot;
import com.example.frienbook.core.cloud.FieldValue;

/** Facebook-style reactions: 👍 ❤️ 😂 😮 😢 😡 with long-press picker. */
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

    /** Apply / switch / remove a reaction transactionally — mirrors the Flutter logic. */
    public static void apply(Context c, com.example.frienbook.models.Models.Post post, String type) {
        String me = Fb.uid();
        if (me == null) return;
        DocumentReference postRef = Fb.db().collection("posts").document(post.id);
        DocumentReference reactRef = postRef.collection("reactions").document(me);
        Fb.db().runTransaction(tx -> {
            DocumentSnapshot existing = tx.get(reactRef);
            DocumentSnapshot postSnap = tx.get(postRef);
            long likes = postSnap.exists() && postSnap.get("likes") instanceof Number
                    ? ((Number) postSnap.get("likes")).longValue() : 0;
            if (existing.exists()) {
                String oldType = existing.getString("type");
                if (type == null || type.equals(oldType)) {
                    tx.delete(reactRef);
                    // Incremented by the Worker on the stored row, so two people reacting at the same
                    // moment cannot cost one of them a like.
                    tx.update(postRef, "likes", FieldValue.increment(-1));
                } else {
                    tx.update(reactRef, "type", type, "createdAt", FieldValue.serverTimestamp());
                }
            } else if (type != null) {
                java.util.Map<String, Object> data = new java.util.HashMap<>();
                data.put("userId", me);
                data.put("type", type);
                data.put("createdAt", FieldValue.serverTimestamp());
                tx.set(reactRef, data);
                tx.update(postRef, "likes", FieldValue.increment(1));
            }
            return null;
        }).addOnSuccessListener(x -> {
            // notify only on a fresh reaction
            if (type != null && post.authorId != null && !post.authorId.equals(Fb.uid())) {
                reactRef.get().addOnSuccessListener(s -> {
                    if (s.exists() && type.equals(s.getString("type")))
                        Notify.like(c, post.authorId, post.id);
                });
            }
        });
    }

    /** Toggle plain "like" (tap on the Like button). */
    public static void tapLike(Context c, com.example.frienbook.models.Models.Post post) {
        String me = Fb.uid();
        if (me == null) return;
        DocumentReference reactRef = Fb.db().collection("posts").document(post.id)
                .collection("reactions").document(me);
        reactRef.get().addOnSuccessListener(s -> apply(c, post, s.exists() ? null : "like"));
    }
}
