package com.friendbook.social.main;

import android.app.Fragment;
import android.content.Intent;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Notify;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.friendbook.social.post.PostDetailActivity;
import com.friendbook.social.profile.ProfileActivity;
import com.friendbook.social.d1.documents.DocumentSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** On-demand notifications: cached pages, unread highlight, explicit mark-all-read, deep links. */
public class NotificationsFragment extends Fragment {

    private static final int PAGE_SIZE = 20;
    private static final int MARK_READ_BATCH_SIZE = 20;
    private final java.util.Set<String> pendingReadIds=new java.util.HashSet<>();
    private boolean markingAll;
    private int markReadGeneration;
    private NotifAdapter adapter;
    private com.friendbook.social.core.CursorPager pager;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipe;
    private TextView moreButton;
    private TextView empty;
    private TextView chipAll, chipUnread;
    private boolean unreadOnly;
    private boolean initialLoaded;
    private String loadedActor;
    private final List<Models.Notif> data = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inf, ViewGroup container, Bundle b) {
        android.app.Activity a = getActivity();
        LinearLayout root = Ui.col(a);
        root.setBackgroundColor(Ui.bg(a));

        LinearLayout head = Ui.row(a);
        Ui.pad(head, 14, 12, 14, 6);
        TextView title = Ui.tvBold(a, "Notifications", 22, Ui.txt(a));
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView markAll = Ui.tvBold(a, "Mark all read", 13, Ui.BLUE);
        markAll.setPadding(Ui.dp(a, 8), Ui.dp(a, 6), 0, Ui.dp(a, 6));
        head.addView(markAll);
        markAll.setOnClickListener(v -> markAllReadReal());
        root.addView(head);

        // filter chips: All / Unread (friendbook)
        LinearLayout chips = Ui.row(a);
        Ui.pad(chips, 14, 0, 14, 6);
        chipAll = Ui.roundChip(a, "All", Ui.BLUE, 0xFFFFFFFF, 12);
        Ui.pad(chipAll, 12, 6, 12, 6);
        chipUnread = Ui.roundChip(a, "Unread", Ui.inputBg(a), Ui.sub(a), 12);
        Ui.pad(chipUnread, 12, 6, 12, 6);
        chipAll.setOnClickListener(v -> setUnreadOnly(false));
        chipUnread.setOnClickListener(v -> setUnreadOnly(true));
        chips.addView(chipAll);
        LinearLayout.LayoutParams cup = new LinearLayout.LayoutParams(-2, -2);
        cup.leftMargin = Ui.dp(a, 8);
        chips.addView(chipUnread, cup);
        root.addView(chips);
        moreButton=Ui.tvBold(a,"Load 20 more",14,Ui.BLUE);
        moreButton.setOnClickListener(v->{if(pager!=null)pager.next();});
        TextView refresh=Ui.tvBold(a,"Refresh notifications",13,Ui.BLUE);
        refresh.setOnClickListener(v->startPages());refresh.setVisibility(View.GONE);

        empty = Ui.tv(a, "No notifications yet.\nLikes, comments and friend requests appear here.", 14, Ui.sub(a));
        empty.setGravity(Gravity.CENTER);
        Ui.pad(empty, 24, 60, 24, 0);
        root.addView(empty);

        RecyclerView rv = new RecyclerView(a);
        LinearLayoutManager layout=new LinearLayoutManager(a);
        layout.setItemPrefetchEnabled(false);
        rv.setLayoutManager(layout);
        rv.setItemAnimator(null);
        rv.setItemViewCacheSize(20);
        adapter = new NotifAdapter();
        rv.setAdapter(adapter);
        com.friendbook.social.core.ScrollPageGate pageGate=new com.friendbook.social.core.ScrollPageGate();
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView list,int dx,int dy) {
                if(dy>0 && isResumed() && !isHidden()
                        && list.getScrollState()!=RecyclerView.SCROLL_STATE_IDLE
                        && pageGate.shouldLoad(dy,layout.findLastVisibleItemPosition()>=adapter.getItemCount()-1,adapter.getItemCount())
                        && pager!=null) pager.next();
            }
        });
        swipe = new androidx.swiperefreshlayout.widget.SwipeRefreshLayout(a);
        swipe.setColorSchemeColors(Ui.BLUE);
        swipe.addView(rv);
        swipe.setOnRefreshListener(() -> {
            initialLoaded = false;
            startPages(true);
        });
        root.addView(swipe, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(moreButton);

        // ⚡ 0.0ms Instant Cached Notifications Hydration!
        List<Models.Notif> cached = com.friendbook.social.core.FastCache.getNotifications();
        if (!cached.isEmpty()) {
            data.clear();
            data.addAll(cached.subList(0,Math.min(20,cached.size())));
            empty.setVisibility(View.GONE);
            adapter.notifyDataSetChanged();
        }
        return root;
    }

    /** ⚡ 0.0ms Optimistic mark-all-read on in-memory & disk-cached notifications. */
    public void markAllReadOptimisticNow() {
        for (Models.Notif n : data) {
            n.isRead = true;
        }
        if (unreadOnly) {
            data.clear();
        } else if (!data.isEmpty()) {
            com.friendbook.social.core.FastCache.saveNotifications(data);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (empty != null) empty.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void markAllReadReal() {
        // Only the explicit "Mark all read" button invokes this operation.
        String me = Fb.uid();
        if (me == null || markingAll) return;
        markingAll=true;
        markUnreadBatch(me, ++markReadGeneration, null);
    }

    private void markUnreadBatch(String me, int generation, DocumentSnapshot cursor) {
        // All rows are touched only for this explicit operation, never for opening the tab.
        if (!isAdded() || isHidden() || !me.equals(Fb.uid()) || generation!=markReadGeneration) {
            markingAll=false;return;
        }
        com.friendbook.social.d1.documents.Query unread=Fb.db().collection("notifications")
                .whereEqualTo("toUserId", me).whereEqualTo("isRead", false)
                .orderBy("createdAt",com.friendbook.social.d1.documents.Query.Direction.DESCENDING);
        // Keyset continuation avoids repeatedly scanning the already-marked prefix.
        if(cursor!=null)unread=unread.startAfter(cursor);
        unread.limit(MARK_READ_BATCH_SIZE).get(com.friendbook.social.d1.documents.Source.SERVER).addOnSuccessListener(snap -> {
                    if (!me.equals(Fb.uid()) || generation!=markReadGeneration) return;
                    if (snap == null) { markingAll=false;return; }
                    if (snap.isEmpty()) { finishMarkAll(me,generation);return; }
                    com.friendbook.social.d1.documents.WriteBatch batch = Fb.db().batch();
                    for (DocumentSnapshot d : snap) batch.update(d.getReference(), "isRead", true);
                    batch.commit().addOnSuccessListener(v -> {
                        if (!me.equals(Fb.uid()) || generation!=markReadGeneration) return;
                        if (snap.size() == MARK_READ_BATCH_SIZE) markUnreadBatch(me,generation,snap.getDocuments().get(snap.size()-1));
                        else finishMarkAll(me,generation);
                    }).addOnFailureListener(error -> markAllFailed(generation));
                }).addOnFailureListener(error -> markAllFailed(generation));
    }

    private void finishMarkAll(String me,int generation) {
        if (!me.equals(Fb.uid()) || generation!=markReadGeneration) return;
        markingAll=false;
        markAllReadOptimisticNow();
        MainActivity.clearNotificationsBadgeNow();
        // The unread pager must not keep old unread cursors after a successful bulk action.
        if (isAdded() && !isHidden()) startPages();
    }

    private void markAllFailed(int generation) {
        if (generation!=markReadGeneration) return;
        markingAll=false;
        if (getActivity()!=null) Ui.toast(getActivity(),"Couldn't mark all read. Tap to retry.");
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            MainActivity.clearNotificationsBadgeNow();
            if (adapter != null) startPages();
        } else {
            stopPages();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if (!isHidden()) {
            MainActivity.clearNotificationsBadgeNow();
        }
        String me = Fb.uid();
        if (me == null || isHidden()) return;
        if (pager == null || !java.util.Objects.equals(loadedActor, me)) {
            startPages();
        }
    }
    private void startPages() { startPages(false); }
    private void startPages(boolean full) {
        stopPages();
        String me=Fb.uid();if(me==null)return;
        if (loadedActor != null && !me.equals(loadedActor)) { data.clear(); adapter.notifyDataSetChanged(); }
        loadedActor = me;
        initialLoaded = true;
        com.friendbook.social.d1.documents.Query query=Fb.db().collection("notifications").whereEqualTo("toUserId",me);
        if(unreadOnly)query=query.whereEqualTo("isRead",false);
        query = query.orderBy("createdAt", com.friendbook.social.d1.documents.Query.Direction.DESCENDING);
        pager=new com.friendbook.social.core.CursorPager(query,PAGE_SIZE,"notifications:"+unreadOnly,(docs,more,loading,error)->{
            android.app.Activity act=getActivity();if(act==null || adapter==null)return;
            if(loading){moreButton.setText("Loading…");moreButton.setEnabled(false);return;}
            if(swipe!=null)swipe.setRefreshing(false);
            List<Models.Notif> next=new ArrayList<>();
            for(DocumentSnapshot doc:docs){Models.Notif n=Models.Notif.fromDoc(doc);if(allowedByPrefs(act,n.type))next.add(n);}
            next.sort(java.util.Comparator.comparingLong((Models.Notif n)->n.createdAt).reversed().thenComparing(n->n.id,java.util.Comparator.reverseOrder()));
            if (!next.isEmpty() || error.isEmpty()) adapter.replace(next);
            if(!unreadOnly)com.friendbook.social.core.FastCache.saveNotifications(data);
            empty.setText(error.isEmpty() ? "No notifications yet.\nLikes, comments and friend requests appear here." : error);
            empty.setVisibility(data.isEmpty()?View.VISIBLE:View.GONE);
            moreButton.setText(!error.isEmpty()?"Retry notifications":more?"Load 20 more":"All loaded");
            moreButton.setEnabled(!loading && (more || !error.isEmpty()));
        });
        if(full)pager.refreshOnDemand(); else pager.startOnDemand();
    }

    private void setUnreadOnly(boolean on) {
        if (unreadOnly == on && initialLoaded) return;
        unreadOnly = on;
        if (adapter != null) adapter.replace(java.util.Collections.emptyList());
        initialLoaded = false;
        android.app.Activity a = getActivity();
        if (a == null) return;
        chipAll.setBackground(Ui.round(a, on ? Ui.inputBg(a) : Ui.BLUE, 10));
        chipAll.setTextColor(on ? Ui.sub(a) : 0xFFFFFFFF);
        chipUnread.setBackground(Ui.round(a, on ? Ui.BLUE : Ui.inputBg(a), 10));
        chipUnread.setTextColor(on ? 0xFFFFFFFF : Ui.sub(a));
        startPages(); // Reset cursor and cache key when the filter changes.
    }

    /** Settings > Notifications switches define what appears here. */
    private boolean allowedByPrefs(android.app.Activity a, String type) {
        if (type == null) return true;
        switch (type) {
            case "like":
            case "reaction":
            case "reel_like":
            case "story_reaction": return com.friendbook.social.core.Prefs.notifLikes(a);
            case "comment":
            case "comment_reply": return com.friendbook.social.core.Prefs.notifComments(a);
            case "friend_request":
            case "friend_accept": return com.friendbook.social.core.Prefs.notifRequests(a);
            case "message": return com.friendbook.social.core.Prefs.notifMessages(a);
            case "mention":
            case "tag": return com.friendbook.social.core.Prefs.notifMentions(a);
            case "birthday": return com.friendbook.social.core.Prefs.notifBirthdays(a);
            case "group": return com.friendbook.social.core.Prefs.notifGroups(a);
            case "marketplace": return com.friendbook.social.core.Prefs.notifMarketplace(a);
            case "security": return com.friendbook.social.core.Prefs.notifSecurity(a);
            default: return true;
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        stopPages();
        initialLoaded = false;
    }

    private void stopPages() {
        markReadGeneration++;markingAll=false;
        if(pager!=null){pager.close();pager=null;}
    }

    private void open(Models.Notif n) {
        if (!n.isRead && pendingReadIds.add(n.id)) {
            final String actor=Fb.uid();
            Fb.db().collection("notifications").document(n.id).update("isRead", true)
                    .addOnSuccessListener(ignored -> {
                        pendingReadIds.remove(n.id);
                        if (!java.util.Objects.equals(actor,Fb.uid())) return;
                        n.isRead=true;
                        if (unreadOnly) { data.remove(n);if(pager!=null)pager.forget(n.id); }
                        else com.friendbook.social.core.FastCache.saveNotifications(data);
                        if(adapter!=null)adapter.notifyDataSetChanged();
                        if(empty!=null)empty.setVisibility(data.isEmpty()?View.VISIBLE:View.GONE);
                    }).addOnFailureListener(error -> pendingReadIds.remove(n.id));
        }
        android.app.Activity a = getActivity();
        if (a == null) return;
        switch (n.type == null ? "" : n.type) {
            case "friend_request":
                if (a instanceof MainActivity) ((MainActivity) a).select(MainActivity.TAB_FRIENDS);
                break;
            case "group_invite":
            case "group":
                if (n.groupId != null && !n.groupId.isEmpty()) {
                    Intent gi = new Intent(a, com.friendbook.social.groups.GroupDetailActivity.class);
                    gi.putExtra("group_id", n.groupId);
                    startActivity(gi);
                } else {
                    startActivity(new Intent(a, com.friendbook.social.groups.GroupsActivity.class));
                }
                break;
            case "team":
            case "announcement":
            case "tick":
            case "payment":
            case "ban":
                Intent tc = new Intent(a, com.friendbook.social.chat.ChatActivity.class);
                tc.putExtra("uid", Ui.TEAM_USER_ID);
                startActivity(tc);
                break;
            case "friend_accept":
            case "follow":
            case "message":
                Intent p = new Intent(a, ProfileActivity.class);
                p.putExtra("uid", n.fromUserId);
                startActivity(p);
                break;
            case "live":
            case "live_invite":
                if (n.postId != null && !n.postId.isEmpty()) {
                    Intent li = new Intent(a, com.friendbook.social.live.LiveActivity.class);
                    li.putExtra("live_id", n.postId);
                    li.putExtra("host_id", n.fromUserId);
                    startActivity(li);
                } else {
                    startActivity(new Intent(a, com.friendbook.social.live.LiveHubActivity.class));
                }
                break;
            case "reel_like":
            case "reel_comment":
                if (a instanceof MainActivity) ((MainActivity) a).select(MainActivity.TAB_REELS);
                break;
            default:
                if (n.groupId != null && !n.groupId.isEmpty()) {
                    Intent gi = new Intent(a, com.friendbook.social.groups.GroupDetailActivity.class);
                    gi.putExtra("group_id", n.groupId);
                    startActivity(gi);
                } else if (n.postId != null && !n.postId.isEmpty()) {
                    Intent i = new Intent(a, PostDetailActivity.class);
                    i.putExtra("post_id", n.postId);
                    startActivity(i);
                } else if (Ui.isTeamIdentity(n.fromUserPhoto, n.fromUserName)
                        || Ui.TEAM_USER_ID.equals(n.fromUserId)) {
                    Intent tc2 = new Intent(a, com.friendbook.social.chat.ChatActivity.class);
                    tc2.putExtra("uid", Ui.TEAM_USER_ID);
                    startActivity(tc2);
                } else {
                    Intent p2 = new Intent(a, ProfileActivity.class);
                    p2.putExtra("uid", n.fromUserId);
                    startActivity(p2);
                }
                break;
        }
    }

    private String emoji(String type) {
        if (type == null) return "🔔";
        switch (type) {
            case "like": return "👍";
            case "comment": case "comment_reply": return "💬";
            case "comment_like": return "❤️";
            case "friend_request": case "friend_accept": return "👥";
            case "follow": return "🚶";
            case "share": return "↗";
            case "reel_like": case "reel_comment": return "🎞️";
            case "group_invite": case "group": return "👥";
            case "announcement": case "team": return "📣";
            case "live": case "live_invite": return "🔴";
            case "tick": return "✓";
            default: return "🔔";
        }
    }

    class NotifAdapter extends RecyclerView.Adapter<NotifAdapter.VH> {

        void replace(List<Models.Notif> next) {
            List<Models.Notif> before = new ArrayList<>(data);
            androidx.recyclerview.widget.DiffUtil.DiffResult diff=androidx.recyclerview.widget.DiffUtil.calculateDiff(new androidx.recyclerview.widget.DiffUtil.Callback() {
                public int getOldListSize(){return before.size();}
                public int getNewListSize(){return next.size();}
                public boolean areItemsTheSame(int a,int b){return before.get(a).id.equals(next.get(b).id);}
                public boolean areContentsTheSame(int a,int b){return false;}
            },false);
            data.clear();data.addAll(next);diff.dispatchUpdatesTo(this);
        }

        @Override public int getItemCount() { return data.size(); }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            android.app.Activity c = getActivity();
            LinearLayout row = Ui.row(c);
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            Ui.pad(row, 12, 10, 12, 10);

            FrameLayout avWrap = new FrameLayout(c);
            ImageView av = new ImageView(c);
            int s = Ui.dp(c, 52);
            avWrap.addView(av, new FrameLayout.LayoutParams(s, s));
            TextView typeDot = Ui.tv(c, "", 16, Ui.txt(c));
            typeDot.setBackground(Ui.circle(Ui.BLUE));
            int ds = Ui.dp(c, 26);
            typeDot.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(ds, ds, Gravity.BOTTOM | Gravity.END);
            avWrap.addView(typeDot, tp);
            ImageView tick = new ImageView(c);
            int tks = Ui.dp(c, 16);
            FrameLayout.LayoutParams tkp = new FrameLayout.LayoutParams(tks, tks, Gravity.TOP | Gravity.END);
            avWrap.addView(tick, tkp);
            row.addView(avWrap);

            LinearLayout mid = Ui.col(c);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1);
            mp.leftMargin = Ui.dp(c, 12);
            TextView text = Ui.tv(c, "", 14, Ui.txt(c));
            TextView time = Ui.tv(c, "", 12, Ui.sub(c));
            time.setPadding(0, Ui.dp(c, 2), 0, 0);
            mid.addView(text);
            mid.addView(time);
            row.addView(mid, mp);
            return new VH(row, av, typeDot, tick, text, time);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Models.Notif n = data.get(position);
            android.app.Activity c = getActivity();
            boolean isTeam = Ui.isTeamIdentity(n.fromUserPhoto, n.fromUserName)
                    || Ui.TEAM_USER_ID.equals(n.fromUserId)
                    || "announcement".equals(n.type) || "team".equals(n.type)
                    || "tick".equals(n.type) || "payment".equals(n.type) || "ban".equals(n.type);
            Ui.avatar(c, h.av, isTeam ? Ui.TEAM_AVATAR_TOKEN : n.fromUserPhoto);
            h.typeDot.setText(emoji(n.type));
            if (isTeam) {
                com.friendbook.social.core.Icons.setTickBadge(h.tick, "gold", false);
            } else {
                com.friendbook.social.core.TickCache.attach(h.tick, n.fromUserId);
            }
            String displaySender = isTeam ? "friendbook Team" : n.fromUserName;
            SpannableStringBuilder sb = new SpannableStringBuilder();
            int start = sb.length();
            sb.append(displaySender);
            sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(" ").append(n.text == null || n.text.isEmpty() ? "interacted with you" : n.text);
            h.text.setText(sb);
            h.time.setText(Format.ago(n.createdAt));
            h.itemView.setBackgroundColor(n.isRead ? Ui.bg(c) : Ui.unread(c));
            h.itemView.setOnClickListener(v -> open(n));
        }

        class VH extends RecyclerView.ViewHolder {
            ImageView av, tick;
            TextView typeDot, text, time;
            VH(View v, ImageView a, TextView td, ImageView tk, TextView t, TextView tm) {
                super(v);
                av = a; typeDot = td; tick = tk; text = t; time = tm;
            }
        }
    }
}
