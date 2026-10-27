package com.friendbook.reels;

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

import com.friendbook.core.Fb;
import com.friendbook.core.Notify;
import com.friendbook.core.Prefs;
import com.friendbook.core.Ui;
import com.friendbook.models.Models;
import com.friendbook.post.CommentsAdapter;
import com.friendbook.post.CommentsEngine;
import com.friendbook.profile.ProfileActivity;

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
    private CommentsAdapter adapter;
    private CommentsEngine.Handle commentsHandle;
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
        Ui.pad(bar, 8, 10, 12, 10);
        TextView back = Ui.tvBold(this, "‹", 28, Ui.txt(this));
        Ui.pad(back, 10, 0, 14, 2);
        back.setOnClickListener(v -> finish());
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
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));

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
        commentsHandle = CommentsEngine.watch(collection, reelId, commentsKey, (all, pinnedId) -> {
            rawComments.clear();
            rawComments.addAll(all);
            adapter.pinnedCommentId = pinnedId == null ? "" : pinnedId;
            updateHeaderText();
            adapter.setAll(rawComments);
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (commentsHandle != null) commentsHandle.remove();
    }

    @Override
    public void onReply(Models.Comment c) {
        replyingTo = c;
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

        cancelReply();
        input.setText("");
        Ui.hideKeyboard(this);

        Models.Comment local = CommentsEngine.sendComment(collection, reelId, commentsKey, text, "", parentId,
                (ok, errMsg) -> {
                    busy = false;
                    if (ok) {
                        if (author != null && !me.equals(author)) {
                            Notify.send(author, "reel_comment", Fb.myName() + " commented on your reel", reelId);
                        }
                        if (replyTarget != null && !me.equals(replyTarget.authorId)) {
                            Notify.send(replyTarget.authorId, "comment_reply", Fb.myName() + " replied to your comment", reelId);
                        }
                    } else {
                        Ui.toast(ReelCommentsActivity.this, errMsg != null ? errMsg : "Failed to post comment");
                    }
                });

        if (local != null) {
            rawComments.add(local);
            updateHeaderText();
            adapter.setAll(rawComments);
            rv.scrollToPosition(Math.max(0, adapter.getItemCount() - 1));
        } else {
            busy = false;
        }
    }
}
