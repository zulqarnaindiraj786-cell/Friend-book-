package com.example.frienbook.post;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Format;
import com.example.frienbook.core.Notify;
import com.example.frienbook.core.Prefs;
import com.example.frienbook.core.Ui;
import com.example.frienbook.models.Models;
import com.example.frienbook.story.StoriesAdapter;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The feed: header (stories rail + composer), post cards, load-more footer. */
public class FeedAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface Host {
        void openComments(Models.Post post);
        void openProfile(String uid);
        void editPost(Models.Post post);
        void createStory();
        void openStoryGroup(StoriesAdapter.Group g);
        void loadMore();
        boolean hasMore();
    }

    private static final int VT_HEADER = 0, VT_POST = 1, VT_FOOTER = 2;

    private final Activity activity;
    private final Host host;
    private final List<Models.Post> posts = new ArrayList<>();
    private List<StoriesAdapter.Group> storyGroups = new ArrayList<>();
    public boolean showHeader = true;
    public boolean showFooter = true;

    public FeedAdapter(Activity a, Host h) {
        this.activity = a;
        this.host = h;
    }

    public void setPosts(List<Models.Post> list) {
        posts.clear();
        posts.addAll(list);
        notifyDataSetChanged();
    }

    public void addPosts(List<Models.Post> list) {
        int start = posts.size();
        posts.addAll(list);
        notifyItemRangeInserted(offset() + start, list.size());
    }

    public void setStories(List<StoriesAdapter.Group> groups) {
        storyGroups = groups == null ? new ArrayList<>() : groups;
        if (showHeader) notifyItemChanged(0);
    }

    private int offset() { return showHeader ? 1 : 0; }
    private int footerCount() { return showFooter ? 1 : 0; }

    @Override public int getItemCount() { return offset() + posts.size() + footerCount(); }

    @Override public int getItemViewType(int position) {
        if (showHeader && position == 0) return VT_HEADER;
        if (showFooter && position == getItemCount() - 1) return VT_FOOTER;
        return VT_POST;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Context c = parent.getContext();
        if (viewType == VT_HEADER) return new HeaderVH(c);
        if (viewType == VT_FOOTER) return new FooterVH(c);
        return new PostVH(c);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
        if (h instanceof HeaderVH) {
            HeaderVH hh = (HeaderVH) h;
            hh.storiesAdapter.setData(storyGroups);
            hh.storiesAdapter.listener = new StoriesAdapter.Listener() {
                @Override public void onGroup(StoriesAdapter.Group g) { if (host != null) host.openStoryGroup(g); }
                @Override public void onCreate() { if (host != null) host.createStory(); }
            };
            Ui.avatar(activity, hh.composeAvatar, Fb.myPhoto());
            hh.composeHint.setText("What's on your mind, " + firstName() + "?");
        } else if (h instanceof FooterVH) {
            FooterVH fh = (FooterVH) h;
            boolean more = host != null && host.hasMore();
            fh.more.setVisibility(more ? View.VISIBLE : View.GONE);
            fh.end.setVisibility(more ? View.GONE : View.VISIBLE);
            fh.more.setOnClickListener(v -> { if (host != null) host.loadMore(); });
        } else {
            bindPost((PostVH) h, posts.get(position - offset()));
        }
    }

    private String firstName() {
        String n = Fb.myName();
        int i = n.indexOf(' ');
        return i > 0 ? n.substring(0, i) : n;
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder h) {
        super.onViewRecycled(h);
        if (h instanceof PostVH) {
            PostVH vh = (PostVH) h;
            if (vh.reactReg != null) { vh.reactReg.remove(); vh.reactReg = null; }
            vh.video.stopPlayback();
            vh.video.setVisibility(View.GONE);
            vh.playBadge.setVisibility(View.GONE);
            vh.mediaBox.setOnTouchListener(null);
        }
    }

    // ================= POST BIND =================

    private void bindPost(PostVH vh, Models.Post p) {
        Context c = activity;
        Ui.avatar(c, vh.avatar, p.authorPhoto);
        boolean hasFeeling = p.feeling != null && !p.feeling.isEmpty();
        vh.name.setText(p.authorName + (hasFeeling ? " is feeling " + p.feeling : ""));
        String icon = "🌐";
        if ("Friends".equalsIgnoreCase(p.privacy)) icon = "👥";
        if ("Only me".equalsIgnoreCase(p.privacy)) icon = "🔒";
        vh.meta.setText(Format.ago(p.createdAt) + " · " + icon
                + (p.sharedFromId != null && !p.sharedFromId.isEmpty() ? " · shared" : ""));
        vh.content.setText(p.content);
        vh.content.setVisibility(p.content == null || p.content.isEmpty() ? View.GONE : View.VISIBLE);

        View.OnClickListener prof = v -> { if (host != null) host.openProfile(p.authorId); };
        vh.avatar.setOnClickListener(prof);
        vh.name.setOnClickListener(prof);

        // shared embed
        boolean isShared = p.sharedFromId != null && !p.sharedFromId.isEmpty();
        vh.sharedBox.setVisibility(isShared ? View.VISIBLE : View.GONE);
        if (isShared) {
            vh.sharedName.setText("Originally shared by " + (p.sharedFromAuthor == null ? "" : p.sharedFromAuthor));
            vh.sharedContent.setText(p.sharedFromContent);
            vh.sharedBox.setOnClickListener(v -> {
                Models.Post orig = new Models.Post();
                orig.id = p.sharedFromId;
                if (host != null) host.openComments(orig);
            });
        }

        // media
        vh.video.stopPlayback();
        vh.video.setVisibility(View.GONE);
        vh.playBadge.setVisibility(View.GONE);
        vh.image.setVisibility(View.GONE);
        vh.mediaBox.setOnClickListener(null);
        vh.mediaBox.setOnTouchListener(null);
        boolean hasMedia = p.mediaType != null && !"none".equals(p.mediaType)
                && p.mediaUrl != null && !p.mediaUrl.isEmpty();
        ViewGroup.LayoutParams mlp = vh.mediaBox.getLayoutParams();
        mlp.height = Ui.dp(c, hasMedia ? 280 : 0);
        vh.mediaBox.setLayoutParams(mlp);
        vh.mediaBox.setVisibility(hasMedia ? View.VISIBLE : View.GONE);
        if ("image".equals(p.mediaType) && p.mediaUrl != null && !p.mediaUrl.isEmpty()) {
            vh.image.setVisibility(View.VISIBLE);
            Ui.img(c, vh.image, p.mediaUrl);
            GestureDetector gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }
                @Override public boolean onDoubleTap(MotionEvent e) {
                    Ui.haptic(vh.image);
                    Reactions.tapLike(c, p);
                    Ui.toast(c, Reactions.emojiFor("love") + " Liked");
                    return true;
                }
                @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                    Intent i = new Intent(c, FullscreenMediaActivity.class);
                    i.putExtra("type", "image");
                    i.putExtra("url", p.mediaUrl);
                    c.startActivity(i);
                    return true;
                }
            });
            vh.mediaBox.setOnTouchListener((v, ev) -> gd.onTouchEvent(ev));
        } else if ("video".equals(p.mediaType) && p.mediaUrl != null && !p.mediaUrl.isEmpty()) {
            vh.playBadge.setText("▶");
            vh.playBadge.setTextSize(34);
            if (Prefs.dataSaver(c)) {
                vh.image.setVisibility(View.VISIBLE);
                vh.image.setImageDrawable(Ui.round(c, 0xFF222222, 0));
                vh.playBadge.setText("▶  Load video (Data Saver)");
                vh.playBadge.setTextSize(15);
                vh.playBadge.setVisibility(View.VISIBLE);
                vh.mediaBox.setOnClickListener(v -> { vh.playBadge.setText("⏳"); streamVideo(vh, p.mediaUrl, true); });
            } else {
                vh.playBadge.setVisibility(View.VISIBLE);
                vh.video.setVisibility(View.VISIBLE);
                vh.video.setVideoURI(android.net.Uri.parse(p.mediaUrl));
                vh.video.setOnPreparedListener(mp -> { mp.setLooping(true); vh.video.seekTo(1); });
                vh.mediaBox.setOnClickListener(v -> toggleVideo(vh));
            }
        } else {
            vh.mediaBox.setOnClickListener(v -> { if (host != null) host.openComments(p); });
        }

        vh.like.setOnClickListener(v -> { Ui.haptic(v); Reactions.tapLike(c, p); });
        vh.like.setOnLongClickListener(v -> {
            Reactions.show(vh.like, type -> Reactions.apply(c, p, type));
            return true;
        });
        vh.comment.setOnClickListener(v -> { if (host != null) host.openComments(p); });
        vh.content.setOnClickListener(v -> { if (host != null) host.openComments(p); });
        vh.share.setOnClickListener(v -> shareDialog(p));
        vh.more.setOnClickListener(v -> postMenu(vh.more, p));

        // realtime reaction summary
        if (vh.reactReg != null) vh.reactReg.remove();
        String me = Fb.uid();
        vh.reactReg = Fb.db().collection("posts").document(p.id).collection("reactions")
                .addSnapshotListener((snap, err) -> {
                    Map<String, Integer> agg = new HashMap<>();
                    String mine = null;
                    int total = 0;
                    if (snap != null) {
                        for (com.google.firebase.firestore.DocumentSnapshot d : snap) {
                            String t = d.getString("type");
                            if (t == null) t = "like";
                            Integer x = agg.get(t);
                            agg.put(t, x == null ? 1 : x + 1);
                            total++;
                            if (d.getId().equals(me)) mine = t;
                        }
                    }
                    StringBuilder em = new StringBuilder();
                    for (String t : Reactions.TYPES) if (agg.containsKey(t)) em.append(Reactions.emojiFor(t));
                    vh.rEmojis.setText(total == 0 ? "" : em.toString());
                    vh.rCount.setText(total == 0 ? "" : Format.count(total));
                    String right = "";
                    if (p.comments > 0) right += Format.count(p.comments) + " comments";
                    if (p.shares > 0) right += (right.isEmpty() ? "" : " · ") + Format.count(p.shares) + " shares";
                    vh.rRight.setText(right);
                    if (mine != null && !mine.isEmpty()) {
                        vh.like.setText(Reactions.emojiFor(mine) + " " + Reactions.labelFor(mine));
                        vh.like.setTextColor(Reactions.colorFor(mine));
                    } else {
                        vh.like.setText("👍 Like");
                        vh.like.setTextColor(Ui.sub(c));
                    }
                });
    }

    private void streamVideo(PostVH vh, String url, boolean autoPlay) {
        vh.video.setVisibility(View.VISIBLE);
        vh.video.setVideoURI(android.net.Uri.parse(url));
        vh.video.setOnPreparedListener(mp -> {
            mp.setLooping(true);
            if (autoPlay) { vh.video.start(); vh.playBadge.setVisibility(View.GONE); }
            else vh.video.seekTo(1);
        });
        vh.mediaBox.setOnClickListener(v -> toggleVideo(vh));
    }

    private void toggleVideo(PostVH vh) {
        if (vh.video.isPlaying()) { vh.video.pause(); vh.playBadge.setVisibility(View.VISIBLE); }
        else { vh.video.start(); vh.playBadge.setVisibility(View.GONE); }
    }

    // ---------- post overflow menu ----------
    private void postMenu(View anchor, Models.Post p) {
        Context c = activity;
        boolean mine = p.authorId != null && p.authorId.equals(Fb.uid());
        boolean saved = p.savedBy != null && p.savedBy.contains(Fb.uid());
        PopupMenu menu = new PopupMenu(c, anchor);
        menu.getMenu().add(saved ? "🔖 Unsave post" : "🔖 Save post");
        if (mine) {
            menu.getMenu().add("✏️ Edit post");
            menu.getMenu().add("🗑 Delete post");
        } else {
            menu.getMenu().add("➕ Follow " + p.authorName);
            menu.getMenu().add("🚫 Block " + p.authorName);
        }
        menu.getMenu().add("📋 Copy text");
        if (!mine) menu.getMenu().add("🚩 Report post");
        menu.setOnMenuItemClickListener(item -> {
            String t = item.getTitle().toString();
            if (t.contains("Unsave")) toggleSave(p, true);
            else if (t.contains("Save post")) toggleSave(p, false);
            else if (t.contains("Edit")) { if (host != null) host.editPost(p); }
            else if (t.contains("Delete")) deletePost(p);
            else if (t.contains("Copy")) copyText(p.content);
            else if (t.contains("Follow")) followUser(p.authorId);
            else if (t.contains("Block")) blockUser(p.authorId);
            else if (t.contains("Report")) reportPost(p);
            return true;
        });
        menu.show();
    }

    private void toggleSave(Models.Post p, boolean unsave) {
        String me = Fb.uid();
        if (me == null) return;
        Fb.db().collection("posts").document(p.id)
                .update("savedBy", unsave ? FieldValue.arrayRemove(me) : FieldValue.arrayUnion(me))
                .addOnSuccessListener(x -> Ui.toast(activity, unsave ? "Removed from Saved" : "Saved ✓"));
    }

    private void deletePost(Models.Post p) {
        Ui.confirm(activity, "Delete post?", "This can't be undone.", "Delete", () -> {
            Fb.db().collection("posts").document(p.id).delete();
            Fb.db().collection("comments").whereEqualTo("postId", p.id).get()
                    .addOnSuccessListener((QuerySnapshot snap) -> {
                        for (com.google.firebase.firestore.DocumentSnapshot d : snap) d.getReference().delete();
                    });
            Fb.db().collection("posts").document(p.id).collection("reactions").get()
                    .addOnSuccessListener(snap -> {
                        for (com.google.firebase.firestore.DocumentSnapshot d : snap) d.getReference().delete();
                    });
            Ui.toast(activity, "Post deleted");
        });
    }

    private void copyText(String text) {
        if (text == null) return;
        ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("post", text));
        Ui.toast(activity, "Copied");
    }

    private void followUser(String uid) {
        String me = Fb.uid();
        if (me == null || uid == null || uid.isEmpty()) return;
        Fb.db().collection("users").document(uid)
                .set(Fb.map("followers", FieldValue.arrayUnion(me)), SetOptions.merge());
        Fb.db().collection("users").document(me)
                .set(Fb.map("following", FieldValue.arrayUnion(uid)), SetOptions.merge());
        Notify.send(uid, "follow", "started following you");
        Ui.toast(activity, "Following ✓");
    }

    private void blockUser(String uid) {
        Ui.confirm(activity, "Block this user?", "They won't be able to see your posts or message you.",
                "Block", () -> {
                    String me = Fb.uid();
                    if (me == null) return;
                    Fb.db().collection("users").document(me)
                            .set(Fb.map("blockedUsers", FieldValue.arrayUnion(uid)), SetOptions.merge());
                    Ui.toast(activity, "Blocked");
                });
    }

    private void reportPost(Models.Post p) {
        Map<String, Object> m = Fb.map(
                "postId", p.id, "reportedBy", Fb.uid(), "authorId", p.authorId,
                "createdAt", FieldValue.serverTimestamp());
        Fb.db().collection("reports").add(m);
        Ui.toast(activity, "Reported. We'll review it.");
    }

    // ---------- share ----------
    private void shareDialog(Models.Post p) {
        String[] opts = {"📤 Share to your feed", "📲 Share via apps…", "📋 Copy text"};
        Ui.choose(activity, "Share post", opts, which -> {
            if (which == 0) shareToFeedDialog(p);
            else if (which == 1) {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, p.authorName + ": " + p.content);
                activity.startActivity(Intent.createChooser(i, "Share post"));
            } else copyText(p.content);
        });
    }

    private void shareToFeedDialog(Models.Post p) {
        Context c = activity;
        LinearLayout box = Ui.col(c);
        Ui.pad(box, 18, 10, 18, 0);
        EditText caption = Ui.multi(c, "Say something about this…", 2);
        box.addView(caption, new LinearLayout.LayoutParams(-1, -2));
        new android.app.AlertDialog.Builder(c)
                .setTitle("Share to your feed")
                .setView(box)
                .setPositiveButton("Share now", (d, w) -> {
                    String baseId = p.sharedFromId != null && !p.sharedFromId.isEmpty() ? p.sharedFromId : p.id;
                    Map<String, Object> m = Fb.map(
                            "authorId", Fb.uid(),
                            "authorName", Fb.myName(),
                            "authorPhotoUrl", Fb.myPhoto(),
                            "content", caption.getText().toString().trim(),
                            "mediaType", "none",
                            "mediaUrl", "",
                            "feeling", "",
                            "privacy", Prefs.defaultPrivacy(c),
                            "likes", 0, "comments", 0, "shares", 0,
                            "savedBy", new ArrayList<String>(),
                            "sharedFromId", baseId,
                            "sharedFromAuthor", p.sharedFromId != null && !p.sharedFromId.isEmpty() ? p.sharedFromAuthor : p.authorName,
                            "sharedFromContent", p.sharedFromId != null && !p.sharedFromId.isEmpty() ? p.sharedFromContent : p.content,
                            "sharedFromMediaUrl", p.sharedFromId != null && !p.sharedFromId.isEmpty() ? p.sharedFromMediaUrl : (p.mediaUrl == null ? "" : p.mediaUrl),
                            "sharedFromMediaType", p.sharedFromId != null && !p.sharedFromId.isEmpty() ? p.sharedFromMediaType : p.mediaType,
                            "createdAt", FieldValue.serverTimestamp());
                    Fb.db().collection("posts").add(m);
                    Fb.db().collection("posts").document(p.id).update("shares", FieldValue.increment(1));
                    Notify.send(p.authorId, "share", "shared your post", p.id);
                    Ui.toast(c, "Shared to your feed ✓");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ================= ViewHolders (views built in constructors) =================

    class HeaderVH extends RecyclerView.ViewHolder {
        final StoriesAdapter storiesAdapter;
        final ImageView composeAvatar;
        final TextView composeHint;

        HeaderVH(Context c) {
            super(Ui.col(c));
            LinearLayout col = (LinearLayout) itemView;
            col.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));

            RecyclerView rail = new RecyclerView(c);
            rail.setLayoutManager(new LinearLayoutManager(c, RecyclerView.HORIZONTAL, false));
            Ui.pad(rail, 6, 6, 6, 6);
            storiesAdapter = new StoriesAdapter();
            rail.setAdapter(storiesAdapter);
            col.addView(rail, new LinearLayout.LayoutParams(-1, Ui.dp(c, 150)));
            col.addView(Ui.divider(c));

            LinearLayout row = Ui.row(c);
            row.setBackgroundColor(Ui.card(c));
            Ui.pad(row, 12, 10, 12, 10);
            composeAvatar = new ImageView(c);
            int s = Ui.dp(c, 40);
            row.addView(composeAvatar, new LinearLayout.LayoutParams(s, s));
            composeHint = Ui.tv(c, "What's on your mind?", 15, Ui.sub(c));
            composeHint.setBackground(Ui.roundStroke(c, Ui.inputBg(c), 22, Ui.div(c), 1));
            composeHint.setPadding(Ui.dp(c, 14), Ui.dp(c, 9), Ui.dp(c, 14), Ui.dp(c, 9));
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, -2, 1);
            hp.leftMargin = Ui.dp(c, 10);
            row.addView(composeHint, hp);
            TextView photo = Ui.tv(c, "🖼️", 22, Ui.GREEN);
            photo.setPadding(Ui.dp(c, 10), 0, 0, 0);
            row.addView(photo);
            View.OnClickListener go = v -> c.startActivity(new Intent(c, CreatePostActivity.class));
            composeHint.setOnClickListener(go);
            composeAvatar.setOnClickListener(go);
            photo.setOnClickListener(go);
            col.addView(row);
            col.addView(Ui.divider(c));
            View gap = new View(c);
            gap.setBackgroundColor(Ui.bg(c));
            col.addView(gap, new LinearLayout.LayoutParams(-1, Ui.dp(c, 8)));
        }
    }

    class FooterVH extends RecyclerView.ViewHolder {
        final TextView more, end;
        FooterVH(Context c) {
            super(Ui.col(c));
            LinearLayout col = (LinearLayout) itemView;
            col.setGravity(Gravity.CENTER);
            Ui.pad(col, 16, 12, 16, 16);
            col.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            more = Ui.btnGrey(c, "Load more posts");
            col.addView(more, new LinearLayout.LayoutParams(-1, Ui.dp(c, 42)));
            end = Ui.tv(c, "You're all caught up 🎉", 13, Ui.sub(c));
            end.setGravity(Gravity.CENTER);
            Ui.pad(end, 0, 10, 0, 0);
            col.addView(end, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    static class PostVH extends RecyclerView.ViewHolder {
        ImageView avatar, image;
        TextView name, meta, more, content, like, comment, share;
        TextView sharedName, sharedContent, rEmojis, rCount, rRight, playBadge;
        LinearLayout sharedBox;
        FrameLayout mediaBox;
        VideoView video;
        ListenerRegistration reactReg;

        PostVH(Context c) {
            super(cardRoot(c));
            LinearLayout card = (LinearLayout) itemView;

            // header
            LinearLayout head = Ui.row(c);
            Ui.pad(head, 12, 10, 8, 6);
            avatar = new ImageView(c);
            int s = Ui.dp(c, 42);
            head.addView(avatar, new LinearLayout.LayoutParams(s, s));
            LinearLayout nameCol = Ui.col(c);
            LinearLayout.LayoutParams ncp = new LinearLayout.LayoutParams(0, -2, 1);
            ncp.leftMargin = Ui.dp(c, 10);
            name = Ui.tvBold(c, "", 15, Ui.txt(c));
            meta = Ui.tv(c, "", 12, Ui.sub(c));
            nameCol.addView(name);
            nameCol.addView(meta);
            head.addView(nameCol, ncp);
            more = Ui.tv(c, "•••", 16, Ui.sub(c));
            more.setPadding(Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6));
            head.addView(more);
            card.addView(head);

            content = Ui.tv(c, "", 15, Ui.txt(c));
            Ui.pad(content, 12, 4, 12, 8);
            card.addView(content);

            // shared embed
            sharedBox = Ui.col(c);
            sharedBox.setBackground(Ui.roundStroke(c, Ui.bg(c), 10, Ui.div(c), 1));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
            sp.setMargins(Ui.dp(c, 12), 0, Ui.dp(c, 12), Ui.dp(c, 8));
            sharedBox.setLayoutParams(sp);
            Ui.pad(sharedBox, 10, 10, 10, 10);
            sharedName = Ui.tvBold(c, "", 13, Ui.txt(c));
            sharedContent = Ui.tv(c, "", 13, Ui.sub(c));
            sharedContent.setMaxLines(4);
            sharedBox.addView(sharedName);
            sharedBox.addView(sharedContent);
            card.addView(sharedBox);

            // media
            mediaBox = new FrameLayout(c);
            card.addView(mediaBox, new LinearLayout.LayoutParams(-1, Ui.dp(c, 280)));
            image = new ImageView(c);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            mediaBox.addView(image, new FrameLayout.LayoutParams(-1, -1));
            video = new VideoView(c);
            video.setVisibility(View.GONE);
            mediaBox.addView(video, new FrameLayout.LayoutParams(-1, -1));
            playBadge = Ui.tv(c, "▶", 34, 0xEEFFFFFF);
            playBadge.setGravity(Gravity.CENTER);
            playBadge.setVisibility(View.GONE);
            mediaBox.addView(playBadge, new FrameLayout.LayoutParams(-1, -1));

            // reaction summary
            LinearLayout summary = Ui.row(c);
            Ui.pad(summary, 12, 6, 12, 6);
            rEmojis = Ui.tv(c, "", 13, Ui.sub(c));
            rCount = Ui.tv(c, "", 13, Ui.sub(c));
            rCount.setPadding(Ui.dp(c, 4), 0, 0, 0);
            rRight = Ui.tv(c, "", 13, Ui.sub(c));
            rRight.setGravity(Gravity.END);
            summary.addView(rEmojis);
            summary.addView(rCount);
            summary.addView(rRight, new LinearLayout.LayoutParams(0, -2, 1));
            card.addView(summary);
            card.addView(Ui.divider(c));

            // actions
            LinearLayout actions = Ui.row(c);
            Ui.pad(actions, 6, 2, 6, 2);
            like = actionBtn(c, "👍 Like");
            comment = actionBtn(c, "💬 Comment");
            share = actionBtn(c, "↗ Share");
            actions.addView(like, new LinearLayout.LayoutParams(0, -2, 1));
            actions.addView(comment, new LinearLayout.LayoutParams(0, -2, 1));
            actions.addView(share, new LinearLayout.LayoutParams(0, -2, 1));
            card.addView(actions);

            View gap = new View(c);
            gap.setBackgroundColor(Ui.bg(c));
            card.addView(gap, new LinearLayout.LayoutParams(-1, Ui.dp(c, 8)));
        }

        private static View cardRoot(Context c) {
            LinearLayout card = Ui.col(c);
            card.setBackgroundColor(Ui.card(c));
            card.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            return card;
        }

        private static TextView actionBtn(Context c, String label) {
            TextView t = Ui.tvBold(c, label, 13, Ui.sub(c));
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(c, 9), 0, Ui.dp(c, 9));
            return t;
        }
    }
}
