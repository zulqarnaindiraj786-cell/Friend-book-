package com.example.frienbook.admin;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.frienbook.core.Admin;
import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Format;
import com.example.frienbook.core.Ui;
import com.example.frienbook.friends.UsersAdapter;
import com.example.frienbook.models.Models;
import com.example.frienbook.profile.ProfileActivity;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Admin user management: search, ban/unban, promote to admin, delete profile. */
public class AdminUsersActivity extends Activity {

    private UsersAdapter adapter;
    private TextView countT;
    private final List<Models.User> all = new ArrayList<>();
    private ListenerRegistration reg;
    private String filter = "";
    private final Handler h = new Handler(Looper.getMainLooper());
    private Runnable pending;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Admin.isAdmin()) { finish(); return; }
        Ui.statusBarBlue(this);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "👥 Manage users"));

        EditText search = Ui.input(this, "🔍 Filter by name…");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 44));
        sp.setMargins(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), 4);
        root.addView(search, sp);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {
                if (pending != null) h.removeCallbacks(pending);
                pending = () -> { filter = s.toString().toLowerCase(Locale.ROOT); render(); };
                h.postDelayed(pending, 250);
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        countT = Ui.tv(this, "", 12, Ui.sub(this));
        Ui.pad(countT, 14, 4, 14, 2);
        root.addView(countT);

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new UsersAdapter(this, null);
        adapter.onClick = this::actions;
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        reg = Fb.db().collection("users")
                .orderBy("createdAt", Query.Direction.DESCENDING).limit(200)
                .addSnapshotListener((snap, err) -> {
                    all.clear();
                    if (snap != null) for (DocumentSnapshot d : snap) all.add(Models.User.fromDoc(d));
                    render();
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
    }

    private void render() {
        List<UsersAdapter.Row> rows = new ArrayList<>();
        for (Models.User u : all) {
            if (!filter.isEmpty() && (u.displayName == null
                    || !u.displayName.toLowerCase(Locale.ROOT).contains(filter))
                    && (u.email == null || !u.email.toLowerCase(Locale.ROOT).contains(filter)))
                continue;
            UsersAdapter.Row r = new UsersAdapter.Row();
            r.user = u;
            StringBuilder s = new StringBuilder();
            if (Admin.isOwnerEmail(u.email)) s.append("👑 OWNER · ");
            else if (u.isAdmin) s.append("🛡 Admin · ");
            if (u.banned) s.append("🚫 BANNED · ");
            s.append(u.email).append(" · ").append(Format.count(u.friendsCount)).append(" friends");
            if (u.createdAt > 0) s.append(" · joined ").append(Format.ago(u.createdAt));
            r.subtitle = s.toString();
            rows.add(r);
        }
        adapter.setData(rows);
        countT.setText(rows.size() + " users");
    }

    private void actions(Models.User u) {
        List<String> opts = new ArrayList<>();
        opts.add("👤 View profile");
        if (Admin.isOwnerEmail(u.email)) {
            opts.add("ℹ️ Owner account (protected)");
        } else {
            opts.add(u.isAdmin ? "➖ Remove admin" : "🛡 Make admin");
            opts.add(u.banned ? "✅ Unban user" : "🚫 Ban user");
            opts.add("🗑 Delete user data");
        }
        String[] arr = opts.toArray(new String[0]);
        Ui.choose(this, u.displayName + "\n" + u.email, arr, w -> {
            String sel = arr[w];
            if (sel.contains("View profile")) {
                Intent i = new Intent(this, ProfileActivity.class);
                i.putExtra("uid", u.uid);
                startActivity(i);
            } else if (sel.contains("Make admin")) {
                Fb.db().collection("users").document(u.uid).update("isAdmin", true);
                Ui.toast(this, u.displayName + " is now an admin");
            } else if (sel.contains("Remove admin")) {
                Fb.db().collection("users").document(u.uid).update("isAdmin", false);
            } else if (sel.contains("Unban")) {
                Fb.db().collection("users").document(u.uid).update("banned", false);
                Ui.toast(this, "User unbanned ✓");
            } else if (sel.contains("Ban user")) {
                Ui.confirm(this, "Ban " + u.displayName + "?",
                        "They will be signed out and blocked from using FriendBook.",
                        "Ban", () -> {
                            Fb.db().collection("users").document(u.uid).update("banned", true);
                            Ui.toast(this, "User banned");
                        });
            } else if (sel.contains("Delete user data")) {
                Ui.confirm(this, "Delete " + u.displayName + "'s profile?",
                        "Removes the Firestore profile doc. Their Auth login remains but the account becomes unusable.",
                        "Delete", () -> {
                            Fb.db().collection("users").document(u.uid).delete();
                            Ui.toast(this, "User data deleted");
                        });
            }
        });
    }
}
