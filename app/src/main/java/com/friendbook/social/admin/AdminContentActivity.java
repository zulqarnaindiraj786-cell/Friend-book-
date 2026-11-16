package com.friendbook.social.admin;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.social.core.Admin;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Ui;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import java.util.ArrayList;
import java.util.List;

/** Admin content moderation: review & delete posts, reels, stories, comments. */
public class AdminContentActivity extends Activity {

    private static final String[] TABS = {"posts", "reels", "stories", "comments"};
    private static final String[] TAB_LABELS = {"Posts", "Reels", "Stories", "Comments"};
    private final TextView[] tabViews = new TextView[4];
    private String tab = "posts";
    private ContentAdapter adapter;
    private ListenerRegistration reg;
    private TextView empty;

    static class Item {
        String id, title, sub, img;
    }

    private final List<Item> items = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Admin.isAdmin()) { finish(); return; }
        Ui.statusBarBlue(this);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "🗂 Content moderation"));

        LinearLayout tabs = Ui.row(this);
        Ui.pad(tabs, 12, 6, 12, 6);
        for (int i = 0; i < TAB_LABELS.length; i++) {
            final int idx = i;
            TextView t = Ui.tvBold(this, TAB_LABELS[i], 14, Ui.sub(this));
            t.setGravity(Gravity.CENTER);
            Ui.pad(t, 8, 8, 8, 8);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) lp.leftMargin = Ui.dp(this, 6);
            tabs.addView(t, lp);
            tabViews[i] = t;
            t.setOnClickListener(v -> switchTab(TABS[idx]));
        }
        root.addView(tabs);

        empty = Ui.tv(this, "", 14, Ui.sub(this));
        empty.setGravity(Gravity.CENTER);
        Ui.pad(empty, 24, 50, 24, 0);
        root.addView(empty);

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ContentAdapter();
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        switchTab("posts");
    }

    private void switchTab(String t) {
        tab = t;
        for (int i = 0; i < TABS.length; i++) {
            boolean on = TABS[i].equals(t);
            tabViews[i].setBackground(Ui.round(this, on ? Ui.BLUE : Ui.inputBg(this), 16));
            tabViews[i].setTextColor(on ? 0xFFFFFFFF : Ui.sub(this));
        }
        listen();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
    }

    private void listen() {
        if (reg != null) reg.remove();
        String orderField = tab.equals("chats") ? "lastMessageTime" : "createdAt";
        // One read per tab. The tab is chosen by the moderator, so re-reading on selection is
        // the explicit action that refreshes it - no background subscription.
        com.friendbook.social.core.D1Snap.read(
                com.friendbook.social.core.DocQuery.of(tab)
                        .orderBy(orderField, com.friendbook.social.core.DocQuery.DESC)
                        .limit(100).build(), snap -> {
                    items.clear();
                    {
                        for (com.friendbook.social.core.Doc d : snap) {
                            Item it = new Item();
                            it.id = d.getId();
                            switch (tab) {
                                case "posts":
                                    it.title = Format.strOr(d.getData(), "authorName", "User");
                                    it.sub = trim(Format.str(d.getData(), "content"), 90);
                                    it.img = Format.str(d.getData(), "mediaUrl");
                                    break;
                                case "reels":
                                    it.title = Format.strOr(d.getData(), "authorName", "User");
                                    it.sub = trim(Format.str(d.getData(), "caption"), 90);
                                    break;
                                case "stories":
                                    it.title = Format.strOr(d.getData(), "authorName", "User");
                                    it.sub = "expires " + Format.ago(Format.ts(d.get("expiresAt")))
                                            + (Format.ts(d.get("expiresAt")) > System.currentTimeMillis() ? " left" : " (expired)");
                                    it.img = Format.str(d.getData(), "mediaUrl");
                                    break;
                                default: // comments
                                    it.title = Format.strOr(d.getData(), "authorName", "User");
                                    it.sub = trim(Format.str(d.getData(), "content"), 90);
                                    break;
                            }
                            items.add(it);
                        }
                    }
                    adapter.notifyDataSetChanged();
                    empty.setText("No " + tab + " found.");
                    empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                });
    }

    private String trim(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private void deleteItem(Item it) {
        Ui.confirm(this, "Delete this " + tab.substring(0, tab.length() - 1) + "?",
                it.title + "\n" + it.sub, "Delete", () -> {
                    Fb.db().collection(tab).document(it.id).delete();
                    if ("posts".equals(tab)) {
                        com.friendbook.social.core.QueryActions.deleteMatching(Fb.db().collection("comments").whereEqualTo("postId",it.id))
                                .addOnFailureListener(e->Ui.toast(this,"Some comments could not be cleaned up."));
                    }
                    Ui.toast(this, "Deleted ✓");
                });
    }

    class ContentAdapter extends RecyclerView.Adapter<ContentAdapter.VH> {
        @Override public int getItemCount() { return items.size(); }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = Ui.row(AdminContentActivity.this);
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            Ui.pad(row, 12, 10, 12, 10);
            row.setBackgroundColor(Ui.card(AdminContentActivity.this));

            ImageView img = new ImageView(AdminContentActivity.this);
            int s = Ui.dp(AdminContentActivity.this, 44);
            row.addView(img, new LinearLayout.LayoutParams(s, s));

            LinearLayout mid = Ui.col(AdminContentActivity.this);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1);
            mp.leftMargin = Ui.dp(AdminContentActivity.this, 12);
            TextView title = Ui.tvBold(AdminContentActivity.this, "", 14, Ui.txt(AdminContentActivity.this));
            TextView sub = Ui.tv(AdminContentActivity.this, "", 12, Ui.sub(AdminContentActivity.this));
            sub.setMaxLines(2);
            mid.addView(title);
            mid.addView(sub);
            row.addView(mid, mp);

            TextView del = Ui.btn(AdminContentActivity.this, "Delete");
            del.setMinHeight(Ui.dp(AdminContentActivity.this, 32));
            del.setTextSize(12);
            del.setBackground(Ui.round(AdminContentActivity.this, Ui.RED, 8));
            row.addView(del);
            return new VH(row, img, title, sub, del);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Item it = items.get(position);
            h.title.setText(it.title);
            h.sub.setText(it.sub);
            if (it.img != null && it.img.startsWith("http")
                    && (tab.equals("posts") || tab.equals("stories")))
                Ui.img(AdminContentActivity.this, h.img, it.img);
            else
                h.img.setImageDrawable(Ui.round(AdminContentActivity.this, Ui.inputBg(AdminContentActivity.this), 8));
            h.del.setOnClickListener(v -> deleteItem(it));
        }

        class VH extends RecyclerView.ViewHolder {
            ImageView img;
            TextView title, sub, del;
            VH(View v, ImageView i, TextView t, TextView s, TextView d) {
                super(v);
                img = i; title = t; sub = s; del = d;
            }
        }
    }
}
