package com.example.frienbook.main;

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

import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Format;
import com.example.frienbook.core.Notify;
import com.example.frienbook.core.Ui;
import com.example.frienbook.models.Models;
import com.example.frienbook.post.PostDetailActivity;
import com.example.frienbook.profile.ProfileActivity;
import com.example.frienbook.core.cloud.DocumentSnapshot;
import com.example.frienbook.core.cloud.ListenerRegistration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Notifications tab: realtime list, unread highlight, mark-all-read, deep links. */
public class NotificationsFragment extends Fragment {

    private NotifAdapter adapter;
    private ListenerRegistration reg;
    private TextView empty;
    private TextView chipAll, chipUnread;
    private boolean unreadOnly;
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

        // filter chips: All / Unread (Facebook Lite)
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

        empty = Ui.tv(a, "No notifications yet.\nLikes, comments and friend requests appear here.", 14, Ui.sub(a));
        empty.setGravity(Gravity.CENTER);
        Ui.pad(empty, 24, 60, 24, 0);
        root.addView(empty);

        RecyclerView rv = new RecyclerView(a);
        rv.setLayoutManager(new LinearLayoutManager(a));
        adapter = new NotifAdapter();
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));
        return root;
    }

    private void markAllReadReal() {
        String me = Fb.uid();
        if (me == null) return;
        Fb.db().collection("notifications")
                .whereEqualTo("toUserId", me).whereEqualTo("isRead", false)
                .get().addOnSuccessListener(snap -> {
                    if (snap == null) return;
                    com.example.frienbook.core.cloud.WriteBatch batch = Fb.db().batch();
                    for (DocumentSnapshot d : snap) batch.update(d.getReference(), "isRead", true);
                    batch.commit();
                });
    }

    @Override
    public void onStart() {
        super.onStart();
        String me = Fb.uid();
        if (me == null) return;
        reg = Fb.db().collection("notifications").whereEqualTo("toUserId", me)
                .addSnapshotListener((snap, err) -> {
                    android.app.Activity act = getActivity();
                    data.clear();
                    if (snap != null && act != null) {
                        for (DocumentSnapshot d : snap) {
                            Models.Notif n = Models.Notif.fromDoc(d);
                            if (!allowedByPrefs(act, n.type)) continue; // Settings > Notifications toggles
                            if (unreadOnly && n.isRead) continue;
                            data.add(n);
                        }
                    }
                    Collections.sort(data, (a, b2) -> Long.compare(b2.createdAt, a.createdAt));
                    if (data.size() > 80) data.subList(80, data.size()).clear();
                    adapter.notifyDataSetChanged();
                    empty.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
                });
    }

    private void setUnreadOnly(boolean on) {
        unreadOnly = on;
        android.app.Activity a = getActivity();
        if (a == null) return;
        chipAll.setBackground(Ui.round(a, on ? Ui.inputBg(a) : Ui.BLUE, 10));
        chipAll.setTextColor(on ? Ui.sub(a) : 0xFFFFFFFF);
        chipUnread.setBackground(Ui.round(a, on ? Ui.BLUE : Ui.inputBg(a), 10));
        chipUnread.setTextColor(on ? 0xFFFFFFFF : Ui.sub(a));
        if (reg != null) { reg.remove(); reg = null; }
        onStart(); // re-register listener so the new filter applies
    }

    /** Settings > Notifications switches define what appears here. */
    private boolean allowedByPrefs(android.app.Activity a, String type) {
        if (type == null) return true;
        switch (type) {
            case "like":
            case "reaction": return com.example.frienbook.core.Prefs.notifLikes(a);
            case "comment": return com.example.frienbook.core.Prefs.notifComments(a);
            case "friend_request":
            case "friend_accept": return com.example.frienbook.core.Prefs.notifRequests(a);
            case "message": return com.example.frienbook.core.Prefs.notifMessages(a);
            default: return true; // ticks/payments/referrals/boosts always show
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
    }

    private void open(Models.Notif n) {
        if (!n.isRead) {
            Fb.db().collection("notifications").document(n.id).update("isRead", true);
        }
        android.app.Activity a = getActivity();
        if (a == null) return;
        switch (n.type == null ? "" : n.type) {
            case "friend_request":
                if (a instanceof MainActivity) ((MainActivity) a).select(MainActivity.TAB_FRIENDS);
                break;
            case "friend_accept":
            case "follow":
            case "message":
                Intent p = new Intent(a, ProfileActivity.class);
                p.putExtra("uid", n.fromUserId);
                startActivity(p);
                break;
            case "reel_like":
            case "reel_comment":
                if (a instanceof MainActivity) ((MainActivity) a).select(MainActivity.TAB_REELS);
                break;
            default:
                if (n.postId != null && !n.postId.isEmpty()) {
                    Intent i = new Intent(a, PostDetailActivity.class);
                    i.putExtra("post_id", n.postId);
                    startActivity(i);
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
            default: return "🔔";
        }
    }

    class NotifAdapter extends RecyclerView.Adapter<NotifAdapter.VH> {

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
            Ui.avatar(c, h.av, n.fromUserPhoto);
            h.typeDot.setText(emoji(n.type));
            com.example.frienbook.core.TickCache.attach(h.tick, n.fromUserId);
            SpannableStringBuilder sb = new SpannableStringBuilder();
            int start = sb.length();
            sb.append(n.fromUserName);
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
