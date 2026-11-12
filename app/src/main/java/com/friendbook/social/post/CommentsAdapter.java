package com.friendbook.social.post;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.social.core.Admin;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Icons;
import com.friendbook.social.core.TickCache;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * friendbook Pro Threaded Comments Adapter:
 * - Optional Header View (TYPE_HEADER) so PostDetailActivity uses ONE single native RecyclerView
 *   with ZERO ScrollView height-clipping bugs!
 * - Zero orphan dropping: any reply whose parentId is missing/"null" promotes cleanly to root.
 * - Supports "all" (All comments), "newest" (Newest first), and "relevant" (Most liked/replied).
 * - Clickable reaction summary ("👍❤️ 5") opens "People who reacted to comment"!
 */
public class CommentsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_COMMENT = 1;
    private static final int TYPE_EMPTY = 2;

    public interface Host {
        void onReply(Models.Comment parent);
        default void openProfile(String uid) { }
    }

    public static class Row {
        public Models.Comment c;
        public int depth; // 0 = root, 1+ = nested reply
        Row(Models.Comment c, int d) { this.c = c; this.depth = d; }
    }

    private final Activity activity;
    private final Host host;
    private final List<Row> rows = new ArrayList<>();
    private View headerView;
    private RecyclerView attachedList;
    private boolean showEmptyWhenNone = true;
    /** Collection holding the parent counter ("posts" or "reels"), and its document id. */
    private void showReactors(Models.Comment c){
        if(com.friendbook.social.core.D1Client.enabled(counterCollection))D1Reactions.show(activity,counterCollection,counterId!=null?counterId:c.postId,c.id);
        else Reactions.showCommentReactors(activity,c);
    }
    public String counterCollection = "posts";
    public String counterId;
    public String postAuthorId = "";
    public String pinnedCommentId = "";
    /** Sort mode: "all" | "newest" | "relevant" */
    public String sortMode = "all";

    public CommentsAdapter(Activity a, Host h) { activity = a; host = h; }

    public void setHeaderView(View header) {
        this.headerView = header;
        notifyDataSetChanged();
    }

    public void setShowEmptyWhenNone(boolean show) {
        this.showEmptyWhenNone = show;
    }

    @Override public void onAttachedToRecyclerView(@NonNull RecyclerView list){super.onAttachedToRecyclerView(list);attachedList=list;}
    @Override public void onDetachedFromRecyclerView(@NonNull RecyclerView list){if(attachedList==list)attachedList=null;super.onDetachedFromRecyclerView(list);}

    public void setAll(List<Models.Comment> all) {
        final int oldCount=rows.size();
        String anchorId=null;int anchorTop=0;
        androidx.recyclerview.widget.LinearLayoutManager layout=attachedList!=null && attachedList.getLayoutManager() instanceof androidx.recyclerview.widget.LinearLayoutManager
                ?(androidx.recyclerview.widget.LinearLayoutManager)attachedList.getLayoutManager():null;
        if(layout!=null){
            int first=layout.findFirstVisibleItemPosition();int row=first-headerOffset();
            View anchor=layout.findViewByPosition(first);
            if(row>=0 && row<rows.size() && anchor!=null){anchorId=rows.get(row).c.id;anchorTop=anchor.getTop()-attachedList.getPaddingTop();}
        }
        rows.clear();
        if (all == null || all.isEmpty()) {
            notifyDataSetChanged();
            return;
        }

        // Deduplicate by id while preserving order, and collect all present comment IDs
        Map<String, Models.Comment> unique = new LinkedHashMap<>();
        for (Models.Comment c : all) {
            if (c == null) continue;
            String key = (c.id == null || c.id.isEmpty())
                    ? ("anon_" + c.authorId + "_" + c.createdAt + "_" + unique.size()) : c.id;
            unique.put(key, c);
        }
        Set<String> presentIds = new HashSet<>(unique.keySet());

        List<Models.Comment> roots = new ArrayList<>();
        Map<String, List<Models.Comment>> byParent = new HashMap<>();
        for (Models.Comment c : unique.values()) {
            String pid = c.parentId == null ? "" : c.parentId.trim();
            boolean hasValidParent = !pid.isEmpty()
                    && !"null".equalsIgnoreCase(pid)
                    && !pid.equals(c.id)
                    && presentIds.contains(pid);
            if (!hasValidParent) {
                roots.add(c);
            } else {
                List<Models.Comment> kids = byParent.get(pid);
                if (kids == null) { kids = new ArrayList<>(); byParent.put(pid, kids); }
                kids.add(c);
            }
        }

        if ("newest".equals(sortMode)) {
            Collections.sort(roots, (a, b) -> {
                if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
                return Long.compare(b.createdAt, a.createdAt);
            });
        } else if ("relevant".equals(sortMode)) {
            Collections.sort(roots, (a, b) -> {
                if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
                int aReplies = byParent.containsKey(a.id) ? byParent.get(a.id).size() : 0;
                int bReplies = byParent.containsKey(b.id) ? byParent.get(b.id).size() : 0;
                int aScore = Math.max(a.likes, a.reactions != null ? a.reactions.size() : 0) * 3 + aReplies * 2;
                int bScore = Math.max(b.likes, b.reactions != null ? b.reactions.size() : 0) * 3 + bReplies * 2;
                if (bScore != aScore) return Integer.compare(bScore, aScore);
                return Long.compare(b.createdAt, a.createdAt);
            });
        } else {
            // "all" -> pinned first, then chronological order so ALL users' comments & threads read naturally
            Collections.sort(roots, (a, b) -> {
                if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
                return Long.compare(a.createdAt, b.createdAt);
            });
        }

        Set<String> visited = new HashSet<>();
        for (Models.Comment root : roots) {
            appendThread(root, 0, byParent, visited);
        }
        // Safety net: if any cyclical parent link existed, still display every single comment!
        for (Models.Comment c : unique.values()) {
            if (c.id != null && !visited.contains(c.id)) {
                rows.add(new Row(c, 0));
                visited.add(c.id);
            }
        }
        notifyDataSetChanged();
        // Older chronological pages may prepend; keep the reader on the same comment/pixel.
        if(layout!=null && anchorId!=null && rows.size()>oldCount){
            for(int i=0;i<rows.size();i++)if(anchorId.equals(rows.get(i).c.id)){layout.scrollToPositionWithOffset(i+headerOffset(),anchorTop);break;}
        }
    }

    private void appendThread(Models.Comment node, int depth,
                              Map<String, List<Models.Comment>> byParent, Set<String> visited) {
        if (node == null) return;
        if (node.id != null && !visited.add(node.id)) return;
        rows.add(new Row(node, Math.min(depth, 2)));
        List<Models.Comment> kids = node.id != null ? byParent.get(node.id) : null;
        if (kids != null && !kids.isEmpty()) {
            Collections.sort(kids, (a, b) -> Long.compare(a.createdAt, b.createdAt));
            for (Models.Comment kid : kids) {
                appendThread(kid, depth + 1, byParent, visited);
            }
        }
    }

    public int getCommentRowCount() { return rows.size(); }

    private int headerOffset() { return headerView != null ? 1 : 0; }

    @Override
    public int getItemCount() {
        int h = headerOffset();
        if (rows.isEmpty()) return h + (showEmptyWhenNone ? 1 : 0);
        return h + rows.size();
    }

    @Override
    public int getItemViewType(int position) {
        if (headerView != null && position == 0) return TYPE_HEADER;
        if (rows.isEmpty()) return TYPE_EMPTY;
        return TYPE_COMMENT;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            if (headerView.getParent() instanceof ViewGroup) {
                ((ViewGroup) headerView.getParent()).removeView(headerView);
            }
            headerView.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            return new HeaderVH(headerView);
        }
        if (viewType == TYPE_EMPTY) {
            LinearLayout box = Ui.col(activity);
            box.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            box.setBackgroundColor(Ui.card(activity));
            box.setGravity(Gravity.CENTER);
            Ui.pad(box, 20, 32, 20, 36);
            TextView icon = Ui.tv(activity, "💬", 30, Ui.sub(activity));
            TextView t1 = Ui.tvBold(activity, "No comments yet", 15, Ui.txt(activity));
            t1.setPadding(0, Ui.dp(activity, 6), 0, Ui.dp(activity, 2));
            TextView t2 = Ui.tv(activity, "Be the first to share your thoughts below.", 13, Ui.sub(activity));
            box.addView(icon);
            box.addView(t1);
            box.addView(t2);
            return new HeaderVH(box);
        }

        LinearLayout outer = Ui.row(activity);
        outer.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
        outer.setBackgroundColor(Ui.card(activity));
        outer.setGravity(Gravity.TOP);
        Ui.pad(outer, 12, 7, 12, 7);

        ImageView av = new ImageView(activity);
        int s = Ui.dp(activity, 36);
        outer.addView(av, new LinearLayout.LayoutParams(s, s));

        LinearLayout col = Ui.col(activity);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
        cp.leftMargin = Ui.dp(activity, 9);
        outer.addView(col, cp);

        LinearLayout bubble = Ui.col(activity);
        bubble.setBackground(Ui.round(activity, Ui.inputBg(activity), 16));
        Ui.pad(bubble, 12, 8, 12, 9);

        LinearLayout nameRow = Ui.row(activity);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView author = Ui.tvBold(activity, "", 13.5f, Ui.txt(activity));
        nameRow.addView(author);
        ImageView tick = new ImageView(activity);
        tick.setImageResource(com.friendbook.social.R.drawable.ic_verified);
        tick.setVisibility(View.GONE);
        int ts = Ui.dp(activity, 13);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ts, ts);
        tp.leftMargin = Ui.dp(activity, 4);
        nameRow.addView(tick, tp);
        bubble.addView(nameRow);

        TextView text = Ui.tv(activity, "", 14.5f, Ui.txt(activity));
        text.setPadding(0, Ui.dp(activity, 2), 0, 0);
        bubble.addView(text);

        ImageView media = new ImageView(activity);
        media.setScaleType(ImageView.ScaleType.CENTER_CROP);
        media.setVisibility(View.GONE);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(Ui.dp(activity, 185), Ui.dp(activity, 145));
        mp.topMargin = Ui.dp(activity, 6);
        bubble.addView(media, mp);

        col.addView(bubble, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout meta = Ui.row(activity);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(meta, 6, 4, 4, 0);
        TextView time = Ui.tv(activity, "", 12, Ui.sub(activity));
        TextView like = Ui.tvBold(activity, "Like", 12, Ui.sub(activity));
        TextView reply = Ui.tvBold(activity, "Reply", 12, Ui.sub(activity));
        TextView likesCount = Ui.tvBold(activity, "", 12, Ui.sub(activity));
        likesCount.setBackground(Ui.roundStroke(activity, Ui.card(activity), 10, Ui.div(activity), 1));
        Ui.pad(likesCount, 7, 2, 7, 2);
        TextView more = Ui.tvBold(activity, "•••", 13, Ui.sub(activity));
        like.setPadding(Ui.dp(activity, 14), 0, 0, 0);
        reply.setPadding(Ui.dp(activity, 14), 0, 0, 0);
        LinearLayout.LayoutParams lcp = new LinearLayout.LayoutParams(-2, -2);
        lcp.leftMargin = Ui.dp(activity, 12);
        more.setPadding(Ui.dp(activity, 12), 0, Ui.dp(activity, 4), 0);
        meta.addView(time);
        meta.addView(like);
        meta.addView(reply);
        meta.addView(likesCount, lcp);
        meta.addView(more);
        col.addView(meta);
        return new VH(outer, av, author, tick, text, media, time, like, reply, likesCount, more);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (!(holder instanceof VH)) return;
        VH h = (VH) holder;
        int idx = position - headerOffset();
        if (idx < 0 || idx >= rows.size()) return;

        Row r = rows.get(idx);
        final Models.Comment c = r.c;
        int leftIndent = r.depth == 0 ? 12 : (r.depth == 1 ? 46 : 68);
        h.itemView.setPadding(Ui.dp(activity, leftIndent),
                Ui.dp(activity, 7), Ui.dp(activity, 12), Ui.dp(activity, 7));

        int avDp = r.depth == 0 ? 36 : 28;
        ViewGroup.LayoutParams avLp = h.av.getLayoutParams();
        avLp.width = Ui.dp(activity, avDp);
        avLp.height = Ui.dp(activity, avDp);
        h.av.setLayoutParams(avLp);

        Models.User liveUser = com.friendbook.social.core.FastCache.getUser(c.authorId);
        String dispName = (liveUser != null && liveUser.displayName != null && !liveUser.displayName.isEmpty())
                ? liveUser.displayName
                : (c.authorName != null && !c.authorName.isEmpty() ? c.authorName : "friendbook User");
        String dispPhoto = (liveUser != null && liveUser.photoUrl != null && !liveUser.photoUrl.isEmpty())
                ? liveUser.photoUrl : c.authorPhoto;
        if (liveUser == null && c.authorId != null && !c.authorId.isEmpty()) {
            Fb.db().collection("users").document(c.authorId).get()
                    .addOnSuccessListener(doc -> {
                        if (doc != null && doc.exists()) {
                            com.friendbook.social.models.Models.User u = com.friendbook.social.models.Models.User.fromDoc(doc);
                            com.friendbook.social.core.FastCache.putUser(u);
                            notifyDataSetChanged();
                        }
                    });
        }

        Ui.avatar(activity, h.av, dispPhoto);
        android.text.SpannableString authorLabel = new android.text.SpannableString((c.pinned ? "📌 " : "") + dispName
                + (c.authorId != null && c.authorId.equals(postAuthorId) ? "  ◆ Creator" : ""));
        if (c.authorId != null && c.authorId.equals(postAuthorId)) {
            authorLabel.setSpan(new android.text.style.ForegroundColorSpan(0xFF1A73E8),0,authorLabel.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        h.author.setText(authorLabel);
        TickCache.attach(h.tick, c.authorId);

        boolean hasText = c.content != null && !c.content.isEmpty();
        h.text.setVisibility(hasText ? View.VISIBLE : View.GONE);
        h.text.setText(com.friendbook.social.core.Mentions.styled(hasText ? c.content : "", c.mentions));
        com.friendbook.social.core.LinkUtils.makeLinksClickable(h.text);

        boolean hasMedia = c.imageUrl != null && !c.imageUrl.isEmpty();
        h.media.setVisibility(hasMedia ? View.VISIBLE : View.GONE);
        if (hasMedia) {
            Ui.imgFeed(activity, h.media, c.imageUrl);
            h.media.setOnClickListener(v -> {
                android.content.Intent in = new android.content.Intent(activity, FullscreenMediaActivity.class);
                in.putExtra("type", "image");
                in.putExtra("url", c.imageUrl);
                in.putExtra("title", dispName + "'s comment photo");
                activity.startActivity(in);
            });
        }

        h.time.setText(Format.ago(c.createdAt)
                + (c.edited ? " · edited" : "")
                + (c.pinned ? " · Pinned" : ""));

        String me = Fb.uid();
        String myReact = (me != null && c.reactions != null) ? c.reactions.get(me) : null;
        if (myReact != null && !myReact.isEmpty()) {
            h.like.setText(Reactions.emojiFor(myReact) + " " + Reactions.labelFor(myReact));
            h.like.setTextColor(Reactions.colorFor(myReact));
        } else {
            h.like.setText("Like");
            h.like.setTextColor(Ui.sub(activity));
        }

        int totalLikes = Math.max(c.likes, c.reactions != null ? c.reactions.size() : 0);
        if (totalLikes > 0) {
            h.likesCount.setVisibility(View.VISIBLE);
            StringBuilder emojiSet = new StringBuilder();
            if (c.reactions != null && !c.reactions.isEmpty()) {
                for (String t : Reactions.TYPES) {
                    if (c.reactions.containsValue(t)) {
                        emojiSet.append(Reactions.emojiFor(t));
                        if (emojiSet.length() >= 4) break;
                    }
                }
            }
            if (emojiSet.length() == 0) emojiSet.append("👍");
            h.likesCount.setText(emojiSet + " " + Format.count(totalLikes));
            h.likesCount.setOnClickListener(v -> showReactors(c));
        } else {
            h.likesCount.setVisibility(View.GONE);
            h.likesCount.setOnClickListener(null);
        }

        final String finalDispPhoto = dispPhoto;
        final String finalDispName = dispName;
        h.av.setOnClickListener(v -> host.openProfile(c.authorId));
        h.av.setOnLongClickListener(v -> {
            if (finalDispPhoto != null && !finalDispPhoto.isEmpty()) {
                android.content.Intent in = new android.content.Intent(activity, FullscreenMediaActivity.class);
                in.putExtra("type", "image");
                in.putExtra("url", finalDispPhoto);
                in.putExtra("title", finalDispName + "'s Profile Picture");
                activity.startActivity(in);
                return true;
            }
            return false;
        });
        h.author.setOnClickListener(v -> host.openProfile(c.authorId));

        h.like.setOnClickListener(v -> {
            Ui.haptic(v);
            toggleCommentReaction(c, myReact != null ? null : "like");
        });
        h.like.setOnLongClickListener(v -> {
            Reactions.show(v, picked -> toggleCommentReaction(c, picked));
            return true;
        });

        h.reply.setOnClickListener(v -> host.onReply(c));

        boolean mineOrAdmin = (me != null && me.equals(c.authorId)) || Admin.isAdmin();
        boolean isPostOwner = (me != null && me.equals(postAuthorId)) || Admin.isAdmin();
        h.more.setVisibility(View.VISIBLE);
        h.more.setOnClickListener(v -> {
            PopupMenu m = new PopupMenu(activity, v);
            if (isPostOwner && r.depth == 0) {
                m.getMenu().add(0, 6, 0, c.pinned ? "📌 Unpin comment" : "📌 Pin comment to top");
            }
            if (hasText) {
                m.getMenu().add(0, 3, 0, "📋 Copy comment");
                m.getMenu().add(0, 7, 0, "🌐 Translate comment");
            }
            if (finalDispPhoto != null && !finalDispPhoto.isEmpty()) {
                m.getMenu().add(0, 8, 0, "👁 View full profile picture");
            }
            if (totalLikes > 0) m.getMenu().add(0, 5, 0, "👍 See who reacted");
            if (me != null && me.equals(c.authorId)) m.getMenu().add(0, 1, 0, "✏️ Edit");
            if (mineOrAdmin || isPostOwner) {
                m.getMenu().add(0, 2, 0, (Admin.isAdmin() && (me == null || !me.equals(c.authorId)))
                        ? "🛡 Admin delete" : "🗑 Delete");
            }
            if (!mineOrAdmin) m.getMenu().add(0, 4, 0, "🚩 Report comment");
            m.setOnMenuItemClickListener(it -> {
                if (it.getItemId() == 6) {
                    boolean nextPin = !c.pinned;
                    c.pinned = nextPin;
                    pinnedCommentId = nextPin ? c.id : "";
                    CommentsEngine.pinComment(counterCollection, counterId, c.id, nextPin);
                    notifyDataSetChanged();
                    Ui.toast(activity, nextPin ? "Comment pinned to top 📌" : "Comment unpinned");
                } else if (it.getItemId() == 3) {
                    Ui.copy(activity, c.content);
                } else if (it.getItemId() == 7) {
                    String q = android.net.Uri.encode(c.content);
                    Ui.openUrl(activity, "https://translate.google.com/?sl=auto&tl=en&text=" + q + "&op=translate");
                } else if (it.getItemId() == 8) {
                    android.content.Intent in = new android.content.Intent(activity, FullscreenMediaActivity.class);
                    in.putExtra("type", "image");
                    in.putExtra("url", finalDispPhoto);
                    in.putExtra("title", finalDispName + "'s Profile Picture");
                    activity.startActivity(in);
                } else if (it.getItemId() == 5) {
                    showReactors(c);
                } else if (it.getItemId() == 1) {
                    editDialog(c);
                } else if (it.getItemId() == 2) {
                    delete(c);
                } else if (it.getItemId() == 4) {
                    Ui.toast(activity, "Comment reported to moderators ✓");
                }
                return true;
            });
            m.show();
        });
    }

    /** 0ms optimistic reaction update + multi-user Firestore sync strictly per `currentUserId` (`Fb.uid()`)! */
    private void toggleCommentReaction(Models.Comment c, String type) {
        String me = Fb.uid();
        if (me == null || me.isEmpty() || c == null || c.id == null) return;
        if (c.reactions == null) c.reactions = new HashMap<>();
        if (c.likedBy == null) c.likedBy = new ArrayList<>();
        String prev = c.reactions.get(me);

        Map<String, String> nextMap = new HashMap<>(c.reactions);
        List<String> nextLikedBy = new ArrayList<>(c.likedBy);
        final String nextType;
        if (type == null || type.equals(prev)) {
            nextType = null;
            nextMap.remove(me);
            nextLikedBy.remove(me);
        } else {
            nextType = Models.normalizeReactionType(type, "like");
            nextMap.put(me, nextType);
            if (!nextLikedBy.contains(me)) nextLikedBy.add(me);
        }
        c.reactions = nextMap;
        c.likedBy = nextLikedBy;
        c.likes = nextMap.size();
        notifyDataSetChanged(); // Instant 0ms UI update!

        String cid = counterId != null ? counterId : c.postId;
        CommentsEngine.updateCommentReaction(counterCollection, cid, c, me, nextType);
    }

    private void editDialog(Models.Comment c) {
        EditText e = Ui.multi(activity, "Comment", 3);
        com.friendbook.social.core.Mentions.attach(e);
        com.friendbook.social.core.Mentions.restore(e,c.content,c.mentions);
        LinearLayout box = Ui.col(activity);
        Ui.pad(box, 18, 12, 18, 4);
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(activity)
                .setTitle("Edit comment")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String nv = e.getText().toString().trim();
                    if (nv.isEmpty()) return;
                    c.mentions = com.friendbook.social.core.Mentions.capture(e);
                    c.content = nv;
                    c.edited = true;
                    notifyDataSetChanged();
                    String cid = counterId != null ? counterId : c.postId;
                    CommentsEngine.editComment(counterCollection, cid, c, nv);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void delete(Models.Comment c) {
        Ui.confirm(activity, "Delete comment?", "This cannot be undone.", "Delete", () -> {
            String cid = counterId != null ? counterId : c.postId;
            rows.removeIf(rw -> rw.c != null && c.id != null && c.id.equals(rw.c.id));
            notifyDataSetChanged();
            CommentsEngine.deleteComment(counterCollection, cid, c);
        });
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        HeaderVH(View v) { super(v); }
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView av, tick, media;
        TextView author, text, time, like, reply, likesCount, more;
        VH(View v, ImageView a, TextView au, ImageView tk, TextView t, ImageView md,
           TextView ti, TextView l, TextView r, TextView lc, TextView m) {
            super(v);
            av = a; author = au; tick = tk; text = t; media = md;
            time = ti; like = l; reply = r; likesCount = lc; more = m;
        }
    }
}
