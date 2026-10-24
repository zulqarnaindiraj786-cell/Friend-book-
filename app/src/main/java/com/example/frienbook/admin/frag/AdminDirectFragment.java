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
import com.example.frienbook.core.Ui;
import com.example.frienbook.friends.UsersAdapter;
import com.example.frienbook.models.Models;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Admin Tab 5: Direct blue-tick control.
 * Search any user by name/email → give or remove the blue tick instantly.
 */
public class AdminDirectFragment extends Fragment {

    private UsersAdapter adapter;
    private TextView hint;
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

        EditText search = Ui.input(a, "✓ Search by name or email, then tap the button…");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, Ui.dp(a, 46));
        sp.setMargins(Ui.dp(a, 12), Ui.dp(a, 10), Ui.dp(a, 12), 4);
        root.addView(search, sp);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int x, int y, int z) { }
            @Override public void onTextChanged(CharSequence s, int x, int y, int z) {
                if (pending != null) h.removeCallbacks(pending);
                pending = () -> { filter = s.toString().toLowerCase(Locale.ROOT); render(); };
                h.postDelayed(pending, 200);
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        hint = Ui.tv(a, "Direct control: your click writes isVerified instantly — no approvals needed.", 12, Ui.sub(a));
        Ui.pad(hint, 14, 4, 14, 4);
        root.addView(hint);

        RecyclerView rv = new RecyclerView(a);
        rv.setLayoutManager(new LinearLayoutManager(a));
        adapter = new UsersAdapter(a, new UsersAdapter.Handler() {
            @Override
            public void primary(UsersAdapter.Row r) {
                Models.User u = r.user;
                if (u.isVerified) Admin.removeBlueTick(a, u.uid, u.displayName);
                else Admin.grantBlueTick(a, u.uid, u.displayName);
            }

            @Override public void secondary(UsersAdapter.Row r) { }
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
        List<UsersAdapter.Row> rows = new ArrayList<>();
        for (Models.User u : all) {
            if (!filter.isEmpty()
                    && (u.displayName == null || !u.displayName.toLowerCase(Locale.ROOT).contains(filter))
                    && (u.email == null || !u.email.toLowerCase(Locale.ROOT).contains(filter)))
                continue;
            UsersAdapter.Row r = new UsersAdapter.Row();
            r.user = u;
            r.subtitle = u.email + (u.isVerified ? " · ✓ VERIFIED" : " · not verified");
            r.primaryLabel = u.isVerified ? "✕ Remove" : "✓ Give tick";
            rows.add(r);
        }
        adapter.setData(rows);
    }
}
