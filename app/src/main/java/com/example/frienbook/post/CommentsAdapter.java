package com.example.frienbook.post;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Format;
import com.example.frienbook.core.Notify;
import com.example.frienbook.core.Ui;
import com.example.frienbook.models.Models;
import com.example.frienbook.profile.ProfileActivity;
import com.google.firebase.firestore.FieldValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Comments with 2-level nested replies (Facebook style). */
public class CommentsAdapter extends RecyclerView.Adapter<CommentsAdapter.VH> {

    public interface Host {
        void replyTo(Models.Comment c);
        void changed();
    }

    private static class Row {
        Models.Comment c;
        int depth;
    }

    private final Activity activity;
    private final Host host;
    private final List<Row> rows = new ArrayList<>();
    /** Override which doc holds the comments counter (default: posts/{postId}). */
    public String counterCollection = null;
    public String counterId = null;

    public CommentsAdapter(Activity a, Host h) {
        this.activity = a;
        this.host = h;
    }

    /** Rebuild the flattened display list from raw comments. */
    public void setAll(List<Models.Comment> all) {
        rows.clear();
        Map<String, List<Models.Comment>> byParent = new HashMap<>();
        List<Models.Comment> roots = new ArrayList<>();
        for (Models.Comment c : all) {
            if (c.parentId == null || c.parentId.isEmpty()) roots.add(c);
            else {
                List<Models.Comment> l = byParent.get(c.parentId);
                if (l == null) { l = new ArrayList<>(); byParent.put(c.parentId, l); }
                l.add(c);
            }
        }
        roots.sort((a, b) -> Long.compare(a.createdAt, b.createdAt));
        for (Models.Comment r : roots) {
            addRow(r, 0, byParent);
        }
        notifyDataSetChanged();
    }

    private void addRow(Models.Comment c, int depth, Map<String, List<Models.Comment>> byParent) {
        Row r = new Row();
        r.c = c;
        r.depth = depth;
        rows.add(r);
        List<Models.Comment> kids = byParent.get(c.id);
        if (kids != null) {
            kids.sort((a, b) -> Long.compare(a.createdAt, b.createdAt));
            for (Models.Comment k : kids) addRow(k, Math.min(depth + 1, 2), byParent);
        }
    }

