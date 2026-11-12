package com.example.frienbook.chat;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Ui;
import com.example.frienbook.models.Models;
import com.example.frienbook.core.cloud.DocumentSnapshot;
import com.example.frienbook.core.cloud.ListenerRegistration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pick a friend to chat with. */
public class NewChatActivity extends Activity {

    private android.widget.TextView empty;
    private androidx.recyclerview.widget.RecyclerView rv;
    private com.example.frienbook.friends.UsersAdapter adapter;
    private ListenerRegistration reg;
    private final List<Models.User> friends = new ArrayList<>();
    private String filter = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "New message"));

        EditText search = Ui.input(this, "🔍 Search friends…");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 44));
        sp.setMargins(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), 4);
        root.addView(search, sp);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {
                filter = s.toString().toLowerCase(Locale.ROOT);
                render();
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        empty = Ui.tv(this, "", 14, Ui.sub(this));
        empty.setGravity(android.view.Gravity.CENTER);
        Ui.pad(empty, 24, 40, 24, 0);
        root.addView(empty);

        rv = new androidx.recyclerview.widget.RecyclerView(this);
        rv.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        adapter = new com.example.frienbook.friends.UsersAdapter(this, null);
        adapter.onClick = u -> {
            Intent i = new Intent(this, ChatActivity.class);
            i.putExtra("uid", u.uid);
            i.putExtra("name", u.displayName);
            startActivity(i);
            finish();
        };
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        String me = Fb.uid();
        if (me == null) { finish(); return; }
        reg = Fb.db().collection("friends").whereArrayContains("participants", me)
                .addSnapshotListener((snap, err) -> {
                    List<String> ids = new ArrayList<>();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap) {
                            for (String p : Models.ChatRoom.fromDoc(d).participants)
                                if (!p.equals(me)) ids.add(p);
                        }
                    }
                    friends.clear();
                    if (ids.isEmpty()) { render(); return; }
                    final int[] pending = {ids.size()};
                    for (String uid : ids) {
                        Fb.getUser(uid).addOnSuccessListener(u -> {
                            if (u != null) friends.add(u);
                            if (--pending[0] <= 0) render();
                        });
                    }
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
    }

    private void render() {
        List<com.example.frienbook.friends.UsersAdapter.Row> rows = new ArrayList<>();
        for (Models.User u : friends) {
            if (!filter.isEmpty() && (u.displayName == null
                    || !u.displayName.toLowerCase(Locale.ROOT).contains(filter))) continue;
            com.example.frienbook.friends.UsersAdapter.Row r = new com.example.frienbook.friends.UsersAdapter.Row();
            r.user = u;
            r.subtitle = u.isOnline ? "🟢 Active now" : (u.bio == null ? "" : u.bio);
            rows.add(r);
        }
        adapter.setData(rows);
        empty.setText(friends.isEmpty()
                ? "Add some friends first — then you can message them here."
                : (rows.isEmpty() ? "No friends match your search." : ""));
        empty.setVisibility(rows.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
    }
}
