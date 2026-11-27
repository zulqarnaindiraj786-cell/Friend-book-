package com.friendbook.social.friends;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.friendbook.social.profile.ProfileActivity;

import java.util.ArrayList;
import java.util.List;

/** Reusable people list: avatar, name, subtitle and up to two action buttons. */
public class UsersAdapter extends RecyclerView.Adapter<UsersAdapter.VH> {

    public interface Handler {
        void primary(Row r);
        void secondary(Row r);
    }

    public interface Click { void click(Models.User u); }

    /** Optional row-click override (default: open profile). */
    public Click onClick;

    public static class Row {
        public String heading;
        public boolean loading;
        public Runnable action;
        public Models.User user;
        public String subtitle = "";
        public String primaryLabel = null;    // blue button
        public String secondaryLabel = null;  // grey button
        public String requestId = null;       // carried for accept/decline
        public boolean primaryBlue = true;
    }

    private final Activity activity;
    private final Handler handler;
    private final List<Row> rows = new ArrayList<>();

    public UsersAdapter(Activity a, Handler h) {
        this.activity = a;
        this.handler = h;
    }

    public void setData(List<Row> list) {
        List<Row> before = new ArrayList<>(rows);
        androidx.recyclerview.widget.DiffUtil.DiffResult diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(new androidx.recyclerview.widget.DiffUtil.Callback() {
            public int getOldListSize() { return before.size(); }
            public int getNewListSize() { return list.size(); }
            public boolean areItemsTheSame(int a, int b) {
                Row old=before.get(a), next=list.get(b);
                if(old.user==null || next.user==null) return old.user==null && next.user==null && a==b;
                return java.util.Objects.equals(old.user.uid,next.user.uid) && java.util.Objects.equals(old.requestId,next.requestId);
            }
            public boolean areContentsTheSame(int a,int b) { return false; }
        },false);
        rows.clear(); rows.addAll(list);
        diff.dispatchUpdatesTo(this);
    }

    public boolean isEmpty() { return rows.isEmpty(); }

    @Override public int getItemViewType(int position){return rows.get(position).user==null?1:0;}

    @Override public int getItemCount() { return rows.size(); }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Activity c = activity;
        if(viewType==1){
            LinearLayout box=Ui.col(c);box.setGravity(android.view.Gravity.CENTER_VERTICAL);Ui.pad(box,14,10,14,10);
            box.setLayoutParams(new RecyclerView.LayoutParams(-1,-2));
            TextView title=Ui.tvBold(c,"",16,Ui.txt(c));box.addView(title);
            android.widget.ProgressBar progress=new android.widget.ProgressBar(c);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(Ui.dp(c,24),Ui.dp(c,24));lp.gravity=android.view.Gravity.CENTER;box.addView(progress,lp);
            VH holder=new VH(box,null,title,null,null,null,null);holder.progress=progress;return holder;
        }
        LinearLayout outer = Ui.col(c);
        outer.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
        LinearLayout row = Ui.row(c);
        Ui.pad(row, 12, 8, 12, 8);

        ImageView av = new ImageView(c);
        int s = Ui.dp(c, 52);
        row.addView(av, new LinearLayout.LayoutParams(s, s));

        LinearLayout mid = Ui.col(c);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1);
        mp.leftMargin = Ui.dp(c, 12);
        TextView name = Ui.tvBold(c, "", 15, Ui.txt(c));
        TextView sub = Ui.tv(c, "", 12, Ui.sub(c));
        sub.setVisibility(View.GONE);
        // name row: displayName + blue tick badge
        LinearLayout nameRow = Ui.row(c);
        nameRow.addView(name, new LinearLayout.LayoutParams(-2, -2));
        ImageView tick = new ImageView(c);
        tick.setImageResource(com.friendbook.social.R.drawable.ic_verified);
        tick.setVisibility(View.GONE);
        int ts = Ui.dp(c, 16);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ts, ts);
        tlp.leftMargin = Ui.dp(c, 5);
        nameRow.addView(tick, tlp);
        mid.addView(nameRow);
        mid.addView(sub);
        row.addView(mid, mp);

        LinearLayout buttons = Ui.col(c);
        TextView primary = Ui.btn(c, "");
        primary.setMinHeight(Ui.dp(c, 32));
        primary.setTextSize(13);
        primary.setVisibility(View.GONE);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        TextView secondary = Ui.btnGrey(c, "");
        secondary.setMinHeight(Ui.dp(c, 32));
        secondary.setTextSize(13);
        secondary.setVisibility(View.GONE);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.topMargin = Ui.dp(c, 6);
        buttons.addView(primary, bp);
        buttons.addView(secondary, sp);
        row.addView(buttons);

        outer.addView(row);
        return new VH(outer, av, name, tick, sub, primary, secondary);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Row r = rows.get(position);
        if(r.user==null){
            h.name.setText(r.heading);h.name.setVisibility(r.heading==null || r.heading.isEmpty()?View.GONE:View.VISIBLE);
            h.progress.setVisibility(r.loading?View.VISIBLE:View.GONE);
            h.progress.setContentDescription("Loading people");
            h.itemView.setOnClickListener(r.action==null?null:v->r.action.run());return;
        }
        Ui.avatar(activity, h.av, r.user.photoUrl);
        h.name.setText(r.user.displayName);
        com.friendbook.social.core.Icons.setTickBadge(h.tick, r.user.tickLevel, false);
        if (r.subtitle != null && !r.subtitle.isEmpty()) {
            h.sub.setVisibility(View.VISIBLE);
            h.sub.setText(r.subtitle);
        } else h.sub.setVisibility(View.GONE);

        if (r.primaryLabel != null) {
            h.primary.setVisibility(View.VISIBLE);
            h.primary.setText(r.primaryLabel);
            h.primary.setOnClickListener(v -> {
                if (handler != null && com.friendbook.social.core.InteractionCooldown.disableBriefly(v)) handler.primary(r);
            });
        } else h.primary.setVisibility(View.GONE);

        if (r.secondaryLabel != null) {
            h.secondary.setVisibility(View.VISIBLE);
            h.secondary.setText(r.secondaryLabel);
            h.secondary.setOnClickListener(v -> {
                if (handler != null && com.friendbook.social.core.InteractionCooldown.disableBriefly(v)) handler.secondary(r);
            });
        } else h.secondary.setVisibility(View.GONE);

        h.itemView.setOnClickListener(v -> {
            if (r.user.uid == null) return;
            if (onClick != null) { onClick.click(r.user); return; }
            Intent i = new Intent(activity, ProfileActivity.class);
            i.putExtra("uid", r.user.uid);
            activity.startActivity(i);
        });
    }

    static class VH extends RecyclerView.ViewHolder {
        android.widget.ProgressBar progress;
        ImageView av, tick;
        TextView name, sub, primary, secondary;
        VH(View v, ImageView a, TextView n, ImageView tk, TextView s, TextView p, TextView sc) {
            super(v);
            av = a; name = n; tick = tk; sub = s; primary = p; secondary = sc;
        }
    }
}
