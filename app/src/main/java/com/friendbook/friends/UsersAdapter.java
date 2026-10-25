package com.friendbook.friends;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.core.Ui;
import com.friendbook.models.Models;
import com.friendbook.profile.ProfileActivity;

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
        rows.clear();
        rows.addAll(list);
        notifyDataSetChanged();
    }

    public boolean isEmpty() { return rows.isEmpty(); }

    @Override public int getItemCount() { return rows.size(); }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Activity c = activity;
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
        tick.setImageResource(com.friendbook.R.drawable.ic_verified);
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
        Ui.avatar(activity, h.av, r.user.photoUrl);
        h.name.setText(r.user.displayName);
        com.friendbook.core.Icons.setTickBadge(h.tick, r.user.tickLevel, false);
        if (r.subtitle != null && !r.subtitle.isEmpty()) {
            h.sub.setVisibility(View.VISIBLE);
            h.sub.setText(r.subtitle);
        } else h.sub.setVisibility(View.GONE);

        if (r.primaryLabel != null) {
            h.primary.setVisibility(View.VISIBLE);
            h.primary.setText(r.primaryLabel);
            h.primary.setOnClickListener(v -> {
                if (handler != null) handler.primary(r);
            });
        } else h.primary.setVisibility(View.GONE);

        if (r.secondaryLabel != null) {
            h.secondary.setVisibility(View.VISIBLE);
            h.secondary.setText(r.secondaryLabel);
            h.secondary.setOnClickListener(v -> {
                if (handler != null) handler.secondary(r);
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
        ImageView av, tick;
        TextView name, sub, primary, secondary;
        VH(View v, ImageView a, TextView n, ImageView tk, TextView s, TextView p, TextView sc) {
            super(v);
            av = a; name = n; tick = tk; sub = s; primary = p; secondary = sc;
        }
    }
}
