package com.friendbook.groups;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.core.Fb;
import com.friendbook.core.Format;
import com.friendbook.core.Ui;
import com.friendbook.models.Models;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Groups tab: discover groups, your groups, create a group. */
public class GroupsActivity extends Activity {

    private final List<Models.Group> groups = new ArrayList<>();
    private Adapter adapter;
    private ListenerRegistration reg;
    private TextView empty;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));

        LinearLayout bar = Ui.pageBar(this, "👨‍👩‍👧‍👦 Groups");
        TextView create = Ui.tvBold(this, "＋ Create", 14, 0xFFFFFFFF);
        create.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        create.setOnClickListener(v -> createDialog());
        bar.addView(create);
        root.addView(bar);

        empty = Ui.tv(this, "", 14, Ui.sub(this));
        empty.setGravity(Gravity.CENTER);
        Ui.pad(empty, 24, 50, 24, 0);
        root.addView(empty);

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        reg = Fb.db().collection("groups")
                .orderBy("createdAt", Query.Direction.DESCENDING).limit(100)
                .addSnapshotListener((snap, err) -> {
                    groups.clear();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap) groups.add(Models.Group.fromDoc(d));
                    }
                    // my groups first
                    String me = Fb.uid();
                    groups.sort((a, b) -> {
                        boolean ma = me != null && a.members.contains(me);
                        boolean mb = me != null && b.members.contains(me);
                        if (ma != mb) return ma ? -1 : 1;
                        return Long.compare(b.createdAt, a.createdAt);
                    });
                    adapter.notifyDataSetChanged();
                    empty.setText("No groups yet — create the first one!");
                    empty.setVisibility(groups.isEmpty() ? View.VISIBLE : View.GONE);
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
    }

    private void createDialog() {
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 18, 8, 18, 0);
        EditText name = Ui.input(this, "Group name");
        box.addView(name, new LinearLayout.LayoutParams(-1, Ui.dp(this, 46)));
        EditText about = Ui.multi(this, "What's this group about?", 2);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.topMargin = Ui.dp(this, 10);
        box.addView(about, ap);
        new AlertDialog.Builder(this)
                .setTitle("Create group")
                .setView(box)
                .setPositiveButton("Create", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.length() < 2) { Ui.toast(this, "Name is too short"); return; }
                    String me = Fb.uid();
                    java.util.Map<String, Object> g = Fb.map(
                            "name", n,
                            "about", about.getText().toString().trim(),
                            "photoUrl", "",
                            "adminId", me,
                            "members", new ArrayList<>(Arrays.asList(me)),
                            "membersCount", 1,
                            "createdAt", FieldValue.serverTimestamp());
                    Fb.db().collection("groups").add(g)
                            .addOnSuccessListener(x -> Ui.toast(this, "Group created 🎉"))
                            .addOnFailureListener(e -> Ui.toast(this, "Failed"));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        @Override public int getItemCount() { return groups.size(); }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = Ui.row(GroupsActivity.this);
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            Ui.pad(row, 12, 10, 12, 10);
            row.setBackgroundColor(Ui.card(GroupsActivity.this));

            ImageView img = new ImageView(GroupsActivity.this);
            int s = Ui.dp(GroupsActivity.this, 50);
            row.addView(img, new LinearLayout.LayoutParams(s, s));

            LinearLayout mid = Ui.col(GroupsActivity.this);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1);
            mp.leftMargin = Ui.dp(GroupsActivity.this, 12);
            TextView name = Ui.tvBold(GroupsActivity.this, "", 15, Ui.txt(GroupsActivity.this));
            TextView sub = Ui.tv(GroupsActivity.this, "", 12, Ui.sub(GroupsActivity.this));
            sub.setMaxLines(1);
            mid.addView(name);
            mid.addView(sub);
            row.addView(mid, mp);

            TextView join = Ui.btn(GroupsActivity.this, "Join");
            join.setMinHeight(Ui.dp(GroupsActivity.this, 32));
            join.setTextSize(12);
            row.addView(join);
            return new VH(row, img, name, sub, join);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Models.Group g = groups.get(position);
            h.name.setText(g.name);
            h.sub.setText(g.members.size() + " members · " + Format.ago(g.createdAt));
            Ui.avatar(GroupsActivity.this, h.img, g.photoUrl);
            boolean member = g.members.contains(Fb.uid());
            h.join.setText(member ? "Joined ✓" : "Join");
            h.join.setBackground(Ui.round(GroupsActivity.this, member ? Ui.inputBg(GroupsActivity.this) : Ui.BLUE, 8));
            if (member) h.join.setTextColor(Ui.txt(GroupsActivity.this));
            h.join.setOnClickListener(v -> {
                String me = Fb.uid();
                if (me == null) return;
                boolean joined = g.members.contains(me);
                java.util.Map<String, Object> m = Fb.map(
                        "members", joined ? FieldValue.arrayRemove(me) : FieldValue.arrayUnion(me),
                        "membersCount", FieldValue.increment(joined ? -1 : 1));
                Fb.db().collection("groups").document(g.id).set(m, SetOptions.merge());
            });
            h.itemView.setOnClickListener(v -> {
                Intent i = new Intent(GroupsActivity.this, GroupDetailActivity.class);
                i.putExtra("group_id", g.id);
                i.putExtra("name", g.name);
                startActivity(i);
            });
        }

        class VH extends RecyclerView.ViewHolder {
            ImageView img;
            TextView name, sub, join;
            VH(View v, ImageView i, TextView n, TextView s, TextView j) {
                super(v);
                img = i; name = n; sub = s; join = j;
            }
        }
    }
}
