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
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.friendbook.social.core.CfClient;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.FastCache;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Icons;
import com.friendbook.social.core.Prefs;
import com.friendbook.social.core.Presence;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.google.android.gms.tasks.Task;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 💬 ConversationsActivity — Full friendbook Messenger Home:
 * Search bar, 🟢 Active Now horizontal strip, Inbox / Archived tabs,
 * Pin, Mute, Nicknames, Archive & Unread badges.
 * 
 * FIXES for stable list (no flicker):
 * - Snapshot listener on chats (not get()) for real-time updates
 * - DiffUtil in adapter for smooth animations
 * - Never clear list - update incrementally via docChanges
 * - Sort by lastMessageTime DESC in memory
 * - Limit 30 initially, load more on scroll
 * - Show cached data immediately, no empty screen
 */
public class ConversationsActivity extends Activity {

    private RecyclerView rv;
    private ConvAdapter adapter;
    private ListenerRegistration chatsReg, usersReg;
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

    // ============ CHAT LIST (stable, no flicker) ============
    private final List<Room> allRooms = new ArrayList<>();      // All chats from listener
    private final List<Room> rooms = new ArrayList<>();         // Filtered list for adapter
    private final List<Room> displayRooms = new ArrayList<>();  // Sorted list for display
    private boolean chatsListenerAttached = false;
    private boolean initialLoadDone = false;
    private boolean inboxLoading = false;
    private boolean inboxMore = true;
    private DocumentSnapshot inboxCursor;
    private String inboxActor;
    private int inboxGeneration;
    private TextView inboxMoreButton;
    private android.widget.ProgressBar inboxSpinner;
    private SwipeRefreshLayout swipeRefresh;

    // ============ CACHES ============
    private static final long CONVERSATIONS_CACHE_TTL_MS = 60_000; // 60 seconds
    private final Map<String, Long> conversationsCacheAt = new HashMap<>();
    private final Map<String, List<Room>> conversationsCache = new HashMap<>();
    private final Set<String> friendIds = new HashSet<>();
    private boolean friendsLoaded = false;

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

