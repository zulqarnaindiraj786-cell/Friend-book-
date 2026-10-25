package com.friendbook.reels;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
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
import com.friendbook.post.CommentsAdapter;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.List;

/** Comments sheet for a reel (shares the global comments collection, key = reel_<id>). */
public class ReelCommentsActivity extends Activity implements CommentsAdapter.Host {

    private String reelId, author, commentsKey;
    private CommentsAdapter adapter;
    private ListenerRegistration reg;
    private EditText input;
    private TextView sendBtn, replyChip;
    private Models.Comment replyingTo;
    private boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        reelId = getIntent().getStringExtra("reel_id");
        author = getIntent().getStringExtra("author");
        if (reelId == null) { finish(); return; }
        commentsKey = "reel_" + reelId;

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.card(this));
        root.addView(Ui.pageBar(this, "Reel comments"));

        RecyclerView rv = new RecyclerView(this);
        rv.setBackgroundColor(Ui.card(this));
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new CommentsAdapter(this, this);
        adapter.counterCollection = "reels";
        adapter.counterId = reelId;
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));

        // composer
        LinearLayout wrap = Ui.col(this);
        wrap.setBackgroundColor(Ui.card(this));
        wrap.addView(Ui.divider(this));
        LinearLayout chipRow = Ui.row(this);
        Ui.pad(chipRow, 12, 4, 12, 0);
        replyChip = Ui.tv(this, "", 12, Ui.sub(this));
        replyChip.setVisibility(android.view.View.GONE);
        TextView cancel = Ui.tvBold(this, "✕", 14, Ui.sub(this));
        cancel.setVisibility(android.view.View.GONE);
        cancel.setPadding(Ui.dp(this, 8), 0, 0, 0);
        cancel.setOnClickListener(v -> cancelReply());
        chipRow.addView(replyChip, new LinearLayout.LayoutParams(0, -2, 1));
        chipRow.addView(cancel);
        wrap.addView(chipRow);

        LinearLayout row = Ui.row(this);
        Ui.pad(row, 10, 8, 10, 8);
        ImageView av = new ImageView(this);
        int s = Ui.dp(this, 34);
        row.addView(av, new LinearLayout.LayoutParams(s, s));
        Ui.avatar(this, av, Fb.myPhoto());
        input = Ui.input(this, "Write a comment…");
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1);
        ip.leftMargin = Ui.dp(this, 8);
        row.addView(input, ip);
        sendBtn = Ui.tv(this, "➤", 22, Ui.sub(this));
        sendBtn.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 4), 0);
        row.addView(sendBtn);
        wrap.addView(row);
        root.addView(wrap);

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {
                sendBtn.setTextColor(s.length() > 0 ? Ui.BLUE : Ui.sub(ReelCommentsActivity.this));
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        sendBtn.setOnClickListener(v -> send());
        setContentView(root);
    }

    private void cancelReply() {
        replyingTo = null;
        replyChip.setVisibility(android.view.View.GONE);
        input.setHint("Write a comment…");
    }

    @Override
    protected void onStart() {
        super.onStart();
        reg = Fb.db().collection("comments").whereEqualTo("postId", commentsKey)
                .addSnapshotListener((snap, err) -> {
                    List<Models.Comment> all = new ArrayList<>();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap) {
                            Models.Comment c = Models.Comment.fromDoc(d);
                            if (Fb.me != null && Fb.me.blocked.contains(c.authorId)) continue;
                            all.add(c);
                        }
                    }
                    adapter.setAll(all);
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
    }

    @Override public void replyTo(Models.Comment c) {
        replyingTo = c;
        replyChip.setVisibility(android.view.View.VISIBLE);
        replyChip.setText("Replying to " + c.authorName);
        input.setHint("Reply to " + c.authorName + "…");
        input.requestFocus();
    }

    @Override public void changed() { }

    private void send() {
        if (busy) return;
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        busy = true;
        java.util.Map<String, Object> m = Fb.map(
                "postId", commentsKey,
                "authorId", Fb.uid(),
                "authorName", Fb.myName(),
                "authorPhotoUrl", Fb.myPhoto(),
                "content", text,
                "imageUrl", null,
                "likes", 0,
                "likedBy", new ArrayList<String>(),
                "parentId", replyingTo == null ? null : replyingTo.id,
                "createdAt", FieldValue.serverTimestamp());
        Fb.db().collection("comments").add(m)
                .addOnSuccessListener(x -> {
                    Fb.db().collection("reels").document(reelId)
                            .set(Fb.map("comments", FieldValue.increment(1)), SetOptions.merge());
                    if (author != null) Notify.send(author, "reel_comment", "commented on your reel");
                    if (replyingTo != null && !replyingTo.authorId.equals(Fb.uid()))
                        Notify.send(replyingTo.authorId, "comment_reply", "replied to your comment");
                    replyingTo = null;
                    replyChip.setVisibility(android.view.View.GONE);
                    input.setText("");
                    input.setHint("Write a comment…");
                    busy = false;
                    Ui.hideKeyboard(this);
                })
                .addOnFailureListener(e -> { busy = false; Ui.toast(this, "Failed"); });
    }
}
