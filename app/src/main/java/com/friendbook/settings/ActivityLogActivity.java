package com.friendbook.settings;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.core.Fb;
import com.friendbook.core.Format;
import com.friendbook.core.Ui;
import com.friendbook.models.Models;
import com.friendbook.post.PostDetailActivity;
import com.google.firebase.firestore.DocumentSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 📋 Activity log — Facebook-Lite filterable history:
 * Filter chips: All · 📝 Posts · 💬 Comments · 📖 Stories + shortcut to Archive & Trash.
 */
public class ActivityLogActivity extends Activity {

    static class Entry {
        String kind; // "post" | "comment" | "story"
        String text;
        String refId;
        long when;
    }

    private final List<Entry> allEntries = new ArrayList<>();
    private final List<Entry> filtered = new ArrayList<>();
    private String filter = "all";
    private final TextView[] chips = new TextView[4];
    private Adapter adapter;
    private TextView empty;
    private int pending = 3;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));

        LinearLayout bar = Ui.pageBar(this, "Activity log");
        TextView archBtn = Ui.roundChip(this, "📦 Archive & Trash", 0x33FFFFFF, Color.WHITE, 12);
        Ui.pad(archBtn, 10, 5, 10, 5);
        archBtn.setOnClickListener(v -> startActivity(new Intent(this, ArchiveTrashActivity.class)));
        bar.addView(archBtn);
        root.addView(bar);

        String[] labels = {"All", "📝 Posts", "💬 Comments", "📖 Stories"};
        String[] keys = {"all", "post", "comment", "story"};
        LinearLayout chipRow = Ui.row(this);
        chipRow.setBackgroundColor(Ui.card(this));
        Ui.pad(chipRow, 10, 8, 10, 8);
        for (int i = 0; i < 4; i++) {
            final String k = keys[i];
            TextView c = Ui.tvBold(this, labels[i], 12, Ui.sub(this));
            c.setGravity(Gravity.CENTER);
            Ui.pad(c, 10, 6, 10, 6);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) lp.leftMargin = Ui.dp(this, 6);
            chipRow.addView(c, lp);
            chips[i] = c;
            c.setOnClickListener(v -> { filter = k; updateChips(); applyFilter(); });
        }
        root.addView(chipRow);
        root.addView(Ui.divider(this));
        updateChips();

        empty = Ui.tv(this, "Loading…", 14, Ui.sub(this));
        empty.setGravity(Gravity.CENTER);
        Ui.pad(empty, 24, 60, 24, 0);
        root.addView(empty);

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void updateChips() {
        String[] keys = {"all", "post", "comment", "story"};
        for (int i = 0; i < 4; i++) {
            boolean on = keys[i].equals(filter);
            chips[i].setBackground(Ui.round(this, on ? Ui.BLUE : Ui.inputBg(this), 16));
            chips[i].setTextColor(on ? Color.WHITE : Ui.sub(this));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        String me = Fb.uid();
        if (me == null) { finish(); return; }
        allEntries.clear();
        pending = 3;

        Fb.db().collection("posts").whereEqualTo("authorId", me).limit(80).get()
                .addOnSuccessListener(snap -> {
                    for (DocumentSnapshot d : snap) {
                        Models.Post p = Models.Post.fromDoc(d);
                        Entry e = new Entry();
                        e.kind = "post";
                        e.text = p.content == null || p.content.isEmpty()
                                ? (p.isMarketplace() ? "🛒 " + p.marketTitle : "[" + p.mediaType + " post]")
                                : p.content;
                        e.refId = p.id;
                        e.when = p.createdAt;
                        allEntries.add(e);
                    }
                    done();
                }).addOnFailureListener(e -> done());

        Fb.db().collection("comments").whereEqualTo("authorId", me).limit(80).get()
                .addOnSuccessListener(snap -> {
                    for (DocumentSnapshot d : snap) {
                        Models.Comment c = Models.Comment.fromDoc(d);
                        Entry e = new Entry();
                        e.kind = "comment";
                        e.text = c.content;
                        e.refId = c.postId;
                        e.when = c.createdAt;
                        allEntries.add(e);
                    }
                    done();
                }).addOnFailureListener(e -> done());

        Fb.db().collection("stories").whereEqualTo("authorId", me).limit(50).get()
                .addOnSuccessListener(snap -> {
                    for (DocumentSnapshot d : snap) {
                        Models.Story s = Models.Story.fromDoc(d);
                        Entry e = new Entry();
                        e.kind = "story";
                        e.text = s.caption == null || s.caption.isEmpty()
                                ? ("text".equals(s.mediaType) ? "Text story" : "Photo/video story")
                                : s.caption;
                        e.refId = s.id;
                        e.when = s.createdAt;
                        allEntries.add(e);
                    }
                    done();
                }).addOnFailureListener(e -> done());
    }

    private void done() {
        if (--pending > 0) return;
        Collections.sort(allEntries, (a, b) -> Long.compare(b.when, a.when));
        applyFilter();
    }

    private void applyFilter() {
        filtered.clear();
        for (Entry e : allEntries) {
            if ("all".equals(filter) || filter.equals(e.kind)) filtered.add(e);
        }
        adapter.notifyDataSetChanged();
        empty.setText("No matching activity yet.");
        empty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
    }

    class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        @Override public int getItemCount() { return filtered.size(); }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = Ui.row(ActivityLogActivity.this);
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            row.setBackgroundColor(Ui.card(ActivityLogActivity.this));
            Ui.pad(row, 12, 10, 12, 10);

            TextView icon = Ui.tv(ActivityLogActivity.this, "", 18, Ui.txt(ActivityLogActivity.this));
            row.addView(icon);
            LinearLayout mid = Ui.col(ActivityLogActivity.this);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1);
            mp.leftMargin = Ui.dp(ActivityLogActivity.this, 10);
            TextView text = Ui.tv(ActivityLogActivity.this, "", 14, Ui.txt(ActivityLogActivity.this));
            text.setMaxLines(2);
            TextView meta = Ui.tv(ActivityLogActivity.this, "", 11, Ui.sub(ActivityLogActivity.this));
            mid.addView(text);
            mid.addView(meta);
            row.addView(mid, mp);
            return new VH(row, icon, text, meta);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Entry e = filtered.get(position);
            h.icon.setText("post".equals(e.kind) ? "📝" : "story".equals(e.kind) ? "📖" : "💬");
            h.text.setText(e.text);
            String label = "post".equals(e.kind) ? "You shared a post"
                    : "story".equals(e.kind) ? "You added to your story" : "You commented";
            h.meta.setText(label + " · " + Format.ago(e.when));
            h.itemView.setOnClickListener(v -> {
                if ("story".equals(e.kind)) {
                    startActivity(new Intent(ActivityLogActivity.this, ArchiveTrashActivity.class));
                    return;
                }
                if (e.refId == null || e.refId.isEmpty() || e.refId.startsWith("reel_")) return;
                Intent i = new Intent(ActivityLogActivity.this, PostDetailActivity.class);
                i.putExtra("post_id", e.refId);
                startActivity(i);
            });
        }

        class VH extends RecyclerView.ViewHolder {
            TextView icon, text, meta;
            VH(View v, TextView i, TextView t, TextView m) {
                super(v);
                icon = i; text = t; meta = m;
            }
        }
    }
}
