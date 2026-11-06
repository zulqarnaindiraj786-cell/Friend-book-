package com.friendbook.social.reels;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Notify;
import com.friendbook.social.core.Prefs;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.friendbook.social.post.CommentsAdapter;
import com.friendbook.social.post.CommentsEngine;
import com.friendbook.social.profile.ProfileActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * 🎬 friendbook Pro Reel Comments Sheet:
 * Powered by {@link CommentsEngine} so ALL users' comments & replies sync in real time
 * across both `reels/{id}.commentsMap` (or `posts/{id}.commentsMap` for video posts) + `comments`.
 */
public class ReelCommentsActivity extends Activity implements CommentsAdapter.Host {

    private String reelId, author, collection, commentsKey;
    private RecyclerView rv;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout commentSwipe;
    private CommentsAdapter adapter;
    private CommentsEngine.Handle commentsHandle;
    private boolean initialLoaded;
    private TextView olderCommentsButton;
    private EditText input;
    private TextView sendBtn, replyChip, countHeader;
    private Models.Comment replyingTo;
    private final List<Models.Comment> rawComments = new ArrayList<>();
    private boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarLight(this);
        reelId = getIntent().getStringExtra("reel_id");
        author = getIntent().getStringExtra("author");
        collection = getIntent().getStringExtra("collection");
        if (collection == null || collection.isEmpty()) collection = "reels";
        if (reelId == null) { finish(); return; }
        commentsKey = "posts".equals(collection) ? reelId : ("reel_" + reelId);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.card(this));

        // Crisp friendbook Top Header
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.card(this));
        bar.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(bar, 6, 6, 12, 6);
        android.widget.ImageView back = Ui.backArrow(this, Ui.txt(this), v -> finish());
        bar.addView(back);
        countHeader = Ui.tvBold(this, "Reel comments · All comments ▾", 16, Ui.txt(this));
        countHeader.setOnClickListener(v -> Ui.choose(this, "Filter comments", new String[]{
                "💬 All comments — show every user's comment & reply",
                "🕐 Newest — most recent first",
                "⭐ Most relevant — top liked first"
        }, idx -> {
            String mode = idx == 1 ? "newest" : (idx == 2 ? "relevant" : "all");
            Prefs.setCommentSort(this, mode);
            adapter.sortMode = mode;
            updateHeaderText();
            adapter.setAll(rawComments);
        }));
        bar.addView(countHeader, new LinearLayout.LayoutParams(0, -2, 1));
        TextView older=Ui.tvBold(this,"View more comments",12,Ui.BLUE);
        olderCommentsButton=older;
        older.setPadding(Ui.dp(this,8),Ui.dp(this,8),Ui.dp(this,8),Ui.dp(this,8));
        older.setOnClickListener(v -> {
            if(commentsHandle==null) return;
            if(!commentsHandle.error.isEmpty()) Ui.toast(this,commentsHandle.error);
            if(commentsHandle.hasMore()) commentsHandle.loadMore(); else Ui.toast(this,"All comments loaded");
            updateCommentPager();
        });
        bar.addView(older);
        if(com.friendbook.social.core.D1Client.enabled(collection)){TextView drafts=Ui.tvBold(this,"Unsent",12,Ui.BLUE);drafts.setOnClickListener(v->CommentsEngine.showD1Drafts(this,collection,reelId));bar.addView(drafts);}
        root.addView(bar);
        root.addView(Ui.divider(this));

        rv = new RecyclerView(this);
        rv.setBackgroundColor(Ui.card(this));
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new CommentsAdapter(this, this);
        adapter.counterCollection = collection;
        adapter.counterId = reelId;
        adapter.postAuthorId = author == null ? "" : author;
        adapter.sortMode = Prefs.commentSort(this);
        rv.setAdapter(adapter);
        com.friendbook.social.core.NearEndPaging.attachComments(rv,()->adapter.sortMode,()->{if(commentsHandle!=null && commentsHandle.hasMore())commentsHandle.loadMore();});
        commentSwipe = new androidx.swiperefreshlayout.widget.SwipeRefreshLayout(this);
        commentSwipe.setColorSchemeColors(Ui.BLUE);
        commentSwipe.addView(rv);
        commentSwipe.setOnRefreshListener(() -> {
            initialLoaded = false;
            loadComments();
        });
        root.addView(commentSwipe, new LinearLayout.LayoutParams(-1, 0, 1));

        // Composer + Quick Emoji Strip
        LinearLayout wrap = Ui.col(this);
        wrap.setBackgroundColor(Ui.card(this));
        wrap.addView(Ui.divider(this));

        LinearLayout emojiStrip = Ui.row(this);
        Ui.pad(emojiStrip, 10, 6, 10, 2);
        String[] quickEmojis = {"❤️", "🔥", "😂", "👏", "😍", "🙌", "😢", "💯"};
        for (final String em : quickEmojis) {
            TextView et = Ui.tv(this, em, 19, Ui.txt(this));
            et.setGravity(Gravity.CENTER);
            et.setOnClickListener(v -> {
                int start = Math.max(0, input.getSelectionStart());
                input.getText().insert(start, em);
            });
            emojiStrip.addView(et, new LinearLayout.LayoutParams(0, -2, 1));
        }
        wrap.addView(emojiStrip);

        LinearLayout chipRow = Ui.row(this);
        Ui.pad(chipRow, 12, 4, 12, 0);
        replyChip = Ui.tvBold(this, "", 12, Ui.BLUE);
        replyChip.setVisibility(View.GONE);
        TextView cancel = Ui.tvBold(this, "✕", 14, Ui.sub(this));
        cancel.setVisibility(View.GONE);
        cancel.setPadding(Ui.dp(this, 8), 0, 0, 0);
        cancel.setOnClickListener(v -> cancelReply());
        chipRow.addView(replyChip, new LinearLayout.LayoutParams(0, -2, 1));
        chipRow.addView(cancel);
        wrap.addView(chipRow);

        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(row, 10, 6, 10, 8);
        ImageView av = new ImageView(this);
        int s = Ui.dp(this, 34);
        row.addView(av, new LinearLayout.LayoutParams(s, s));
        Ui.avatar(this, av, Fb.myPhoto());
        input = Ui.input(this, "Add a public comment…");
        com.friendbook.social.core.Mentions.attach(input, () -> author);
        input.setBackground(Ui.round(this, Ui.inputBg(this), 20));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1);
        ip.leftMargin = Ui.dp(this, 8);
        row.addView(input, ip);
        sendBtn = Ui.tvBold(this, "Send", 15, Ui.sub(this));
        sendBtn.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
        row.addView(sendBtn);
        wrap.addView(row);
        root.addView(wrap);

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s2, int a, int b2, int c) { }
            @Override public void onTextChanged(CharSequence s2, int a, int b2, int c) {
                sendBtn.setTextColor(s2.toString().trim().length() > 0 ? Ui.BLUE : Ui.sub(ReelCommentsActivity.this));
            }
            @Override public void afterTextChanged(Editable s2) { }
        });
        sendBtn.setOnClickListener(v -> send());
        setContentView(root);
    }

    private void updateHeaderText() {
        String label = "newest".equals(adapter.sortMode) ? "Newest"
                : ("relevant".equals(adapter.sortMode) ? "Most relevant" : "All comments");
        countHeader.setText("Comments (" + rawComments.size() + ") · " + label + " ▾");
    }

    private void cancelReply() {
        com.friendbook.social.core.Mentions.clearReplyTarget(input);
        replyingTo = null;
        replyChip.setVisibility(View.GONE);
        if (replyChip.getParent() instanceof LinearLayout) {
            ((LinearLayout) replyChip.getParent()).getChildAt(1).setVisibility(View.GONE);
        }
        input.setHint("Add a public comment…");
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!initialLoaded) {
            loadComments();
        }
    }

    private void loadComments() {
        initialLoaded = true;
        if (commentsHandle != null) { commentsHandle.remove(); commentsHandle = null; }
        commentsHandle = CommentsEngine.watch(collection, reelId, commentsKey, (all, pinnedId) -> {
            updateCommentPager();
            if(commentsHandle!=null && commentsHandle.isLoading())return;
            if(commentSwipe!=null)commentSwipe.setRefreshing(false);
            rawComments.clear();
            rawComments.addAll(all);
            adapter.pinnedCommentId = pinnedId == null ? "" : pinnedId;
            updateHeaderText();
            adapter.setAll(rawComments);
        });
    }

    private void updateCommentPager() {
        if(olderCommentsButton==null || commentsHandle==null) return;
        olderCommentsButton.setText(commentsHandle.isLoading()?"Loading comments…":
                !commentsHandle.error.isEmpty()?"Retry older comments":commentsHandle.hasMore()?"View more comments":"All comments loaded");
        olderCommentsButton.setEnabled(!commentsHandle.isLoading() && (commentsHandle.hasMore() || !commentsHandle.error.isEmpty()));
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isFinishing() && commentsHandle != null) commentsHandle.remove();
    }

    @Override
    public void onReply(Models.Comment c) {
        replyingTo = c;
        com.friendbook.social.core.Mentions.setReplyTarget(input, c.authorId, c.authorName);
        replyChip.setVisibility(View.VISIBLE);
        if (replyChip.getParent() instanceof LinearLayout) {
            ((LinearLayout) replyChip.getParent()).getChildAt(1).setVisibility(View.VISIBLE);
        }
        replyChip.setText("Replying to " + c.authorName);
        input.setHint("Reply to " + c.authorName + "…");
        input.requestFocus();
    }

    @Override
    public void openProfile(String uid) {
        if (uid == null || uid.isEmpty()) return;
        Intent i = new Intent(this, ProfileActivity.class);
        i.putExtra("uid", uid);
        startActivity(i);
    }

    private void send() {
        if (busy) return;
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        String me = Fb.uid();
        if (me == null) return;
        busy = true;
        final Models.Comment replyTarget = replyingTo;
        String parentId = replyTarget == null ? "" : replyTarget.id;

        java.util.List<java.util.Map<String,Object>> mentions = com.friendbook.social.core.Mentions.capture(input);
        cancelReply();
        input.setText("");

        Models.Comment local = CommentsEngine.sendComment(collection, reelId, commentsKey, text, "", parentId, mentions,
                (ok, errMsg) -> {
                    if (ok) {
                        if (author != null && !me.equals(author)) {
                            Notify.send(author, "reel_comment", Fb.myName() + " commented on your reel", reelId);
                        }
                        if (replyTarget != null && !me.equals(replyTarget.authorId)) {
                            Notify.send(replyTarget.authorId, "reel_comment_reply", Fb.myName() + " replied to your comment", reelId);
                        }
                    } else {
                        Ui.toast(ReelCommentsActivity.this, errMsg != null ? errMsg : "Failed to post comment");
                    }
                });

        busy = false; // Local queue accepted; allow another comment before network acknowledgement.
        input.requestFocus();
        if (local != null) {
            rawComments.add(local);
            updateHeaderText();
            adapter.setAll(rawComments);
            rv.scrollToPosition(Math.max(0, adapter.getItemCount() - 1));
        } else {
            busy = false;
            if(com.friendbook.social.core.D1Client.enabled(collection))com.friendbook.social.core.Mentions.restore(input,text,mentions);
        }
    }
}
