package com.friendbook.social.friends;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Prefs;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.friendbook.social.profile.ProfileActivity;
import com.friendbook.social.d1.documents.DocumentSnapshot;

import java.util.ArrayList;
import java.util.List;

/** Followers / following lists for any profile. */
public class FollowListActivity extends Activity {

    private final List<String> ids = new ArrayList<>();
    private UsersAdapter adapter;
    private TextView empty;
    private com.friendbook.social.core.CursorPager followPager;
    private TextView moreButton;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        String uid = getIntent().getStringExtra("uid");
        String mode = getIntent().getStringExtra("mode"); // "followers" | "following"
        if (uid == null) { finish(); return; }
        boolean followers = !"following".equals(mode);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, followers ? "Followers" : "Following"));

        empty = Ui.tv(this, "", 14, Ui.sub(this));
        empty.setGravity(Gravity.CENTER);
        Ui.pad(empty, 24, 60, 24, 0);
        root.addView(empty);

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new UsersAdapter(this, null);
        adapter.onClick = u -> {
            Intent i = new Intent(this, ProfileActivity.class);
            i.putExtra("uid", u.uid);
            startActivity(i);
        };
        rv.setAdapter(adapter);
        com.friendbook.social.core.NearEndPaging.attach(rv,()->{if(followPager!=null)followPager.next();});
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));
        moreButton=Ui.tvBold(this,"Load 20 more",14,Ui.BLUE);root.addView(moreButton);
        setContentView(root);

        com.friendbook.social.core.CursorPager pager=new com.friendbook.social.core.CursorPager(
            Fb.db().collection("users").whereArrayContains(followers?"following":"followers",uid),20,"follow:"+uid+":"+followers,
            (docs,more,loading,error)-> {
                if(isFinishing())return;
                if(loading){moreButton.setText("Loading…");moreButton.setEnabled(false);return;}
                List<UsersAdapter.Row> rows=followers?followersRows:followingRows;rows.clear();
                for(DocumentSnapshot doc:docs){Models.User u=Models.User.fromDoc(doc);if(u.deactivated)continue;
                    com.friendbook.social.core.FastCache.putUser(u);UsersAdapter.Row row=new UsersAdapter.Row();row.user=u;row.subtitle=u.bio;rows.add(row);}
                render();moreButton.setText(loading?"Loading…":!error.isEmpty()?"Retry":more?"Load 20 more":"All loaded");
                moreButton.setEnabled(!loading && more);
            });
        followPager=pager;moreButton.setOnClickListener(v->pager.next());pager.start();
    }
    @Override protected void onDestroy(){if(followPager!=null)followPager.close();super.onDestroy();}

    private final List<UsersAdapter.Row> followersRows = new ArrayList<>();
    private final List<UsersAdapter.Row> followingRows = new ArrayList<>();

    private void render() {
        boolean followers = !"following".equals(getIntent().getStringExtra("mode"));
        List<UsersAdapter.Row> rows = followers ? followersRows : followingRows;
        rows.sort((a, b) -> a.user.displayName.compareToIgnoreCase(b.user.displayName));
        adapter.setData(rows);
        empty.setText(followers ? "No followers yet." : "Not following anyone yet.");
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
