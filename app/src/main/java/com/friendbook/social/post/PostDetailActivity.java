package com.friendbook.social.post;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
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

import com.friendbook.social.core.Cloudinary;
import com.friendbook.social.core.FastCache;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Icons;
import com.friendbook.social.core.Notify;
import com.friendbook.social.core.Prefs;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.friendbook.social.profile.ProfileActivity;
import com.friendbook.social.d1.documents.DocumentSnapshot;
import com.friendbook.social.d1.documents.FieldValue;
import com.friendbook.social.d1.documents.ListenerRegistration;
import com.friendbook.social.d1.documents.SetOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * friendbook Pro Post Detail + Full Comments Thread:
 * - Single native RecyclerView (Header = Post + Reactions Summary + Sort Bar; Rows = ALL Comments & Replies)
 * - Fixes 100% of ScrollView height-clipping so every single comment & reply renders smoothly!
 * - Instant 0ms optimistic comment send + photo comment support + @mention notifications.
 */
public class PostDetailActivity extends Activity implements FeedAdapter.Host, CommentsAdapter.Host {

    private static final int REQ_COMMENT_PHOTO = 941;

    private String postId;
    private Models.Post post;
    private FeedAdapter postAdapter;
    private CommentsAdapter commentsAdapter;
    private RecyclerView mainRv;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout commentSwipe;
    private ListenerRegistration postReg;
    private CommentsEngine.Handle commentsHandle;
    private boolean initialLoaded;
    private String retryD1Media="";
    private TextView olderCommentsButton;
    private EditText input;
    private TextView sendBtn, replyChip, sortLabel, whoReactedChip;
    private ImageView mediaThumb;
    private LinearLayout mediaPreviewRow;
    private Uri pendingPhotoUri;
    private Models.Comment replyingTo;
    private final List<Models.Comment> rawComments = new ArrayList<>();
    private boolean busy;
    private int lastSyncedCommentCount = -1;

