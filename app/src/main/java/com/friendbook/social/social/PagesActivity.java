package com.friendbook.social.social;

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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Icons;
import com.friendbook.social.core.Ui;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 🚩 PagesActivity — Full friendbook Pages Directory:
 * Tabs (Discover · Following · My Pages), Search, Create Page, Follow/Unfollow &
 * tap any Page to open PageDetailActivity (cover, Post-as-Page & Page Feed).
 */
public class PagesActivity extends Activity {

    static class Page {
        String id, name, category, bio, photoUrl, ownerId;
        List<String> followers = new ArrayList<>();
        String tick = "none";
    }

    private final List<Page> allPages = new ArrayList<>();
    private final List<Page> pages = new ArrayList<>();
    private String tab = "discover", query = "";
    private final TextView[] tabs = new TextView[3];
    private Adapter adapter;
    private ListenerRegistration reg;
    private TextView empty;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));

        LinearLayout bar = Ui.pageBar(this, "Pages");
        TextView create = Ui.roundChip(this, "＋ Create", Ui.inputBg(this), Ui.txt(this), 13);
        Ui.pad(create, 12, 6, 12, 6);
        create.setOnClickListener(v -> createDialog());
        bar.addView(create);
        root.addView(bar);

        // Tabs + search
        LinearLayout top = Ui.col(this);
        top.setBackgroundColor(Ui.card(this));
        Ui.pad(top, 10, 8, 10, 8);
        String[] labels = {"Discover", "Following", "My Pages"};
        String[] keys = {"discover", "following", "mine"};
        LinearLayout trow = Ui.row(this);
        for (int i = 0; i < 3; i++) {
            final String k = keys[i];
            TextView t = Ui.tvBold(this, labels[i], 13, Ui.sub(this));
            t.setGravity(Gravity.CENTER);
            Ui.pad(t, 10, 6, 10, 6);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) lp.leftMargin = Ui.dp(this, 6);
            trow.addView(t, lp);
            tabs[i] = t;
            t.setOnClickListener(v -> { tab = k; updateTabs(); applyFilter(); });
        }
        top.addView(trow);

        EditText search = Ui.input(this, "🔎 Search Pages by name or category…");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 40));
        sp.topMargin = Ui.dp(this, 8);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {
                query = s.toString().trim().toLowerCase();
                applyFilter();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        top.addView(search, sp);
        root.addView(top);
        root.addView(Ui.divider(this));
        updateTabs();

        empty = Ui.tv(this, "No Pages match.\nTap ＋ Create to launch your brand, creator or shop Page!", 14, Ui.sub(this));
        empty.setGravity(Gravity.CENTER);
        Ui.pad(empty, 24, 60, 24, 0);
        root.addView(empty);

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void updateTabs() {
        String[] keys = {"discover", "following", "mine"};
        for (int i = 0; i < 3; i++) {
            boolean on = keys[i].equals(tab);
            tabs[i].setBackground(Ui.round(this, on ? Ui.BLUE : Ui.inputBg(this), 16));
            tabs[i].setTextColor(on ? Color.WHITE : Ui.sub(this));
        }
    }

    private void createDialog() {
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 18, 12, 18, 4);
        EditText name = Ui.input(this, "Page name (e.g. Ali's Photography)");
        EditText cat = Ui.input(this, "Category (Creator · Brand · News · Shop)");
        EditText bio = Ui.input(this, "Short description");
        bio.setMinLines(2);
        for (View v : new View[]{name, cat, bio}) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.bottomMargin = Ui.dp(this, 8);
            box.addView(v, p);
        }
        new AlertDialog.Builder(this)
                .setTitle("Create a Page")
                .setView(box)
                .setPositiveButton("Create", (dlg, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) { Ui.toast(this, "Enter a Page name"); return; }
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", n);
                    m.put("category", cat.getText().toString().trim().isEmpty() ? "Page" : cat.getText().toString().trim());
                    m.put("bio", bio.getText().toString().trim());
                    m.put("photoUrl", Fb.myPhoto());
                    m.put("ownerId", Fb.uid());
                    m.put("followers", Collections.singletonList(Fb.uid()));
                    m.put("tickLevel", "none");
                    m.put("createdAt", FieldValue.serverTimestamp());
                    Fb.db().collection("pages").add(m).addOnSuccessListener(ref -> {
                        Ui.toast(this, "Page created 🚩");
                        Intent i = new Intent(this, PageDetailActivity.class);
                        i.putExtra("page_id", ref.getId());
                        startActivity(i);
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onStart() {
        super.onStart();
        reg = Fb.db().collection("pages").limit(100)
                .addSnapshotListener((snap, err) -> {
                    allPages.clear();
                    if (snap != null) for (DocumentSnapshot d : snap) {
                        Page p = new Page();
                        p.id = d.getId();
                        p.name = d.getString("name");
                        p.category = d.getString("category");
                        p.bio = d.getString("bio");
                        p.photoUrl = d.getString("photoUrl");
                        p.ownerId = d.getString("ownerId");
                        p.followers = Format.listOf(d.getData(), "followers");
                        p.tick = d.getString("tickLevel") == null ? "none" : d.getString("tickLevel");
                        allPages.add(p);
                    }
                    Collections.sort(allPages, (a, b2) -> Integer.compare(b2.followers.size(), a.followers.size()));
                    applyFilter();
                });
    }

    private void applyFilter() {
        pages.clear();
        String me = Fb.uid();
        for (Page p : allPages) {
            if ("following".equals(tab) && (me == null || !p.followers.contains(me))) continue;
            if ("mine".equals(tab) && (me == null || !me.equals(p.ownerId))) continue;
            if (!query.isEmpty()) {
                String hay = ((p.name == null ? "" : p.name) + " "
                        + (p.category == null ? "" : p.category)).toLowerCase();
                if (!hay.contains(query)) continue;
            }
            pages.add(p);
        }
        adapter.notifyDataSetChanged();
        empty.setVisibility(pages.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
    }

    class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        @Override public int getItemCount() { return pages.size(); }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
            LinearLayout card = Ui.row(PagesActivity.this);
            RecyclerView.LayoutParams p = new RecyclerView.LayoutParams(-1, -2);
            p.bottomMargin = Ui.dp(PagesActivity.this, 6);
            card.setLayoutParams(p);
            card.setBackgroundColor(Ui.card(PagesActivity.this));
            Ui.pad(card, 14, 12, 14, 12);

            ImageView av = new ImageView(PagesActivity.this);
            card.addView(av, new LinearLayout.LayoutParams(Ui.dp(PagesActivity.this, 50), Ui.dp(PagesActivity.this, 50)));

            LinearLayout mid = Ui.col(PagesActivity.this);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1);
            mp.leftMargin = Ui.dp(PagesActivity.this, 12);
            LinearLayout nrow = Ui.row(PagesActivity.this);
            TextView name = Ui.tvBold(PagesActivity.this, "", 15, Ui.txt(PagesActivity.this));
            ImageView tick = new ImageView(PagesActivity.this);
            tick.setVisibility(View.GONE);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(Ui.dp(PagesActivity.this, 15), Ui.dp(PagesActivity.this, 15));
            tp.leftMargin = Ui.dp(PagesActivity.this, 4);
            nrow.addView(name);
            nrow.addView(tick, tp);
            TextView cat = Ui.tv(PagesActivity.this, "", 12, Ui.sub(PagesActivity.this));
            TextView bio = Ui.tv(PagesActivity.this, "", 12, Ui.txt(PagesActivity.this));
            bio.setMaxLines(2);
            mid.addView(nrow);
            mid.addView(cat);
            mid.addView(bio);
            card.addView(mid, mp);

            TextView btn = Ui.tvBold(PagesActivity.this, "", 13, Color.WHITE);
            Ui.pad(btn, 14, 7, 14, 7);
            card.addView(btn);
            return new VH(card, av, name, tick, cat, bio, btn);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int i) {
            Page p = pages.get(i);
            Ui.avatar(PagesActivity.this, h.av, p.photoUrl);
            h.name.setText(p.name);
            Icons.setTickBadge(h.tick, p.tick, false);
            h.cat.setText(p.category + " · " + Format.count(p.followers.size()) + " followers");
            h.bio.setText(p.bio == null ? "" : p.bio);
            h.bio.setVisibility(p.bio == null || p.bio.isEmpty() ? View.GONE : View.VISIBLE);

            boolean following = Fb.uid() != null && p.followers.contains(Fb.uid());
            h.btn.setText(following ? "Following" : "Follow");
            h.btn.setTextColor(following ? Ui.txt(PagesActivity.this) : Color.WHITE);
            h.btn.setBackground(Ui.round(PagesActivity.this, following ? Ui.inputBg(PagesActivity.this) : Ui.BLUE, 8));
            h.btn.setOnClickListener(v -> {
                String me = Fb.uid();
                if (me == null) return;
                com.friendbook.social.core.DataRouter.mirrorPut("pages", p.id, "followers", following ? FieldValue.arrayRemove(me) : FieldValue.arrayUnion(me));
                Fb.db().collection("pages").document(p.id).update("followers",
                        following ? FieldValue.arrayRemove(me) : FieldValue.arrayUnion(me));
            });
            h.itemView.setOnClickListener(v -> {
                Intent open = new Intent(PagesActivity.this, PageDetailActivity.class);
                open.putExtra("page_id", p.id);
                startActivity(open);
            });
        }

        class VH extends RecyclerView.ViewHolder {
            ImageView av, tick;
            TextView name, cat, bio, btn;
            VH(View v, ImageView a, TextView n, ImageView t, TextView c, TextView b, TextView bt) {
                super(v); av = a; name = n; tick = t; cat = c; bio = b; btn = bt;
            }
        }
    }
}
