package com.example.frienbook.admin;

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

import com.example.frienbook.core.Admin;
import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Format;
import com.example.frienbook.core.Ui;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Admin feedback inbox: what users reported via shake-to-report. */
public class AdminFeedbackActivity extends Activity {

    private final List<DocumentSnapshot> items = new ArrayList<>();
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
        root.addView(Ui.pageBar(this, "📮 Feedback inbox"));

        empty = Ui.tv(this, "No feedback yet.", 14, Ui.sub(this));
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
        reg = Fb.db().collection("feedback")
                .orderBy("createdAt", Query.Direction.DESCENDING).limit(100)
                .addSnapshotListener((snap, err) -> {
                    items.clear();
                    if (snap != null) items.addAll(snap.getDocuments());
                    adapter.notifyDataSetChanged();
                    empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
    }

    class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        @Override public int getItemCount() { return items.size(); }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout col = Ui.col(AdminFeedbackActivity.this);
            col.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            col.setBackgroundColor(Ui.card(AdminFeedbackActivity.this));
            Ui.pad(col, 12, 10, 12, 10);
            TextView from = Ui.tvBold(AdminFeedbackActivity.this, "", 14, Ui.txt(AdminFeedbackActivity.this));
            TextView text = Ui.tv(AdminFeedbackActivity.this, "", 14, Ui.txt(AdminFeedbackActivity.this));
            TextView meta = Ui.tv(AdminFeedbackActivity.this, "", 11, Ui.sub(AdminFeedbackActivity.this));
            TextView del = Ui.tvBold(AdminFeedbackActivity.this, "Archive ✓", 12, Ui.BLUE);
            Ui.pad(del, 0, 6, 0, 0);
            col.addView(from);
            col.addView(text);
            col.addView(meta);
            col.addView(del);
            return new VH(col, from, text, meta, del);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            DocumentSnapshot d = items.get(position);
            Map<String, Object> m = d.getData();
            h.from.setText(Format.strOr(m, "name", "User"));
            h.text.setText(Format.str(m, "text"));
            h.meta.setText(Format.str(m, "app") + " · " + Format.ago(Format.ts(m == null ? null : m.get("createdAt"))));
            h.del.setOnClickListener(v -> {
                d.getReference().delete();
                Ui.toast(AdminFeedbackActivity.this, "Archived");
            });
        }

        class VH extends RecyclerView.ViewHolder {
            TextView from, text, meta, del;
            VH(View v, TextView f, TextView t, TextView m, TextView d) {
                super(v);
                from = f; text = t; meta = m; del = d;
            }
        }
    }
}
