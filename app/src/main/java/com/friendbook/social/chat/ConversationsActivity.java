package com.friendbook.social.chat;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Icons;
import com.friendbook.social.core.Prefs;
import com.friendbook.social.core.Presence;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 💬 ConversationsActivity — Full friendbook Messenger Home:
 * Search bar, 🟢 Active Now horizontal strip, Inbox / Archived tabs,
 * Pin, Mute, Nicknames, Archive & Unread badges.
 */
public class ConversationsActivity extends Activity {

    private RecyclerView rv;
    private ConvAdapter adapter;
    private ListenerRegistration reg, usersReg;
    private LinearLayout activeRow, activeWrap;
    private TextView inboxTab, unreadTab, groupsTab, archiveTab, empty;
    private int tabMode = 0; // 0 = All Inbox, 1 = Unread Only, 3 = Groups Only, 2 = Archived
    private boolean showArchived = false;
    private String query = "";
    private final Map<String, Models.User> userCache = new HashMap<>();
    private boolean watchingPresence;
    private final Map<String, Presence.Handle> activePresence = new HashMap<>();
    private final Map<String, Boolean> onlineUsers = new HashMap<>();
    private final Map<String, Long> lastSeenUsers = new HashMap<>();
    private final Set<ConvAdapter.VH> boundHolders = new HashSet<>();

    private void ensurePresence(String uid) {
        if (!watchingPresence || uid == null || uid.isEmpty() || Ui.TEAM_USER_ID.equals(uid) || uid.equals(Fb.uid()) || activePresence.containsKey(uid)) return;
        Presence.Handle handle = Presence.watch(uid, (online, lastSeen) -> {
            if (!watchingPresence) return;
            onlineUsers.put(uid, online); lastSeenUsers.put(uid, lastSeen);
            renderActiveNow();
            for (ConvAdapter.VH holder : boundHolders) if (uid.equals(holder.boundUid)) renderRowPresence(holder, online, lastSeen);
        });
        activePresence.put(uid, handle);
    }
    private void renderRowPresence(ConvAdapter.VH h, boolean online, long seen) {
        h.dot.setVisibility(online ? View.VISIBLE : View.GONE);
        h.lastActive.setText(com.friendbook.social.core.TimeAgoUtil.formatShortLastSeen(seen, Presence.now()));
        h.lastActive.setContentDescription(Presence.formatActiveStatus(false, seen));
        h.lastActive.setVisibility(!online && seen > 0 ? View.VISIBLE : View.GONE);
        h.time.setVisibility(!online && seen > 0 ? View.GONE : View.VISIBLE);
    }

    private final List<Room> allRooms = new ArrayList<>();
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

