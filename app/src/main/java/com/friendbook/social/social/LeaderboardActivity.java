package com.friendbook.social.social;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.friendbook.social.profile.ProfileActivity;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.Query;

import java.util.ArrayList;
import java.util.List;

/** 🏆 Leaderboard — top friendbook stars (weekly-style engagement, like FB's top fans). */
public class LeaderboardActivity extends Activity {

    private LinearLayout list;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "🏆 Leaderboard"));
        root.addView(Ui.divider(this));

        TextView sub = Ui.tv(this,
                "Top friends by ⭐ stars — earn stars from gifts, referrals & daily bonus!",
                13, Ui.sub(this));
        Ui.pad(sub, 16, 8, 16, 4);
        root.addView(sub);

        list = Ui.col(this);
        Ui.pad(list, 12, 6, 12, 24);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        load();
    }

    private void load() {
        // The board is a D1 read; it was a Firestore orderBy, so it showed "No one on the board".
        com.friendbook.social.core.CloudflareStore.query(
                com.friendbook.social.core.DocQuery.of("users")
                        .orderBy("stars", com.friendbook.social.core.DocQuery.DESC).limit(20).build(),
                snap -> {
                    list.removeAllViews();
                    int rank = 0;
                    String me = Fb.uid();
                    for (java.util.Map<String, Object> row : snap.documents) {
                        String rowId = row.get("id") == null ? "" : String.valueOf(row.get("id"));
                        Models.User u = Models.User.fromMap(rowId, row);
                        rank++;
                        list.addView(row(rank, u, u.uid.equals(me)));
                    }
                    if (rank == 0) {
                        TextView e = Ui.tv(this, "No one on the board yet — earn some ⭐!", 14, Ui.sub(this));
                        Ui.pad(e, 0, 24, 0, 0);
                        list.addView(e);
                    }
                }, () -> {
                    list.removeAllViews();
                    TextView t = Ui.tv(this, "Couldn't load leaderboard. Try again.", 13, Ui.sub(this));
                    Ui.pad(t, 0, 24, 0, 0);
                    list.addView(t);
                });
    }

    private View row(int rank, Models.User u, boolean isMe) {
        LinearLayout r = Ui.row(this);
        r.setBackground(Ui.round(this, Ui.card(this), 12));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.bottomMargin = Ui.dp(this, 8);
        r.setLayoutParams(rp);
        Ui.pad(r, 12, 10, 12, 10);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);

        String medal = rank == 1 ? "🥇" : rank == 2 ? "🥈" : rank == 3 ? "🥉" : "#" + rank;
        TextView rk = Ui.tvBold(this, medal, rank < 4 ? 22 : 14, rank < 4 ? Ui.txt(this) : Ui.sub(this));
        rk.setMinWidth(Ui.dp(this, 40));
        rk.setGravity(android.view.Gravity.CENTER);
        r.addView(rk);

        ImageView av = new ImageView(this);
        int s = Ui.dp(this, 42);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(s, s);
        ap.leftMargin = Ui.dp(this, 8);
        r.addView(av, ap);
        Ui.avatar(this, av, u.photoUrl);

        LinearLayout ncol = Ui.col(this);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, -2, 1);
        np.leftMargin = Ui.dp(this, 10);
        LinearLayout nrow = Ui.row(this);
        TextView nm = Ui.tvBold(this,
                u.displayName + (isMe ? " (you)" : ""), 15, isMe ? Ui.BLUE : Ui.txt(this));
        nrow.addView(nm);
        ImageView tick = new ImageView(this);
        com.friendbook.social.core.Icons.setTickBadge(tick, u.tickLevel, true);
        int ts = Ui.dp(this, 14);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ts, ts);
        tp.leftMargin = Ui.dp(this, 4);
        nrow.addView(tick, tp);
        ncol.addView(nrow);
        ncol.addView(Ui.tv(this, "⭐ " + Format.count(u.stars) + " stars", 12, Ui.sub(this)));
        r.addView(ncol, np);

        r.setOnClickListener(v -> {
            Intent i = new Intent(this, ProfileActivity.class);
            i.putExtra("uid", u.uid);
            startActivity(i);
        });
        return r;
    }
}
