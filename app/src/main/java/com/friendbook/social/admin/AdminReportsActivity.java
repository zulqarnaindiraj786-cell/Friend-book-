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
    private LinearLayout pageControls;
    private String screenActor;
    private boolean started;
    private final java.util.Set<String> removed = new java.util.HashSet<>();
    private final java.util.Map<String,String> actionCompleted = new java.util.HashMap<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Admin.isAdmin()) { finish(); return; }
        Ui.statusBarBlue(this);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "🚩 Reports queue"));

        empty = Ui.tv(this, "Loading reports…", 14, Ui.sub(this));
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
                Fb.db().collection("reports").orderBy("createdAt", Query.Direction.DESCENDING),
                10,"admin-reports",(snap, err) -> {
                    if (err != null || snap == null) {
                        empty.setText("Couldn't load reports. Please retry.");
                        empty.setVisibility(View.VISIBLE);
                        return;
                    }
                    reports.clear();
                    for(DocumentSnapshot d:snap)if(!removed.contains(d.getId()))reports.add(d);
                    adapter.notifyDataSetChanged();
                    empty.setText("No reports in loaded pages");
                    empty.setVisibility(reports.isEmpty() ? View.VISIBLE : View.GONE);
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        started=false;
        if (reg != null) {reg.remove();reg=null;}
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
        if(!started || !java.util.Objects.equals(screenActor,Fb.uid()) || !Admin.isAdmin())return;
        final String actor=screenActor,id=d.getId();
        if(ModerationGate.SHARED.pending(actor,"reports",id))return;
        if(actionCompleted.containsKey(id)) {
            if(ModerationGate.SHARED.begin(actor,"reports",id))dismissAfterAction(d,actionCompleted.get(id),actor);
            return;
        }
        Map<String, Object> m=d.getData();
        String[] opts={"🗑 Delete reported content", "🚫 Ban content author", "✖ Dismiss report"};
        Ui.choose(this,describe(d),opts,w->{
            if(!started || !java.util.Objects.equals(actor,Fb.uid()) || !Admin.isAdmin()
                    || !ModerationGate.SHARED.begin(actor,"reports",id))return;
            if(w==2){dismissAfterAction(d,"Report dismissed",actor);return;}
            String targetAction="",collection="",target="";
            if(w==0 && m!=null) {
                String postId=Format.str(m,"postId"),reelId=Format.str(m,"reelId"),uid=Format.str(m,"uid"),type=Format.str(m,"type");
                if(!postId.isEmpty() && !postId.startsWith("reel_")){targetAction="delete_content";collection="posts";target=postId;}
                else if(postId.startsWith("reel_")){targetAction="delete_content";collection="reels";target=postId.substring(5);}
                else if(!reelId.isEmpty()){targetAction="delete_content";collection="reels";target=reelId;}
                else if("story".equals(type) && !Format.str(m,"targetId").isEmpty()){targetAction="delete_content";collection="stories";target=Format.str(m,"targetId");}
                else if(!uid.isEmpty() && "profile".equals(type)){targetAction="ban_user";collection="users";target=uid;}
            } else if(w==1 && m!=null) {
                target=Format.str(m,"authorId");targetAction="ban_user";collection="users";
            }
            if(target.isEmpty()){
                ModerationGate.SHARED.end(actor,"reports",id);
                Ui.toast(this,w==1?"Author unknown for this report":"No supported content action for this report");return;
            }
            adapter.notifyDataSetChanged();
            final String success=w==1?"Author banned ✓":"Content action completed ✓";
            Fb.db().moderate(targetAction,collection,target).addOnCompleteListener(task->{
                if(!java.util.Objects.equals(actor,Fb.uid())){ModerationGate.SHARED.end(actor,"reports",id);return;}
                if(task.isSuccessful()) {
                    actionCompleted.put(id,success);
                    dismissAfterAction(d,success,actor);
                } else {
                    ModerationGate.SHARED.end(actor,"reports",id);
                    if(started){adapter.notifyDataSetChanged();Ui.toast(this,w==1?"Ban failed; report kept in queue":"Action failed; report kept in queue");}
                }
            });
        });
    }

    private void dismissAfterAction(DocumentSnapshot report,String successMessage,String actor) {
        String id=report.getId();
        if(!java.util.Objects.equals(actor,Fb.uid())){ModerationGate.SHARED.end(actor,"reports",id);return;}
        Fb.db().moderate("delete_content","reports",id).addOnCompleteListener(task->{
            ModerationGate.SHARED.end(actor,"reports",id);
            if(!java.util.Objects.equals(actor,Fb.uid()))return;
            if(task.isSuccessful()) {
                actionCompleted.remove(id);removed.add(id);reports.removeIf(d->id.equals(d.getId()));
                if(started){adapter.notifyDataSetChanged();empty.setVisibility(reports.isEmpty()?View.VISIBLE:View.GONE);Ui.toast(this,successMessage);}
            } else if(started) {
                adapter.notifyDataSetChanged();Ui.toast(this,"Couldn't dismiss report. Please try again.");
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
            DocumentSnapshot d = reports.get(position);
            h.title.setText(describe(d));
            Map<String, Object> m = d.getData();
            String by = Format.strOr(m, "reportedBy", "?");
            if (by.length() > 8) by = by.substring(0, 8) + "…";
            h.sub.setText("by " + by + " · "
                    + Format.ago(Format.ts(m == null ? null : m.get("createdAt"))));
            h.act.setEnabled(!ModerationGate.SHARED.pending(screenActor,"reports",d.getId()));
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