        LinearLayout bar = Ui.pageBar(this, "Messages");
        TextView newGroupBtn = Ui.roundChip(this, "👥 New Group", Ui.BLUE, Color.WHITE, 12f);
        Ui.pad(newGroupBtn, 11, 6, 11, 6);
        newGroupBtn.setOnClickListener(v -> startActivity(new Intent(this, CreateGroupChatActivity.class)));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-2, -2);
        gp.rightMargin = Ui.dp(this, 6);
        bar.addView(newGroupBtn, gp);

        TextView newChatBtn = Ui.roundChip(this, "✏️ Chat", Ui.inputBg(this), Ui.txt(this), 12f);
        Ui.pad(newChatBtn, 11, 6, 11, 6);
        newChatBtn.setOnClickListener(v -> startActivity(new Intent(this, NewChatActivity.class)));
        bar.addView(newChatBtn);
        root.addView(bar);

        // Search + Inbox/Archived tabs
        LinearLayout top = Ui.col(this);
        top.setBackgroundColor(Ui.card(this));
        Ui.pad(top, 10, 8, 10, 8);
        EditText search = Ui.input(this, "🔎 Search messages or people…");
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {
                query = s.toString().trim().toLowerCase();
                applyFilter();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        top.addView(search, new LinearLayout.LayoutParams(-1, Ui.dp(this, 40)));

        LinearLayout tabs = Ui.row(this);
        tabs.setPadding(0, Ui.dp(this, 8), 0, 0);
        inboxTab = Ui.tvBold(this, "💬 All", 11.5f, Color.WHITE);
        inboxTab.setGravity(Gravity.CENTER);
        Ui.pad(inboxTab, 8, 6, 8, 6);
        groupsTab = Ui.tvBold(this, "👥 Groups", 11.5f, Ui.sub(this));
        groupsTab.setGravity(Gravity.CENTER);
        Ui.pad(groupsTab, 8, 6, 8, 6);
        unreadTab = Ui.tvBold(this, "🔵 Unread", 11.5f, Ui.sub(this));
        unreadTab.setGravity(Gravity.CENTER);
        Ui.pad(unreadTab, 8, 6, 8, 6);
        archiveTab = Ui.tvBold(this, "📦 Archived", 11.5f, Ui.sub(this));
        archiveTab.setGravity(Gravity.CENTER);
        Ui.pad(archiveTab, 8, 6, 8, 6);
        LinearLayout.LayoutParams tp1 = new LinearLayout.LayoutParams(0, -2, 1);
        LinearLayout.LayoutParams tpG = new LinearLayout.LayoutParams(0, -2, 1);
        tpG.leftMargin = Ui.dp(this, 5);
        LinearLayout.LayoutParams tpU = new LinearLayout.LayoutParams(0, -2, 1);
        tpU.leftMargin = Ui.dp(this, 5);
        LinearLayout.LayoutParams tp2 = new LinearLayout.LayoutParams(0, -2, 1);
        tp2.leftMargin = Ui.dp(this, 5);
        tabs.addView(inboxTab, tp1);
        tabs.addView(groupsTab, tpG);
        tabs.addView(unreadTab, tpU);
        tabs.addView(archiveTab, tp2);
        inboxTab.setOnClickListener(v -> { tabMode = 0; showArchived = false; updateTabs(); applyFilter(); });
        groupsTab.setOnClickListener(v -> { tabMode = 3; showArchived = false; updateTabs(); applyFilter(); });
        unreadTab.setOnClickListener(v -> { tabMode = 1; showArchived = false; updateTabs(); applyFilter(); });
        archiveTab.setOnClickListener(v -> { tabMode = 2; showArchived = true; updateTabs(); applyFilter(); });
        top.addView(tabs);
        root.addView(top);
        updateTabs();

        // 🟢 Active Now horizontal bar
        activeWrap = Ui.col(this);
        activeWrap.setBackgroundColor(Ui.card(this));
        activeWrap.setVisibility(View.GONE);
        TextView actLabel = Ui.tvBold(this, "🟢 Active now", 12, Ui.sub(this));
        Ui.pad(actLabel, 12, 4, 12, 2);
        activeWrap.addView(actLabel);
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        activeRow = Ui.row(this);
        Ui.pad(activeRow, 10, 4, 10, 8);
        hsv.addView(activeRow);
        activeWrap.addView(hsv);
        root.addView(activeWrap);
        root.addView(Ui.divider(this));

        empty = Ui.tvBold(this, "No conversations yet.\nTap ✏️ New Chat or tap here to message anyone on friendbook 💬", 14, Ui.BLUE);
        empty.setGravity(Gravity.CENTER);
        Ui.pad(empty, 24, 50, 24, 16);
        empty.setVisibility(View.GONE);
        empty.setOnClickListener(v -> startActivity(new Intent(this, NewChatActivity.class)));
        root.addView(empty);

        rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setItemAnimator(null);
        rv.setItemViewCacheSize(20);
        adapter = new ConvAdapter();
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        // ⚡ 0.0ms Instant Frame #1 Hydration from FastCache!
        String me0 = Fb.uid();
        if (me0 != null) {
            for (Models.ChatRoom cr : com.friendbook.social.core.FastCache.getChats()) {
                Room r = new Room();
                r.room = cr;
                r.other = cr.otherUid(me0);
                if (r.other.isEmpty()) continue;
                Models.User cu = com.friendbook.social.core.FastCache.getUser(r.other);
                if (cu != null) userCache.put(r.other, cu);
                allRooms.add(r);
            }
            if (!allRooms.isEmpty()) {
                renderActiveNow();
                applyFilter();
            }
        }
    }

    private void updateTabs() {
        inboxTab.setBackground(Ui.round(this, tabMode == 0 ? Ui.BLUE : Ui.inputBg(this), 16));
        inboxTab.setTextColor(tabMode == 0 ? Color.WHITE : Ui.sub(this));
        if (groupsTab != null) {
            groupsTab.setBackground(Ui.round(this, tabMode == 3 ? Ui.BLUE : Ui.inputBg(this), 16));
            groupsTab.setTextColor(tabMode == 3 ? Color.WHITE : Ui.sub(this));
        }
        if (unreadTab != null) {
            unreadTab.setBackground(Ui.round(this, tabMode == 1 ? Ui.BLUE : Ui.inputBg(this), 16));
            unreadTab.setTextColor(tabMode == 1 ? Color.WHITE : Ui.sub(this));
        }
        archiveTab.setBackground(Ui.round(this, tabMode == 2 ? Ui.BLUE : Ui.inputBg(this), 16));
        archiveTab.setTextColor(tabMode == 2 ? Color.WHITE : Ui.sub(this));
    }

    @Override
    protected void onStart() {
        super.onStart();
        String me = Fb.uid();
        if (me == null) { finish(); return; }
        watchingPresence = true;
        adapter.notifyDataSetChanged();
        // Dismiss only the outer icon badge; preserve every conversation's unread state.
        com.friendbook.social.main.MainActivity.clearMessagesBadgeNow();
        // Live snapshot listener on users so Active Now strip updates green online dots in real time!
        usersReg = Fb.db().collection("users").limit(45).addSnapshotListener((usnap, uerr) -> {
            if (isFinishing() || usnap == null) return;
            for (DocumentSnapshot ud : usnap) {
                Models.User u = Models.User.fromDoc(ud);
                if (u.uid == null || u.uid.equals(me)) continue;
                if (u.deactivated) { userCache.remove(u.uid); onlineUsers.remove(u.uid); continue; }
                if (Fb.me != null && Fb.me.blocked != null && Fb.me.blocked.contains(u.uid)) continue;
                com.friendbook.social.core.FastCache.putUser(u);
                userCache.put(u.uid, u);
                ensurePresence(u.uid);
            }
            renderActiveNow();
            applyFilter();
        });
        reg = Fb.db().collection("chats").whereArrayContains("participants", me)
                .addSnapshotListener((snap, err) -> {
                    allRooms.clear();
                    if (snap != null) {
                        Set<String> hiddenChats = getSharedPreferences("fb_prefs", MODE_PRIVATE)
                                .getStringSet("hiddenChats", new HashSet<>());
                        for (DocumentSnapshot d : snap) {
                            CallActivity.maybeHandleIncomingFromRoomDoc(ConversationsActivity.this, d);
                            if (hiddenChats.contains(d.getId())) continue;
                            Room r = new Room();
                            r.room = Models.ChatRoom.fromDoc(d);
                            r.other = r.room.otherUid(me);
                            if (!r.room.isGroup && r.other.isEmpty()) continue;
                            if (!r.room.isGroup && Fb.me != null && Fb.me.blocked != null && Fb.me.blocked.contains(r.other)) continue;
                            Object u = d.get("unreadCount");
                            if (u instanceof Map) {
                                Object n = ((Map<?, ?>) u).get(me);
                                if (n instanceof Number) r.unread = ((Number) n).intValue();
                            }
                            allRooms.add(r);
                            if (!r.room.isGroup && !Ui.TEAM_USER_ID.equals(r.other) && !userCache.containsKey(r.other)) {
                                Fb.getUser(r.other).addOnSuccessListener(us -> {
                                    if (us != null) {
                                        com.friendbook.social.core.FastCache.putUser(us);
                                        userCache.put(r.other, us);
                                        ensurePresence(r.other);
                                        renderActiveNow();
                                        applyFilter();
                                    }
                                });
                            }
                        }
                    }
                    List<Models.ChatRoom> toCache = new ArrayList<>();
                    for (Room r : allRooms) toCache.add(r.room);
                    com.friendbook.social.core.FastCache.saveChats(toCache);
                    renderActiveNow();
                    applyFilter();
                });
    }

    private void renderActiveNow() {
        activeRow.removeAllViews();
        String me = Fb.uid();
        List<Models.User> stripUsers = new ArrayList<>(userCache.values());
        stripUsers.sort((a, b2) -> String.valueOf(a.uid).compareTo(String.valueOf(b2.uid)));
        int count = 0;
        for (Models.User u : stripUsers) {
            if (u == null || u.uid == null || u.uid.equals(me) || u.deactivated) continue;
            if (Fb.me != null && Fb.me.blocked != null && Fb.me.blocked.contains(u.uid)) continue;
            boolean online = Boolean.TRUE.equals(onlineUsers.get(u.uid));
            if (!online) continue; // Active Now contains only fresh, live presence; never cached isOnline flags.
            if (count >= 18) break;
            LinearLayout b = Ui.col(this);
            b.setGravity(Gravity.CENTER);
            FrameLayout aw = new FrameLayout(this);
            ImageView av = new ImageView(this);
            Ui.avatar(this, av, u.photoUrl);
            int s = Ui.dp(this, 48);
            aw.addView(av, new FrameLayout.LayoutParams(s, s));
            if (online) {
                View dot = Ui.onlineDot(this);
                int ds = Ui.dp(this, 13);
                aw.addView(dot, new FrameLayout.LayoutParams(ds, ds, Gravity.BOTTOM | Gravity.END));
            }
            b.addView(aw);
            TextView nm = Ui.tv(this, u.displayName == null ? "User" : u.displayName.split(" ")[0], 11, Ui.txt(this));
            nm.setSingleLine(true);
            nm.setMaxWidth(Ui.dp(this, 58));
            b.addView(nm);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = Ui.dp(this, 12);
            b.setOnClickListener(v -> {
                Intent i = new Intent(this, ChatActivity.class);
                i.putExtra("uid", u.uid);
                i.putExtra("name", u.displayName);
                startActivity(i);
            });
            activeRow.addView(b, lp);
            count++;
        }
        activeWrap.setVisibility(count > 0 && !showArchived ? View.VISIBLE : View.GONE);
    }

    private void applyFilter() {
        rooms.clear();
        for (Room r : allRooms) {
            boolean arch = Prefs.isChatArchived(this, r.room.id);
            if (arch != showArchived) continue;
            if (tabMode == 1 && r.unread <= 0) continue;
            if (tabMode == 3 && !r.room.isGroup) continue;
            if (!query.isEmpty()) {
                Models.User u = userCache.get(r.other);
                String nick = Prefs.chatNickname(this, r.room.id);
                String gName = r.room.groupName == null ? "" : r.room.groupName;
                String hay = ((u != null && u.displayName != null ? u.displayName : "") + " "
                        + gName + " " + nick + " "
                        + (r.room.lastMessage == null ? "" : r.room.lastMessage)).toLowerCase();
                if (!hay.contains(query)) continue;
            }
            rooms.add(r);
        }
        Set<String> pinned = Prefs.pinnedChats(this);
        rooms.sort((a, b2) -> {
            boolean pa = pinned.contains(a.room.id), pb = pinned.contains(b2.room.id);
            if (pa != pb) return pa ? -1 : 1;
            return Long.compare(b2.room.lastMessageTime, a.room.lastMessageTime);
        });
        adapter.notifyDataSetChanged();
        empty.setText(showArchived
                ? "No archived conversations 📦"
                : "No conversations yet.\nTap ✏️ New Chat or tap any person above to start chatting 💬");
        empty.setVisibility(rooms.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
        if (usersReg != null) usersReg.remove();
        watchingPresence = false;
        for (Presence.Handle handle : activePresence.values()) handle.close();
        activePresence.clear(); onlineUsers.clear(); lastSeenUsers.clear();
        for (ConvAdapter.VH h : boundHolders) { h.dot.setVisibility(View.GONE); h.lastActive.setVisibility(View.GONE); }
        boundHolders.clear();
        renderActiveNow();
    }

    private void roomMenu(Room r) {
        Set<String> pinned = Prefs.pinnedChats(this);
        boolean isPinned = pinned.contains(r.room.id);
        boolean isMuted = Prefs.isChatMuted(this, r.room.id);
        boolean isArch = Prefs.isChatArchived(this, r.room.id);
        String[] opts = {
                isPinned ? "📌 Unpin chat" : "📌 Pin chat",
                isMuted ? "🔔 Unmute notifications" : "🔕 Mute notifications",
                isArch ? "📥 Unarchive chat" : "📦 Archive chat",
                "✔ Mark as read",
                "🗑 Delete conversation",
                "🚫 Block user"
        };
        Ui.choose(this, "Chat options", opts, w -> {
            if (w == 0) {
                Set<String> p = Prefs.pinnedChats(this);
                if (isPinned) p.remove(r.room.id); else p.add(r.room.id);
                Prefs.setPinnedChats(this, p);
                applyFilter();
            } else if (w == 1) {
                Prefs.muteChat(this, r.room.id, !isMuted);
                Ui.toast(this, !isMuted ? "Chat muted 🔕" : "Chat unmuted 🔔");
                applyFilter();
            } else if (w == 2) {
                Prefs.archiveChat(this, r.room.id, !isArch);
                Ui.toast(this, !isArch ? "Chat archived 📦" : "Moved back to Inbox");
                applyFilter();
            } else if (w == 3) {
                Map<String, Object> inner = new HashMap<>();
                inner.put(Fb.uid(), 0);
                Fb.db().collection("chats").document(r.room.id)
                        .set(Fb.map("unreadCount", inner), SetOptions.merge());
            } else if (w == 4) {
                Ui.confirm(this, "Delete this conversation?", "It will be hidden from your chat list.", "Delete", () -> {
                    Set<String> hidden = getSharedPreferences("fb_prefs", MODE_PRIVATE)
                            .getStringSet("hiddenChats", new HashSet<>());
                    Set<String> hs = new HashSet<>(hidden);
                    hs.add(r.room.id);
                    getSharedPreferences("fb_prefs", MODE_PRIVATE).edit()
                            .putStringSet("hiddenChats", hs).apply();
                    allRooms.remove(r);
                    applyFilter();
                });
            } else if (w == 5) {
                Ui.confirm(this, "Block this user?", "", "Block", () -> {
                    Fb.db().collection("users").document(Fb.uid())
                            .set(Fb.map("blockedUsers", FieldValue.arrayUnion(r.other)), SetOptions.merge());
                    Ui.toast(this, "Blocked");
                });
            }
        });
    }

    class ConvAdapter extends RecyclerView.Adapter<ConvAdapter.VH> {
        @Override public int getItemCount() { return rooms.size(); }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = Ui.row(ConversationsActivity.this);
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            row.setBackgroundColor(Ui.card(ConversationsActivity.this));
            Ui.pad(row, 12, 10, 12, 10);

            FrameLayout avWrap = (FrameLayout) getLayoutInflater().inflate(com.friendbook.social.R.layout.view_presence_avatar, row, false);
            ImageView av = avWrap.findViewById(com.friendbook.social.R.id.presence_avatar);
            View dot = avWrap.findViewById(com.friendbook.social.R.id.presence_green_dot);
            row.addView(avWrap);

            LinearLayout mid = Ui.col(ConversationsActivity.this);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1);
            mp.leftMargin = Ui.dp(ConversationsActivity.this, 12);
            TextView name = Ui.tvBold(ConversationsActivity.this, "", 16, Ui.txt(ConversationsActivity.this));
            ImageView tick = new ImageView(ConversationsActivity.this);
            int ts = Ui.dp(ConversationsActivity.this, 17);
            LinearLayout.LayoutParams tickp = new LinearLayout.LayoutParams(ts, ts);
            tickp.leftMargin = Ui.dp(ConversationsActivity.this, 5);
            tickp.gravity = Gravity.CENTER_VERTICAL;
            LinearLayout nameRow = Ui.row(ConversationsActivity.this);
            nameRow.addView(name);
            nameRow.addView(tick, tickp);
            TextView last = Ui.tv(ConversationsActivity.this, "", 13, Ui.sub(ConversationsActivity.this));
            last.setMaxLines(1);
            mid.addView(nameRow);
            mid.addView(last);
            row.addView(mid, mp);

            LinearLayout right = Ui.col(ConversationsActivity.this);
            right.setGravity(Gravity.END);
            TextView time = Ui.tv(ConversationsActivity.this, "", 11, Ui.sub(ConversationsActivity.this));
            TextView badge = Ui.badge(ConversationsActivity.this);
            TextView pin = Ui.tv(ConversationsActivity.this, "", 12, Ui.sub(ConversationsActivity.this));
            TextView lastActive = (TextView) getLayoutInflater().inflate(com.friendbook.social.R.layout.view_last_active, right, false);
            lastActive.setTextColor(Ui.sub(ConversationsActivity.this));
            right.addView(time);
            right.addView(lastActive);
            right.addView(badge);
            right.addView(pin);
            row.addView(right);
            return new VH(row, av, dot, name, tick, last, time, badge, pin, lastActive);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Room r = rooms.get(position);
            boundHolders.add(h); h.boundUid = null;
            h.lastActive.setVisibility(View.GONE); h.time.setVisibility(View.VISIBLE);
            h.dot.setVisibility(View.GONE);
            h.av.setPadding(0, 0, 0, 0);
            h.av.setBackground(null);

            final String resolvedName;
            if (r.room.isGroup) {
                resolvedName = (r.room.groupName == null || r.room.groupName.isEmpty())
                        ? "Group Chat" : r.room.groupName;
                h.name.setText("👥 " + resolvedName);
                h.tick.setVisibility(View.GONE);
                if (r.room.groupPhoto != null && !r.room.groupPhoto.isEmpty()) {
                    Ui.avatar(ConversationsActivity.this, h.av, r.room.groupPhoto);
                } else {
                    h.av.setBackground(Ui.circle(Ui.BLUE));
                    int p = Ui.dp(ConversationsActivity.this, 13);
                    h.av.setPadding(p, p, p, p);
                    h.av.setImageDrawable(Icons.tinted(ConversationsActivity.this, Icons.GROUPS, Color.WHITE));
                }
            } else if (Ui.TEAM_USER_ID.equals(r.other)) {
                resolvedName = "friendbook Team";
                h.name.setText(resolvedName);
                Icons.setTickBadge(h.tick, "gold", false);
                Ui.avatar(ConversationsActivity.this, h.av, Ui.TEAM_AVATAR_TOKEN);
            } else {
                Models.User tmpU = userCache.get(r.other);
                if (tmpU == null) {
                    tmpU = com.friendbook.social.core.FastCache.getUser(r.other);
                    if (tmpU != null) userCache.put(r.other, tmpU);
                }
                final Models.User u = tmpU;
                String nick = Prefs.chatNickname(ConversationsActivity.this, r.room.id);
                String fallbackName = r.room.participantNames != null ? r.room.participantNames.get(r.other) : null;
                String fallbackPhoto = r.room.participantPhotos != null ? r.room.participantPhotos.get(r.other) : null;
                resolvedName = !nick.isEmpty() ? nick
                        : (u != null && u.displayName != null && !u.displayName.isEmpty() ? u.displayName
                        : (fallbackName != null && !fallbackName.isEmpty() ? fallbackName : "friendbook user"));
                final String resolvedPhoto = u != null && u.photoUrl != null && !u.photoUrl.isEmpty()
                        ? u.photoUrl : (fallbackPhoto == null ? "" : fallbackPhoto);
                h.name.setText(resolvedName);
                Icons.setTickBadge(h.tick, u != null ? u.tickLevel : "none", false);
                Ui.avatar(ConversationsActivity.this, h.av, resolvedPhoto);
                h.boundUid = r.other;
                ensurePresence(r.other);
                renderRowPresence(h, Boolean.TRUE.equals(onlineUsers.get(r.other)), lastSeenUsers.getOrDefault(r.other, 0L));
            }

            boolean unread = r.unread > 0;
            h.last.setText(r.room.lastMessage == null || r.room.lastMessage.isEmpty()
                    ? "Say hi 👋" : r.room.lastMessage);
            h.last.setTypeface(null, unread ? Typeface.BOLD : Typeface.NORMAL);
            h.last.setTextColor(unread ? Ui.txt(ConversationsActivity.this) : Ui.sub(ConversationsActivity.this));
            h.time.setText(r.room.lastMessageTime > 0 ? Format.ago(r.room.lastMessageTime) : "now");
            Ui.setBadge(h.badge, r.unread);

            String flags = (Prefs.pinnedChats(ConversationsActivity.this).contains(r.room.id) ? "📌" : "")
                    + (Prefs.isChatMuted(ConversationsActivity.this, r.room.id) ? "🔕" : "");
            h.pin.setText(flags);

            h.itemView.setOnClickListener(v -> {
                Intent i = new Intent(ConversationsActivity.this, ChatActivity.class);
                if (r.room.isGroup) {
                    i.putExtra("chat_id", r.room.id);
                    i.putExtra("is_group", true);
                    i.putExtra("name", resolvedName);
                } else {
                    i.putExtra("uid", r.other);
                    i.putExtra("name", resolvedName);
                }
                startActivity(i);
            });
            h.itemView.setOnLongClickListener(v -> { roomMenu(r); return true; });
        }

        @Override
        public void onViewRecycled(@NonNull VH h) {
            super.onViewRecycled(h);
            boundHolders.remove(h); h.boundUid = null;
            h.dot.setVisibility(View.GONE); h.lastActive.setVisibility(View.GONE);
        }

        class VH extends RecyclerView.ViewHolder {
            ImageView av, tick;
            View dot;
            TextView name, last, time, badge, pin, lastActive;
            String boundUid;
            VH(View v, ImageView a, View d, TextView n, ImageView tk, TextView l, TextView t, TextView b, TextView p, TextView active) {
                super(v);
                av = a; dot = d; name = n; tick = tk; last = l; time = t; badge = b; pin = p; lastActive = active;
            }
        }
    }
}
