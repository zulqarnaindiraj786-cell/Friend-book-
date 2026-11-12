package com.example.frienbook.friends;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Format;
import com.example.frienbook.core.Prefs;
import com.example.frienbook.core.Ui;
import com.example.frienbook.models.Models;
import com.example.frienbook.profile.ProfileActivity;
import com.example.frienbook.core.cloud.DocumentSnapshot;

import java.util.ArrayList;
import java.util.List;

/** Followers / following lists for any profile. */
public class FollowListActivity extends Activity {

    private final List<String> ids = new ArrayList<>();
    private UsersAdapter adapter;
    private TextView empty;
    private int loaded = 0;

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
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        Fb.db().collection("users").document(uid).get().addOnSuccessListener(d -> {
            ids.clear();
            ids.addAll(followers ? Format.listOf(d.getData(), "followers")
                    : Format.listOf(d.getData(), "following"));
            if (ids.isEmpty()) { render(); return; }
            loaded = ids.size();
            for (String id : ids) {
                Fb.getUser(id).addOnSuccessListener(u -> {
                    if (u != null) {
                        UsersAdapter.Row r = new UsersAdapter.Row();
                        r.user = u;
                        r.subtitle = u.bio == null ? "" : u.bio;
                        (followers ? followersRows : followingRows).add(r);
                    }
                    if (--loaded <= 0) render();
                });
            }
        }).addOnFailureListener(e -> render());
    }

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
