package com.friendbook.social.settings;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Ui;
import com.friendbook.social.friends.UsersAdapter;
import com.friendbook.social.models.Models;
import com.friendbook.social.d1.documents.FieldValue;
import com.friendbook.social.d1.documents.ListenerRegistration;

import java.util.ArrayList;
import java.util.List;

/** Manage the block list. */
public class BlockedUsersActivity extends Activity implements UsersAdapter.Handler {

    private UsersAdapter adapter;
    private TextView empty;
    private ListenerRegistration reg;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "🚫 Blocked users"));

        empty = Ui.tv(this, "You haven't blocked anyone.", 14, Ui.sub(this));
        empty.setGravity(Gravity.CENTER);
        Ui.pad(empty, 24, 60, 24, 0);
        root.addView(empty);

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new UsersAdapter(this, this);
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        String me = Fb.uid();
        if (me == null) { finish(); return; }
        reg = Fb.db().collection("users").document(me)
                .addSnapshotListener((d, err) -> {
                    if (d == null || !d.exists()) return;
                    Models.User me2 = Models.User.fromDoc(d);
                    List<UsersAdapter.Row> rows = new ArrayList<>();
                    if (me2.blocked.isEmpty()) {
                        adapter.setData(rows);
                        empty.setVisibility(android.view.View.VISIBLE);
                        return;
                    }
                    empty.setVisibility(android.view.View.GONE);
                    final List<UsersAdapter.Row> out = new ArrayList<>();
                    final int[] pending = {me2.blocked.size()};
                    for (String uid : me2.blocked) {
                        Fb.getUser(uid).addOnSuccessListener(u -> {
                            if (u != null) {
                                UsersAdapter.Row r = new UsersAdapter.Row();
                                r.user = u;
                                r.subtitle = "Blocked";
                                r.primaryLabel = "Unblock";
                                out.add(r);
                            }
                            if (--pending[0] <= 0) {
                                out.sort((a, b2) -> a.user.displayName.compareToIgnoreCase(b2.user.displayName));
                                adapter.setData(out);
                            }
                        });
                    }
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
    }

    @Override
    public void primary(UsersAdapter.Row r) {
        Ui.confirm(this, "Unblock " + r.user.displayName + "?",
                "They'll be able to see your posts and message you again.",
                "Unblock", () -> {
                    Fb.patchMe(Fb.map("blockedUsers", FieldValue.arrayRemove(r.user.uid)));
                    Ui.toast(this, "Unblocked ✓");
                });
    }

    @Override
    public void secondary(UsersAdapter.Row r) { }
}
