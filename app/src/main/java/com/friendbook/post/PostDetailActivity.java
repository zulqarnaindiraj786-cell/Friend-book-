package com.friendbook.post;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.core.Fb;
import com.friendbook.core.Notify;
import com.friendbook.core.Ui;
import com.friendbook.models.Models;
import com.friendbook.profile.ProfileActivity;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.List;

/** Post detail: full post card + realtime nested comments + composer. */
public class PostDetailActivity extends Activity implements FeedAdapter.Host, CommentsAdapter.Host {

    private static final int PICK_IMG = 21;

    private String postId;
    private FeedAdapter postAdapter;
    private CommentsAdapter commentsAdapter;
    private ListenerRegistration postReg, commentsReg;
    private EditText input;
    private TextView sendBtn, replyChip;
    private Models.Comment replyingTo;
    private Uri commentImage;
    private ImageView imgPreview;
    private String postAuthorId;
    private List<String> postSubscribers = new ArrayList<>();
    private final List<Models.Comment> rawComments = new ArrayList<>();
    private TextView sortBtn;
    private boolean busy;

    private final com.friendbook.core.FastCache.ReactionObserver reactionObserver = (pid, mine, total) -> {
        if (postId != null && postId.equals(pid) && postAdapter != null) {
            postAdapter.onFastReactionUpdate(pid, mine, total);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        postId = getIntent().getStringExtra("post_id");
        if (postId == null) { finish(); return; }

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "Post"));

        ScrollView sv = new ScrollView(this);
        LinearLayout scrollBody = Ui.col(this);

        RecyclerView postRv = new RecyclerView(this);
        postRv.setLayoutManager(new LinearLayoutManager(this));
        postRv.setItemAnimator(null);
        postRv.setNestedScrollingEnabled(false);
        postAdapter = new FeedAdapter(this, this);
        postAdapter.showHeader = false;
        postAdapter.showFooter = false;
        postRv.setAdapter(postAdapter);
        scrollBody.addView(postRv, new LinearLayout.LayoutParams(-1, -2));

        // ⚡ 0.0ms Instant Pre-Render from FastCache!
        for (Models.Post cached : com.friendbook.core.FastCache.getFeed()) {
            if (cached != null && postId.equals(cached.id)) {
                postAuthorId = cached.authorId;
                List<Models.Post> one = new ArrayList<>();
                one.add(cached);
                postAdapter.setPosts(one);
                break;
            }
        }

        LinearLayout cHead = Ui.row(this);
        Ui.pad(cHead, 12, 8, 12, 4);
        TextView cTitle = Ui.tvBold(this, "Comments", 14, Ui.txt(this));
        cHead.addView(cTitle, new LinearLayout.LayoutParams(0, -2, 1));
        sortBtn = Ui.tvBold(this, sortLabel(com.friendbook.core.Prefs.commentSort(this)), 12, Ui.BLUE);
        sortBtn.setOnClickListener(v -> pickCommentSort());
        cHead.addView(sortBtn);
        scrollBody.addView(cHead);

        RecyclerView cRv = new RecyclerView(this);
        cRv.setLayoutManager(new LinearLayoutManager(this));
        cRv.setItemAnimator(null);
        cRv.setNestedScrollingEnabled(false);
        commentsAdapter = new CommentsAdapter(this, this);
        cRv.setAdapter(commentsAdapter);
        scrollBody.addView(cRv, new LinearLayout.LayoutParams(-1, -2));