    private final FastCache.ReactionObserver reactionObserver = (pid, mine, total) -> {
        if (pid != null && pid.equals(postId)) {
            if (postAdapter != null) postAdapter.onFastReactionUpdate(pid, mine, total);
            if (whoReactedChip != null) {
                whoReactedChip.setText(total > 0
                        ? (total + (total == 1 ? " reaction · See all" : " reactions · See all"))
                        : "See reactions");
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarLight(this);
        postId = getIntent().getStringExtra("post_id");
        if (postId == null) { finish(); return; }

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.card(this));

        // Crisp white friendbook top bar
        LinearLayout topBar = Ui.row(this);
        topBar.setBackgroundColor(Ui.card(this));
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(topBar, 6, 6, 12, 6);
        ImageView back = Ui.backArrow(this, Ui.txt(this), v -> finish());
        topBar.addView(back);
        TextView barTitle = Ui.tvBold(this, "Post & Comments", 17, Ui.txt(this));
        topBar.addView(barTitle, new LinearLayout.LayoutParams(0, -2, 1));
        whoReactedChip = Ui.roundChip(this, "👍 Who liked", Ui.inputBg(this), Ui.BLUE, 12);
        Ui.pad(whoReactedChip, 10, 5, 10, 5);
        whoReactedChip.setOnClickListener(v -> Reactions.showWhoReacted(this, postId));
        topBar.addView(whoReactedChip);
        root.addView(topBar);
        root.addView(Ui.divider(this));

        // Build Header View: Post Card + Comments Filter Bar
        LinearLayout headerBox = Ui.col(this);
        headerBox.setBackgroundColor(Ui.bg(this));

        RecyclerView postRv = new RecyclerView(this);
        postRv.setLayoutManager(new LinearLayoutManager(this));
        postRv.setItemAnimator(null);
        postRv.setNestedScrollingEnabled(false);
        postAdapter = new FeedAdapter(this, this);
        postAdapter.showHeader = false;
        postAdapter.showFooter = false;
        postAdapter.showInlineComments = false; // Full comment thread is rendered directly below!
        final boolean isSponsoredAdDetail = postId.startsWith("sponsored_ad_");
        postAdapter.feedEngagementInPage=!isSponsoredAdDetail && com.friendbook.social.core.D1Client.enabled("posts");
        final int adSlotOrdinal = getIntent().getIntExtra("ad_slot_ordinal", 0);
        if (isSponsoredAdDetail) {
            // Render ONLY the 1 real AdMob AdVH card — NEVER render a fake PostVH card above it!
            postAdapter.standaloneAdSlotOrdinal = Math.max(0, adSlotOrdinal);
        } else {
            // Regular user post detail: render ONLY the user's PostVH card (no extra AdVH inside header)
            postAdapter.setAdsConfig("", false);
        }
        postRv.setAdapter(postAdapter);
        headerBox.addView(postRv, new LinearLayout.LayoutParams(-1, -2));

        // 0ms instant preload from FastCache or AdsConfig if post is already in RAM!
        if (!isSponsoredAdDetail) {
            for (Models.Post cached : FastCache.getFeed()) {
                if (cached != null && postId.equals(cached.id)) {
                    post = cached;
                    reactionObserver.onReactionChanged(postId,FastCache.getMyReaction(postId),FastCache.getReactionTotal(postId,post.likes));
                    postAdapter.setPosts(Collections.singletonList(post));
                    if (!post.detailPreview().isEmpty()) {
                        for(Models.Comment comment:post.detailPreview())if(Fb.me==null || Fb.me.blocked==null || !Fb.me.blocked.contains(comment.authorId))rawComments.add(comment);
                    }
                    break;
                }
            }
        } else {
            Models.Post adCached = com.friendbook.social.core.AdsConfig.findCachedAdPostById(postId);
            if (adCached != null) {
                post = adCached;
                if (post.inlineComments != null && !post.inlineComments.isEmpty()) {
                    rawComments.addAll(post.inlineComments);
                }
            }
            postAdapter.notifyDataSetChanged();
        }

        // Comments Filter & Count Bar
        LinearLayout cHeader = Ui.row(this);
        cHeader.setBackgroundColor(Ui.card(this));
        cHeader.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(cHeader, 14, 11, 14, 9);
        sortLabel = Ui.tvBold(this, sortText(Prefs.commentSort(this), 0), 14, Ui.txt(this));
        sortLabel.setOnClickListener(v -> Ui.choose(this, "Filter comments", new String[]{
                "💬 All comments — show every comment & reply",
                "🕐 Newest — show most recent comments first",
                "⭐ Most relevant — top liked & replied first"
        }, idx -> {
            String mode = idx == 1 ? "newest" : (idx == 2 ? "relevant" : "all");
            Prefs.setCommentSort(this, mode);
            commentsAdapter.sortMode = mode;
            sortLabel.setText(sortText(mode, rawComments.size()));
            commentsAdapter.setAll(rawComments);
        }));
        cHeader.addView(sortLabel, new LinearLayout.LayoutParams(0, -2, 1));
        TextView older=Ui.tvBold(this,"View more comments",12,Ui.BLUE);
        olderCommentsButton=older;
        older.setPadding(Ui.dp(this,8),Ui.dp(this,8),Ui.dp(this,8),Ui.dp(this,8));
        older.setOnClickListener(v -> {
            if(commentsHandle==null) return;
            if(!commentsHandle.error.isEmpty()) Ui.toast(this,commentsHandle.error);
            if(commentsHandle.hasMore()) commentsHandle.loadMore(); else Ui.toast(this,"All comments loaded");
            updateCommentPager();
        });
        cHeader.addView(older);
        if(com.friendbook.social.core.D1Client.enabled("posts")){TextView drafts=Ui.tvBold(this,"Unsent",12,Ui.BLUE);drafts.setOnClickListener(v->CommentsEngine.showD1Drafts(this,"posts",postId));cHeader.addView(drafts);}

        headerBox.addView(cHeader);
        headerBox.addView(Ui.divider(this));

        // SINGLE Unified Native RecyclerView — 100% of comments render & scroll cleanly!
        mainRv = new RecyclerView(this);
        mainRv.setBackgroundColor(Ui.card(this));
        mainRv.setLayoutManager(new LinearLayoutManager(this));
        mainRv.setItemAnimator(null);
        commentsAdapter = new CommentsAdapter(this, this);
        commentsAdapter.setShowEmptyWhenNone(false);
        commentsAdapter.counterCollection = "posts";
        commentsAdapter.counterId = postId;
        commentsAdapter.postAuthorId = post != null ? post.authorId : "";
        commentsAdapter.sortMode = Prefs.commentSort(this);
        commentsAdapter.setHeaderView(headerBox);
        if (!rawComments.isEmpty()) {
            sortLabel.setText(sortText(commentsAdapter.sortMode, rawComments.size()));
            commentsAdapter.setAll(rawComments);
        }
        mainRv.setAdapter(commentsAdapter);
        com.friendbook.social.core.NearEndPaging.attachComments(mainRv,()->commentsAdapter.sortMode,()->{if(commentsHandle!=null && commentsHandle.hasMore())commentsHandle.loadMore();});
        commentSwipe = new androidx.swiperefreshlayout.widget.SwipeRefreshLayout(this);
        commentSwipe.setColorSchemeColors(Ui.BLUE);
        commentSwipe.addView(mainRv);
        commentSwipe.setOnRefreshListener(() -> {
            initialLoaded = false;
            loadPostAndComments(true);
        });
        root.addView(commentSwipe, new LinearLayout.LayoutParams(-1, 0, 1));

        // Bottom Comment Composer Bar
        LinearLayout composerWrap = Ui.col(this);
        composerWrap.setBackgroundColor(Ui.card(this));
        composerWrap.addView(Ui.divider(this));

        // Quick Emoji Reaction Bar (friendbook quick comment strip)
        LinearLayout emojiStrip = Ui.row(this);
        Ui.pad(emojiStrip, 10, 5, 10, 2);
        String[] quickEmojis = {"❤️", "🙌", "🔥", "😂", "😍", "😢", "👏", "💯"};
        for (final String em : quickEmojis) {
            TextView et = Ui.tv(this, em, 18, Ui.txt(this));
            et.setGravity(Gravity.CENTER);
            et.setOnClickListener(v -> {
                int start = Math.max(0, input.getSelectionStart());
                input.getText().insert(start, em);
            });
            emojiStrip.addView(et, new LinearLayout.LayoutParams(0, -2, 1));
        }
        composerWrap.addView(emojiStrip);

        LinearLayout chipRow = Ui.row(this);
        Ui.pad(chipRow, 12, 4, 12, 0);
        replyChip = Ui.tvBold(this, "", 12, Ui.BLUE);
        replyChip.setVisibility(View.GONE);
        TextView cancelReply = Ui.tvBold(this, "✕", 14, Ui.sub(this));
        cancelReply.setVisibility(View.GONE);
        cancelReply.setPadding(Ui.dp(this, 8), 0, 0, 0);
        cancelReply.setOnClickListener(v -> {
            com.friendbook.social.core.Mentions.clearReplyTarget(input);
            replyingTo = null;
            replyChip.setVisibility(View.GONE);
            cancelReply.setVisibility(View.GONE);
        });
        chipRow.addView(replyChip, new LinearLayout.LayoutParams(0, -2, 1));
        chipRow.addView(cancelReply);
        composerWrap.addView(chipRow);

        // Pending photo comment preview
        mediaPreviewRow = Ui.row(this);
        Ui.pad(mediaPreviewRow, 12, 6, 12, 2);
        mediaPreviewRow.setVisibility(View.GONE);
        mediaThumb = new ImageView(this);
        mediaThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mediaPreviewRow.addView(mediaThumb, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)));
        TextView removePhoto = Ui.tvBold(this, "  ✕ Remove photo", 12, Ui.RED);
        removePhoto.setOnClickListener(v -> {
            pendingPhotoUri = null;
            mediaPreviewRow.setVisibility(View.GONE);
            refreshSendState();
        });
        mediaPreviewRow.addView(removePhoto);
        composerWrap.addView(mediaPreviewRow);

