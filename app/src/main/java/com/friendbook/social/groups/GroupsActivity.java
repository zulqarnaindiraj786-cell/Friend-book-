package com.friendbook.social.groups;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Icons;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 👥 friendbook Community Post Groups Hub:
 * - Tabs: ✨ Discover · 👥 Your Groups · 📨 Invites
 * - Create Public or Private Groups with Category, Rules & Post Approval settings!
 */
public class GroupsActivity extends Activity {
    private LinearLayout pageControls;

    private static final String[] CATEGORIES = {
            "Community", "Gaming", "Tech & Coding", "Sports", "Buy & Sell", "Education", "Entertainment"
    };

    private ListenerRegistration reg;
    private final List<Models.Group> allGroups = new ArrayList<>();
    private final List<Models.Group> visible = new ArrayList<>();
    private GroupAdapter adapter;
    private TextView empty;
    private TextView discoverTab, mineTab, invitesTab;
    private int tabMode = 0; // 0 = Discover, 1 = Your Groups, 2 = Invites
    private String query = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));

        LinearLayout bar = Ui.pageBar(this, "Groups");
        TextView add = Ui.roundChip(this, "＋ Create", Ui.BLUE, Color.WHITE, 13);
        Ui.pad(add, 13, 6, 13, 6);
        add.setOnClickListener(v -> createDialog());
        bar.addView(add);
        root.addView(bar);

        // Search + Tabs
        LinearLayout header = Ui.col(this);
        header.setBackgroundColor(Ui.card(this));
        Ui.pad(header, 12, 8, 12, 10);
        EditText search = Ui.input(this, "🔍 Search groups or topics…");
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {
                query = s.toString().trim().toLowerCase(Locale.US);
                applyFilter();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        header.addView(search, new LinearLayout.LayoutParams(-1, Ui.dp(this, 40)));

        LinearLayout tabs = Ui.row(this);
        tabs.setPadding(0, Ui.dp(this, 8), 0, 0);
        discoverTab = Ui.tvBold(this, "✨ Discover", 12, Color.WHITE);
        discoverTab.setGravity(Gravity.CENTER);
        Ui.pad(discoverTab, 10, 6, 10, 6);
        mineTab = Ui.tvBold(this, "👥 Your Groups", 12, Ui.sub(this));
        mineTab.setGravity(Gravity.CENTER);
        Ui.pad(mineTab, 10, 6, 10, 6);
        invitesTab = Ui.tvBold(this, "📨 Invites", 12, Ui.sub(this));
        invitesTab.setGravity(Gravity.CENTER);
        Ui.pad(invitesTab, 10, 6, 10, 6);

        LinearLayout.LayoutParams t1 = new LinearLayout.LayoutParams(0, -2, 1);
        LinearLayout.LayoutParams t2 = new LinearLayout.LayoutParams(0, -2, 1);
        t2.leftMargin = Ui.dp(this, 6);
        LinearLayout.LayoutParams t3 = new LinearLayout.LayoutParams(0, -2, 1);
        t3.leftMargin = Ui.dp(this, 6);
        tabs.addView(discoverTab, t1);
        tabs.addView(mineTab, t2);
        tabs.addView(invitesTab, t3);

        discoverTab.setOnClickListener(v -> { tabMode = 0; updateTabs(); startGroupPages(); });
        mineTab.setOnClickListener(v -> { tabMode = 1; updateTabs(); startGroupPages(); });
        invitesTab.setOnClickListener(v -> { tabMode = 2; updateTabs(); startGroupPages(); });
        header.addView(tabs);
        root.addView(header);
        root.addView(Ui.divider(this));
        updateTabs();

        empty = Ui.tv(this, "No groups yet — tap ＋ Create to start a community!", 14, Ui.sub(this));
        empty.setGravity(Gravity.CENTER);
        Ui.pad(empty, 20, 44, 20, 20);
        empty.setVisibility(View.GONE);
        root.addView(empty);

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new GroupAdapter();
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));
        pageControls=Ui.col(this);root.addView(pageControls);
        setContentView(root);
    }

    private void updateTabs() {
        discoverTab.setBackground(Ui.round(this, tabMode == 0 ? Ui.BLUE : Ui.inputBg(this), 16));
        discoverTab.setTextColor(tabMode == 0 ? Color.WHITE : Ui.sub(this));
        mineTab.setBackground(Ui.round(this, tabMode == 1 ? Ui.BLUE : Ui.inputBg(this), 16));
        mineTab.setTextColor(tabMode == 1 ? Color.WHITE : Ui.sub(this));
        invitesTab.setBackground(Ui.round(this, tabMode == 2 ? Ui.BLUE : Ui.inputBg(this), 16));
        invitesTab.setTextColor(tabMode == 2 ? Color.WHITE : Ui.sub(this));
    }

    @Override
    protected void onStart() {
        super.onStart();
        startGroupPages();
    }
    private void startGroupPages() {
        if(reg!=null)reg.remove();
        com.google.firebase.firestore.Query groupQuery=Fb.db().collection("groups");
        if(tabMode==1)groupQuery=groupQuery.whereArrayContains("members",Fb.uid());
        if(tabMode==2)groupQuery=groupQuery.whereArrayContains("invitedUids",Fb.uid());
        reg=com.friendbook.social.core.PagedLists.attach(this,pageControls,groupQuery,20,"groups:"+tabMode,(snap,err)-> {
                    allGroups.clear();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap.getDocuments()) {
                            allGroups.add(Models.Group.fromDoc(d));
                        }
                    }
                    Collections.sort(allGroups, (a, b) -> Long.compare(b.createdAt, a.createdAt));
                    updateInviteBadge();
                    applyFilter();
                });
    }

    private void updateInviteBadge() {
        String me = Fb.uid();
        int inv = 0;
        if (me != null) {
            for (Models.Group g : allGroups) {
                if (g.invitedUids.contains(me) && !g.members.contains(me)) inv++;
            }
        }
        invitesTab.setText(inv > 0 ? "📨 Invites (loaded " + inv + ")" : "📨 Invites");
    }

    private void applyFilter() {
        String me = Fb.uid();
        visible.clear();
        for (Models.Group g : allGroups) {
            if (me != null && g.bannedMemberIds.contains(me)) continue;
            boolean member = me != null && g.members.contains(me);
            boolean invited = me != null && g.invitedUids.contains(me) && !member;
            if (tabMode == 1 && !member) continue;
            if (tabMode == 2 && !invited) continue;
            if (!query.isEmpty()) {
                String hay = (g.name + " " + g.about + " " + g.category).toLowerCase(Locale.US);
                if (!hay.contains(query)) continue;
            }
            visible.add(g);
        }
        adapter.notifyDataSetChanged();
        if (tabMode == 2) empty.setText("No pending group invitations 📨");
        else if (tabMode == 1) empty.setText("You haven't joined any groups yet.\nExplore Discover or tap ＋ Create!");
        else empty.setText("No groups found — tap ＋ Create to launch one!");
        empty.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
    }

    private void createDialog() {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 18, 12, 18, 8);
        sv.addView(box);

        EditText name = Ui.input(this, "Group name (e.g. Tech Creators Hub)");
        EditText about = Ui.multi(this, "About this group…", 3);
        EditText rules = Ui.multi(this, "Group rules (e.g. 1. Be kind & respectful\n2. No spam)", 3);

        final String[] selectedCat = {CATEGORIES[0]};
        HorizontalScrollView catSv = new HorizontalScrollView(this);
        catSv.setHorizontalScrollBarEnabled(false);
        LinearLayout catRow = Ui.row(this);
        catRow.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        final List<TextView> catPills = new ArrayList<>();
        for (int i = 0; i < CATEGORIES.length; i++) {
            final int idx = i;
            TextView ch = Ui.roundChip(this, CATEGORIES[i],
                    i == 0 ? Ui.BLUE : Ui.inputBg(this),
                    i == 0 ? Color.WHITE : Ui.txt(this), 11.5f);
            Ui.pad(ch, 10, 5, 10, 5);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
            clp.rightMargin = Ui.dp(this, 6);
            ch.setOnClickListener(v -> {
                selectedCat[0] = CATEGORIES[idx];
                for (int j = 0; j < catPills.size(); j++) {
                    catPills.get(j).setBackground(Ui.round(this, j == idx ? Ui.BLUE : Ui.inputBg(this), 16));
                    catPills.get(j).setTextColor(j == idx ? Color.WHITE : Ui.txt(this));
                }
            });
            catPills.add(ch);
            catRow.addView(ch, clp);
        }
        catSv.addView(catRow);

        final String[] privacy = {"Public"};
        TextView privChip = Ui.btnGrey(this, "🌍 Privacy: Public (Anyone can join & view posts)");
        privChip.setOnClickListener(v -> {
            privacy[0] = "Public".equals(privacy[0]) ? "Private" : "Public";
            privChip.setText("Public".equals(privacy[0])
                    ? "🌍 Privacy: Public (Anyone can join & view posts)"
                    : "🔒 Privacy: Private (Admins/Mods approve join requests)");
        });

        LinearLayout apprRow = Ui.row(this);
        apprRow.setGravity(Gravity.CENTER_VERTICAL);
        apprRow.setPadding(0, Ui.dp(this, 10), 0, 0);
        apprRow.addView(Ui.tvBold(this, "🛡 Require Post Approval", 13.5f, Ui.txt(this)),
                new LinearLayout.LayoutParams(0, -2, 1));
        Switch apprSw = new Switch(this);
        apprRow.addView(apprSw);

        box.addView(name);
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(-1, -2);
        p1.topMargin = Ui.dp(this, 8);
        box.addView(about, p1);
        box.addView(catSv);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(-1, -2);
        p2.topMargin = Ui.dp(this, 6);
        box.addView(privChip, p2);
        box.addView(apprRow);
        LinearLayout.LayoutParams p3 = new LinearLayout.LayoutParams(-1, -2);
        p3.topMargin = Ui.dp(this, 8);
        box.addView(rules, p3);

        new AlertDialog.Builder(this)
                .setTitle("👥 Create Community Group")
                .setView(sv)
                .setPositiveButton("Create Group", (d, w) -> {
                    String n = name.getText().toString().trim();
                    String me = Fb.uid();
                    if (n.isEmpty() || me == null) {
                        Ui.toast(this, "Enter a group name");
                        return;
                    }
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", n);
                    m.put("about", about.getText().toString().trim());
                    m.put("category", selectedCat[0]);
                    m.put("rules", rules.getText().toString().trim());
                    m.put("privacy", privacy[0]);
                    m.put("requirePostApproval", apprSw.isChecked());
                    m.put("requireJoinApproval", "Private".equals(privacy[0]));
                    m.put("postPermission", "Everyone");
                    m.put("adminId", me);
                    m.put("adminIds", Collections.singletonList(me));
                    m.put("moderatorIds", new ArrayList<String>());
                    m.put("members", Collections.singletonList(me));
                    m.put("pendingMemberIds", new ArrayList<String>());
                    m.put("bannedMemberIds", new ArrayList<String>());
                    m.put("invitedUids", new ArrayList<String>());
                    m.put("photoUrl", "");
                    m.put("coverUrl", "");
                    m.put("createdAt", FieldValue.serverTimestamp());
                    Fb.db().collection("groups").add(m)
                            .addOnSuccessListener(ref -> {
                                Ui.toast(this, "Group created 🎉");
                                startActivity(new Intent(this, GroupDetailActivity.class)
                                        .putExtra("group_id", ref.getId()));
                            });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    class GroupAdapter extends RecyclerView.Adapter<GroupAdapter.VH> {
        @Override public int getItemCount() { return visible.size(); }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = Ui.row(GroupsActivity.this);
            RecyclerView.LayoutParams rlp = new RecyclerView.LayoutParams(-1, -2);
            rlp.setMargins(Ui.dp(GroupsActivity.this, 10), Ui.dp(GroupsActivity.this, 6),
                    Ui.dp(GroupsActivity.this, 10), 0);
            row.setLayoutParams(rlp);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Ui.round(GroupsActivity.this, Ui.card(GroupsActivity.this), 14));
            Ui.pad(row, 12, 12, 12, 12);

            ImageView iconIv = new ImageView(GroupsActivity.this);
            int s = Ui.dp(GroupsActivity.this, 52);
            row.addView(iconIv, new LinearLayout.LayoutParams(s, s));

            LinearLayout mid = Ui.col(GroupsActivity.this);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1);
            mp.leftMargin = Ui.dp(GroupsActivity.this, 12);
            TextView name = Ui.tvBold(GroupsActivity.this, "", 16, Ui.txt(GroupsActivity.this));
            TextView meta = Ui.tv(GroupsActivity.this, "", 12, Ui.sub(GroupsActivity.this));
            TextView role = Ui.tvBold(GroupsActivity.this, "", 11, Ui.BLUE);
            mid.addView(name);
            mid.addView(meta);
            mid.addView(role);
            row.addView(mid, mp);

            TextView btn = Ui.btn(GroupsActivity.this, "Join");
            row.addView(btn);
            return new VH(row, iconIv, name, meta, role, btn);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Models.Group g = visible.get(position);
            String me = Fb.uid();
            boolean in = me != null && g.members.contains(me);
            boolean pending = me != null && g.pendingMemberIds.contains(me);
            boolean invited = me != null && g.invitedUids.contains(me) && !in;

            if (g.photoUrl != null && !g.photoUrl.isEmpty()) {
                h.iconIv.setPadding(0, 0, 0, 0);
                h.iconIv.setBackground(null);
                Ui.avatar(GroupsActivity.this, h.iconIv, g.photoUrl);
            } else {
                h.iconIv.setBackground(Ui.circle(Ui.BLUE));
                int p = Ui.dp(GroupsActivity.this, 13);
                h.iconIv.setPadding(p, p, p, p);
                h.iconIv.setImageDrawable(Icons.tinted(GroupsActivity.this, Icons.GROUPS, Color.WHITE));
            }

            h.name.setText(g.name);
            String priv = "Private".equalsIgnoreCase(g.privacy) ? "🔒 Private" : "🌍 Public";
            h.meta.setText(priv + " · " + g.members.size() + " members · " + g.category);

            if (g.isGroupAdmin(me)) {
                h.role.setVisibility(View.VISIBLE);
                h.role.setText("👑 Group Admin");
            } else if (g.isGroupModerator(me)) {
                h.role.setVisibility(View.VISIBLE);
                h.role.setText("🛡 Group Moderator");
            } else if (invited) {
                h.role.setVisibility(View.VISIBLE);
                h.role.setText("📨 You were invited to join!");
            } else {
                h.role.setVisibility(View.GONE);
            }

            if (in) {
                h.btn.setText("Open");
                h.btn.setBackground(Ui.round(GroupsActivity.this, Ui.inputBg(GroupsActivity.this), 8));
                h.btn.setTextColor(Ui.txt(GroupsActivity.this));
            } else if (pending) {
                h.btn.setText("Requested");
                h.btn.setBackground(Ui.round(GroupsActivity.this, Ui.inputBg(GroupsActivity.this), 8));
                h.btn.setTextColor(Ui.sub(GroupsActivity.this));
            } else {
                h.btn.setText(invited ? "Accept" : ("Private".equalsIgnoreCase(g.privacy) || g.requireJoinApproval ? "Request" : "Join"));
                h.btn.setBackground(Ui.round(GroupsActivity.this, Ui.BLUE, 8));
                h.btn.setTextColor(Color.WHITE);
            }
            Ui.pad(h.btn, 14, 7, 14, 7);

            h.btn.setOnClickListener(v -> {
                if (me == null) return;
                if (in) {
                    openGroup(g.id);
                } else if (pending) {
                    Fb.db().collection("groups").document(g.id)
                            .update("pendingMemberIds", FieldValue.arrayRemove(me));
                    Ui.toast(GroupsActivity.this, "Join request cancelled");
                } else if (!invited && ("Private".equalsIgnoreCase(g.privacy) || g.requireJoinApproval)) {
                    Fb.db().collection("groups").document(g.id)
                            .update("pendingMemberIds", FieldValue.arrayUnion(me));
                    Ui.toast(GroupsActivity.this, "⏳ Join request sent to Group Admins");
                } else {
                    Map<String, Object> up = new HashMap<>();
                    up.put("members", FieldValue.arrayUnion(me));
                    up.put("invitedUids", FieldValue.arrayRemove(me));
                    Fb.db().collection("groups").document(g.id).update(up);
                    Ui.toast(GroupsActivity.this, "Joined " + g.name + " 🎉");
                }
            });
            h.itemView.setOnClickListener(v -> openGroup(g.id));
        }

        private void openGroup(String gid) {
            startActivity(new Intent(GroupsActivity.this, GroupDetailActivity.class)
                    .putExtra("group_id", gid));
        }

        class VH extends RecyclerView.ViewHolder {
            ImageView iconIv;
            TextView name, meta, role, btn;
            VH(View v, ImageView ic, TextView n, TextView m, TextView rl, TextView b) {
                super(v);
                iconIv = ic; name = n; meta = m; role = rl; btn = b;
            }
        }
    }
}