    @Override public int getItemCount() { return rows.size(); }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Activity c = activity;
        LinearLayout outer = Ui.col(c);
        outer.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));

        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.TOP);
        ImageView av = new ImageView(c);
        int s = Ui.dp(c, 34);
        row.addView(av, new LinearLayout.LayoutParams(s, s));

        LinearLayout right = Ui.col(c);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1);
        rp.leftMargin = Ui.dp(c, 8);
        LinearLayout bubble = Ui.col(c);
        bubble.setBackground(Ui.round(c, Ui.inputBg(c), 12));
        Ui.pad(bubble, 10, 7, 10, 7);
        TextView name = Ui.tvBold(c, "", 13, Ui.txt(c));
        TextView text = Ui.tv(c, "", 14, Ui.txt(c));
        ImageView img = new ImageView(c);
        img.setVisibility(View.GONE);
        bubble.addView(name);
        bubble.addView(text);
        bubble.addView(img, new LinearLayout.LayoutParams(Ui.dp(c, 180), Ui.dp(c, 120)));
        right.addView(bubble);

        LinearLayout meta = Ui.row(c);
        meta.setPadding(Ui.dp(c, 12), Ui.dp(c, 3), 0, Ui.dp(c, 4));
        TextView time = Ui.tv(c, "", 12, Ui.sub(c));
        TextView like = Ui.tvBold(c, "Like", 12, Ui.sub(c));
        TextView reply = Ui.tvBold(c, "Reply", 12, Ui.sub(c));
        TextView likesCount = Ui.tvBold(c, "", 12, Ui.BLUE);
        meta.addView(time);
        meta.addView(spacer(c));
        meta.addView(like);
        meta.addView(spacer(c));
        meta.addView(reply);
        meta.addView(spacer(c));
        meta.addView(likesCount);
        right.addView(meta);

        outer.addView(row);
        row.addView(right, rp);
        return new VH(outer, av, name, text, img, time, like, reply, likesCount);
    }

    private View spacer(Activity c) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 14), 1));
        return v;
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Row r = rows.get(position);
        Models.Comment c = r.c;
        RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) h.itemView.getLayoutParams();
        lp.leftMargin = Ui.dp(activity, 10 + r.depth * 34);
        lp.rightMargin = Ui.dp(activity, 10);
        lp.topMargin = Ui.dp(activity, 8);
        h.itemView.setLayoutParams(lp);

        Ui.avatar(activity, h.av, c.authorPhoto);
        h.name.setText(c.authorName + (c.authorId.equals(Fb.uid()) ? " (you)" : ""));
        h.text.setText(c.content);
        if (c.imageUrl != null && !c.imageUrl.isEmpty()) {
            h.img.setVisibility(View.VISIBLE);
            Ui.img(activity, h.img, c.imageUrl);
            h.img.setOnClickListener(v -> {
                Intent i = new Intent(activity, FullscreenMediaActivity.class);
                i.putExtra("type", "image");
                i.putExtra("url", c.imageUrl);
                activity.startActivity(i);
            });
        } else h.img.setVisibility(View.GONE);

        h.time.setText(Format.ago(c.createdAt));
        boolean liked = c.likedBy.contains(Fb.uid());
        h.like.setTextColor(liked ? Ui.BLUE : Ui.sub(activity));
        h.likesCount.setText(c.likes > 0 ? "👍 " + Format.count(c.likes) : "");

        h.like.setOnClickListener(v -> toggleLike(c));
        h.reply.setOnClickListener(v -> host.replyTo(c));
        View.OnClickListener prof = v -> {
            Intent i = new Intent(activity, ProfileActivity.class);
            i.putExtra("uid", c.authorId);
            activity.startActivity(i);
        };
        h.av.setOnClickListener(prof);
        h.name.setOnClickListener(prof);

        h.itemView.setOnLongClickListener(v -> {
            boolean mine = c.authorId.equals(Fb.uid());
            String[] opts = mine ? new String[]{"Reply", "✏️ Edit comment", "Delete comment"}
                    : new String[]{"Reply", "Report comment"};
            Ui.choose(activity, "Comment", opts, w -> {
                if (w == 0) host.replyTo(c);
                else if (mine) {
                    if (w == 1) editComment(c);
                    else deleteComment(c);
                } else {
                    Fb.db().collection("reports").add(Fb.map(
                            "commentId", c.id, "reportedBy", Fb.uid(),
                            "createdAt", FieldValue.serverTimestamp()));
                    Ui.toast(activity, "Reported");
                }
            });
            return true;
        });
    }

    /** ✏️ Edit your own comment (FB Lite: Edit option on long-press). */
    private void editComment(Models.Comment c) {
        if (c.postId == null || c.id == null) return;
        android.widget.EditText in = new android.widget.EditText(activity);
        in.setText(c.content);
        in.setSelection(in.getText().length());
        android.widget.LinearLayout box = Ui.col(activity);
        Ui.pad(box, 18, 8, 18, 0);
        box.addView(in, new android.widget.LinearLayout.LayoutParams(-1, Ui.dp(activity, 48)));
        new android.app.AlertDialog.Builder(activity)
                .setTitle("✏️ Edit comment")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String t = in.getText().toString().trim();
                    if (t.isEmpty()) return;
                    Map<String, Object> m = new HashMap<>();
                    m.put("content", t);
                    m.put("edited", true);
                    Fb.db().collection("comments").document(c.id).update(m)
                            .addOnSuccessListener(x -> Ui.toast(activity, "Comment updated ✓"))
                            .addOnFailureListener(e -> Ui.toast(activity, e.getMessage()));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void toggleLike(Models.Comment c) {
        String me = Fb.uid();
        if (me == null) return;
        boolean liked = c.likedBy.contains(me);
        Map<String, Object> m = new HashMap<>();
        m.put("likedBy", liked ? FieldValue.arrayRemove(me) : FieldValue.arrayUnion(me));
        m.put("likes", FieldValue.increment(liked ? -1 : 1));
        Fb.db().collection("comments").document(c.id).update(m);
        if (!liked) Notify.send(c.authorId, "comment_like", "liked your comment", c.postId);
    }

    private void deleteComment(Models.Comment c) {
        Ui.confirm(activity, "Delete comment?", "", "Delete", () -> {
            Fb.db().collection("comments").document(c.id).delete();
            String col = counterCollection != null ? counterCollection : "posts";
            String id = counterId != null ? counterId : c.postId;
            Fb.db().collection(col).document(id)
                    .set(com.example.frienbook.core.Fb.map("comments", FieldValue.increment(-1)),
                            com.google.firebase.firestore.SetOptions.merge());
            host.changed();
        });
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView av, img;
        TextView name, text, time, like, reply, likesCount;
        VH(View v, ImageView a, TextView n, TextView t, ImageView i, TextView tm, TextView l, TextView r, TextView lc) {
            super(v);
            av = a; name = n; text = t; img = i; time = tm; like = l; reply = r; likesCount = lc;
        }
    }
}
