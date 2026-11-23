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
import com.friendbook.social.d1.documents.DocumentSnapshot;
import com.friendbook.social.d1.documents.ListenerRegistration;
import com.friendbook.social.d1.documents.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Admin reports queue: act on reported posts / comments / messages / users. */
public class AdminReportsActivity extends Activity {

    private final List<DocumentSnapshot> reports = new ArrayList<>();
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
        reg = Fb.db().collection("reports")
                .orderBy("createdAt", Query.Direction.DESCENDING).limit(100)
                .addSnapshotListener((snap, err) -> {
                    if (err != null || snap == null) {
                        empty.setText("Couldn't load reports. Please retry.");
                        empty.setVisibility(View.VISIBLE);
                        return;
                    }
                    reports.clear();
                    reports.addAll(snap.getDocuments());
                    adapter.notifyDataSetChanged();
                    empty.setText("No reports — everything is clean 🎉");
                    empty.setVisibility(reports.isEmpty() ? View.VISIBLE : View.GONE);
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
    }

    private String describe(DocumentSnapshot d) {
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

    private void actOn(DocumentSnapshot d) {
        Map<String, Object> m = d.getData();
        String[] opts = {"🗑 Delete reported content", "🚫 Ban content author", "✖ Dismiss report"};
        Ui.choose(this, describe(d), opts, w -> {
            if (w == 0) {
                if (m == null) return;
                String postId = Format.str(m, "postId");
                String reelId = Format.str(m, "reelId");
                String uid = Format.str(m, "uid");
                String type = Format.str(m, "type");
                com.google.android.gms.tasks.Task<Void> action;
                if (!postId.isEmpty() && !postId.startsWith("reel_")) {
                    action = Fb.db().moderate("delete_content", "posts", postId);
                } else if (postId.startsWith("reel_")) {
                    action = Fb.db().moderate("delete_content", "reels", postId.substring(5));
                } else if (!reelId.isEmpty()) {
                    action = Fb.db().moderate("delete_content", "reels", reelId);
                } else if ("story".equals(type) && !Format.str(m, "targetId").isEmpty()) {
                    action = Fb.db().moderate("delete_content", "stories", Format.str(m, "targetId"));
                } else if (!uid.isEmpty() && "profile".equals(type)) {
                    action = Fb.db().moderate("ban_user", "users", uid);
                } else {
                    Ui.toast(this, "No supported content action for this report");
                    return;
                }
                action.addOnSuccessListener(v -> dismissAfterAction(d, "Content action completed ✓"))
                        .addOnFailureListener(e -> Ui.toast(this, "Action failed; report kept in queue"));
            } else if (w == 1) {
                String author = m != null ? Format.str(m, "authorId") : "";
                if (author.isEmpty()) { Ui.toast(this, "Author unknown for this report"); return; }
                Fb.db().moderate("ban_user", "users", author)
                        .addOnSuccessListener(v -> dismissAfterAction(d, "Author banned ✓"))
                        .addOnFailureListener(e -> Ui.toast(this, "Ban failed; report kept in queue"));
            } else {
                dismissAfterAction(d, "Report dismissed");
            }
        });
    }

    private void dismissAfterAction(DocumentSnapshot report, String successMessage) {
        Fb.db().moderate("delete_content", "reports", report.getId())
                .addOnSuccessListener(v -> Ui.toast(this, successMessage))
                .addOnFailureListener(e -> Ui.toast(this, "Couldn't dismiss report. Please try again."));
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
            DocumentSnapshot d = reports.get(position);
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
