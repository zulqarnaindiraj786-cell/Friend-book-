package com.friendbook.settings;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.core.Fb;
import com.friendbook.core.Icons;
import com.friendbook.core.Ui;
import com.friendbook.friends.UsersAdapter;
import com.friendbook.models.Models;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * ⭐ Feed preferences — Facebook Lite style:
 * choose FAVORITE friends (their posts appear first in Home),
 * plus shortcuts to hidden posts / snoozed people.
 * Firestore: users/{me}.favoriteUids[]
 */
public class FeedPreferencesActivity extends Activity {

    private UsersAdapter adapter;
    private TextView countT;
    private final List<Models.User> friends = new ArrayList<>();
    private final java.util.Set<String> favorites = new java.util.HashSet<>();
    private ListenerRegistration reg, meReg;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "⭐ Feed preferences"));
        root.addView(Ui.divider(this));

        LinearLayout head = Ui.col(this);
        Ui.pad(head, 14, 10, 14, 4);
        head.addView(Ui.tvBold(this, "Favorites", 16, Ui.txt(this)));
        head.addView(Ui.tv(this,
                "In friends ki posts hamesha feed me sab se ooper dikhengi — aap har waqt dekh sakte ho sab miss nahi hoga.",
                12, Ui.sub(this)));
        countT = Ui.tv(this, "", 12, Ui.sub(this));
        countT.setPadding(0, Ui.dp(this, 6), 0, 0);
        head.addView(countT);
        root.addView(head);
        root.addView(Ui.divider(this));

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new UsersAdapter(this, new UsersAdapter.Handler() {
            @Override public void primary(UsersAdapter.Row r) { toggleFav(r.user); }
            @Override public void secondary(UsersAdapter.Row r) { toggleFav(r.user); }
        });
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void toggleFav(Models.User u) {
        String me = Fb.uid();
        if (me == null) return;
        if (favorites.contains(u.uid)) {
            favorites.remove(u.uid);
        } else {
            favorites.add(u.uid);
        }
        Fb.db().collection("users").document(me)
                .set(Fb.map("favoriteUids", new ArrayList<>(favorites)), SetOptions.merge());
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        String me = Fb.uid();
        if (me == null) { finish(); return; }
        // my favorite ids (live)
        meReg = Fb.db().collection("users").document(me)
                .addSnapshotListener((d, err) -> {
                    favorites.clear();
                    if (d != null && d.exists())
                        favorites.addAll(com.friendbook.core.Format.listOf(d.getData(), "favoriteUids"));
                    render();
                });
        // my friends list
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
            countT.setText("Pehle kuch friends add karo 🙂");
            return;
        }
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
            r.subtitle = (fav ? "⭐ Favorite — posts feed ke top pe" : (u.email == null ? "" : u.email));
            r.primaryLabel = fav ? "⭐ On" : "☆ Add";
            r.secondaryLabel = "⋯";
            rows.add(r);
        }
        adapter.setData(rows);
        countT.setText(favorites.size() + " favorite(s) selected");
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
        if (meReg != null) meReg.remove();
    }
}