        LinearLayout composer = Ui.row(this);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(composer, 10, 6, 10, 8);
        ImageView me = new ImageView(this);
        int s = Ui.dp(this, 34);
        composer.addView(me, new LinearLayout.LayoutParams(s, s));
        Ui.avatar(this, me, Fb.myPhoto());

        ImageView camBtn = Icons.icon(this, Icons.CAMERA, 22, Ui.sub(this));
        camBtn.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
        camBtn.setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
            pick.setType("image/*");
            startActivityForResult(Intent.createChooser(pick, "Attach comment photo"), REQ_COMMENT_PHOTO);
        });
        composer.addView(camBtn);

        input = Ui.input(this, "Write a comment…");
        com.friendbook.social.core.Mentions.attach(input, () -> post == null ? null : post.authorId);
        input.setBackground(Ui.round(this, Ui.inputBg(this), 20));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1);
        ip.leftMargin = Ui.dp(this, 6);
        composer.addView(input, ip);

        sendBtn = Ui.tvBold(this, "Send", 15, Ui.sub(this));
        sendBtn.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
        sendBtn.setOnClickListener(v -> send());
        composer.addView(sendBtn);
        composerWrap.addView(composer);
        root.addView(composerWrap);

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { refreshSendState(); }
        });

        setContentView(root);
    }

    private void refreshSendState() {
        boolean ready = !input.getText().toString().trim().isEmpty() || pendingPhotoUri != null;
        sendBtn.setTextColor(ready ? Ui.BLUE : Ui.sub(this));
    }

    private String sortText(String mode, int count) {
        String countPart = count > 0 ? " (" + count + ")" : "";
        if ("newest".equals(mode)) return "Newest" + countPart + " ▾";
        if ("relevant".equals(mode)) return "Most relevant" + countPart + " ▾";
        return "All comments" + countPart + " ▾";
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_COMMENT_PHOTO && res == RESULT_OK && data != null && data.getData() != null) {
            pendingPhotoUri = data.getData();
            mediaThumb.setImageURI(pendingPhotoUri);
            mediaPreviewRow.setVisibility(View.VISIBLE);
            refreshSendState();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        FastCache.addReactionObserver(reactionObserver);
        if (!initialLoaded) {
            loadPostAndComments(false);
        }
    }

    private void loadPostAndComments(final boolean forceHeader) {
        initialLoaded = true;
        commentsAdapter.setShowEmptyWhenNone(false);
        commentsAdapter.notifyDataSetChanged();
        if (postReg != null) { postReg.remove(); postReg = null; }
        if (commentsHandle != null) { commentsHandle.remove(); commentsHandle = null; }
        final boolean[] headerRendered = {!forceHeader && post != null};
        if(forceHeader || post==null || !postAdapter.feedEngagementInPage)postReg = Fb.db().collection("posts").document(postId).addSnapshotListener((d, e) -> {
            // A listener may first emit null while the D1/Firestore-compatible endpoint is
            // connecting, or report an error during an offline/network transition. Do not close
            // the comments screen on a transient read failure; retain cached post/comments and
            // let the user retry via swipe-to-refresh. Only a confirmed missing server document
            // should dismiss a post detail that has no cached copy.
            if (e != null || d == null) {
                if (post == null && commentSwipe != null) commentSwipe.setRefreshing(false);
                return;
            }
            if (!d.exists()) {
                if (post != null || (postId != null && postId.startsWith("sponsored_ad_"))) return;
                if (!d.getMetadata().isFromCache()) finish();
                return;
            }
            post = Models.Post.fromDoc(d);
            commentsAdapter.postAuthorId = post.authorId;
            commentsAdapter.pinnedCommentId = post.pinnedCommentId;
            if (postId != null && postId.startsWith("sponsored_ad_")) {
                postAdapter.notifyDataSetChanged();
            } else if (!headerRendered[0]) {
                headerRendered[0] = true;
                postAdapter.setPosts(Collections.singletonList(post));
            }
            int likes = FastCache.getReactionTotal(postId, post.likes);
            whoReactedChip.setText(likes > 0
                    ? (likes + (likes == 1 ? " reaction · See all" : " reactions · See all"))
                    : "See reactions");
        });

        // Latest canonical page stays live; older pages are explicit one-shot reads.
        commentsHandle = CommentsEngine.watchPostDetail(postId, forceHeader?null:post, (allComments, pinnedId) -> {
            if (commentsHandle==null) return;
            updateCommentPager();
            if(commentsHandle.isLoading())return;
            if(commentSwipe!=null)commentSwipe.setRefreshing(false);
            if(!commentsHandle.hasInitialPage())return;
            commentsAdapter.setShowEmptyWhenNone(true);
            rawComments.clear();
            rawComments.addAll(allComments);
            commentsAdapter.pinnedCommentId = pinnedId == null ? "" : pinnedId;
            sortLabel.setText(sortText(commentsAdapter.sortMode, rawComments.size()));
            commentsAdapter.setAll(rawComments);
        }, this::finish);
        commentsHandle.publishCached();
        updateCommentPager();
    }

    private void updateCommentPager() {
        if(olderCommentsButton==null || commentsHandle==null) return;
        olderCommentsButton.setText(commentsHandle.isLoading()?"Loading comments…":
                !commentsHandle.error.isEmpty()?(commentsHandle.hasInitialPage()?"Retry older comments":"Retry loading comments"):commentsHandle.hasMore()?"View more comments":"All comments loaded");
        olderCommentsButton.setVisibility(commentsHandle.isLoading() || commentsHandle.hasMore()
                || !commentsHandle.error.isEmpty()?View.VISIBLE:View.GONE);
        olderCommentsButton.setEnabled(!commentsHandle.isLoading() && (commentsHandle.hasMore() || !commentsHandle.error.isEmpty()));
    }

    @Override
    protected void onStop() {
        super.onStop();
        FastCache.removeReactionObserver(reactionObserver);
        if (isFinishing()) {
            if (postReg != null) postReg.remove();
            if (commentsHandle != null) commentsHandle.remove();
        }
    }

    private void send() {
        if (busy) return;
        if (!com.friendbook.social.core.Admin.checkNotSuspended(this)) return;
        String text = input.getText().toString().trim();
        if (text.isEmpty() && pendingPhotoUri == null && retryD1Media.isEmpty()) return;
        String me = Fb.uid();
        if (me == null) return;
        busy = true;
        final List<Map<String,Object>> mentions = com.friendbook.social.core.Mentions.capture(input);

        if(com.friendbook.social.core.D1Client.enabled("posts") && pendingPhotoUri==null && !retryD1Media.isEmpty()){String media=retryD1Media;retryD1Media="";writeCommentDoc(me,text,media,mentions);}
        else if (pendingPhotoUri != null) {
            Uri toUpload = pendingPhotoUri;
            Ui.toast(this, "Uploading comment photo…");
            String path = "comments/" + me + "_" + System.currentTimeMillis() + ".jpg";
            Cloudinary.uploadOrFirebase(this, path, toUpload)
                    .addOnSuccessListener(url -> {
                        pendingPhotoUri = null;
                        mediaPreviewRow.setVisibility(View.GONE);
                        writeCommentDoc(me, text, url, mentions);
                    })
                    .addOnFailureListener(err -> {
                        busy = false;
                        Ui.toast(PostDetailActivity.this, "Photo upload failed: " + err.getMessage());
                    });
        } else {
            writeCommentDoc(me, text, "", mentions);
        }
    }

    private void writeCommentDoc(String me, String text, String mediaUrl, List<Map<String,Object>> mentions) {
        final Models.Comment replyTarget = replyingTo;
        String parentId = replyTarget != null ? replyTarget.id : "";

        input.setText("");
        replyingTo = null;
        replyChip.setVisibility(View.GONE);
        if (replyChip.getParent() instanceof LinearLayout) {
            ((LinearLayout) replyChip.getParent()).getChildAt(1).setVisibility(View.GONE);
        }
        refreshSendState();

        // Writes atomically to posts/{postId}.commentsMap.<newId> (100% visible to ALL users!) + mirrors!
        Models.Comment local = CommentsEngine.sendComment("posts", postId, postId, text, mediaUrl, parentId, mentions,
                new CommentsEngine.PostCallback() {
                    @Override public void onSaved(String commentId) {
                        if (post != null && !me.equals(post.authorId)) {
                            Notify.sendForComment(post.authorId, "comment",
                                    Fb.myName() + " commented on your post", "posts",postId,commentId);
                        }
                        if (replyTarget != null && !me.equals(replyTarget.authorId)) {
                            Notify.sendForComment(replyTarget.authorId, "reply",
                                    Fb.myName() + " replied to your comment", "posts",postId,commentId);
                        }
                    }
                    @Override public void onComplete(boolean ok,String errMsg) {
                        if(!ok)Ui.toast(PostDetailActivity.this, errMsg != null ? errMsg : "Comment failed");
                    }
                });

        busy = false; // Queueing is complete; never wait for a remote ACK to accept the next comment.
        input.requestFocus();
        if (local != null) {
            rawComments.add(local);
            sortLabel.setText(sortText(commentsAdapter.sortMode, rawComments.size()));
            commentsAdapter.setAll(rawComments);
            mainRv.scrollToPosition(Math.max(0, commentsAdapter.getItemCount() - 1));
        } else {
            busy = false;
            if(com.friendbook.social.core.D1Client.enabled("posts")){com.friendbook.social.core.Mentions.restore(input,text,mentions);retryD1Media=mediaUrl;}
        }
    }

    // ---------- hosts ----------
    @Override public void onReply(Models.Comment parent) {
        replyingTo = parent;
        com.friendbook.social.core.Mentions.setReplyTarget(input, parent.authorId, parent.authorName);
        replyChip.setText("Replying to " + parent.authorName);
        replyChip.setVisibility(View.VISIBLE);
        ((LinearLayout) replyChip.getParent()).getChildAt(1).setVisibility(View.VISIBLE);
        input.requestFocus();
    }

    @Override public void openComments(Models.Post p) {
        if (commentsAdapter.getCommentRowCount() > 0) {
            mainRv.smoothScrollToPosition(1);
        } else {
            input.requestFocus();
        }
    }

    @Override public void openProfile(String uid) {
        Intent i = new Intent(this, ProfileActivity.class);
        i.putExtra("uid", uid);
        startActivity(i);
    }

    @Override public void editPost(Models.Post p) {
        Intent i = new Intent(this, CreatePostActivity.class);
        i.putExtra("edit_id", p.id);
        startActivity(i);
    }

    @Override public void createStory() { }
    @Override public void openStoryGroup(com.friendbook.social.story.StoriesAdapter.Group g) { }
    @Override public void loadMore() { }
    @Override public boolean hasMore() { return false; }
}