        sv.addView(scrollBody);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(buildComposer());
        setContentView(root);
    }

    private View buildComposer() {
        LinearLayout wrap = Ui.col(this);
        wrap.setBackgroundColor(Ui.card(this));
        wrap.addView(Ui.divider(this));

        // reply indicator chip
        LinearLayout chipRow = Ui.row(this);
        Ui.pad(chipRow, 12, 4, 12, 0);
        replyChip = Ui.tv(this, "", 12, Ui.sub(this));
        replyChip.setVisibility(View.GONE);
        TextView cancelReply = Ui.tvBold(this, "✕", 14, Ui.sub(this));
        cancelReply.setVisibility(View.GONE);
        cancelReply.setPadding(Ui.dp(this, 8), 0, 0, 0);
        cancelReply.setOnClickListener(v -> {
            replyingTo = null;
            replyChip.setVisibility(View.GONE);
            cancelReply.setVisibility(View.GONE);
            input.setHint("Write a comment…");
        });
        chipRow.addView(replyChip, new LinearLayout.LayoutParams(0, -2, 1));
        chipRow.addView(cancelReply);
        wrap.addView(chipRow);
        cancelReply.setTag("cancelReply");

        // ⚡ Facebook Lite Quick Emoji Bar above comment input
        LinearLayout emojiRow = Ui.row(this);
        Ui.pad(emojiRow, 10, 4, 10, 0);
        String[] emojis = {"❤️", "😂", "🔥", "👏", "😢", "😍", "👍", "💯"};
        for (final String em : emojis) {
            TextView et = Ui.tv(this, em, 18, Ui.txt(this));
            et.setGravity(android.view.Gravity.CENTER);
            et.setOnClickListener(v -> {
                Ui.haptic(v);
                int pos = Math.max(0, input.getSelectionStart());
                input.getText().insert(pos, em);
            });
            emojiRow.addView(et, new LinearLayout.LayoutParams(0, -2, 1));
        }
        wrap.addView(emojiRow);

        LinearLayout row = Ui.row(this);
        Ui.pad(row, 10, 8, 10, 8);
        ImageView av = new ImageView(this);
        int s = Ui.dp(this, 34);
        row.addView(av, new LinearLayout.LayoutParams(s, s));
        Ui.avatar(this, av, Fb.myPhoto());

        LinearLayout inputWrap = Ui.col(this);
        inputWrap.setBackground(Ui.round(this, Ui.inputBg(this), 20));
        inputWrap.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 4), 0);
        LinearLayout inner = Ui.row(this);
        input = Ui.input(this, "Write a comment…");
        input.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        input.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        inner.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        TextView cam = Ui.tv(this, "📷", 18, Ui.sub(this));
        cam.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        cam.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            startActivityForResult(i, PICK_IMG);
        });
        inner.addView(cam);
        inputWrap.addView(inner);

        imgPreview = new ImageView(this);
        imgPreview.setVisibility(View.GONE);
        inputWrap.addView(imgPreview, new LinearLayout.LayoutParams(Ui.dp(this, 90), Ui.dp(this, 70)));

        LinearLayout.LayoutParams iw = new LinearLayout.LayoutParams(0, -2, 1);
        iw.leftMargin = Ui.dp(this, 8);
        row.addView(inputWrap, iw);

        sendBtn = Ui.tv(this, "➤", 22, Ui.sub(this));
        sendBtn.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 4), 0);
        row.addView(sendBtn);
        wrap.addView(row);

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                sendBtn.setTextColor(s.length() > 0 ? Ui.BLUE : Ui.sub(PostDetailActivity.this));
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        sendBtn.setOnClickListener(v -> {
            if (replyingTo == null) sendComment();
            else sendComment();
        });
        return wrap;
    }

    private String sortLabel(String s) {
        if ("newest".equals(s)) return "Newest first ▾";
        if ("oldest".equals(s)) return "All comments (oldest) ▾";
        return "Most relevant ▾";
    }

    private void pickCommentSort() {
        String[] opts = {"⭐ Most relevant (top liked)", "🕒 Newest first", "📜 All comments (oldest first)"};
        Ui.choose(this, "Filter comments", opts, w -> {
            String key = w == 1 ? "newest" : (w == 2 ? "oldest" : "relevant");
            com.friendbook.core.Prefs.setCommentSort(this, key);
            if (sortBtn != null) sortBtn.setText(sortLabel(key));
            commentsAdapter.setSortMode(key);
            commentsAdapter.setAll(rawComments);
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        com.friendbook.core.FastCache.addReactionObserver(reactionObserver);
        commentsAdapter.setSortMode(com.friendbook.core.Prefs.commentSort(this));
        postReg = Fb.db().collection("posts").document(postId)
                .addSnapshotListener((d, err) -> {
                    if (d == null || !d.exists()) {
                        if (postAdapter.getItemCount() == 0) { Ui.toast(this, "Post was deleted"); finish(); }
                        return;
                    }
                    Models.Post p = Models.Post.fromDoc(d);
                    postAuthorId = p.authorId;
                    postSubscribers = p.subscribedBy != null ? p.subscribedBy : new ArrayList<>();
                    List<Models.Post> one = new ArrayList<>();
                    one.add(p);
                    postAdapter.setPosts(one);
                });
        commentsReg = Fb.db().collection("comments").whereEqualTo("postId", postId)
                .addSnapshotListener((snap, err) -> {
                    if (snap == null) return;
                    rawComments.clear();
                    for (DocumentSnapshot d : snap) {
                        Models.Comment c = Models.Comment.fromDoc(d);
                        if (Fb.me != null && Fb.me.blocked.contains(c.authorId)) continue;
                        rawComments.add(c);
                    }
                    commentsAdapter.setAll(rawComments);
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        com.friendbook.core.FastCache.removeReactionObserver(reactionObserver);
        if (postReg != null) postReg.remove();
        if (commentsReg != null) commentsReg.remove();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == PICK_IMG && res == RESULT_OK && data != null && data.getData() != null) {
            commentImage = data.getData();
            imgPreview.setVisibility(View.VISIBLE);
            imgPreview.setImageURI(commentImage);
            imgPreview.setOnClickListener(v -> {
                commentImage = null;
                imgPreview.setVisibility(View.GONE);
            });
            Ui.toast(this, "Photo attached — tap preview to remove");
        }
    }

    // ---------- CommentsAdapter.Host ----------
    @Override
    public void replyTo(Models.Comment c) {
        replyingTo = c;
        replyChip.setVisibility(View.VISIBLE);
        replyChip.setText("Replying to " + c.authorName);
        View cancel = replyChip.getParent() instanceof View
                ? ((LinearLayout) replyChip.getParent()).getChildAt(1) : null;
        if (cancel != null) cancel.setVisibility(View.VISIBLE);
        input.setHint("Reply to " + c.authorName + "…");
        input.requestFocus();
    }

    @Override
    public void changed() { }

    private void sendComment() {
        if (busy) return;
        String text = input.getText().toString().trim();
        if (text.isEmpty() && commentImage == null) return;
        busy = true;
        sendBtn.setEnabled(false);
        if (commentImage != null) {
            String path = "comments/" + Fb.uid() + "/" + System.currentTimeMillis() + ".jpg";
            com.friendbook.core.Cloudinary.uploadOrFirebase(this, path, commentImage)
                    .addOnSuccessListener(url -> write(text, url.toString()))
                    .addOnFailureListener(e -> { busy = false; sendBtn.setEnabled(true); Ui.toast(this, "Upload failed"); });
        } else {
            write(text, null);
        }
    }

    private void write(String text, String imageUrl) {
        final Models.Comment prevReply = replyingTo;
        java.util.Map<String, Object> m = Fb.map(
                "postId", postId,
                "authorId", Fb.uid(),
                "authorName", Fb.myName(),
                "authorPhotoUrl", Fb.myPhoto(),
                "content", text,
                "imageUrl", imageUrl,
                "likes", 0,
                "likedBy", new ArrayList<String>(),
                "parentId", prevReply == null ? null : prevReply.id,
                "createdAt", FieldValue.serverTimestamp());

        // ⚡ 0.0ms Optimistic Comment Write: clear composer & queue locally immediately!
        replyingTo = null;
        replyChip.setVisibility(View.GONE);
        View cancel = replyChip.getParent() instanceof View
                ? ((LinearLayout) replyChip.getParent()).getChildAt(1) : null;
        if (cancel != null) cancel.setVisibility(View.GONE);
        input.setText("");
        input.setHint("Write a comment…");
        commentImage = null;
        imgPreview.setVisibility(View.GONE);
        busy = false;
        sendBtn.setEnabled(true);
        Ui.hideKeyboard(this);

        com.google.firebase.firestore.DocumentReference cRef = Fb.db().collection("comments").document();
        cRef.set(m);
        Fb.db().collection("posts").document(postId).update("comments", FieldValue.increment(1));
        if (postAuthorId != null) Notify.comment(this, postAuthorId, postId);
        if (prevReply != null && prevReply.authorId != null && !prevReply.authorId.equals(Fb.uid())) {
            Notify.send(prevReply.authorId, "comment_reply", "replied to your comment", postId);
        }
        for (String subUid : postSubscribers) {
            if (subUid != null && !subUid.equals(Fb.uid()) && !subUid.equals(postAuthorId)) {
                Notify.send(subUid, "comment", "commented on a post you turned notifications on for", postId);
            }
        }
    }

    // ---------- FeedAdapter.Host (single post card) ----------
    @Override public void openComments(Models.Post post) {
        input.requestFocus();
    }

    @Override public void openProfile(String uid) {
        Intent i = new Intent(this, ProfileActivity.class);
        i.putExtra("uid", uid);
        startActivity(i);
    }

    @Override public void editPost(Models.Post post) {
        Intent i = new Intent(this, CreatePostActivity.class);
        i.putExtra("edit_id", post.id);
        startActivity(i);
    }

    @Override public void createStory() { }
    @Override public void openStoryGroup(com.friendbook.story.StoriesAdapter.Group g) { }
    @Override public void loadMore() { }
    @Override public boolean hasMore() { return false; }
}
