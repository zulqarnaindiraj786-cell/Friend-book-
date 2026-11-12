package com.example.frienbook.post;

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

import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Notify;
import com.example.frienbook.core.Ui;
import com.example.frienbook.models.Models;
import com.example.frienbook.profile.ProfileActivity;
import com.example.frienbook.core.cloud.DocumentSnapshot;
import com.example.frienbook.core.cloud.FieldValue;
import com.example.frienbook.core.cloud.ListenerRegistration;

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
    private boolean busy;

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
        postRv.setNestedScrollingEnabled(false);
        postAdapter = new FeedAdapter(this, this);
        postAdapter.showHeader = false;
        postAdapter.showFooter = false;
        postRv.setAdapter(postAdapter);
        scrollBody.addView(postRv, new LinearLayout.LayoutParams(-1, -2));

        TextView cTitle = Ui.tvBold(this, "Comments", 14, Ui.sub(this));
        Ui.pad(cTitle, 12, 8, 12, 2);
        scrollBody.addView(cTitle);

        RecyclerView cRv = new RecyclerView(this);
        cRv.setLayoutManager(new LinearLayoutManager(this));
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

    @Override
    protected void onStart() {
        super.onStart();
        postReg = Fb.db().collection("posts").document(postId)
                .addSnapshotListener((d, err) -> {
                    if (d == null || !d.exists()) { Ui.toast(this, "Post was deleted"); finish(); return; }
                    Models.Post p = Models.Post.fromDoc(d);
                    postAuthorId = p.authorId;
                    List<Models.Post> one = new ArrayList<>();
                    one.add(p);
                    postAdapter.setPosts(one);
                });
        commentsReg = Fb.db().collection("comments").whereEqualTo("postId", postId)
                .addSnapshotListener((snap, err) -> {
                    if (snap == null) return;
                    List<Models.Comment> all = new ArrayList<>();
                    for (DocumentSnapshot d : snap) {
                        Models.Comment c = Models.Comment.fromDoc(d);
                        if (Fb.me != null && Fb.me.blocked.contains(c.authorId)) continue;
                        all.add(c);
                    }
                    commentsAdapter.setAll(all);
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
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
            com.example.frienbook.core.CloudflareMedia.uploadPhoto(this, commentImage, null,
                    url -> write(text, url),
                    e -> { busy = false; sendBtn.setEnabled(true); Ui.toast(this, "Upload failed"); });
        } else {
            write(text, null);
        }
    }

    private void write(String text, String imageUrl) {
        java.util.Map<String, Object> m = Fb.map(
                "postId", postId,
                "authorId", Fb.uid(),
                "authorName", Fb.myName(),
                "authorPhotoUrl", Fb.myPhoto(),
                "content", text,
                "imageUrl", imageUrl,
                "likes", 0,
                "likedBy", new ArrayList<String>(),
                "parentId", replyingTo == null ? null : replyingTo.id,
                "createdAt", FieldValue.serverTimestamp());
        Fb.db().collection("comments").add(m)
                .addOnSuccessListener(ref -> com.example.frienbook.core.CloudflareDocs.mirror("comments", ref.getId(), m))
                .addOnSuccessListener(x -> {
                    Fb.db().collection("posts").document(postId).update("comments", FieldValue.increment(1));
                    if (postAuthorId != null) Notify.comment(this, postAuthorId, postId);
                    if (replyingTo != null && !replyingTo.authorId.equals(Fb.uid()))
                        Notify.send(replyingTo.authorId, "comment_reply", "replied to your comment", postId);
                    replyingTo = null;
                    replyChip.setVisibility(View.GONE);
                    input.setText("");
                    input.setHint("Write a comment…");
                    commentImage = null;
                    imgPreview.setVisibility(View.GONE);
                    busy = false;
                    sendBtn.setEnabled(true);
                    Ui.hideKeyboard(this);
                })
                .addOnFailureListener(e -> {
                    busy = false;
                    sendBtn.setEnabled(true);
                    Ui.toast(this, "Failed to comment");
                });
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
    @Override public void openStoryGroup(com.example.frienbook.story.StoriesAdapter.Group g) { }
    @Override public void loadMore() { }
    @Override public boolean hasMore() { return false; }
}
