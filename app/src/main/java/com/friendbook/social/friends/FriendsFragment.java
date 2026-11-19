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
import com.friendbook.social.d1.documents.DocumentSnapshot;
import com.friendbook.social.d1.documents.FieldValue;
import com.friendbook.social.d1.documents.Query;
import com.friendbook.social.d1.documents.WriteBatch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded, cached friends pages. Requests includes a small inline suggestions page; no whole-list listeners. */
public class FriendsFragment extends Fragment implements UsersAdapter.Handler {

    private static final int SEC_REQUESTS = 0, SEC_SUGGESTIONS = 1, SEC_FRIENDS = 2, SEC_SENT = 3;

    private final TextView[] tabs = new TextView[4];
    private LinearLayout tabRow;
    private TextView empty;
    private UsersAdapter adapter;
    private int section = SEC_REQUESTS;

    private final List<Models.FriendRequest> incoming = new ArrayList<>();
    private final Set<String> dismissedReqIds = new HashSet<>();
    private final Set<String> outgoing = new java.util.LinkedHashSet<>();
    private final List<String> friendIds = new ArrayList<>();
    private final Map<String, Models.User> userCache = new HashMap<>();
    private final List<Models.User> suggestions = new ArrayList<>();
    private com.friendbook.social.d1.documents.ListenerRegistration requestWatch;
    private String requestWatchKey = "";

    private void stopRequestWatch() {
        if (requestWatch != null) requestWatch.remove();
        requestWatch = null;
        requestWatchKey = "";
    }

    private void watchRequestPage(Query query, String actor, int selected) {
        if (selected != SEC_REQUESTS && selected != SEC_SENT) { stopRequestWatch(); return; }
        String key = actor + ":" + selected;
        stopRequestWatch();
        requestWatchKey = key;
        final int token=generation;
        requestWatch = query.limit(20).addSnapshotListener((snapshot, error) -> {
            if (!current(token,actor) || section!=selected || !key.equals(requestWatchKey) || activePager==null) return;
            if (error!=null) activePager.observedError();
            else activePager.acceptObservedFirstPage(snapshot);
        });
    }

    private com.friendbook.social.core.CursorPager activePager;
    private com.friendbook.social.core.CursorPager suggestionPager;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipe;
    private boolean pageMore,pageLoading,suggestionMore,suggestionLoading;
    private String pageError="",suggestionError="";
    private int generation;
    private int requestBoundary=-1,suggestionHeader=-1;
    private boolean started;
    private String pageActor;
    private final Set<Integer> loadedSections=new HashSet<>();
    private final Set<String> checkingUsers=new HashSet<>(),loadingProfiles=new HashSet<>(),checkedCandidates=new HashSet<>(),candidateFriends=new HashSet<>(),candidateIncoming=new HashSet<>(),candidateOutgoing=new HashSet<>(),failedCandidates=new HashSet<>();
    private final Map<String,String> sentIds=new HashMap<>();
    private final Set<String> cancelledRequestIds=new HashSet<>(),removedFriendIds=new HashSet<>(),requestWrites=new HashSet<>();
    private final android.os.Handler frameHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean renderQueued;
    private final Runnable renderFrame=()->{renderQueued=false;render();};
    private void requestRender(){if(!renderQueued){renderQueued=true;frameHandler.postDelayed(renderFrame,16);}}
    private boolean current(int token,String actor){return token==generation && java.util.Objects.equals(actor,Fb.uid()) && getActivity()!=null && !isHidden();}
    private void closePage(){generation++;if(activePager!=null){activePager.close();activePager=null;}if(suggestionPager!=null){suggestionPager.close();suggestionPager=null;}checkingUsers.clear();loadingProfiles.clear();frameHandler.removeCallbacks(renderFrame);renderQueued=false;}


