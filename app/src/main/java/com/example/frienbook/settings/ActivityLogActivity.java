package com.example.frienbook.settings;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Format;
import com.example.frienbook.core.Ui;
import com.example.frienbook.models.Models;
import com.example.frienbook.post.PostDetailActivity;
import com.example.frienbook.core.cloud.DocumentSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Activity log: everything YOU posted, commented or liked — one timeline. */
public class ActivityLogActivity extends Activity {

    static class Entry {
        String kind;   // "post" | "comment"
        String text;
        String refId;
        long when;
    }

    private final List<Entry> entries = new ArrayList<>();
    private Adapter adapter;
    private TextView empty;
    private int pending = 2;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "📋 Activity log"));

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

    @Override
    protected void onStart() {
        super.onStart();
        String me = Fb.uid();
        if (me == null) { finish(); return; }
        entries.clear();
        pending = 2;

        Fb.db().collection("posts").whereEqualTo("authorId", me).limit(50).get()
                .addOnSuccessListener(snap -> {
                    for (DocumentSnapshot d : snap) {
                        Models.Post p = Models.Post.fromDoc(d);
                        Entry e = new Entry();
                        e.kind = "post";
                        e.text = p.content == null || p.content.isEmpty() ? "[" + p.mediaType + " post]" : p.content;
                        e.refId = p.id;
                        e.when = p.createdAt;
                        entries.add(e);
                    }
                    done();
                })
                .addOnFailureListener(e -> done());

        Fb.db().collection("comments").whereEqualTo("authorId", me).limit(50).get()
                .addOnSuccessListener(snap -> {
                    for (DocumentSnapshot d : snap) {
                        Models.Comment c = Models.Comment.fromDoc(d);
                        Entry e = new Entry();
                        e.kind = "comment";
                        e.text = c.content;
                        e.refId = c.postId;
                        e.when = c.createdAt;
                        entries.add(e);
                    }
                    done();
                })
                .addOnFailureListener(e -> done());
    }

    private void done() {
        if (--pending > 0) return;
        Collections.sort(entries, (a, b) -> Long.compare(b.when, a.when));
        adapter.notifyDataSetChanged();
        empty.setText("No activity yet — post something!");
        empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
    }

    class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        @Override public int getItemCount() { return entries.size(); }

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
            Entry e = entries.get(position);
            h.icon.setText("post".equals(e.kind) ? "📝" : "💬");
            h.text.setText(e.text);
            h.meta.setText(("post".equals(e.kind) ? "You posted this" : "You commented") + " · " + Format.ago(e.when));
            h.itemView.setOnClickListener(v -> {
                if (e.refId == null || e.refId.isEmpty()) return;
                Intent i = new Intent(ActivityLogActivity.this, PostDetailActivity.class);
                i.putExtra("post_id", e.refId.startsWith("reel_") ? "" : e.refId);
                if (!i.getStringExtra("post_id").isEmpty()) startActivity(i);
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
