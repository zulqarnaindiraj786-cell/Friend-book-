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

/** Admin feedback inbox: what users reported via shake-to-report. */
public class AdminFeedbackActivity extends Activity {

    private final List<DocumentSnapshot> items = new ArrayList<>();
    private Adapter adapter;
    private ListenerRegistration reg;
    private TextView empty;
    private LinearLayout pageControls;
    private String screenActor;
    private boolean started;
    private final java.util.Set<String> removed = new java.util.HashSet<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Admin.isAdmin()) { finish(); return; }
        Ui.statusBarBlue(this);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "📮 Feedback inbox"));

        empty = Ui.tv(this, "Loading feedback…", 14, Ui.sub(this));
        empty.setGravity(Gravity.CENTER);
        Ui.pad(empty, 24, 60, 24, 0);
        root.addView(empty);

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));
        pageControls=Ui.col(this);root.addView(pageControls);
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if(adapter==null || !Admin.isAdmin() || Fb.uid()==null){finish();return;}
        if(screenActor!=null && !screenActor.equals(Fb.uid())){finish();return;}
        screenActor=Fb.uid();started=true;
        reg = com.friendbook.social.core.PagedLists.attach(this,pageControls,
                Fb.db().collection("feedback").orderBy("createdAt", Query.Direction.DESCENDING),
                10,"admin-feedback",(snap, err) -> {
                    if (err != null || snap == null) {
                        empty.setText("Couldn't load feedback. Reopen to retry.");
                        empty.setVisibility(View.VISIBLE);
                        return;
                    }
                    items.clear();
                    for(DocumentSnapshot d:snap)if(!removed.contains(d.getId()))items.add(d);
                    adapter.notifyDataSetChanged();
                    empty.setText("No feedback in loaded pages");
                    empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        started=false;
        if (reg != null) {reg.remove();reg=null;}
    }

    private void archiveFeedback(DocumentSnapshot doc) {
        final String actor=screenActor,id=doc.getId();
        if(!started || !java.util.Objects.equals(actor,Fb.uid()) || !Admin.isAdmin()
                || !ModerationGate.SHARED.begin(actor,"feedback",id))return;
        adapter.notifyDataSetChanged();
        Fb.db().moderate("delete_content","feedback",id).addOnCompleteListener(task -> {
            ModerationGate.SHARED.end(actor,"feedback",id);
            if(!java.util.Objects.equals(actor,Fb.uid()))return;
            if(task.isSuccessful()) {
                removed.add(id);items.removeIf(d->id.equals(d.getId()));
                if(started){adapter.notifyDataSetChanged();empty.setVisibility(items.isEmpty()?View.VISIBLE:View.GONE);Ui.toast(this,"Archived");}
            } else if(started) {
                adapter.notifyDataSetChanged();Ui.toast(this,"Couldn't archive feedback. Please try again.");
            }
        });
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
            h.del.setEnabled(!ModerationGate.SHARED.pending(screenActor,"feedback",d.getId()));
            h.del.setOnClickListener(v -> archiveFeedback(d));
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
