package com.friendbook.social.chat;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.social.core.FastCache;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 💬 NewChatActivity — Message Friends OR Any Active friendbook Member in 1 Tap!
 * Friends & Online users sort to the top automatically.
 */
public class NewChatActivity extends Activity {

    private TextView empty;
    private RecyclerView rv;
    private com.friendbook.social.friends.UsersAdapter adapter;
    private ListenerRegistration friendsReg, usersReg;
    private final Set<String> friendIds = new HashSet<>();
    private final Map<String, Models.User> allUsers = new LinkedHashMap<>();
    private String filter = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        String fw = getIntent().getStringExtra("forward_text");
        root.addView(Ui.pageBar(this, fw != null ? "Forward message to…" : "New message"));

        if (fw == null) {
            LinearLayout createGrp = Ui.row(this);
            createGrp.setGravity(Gravity.CENTER_VERTICAL);
            createGrp.setBackgroundColor(Ui.card(this));
            Ui.pad(createGrp, 14, 12, 14, 12);
            android.widget.ImageView gIc = new android.widget.ImageView(this);
            gIc.setBackground(Ui.circle(Ui.BLUE));
            int s = Ui.dp(this, 42);
            int p = Ui.dp(this, 10);
            gIc.setPadding(p, p, p, p);
            gIc.setImageDrawable(com.friendbook.social.core.Icons.tinted(this, com.friendbook.social.core.Icons.GROUPS, 0xFFFFFFFF));
            createGrp.addView(gIc, new LinearLayout.LayoutParams(s, s));
            LinearLayout gCol = Ui.col(this);
            gCol.setPadding(Ui.dp(this, 12), 0, 0, 0);
            gCol.addView(Ui.tvBold(this, "👥 Create a New Group Chat", 15, Ui.BLUE));
            gCol.addView(Ui.tv(this, "Add friends, remove members & manage group admins", 12, Ui.sub(this)));
            createGrp.addView(gCol, new LinearLayout.LayoutParams(0, -2, 1));
            createGrp.setOnClickListener(v -> {
                startActivity(new Intent(this, CreateGroupChatActivity.class));
                finish();
            });
            root.addView(createGrp);
            root.addView(Ui.divider(this));
        }

        EditText search = Ui.input(this, "🔍 Search friends or people on friendbook…");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 44));
        sp.setMargins(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), 4);
        root.addView(search, sp);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {
                filter = s.toString().trim().toLowerCase(Locale.ROOT);
                render();
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        empty = Ui.tv(this, "", 14, Ui.sub(this));
        empty.setGravity(Gravity.CENTER);
        Ui.pad(empty, 24, 40, 24, 0);
        empty.setVisibility(View.GONE);
        root.addView(empty);

        rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setItemAnimator(null);
        adapter = new com.friendbook.social.friends.UsersAdapter(this, null);
        adapter.onClick = u -> {
            Intent i = new Intent(this, ChatActivity.class);
            i.putExtra("uid", u.uid);
            i.putExtra("name", u.displayName);
            if (fw != null && !fw.isEmpty()) i.putExtra("prefill", "↪ Forwarded: " + fw);
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

        friendsReg = Fb.db().collection("friends").whereArrayContains("participants", me)
                .addSnapshotListener((snap, err) -> {
                    friendIds.clear();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap) {
                            for (String p : Models.ChatRoom.fromDoc(d).participants) {
                                if (p != null && !p.equals(me)) friendIds.add(p);
                            }
                        }
                    }
                    render();
                });

        usersReg = Fb.db().collection("users").limit(150)
                .addSnapshotListener((snap, err) -> {
                    if (snap != null) {
                        for (DocumentSnapshot d : snap) {
                            Models.User u = Models.User.fromDoc(d);
                            if (u.uid == null || u.uid.equals(me) || u.deactivated) continue;
                            if (Fb.me != null && Fb.me.blocked != null && Fb.me.blocked.contains(u.uid)) continue;
                            FastCache.putUser(u);
                            allUsers.put(u.uid, u);
                        }
                    }
                    render();
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (friendsReg != null) friendsReg.remove();
        if (usersReg != null) usersReg.remove();
    }

    private void render() {
        List<Models.User> sorted = new ArrayList<>(allUsers.values());
        sorted.sort((a, b2) -> {
            boolean fa = friendIds.contains(a.uid), fb = friendIds.contains(b2.uid);
            if (fa != fb) return fa ? -1 : 1;
            if (a.isOnline != b2.isOnline) return a.isOnline ? -1 : 1;
            String na = a.displayName == null ? "" : a.displayName;
            String nb = b2.displayName == null ? "" : b2.displayName;
            return na.compareToIgnoreCase(nb);
        });

        List<com.friendbook.social.friends.UsersAdapter.Row> rows = new ArrayList<>();
        for (Models.User u : sorted) {
            String hay = ((u.displayName == null ? "" : u.displayName) + " "
                    + (u.bio == null ? "" : u.bio)).toLowerCase(Locale.ROOT);
            if (!filter.isEmpty() && !hay.contains(filter)) continue;
            com.friendbook.social.friends.UsersAdapter.Row r = new com.friendbook.social.friends.UsersAdapter.Row();
            r.user = u;
            boolean isFriend = friendIds.contains(u.uid);
            r.subtitle = (u.isOnline ? "🟢 Active now" : "💬 Tap to message")
                    + (isFriend ? " · 👥 Friend" : "");
            rows.add(r);
        }
        adapter.setData(rows);
        empty.setText(allUsers.isEmpty()
                ? "Loading people on friendbook…"
                : (rows.isEmpty() ? "No users match your search." : ""));
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