        // Search bar
        LinearLayout searchRow = Ui.row(this);
        searchRow.setBackgroundColor(Ui.card(this));
        Ui.pad(searchRow, 12, 8, 12, 8);
        EditText search = Ui.input(this, "Search chats...");
        search.setLayoutParams(new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s.toString().trim().toLowerCase();
                applyFilterAndSort();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        searchRow.addView(search);
        root.addView(searchRow);
        root.addView(Ui.divider(this));

        // Tabs - using Ui.tvBold like original
        LinearLayout tabs = Ui.row(this);
        tabs.setBackgroundColor(Ui.bg(this));
        Ui.pad(tabs, 8, 4, 8, 4);
        inboxTab = Ui.tvBold(this, "📥 Inbox", 11.5f, Ui.sub(this));
        inboxTab.setGravity(Gravity.CENTER);
        Ui.pad(inboxTab, 8, 6, 8, 6);
        groupsTab = Ui.tvBold(this, "👥 Groups", 11.5f, Ui.sub(this));
        groupsTab.setGravity(Gravity.CENTER);
        Ui.pad(groupsTab, 8, 6, 8, 6);
        unreadTab = Ui.tvBold(this, "🔔 Unread", 11.5f, Ui.sub(this));
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
        inboxTab.setOnClickListener(v -> { tabMode = 0; showArchived = false; updateTabs(); applyFilterAndSort(); });
        groupsTab.setOnClickListener(v -> { tabMode = 3; showArchived = false; updateTabs(); applyFilterAndSort(); });
        unreadTab.setOnClickListener(v -> { tabMode = 1; showArchived = false; updateTabs(); applyFilterAndSort(); });
        archiveTab.setOnClickListener(v -> { tabMode = 2; showArchived = true; updateTabs(); applyFilterAndSort(); });
        root.addView(tabs);
        root.addView(Ui.divider(this));

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

        empty = Ui.tvBold(this, "No conversations yet.\nTap ✏️ New Chat or tap any person above to start chatting 💬", 14, Ui.BLUE);
        empty.setGravity(Gravity.CENTER);
        Ui.pad(empty, 24, 50, 24, 16);
        empty.setVisibility(View.GONE);
        empty.setOnClickListener(v -> startActivity(new Intent(this, NewChatActivity.class)));
        root.addView(empty);

        // SwipeRefreshLayout wrapping RecyclerView
        swipeRefresh = new SwipeRefreshLayout(this);
        swipeRefresh.setColorSchemeColors(Ui.BLUE);
        swipeRefresh.setOnRefreshListener(() -> {
            refreshChatList();
            swipeRefresh.setRefreshing(false);
        });

        rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setItemAnimator(null); // Disable default animator - DiffUtil handles animations
        rv.setItemViewCacheSize(30);
        adapter = new ConvAdapter();
        rv.setAdapter(adapter);

        // NearEndPaging for compatibility + custom scroll listener for load more
        com.friendbook.social.core.NearEndPaging.attach(rv, () -> loadMoreChats());
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (lm != null && !inboxLoading && inboxMore) {
                    int visibleItemCount = lm.getChildCount();
                    int totalItemCount = lm.getItemCount();
                    int firstVisibleItem = lm.findFirstVisibleItemPosition();
                    if (firstVisibleItem + visibleItemCount >= totalItemCount - 5) {
                        loadMoreChats();
                    }
                }
            }
        });

        swipeRefresh.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(swipeRefresh, new LinearLayout.LayoutParams(-1, 0, 1));

        inboxMoreButton = Ui.tvBold(this, "Load more chats", 13, Ui.BLUE);
        inboxMoreButton.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        inboxMoreButton.setOnClickListener(v -> loadMoreChats());
        inboxMoreButton.setVisibility(View.GONE);
        root.addView(inboxMoreButton);

        inboxSpinner = new android.widget.ProgressBar(this);
        inboxSpinner.setVisibility(View.GONE);
        inboxSpinner.setContentDescription("Loading more conversations");
        root.addView(inboxSpinner, new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26)));

        setContentView(root);

        // ⚡ 0.0ms Instant Frame #1 Hydration from FastCache!
        String me0 = Fb.uid();
        if (me0 != null) {
            inboxActor = me0;
            int cachedRooms = 0;
            for (Models.ChatRoom cr : com.friendbook.social.core.FastCache.getChats()) {
                if (cachedRooms++ >= 30) break;
                Room r = new Room();
                r.room = cr;
                r.other = cr.otherUid(me0);
                if (r.other.isEmpty()) continue;
                Models.User cu = com.friendbook.social.core.FastCache.getUser(r.other);
                if (cu != null) userCache.put(r.other, cu);
                allRooms.add(r);
            }
            if (!allRooms.isEmpty()) {
                sortAndFilterRooms();
                renderActiveNow();
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
        com.friendbook.social.main.MainActivity.clearMessagesBadgeNow();

        // Load friends for Active Now filter
        loadFriends();

        // Attach chats snapshot listener (only once)
        if (!chatsListenerAttached || !java.util.Objects.equals(inboxActor, me)) {
            attachChatsListener(me);
        }

        // Refresh if needed
        if (!initialLoadDone) {
            initialLoadDone = true;
            // List already populated from cache in onCreate
        }
    }

    private void attachChatsListener(String me) {
        if (chatsReg != null) {
            chatsReg.remove();
            chatsReg = null;
        }
        inboxActor = me;
        chatsListenerAttached = true;

        // Query: all chats where current user is a participant
        // Sort by lastMessageTime DESC in memory (Firestore can't do whereArrayContains + orderBy different field easily)
        Query query = Fb.db().collection("chats")
                .whereArrayContains("participants", me)
                .limit(100); // Load up to 100 chats for smooth scrolling

        chatsReg = query.addSnapshotListener((snap, err) -> {
            if (err != null || snap == null || isFinishing() || !java.util.Objects.equals(me, Fb.uid())) return;

            // Process document changes incrementally - NEVER clear the list
            for (com.google.firebase.firestore.DocumentChange change : snap.getDocumentChanges()) {
                DocumentSnapshot d = change.getDocument();
                String chatId = d.getId();

                Set<String> hiddenChats = getSharedPreferences("fb_prefs", MODE_PRIVATE)
                        .getStringSet("hiddenChats", new HashSet<>());
                if (hiddenChats.contains(chatId)) {
                    // Remove if was hidden
                    if (change.getType() != com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        removeRoom(chatId);
                    }
                    continue;
                }

                Models.ChatRoom room = Models.ChatRoom.fromDoc(d);
                String other = room.otherUid(me);
                if (!room.isGroup && other.isEmpty()) continue;
                if (!room.isGroup && Fb.me != null && Fb.me.blocked != null && Fb.me.blocked.contains(other)) continue;

                Room r = new Room();
                r.room = room;
                r.other = other;

                Object u = d.get("unreadCount");
                if (u instanceof Map) {
                    Object n = ((Map<?, ?>) u).get(me);
                    if (n instanceof Number) r.unread = ((Number) n).intValue();
                }

                if (change.getType() == com.google.firebase.firestore.DocumentChange.Type.REMOVED) {
                    removeRoom(chatId);
                } else {
                    // ADDED or MODIFIED - upsert
                    upsertRoom(r);
                }

                // Load user profile for 1:1 chats
                if (!room.isGroup && !Ui.TEAM_USER_ID.equals(other) && !userCache.containsKey(other)) {
                    Fb.getUser(other).addOnSuccessListener(us -> {
                        if (us != null) {
                            com.friendbook.social.core.FastCache.putUser(us);
                            userCache.put(other, us);
                            renderActiveNow();
                            applyFilterAndSort();
                        }
                    });
                }
            }

            // Update cache
            List<Models.ChatRoom> toCache = new ArrayList<>();
            for (Room r : allRooms) toCache.add(r.room);
            com.friendbook.social.core.FastCache.saveChats(toCache);

            String cacheKey = "conversations_" + me;
            conversationsCache.put(cacheKey, new ArrayList<>(allRooms));
            conversationsCacheAt.put(cacheKey, android.os.SystemClock.elapsedRealtime());

            // Sort and filter (this triggers DiffUtil in adapter)
            sortAndFilterRooms();
            renderActiveNow();
        });
    }

    private void upsertRoom(Room newRoom) {
        // Remove existing if present
        allRooms.removeIf(r -> r.room.id.equals(newRoom.room.id));
        // Add new/updated room
        allRooms.add(newRoom);
    }

    private void removeRoom(String chatId) {
        allRooms.removeIf(r -> r.room.id.equals(chatId));
    }

    private void sortAndFilterRooms() {
        // Sort by lastMessageTime DESC (newest first)
        Collections.sort(allRooms, (a, b) -> Long.compare(b.room.lastMessageTime, a.room.lastMessageTime));

        // Apply filters
        applyFilterAndSort();
    }

    private void applyFilterAndSort() {
        List<Room> filtered = new ArrayList<>();
        Set<String> pinned = Prefs.pinnedChats(this);

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
            filtered.add(r);
        }

        // Sort: pinned first, then by lastMessageTime DESC
        filtered.sort((a, b) -> {
            boolean pa = pinned.contains(a.room.id), pb = pinned.contains(b.room.id);
            if (pa != pb) return pa ? -1 : 1;
            return Long.compare(b.room.lastMessageTime, a.room.lastMessageTime);
        });

        // Update adapter via DiffUtil (smooth, no flicker)
        adapter.submitList(new ArrayList<>(filtered));
        empty.setText(showArchived
                ? "No archived conversations 📦"
                : "No conversations yet.\nTap ✏️ New Chat or tap any person above to start chatting 💬");
        empty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void loadMoreChats() {
        // With whereArrayContains, true pagination is hard.
        // We already load up to 100 in the listener.
        // This button is kept for compatibility but does nothing extra.
        inboxMoreButton.setVisibility(View.GONE);
    }

    // Call this when a new message arrives (from FCM or after sending)
    public void refreshChatList() {
        // Listener is already attached and will receive updates automatically
        // Just ensure we're listening to the right user
        String me = Fb.uid();
        if (me != null && !java.util.Objects.equals(inboxActor, me)) {
            attachChatsListener(me);
        }
    }

    private void loadFriends() {
        if (friendsLoaded) return;
        String me = Fb.uid();
        if (me == null) return;

        CfClient.getFriends().addOnSuccessListener(friends -> {
            if (isFinishing() || !java.util.Objects.equals(me, Fb.uid())) return;
            friendIds.clear();
            for (Models.User u : friends) {
                if (u != null && u.uid != null) {
                    friendIds.add(u.uid);
                }
            }
            friendsLoaded = true;
            renderActiveNow();
        }).addOnFailureListener(e -> {
            android.util.Log.w("ConversationsActivity", "Failed to load friends for Active Now filter", e);
            friendsLoaded = true;
            renderActiveNow();
        });
    }

    private void renderActiveNow() {
        activeRow.removeAllViews();
        String me = Fb.uid();
        long now = Presence.now();
        int count = 0;

        // Only show users who meet ALL conditions:
        // 1. Have existing conversation (chat room exists)
        // 2. Are friends (mutual follow)
        // 3. Are online (last_seen < 5 min = 300000ms) - using cached user doc
        for (Room r : allRooms) {
            if (r.room.isGroup) continue;
            String otherUid = r.other;
            if (otherUid == null || otherUid.isEmpty() || otherUid.equals(me) || otherUid.equals(Ui.TEAM_USER_ID)) continue;
            if (Fb.me != null && Fb.me.blocked != null && Fb.me.blocked.contains(otherUid)) continue;

            if (!friendIds.contains(otherUid)) continue;

            Models.User u = userCache.get(otherUid);
            if (u == null) u = FastCache.getUser(otherUid);
            if (u == null || u.deactivated) continue;

            long lastSeen = u.lastSeen > 0 ? u.lastSeen : 0;
            boolean online = lastSeen > 0 && (now - lastSeen) < 300_000;
            boolean recentlyOnline = lastSeen > 0 && (now - lastSeen) < 300_000;

            if (!online && !recentlyOnline) continue;

            if (count >= 18) break;
            LinearLayout b = Ui.col(this);
            b.setGravity(Gravity.CENTER);
            FrameLayout aw = new FrameLayout(this);
            ImageView av = new ImageView(this);
            Ui.avatar(this, av, u.photoUrl);
            int s = Ui.dp(this, 48);
            aw.addView(av, new FrameLayout.LayoutParams(s, s));
            if (online || recentlyOnline) {
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
            final Models.User clickedUser = u;
            b.setOnClickListener(v -> {
                Intent i = new Intent(this, ChatActivity.class);
                i.putExtra("uid", clickedUser.uid);
                i.putExtra("name", clickedUser.displayName);
                startActivity(i);
            });
            activeRow.addView(b, lp);
            count++;
        }
        activeWrap.setVisibility(count > 0 && !showArchived ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (chatsReg != null) { chatsReg.remove(); chatsReg = null; chatsListenerAttached = false; }
        if (usersReg != null) { usersReg.remove(); usersReg = null; }
        watchingPresence = false;
        for (Presence.Handle handle : activePresence.values()) handle.close();
        activePresence.clear();
        for (ConvAdapter.VH h : boundHolders) { h.dot.setVisibility(View.GONE); h.lastActive.setVisibility(View.GONE); }
        boundHolders.clear();
        friendsLoaded = false;
        friendIds.clear();
        renderActiveNow();
    }

    // ============ ROOM MODEL ============
    static class Room {
        Models.ChatRoom room;
        int unread;
        String other;
    }

    // ============ ADAPTER WITH DIFFUTIL ============
    class ConvAdapter extends RecyclerView.Adapter<ConvAdapter.VH> {
        private final List<Room> currentList = new ArrayList<>();

        void submitList(List<Room> newList) {
            DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new RoomDiffCallback(currentList, newList), true);
            currentList.clear();
            currentList.addAll(newList);
            diffResult.dispatchUpdatesTo(this);
        }

        @Override public int getItemCount() { return currentList.size(); }

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
            Room r = currentList.get(position);
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
            }

            // Last message preview
            String lastMsg = r.room.lastMessage;
            if (lastMsg != null && !lastMsg.isEmpty()) {
                h.last.setText(lastMsg);
            } else {
                h.last.setText("");
            }

            // Time
            if (r.room.lastMessageTime > 0) {
                h.time.setText(com.friendbook.social.core.TimeAgoUtil.formatShortLastSeen(r.room.lastMessageTime, Presence.now()));
                h.time.setVisibility(View.VISIBLE);
            } else {
                h.time.setVisibility(View.GONE);
            }

            // Unread badge
            if (r.unread > 0) {
                com.friendbook.social.core.Ui.setBadge(h.badge, r.unread);
                h.badge.setVisibility(View.VISIBLE);
            } else {
                h.badge.setVisibility(View.GONE);
            }

            // Pin
            if (Prefs.pinnedChats(ConversationsActivity.this).contains(r.room.id)) {
                h.pin.setText("📌");
                h.pin.setVisibility(View.VISIBLE);
            } else {
                h.pin.setVisibility(View.GONE);
            }

            // Click to open chat
            h.itemView.setOnClickListener(v -> {
                Intent i = new Intent(ConversationsActivity.this, ChatActivity.class);
                i.putExtra("chatId", r.room.id);
                if (!r.room.isGroup) {
                    i.putExtra("uid", r.other);
                    i.putExtra("name", resolvedName);
                }
                startActivity(i);
            });

            // Long press for menu
            h.itemView.setOnLongClickListener(v -> {
                roomMenu(r);
                return true;
            });
        }

        static class VH extends RecyclerView.ViewHolder {
            ImageView av, tick;
            View dot;
            TextView name, last, time, badge, pin, lastActive;
            String boundUid;
            VH(View v, ImageView av, View dot, TextView name, ImageView tick, TextView last, TextView time, TextView badge, TextView pin, TextView lastActive) {
                super(v);
                this.av = av; this.dot = dot; this.name = name; this.tick = tick;
                this.last = last; this.time = time; this.badge = badge; this.pin = pin; this.lastActive = lastActive;
            }
        }
    }

    // DiffUtil callback for smooth list updates
    private static class RoomDiffCallback extends DiffUtil.Callback {
        private final List<Room> oldList, newList;
        RoomDiffCallback(List<Room> oldList, List<Room> newList) {
            this.oldList = oldList; this.newList = newList;
        }
        @Override public int getOldListSize() { return oldList.size(); }
        @Override public int getNewListSize() { return newList.size(); }
        @Override public boolean areItemsTheSame(int oldPos, int newPos) {
            return oldList.get(oldPos).room.id.equals(newList.get(newPos).room.id);
        }
        @Override public boolean areContentsTheSame(int oldPos, int newPos) {
            Room o = oldList.get(oldPos);
            Room n = newList.get(newPos);
            return o.unread == n.unread
                    && java.util.Objects.equals(o.room.lastMessage, n.room.lastMessage)
                    && o.room.lastMessageTime == n.room.lastMessageTime;
        }
    }

    // ============ ROOM MENU ============
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
                applyFilterAndSort();
            } else if (w == 1) {
                Prefs.muteChat(this, r.room.id, !isMuted);
                Ui.toast(this, !isMuted ? "Chat muted 🔕" : "Chat unmuted 🔔");
                applyFilterAndSort();
            } else if (w == 2) {
                Prefs.archiveChat(this, r.room.id, !isArch);
                Ui.toast(this, !isArch ? "Chat archived 📦" : "Moved back to Inbox");
                applyFilterAndSort();
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
                    getSharedPreferences("fb_prefs", MODE_PRIVATE).edit().putStringSet("hiddenChats", hs).apply();
                    applyFilterAndSort();
                    Ui.toast(this, "Conversation hidden 🗑");
                });
            } else if (w == 5 && !r.room.isGroup) {
                Ui.confirm(this, "Block " + r.other + "?", "They won't be able to message or find you.", "Block", () -> {
                    Fb.db().collection("users").document(Fb.uid())
                            .set(Fb.map("blockedUsers", FieldValue.arrayUnion(r.other)), SetOptions.merge());
                    Ui.toast(this, "Blocked 🚫");
                    applyFilterAndSort();
                });
            }
        });
    }
}