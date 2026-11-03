package com.friendbook.social.friends;

import android.app.Fragment;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.social.chat.ChatActivity;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Notify;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.WriteBatch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Friends tab: friend requests, suggestions, your friends — all realtime. */
public class FriendsFragment extends Fragment implements UsersAdapter.Handler {

    private static final int SEC_REQUESTS = 0, SEC_SUGGESTIONS = 1, SEC_FRIENDS = 2;

    private final TextView[] tabs = new TextView[3];
    private LinearLayout tabRow;
    private TextView empty;
    private UsersAdapter adapter;
    private int section = SEC_REQUESTS;

    private final List<Models.FriendRequest> incoming = new ArrayList<>();
    private final Set<String> dismissedReqIds = new HashSet<>();
    private final Set<String> outgoing = new HashSet<>();
    private LinearLayout pageControls;
    private final List<String> friendIds = new ArrayList<>();
    private final Map<String, Models.User> userCache = new HashMap<>();
    private final List<Models.User> suggestions = new ArrayList<>();
    private ListenerRegistration inReg, outReg, friendsReg, usersReg;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inf, ViewGroup container, Bundle b) {
        android.app.Activity a = getActivity();
        LinearLayout root = Ui.col(a);
        root.setBackgroundColor(Ui.bg(a));

        TextView title = Ui.tvBold(a, "Friends", 22, Ui.txt(a));
        Ui.pad(title, 14, 12, 14, 4);
        root.addView(title);

        String[] labels = {"Requests", "Suggestions", "All friends"};
        tabRow = Ui.row(a);
        Ui.pad(tabRow, 12, 4, 12, 8);
        for (int i = 0; i < 3; i++) {
            final int sec = i;
            TextView t = Ui.tvBold(a, labels[i], 14, Ui.sub(a));
            t.setGravity(Gravity.CENTER);
            t.setBackground(Ui.round(a, Ui.inputBg(a), 18));
            Ui.pad(t, 10, 8, 10, 8);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) p.leftMargin = Ui.dp(a, 8);
            tabRow.addView(t, p);
            tabs[i] = t;
            t.setOnClickListener(v -> {
                section = sec;
                if (sec == SEC_REQUESTS) {
                    com.friendbook.social.main.MainActivity.clearFriendsBadgeNow();
                }
                refreshTabs();
                render();
            });
        }
        root.addView(tabRow);

        empty = Ui.tv(a, "", 14, Ui.sub(a));
        empty.setGravity(Gravity.CENTER);
        Ui.pad(empty, 24, 40, 24, 0);
        root.addView(empty);

        RecyclerView rv = new RecyclerView(a);
        rv.setLayoutManager(new LinearLayoutManager(a));
        rv.setItemAnimator(null);
        rv.setItemViewCacheSize(20);
        adapter = new UsersAdapter(a, this);
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));
        pageControls=Ui.col(a);root.addView(pageControls);
        refreshTabs();
        return root;
    }

    private void refreshTabs() {
        for (int i = 0; i < 3; i++) {
            boolean on = i == section;
            tabs[i].setBackground(Ui.round(getActivity(), on ? Ui.BLUE : Ui.inputBg(getActivity()), 18));
            tabs[i].setTextColor(on ? 0xFFFFFFFF : Ui.sub(getActivity()));
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            com.friendbook.social.main.MainActivity.clearFriendsBadgeNow();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if (!isHidden()) {
            com.friendbook.social.main.MainActivity.clearFriendsBadgeNow();
        }
        String me = Fb.uid();
        if (me == null) return;

        inReg=com.friendbook.social.core.PagedLists.attach(getActivity(),pageControls,Fb.db().collection("friend_requests").whereEqualTo("toUid", me),20,"Incoming requests",(snap,err)-> {
                    incoming.clear();
                    List<String> allIds = new ArrayList<>();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap) {
                            Models.FriendRequest r = Models.FriendRequest.fromDoc(d);
                            if ("pending".equals(r.status) && !dismissedReqIds.contains(r.id)) {
                                incoming.add(r);
                                allIds.add(r.id);
                            }
                        }
                    }
                    incoming.sort((a, b2) -> Long.compare(b2.createdAt, a.createdAt));
                    tabs[SEC_REQUESTS].setText(incoming.isEmpty() ? "Requests"
                            : "Requests (" + incoming.size() + ")");
                    // resolve sender profiles
                    for (Models.FriendRequest r : incoming) ensureUser(r.fromUid);
                    render();
                });

        outReg=com.friendbook.social.core.PagedLists.attach(getActivity(),pageControls,Fb.db().collection("friend_requests").whereEqualTo("fromUid", me),20,"Sent requests",(snap,err)-> {
                    outgoing.clear();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap) {
                            if ("pending".equals(d.getString("status")))
                                outgoing.add(d.getString("toUid"));
                        }
                    }
                    render();
                });

        friendsReg=com.friendbook.social.core.PagedLists.attach(getActivity(),pageControls,Fb.db().collection("friends").whereArrayContains("participants", me),20,"Friends",(snap,err)-> {
                    friendIds.clear();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap) {
                            for (String p : Models.ChatRoom.fromDoc(d).participants)
                                if (!p.equals(me)) friendIds.add(p);
                        }
                    }
                    for (String uid : friendIds) ensureUser(uid);
                    render();
                });

        usersReg=com.friendbook.social.core.PagedLists.attach(getActivity(),pageControls,
                Fb.db().collection("users").orderBy("createdAt",Query.Direction.DESCENDING),20,"People",(snap,err)-> {
                    suggestions.clear();
                    String me2 = Fb.uid();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap) {
                            Models.User u = Models.User.fromDoc(d);
                            if (u.uid.equals(me2)) continue;
                            if (friendIds.contains(u.uid)) continue;
                            if (Fb.me != null && Fb.me.blocked.contains(u.uid)) continue;
                            if (u.followers != null && u.followers.contains(me2)) { /* still suggest */ }
                            suggestions.add(u);
                            userCache.put(u.uid, u);
                        }
                    }
                    render();
                });
    }

    @Override
    public void onStop() {
        super.onStop();
        if (inReg != null) inReg.remove();
        if (outReg != null) outReg.remove();
        if (friendsReg != null) friendsReg.remove();
        if (usersReg != null) usersReg.remove();
    }

    private void ensureUser(String uid) {
        if (uid == null || userCache.containsKey(uid)) return;
        Fb.getUser(uid).addOnSuccessListener(u -> {
            if (u != null) { userCache.put(uid, u); render(); }
        });
    }

    private void render() {
        if (getActivity() == null) return;
        List<UsersAdapter.Row> rows = new ArrayList<>();
        String me = Fb.uid();
        if (section == SEC_REQUESTS) {
            empty.setText("No pending requests.\nWhen someone sends you a friend request, it'll show here.");
            for (Models.FriendRequest r : incoming) {
                Models.User u = userCache.get(r.fromUid);
                if (u == null) u = com.friendbook.social.core.FastCache.getUser(r.fromUid);
                if (u == null) {
                    u = new Models.User();
                    u.uid = r.fromUid;
                    u.displayName = r.fromName.isEmpty() ? "friendbook user" : r.fromName;
                    u.photoUrl = r.fromPhoto;
                }
                UsersAdapter.Row row = new UsersAdapter.Row();
                row.user = u;
                row.requestId = r.id;
                row.subtitle = "Sent you a friend request";
                row.primaryLabel = "Confirm";
                row.secondaryLabel = "Delete";
                rows.add(row);
            }
        } else if (section == SEC_SUGGESTIONS) {
            empty.setText("No more suggestions right now.");
            for (Models.User u : suggestions) {
                if (outgoing.contains(u.uid)) continue;
                boolean requestedByThem = false;
                for (Models.FriendRequest r : incoming) if (r.fromUid.equals(u.uid)) { requestedByThem = true; break; }
                if (requestedByThem) continue;
                UsersAdapter.Row row = new UsersAdapter.Row();
                row.user = u;
                row.subtitle = u.bio != null && !u.bio.isEmpty() ? u.bio : "New on friendbook";
                row.primaryLabel = "＋ Add friend";
                rows.add(row);
            }
            // show outgoing requests as "Requested"
            for (String uid : outgoing) {
                Models.User u = userCache.get(uid);
                if (u == null || friendIds.contains(uid)) continue;
                UsersAdapter.Row row = new UsersAdapter.Row();
                row.user = u;
                row.subtitle = "Request sent";
                row.primaryLabel = "✓ Requested";
                rows.add(row);
            }
        } else {
            empty.setText("No friends yet — add people from Suggestions!");
            for (String uid : friendIds) {
                Models.User u = userCache.get(uid);
                if (u == null) continue;
                UsersAdapter.Row row = new UsersAdapter.Row();
                row.user = u;
                row.subtitle = u.bio != null && !u.bio.isEmpty() ? u.bio
                        : (u.isOnline ? "Active now" : "Friend");
                row.primaryLabel = "💬 Message";
                row.secondaryLabel = "Unfriend";
                rows.add(row);
            }
        }
        adapter.setData(rows);
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ---------- actions ----------
    @Override
    public void primary(UsersAdapter.Row r) {
        if (getActivity() == null || r.user == null) return;
        String me = Fb.uid();
        if (me == null) return;
        if ("Confirm".equals(r.primaryLabel)) {
            acceptRequest(r);
        } else if (r.primaryLabel.contains("Add friend")) {
            sendRequest(r.user);
        } else if (r.primaryLabel.contains("Requested")) {
            cancelRequest(r.user);
        } else if (r.primaryLabel.contains("Message")) {
            Intent i = new Intent(getActivity(), ChatActivity.class);
            i.putExtra("uid", r.user.uid);
            i.putExtra("name", r.user.displayName);
            startActivity(i);
        }
    }

    private void removeRequestOptimistic(String reqId) {
        if (reqId == null) return;
        dismissedReqIds.add(reqId);
        incoming.removeIf(req -> reqId.equals(req.id));
        if (tabs[SEC_REQUESTS] != null) {
            tabs[SEC_REQUESTS].setText(incoming.isEmpty() ? "Requests"
                    : "Requests (" + incoming.size() + ")");
        }
        com.friendbook.social.main.MainActivity.clearFriendsBadgeNow();
        render();
    }

    @Override
    public void secondary(UsersAdapter.Row r) {
        if (getActivity() == null || r.user == null) return;
        if ("Delete".equals(r.secondaryLabel) && r.requestId != null) {
            String rid = r.requestId;
            removeRequestOptimistic(rid);
            Fb.db().collection("friend_requests").document(rid).delete();
            Ui.toast(getActivity(), "Request removed");
        } else if ("Unfriend".equals(r.secondaryLabel)) {
            unfriend(r.user);
        }
    }

    private void sendRequest(Models.User target) {
        String me = Fb.uid();
        if (me == null) return;
        // guard duplicates
        Fb.db().collection("friend_requests")
                .whereEqualTo("fromUid", me).whereEqualTo("toUid", target.uid)
                .limit(1).get().addOnSuccessListener(snap -> {
                    if (snap != null && !snap.isEmpty()) {
                        Ui.toast(getActivity(), "Request already sent");
                        return;
                    }
                    Map<String, Object> m = Fb.map(
                            "fromUid", me,
                            "toUid", target.uid,
                            "fromUserName", Fb.myName(),
                            "fromUserPhotoUrl", Fb.myPhoto(),
                            "status", "pending",
                            "createdAt", FieldValue.serverTimestamp());
                    Fb.db().collection("friend_requests").add(m)
                            .addOnSuccessListener(ref -> {
                                Notify.send(target.uid, "friend_request", "sent you a friend request");
                                Ui.toast(getActivity(), "Friend request sent ✓");
                            });
                });
    }

    private void cancelRequest(Models.User target) {
        String me = Fb.uid();
        if (me == null) return;
        com.friendbook.social.core.QueryActions.deleteMatching(Fb.db().collection("friend_requests")
                .whereEqualTo("fromUid",me).whereEqualTo("toUid",target.uid))
                .addOnSuccessListener(v->Ui.toast(getActivity(),"Request cancelled"))
                .addOnFailureListener(e->Ui.toast(getActivity(),"Couldn't cancel. Please retry."));
    }

    private void acceptRequest(UsersAdapter.Row r) {
        String me = Fb.uid();
        if (me == null || r.requestId == null) return;
        String rid = r.requestId;
        String from = r.user.uid;
        // ⚡ 0.0ms Optimistic UI removal + badge reset to 0!
        removeRequestOptimistic(rid);
        if (!friendIds.contains(from)) friendIds.add(from);

        WriteBatch batch = Fb.db().batch();
        batch.update(Fb.db().collection("friend_requests").document(rid),
                "status", "accepted");
        batch.set(Fb.db().collection("friends").document(), Fb.map(
                "participants", java.util.Arrays.asList(me, from),
                "createdAt", FieldValue.serverTimestamp()));
        batch.update(Fb.db().collection("users").document(me), "friendsCount", FieldValue.increment(1));
        batch.update(Fb.db().collection("users").document(from), "friendsCount", FieldValue.increment(1));
        batch.commit().addOnSuccessListener(x -> {
            Notify.send(from, "friend_accept", "accepted your friend request");
            Ui.toast(getActivity(), "You're now friends with " + r.user.displayName + " 🎉");
        });
    }

    private void unfriend(Models.User u) {
        Ui.confirm(getActivity(), "Unfriend " + u.displayName + "?", "You'll have to send a new request to reconnect.",
                "Unfriend", () -> {
                    String me = Fb.uid();
                    if (me == null) return;
                    Fb.db().collection("friends").whereIn("participants",java.util.Arrays.asList(java.util.Arrays.asList(me,u.uid),java.util.Arrays.asList(u.uid,me)))
                            .limit(1).get().addOnSuccessListener(snap -> {
                                if (snap == null) return;
                                for (DocumentSnapshot d : snap) {
                                    List<String> parts = Models.ChatRoom.fromDoc(d).participants;
                                    if (parts.contains(u.uid)) {
                                        d.getReference().delete();
                                        Fb.db().collection("users").document(me)
                                                .update("friendsCount", FieldValue.increment(-1));
                                        Fb.db().collection("users").document(u.uid)
                                                .update("friendsCount", FieldValue.increment(-1));
                                        Ui.toast(getActivity(), "Unfriended");
                                    }
                                }
                            });
                });
    }
}
