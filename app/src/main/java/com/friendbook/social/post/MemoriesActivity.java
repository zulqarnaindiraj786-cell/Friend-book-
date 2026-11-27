package com.friendbook.social.post;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.friendbook.social.d1.documents.DocumentSnapshot;
import com.friendbook.social.d1.documents.Query;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 📅 Memories — "On this day": your posts from previous years on today's date —
 * exactly like friendbook's Memories. Read-only, realtime.
 */
public class MemoriesActivity extends Activity implements FeedAdapter.Host {
    private LinearLayout pageControls;
    private com.friendbook.social.d1.documents.ListenerRegistration memoriesReg;

    private LinearLayout slots;
    private TextView countT;
    private FeedAdapter adapter;
    private final List<Models.Post> memories = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        LinearLayout bar = Ui.pageBar(this, "📅 On this day");
        TextView shareNew = Ui.roundChip(this, "＋ Post Memory", Ui.inputBg(this), Ui.txt(this), 12);
        Ui.pad(shareNew, 10, 6, 10, 6);
        shareNew.setOnClickListener(v -> {
            android.content.Intent ci = new android.content.Intent(this, CreatePostActivity.class);
            startActivity(ci);
        });
        bar.addView(shareNew);
        root.addView(bar);
        root.addView(Ui.divider(this));

        androidx.recyclerview.widget.RecyclerView rv = new androidx.recyclerview.widget.RecyclerView(this);
        rv.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        rv.setItemAnimator(null);
        adapter = new FeedAdapter(this, this);
        adapter.showHeader = false;
        adapter.showFooter = false;
        rv.setAdapter(adapter);

        LinearLayout wrap = Ui.col(this);
        countT = Ui.tvBold(this, "📅 Today's Memories", 14, Ui.txt(this));
        Ui.pad(countT, 14, 10, 14, 6);
        wrap.addView(countT);
        TextView hint = Ui.tv(this,
                "Aapki purani posts jo aaj ke din pichle saalon me share ki gayi thi.",
                12, Ui.sub(this));
        Ui.pad(hint, 14, 0, 14, 6);
        wrap.addView(hint);
        wrap.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));
        pageControls=Ui.col(this);
        pageControls.addView(Ui.tv(this,"Memories from loaded posts; load more to search older posts.",12,Ui.sub(this)));
        wrap.addView(pageControls);
        root.addView(wrap, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override protected void onStart() {
        super.onStart();
        String me = Fb.uid();
        if (me == null) { finish(); return; }
        memoriesReg=com.friendbook.social.core.PagedLists.attach(this,pageControls,
                Fb.db().collection("posts").whereEqualTo("authorId", me)
                .orderBy("createdAt", Query.Direction.DESCENDING),10,"memory-posts",(snap,err)-> {
                    memories.clear();
                    Calendar today = Calendar.getInstance();
                    int m = today.get(Calendar.MONTH), d = today.get(Calendar.DAY_OF_MONTH), y = today.get(Calendar.YEAR);
                    for (DocumentSnapshot doc : snap) {
                        Models.Post p = Models.Post.fromDoc(doc);
                        if (p.createdAt == 0) continue;
                        Calendar c = Calendar.getInstance();
                        c.setTimeInMillis(p.createdAt);
                        if (c.get(Calendar.MONTH) == m && c.get(Calendar.DAY_OF_MONTH) == d
                                && c.get(Calendar.YEAR) < y) memories.add(p);
                    }
                    adapter.setPosts(memories);
                    countT.setText(memories.isEmpty()
                            ? "Aaj ke din abhi koi purani memory nahi 😊"
                            : memories.size() + " memor" + (memories.size() == 1 ? "y" : "ies")
                            + " — " + y + " se pehle ke aaj");
                });
    }

    @Override protected void onStop() {
        super.onStop();
        if(memoriesReg!=null){memoriesReg.remove();memoriesReg=null;}
    }

    // -------- FeedAdapter.Host --------
    @Override public void openComments(Models.Post post) {
        android.content.Intent i = new android.content.Intent(this, PostDetailActivity.class);
        i.putExtra("post_id", post.id);
        startActivity(i);
    }

    @Override public void openProfile(String uid) {
        android.content.Intent i = new android.content.Intent(this, com.friendbook.social.profile.ProfileActivity.class);
        i.putExtra("uid", uid);
        startActivity(i);
    }

    @Override public void editPost(Models.Post post) { }
    @Override public boolean hasMore() { return false; }
    @Override public void loadMore() { }

    @Override public void openStoryGroup(com.friendbook.social.story.StoriesAdapter.Group g) { }
    @Override public void createStory() { }
}
