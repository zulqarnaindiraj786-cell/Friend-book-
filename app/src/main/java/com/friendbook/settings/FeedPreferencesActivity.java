package com.friendbook.settings;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.core.Fb;
import com.friendbook.core.Prefs;
import com.friendbook.core.Ui;
import com.friendbook.friends.UsersAdapter;
import com.friendbook.models.Models;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ⭐ Feed preferences — Full friendbook Feed Controls:
 * 1) Default Feed Sort: Top (Favorites First) vs Most Recent (Chronological)
 * 2) Favorites manager (up to 30 friends prioritized at top of Home)
 * 3) Quick Snooze / Unsnooze per friend
 */
public class FeedPreferencesActivity extends Activity {

    private UsersAdapter adapter;
    private TextView countT, topSortBtn, recentSortBtn;
    private final List<Models.User> friends = new ArrayList<>();
    private final Set<String> favorites = new HashSet<>();
    private ListenerRegistration reg, meReg;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "News Feed preferences"));

        // Feed Sort selector card
        LinearLayout sortCard = Ui.col(this);
        sortCard.setBackgroundColor(Ui.card(this));
        Ui.pad(sortCard, 14, 10, 14, 10);
        sortCard.addView(Ui.tvBold(this, "Default Feed order", 14, Ui.txt(this)));
        LinearLayout sortRow = Ui.row(this);
        sortRow.setPadding(0, Ui.dp(this, 8), 0, 0);
        topSortBtn = Ui.tvBold(this, "✨ Top (Favorites first)", 13, Color.WHITE);
        topSortBtn.setGravity(Gravity.CENTER);
        Ui.pad(topSortBtn, 10, 8, 10, 8);
        recentSortBtn = Ui.tvBold(this, "🕒 Most Recent", 13, Ui.txt(this));
        recentSortBtn.setGravity(Gravity.CENTER);
        Ui.pad(recentSortBtn, 10, 8, 10, 8);
        LinearLayout.LayoutParams sp1 = new LinearLayout.LayoutParams(0, -2, 1);
        LinearLayout.LayoutParams sp2 = new LinearLayout.LayoutParams(0, -2, 1);
        sp2.leftMargin = Ui.dp(this, 8);
        sortRow.addView(topSortBtn, sp1);
        sortRow.addView(recentSortBtn, sp2);
        topSortBtn.setOnClickListener(v -> { Prefs.setFeedSort(this, "top"); refreshSortButtons(); });
        recentSortBtn.setOnClickListener(v -> { Prefs.setFeedSort(this, "recent"); refreshSortButtons(); });
        sortCard.addView(sortRow);

        TextView resetAlgoBtn = Ui.roundChip(this,
                "🔄 Reset Seen History (Show fresh posts & videos now)",
                Ui.inputBg(this), Ui.BLUE, 12);
        Ui.pad(resetAlgoBtn, 12, 8, 12, 8);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = Ui.dp(this, 10);
        resetAlgoBtn.setGravity(Gravity.CENTER);
        resetAlgoBtn.setOnClickListener(v -> {
            com.friendbook.core.FeedAlgorithm.resetHistory(this);
            Ui.toast(this, "✨ Feed & video discovery history refreshed!");
        });
        sortCard.addView(resetAlgoBtn, rlp);

        root.addView(sortCard);
        root.addView(Ui.divider(this));
        refreshSortButtons();

        LinearLayout head = Ui.col(this);
        Ui.pad(head, 14, 10, 14, 6);
        head.addView(Ui.tvBold(this, "⭐ Manage Favorites & Snooze", 15, Ui.txt(this)));
        head.addView(Ui.tv(this,
                "Star up to 30 friends so their posts appear at the very top of your Feed, or Snooze anyone for 30 days.",
                12, Ui.sub(this)));
        countT = Ui.tv(this, "", 12, Ui.BLUE);
        countT.setPadding(0, Ui.dp(this, 4), 0, 0);
        head.addView(countT);
        root.addView(head);
        root.addView(Ui.divider(this));

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new UsersAdapter(this, new UsersAdapter.Handler() {
            @Override public void primary(UsersAdapter.Row r) { toggleFav(r.user); }
            @Override public void secondary(UsersAdapter.Row r) { toggleSnooze(r.user); }
        });
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void refreshSortButtons() {
        boolean recent = "recent".equals(Prefs.feedSort(this));
        topSortBtn.setBackground(Ui.round(this, !recent ? Ui.BLUE : Ui.inputBg(this), 16));
        topSortBtn.setTextColor(!recent ? Color.WHITE : Ui.txt(this));
        recentSortBtn.setBackground(Ui.round(this, recent ? Ui.BLUE : Ui.inputBg(this), 16));
        recentSortBtn.setTextColor(recent ? Color.WHITE : Ui.txt(this));
    }

    private void toggleFav(Models.User u) {
        String me = Fb.uid();
        if (me == null) return;
        if (favorites.contains(u.uid)) favorites.remove(u.uid);
        else favorites.add(u.uid);
        Fb.db().collection("users").document(me)
                .set(Fb.map("favoriteUids", new ArrayList<>(favorites)), SetOptions.merge());
        render();
    }

    private void toggleSnooze(Models.User u) {
        if (Prefs.isSnoozed(this, u.uid)) {
            Prefs.unsnooze(this, u.uid);
            Ui.toast(this, u.displayName + " unsnoozed ✓");
        } else {
            Prefs.snooze(this, u.uid, 30);
            Ui.toast(this, u.displayName + " snoozed for 30 days 😴");
        }
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        String me = Fb.uid();
        if (me == null) { finish(); return; }
        meReg = Fb.db().collection("users").document(me)
                .addSnapshotListener((d, err) -> {
                    favorites.clear();
                    if (d != null && d.exists())
                        favorites.addAll(com.friendbook.core.Format.listOf(d.getData(), "favoriteUids"));
                    render();
                });
        reg = Fb.db().collection("friends").whereArrayContains("participants", me)
                .addSnapshotListener((snap, err) -> {
                    friends.clear();
                    if (snap != null) {
                        List<String> ids = new ArrayList<>();
                        for (DocumentSnapshot d : snap) {
                            List<String> parts = Models.ChatRoom.fromDoc(d).participants;
                            for (String p : parts) if (!p.equals(me)) ids.add(p);
                        }
                        loadUsers(ids);
                    }
                });
    }

    private void loadUsers(List<String> ids) {
        if (ids.isEmpty()) {
            countT.setText("Add friends first to customize your Feed 🙂");
            return;
        }
        friends.clear();
        for (List<String> chunk : Ui.chunks(ids, 10)) {
            Fb.db().collection("users").whereIn(
                    com.google.firebase.firestore.FieldPath.documentId(), chunk)
                    .get().addOnSuccessListener(snap -> {
                        for (DocumentSnapshot d : snap) friends.add(Models.User.fromDoc(d));
                        render();
                    });
        }
    }

    private void render() {
        List<UsersAdapter.Row> rows = new ArrayList<>();
        for (Models.User u : friends) {
            UsersAdapter.Row r = new UsersAdapter.Row();
            r.user = u;
            boolean fav = favorites.contains(u.uid);
            boolean snz = Prefs.isSnoozed(this, u.uid);
            r.subtitle = fav ? "⭐ Prioritized first in Feed"
                    : (snz ? "😴 Snoozed (hidden 30 days)" : "Friend");
            r.primaryLabel = fav ? "⭐ Favorited" : "☆ Favorite";
            r.secondaryLabel = snz ? "Unsnooze" : "😴 Snooze";
            rows.add(r);
        }
        adapter.setData(rows);
        countT.setText(favorites.size() + " / 30 favorite(s) selected");
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
        if (meReg != null) meReg.remove();
    }
}
