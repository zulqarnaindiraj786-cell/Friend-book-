package com.example.frienbook.admin.frag;

import android.app.Fragment;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.frienbook.core.Admin;
import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Format;
import com.example.frienbook.core.Icons;
import com.example.frienbook.core.Ui;
import com.example.frienbook.friends.UsersAdapter;
import com.example.frienbook.models.Models;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Admin Tab 1: All users (name, email, blue tick) + per-user "Give Blue Tick" button + row actions. */
public class AdminUsersFragment extends Fragment {

    private UsersAdapter adapter;
    private TextView countT;
    private final List<Models.User> all = new ArrayList<>();
    private ListenerRegistration reg;
    private String filter = "";
    private final Handler h = new Handler(Looper.getMainLooper());
    private Runnable pending;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inf, ViewGroup container, Bundle b) {
        android.app.Activity a = getActivity();
        LinearLayout root = Ui.col(a);
        root.setBackgroundColor(Ui.bg(a));

        EditText search = Ui.input(a, "🔍 Filter by name or email…");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, Ui.dp(a, 44));
        sp.setMargins(Ui.dp(a, 12), Ui.dp(a, 10), Ui.dp(a, 12), 4);
        root.addView(search, sp);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int x, int y, int z) { }
            @Override public void onTextChanged(CharSequence s, int x, int y, int z) {
                if (pending != null) h.removeCallbacks(pending);
                pending = () -> { filter = s.toString().toLowerCase(Locale.ROOT); render(); };
                h.postDelayed(pending, 250);
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        countT = Ui.tv(a, "", 12, Ui.sub(a));
        Ui.pad(countT, 14, 4, 14, 2);
        root.addView(countT);

        RecyclerView rv = new RecyclerView(a);
        rv.setLayoutManager(new LinearLayoutManager(a));
        adapter = new UsersAdapter(a, new UsersAdapter.Handler() {
            @Override
            public void primary(UsersAdapter.Row r) {
                // ✓ ONE-TAP blue tick — search the user, tap, done. Admin ka full direct control.
                Models.User u = r.user;
                String next = "none".equals(u.tickLevel) ? "blue" : "none";
                Admin.setTickLevel(a, u.uid, u.displayName, next);
            }

            @Override
            public void secondary(UsersAdapter.Row r) {
                moreActions(r.user);
            }
        });
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));
        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        reg = Fb.db().collection("users")
                .orderBy("createdAt", Query.Direction.DESCENDING).limit(300)
                .addSnapshotListener((snap, err) -> {
                    all.clear();
                    if (snap != null) for (DocumentSnapshot d : snap) all.add(Models.User.fromDoc(d));
                    render();
                });
    }

    @Override
    public void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
    }

    private void render() {
        android.app.Activity a = getActivity();
        if (a == null) return;
        List<UsersAdapter.Row> rows = new ArrayList<>();
        int verifiedCount = 0;
        for (Models.User u : all) {
            if (u.isVerified) verifiedCount++;
            if (!filter.isEmpty()
                    && (u.displayName == null || !u.displayName.toLowerCase(Locale.ROOT).contains(filter))
                    && (u.email == null || !u.email.toLowerCase(Locale.ROOT).contains(filter)))
                continue;
            UsersAdapter.Row r = new UsersAdapter.Row();
            r.user = u;
            StringBuilder s = new StringBuilder();
            if (Admin.isOwnerEmail(u.email)) s.append("👑 OWNER · ");
            else if (u.isAdmin) s.append("🛡 Admin · ");
            if (u.banned) s.append("🚫 BANNED · ");
            if (!"none".equals(u.tickLevel)) s.append(Admin.tickLabel(u.tickLevel)).append(" · ");
            s.append(u.email);
            r.subtitle = s.toString();
            // ✓ one-tap blue tick — search kr k khud do (full levels in the ⋯ menu)
            r.primaryLabel = "none".equals(u.tickLevel) ? "✓ Blue tick" : "✕ Tick";
            r.secondaryLabel = "⋯";
            rows.add(r);
        }
        adapter.setData(rows);
        countT.setText(rows.size() + " users · " + verifiedCount + " have the blue tick ✓");
    }

    private void moreActions(Models.User u) {
        android.app.Activity a = getActivity();
        if (a == null) return;
        List<String> opts = new ArrayList<>();
        opts.add(u.isVerified ? "✕ Remove ALL ticks" : "✓ Give BLUE tick (instant)");
        opts.add("✨ Give GOLD tick");
        opts.add("💎 Give DIAMOND tick");
        if (Admin.isOwnerEmail(u.email)) {
            opts.add("ℹ️ Owner account (protected)");
        } else {
            opts.add(u.isAdmin ? "➖ Remove admin" : "🛡 Make admin");
            opts.add(u.banned ? "✅ Unban user" : "🚫 Ban user");
            opts.add("🗑 Delete user data");
        }
        String[] arr = opts.toArray(new String[0]);
        Ui.choose(a, u.displayName + "\n" + u.email, arr, w -> {
            String sel = arr[w];
            if (sel.contains("Give BLUE tick")) Admin.setTickLevel(a, u.uid, u.displayName, "blue");
            else if (sel.contains("Give GOLD")) Admin.setTickLevel(a, u.uid, u.displayName, "gold");
            else if (sel.contains("Give DIAMOND")) Admin.setTickLevel(a, u.uid, u.displayName, "diamond");
            else if (sel.contains("Remove ALL ticks")) Admin.setTickLevel(a, u.uid, u.displayName, "none");
            else if (sel.contains("Make admin"))
                Fb.db().collection("users").document(u.uid).update("isAdmin", true);
            else if (sel.contains("Remove admin"))
                Fb.db().collection("users").document(u.uid).update("isAdmin", false);
            else if (sel.contains("Unban"))
                Fb.db().collection("users").document(u.uid).update("banned", false);
            else if (sel.contains("Ban user"))
                Ui.confirm(a, "Ban " + u.displayName + "?", "They'll be signed out and blocked.",
                        "Ban", () -> Fb.db().collection("users").document(u.uid).update("banned", true));
            else if (sel.contains("Delete user data"))
                Ui.confirm(a, "Delete " + u.displayName + "'s profile?", "Removes the Firestore profile doc.",
                        "Delete", () -> Fb.db().collection("users").document(u.uid).delete());
        });
    }
}
