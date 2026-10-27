package com.friendbook.settings;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.friendbook.core.Fb;
import com.friendbook.core.Format;
import com.friendbook.core.Ui;
import com.friendbook.models.Models;
import com.friendbook.post.PostDetailActivity;
import com.friendbook.story.StoryViewerActivity;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 📦 Archive & Trash — friendbook 525 full content lifecycle manager:
 * Tab 0: 📖 Story Archive (all past stories, even >24h, + Feature on Profile Highlights + Re-share)
 * Tab 1: 🗂 Post Archive (posts hidden from profile/feed, restorable anytime)
 * Tab 2: 🗑 Recycle Bin / Trash (30-day soft delete — Restore or Delete Forever)
 */
public class ArchiveTrashActivity extends Activity {

    private int activeTab = 0;
    private final TextView[] tabViews = new TextView[3];
    private LinearLayout listCol;
    private TextView bannerTv, emptyAllBtn;
    private ListenerRegistration postsReg, storiesReg;
    private final List<Models.Post> myPosts = new ArrayList<>();
    private final List<Models.Story> myStories = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        activeTab = getIntent().getIntExtra("tab", 0);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));

        LinearLayout bar = Ui.pageBar(this, "Archive & Recycle Bin");
        emptyAllBtn = Ui.roundChip(this, "Empty Trash", Ui.RED, Color.WHITE, 12);
        Ui.pad(emptyAllBtn, 10, 5, 10, 5);
        emptyAllBtn.setVisibility(View.GONE);
        emptyAllBtn.setOnClickListener(v -> emptyTrash());
        bar.addView(emptyAllBtn);
        root.addView(bar);

        // 3 friendbook style pill tabs
        String[] labels = {"📖 Story Archive", "🗂 Post Archive", "🗑 Trash"};
        LinearLayout tabs = Ui.row(this);
        tabs.setBackgroundColor(Ui.card(this));
        Ui.pad(tabs, 10, 8, 10, 8);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView t = Ui.tvBold(this, labels[i], 12, Ui.sub(this));
            t.setGravity(Gravity.CENTER);
            Ui.pad(t, 8, 8, 8, 8);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) lp.leftMargin = Ui.dp(this, 6);
            tabs.addView(t, lp);
            tabViews[i] = t;
            t.setOnClickListener(v -> { activeTab = idx; updateTabs(); render(); });
        }
        root.addView(tabs);
        root.addView(Ui.divider(this));

        bannerTv = Ui.tv(this, "", 12, Ui.sub(this));
        bannerTv.setBackgroundColor(Ui.card(this));
        Ui.pad(bannerTv, 14, 8, 14, 8);
        root.addView(bannerTv);
        root.addView(Ui.divider(this));

        ScrollView sv = new ScrollView(this);
        listCol = Ui.col(this);
        Ui.pad(listCol, 12, 10, 12, 24);
        sv.addView(listCol);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        updateTabs();
        setContentView(root);
    }

    private void updateTabs() {
        for (int i = 0; i < 3; i++) {
            boolean on = i == activeTab;
            tabViews[i].setBackground(Ui.round(this, on ? Ui.BLUE : Ui.inputBg(this), 18));
            tabViews[i].setTextColor(on ? Color.WHITE : Ui.sub(this));
        }
        if (activeTab == 0) {
            bannerTv.setText("Only you can see your Story Archive. Feature any story to Profile Highlights ✨");
            emptyAllBtn.setVisibility(View.GONE);
        } else if (activeTab == 1) {
            bannerTv.setText("Archived posts are hidden from your profile & Feed. Restore them anytime.");
            emptyAllBtn.setVisibility(View.GONE);
        } else {
            bannerTv.setText("Items in Recycle Bin are hidden from everyone and kept for 30 days.");
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        String me = Fb.uid();
        if (me == null) { finish(); return; }

        storiesReg = Fb.db().collection("stories").whereEqualTo("authorId", me)
                .addSnapshotListener((snap, err) -> {
                    myStories.clear();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap) myStories.add(Models.Story.fromDoc(d));
                        Collections.sort(myStories, (x, y) -> Long.compare(y.createdAt, x.createdAt));
                    }
                    render();
                });

        postsReg = Fb.db().collection("posts").whereEqualTo("authorId", me)
                .addSnapshotListener((snap, err) -> {
                    myPosts.clear();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap) myPosts.add(Models.Post.fromDoc(d));
                        Collections.sort(myPosts, (x, y) -> Long.compare(y.createdAt, x.createdAt));
                    }
                    render();
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (storiesReg != null) storiesReg.remove();
        if (postsReg != null) postsReg.remove();
    }

    private void render() {
        listCol.removeAllViews();
        if (activeTab == 0) renderStories();
        else if (activeTab == 1) renderArchivedPosts();
        else renderTrashedPosts();
    }

    private void renderStories() {
        emptyAllBtn.setVisibility(View.GONE);
        if (myStories.isEmpty()) {
            addEmpty("No stories in your archive yet.\nShare a Photo, Video or Text story — it stays saved here!");
            return;
        }
        List<String> highlights = Fb.me != null && Fb.me.highlightStoryIds != null
                ? Fb.me.highlightStoryIds : new ArrayList<>();
        for (Models.Story s : myStories) {
            LinearLayout card = Ui.row(this);
            card.setBackground(Ui.round(this, Ui.card(this), 12));
            Ui.pad(card, 10, 10, 10, 10);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.bottomMargin = Ui.dp(this, 8);
            card.setLayoutParams(cp);

            // Thumbnail or text story swatch
            if ("text".equals(s.mediaType) || s.mediaUrl == null || s.mediaUrl.isEmpty()) {
                TextView sw = Ui.tvBold(this, s.caption == null || s.caption.isEmpty() ? "Aa" : s.caption,
                        11, Color.WHITE);
                sw.setGravity(Gravity.CENTER);
                sw.setMaxLines(3);
                Ui.pad(sw, 6, 6, 6, 6);
                int bg = s.bgColor != 0 ? s.bgColor : Ui.BLUE;
                sw.setBackground(Ui.round(this, bg, 10));
                card.addView(sw, new LinearLayout.LayoutParams(Ui.dp(this, 60), Ui.dp(this, 82)));
            } else {
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                Ui.img(this, iv, s.mediaUrl);
                card.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 60), Ui.dp(this, 82)));
            }

            LinearLayout info = Ui.col(this);
            info.setPadding(Ui.dp(this, 12), 0, 0, 0);
            boolean isHi = highlights.contains(s.id);
            String title = (s.caption != null && !s.caption.isEmpty()) ? s.caption
                    : ("text".equals(s.mediaType) ? "Text story" : s.mediaType + " story");
            TextView tv = Ui.tvBold(this, title, 14, Ui.txt(this));
            tv.setMaxLines(2);
            info.addView(tv);
            info.addView(Ui.tv(this, Format.dateTime(s.createdAt)
                    + (s.expired() ? " · Archived" : " · Live now")
                    + " · 👁 " + s.viewedBy.size()
                    + (s.reactions.isEmpty() ? "" : " · ❤️ " + s.reactions.size()), 11, Ui.sub(this)));

            LinearLayout btns = Ui.row(this);
            btns.setPadding(0, Ui.dp(this, 8), 0, 0);
            TextView hiBtn = Ui.btnOutline(this, isHi ? "★ Highlighted" : "✨ Highlight",
                    isHi ? Ui.ORANGE : Ui.BLUE);
            hiBtn.setOnClickListener(v -> toggleHighlight(s, isHi));
            btns.addView(hiBtn);

            TextView reshare = Ui.btnGrey(this, "🔄 Re-share");
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, Ui.dp(this, 34));
            rp.leftMargin = Ui.dp(this, 6);
            reshare.setOnClickListener(v -> reshareStory(s));
            btns.addView(reshare, rp);

            TextView del = Ui.tvBold(this, "🗑", 16, Ui.RED);
            del.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 4), 0);
            del.setOnClickListener(v -> Ui.confirm(this, "Delete story?",
                    "This removes the story permanently from your archive.", "Delete",
                    () -> Fb.db().collection("stories").document(s.id).delete()));
            btns.addView(del);

            info.addView(btns);
            card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
            card.setOnClickListener(v -> {
                Intent i = new Intent(this, StoryViewerActivity.class);
                i.putExtra("author", s.authorId);
                i.putExtra("story_id", s.id);
                startActivity(i);
            });
            listCol.addView(card);
        }
    }

    private void toggleHighlight(Models.Story s, boolean currentlyHighlighted) {
        String me = Fb.uid();
        if (me == null) return;
        Fb.db().collection("users").document(me).update("highlightStoryIds",
                currentlyHighlighted ? FieldValue.arrayRemove(s.id) : FieldValue.arrayUnion(s.id))
                .addOnSuccessListener(x -> Ui.toast(this, currentlyHighlighted
                        ? "Removed from Profile Highlights" : "Added to Profile Highlights ✨"));
    }

    private void reshareStory(Models.Story s) {
        Map<String, Object> m = new HashMap<>();
        m.put("authorId", Fb.uid());
        m.put("authorName", Fb.myName());
        m.put("authorPhotoUrl", Fb.myPhoto());
        m.put("mediaUrl", s.mediaUrl == null ? "" : s.mediaUrl);
        m.put("mediaType", s.mediaType == null ? "text" : s.mediaType);
        m.put("caption", s.caption == null ? "" : s.caption);
        m.put("bgColor", s.bgColor);
        m.put("musicMood", s.musicMood == null ? "" : s.musicMood);
        m.put("viewedBy", new ArrayList<String>());
        m.put("createdAt", FieldValue.serverTimestamp());
        m.put("expiresAt", new Timestamp(new Date(System.currentTimeMillis() + 24L * 3600_000L)));
        Fb.db().collection("stories").add(m)
                .addOnSuccessListener(x -> Ui.toast(this, "Story re-shared for 24h 📖"));
    }

    private void renderArchivedPosts() {
        emptyAllBtn.setVisibility(View.GONE);
        List<Models.Post> list = new ArrayList<>();
        for (Models.Post p : myPosts) if (p.archived && p.trashedAt <= 0) list.add(p);
        if (list.isEmpty()) {
            addEmpty("No archived posts.\nTap ••• on any of your posts → Move to Archive.");
            return;
        }
        for (Models.Post p : list) listCol.addView(postRow(p, false));
    }

    private void renderTrashedPosts() {
        List<Models.Post> list = new ArrayList<>();
        for (Models.Post p : myPosts) if (p.trashedAt > 0) list.add(p);
        emptyAllBtn.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
        if (list.isEmpty()) {
            addEmpty("Recycle Bin is empty 🧹\nPosts moved to Trash stay here for 30 days before permanent deletion.");
            return;
        }
        for (Models.Post p : list) listCol.addView(postRow(p, true));
    }

    private View postRow(Models.Post p, boolean isTrash) {
        LinearLayout card = Ui.col(this);
        card.setBackground(Ui.round(this, Ui.card(this), 12));
        Ui.pad(card, 12, 12, 12, 12);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.bottomMargin = Ui.dp(this, 8);
        card.setLayoutParams(cp);

        String txt = (p.content != null && !p.content.isEmpty()) ? p.content
                : (p.isMarketplace() ? "🛒 " + p.marketTitle + " (" + p.marketPrice + ")"
                : "[" + p.mediaType + " post]");
        TextView body = Ui.tvBold(this, txt, 14, Ui.txt(this));
        body.setMaxLines(3);
        card.addView(body);

        long daysLeft = isTrash
                ? Math.max(1, 30 - ((System.currentTimeMillis() - p.trashedAt) / 86400_000L)) : 0;
        TextView sub = Ui.tv(this, Format.dateTime(p.createdAt)
                + (isTrash ? " · ⏳ " + daysLeft + " days left in Trash" : " · 🗂 Archived"),
                11, Ui.sub(this));
        sub.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        card.addView(sub);

        LinearLayout btns = Ui.row(this);
        TextView restore = Ui.btnOutline(this, "↩ Restore to profile", Ui.BLUE);
        restore.setOnClickListener(v -> {
            Map<String, Object> upd = new HashMap<>();
            upd.put("archived", false);
            upd.put("trashedAt", 0L);
            Fb.db().collection("posts").document(p.id).update(upd)
                    .addOnSuccessListener(x -> Ui.toast(this, "Restored to your profile ✓"));
        });
        btns.addView(restore);

        TextView second = Ui.btnOutline(this, isTrash ? "✕ Delete forever" : "🗑 Move to Trash", Ui.RED);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.leftMargin = Ui.dp(this, 8);
        second.setOnClickListener(v -> {
            if (isTrash) {
                Ui.confirm(this, "Delete forever?", "This post cannot be recovered.", "Delete",
                        () -> Fb.db().collection("posts").document(p.id).delete()
                                .addOnSuccessListener(x -> Ui.toast(this, "Deleted permanently")));
            } else {
                Fb.db().collection("posts").document(p.id)
                        .update("archived", false, "trashedAt", System.currentTimeMillis())
                        .addOnSuccessListener(x -> Ui.toast(this, "Moved to Recycle Bin 🗑"));
            }
        });
        btns.addView(second, sp);
        card.addView(btns);

        card.setOnClickListener(v -> {
            Intent i = new Intent(this, PostDetailActivity.class);
            i.putExtra("post_id", p.id);
            startActivity(i);
        });
        return card;
    }

    private void emptyTrash() {
        Ui.confirm(this, "Empty Recycle Bin?",
                "All posts in Recycle Bin will be permanently deleted. This cannot be undone.",
                "Empty Trash", () -> {
                    for (Models.Post p : myPosts) {
                        if (p.trashedAt > 0) Fb.db().collection("posts").document(p.id).delete();
                    }
                    Ui.toast(this, "Recycle Bin emptied 🧹");
                });
    }

    private void addEmpty(String msg) {
        TextView e = Ui.tv(this, msg, 14, Ui.sub(this));
        e.setGravity(Gravity.CENTER);
        Ui.pad(e, 24, 50, 24, 24);
        listCol.addView(e);
    }
}
