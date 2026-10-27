package com.friendbook.groups;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
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
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.Source;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 👨‍👩‍👧‍👦 friendbook 525 Groups: Search, Discover / Joined / My Groups tabs, Public/Private & Rules. */
public class GroupsActivity extends Activity {

    private final List<Models.Group> allGroups = new ArrayList<>();
    private final List<Models.Group> groups = new ArrayList<>();
    private Adapter adapter;
    private ListenerRegistration reg;
    private TextView empty, tabDiscover, tabJoined, tabMine;
    private int tabMode = 0; // 0 = All/Discover, 1 = Joined, 2 = Admin/Mine
    private String searchQuery = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));

        LinearLayout bar = Ui.pageBar(this, "👨‍👩‍👧‍👦 Groups");
        TextView create = Ui.roundChip(this, "＋ Create", Ui.inputBg(this), Ui.txt(this), 13);
        Ui.pad(create, 12, 6, 12, 6);
        create.setOnClickListener(v -> createDialog());
        bar.addView(create);
        root.addView(bar);

        // Search + Tabs
        LinearLayout top = Ui.col(this);
        top.setBackgroundColor(Ui.card(this));
        Ui.pad(top, 10, 8, 10, 8);
        EditText search = Ui.input(this, "🔎 Search groups…");
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {
                searchQuery = s.toString().trim().toLowerCase();
                applyFilter();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        top.addView(search, new LinearLayout.LayoutParams(-1, Ui.dp(this, 40)));

        LinearLayout tabs = Ui.row(this);
        tabs.setPadding(0, Ui.dp(this, 8), 0, 0);
        tabDiscover = Ui.tvBold(this, "🌐 Discover", 12, 0xFFFFFFFF);
        tabDiscover.setGravity(Gravity.CENTER);
        Ui.pad(tabDiscover, 10, 6, 10, 6);
        tabJoined = Ui.tvBold(this, "✓ Your Groups", 12, Ui.sub(this));
        tabJoined.setGravity(Gravity.CENTER);
        Ui.pad(tabJoined, 10, 6, 10, 6);
        tabMine = Ui.tvBold(this, "👑 Managed", 12, Ui.sub(this));
        tabMine.setGravity(Gravity.CENTER);
        Ui.pad(tabMine, 10, 6, 10, 6);

        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, -2, 1);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, -2, 1);
        lp2.leftMargin = Ui.dp(this, 6);
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(0, -2, 1);
        lp3.leftMargin = Ui.dp(this, 6);
        tabs.addView(tabDiscover, lp1);
        tabs.addView(tabJoined, lp2);
        tabs.addView(tabMine, lp3);

        tabDiscover.setOnClickListener(v -> { tabMode = 0; refreshTabs(); applyFilter(); });
        tabJoined.setOnClickListener(v -> { tabMode = 1; refreshTabs(); applyFilter(); });
        tabMine.setOnClickListener(v -> { tabMode = 2; refreshTabs(); applyFilter(); });
        top.addView(tabs);
        root.addView(top);
        root.addView(Ui.divider(this));
        refreshTabs();

        empty = Ui.tv(this, "", 14, Ui.sub(this));
        empty.setGravity(Gravity.CENTER);
        Ui.pad(empty, 24, 50, 24, 0);
        empty.setVisibility(View.GONE);
        root.addView(empty);

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setItemAnimator(null);
        rv.setItemViewCacheSize(20);
        adapter = new Adapter();
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        // ⚡ Instant cache-first hydration!
        Fb.db().collection("groups").orderBy("createdAt", Query.Direction.DESCENDING).limit(100)
                .get(Source.CACHE)
                .addOnSuccessListener(snap -> {
                    if (snap != null && !snap.isEmpty() && allGroups.isEmpty()) {
                        for (DocumentSnapshot d : snap) allGroups.add(Models.Group.fromDoc(d));
                        applyFilter();
                    }
                });
    }

    private void refreshTabs() {
        tabDiscover.setBackground(Ui.round(this, tabMode == 0 ? Ui.BLUE : Ui.inputBg(this), 16));
        tabDiscover.setTextColor(tabMode == 0 ? 0xFFFFFFFF : Ui.sub(this));
        tabJoined.setBackground(Ui.round(this, tabMode == 1 ? Ui.BLUE : Ui.inputBg(this), 16));
        tabJoined.setTextColor(tabMode == 1 ? 0xFFFFFFFF : Ui.sub(this));
        tabMine.setBackground(Ui.round(this, tabMode == 2 ? Ui.BLUE : Ui.inputBg(this), 16));
        tabMine.setTextColor(tabMode == 2 ? 0xFFFFFFFF : Ui.sub(this));
    }

    private void applyFilter() {
        String me = Fb.uid();
        groups.clear();
        for (Models.Group g : allGroups) {
            if (tabMode == 1 && (me == null || !g.members.contains(me))) continue;
            if (tabMode == 2 && (me == null || !me.equals(g.adminId))) continue;
            if (!searchQuery.isEmpty()) {
                String hay = ((g.name == null ? "" : g.name) + " " + (g.about == null ? "" : g.about)).toLowerCase();
                if (!hay.contains(searchQuery)) continue;
            }
            groups.add(g);
        }
        groups.sort((a, b) -> {
            boolean ma = me != null && a.members.contains(me);
            boolean mb = me != null && b.members.contains(me);
            if (ma != mb) return ma ? -1 : 1;
            return Long.compare(b.createdAt, a.createdAt);
        });
        adapter.notifyDataSetChanged();
        empty.setText(tabMode == 1 ? "You haven't joined any groups yet."
                : tabMode == 2 ? "You don't manage any groups yet — tap ＋ Create!"
                : "No groups found — tap ＋ Create to start one!");
        empty.setVisibility(groups.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        reg = Fb.db().collection("groups")
                .orderBy("createdAt", Query.Direction.DESCENDING).limit(100)
                .addSnapshotListener((snap, err) -> {
                    if (snap == null) return;
                    allGroups.clear();
                    for (DocumentSnapshot d : snap) allGroups.add(Models.Group.fromDoc(d));
                    applyFilter();
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

        final String[] priv = {"Public"};
        TextView privBtn = Ui.roundChip(this, "🌐 Privacy: Public (tap to toggle)", Ui.inputBg(this), Ui.BLUE, 12);
        Ui.pad(privBtn, 12, 8, 12, 8);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2);
        pp.topMargin = Ui.dp(this, 10);
        privBtn.setOnClickListener(v -> {
            priv[0] = "Public".equals(priv[0]) ? "Private" : "Public";
            privBtn.setText(("Public".equals(priv[0]) ? "🌐 Privacy: Public" : "🔒 Privacy: Private") + " (tap to toggle)");
        });
        box.addView(privBtn, pp);

        EditText rules = Ui.multi(this, "Group rules (optional, e.g. Be kind & respectful)", 2);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = Ui.dp(this, 10);
        box.addView(rules, rp);

        new AlertDialog.Builder(this)
                .setTitle("👨‍👩‍👧‍👦 Create group")
                .setView(box)
                .setPositiveButton("Create", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.length() < 2) { Ui.toast(this, "Name is too short"); return; }
                    String me = Fb.uid();
                    DocumentReference docRef = Fb.db().collection("groups").document();
                    java.util.Map<String, Object> g = Fb.map(
                            "name", n,
                            "about", about.getText().toString().trim(),
                            "privacy", priv[0],
                            "rules", rules.getText().toString().trim(),
                            "photoUrl", "",
                            "adminId", me,
                            "members", new ArrayList<>(Arrays.asList(me)),
                            "membersCount", 1,
                            "createdAt", FieldValue.serverTimestamp());
                    docRef.set(g);
                    Ui.toast(this, "Group created 🎉");
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
            String pIcon = "Private".equalsIgnoreCase(g.privacy) ? "🔒 " : "🌐 ";
            h.name.setText(g.name);
            h.sub.setText(pIcon + g.members.size() + " members · " + Format.ago(g.createdAt));
            Ui.avatar(GroupsActivity.this, h.img, g.photoUrl);
            String me = Fb.uid();
            boolean member = me != null && g.members.contains(me);
            h.join.setText(member ? "Joined ✓" : "Join");
            h.join.setBackground(Ui.round(GroupsActivity.this, member ? Ui.inputBg(GroupsActivity.this) : Ui.BLUE, 8));
            h.join.setTextColor(member ? Ui.txt(GroupsActivity.this) : 0xFFFFFFFF);
            h.join.setOnClickListener(v -> {
                if (me == null) return;
                // ⚡ 0.0ms optimistic join/leave
                if (member) g.members.remove(me); else g.members.add(me);
                notifyItemChanged(position);
                java.util.Map<String, Object> m = Fb.map(
                        "members", member ? FieldValue.arrayRemove(me) : FieldValue.arrayUnion(me),
                        "membersCount", FieldValue.increment(member ? -1 : 1));
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
