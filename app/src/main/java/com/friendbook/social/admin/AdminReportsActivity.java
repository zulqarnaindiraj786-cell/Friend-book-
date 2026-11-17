package com.friendbook.social.admin;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.social.core.Admin;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Ui;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Admin reports queue: act on reported posts / comments / messages / users. */
public class AdminReportsActivity extends Activity {

    private final java.util.List<com.friendbook.social.core.Doc> reports = new ArrayList<>();
    private Adapter adapter;
    private ListenerRegistration reg;
    private TextView empty;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Admin.isAdmin()) { finish(); return; }
        Ui.statusBarBlue(this);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "🚩 Reports queue"));

        empty = Ui.tv(this, "No reports — everything is clean 🎉", 14, Ui.sub(this));
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
        com.friendbook.social.core.D1Snap.read(com.friendbook.social.core.DocQuery.of("reports").orderBy("createdAt",com.friendbook.social.core.DocQuery.DESC).limit(100).build(),snap->{
                    reports.clear();
                    if (snap != null) reports.addAll(snap.getDocuments());
                    adapter.notifyDataSetChanged();
                    empty.setVisibility(reports.isEmpty() ? View.VISIBLE : View.GONE);
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
    }

    private String describe(com.friendbook.social.core.Doc d) {
        Map<String, Object> m = d.getData();
        if (m == null) return "Unknown report";
        if (m.containsKey("postId")) return "📝 Reported post: " + Format.str(m, "postId");
        if (m.containsKey("commentId")) return "💬 Reported comment: " + Format.str(m, "commentId");
        if (m.containsKey("messageId")) return "✉️ Reported message in chat " + Format.str(m, "chatId");
        if (m.containsKey("uid") && "profile".equals(Format.str(m, "type")))
            return "👤 Reported user: " + Format.str(m, "uid");
        if (m.containsKey("authorId")) return "📝 Reported post by " + Format.str(m, "authorId");
        return "Report";
    }

    private void actOn(com.friendbook.social.core.Doc d) {
        Map<String, Object> m = d.getData();
        String[] opts = {"🗑 Delete reported content", "🚫 Ban content author", "✖ Dismiss report"};
        Ui.choose(this, describe(d), opts, w -> {
            if (w == 0) {
                if (m == null) return;
                String postId = Format.str(m, "postId");
                String commentId = Format.str(m, "commentId");
                if (!postId.isEmpty() && !postId.startsWith("reel_")) {
                    com.friendbook.social.core.DataRouter.batch().delete("posts", postId).commit();
                } else if (postId.startsWith("reel_")) {
                    com.friendbook.social.core.DataRouter.batch().delete("reels", postId.substring(5)).commit();
                }
                if (!commentId.isEmpty()) com.friendbook.social.core.DataRouter.batch().delete("comments", commentId).commit();
                String uid = Format.str(m, "uid");
                String type = Format.str(m, "type");
                if (!uid.isEmpty() && "profile".equals(type)) {
                    com.friendbook.social.core.DataRouter.mirrorPut("users", uid, "banned", true);
                    com.friendbook.social.core.DataRouter.batch().set("users", uid, Fb.map("banned", true)).commit();
                }
                com.friendbook.social.core.DataRouter.batch().delete("reports", d.getId()).commit();
                com.friendbook.social.core.DataRouter.mirrorDelete("reports", d.getId());
                Ui.toast(this, "Content deleted ✓");
            } else if (w == 1) {
                String author = m != null ? Format.str(m, "authorId") : "";
                if (author.isEmpty()) { Ui.toast(this, "Author unknown for this report"); return; }
                com.friendbook.social.core.DataRouter.mirrorPut("users", author, "banned", true);
                com.friendbook.social.core.DataRouter.batch().set("users", author, Fb.map("banned", true)).commit();
                com.friendbook.social.core.DataRouter.batch().delete("reports", d.getId()).commit();
                com.friendbook.social.core.DataRouter.mirrorDelete("reports", d.getId());
                Ui.toast(this, "Author banned ✓");
            } else {
                com.friendbook.social.core.DataRouter.batch().delete("reports", d.getId()).commit();
                com.friendbook.social.core.DataRouter.mirrorDelete("reports", d.getId());
                Ui.toast(this, "Report dismissed");
            }
        });
    }

    class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        @Override public int getItemCount() { return reports.size(); }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout col = Ui.col(AdminReportsActivity.this);
            col.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            col.setBackgroundColor(Ui.card(AdminReportsActivity.this));
            Ui.pad(col, 12, 10, 12, 10);
            TextView title = Ui.tvBold(AdminReportsActivity.this, "", 14, Ui.txt(AdminReportsActivity.this));
            TextView sub = Ui.tv(AdminReportsActivity.this, "", 12, Ui.sub(AdminReportsActivity.this));
            TextView act = Ui.tvBold(AdminReportsActivity.this, "Take action ›", 13, Ui.BLUE);
            Ui.pad(act, 0, 6, 0, 0);
            col.addView(title);
            col.addView(sub);
            col.addView(act);
            return new VH(col, title, sub, act);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            com.friendbook.social.core.Doc d = reports.get(position);
            h.title.setText(describe(d));
            Map<String, Object> m = d.getData();
            String by = Format.strOr(m, "reportedBy", "?");
            if (by.length() > 8) by = by.substring(0, 8) + "…";
            h.sub.setText("by " + by + " · "
                    + Format.ago(Format.ts(m == null ? null : m.get("createdAt"))));
            h.act.setOnClickListener(v -> actOn(d));
        }

        class VH extends RecyclerView.ViewHolder {
            TextView title, sub, act;
            VH(View v, TextView t, TextView s, TextView a) {
                super(v);
                title = t; sub = s; act = a;
            }
        }
    }
}
