package com.example.frienbook.friends;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.frienbook.chat.ChatActivity;
import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Ui;
import com.example.frienbook.models.Models;
import com.example.frienbook.profile.ProfileActivity;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Birthdays tab: friends whose birthday is today or coming up. */
public class BirthdaysActivity extends Activity {

    private final List<RowItem> rows = new ArrayList<>();
    private Adapter adapter;
    private ListenerRegistration friendsReg;
    private TextView empty;
    private final List<String> friendIds = new ArrayList<>();
    private int pendingUsers = 0;

    static class RowItem {
        Models.User user;
        String when; // "Today 🎉" | "Tomorrow" | "in N days"
        int daysAway;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "🎂 Birthdays"));

        empty = Ui.tv(this, "No upcoming birthdays.\nFriends who add their birthday in Edit Profile show up here.",
                14, Ui.sub(this));
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
        friendsReg = Fb.db().collection("friends").whereArrayContains("participants", me)
                .addSnapshotListener((snap, err) -> {
                    friendIds.clear();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap)
                            for (String p : Models.ChatRoom.fromDoc(d).participants)
                                if (!p.equals(me)) friendIds.add(p);
                    }
                    loadUsers();
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (friendsReg != null) friendsReg.remove();
    }

    private void loadUsers() {
        rows.clear();
        adapter.notifyDataSetChanged();
        if (friendIds.isEmpty()) { render(); return; }
        pendingUsers = friendIds.size();
        for (String uid : friendIds) {
            Fb.getUser(uid).addOnSuccessListener(u -> {
                if (u != null && u.birthday != null && !u.birthday.isEmpty()) {
                    int days = daysUntil(u.birthday);
                    if (days >= 0 && days <= 30) {
                        RowItem r = new RowItem();
                        r.user = u;
                        r.daysAway = days;
                        r.when = days == 0 ? "Today 🎉" : days == 1 ? "Tomorrow" : "in " + days + " days";
                        rows.add(r);
                    }
                }
                if (--pendingUsers <= 0) render();
            });
        }
    }

    private void render() {
        rows.sort((a, b) -> Integer.compare(a.daysAway, b.daysAway));
        adapter.notifyDataSetChanged();
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** birthday format "dd/MM/yyyy" or "dd/MM" → days until next occurrence (0..365, -1 invalid). */
    private int daysUntil(String birthday) {
        try {
            String[] parts = birthday.split("/");
            int day = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]) - 1;
            Calendar now = Calendar.getInstance();
            Calendar next = Calendar.getInstance();
            next.set(Calendar.MONTH, month);
            next.set(Calendar.DAY_OF_MONTH, day);
            next.set(Calendar.HOUR_OF_DAY, 0);
            next.set(Calendar.MINUTE, 0);
            next.set(Calendar.SECOND, 0);
            next.set(Calendar.MILLISECOND, 0);
            Calendar today = Calendar.getInstance();
            today.set(Calendar.HOUR_OF_DAY, 0);
            today.set(Calendar.MINUTE, 0);
            today.set(Calendar.SECOND, 0);
            today.set(Calendar.MILLISECOND, 0);
            if (next.before(today)) next.add(Calendar.YEAR, 1);
            long diff = next.getTimeInMillis() - today.getTimeInMillis();
            return (int) (diff / 86400_000L);
        } catch (Exception e) {
            return -1;
        }
    }

    class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        @Override public int getItemCount() { return rows.size(); }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = Ui.row(BirthdaysActivity.this);
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            row.setBackgroundColor(Ui.card(BirthdaysActivity.this));
            Ui.pad(row, 12, 10, 12, 10);

            ImageView av = new ImageView(BirthdaysActivity.this);
            int s = Ui.dp(BirthdaysActivity.this, 52);
            row.addView(av, new LinearLayout.LayoutParams(s, s));

            LinearLayout mid = Ui.col(BirthdaysActivity.this);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1);
            mp.leftMargin = Ui.dp(BirthdaysActivity.this, 12);
            TextView name = Ui.tvBold(BirthdaysActivity.this, "", 15, Ui.txt(BirthdaysActivity.this));
            TextView when = Ui.tv(BirthdaysActivity.this, "", 13, Ui.sub(BirthdaysActivity.this));
            mid.addView(name);
            mid.addView(when);
            row.addView(mid, mp);

            TextView wish = Ui.btn(BirthdaysActivity.this, "🎉 Wish");
            wish.setMinHeight(Ui.dp(BirthdaysActivity.this, 34));
            wish.setTextSize(12);
            row.addView(wish);
            return new VH(row, av, name, when, wish);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            RowItem r = rows.get(position);
            Ui.avatar(BirthdaysActivity.this, h.av, r.user.photoUrl);
            h.name.setText(r.user.displayName);
            h.when.setText(r.when + " · " + prettyBirthday(r.user.birthday));
            if (r.daysAway == 0) h.when.setTextColor(Ui.BLUE);
            else h.when.setTextColor(Ui.sub(BirthdaysActivity.this));
            h.wish.setOnClickListener(v -> {
                Intent i = new Intent(BirthdaysActivity.this, ChatActivity.class);
                i.putExtra("uid", r.user.uid);
                i.putExtra("name", r.user.displayName);
                i.putExtra("prefill", "🎂 Happy Birthday " + r.user.displayName.split(" ")[0] + "! 🎉");
                startActivity(i);
            });
            h.itemView.setOnClickListener(v -> {
                Intent i = new Intent(BirthdaysActivity.this, ProfileActivity.class);
                i.putExtra("uid", r.user.uid);
                startActivity(i);
            });
        }

        private String prettyBirthday(String b) {
            try {
                String[] parts = b.split("/");
                SimpleDateFormat monthFmt = new SimpleDateFormat("MMMM", Locale.getDefault());
                Calendar c = Calendar.getInstance();
                c.set(Calendar.MONTH, Integer.parseInt(parts[1]) - 1);
                return Integer.parseInt(parts[0]) + " " + monthFmt.format(c.getTime());
            } catch (Exception e) {
                return b;
            }
        }

        class VH extends RecyclerView.ViewHolder {
            ImageView av;
            TextView name, when, wish;
            VH(View v, ImageView a, TextView n, TextView w, TextView ws) {
                super(v);
                av = a; name = n; when = w; wish = ws;
            }
        }
    }
}
