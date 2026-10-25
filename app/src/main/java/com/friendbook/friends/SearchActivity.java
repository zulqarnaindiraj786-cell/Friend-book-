package com.friendbook.friends;

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

import com.friendbook.core.Fb;
import com.friendbook.core.Ui;
import com.friendbook.models.Models;
import com.friendbook.post.FeedAdapter;
import com.friendbook.post.CreatePostActivity;
import com.friendbook.post.PostDetailActivity;
import com.friendbook.profile.ProfileActivity;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.Query;

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

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));

        // search bar
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.BLUE);
        TextView back = Ui.tv(this, "←", 22, 0xFFFFFFFF);
        back.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12));
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        input = Ui.input(this, "🔍 Search people and posts…");
        input.setBackground(Ui.round(this, 0x33FFFFFF, 20));
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xB3FFFFFF);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1);
        ip.setMargins(0, 0, Ui.dp(this, 12), 0);
        bar.addView(input, ip);
        root.addView(bar);

        ScrollView sv = new ScrollView(this);
        LinearLayout body = Ui.col(this);

        noResults = Ui.tv(this, "Type to search for people and posts", 14, Ui.sub(this));
        noResults.setGravity(android.view.Gravity.CENTER);
        Ui.pad(noResults, 24, 50, 24, 0);
        body.addView(noResults);

        // 🔍 recent searches (FB Lite: chips when the box is empty)
        historyBox = Ui.col(this);
        body.addView(historyBox, 0);
        renderHistory();

        peopleTitle = section("People");
        body.addView(peopleTitle);
        RecyclerView usersRv = new RecyclerView(this);
        usersRv.setLayoutManager(new LinearLayoutManager(this));
        usersRv.setNestedScrollingEnabled(false);
        usersAdapter = new UsersAdapter(this, this);
        usersRv.setAdapter(usersAdapter);
        body.addView(usersRv, new LinearLayout.LayoutParams(-1, -2));

        postsTitle = section("Posts");
        body.addView(postsTitle);
        RecyclerView postsRv = new RecyclerView(this);
        postsRv.setLayoutManager(new LinearLayoutManager(this));
        postsRv.setNestedScrollingEnabled(false);
        postsAdapter = new FeedAdapter(this, this);
        postsAdapter.showHeader = false;
        postsAdapter.showFooter = false;
        postsRv.setAdapter(postsAdapter);
        body.addView(postsRv, new LinearLayout.LayoutParams(-1, -2));

        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {
                if (pending != null) handler.removeCallbacks(pending);
                final String q = s.toString().trim();
                pending = () -> search(q);
                handler.postDelayed(pending, 350);
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
        java.util.List<String> hist = com.friendbook.core.Prefs.searchHistory(this);
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
            com.friendbook.core.Prefs.clearSearch(this);
            renderHistory();
        });
        historyBox.addView(clear);
    }

    private void search(String q) {
        if (q.isEmpty()) {
            peopleTitle.setVisibility(View.GONE);
            postsTitle.setVisibility(View.GONE);
            usersAdapter.setData(new ArrayList<>());
            postsAdapter.setPosts(new ArrayList<>());
            noResults.setText("Type to search for people and posts");
            renderHistory();
            return;
        }
        if (historyBox != null) historyBox.setVisibility(View.GONE);
        com.friendbook.core.Prefs.addSearch(this, q);

        // ---- people: displayName prefix search in both cases ----
        Map<String, Models.User> people = new LinkedHashMap<>();
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
        final int[] done = {0};
        Runnable tick = () -> { if (++done[0] >= 2) finishPeople.run(); };
        Query q1 = Fb.db().collection("users")
                .whereGreaterThanOrEqualTo("displayName", q)
                .whereLessThanOrEqualTo("displayName", q + "").limit(15);
        q1.get().addOnSuccessListener(snap -> {
            for (DocumentSnapshot d : snap) people.put(d.getId(), Models.User.fromDoc(d));
            tick.run();
        }).addOnFailureListener(e -> tick.run());
        Query q2 = Fb.db().collection("users")
                .whereGreaterThanOrEqualTo("displayName", cap)
                .whereLessThanOrEqualTo("displayName", cap + "").limit(15);
        q2.get().addOnSuccessListener(snap -> {
            for (DocumentSnapshot d : snap) people.put(d.getId(), Models.User.fromDoc(d));
            tick.run();
        }).addOnFailureListener(e -> tick.run());

        // ---- posts: recent 100 filtered client-side ----
        Fb.db().collection("posts")
                .orderBy("createdAt", Query.Direction.DESCENDING).limit(100).get()
                .addOnSuccessListener(snap -> {
                    List<Models.Post> list = new ArrayList<>();
                    String needle = q.toLowerCase(Locale.ROOT);
                    if (snap != null) {
                        for (DocumentSnapshot d : snap) {
                            Models.Post p = Models.Post.fromDoc(d);
                            if (p.archived || p.trashedAt > 0) continue;
                            if ("Only me".equalsIgnoreCase(p.privacy) && !p.authorId.equals(Fb.uid())) continue;
                            if (Fb.me != null && Fb.me.blocked.contains(p.authorId)) continue;
                            String hay = (p.content == null ? "" : p.content).toLowerCase(Locale.ROOT)
                                    + " " + (p.authorName == null ? "" : p.authorName).toLowerCase(Locale.ROOT);
                            if (hay.contains(needle)) list.add(p);
                            if (list.size() >= 20) break;
                        }
                    }
                    postsAdapter.setPosts(list);
                    postsTitle.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
                    noResults.setText("");
                });
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
    @Override public void openStoryGroup(com.friendbook.story.StoriesAdapter.Group g) { }
    @Override public void loadMore() { }
    @Override public boolean hasMore() { return false; }
}
