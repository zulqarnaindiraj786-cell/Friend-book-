package com.example.frienbook.chat;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Format;
import com.example.frienbook.core.Prefs;
import com.example.frienbook.core.Presence;
import com.example.frienbook.core.Ui;
import com.example.frienbook.models.Models;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Messenger home: realtime conversation list with unread counts, pins, presence. */
public class ConversationsActivity extends Activity {

    private RecyclerView rv;
    private ConvAdapter adapter;
    private ListenerRegistration reg;
    private final Map<String, Models.User> userCache = new HashMap<>();
    private final List<Room> rooms = new ArrayList<>();

    static class Room {
        Models.ChatRoom room;
        int unread;
        String other;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));

        LinearLayout bar = Ui.pageBar(this, "💬 Chats");
        TextView newChat = Ui.tv(this, "✎", 22, 0xFFFFFFFF);
        newChat.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 6), 0);
        newChat.setOnClickListener(v -> startActivity(new Intent(this, NewChatActivity.class)));
        bar.addView(newChat);
        root.addView(bar);

        rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ConvAdapter();
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        String me = Fb.uid();
        if (me == null) { finish(); return; }
        reg = Fb.db().collection("chats").whereArrayContains("participants", me)
                .addSnapshotListener((snap, err) -> {
                    rooms.clear();
                    if (snap != null) {
                        Set<String> hiddenChats = getSharedPreferences("fb_prefs", MODE_PRIVATE)
                                .getStringSet("hiddenChats", new java.util.HashSet<>());
                        for (DocumentSnapshot d : snap) {
                            if (hiddenChats.contains(d.getId())) continue;
                            Room r = new Room();
                            r.room = Models.ChatRoom.fromDoc(d);
                            r.other = r.room.otherUid(me);
                            if (r.other.isEmpty()) continue;
                            Object u = d.get("unreadCount");
                            if (u instanceof Map) {
                                Object n = ((Map<?, ?>) u).get(me);
                                if (n instanceof Number) r.unread = ((Number) n).intValue();
                            }
                            rooms.add(r);
                            if (!userCache.containsKey(r.other)) {
                                Fb.getUser(r.other).addOnSuccessListener(us -> {
                                    if (us != null) { userCache.put(r.other, us); adapter.notifyDataSetChanged(); }
                                });
                            }
                        }
                    }
                    Set<String> pinned = Prefs.pinnedChats(this);
                    rooms.sort((a, b) -> {
                        boolean pa = pinned.contains(a.room.id), pb = pinned.contains(b.room.id);
                        if (pa != pb) return pa ? -1 : 1;
                        return Long.compare(b.room.lastMessageTime, a.room.lastMessageTime);
                    });
                    adapter.notifyDataSetChanged();
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
    }

    private void roomMenu(Room r) {
        Set<String> pinned = Prefs.pinnedChats(this);
        boolean isPinned = pinned.contains(r.room.id);
        String[] opts = {isPinned ? "📌 Unpin chat" : "📌 Pin chat", "✔ Mark as read", "🗑 Delete conversation", "🚫 Block user"};
        Ui.choose(this, "Chat options", opts, w -> {
            if (w == 0) {
                Set<String> p = Prefs.pinnedChats(this);
                if (isPinned) p.remove(r.room.id); else p.add(r.room.id);
                Prefs.setPinnedChats(this, p);
            } else if (w == 1) {
                Map<String, Object> inner = new HashMap<>();
                inner.put(Fb.uid(), 0);
                Fb.db().collection("chats").document(r.room.id)
                        .set(Fb.map("unreadCount", inner), com.google.firebase.firestore.SetOptions.merge());
            } else if (w == 2) {
                Ui.confirm(this, "Delete this conversation?", "It will be hidden from your chat list.", "Delete", () -> {
                    Set<String> hidden = getSharedPreferences("fb_prefs", MODE_PRIVATE)
                            .getStringSet("hiddenChats", new java.util.HashSet<>());
                    Set<String> hs = new java.util.HashSet<>(hidden);
                    hs.add(r.room.id);
                    getSharedPreferences("fb_prefs", MODE_PRIVATE).edit()
                            .putStringSet("hiddenChats", hs).apply();
                    rooms.remove(r);
                    adapter.notifyDataSetChanged();
                });
            } else if (w == 3) {
                Ui.confirm(this, "Block this user?", "", "Block", () -> {
                    Fb.db().collection("users").document(Fb.uid())
                            .set(Fb.map("blockedUsers", FieldValue.arrayUnion(r.other)),
                                    com.google.firebase.firestore.SetOptions.merge());
                    Ui.toast(this, "Blocked");
                });
            }
        });
    }

    // ================== adapter ==================

    class ConvAdapter extends RecyclerView.Adapter<ConvAdapter.VH> {

        @Override public int getItemCount() { return rooms.size(); }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = Ui.row(ConversationsActivity.this);
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            Ui.pad(row, 12, 10, 12, 10);

            FrameLayout avWrap = new FrameLayout(ConversationsActivity.this);
            ImageView av = new ImageView(ConversationsActivity.this);
            int s = Ui.dp(ConversationsActivity.this, 54);
            avWrap.addView(av, new FrameLayout.LayoutParams(s, s));
            View dot = Ui.onlineDot(ConversationsActivity.this);
            FrameLayout.LayoutParams dp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            avWrap.addView(dot, dp);
            dot.setVisibility(View.GONE);
            row.addView(avWrap);

            LinearLayout mid = Ui.col(ConversationsActivity.this);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1);
            mp.leftMargin = Ui.dp(ConversationsActivity.this, 12);
            TextView name = Ui.tvBold(ConversationsActivity.this, "", 16, Ui.txt(ConversationsActivity.this));
            TextView last = Ui.tv(ConversationsActivity.this, "", 13, Ui.sub(ConversationsActivity.this));
            last.setMaxLines(1);
            mid.addView(name);
            mid.addView(last);
            row.addView(mid, mp);

            LinearLayout right = Ui.col(ConversationsActivity.this);
            right.setGravity(Gravity.END);
            TextView time = Ui.tv(ConversationsActivity.this, "", 11, Ui.sub(ConversationsActivity.this));
            TextView badge = Ui.badge(ConversationsActivity.this);
            TextView pin = Ui.tv(ConversationsActivity.this, "", 12, Ui.sub(ConversationsActivity.this));
            right.addView(time);
            right.addView(badge);
            right.addView(pin);
            row.addView(right);
            return new VH(row, av, dot, name, last, time, badge, pin);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Room r = rooms.get(position);
            Models.User u = userCache.get(r.other);
            h.name.setText(u != null ? u.displayName : "Loading…");
            Ui.avatar(ConversationsActivity.this, h.av, u != null ? u.photoUrl : "");
            boolean unread = r.unread > 0;
            h.last.setText(r.room.lastMessage == null || r.room.lastMessage.isEmpty()
                    ? "Say hi 👋" : r.room.lastMessage);
            h.last.setTypeface(null, unread ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            h.last.setTextColor(unread ? Ui.txt(ConversationsActivity.this) : Ui.sub(ConversationsActivity.this));
            h.time.setText(Format.ago(r.room.lastMessageTime));
            Ui.setBadge(h.badge, r.unread);
            Set<String> pinned = Prefs.pinnedChats(ConversationsActivity.this);
            h.pin.setText(pinned.contains(r.room.id) ? "📌" : "");

            // presence
            if (h.presence != null) h.presence.close();
            h.presence = Presence.watch(r.other, (online, lastSeen) -> {
                h.dot.setVisibility(online && r.unread == 0 ? View.VISIBLE : View.GONE);
            });

            h.itemView.setOnClickListener(v -> {
                Intent i = new Intent(ConversationsActivity.this, ChatActivity.class);
                i.putExtra("uid", r.other);
                if (u != null) i.putExtra("name", u.displayName);
                startActivity(i);
            });
            h.itemView.setOnLongClickListener(v -> { roomMenu(r); return true; });
        }

        @Override
        public void onViewRecycled(@NonNull VH h) {
            super.onViewRecycled(h);
            if (h.presence != null) { h.presence.close(); h.presence = null; }
        }

        class VH extends RecyclerView.ViewHolder {
            ImageView av;
            View dot;
            TextView name, last, time, badge, pin;
            Presence.Handle presence;
            VH(View v, ImageView a, View d, TextView n, TextView l, TextView t, TextView b, TextView p) {
                super(v);
                av = a; dot = d; name = n; last = l; time = t; badge = b; pin = p;
            }
        }
    }
}
