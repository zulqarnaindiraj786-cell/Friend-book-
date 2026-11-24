package com.friendbook.social.friends;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.friendbook.social.post.FeedAdapter;
import com.friendbook.social.post.CreatePostActivity;
import com.friendbook.social.post.PostDetailActivity;
import com.friendbook.social.profile.ProfileActivity;
import com.friendbook.social.d1.documents.DocumentSnapshot;
import com.friendbook.social.d1.documents.Query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Global search: people (prefix search) + recent posts (contains). */
public class SearchActivity extends Activity implements UsersAdapter.Handler, FeedAdapter.Host {

    private EditText input;
    private UsersAdapter usersAdapter;
    private FeedAdapter postsAdapter;
    private TextView peopleTitle, postsTitle, noResults;
    private LinearLayout historyBox;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pending;
    private com.friendbook.social.core.CursorPager peopleA,peopleB,postPages;
    private TextView morePeople,morePosts;
    private boolean searchStarted;
    private String searchActor;
    private boolean prepareSearchActor(){
        String actor=Fb.uid();
        if(!java.util.Objects.equals(searchActor,actor)){
            closePages();searchActor=actor;
            if(usersAdapter!=null)usersAdapter.setData(new ArrayList<>());
            if(postsAdapter!=null){postsAdapter.feedEngagementInPage=true;postsAdapter.setPosts(new ArrayList<>());}
            if(peopleTitle!=null)peopleTitle.setVisibility(View.GONE);
            if(postsTitle!=null)postsTitle.setVisibility(View.GONE);
        }
        return actor!=null;
    }
    private void closePages(){
        if(peopleA!=null)peopleA.close();if(peopleB!=null)peopleB.close();if(postPages!=null)postPages.close();
        peopleA=null;peopleB=null;postPages=null;
    }
    private void queueSearch(String q){
        if(pending!=null)handler.removeCallbacks(pending);
        if(!searchStarted || !prepareSearchActor())return;
        final String expectedActor=searchActor;
        pending=()->{pending=null;if(searchStarted && java.util.Objects.equals(expectedActor,Fb.uid()) && !isFinishing() && !isDestroyed())search(q);};
        handler.postDelayed(pending, com.friendbook.social.core.SearchReadPolicy.DEBOUNCE_MS);
    }
    @Override protected void onStart(){
        super.onStart();searchStarted=true;prepareSearchActor();
        if(input!=null && input.length()>0)queueSearch(input.getText().toString().trim());
    }
    @Override protected void onStop(){
        searchStarted=false;if(pending!=null){handler.removeCallbacks(pending);pending=null;}
        closePages();if(postsAdapter!=null)postsAdapter.feedEngagementInPage=true;
        super.onStop();
    }
    @Override protected void onDestroy(){closePages();if(pending!=null)handler.removeCallbacks(pending);super.onDestroy();}

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));

        // crisp white search bar with vector back arrow
        LinearLayout bar = Ui.row(this);
        bar.setBackground(Ui.barBg(this));
        bar.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        android.widget.ImageView back = Ui.backArrow(this, Ui.txt(this), v -> finish());
        bar.addView(back);
        input = Ui.input(this, "🔍 Search people and posts…");
        input.setBackground(Ui.round(this, Ui.inputBg(this), 20));
        input.setTextColor(Ui.txt(this));
        input.setHintTextColor(Ui.sub(this));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1);
        ip.setMargins(Ui.dp(this, 4), 0, 0, 0);
        bar.addView(input, ip);
        root.addView(bar);

        ScrollView sv = new ScrollView(this);
        LinearLayout body = Ui.col(this);

        noResults = Ui.tv(this, "Type to search for people and posts", 14, Ui.sub(this));
        noResults.setGravity(android.view.Gravity.CENTER);
        Ui.pad(noResults, 24, 50, 24, 0);
        body.addView(noResults);

        // 🔍 recent searches (friendbook: chips when the box is empty)
        historyBox = Ui.col(this);
        body.addView(historyBox, 0);
        renderHistory();

        peopleTitle = section("People");
        body.addView(peopleTitle);
        RecyclerView usersRv = new RecyclerView(this);
        LinearLayoutManager peopleLayout=new LinearLayoutManager(this);peopleLayout.setItemPrefetchEnabled(false);
        usersRv.setLayoutManager(peopleLayout);
        usersRv.setNestedScrollingEnabled(false);
        usersAdapter = new UsersAdapter(this, this);
        usersRv.setAdapter(usersAdapter);
        body.addView(usersRv, new LinearLayout.LayoutParams(-1, -2));

        morePeople=Ui.tvBold(this,"More people",14,Ui.BLUE);morePeople.setVisibility(View.GONE);body.addView(morePeople);
        morePeople.setOnClickListener(v->{if(peopleA!=null)peopleA.next();if(peopleB!=null)peopleB.next();});
        postsTitle = section("Posts");
        body.addView(postsTitle);
        RecyclerView postsRv = new RecyclerView(this);
        LinearLayoutManager postsLayout=new LinearLayoutManager(this);postsLayout.setItemPrefetchEnabled(false);
        postsRv.setLayoutManager(postsLayout);
        postsRv.setNestedScrollingEnabled(false);
        postsAdapter = new FeedAdapter(this, this);
        postsAdapter.showHeader = false;
        postsAdapter.showFooter = false;
        // Keystroke previews are local-only; a real result page opts into row demand.
        postsAdapter.feedEngagementInPage = true;
        postsRv.setAdapter(postsAdapter);
        body.addView(postsRv, new LinearLayout.LayoutParams(-1, -2));

        morePosts=Ui.tvBold(this,"More post results",14,Ui.BLUE);morePosts.setVisibility(View.GONE);body.addView(morePosts);
        morePosts.setOnClickListener(v->{if(postPages!=null)postPages.next();});
        sv.addView(body);
        // A scroll in one section must not advance all three independent result
        // streams. The two More controls above are the only next-page demand.
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {
                closePages();
                if (pending != null) {handler.removeCallbacks(pending);pending=null;}
                if(!searchStarted || !prepareSearchActor())return;
                postsAdapter.feedEngagementInPage=true;
                final String q = s.toString().trim();
                // ⚡ 0.0ms immediate local FastCache search on every keystroke!
                if (!q.isEmpty()) {
                    String ql = q.toLowerCase();
                    List<Models.Post> fastPosts = new ArrayList<>();
                    for (Models.Post p : com.friendbook.social.core.FastCache.getFeed()) {
                        if (p == null) continue;
                        String hay = ((p.authorName == null ? "" : p.authorName) + " "
                                + (p.content == null ? "" : p.content)).toLowerCase();
                        if (hay.contains(ql) && fastPosts.size()<10) {
                            com.friendbook.social.post.D1Reactions.hydrate(p);fastPosts.add(p);
                        }
                    }
                    if (!fastPosts.isEmpty()) {
                        historyBox.setVisibility(View.GONE);
                        noResults.setVisibility(View.GONE);
                        postsTitle.setVisibility(View.VISIBLE);
                        postsAdapter.setPosts(fastPosts);
                    }
                }
                queueSearch(q);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
    }

    private TextView section(String t) {
        TextView v = Ui.tvBold(this, t, 15, Ui.sub(this));
        Ui.pad(v, 14, 12, 14, 4);
        v.setVisibility(View.GONE);
        return v;
    }

    /** Recent searches list — visible only when the input is empty. */
    private void renderHistory() {
        if (historyBox == null) return;
        historyBox.removeAllViews();
        java.util.List<String> hist = com.friendbook.social.core.Prefs.searchHistory(this);
        if (hist.isEmpty()) { historyBox.setVisibility(View.GONE); return; }
        historyBox.setVisibility(View.VISIBLE);
        TextView head = Ui.tvBold(this, "Recent", 15, Ui.txt(this));
        Ui.pad(head, 14, 10, 14, 2);
        historyBox.addView(head);
        for (final String q : hist) {
            TextView row = Ui.tv(this, "🕘  " + q, 14, Ui.sub(this));
            Ui.pad(row, 16, 10, 16, 10);
            row.setOnClickListener(v -> { input.setText(q); input.setSelection(q.length()); });
            historyBox.addView(row);
        }
        TextView clear = Ui.tvBold(this, "🧹 Clear recent searches", 13, Ui.BLUE);
        Ui.pad(clear, 16, 8, 16, 12);
        clear.setOnClickListener(v -> {
            com.friendbook.social.core.Prefs.clearSearch(this);
            renderHistory();
        });
        historyBox.addView(clear);
    }

    private void search(String q) {
        if(!searchStarted || !prepareSearchActor() || isFinishing() || isDestroyed())return;
        if (q.isEmpty()) {
            morePeople.setVisibility(View.GONE);morePosts.setVisibility(View.GONE);
            peopleTitle.setVisibility(View.GONE);
            postsTitle.setVisibility(View.GONE);
            usersAdapter.setData(new ArrayList<>());
            postsAdapter.setPosts(new ArrayList<>());
            noResults.setText("Type to search for people and posts");
            renderHistory();
            return;
        }
        morePeople.setVisibility(View.VISIBLE);morePosts.setVisibility(View.VISIBLE);
        if (historyBox != null) historyBox.setVisibility(View.GONE);
        com.friendbook.social.core.Prefs.addSearch(this, q);

        // ---- people: displayName prefix search in both cases ----
        Map<String, Models.User> people = new LinkedHashMap<>();
        Map<String, Models.User> firstSpelling=new LinkedHashMap<>(),secondSpelling=new LinkedHashMap<>();
        Runnable mergePeople=()->{people.clear();people.putAll(firstSpelling);people.putAll(secondSpelling);};
        Runnable finishPeople = () -> {
            List<UsersAdapter.Row> rows = new ArrayList<>();
            for (Models.User u : people.values()) {
                if (u.uid.equals(Fb.uid()) || u.deactivated) continue;
                if ("Only me".equalsIgnoreCase(u.lookupPrivacy)) continue;
                UsersAdapter.Row r = new UsersAdapter.Row();
                r.user = u;
                r.subtitle = u.currentCity != null && !u.currentCity.isEmpty() ? ("📍 " + u.currentCity)
                        : (u.bio != null && !u.bio.isEmpty() ? u.bio : "friendbook member");
                rows.add(r);
            }
            usersAdapter.setData(rows);
            peopleTitle.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
            if (rows.isEmpty()) noResults.setText("No results for \"" + q + "\"");
        };
        String cap = q.isEmpty() ? q : Character.toUpperCase(q.charAt(0)) + q.substring(1);
        boolean same=cap.equals(q);
        peopleA=new com.friendbook.social.core.CursorPager(Fb.db().collection("users").orderBy("displayName",Query.Direction.ASCENDING).startAt(q).endAt(q+"\uf8ff"),10,"search-people:"+q,(docs,more,loading,error)->{
            if(loading)return;
            firstSpelling.clear();for(DocumentSnapshot doc:docs)firstSpelling.put(doc.getId(),Models.User.fromDoc(doc));mergePeople.run();finishPeople.run();
            morePeople.setText(!error.isEmpty()?"Retry people":"More people");
        });
        peopleB=same?null:new com.friendbook.social.core.CursorPager(Fb.db().collection("users").orderBy("displayName",Query.Direction.ASCENDING).startAt(cap).endAt(cap+"\uf8ff"),10,"search-people:"+cap,(docs,more,loading,error)->{
            if(loading)return;
            secondSpelling.clear();for(DocumentSnapshot doc:docs)secondSpelling.put(doc.getId(),Models.User.fromDoc(doc));mergePeople.run();finishPeople.run();
        });
        peopleA.startCachedFor(com.friendbook.social.core.SearchReadPolicy.EXACT_PAGE_REUSE_MS);
        if(peopleB!=null)peopleB.startCachedFor(com.friendbook.social.core.SearchReadPolicy.EXACT_PAGE_REUSE_MS);
        boolean tagSearch=q.matches("[#@][\\p{L}\\p{N}_]{1,80}");
        Query postQuery=Fb.db().collection("posts").orderBy("createdAt",Query.Direction.DESCENDING);
        if(tagSearch)postQuery=postQuery.matchingPostTag(q);
        postPages=new com.friendbook.social.core.CursorPager(postQuery,10,"search-posts:"+q,(docs,more,loading,error)->{
            if(loading){morePosts.setText("Loading…");morePosts.setEnabled(false);return;}
            List<Models.Post> list=new ArrayList<>();String needle=q.toLowerCase(Locale.ROOT);
            for(DocumentSnapshot doc:docs){Models.Post p=Models.Post.fromDoc(doc);
                if(p.archived || p.trashedAt>0 || ("Only me".equalsIgnoreCase(p.privacy) && !p.authorId.equals(Fb.uid())) || (Fb.me!=null && Fb.me.blocked.contains(p.authorId)))continue;
                String hay=(p.content+" "+p.authorName).toLowerCase(Locale.ROOT);if(tagSearch || hay.contains(needle))list.add(p);
            }
            postsAdapter.feedEngagementInPage=false;
            postsAdapter.setPosts(list);postsTitle.setVisibility(list.isEmpty()?View.GONE:View.VISIBLE);
            morePosts.setText(loading?"Loading…":!error.isEmpty()?"Retry posts":more?"More post results":"All scanned");morePosts.setEnabled(!loading && more);
        });
        // Ordinary terms filter the same recent-post page locally. Reuse its
        // exact response briefly instead of refetching ten posts per term.
        // Hashtag queries have distinct serialized keys and cannot mix results.
        postPages.startCachedFor(com.friendbook.social.core.SearchReadPolicy.EXACT_PAGE_REUSE_MS);
    }

    // ---- UsersAdapter.Handler ----
    @Override public void primary(UsersAdapter.Row r) { }
    @Override public void secondary(UsersAdapter.Row r) { }

    // ---- FeedAdapter.Host ----
    @Override public void openComments(Models.Post post) {
        Intent i = new Intent(this, PostDetailActivity.class);
        i.putExtra("post_id", post.id);
        startActivity(i);
    }
    @Override public void openProfile(String uid) {
        Intent i = new Intent(this, ProfileActivity.class);
        i.putExtra("uid", uid);
        startActivity(i);
    }
    @Override public void editPost(Models.Post post) {
        Intent i = new Intent(this, CreatePostActivity.class);
        i.putExtra("edit_id", post.id);
        startActivity(i);
    }
    @Override public void createStory() { }
    @Override public void openStoryGroup(com.friendbook.social.story.StoriesAdapter.Group g) { }
    @Override public void loadMore() { }
    @Override public boolean hasMore() { return false; }
}