    @Nullable
    @Override
    public View onCreateView(LayoutInflater inf, ViewGroup container, Bundle b) {
        android.app.Activity a = getActivity();
        LinearLayout root = Ui.col(a);
        root.setBackgroundColor(Ui.bg(a));

        TextView title = Ui.tvBold(a, "Friends", 22, Ui.txt(a));
        Ui.pad(title, 14, 12, 14, 4);
        root.addView(title);

        String[] labels = {"Requests", "Suggestions", "All friends", "Sent"};
        tabRow = Ui.row(a);
        Ui.pad(tabRow, 12, 4, 12, 8);
        for (int i = 0; i < tabs.length; i++) {
            final int sec = i;
            TextView t = Ui.tvBold(a, labels[i], 12, Ui.sub(a));
            t.setGravity(Gravity.CENTER);
            t.setBackground(Ui.round(a, Ui.inputBg(a), 18));
            Ui.pad(t, 4, 8, 4, 8);
            t.setMaxLines(2);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,-2,1);
            if (i > 0) p.leftMargin = Ui.dp(a, 4);
            tabRow.addView(t, p);
            tabs[i] = t;
            t.setOnClickListener(v -> {
                section = sec;
                if (sec == SEC_REQUESTS) {
                    com.friendbook.social.main.MainActivity.clearFriendsBadgeNow();
                }
                refreshTabs();
                startSection();
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
        com.friendbook.social.core.NearEndPaging.attach(rv,()->{
            if(section==SEC_REQUESTS){if(suggestionPager!=null)suggestionPager.next();}
            else if(activePager!=null)activePager.next();
        });
        rv.addOnScrollListener(new RecyclerView.OnScrollListener(){
            @Override public void onScrolled(RecyclerView list,int dx,int dy){
                if(dy<=0 || section!=SEC_REQUESTS || !pageMore || pageLoading || activePager==null)return;
                LinearLayoutManager layout=(LinearLayoutManager)list.getLayoutManager();int last=layout.findLastVisibleItemPosition();
                // Prefetch requests only BEFORE suggestions enter view; never insert above someone reading suggestions.
                if(last>=requestBoundary-2 && last<suggestionHeader)activePager.next();
            }
        });
        swipe=new androidx.swiperefreshlayout.widget.SwipeRefreshLayout(a);swipe.setColorSchemeColors(Ui.BLUE);
        swipe.addView(rv);swipe.setOnRefreshListener(this::startSection);
        root.addView(swipe, new LinearLayout.LayoutParams(-1,0,1));
        refreshTabs();
        return root;
    }

    private void refreshTabs() {
        for (int i = 0; i < tabs.length; i++) {
            boolean on = i == section;
            tabs[i].setBackground(Ui.round(getActivity(), on ? Ui.BLUE : Ui.inputBg(getActivity()), 18));
            tabs[i].setTextColor(on ? 0xFFFFFFFF : Ui.sub(getActivity()));
        }
    }

    @Override public void onHiddenChanged(boolean hidden){
        super.onHiddenChanged(hidden);
        if(hidden){ stopRequestWatch(); closePage(); }else if(started && adapter!=null){com.friendbook.social.main.MainActivity.clearFriendsBadgeNow();startSection();}
    }
    @Override public void onStart(){super.onStart();started=true;if(!isHidden()){com.friendbook.social.main.MainActivity.clearFriendsBadgeNow();startSection();}}
    @Override public void onStop(){started=false;stopRequestWatch();closePage();super.onStop();}

    private void startSection(){
        String me=Fb.uid();if(me==null || !started || getActivity()==null || isHidden()){if(swipe!=null)swipe.setRefreshing(false);return;}
        if(!java.util.Objects.equals(pageActor,me)){incoming.clear();outgoing.clear();friendIds.clear();suggestions.clear();userCache.clear();dismissedReqIds.clear();sentIds.clear();cancelledRequestIds.clear();removedFriendIds.clear();requestWrites.clear();loadedSections.clear();}
        // Relationship checks and profile details belong to the current actor and
        // must be revalidated on each visit, not just on manual pull-to-refresh.
        closePage();
        checkedCandidates.clear();candidateFriends.clear();candidateIncoming.clear();candidateOutgoing.clear();failedCandidates.clear();userCache.clear();
        pageActor=me;final int token=generation, selected=section;
        pageMore=false;pageLoading=true;pageError="";suggestionMore=false;suggestionLoading=false;suggestionError="";
        Query query;
        if(selected==SEC_REQUESTS)query=Fb.db().collection("friend_requests").whereEqualTo("toUid",me).whereEqualTo("status","pending");
        else if(selected==SEC_SENT)query=Fb.db().collection("friend_requests").whereEqualTo("fromUid",me).whereEqualTo("status","pending");
        else if(selected==SEC_FRIENDS)query=Fb.db().collection("friends").whereArrayContains("participants",me);
        else query=Fb.db().collection("users").orderBy("createdAt",Query.Direction.DESCENDING);
        activePager=new com.friendbook.social.core.CursorPager(query,selected==SEC_SUGGESTIONS?10:20,"friends-section:"+selected,(docs,more,loading,error)->{
            if(!current(token,me) || section!=selected)return;
            pageMore=more;pageLoading=loading;pageError=error;
            if(loading){requestRender();return;}
            loadedSections.add(selected);
            if(swipe!=null)swipe.setRefreshing(false);
            if(selected==SEC_REQUESTS){incoming.clear();for(DocumentSnapshot doc:docs){Models.FriendRequest req=Models.FriendRequest.fromDoc(doc);
                if("pending".equals(req.status) && !dismissedReqIds.contains(req.id)){incoming.add(req);if(req.fromName.isEmpty())ensureUser(req.fromUid);}}
                tabs[SEC_REQUESTS].setText("Requests");
            }else if(selected==SEC_SENT){outgoing.clear();sentIds.clear();for(DocumentSnapshot doc:docs){if(cancelledRequestIds.contains(doc.getId()))continue;String other=doc.getString("toUid");if(other!=null){outgoing.add(other);sentIds.put(other,doc.getId());ensureUser(other);}}}
            else if(selected==SEC_FRIENDS){friendIds.clear();for(DocumentSnapshot doc:docs)for(String other:Models.ChatRoom.fromDoc(doc).participants)
                if(!me.equals(other) && !removedFriendIds.contains(other) && !friendIds.contains(other)){friendIds.add(other);ensureUser(other);}}
            else consumeSuggestions(docs,me,token);
            render();
        });render();
        if(selected==SEC_REQUESTS || selected==SEC_SENT){
            activePager.startObserved();
            watchRequestPage(query,me,selected);
        }else{stopRequestWatch();activePager.start();}
    }

    private void loadInlineSuggestions(){
        if(section!=SEC_REQUESTS || !started || isHidden() || suggestionPager!=null || pageLoading)return;
        String me=Fb.uid(); if(me==null)return;
        int token=generation;
        suggestionPager=new com.friendbook.social.core.CursorPager(Fb.db().collection("users").orderBy("createdAt",Query.Direction.DESCENDING),10,"friends-section:"+SEC_SUGGESTIONS,(docs,more,loading,error)->{
            if(!current(token,me) || section!=SEC_REQUESTS)return;
            suggestionMore=more;suggestionLoading=loading;suggestionError=error;
            if(loading){requestRender();return;}consumeSuggestions(docs,me,token);render();
        });suggestionPager.start();
    }

    private void consumeSuggestions(List<DocumentSnapshot> docs,String me,int token){
        suggestions.clear();for(DocumentSnapshot doc:docs){Models.User user=Models.User.fromDoc(doc);
            if(me.equals(user.uid) || user.deactivated || "Only me".equalsIgnoreCase(user.lookupPrivacy) || (Fb.me!=null && Fb.me.blocked.contains(user.uid)))continue;
            suggestions.add(user);userCache.put(user.uid,user);com.friendbook.social.core.FastCache.putUser(user);
        }checkCandidates(me,token);
    }
    private UsersAdapter.Row control(String title,boolean loading,Runnable action){
        UsersAdapter.Row row=new UsersAdapter.Row();row.heading=title;row.loading=loading;row.action=action;return row;
    }
    private void addSuggestions(List<UsersAdapter.Row> rows){
        for(Models.User u:suggestions){
            if(candidateFriends.contains(u.uid) || candidateIncoming.contains(u.uid) || candidateOutgoing.contains(u.uid))continue;
            boolean incomingRequest=false;for(Models.FriendRequest request:incoming)if(request.fromUid.equals(u.uid)){incomingRequest=true;break;}
            if(incomingRequest)continue;
            UsersAdapter.Row row=new UsersAdapter.Row();row.user=u;
            row.subtitle=!checkedCandidates.contains(u.uid)?(failedCandidates.contains(u.uid)?"Pull down to retry connection check.":"Checking connection…"):(u.bio!=null && !u.bio.isEmpty()?u.bio:"New on friendbook");
            row.primaryLabel=com.friendbook.social.core.FriendCandidatePages.canOfferRequest(checkedCandidates.contains(u.uid),candidateFriends.contains(u.uid),candidateIncoming.contains(u.uid),candidateOutgoing.contains(u.uid)) && !requestWrites.contains(u.uid)?"＋ Add friend":null;
            if(requestWrites.contains(u.uid))row.subtitle="Sending request…";rows.add(row);
        }
    }

    /** Resolve only NEW visible candidates, in <=10-user batches, not a scan of all friendships. */
    private void checkCandidates(String me,int token){
        List<String> unchecked=new ArrayList<>();for(Models.User u:suggestions)if(!checkedCandidates.contains(u.uid) && !checkingUsers.contains(u.uid) && !failedCandidates.contains(u.uid))unchecked.add(u.uid);
        for(int offset=0;offset<unchecked.size();offset+=10){
            final List<String> ids=new ArrayList<>(unchecked.subList(offset,Math.min(offset+10,unchecked.size())));checkingUsers.addAll(ids);
            frameHandler.postDelayed(()->{
                if(current(token,me) && checkingUsers.containsAll(ids)){
                    checkingUsers.removeAll(ids);failedCandidates.addAll(ids);requestRender();
                }
            },20000L);
            List<List<String>> pairs=com.friendbook.social.core.FriendCandidatePages.pairs(me,ids);
            com.google.android.gms.tasks.Task<com.friendbook.social.d1.documents.QuerySnapshot> friends=Fb.db().collection("friends").whereIn("participants",pairs).limit(20).get();
            com.google.android.gms.tasks.Task<com.friendbook.social.d1.documents.QuerySnapshot> received=Fb.db().collection("friend_requests").whereEqualTo("toUid",me).whereIn("fromUid",ids).whereEqualTo("status","pending").limit(20).get();
            com.google.android.gms.tasks.Task<com.friendbook.social.d1.documents.QuerySnapshot> sent=Fb.db().collection("friend_requests").whereEqualTo("fromUid",me).whereIn("toUid",ids).whereEqualTo("status","pending").limit(20).get();
            com.google.android.gms.tasks.Tasks.whenAllSuccess(friends,received,sent).addOnSuccessListener(results->{
                if(!current(token,me) || !checkingUsers.containsAll(ids))return;checkingUsers.removeAll(ids);
                com.friendbook.social.d1.documents.QuerySnapshot a=(com.friendbook.social.d1.documents.QuerySnapshot)results.get(0),b=(com.friendbook.social.d1.documents.QuerySnapshot)results.get(1),c=(com.friendbook.social.d1.documents.QuerySnapshot)results.get(2);
                // Saturated legacy duplicate data is not evidence that other candidates have no relationship.
                if(a.getMetadata().isFromCache() || b.getMetadata().isFromCache() || c.getMetadata().isFromCache() || a.size()==20 || b.size()==20 || c.size()==20){failedCandidates.addAll(ids);requestRender();return;}
                for(DocumentSnapshot doc:a)for(String other:Models.ChatRoom.fromDoc(doc).participants)if(!other.equals(me))candidateFriends.add(other);
                for(DocumentSnapshot doc:b)candidateIncoming.add(doc.getString("fromUid"));for(DocumentSnapshot doc:c)candidateOutgoing.add(doc.getString("toUid"));
                checkedCandidates.addAll(ids);requestRender();
            }).addOnFailureListener(error->{if(current(token,me) && checkingUsers.containsAll(ids)){checkingUsers.removeAll(ids);failedCandidates.addAll(ids);requestRender();}});
        }
    }

    private void ensureUser(String uid){
        if(uid==null || userCache.containsKey(uid) || loadingProfiles.contains(uid))return;
        Models.User cached=com.friendbook.social.core.FastCache.getUser(uid);if(cached!=null)userCache.put(uid,cached);
        final int token=generation;final String actor=Fb.uid();loadingProfiles.add(uid);
        Fb.getUserFresh(uid).addOnSuccessListener(user->{if(!current(token,actor))return;loadingProfiles.remove(uid);
            if(user!=null){userCache.put(uid,user);com.friendbook.social.core.FastCache.putUser(user);requestRender();}
        }).addOnFailureListener(error->{if(current(token,actor))loadingProfiles.remove(uid);});
    }

    private void render() {
        if (getActivity() == null) return;
        List<UsersAdapter.Row> rows = new ArrayList<>();
        String me = Fb.uid();
        if (section == SEC_REQUESTS) {
            empty.setText("No pending requests.\nWhen someone sends you a friend request, it'll show here.");
            rows.add(control("Friend requests",false,null));
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
            requestBoundary=rows.size();
            if(pageLoading || pageMore || !pageError.isEmpty())rows.add(control(!pageError.isEmpty()?"Tap to retry requests":pageLoading?"":"See more requests",pageLoading,()->{if(activePager!=null)activePager.next();}));
            suggestionHeader=rows.size();
            if(suggestionPager==null){
                if(!pageLoading) rows.add(control("People you may know · Tap to load",false,this::loadInlineSuggestions));
            }else rows.add(control("People you may know",false,null));
            int beforeSuggestions=rows.size();if(suggestionPager!=null)addSuggestions(rows);
            if(suggestionLoading || suggestionMore || !suggestionError.isEmpty())rows.add(control(!suggestionError.isEmpty()?"Tap to retry suggestions":!suggestionLoading && rows.size()==beforeSuggestions?"See more people":"",suggestionLoading,()->{if(suggestionPager!=null)suggestionPager.next();}));
        } else if (section == SEC_SUGGESTIONS) {
            empty.setText("No more suggestions right now.");addSuggestions(rows);
        } else if(section==SEC_SENT){
            empty.setText("No pending sent requests.");
            for (String uid : outgoing) {
                Models.User u = userCache.get(uid);
                if(u==null){u=new Models.User();u.uid=uid;u.displayName="Loading…";}
                UsersAdapter.Row row = new UsersAdapter.Row();
                row.user = u;
                row.requestId=sentIds.get(uid);
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
                        : "Friend";
                row.primaryLabel = "💬 Message";
                row.secondaryLabel = "Unfriend";
                rows.add(row);
            }
        }
        if(section!=SEC_REQUESTS && (pageLoading || pageMore || !pageError.isEmpty()))rows.add(control(!pageError.isEmpty()?"Tap to retry":!pageLoading && rows.isEmpty()?"See more people":"",pageLoading,()->{if(activePager!=null)activePager.next();}));
        if(swipe!=null && !pageLoading && !suggestionLoading)swipe.setRefreshing(false);
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
        } else if (r.primaryLabel!=null && r.primaryLabel.contains("Add friend")) {
            sendRequest(r.user);
        } else if (r.primaryLabel!=null && r.primaryLabel.contains("Requested")) {
            cancelRequest(r);
        } else if (r.primaryLabel!=null && r.primaryLabel.contains("Message")) {
            Intent i = new Intent(getActivity(), ChatActivity.class);
            i.putExtra("uid", r.user.uid);
            i.putExtra("name", r.user.displayName);
            startActivity(i);
        }
    }

    private void removeRequestOptimistic(String reqId) {
        if (reqId == null) return;
        dismissedReqIds.add(reqId);
        if(section==SEC_REQUESTS && activePager!=null)activePager.forget(reqId);
        incoming.removeIf(req -> reqId.equals(req.id));
        if (tabs[SEC_REQUESTS] != null) {
            tabs[SEC_REQUESTS].setText("Requests");
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
            final String actor=Fb.uid();
            Fb.db().collection("friend_requests").document(rid).delete().addOnFailureListener(error->{
                if(java.util.Objects.equals(actor,Fb.uid()) && getActivity()!=null){dismissedReqIds.remove(rid);Ui.toast(getActivity(),"Couldn't remove request. Try again.");startSection();}
            });
            Ui.toast(getActivity(), "Request removed");
        } else if ("Unfriend".equals(r.secondaryLabel)) {
            unfriend(r.user);
        }
    }

    private void sendRequest(Models.User target) {
        String me=Fb.uid();if(me==null || !requestWrites.add(target.uid))return;render();
        // D1 already checks duplicates in the same transaction as the insert.
        Fb.db().collection("friend_requests").add(Fb.map("fromUid",me,"toUid",target.uid,"fromUserName",Fb.myName(),"fromUserPhotoUrl",Fb.myPhoto(),"status","pending","createdAt",FieldValue.serverTimestamp()))
            .addOnSuccessListener(ref->{if(!me.equals(Fb.uid()) || getActivity()==null)return;
                requestWrites.remove(target.uid);candidateOutgoing.add(target.uid);render();
                Notify.send(target.uid,"friend_request","sent you a friend request");Ui.toast(getActivity(),"Friend request sent ✓");
            }).addOnFailureListener(error->{
                if(!me.equals(Fb.uid()) || getActivity()==null)return;
                requestWrites.remove(target.uid);
                boolean duplicate=error instanceof com.friendbook.social.d1.documents.D1DocumentStoreException
                    && ((com.friendbook.social.d1.documents.D1DocumentStoreException)error).getCode()==com.friendbook.social.d1.documents.D1DocumentStoreException.Code.ALREADY_EXISTS;
                if(duplicate) candidateOutgoing.add(target.uid);
                render();Ui.toast(getActivity(),duplicate?"Request already sent":"Couldn't send. Try again.");
            });
    }

    private void cancelRequest(UsersAdapter.Row row) {
        String me=Fb.uid();if(me==null || row.requestId==null)return;
        final String requestId=row.requestId,other=row.user.uid;
        Fb.db().collection("friend_requests").document(requestId).delete().addOnSuccessListener(v->{
            if(!me.equals(Fb.uid()) || getActivity()==null)return;
            cancelledRequestIds.add(requestId);if(section==SEC_SENT && activePager!=null)activePager.forget(requestId);outgoing.remove(other);sentIds.remove(other);candidateOutgoing.remove(other);render();
            Ui.toast(getActivity(),"Request cancelled");
        }).addOnFailureListener(error->{if(me.equals(Fb.uid()) && getActivity()!=null)Ui.toast(getActivity(),"Couldn't cancel. Try again.");});
    }

    private void acceptRequest(UsersAdapter.Row r) {
        String me = Fb.uid();
        if (me == null || r.requestId == null) return;
        String rid = r.requestId;
        String from = r.user.uid;
        // ⚡ 0.0ms Optimistic UI removal + badge reset to 0!
        removeRequestOptimistic(rid);
        removedFriendIds.remove(from);candidateFriends.add(from);
        if (!friendIds.contains(from)) friendIds.add(from);

        WriteBatch batch = Fb.db().batch();
        batch.update(Fb.db().collection("friend_requests").document(rid),
                "status", "accepted");
        com.friendbook.social.d1.documents.DocumentReference friendDocRef = Fb.db().collection("friends").document();
        Map<String, Object> friendDoc = Fb.map(
                "participants", java.util.Arrays.asList(me, from),
                "createdAt", FieldValue.serverTimestamp());
        // The server authorizes and commits the relationship and counters together.
        batch.set(friendDocRef, friendDoc);
        batch.update(Fb.db().collection("users").document(me), "friendsCount", FieldValue.increment(1));
        batch.update(Fb.db().collection("users").document(from), "friendsCount", FieldValue.increment(1));
        batch.commit().addOnSuccessListener(x -> {
            if(!me.equals(Fb.uid()) || getActivity()==null)return;
            // The server atomically resolves historical duplicates from the same sender.
            for(Models.FriendRequest duplicate:new ArrayList<>(incoming))if(from.equals(duplicate.fromUid))removeRequestOptimistic(duplicate.id);
            Notify.send(from, "friend_accept", "accepted your friend request");
            Ui.toast(getActivity(), "You're now friends with " + r.user.displayName + " 🎉");
            startSection();
        }).addOnFailureListener(error->{if(me.equals(Fb.uid()) && getActivity()!=null){dismissedReqIds.remove(rid);friendIds.remove(from);candidateFriends.remove(from);Ui.toast(getActivity(),"Couldn't confirm. Try again.");startSection();}});
    }

    private void unfriend(Models.User user) {
        Ui.confirm(getActivity(),"Unfriend "+user.displayName+"?","You'll have to send a new request to reconnect.","Unfriend",()->{
            String me=Fb.uid();if(me==null)return;
            Fb.db().collection("friends").whereIn("participants",java.util.Arrays.asList(java.util.Arrays.asList(me,user.uid),java.util.Arrays.asList(user.uid,me))).limit(20).get().addOnSuccessListener(page->{
                if(!me.equals(Fb.uid()) || getActivity()==null)return;
                if(page.getMetadata().isFromCache() || page.size()==20){Ui.toast(getActivity(),"Couldn't verify all relationship records. Nothing changed; retry online.");return;}
                if(page.isEmpty()){removedFriendIds.add(user.uid);friendIds.remove(user.uid);render();return;}
                WriteBatch batch=Fb.db().batch();for(DocumentSnapshot doc:page)batch.delete(doc.getReference());
                batch.update(Fb.db().collection("users").document(me),"friendsCount",FieldValue.increment(-page.size()));
                batch.update(Fb.db().collection("users").document(user.uid),"friendsCount",FieldValue.increment(-page.size()));
                batch.commit().addOnSuccessListener(v->{if(!me.equals(Fb.uid()) || getActivity()==null)return;
                    removedFriendIds.add(user.uid);friendIds.remove(user.uid);candidateFriends.remove(user.uid);render();Ui.toast(getActivity(),"Unfriended");
                }).addOnFailureListener(error->{if(me.equals(Fb.uid()) && getActivity()!=null)Ui.toast(getActivity(),"Couldn't unfriend. Try again.");});
            }).addOnFailureListener(error->{if(me.equals(Fb.uid()) && getActivity()!=null)Ui.toast(getActivity(),"Couldn't check relationship. Try again.");});
        });
    }
}
