package com.friendbook.social.story;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.VideoView;

import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Notify;
import com.friendbook.social.core.Prefs;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.friendbook.social.profile.ProfileActivity;
import com.friendbook.social.settings.ArchiveTrashActivity;
import com.friendbook.social.d1.documents.DocumentSnapshot;
import com.friendbook.social.d1.documents.FieldValue;
import com.friendbook.social.d1.documents.ListenerRegistration;
import com.friendbook.social.d1.documents.SetOptions;
import com.friendbook.social.d1.documents.WriteBatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 📖 StoryViewerActivity — Full friendbook Story Viewer:
 * Supports Photo, Video & Aa Text Stories, Music/Mood badge, Quick Emoji Reactions
 * (❤️ 😂 😮 😢 🔥 👏), Highlight pinning, Story Archive mode & Mute author.
 */
public class StoryViewerActivity extends Activity {

    private static final String[] QUICK_EMOJIS = {"❤️", "😂", "😮", "😢", "🔥", "👏"};

    private final List<Models.Story> stories = new ArrayList<>();
    private int index = 0;
    private ListenerRegistration reg;
    private String authorId, specificStoryId;
    private boolean viewerFriend;
    private String viewerActor;
    private FrameLayout root;
    private com.friendbook.social.core.CursorPager storyPages;
    private TextView moreStories;
    private boolean storyMore=true,storyBusy,advanceAfterPage;
    private int storyRequest;

    private ImageView img, headerAvatar;
    private VideoView video;
    private TextView textCanvas, moodBadge, nameT, timeT, captionT, viewersT;
    private LinearLayout segments, replyArea, ownerRow;
    private EditText replyInput;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private Runnable timer;
    private ValueAnimator anim;
    private TextView storyStatus;
    private Runnable mediaTimeout;
    private int playbackToken;
    private boolean ready;
    private final java.util.Set<String> recordingViews=new java.util.HashSet<>();


    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        com.friendbook.social.core.Ui.statusBar(getWindow(), Color.BLACK);
        authorId = getIntent().getStringExtra("author");
        specificStoryId = getIntent().getStringExtra("story_id");
        if (authorId == null) { finish(); return; }

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(img, new FrameLayout.LayoutParams(-1, -1));

        video = new VideoView(this);
        video.setVisibility(View.GONE);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1));

        textCanvas = new TextView(this);
        com.friendbook.social.core.LinkUtils.makeLinksClickable(textCanvas);
        textCanvas.setGravity(Gravity.CENTER);
        textCanvas.setTextColor(Color.WHITE);
        textCanvas.setTextSize(26);
        textCanvas.setTypeface(Typeface.DEFAULT_BOLD);
        Ui.pad(textCanvas, 28, 80, 28, 120);
        textCanvas.setVisibility(View.GONE);
        root.addView(textCanvas, new FrameLayout.LayoutParams(-1, -1));

        // Tap zones (left 30% prev, right 30% next)
        View left = new View(this);
        View right = new View(this);
        int zoneW = getResources().getDisplayMetrics().widthPixels * 30 / 100;
        left.setLayoutParams(new FrameLayout.LayoutParams(zoneW, -1, Gravity.START));
        right.setLayoutParams(new FrameLayout.LayoutParams(zoneW, -1, Gravity.END));
        root.addView(left);
        root.addView(right);
        left.setOnClickListener(v -> step(-1));
        right.setOnClickListener(v -> step(1));

        // Top overlay: segments + author + mood + ••• + close
        LinearLayout top = Ui.col(this);
        Ui.pad(top, 12, 28, 12, 0);
        segments = Ui.row(this);
        top.addView(segments, new LinearLayout.LayoutParams(-1, Ui.dp(this, 3)));

        LinearLayout head = Ui.row(this);
        head.setPadding(0, Ui.dp(this, 10), 0, 0);
        headerAvatar = new ImageView(this);
        int s = Ui.dp(this, 36);
        head.addView(headerAvatar, new LinearLayout.LayoutParams(s, s));
        Ui.avatar(this, headerAvatar, getIntent().getStringExtra("authorPhoto"));

        LinearLayout nc = Ui.col(this);
        LinearLayout.LayoutParams ncp = new LinearLayout.LayoutParams(0, -2, 1);
        ncp.leftMargin = Ui.dp(this, 8);
        nameT = Ui.tvBold(this, "", 14, Color.WHITE);
        timeT = Ui.tv(this, "", 11, 0xCCFFFFFF);
        nc.addView(nameT);
        nc.addView(timeT);
        head.addView(nc, ncp);

        moodBadge = Ui.tvBold(this, "", 11, Color.WHITE);
        moodBadge.setBackground(Ui.round(this, 0x55000000, 12));
        Ui.pad(moodBadge, 8, 4, 8, 4);
        moodBadge.setVisibility(View.GONE);
        head.addView(moodBadge);

        TextView menuBtn = Ui.tvBold(this, "⋮", 20, Color.WHITE);
        menuBtn.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
        menuBtn.setOnClickListener(this::storyMenu);
        head.addView(menuBtn);

        TextView close = Ui.tvBold(this, "✕", 19, Color.WHITE);
        close.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        close.setOnClickListener(v -> finish());
        head.addView(close);

        top.addView(head);
        root.addView(top);

        headerAvatar.setOnClickListener(v -> openProfile());
        nameT.setOnClickListener(v -> openProfile());

        // Caption (for photo/video stories)
        captionT = Ui.tvBold(this, "", 15, Color.WHITE);
        captionT.setGravity(Gravity.CENTER);
        captionT.setBackgroundColor(0x66000000);
        Ui.pad(captionT, 14, 8, 14, 8);
        FrameLayout.LayoutParams capp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        capp.setMargins(0, 0, 0, Ui.dp(this, 128));
        root.addView(captionT, capp);

        // Owner bottom bar
        ownerRow = Ui.row(this);
        ownerRow.setGravity(Gravity.CENTER);
        ownerRow.setBackground(Ui.round(this, 0x66000000, 20));
        Ui.pad(ownerRow, 12, 6, 12, 6);
        FrameLayout.LayoutParams op = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        op.setMargins(0, 0, 0, Ui.dp(this, 26));
        ownerRow.setLayoutParams(op);

        viewersT = Ui.tvBold(this, "", 13, Color.WHITE);
        viewersT.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        viewersT.setOnClickListener(v -> showViewers());
        ownerRow.addView(viewersT);

        TextView hiBtn = Ui.tvBold(this, "✨ Highlight", 13, Color.WHITE);
        hiBtn.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        hiBtn.setOnClickListener(v -> addCurrentToHighlights());
        ownerRow.addView(hiBtn);

        TextView del = Ui.tvBold(this, "🗑 Delete", 13, 0xFFFF8A80);
        del.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        del.setOnClickListener(v -> deleteCurrent());
        ownerRow.addView(del);
        root.addView(ownerRow);

        // Viewer bottom bar: Quick Emoji Reactions + Reply composer
        replyArea = Ui.col(this);
        Ui.pad(replyArea, 10, 6, 10, 14);
        FrameLayout.LayoutParams rap = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        replyArea.setLayoutParams(rap);

        LinearLayout emojiRow = Ui.row(this);
        emojiRow.setGravity(Gravity.CENTER);
        emojiRow.setPadding(0, 0, 0, Ui.dp(this, 8));
        for (String em : QUICK_EMOJIS) {
            TextView ev = Ui.tv(this, em, 24, Color.WHITE);
            ev.setGravity(Gravity.CENTER);
            ev.setPadding(Ui.dp(this, 8), Ui.dp(this, 2), Ui.dp(this, 8), Ui.dp(this, 2));
            ev.setOnClickListener(v -> sendQuickReaction(em));
            emojiRow.addView(ev);
        }
        replyArea.addView(emojiRow);

        LinearLayout inputRow = Ui.row(this);
        replyInput = Ui.input(this, "Send message…");
        replyInput.setTextColor(Color.WHITE);
        replyInput.setHintTextColor(0xAAFFFFFF);
        replyInput.setBackground(Ui.roundStroke(this, 0x44000000, 22, 0x66FFFFFF, 1));
        inputRow.addView(replyInput, new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1));
        TextView sendReply = Ui.btn(this, "Send");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, Ui.dp(this, 42));
        sp.leftMargin = Ui.dp(this, 8);
        sendReply.setOnClickListener(v -> sendReply());
        inputRow.addView(sendReply, sp);
        replyArea.addView(inputRow);

        root.addView(replyArea);
        moreStories=Ui.tvBold(this,"More stories",13,Color.WHITE);
        Ui.pad(moreStories,12,8,12,8);moreStories.setBackgroundColor(0x88000000);
        FrameLayout.LayoutParams moreParams=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.END);
        moreParams.topMargin=Ui.dp(this,92);root.addView(moreStories,moreParams);
        moreStories.setOnClickListener(v->{if(storyPages!=null){advanceAfterPage=!stories.isEmpty();storyPages.next();}});
        storyStatus=Ui.tvBold(this,"Loading story…",16,Color.WHITE);
        Ui.pad(storyStatus,16,10,16,10);
        storyStatus.setBackgroundColor(0xCC202020);
        root.addView(storyStatus,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
        setContentView(root);
    }

    private void openProfile() {
        Intent i = new Intent(this, ProfileActivity.class);
        i.putExtra("uid", authorId);
        startActivity(i);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if(storyStatus!=null)loadStories();
    }

    private void loadStories(){
        status("Loading story…",null);
        final int request=++storyRequest;final String actor=Fb.uid();viewerActor=actor;
        if(actor==null){finish();return;}
        if(actor.equals(authorId)){viewerFriend=true;loadStoryPages(request);return;}
        Fb.db().collection("friends").whereIn("participants",java.util.Arrays.asList(java.util.Arrays.asList(actor,authorId),java.util.Arrays.asList(authorId,actor)))
                .count().get(com.friendbook.social.d1.documents.AggregateSource.SERVER).addOnSuccessListener(count->{
                    if(request!=storyRequest || !java.util.Objects.equals(actor,Fb.uid()) || isFinishing())return;
                    viewerFriend=count.getCount()>0;
                    if(viewerFriend || (Fb.me!=null && Fb.me.following.contains(authorId)))loadStoryPages(request);else status("This story is unavailable",null);
                }).addOnFailureListener(error->{if(request==storyRequest)status("Story could not load · Tap to retry",this::reload);});
    }
    private boolean allowedStory(Models.Story story){
        return java.util.Objects.equals(viewerActor,Fb.uid()) && com.friendbook.social.core.StoryAudience.visible(Fb.uid(),story.authorId,story.privacy,viewerFriend,
                Fb.me!=null && Fb.me.following.contains(story.authorId),Fb.me!=null && Fb.me.blocked.contains(story.authorId),Prefs.isStoryAuthorMuted(this,story.authorId));
    }
    private void loadStoryPages(int request){
        if(specificStoryId!=null){
            moreStories.setVisibility(View.GONE);
            Fb.db().collection("stories").document(specificStoryId).get().addOnSuccessListener(doc->{
                if(request!=storyRequest || isFinishing())return;
                if(!doc.exists()){status("This story is no longer available",null);return;}Models.Story story=Models.Story.fromDoc(doc);
                if(!authorId.equals(story.authorId) || !allowedStory(story)
                        || (getIntent().getBooleanExtra("active_story_only",false) && story.expired())){status("This story is unavailable",null);return;}stories.clear();stories.add(story);index=0;show();
            }).addOnFailureListener(e->{if(request==storyRequest)status("Story could not load · Tap to retry",this::reload);});
            return;
        }
        storyPages=new com.friendbook.social.core.CursorPager(Fb.db().collection("stories").whereEqualTo("authorId",authorId),10,"story-viewer:"+authorId,(docs,more,busy,error)->{
            if(request!=storyRequest || isFinishing())return;
            storyMore=more;storyBusy=busy;moreStories.setEnabled(!busy && more);
            moreStories.setText(busy?"Loading…":!error.isEmpty()?"Retry stories":more?"More stories":"All loaded");
            if(!error.isEmpty()){if(stories.isEmpty() || advanceAfterPage)status("Stories could not load · Tap to retry",this::reload);return;}
            if(busy)return;
            String current=stories.isEmpty()?null:stories.get(Math.min(index,stories.size()-1)).id;
            stories.clear();for(DocumentSnapshot doc:docs){Models.Story story=Models.Story.fromDoc(doc);if(!story.expired() && allowedStory(story))stories.add(story);}
            // Keep append order stable: cursor doc-ID order, never re-sort a growing sequence under the viewer.
            if(advanceAfterPage){advanceAfterPage=false;int found=-1;for(int i=0;i<stories.size();i++)if(stories.get(i).id.equals(current))found=i;
                if(found+1<stories.size())index=found+1;
                else if(!more){finish();return;}
                else {stopTimer();status("No active stories on this page · Tap More stories",null);return;}
            }else if(current!=null){for(int i=0;i<stories.size();i++)if(stories.get(i).id.equals(current))index=i;}
            // Never drain filtered or expired pages in the background. A user
            // can explicitly request the next page when this one is empty.
            if(stories.isEmpty()){
                if(!more)status("No active stories",null);
                else status("No active stories on this page · Tap More stories",null);
                return;
            }
            index=Math.min(index,stories.size()-1);show();
        });storyPages.start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
        storyRequest++;if(storyPages!=null){storyPages.close();storyPages=null;}
        if(video!=null)clearMedia();
    }

    private void reload(){
        clearMedia();
        if(storyPages!=null){storyPages.close();storyPages=null;}
        advanceAfterPage=false;
        status("Loading story…",null);
        loadStories();
    }

    private void status(String message,Runnable retry){
        storyStatus.setText(message);
        storyStatus.setVisibility(View.VISIBLE);
        storyStatus.setOnClickListener(retry==null?null:v->retry.run());
        storyStatus.setClickable(retry!=null);
    }

    private void clearMedia(){
        playbackToken++;
        ready=false;
        if(mediaTimeout!=null)H.removeCallbacks(mediaTimeout);
        stopTimer();
        video.setOnPreparedListener(null);
        video.setOnCompletionListener(null);
        video.setOnErrorListener(null);
        video.stopPlayback();
        com.bumptech.glide.Glide.with(this).clear(img);
    }

    private void failed(int token){
        if(token!=playbackToken || isFinishing())return;
        clearMedia();
        status("Story media could not load · Tap to retry",this::show);
    }

    private void ready(int token,Models.Story story,boolean isVideo){
        if(token!=playbackToken || ready || isFinishing())return;
        if(mediaTimeout!=null)H.removeCallbacks(mediaTimeout);
        ready=true;
        storyStatus.setVisibility(View.GONE);
        animateSegment(isVideo?Math.max(1,video.getDuration()):5500);
        if(!isVideo){timer=()->{if(token==playbackToken)step(1);};H.postDelayed(timer,5500);}
        String actor=Fb.uid();
        String viewKey=actor+":"+story.id;
        if(actor!=null && !actor.equals(story.authorId) && !story.viewedBy.contains(actor) && recordingViews.add(viewKey)){
            Fb.db().storyEngagement(story.id,"view",null).addOnCompleteListener(task->{
                recordingViews.remove(viewKey);
                if(task.isSuccessful() && actor.equals(Fb.uid()) && !story.viewedBy.contains(actor)) story.viewedBy.add(actor);
            });
        }
    }

    private void show() {
        if(stories.isEmpty())return;
        clearMedia();
        final int token=playbackToken;
        status("Loading story…",null);
        mediaTimeout=()->failed(token);
        H.postDelayed(mediaTimeout,20000L);
        Models.Story s = stories.get(index);
        nameT.setText(s.authorName);
        timeT.setText(Format.ago(s.createdAt) + ("Friends".equalsIgnoreCase(s.privacy) ? " · 👥" : " · 🌐"));
        Ui.avatar(this, headerAvatar, s.authorPhoto);

        if (s.musicMood != null && !s.musicMood.isEmpty()) {
            moodBadge.setVisibility(View.VISIBLE);
            moodBadge.setText(s.musicMood);
        } else moodBadge.setVisibility(View.GONE);

        boolean mine = s.authorId.equals(Fb.uid());
        ownerRow.setVisibility(mine ? View.VISIBLE : View.GONE);
        replyArea.setVisibility(mine ? View.GONE : View.VISIBLE);
        viewersT.setText("👁 " + s.viewedBy.size()
                + (s.reactions.isEmpty() ? "" : "  ❤️ " + s.reactions.size()));

        buildSegments();

        if ("text".equalsIgnoreCase(s.mediaType)) {
            video.stopPlayback();
            video.setVisibility(View.GONE);
            img.setVisibility(View.GONE);
            captionT.setVisibility(View.GONE);
            textCanvas.setVisibility(View.VISIBLE);
            int bg = s.bgColor != 0 ? s.bgColor : Ui.BLUE;
            textCanvas.setBackground(Ui.gradient(bg, Ui.blend(Color.BLACK, bg, 0.45f), 0));
            Typeface tf = s.fontStyle == 1 ? Typeface.SERIF
                    : s.fontStyle == 2 ? Typeface.MONOSPACE : Typeface.DEFAULT_BOLD;
            textCanvas.setTypeface(tf, Typeface.BOLD);
            textCanvas.setText(s.caption == null ? "" : s.caption);
            ready(token,s,false);
        } else if(s.mediaUrl==null || s.mediaUrl.isEmpty()){
            failed(token);
        } else if ("video".equalsIgnoreCase(s.mediaType)) {
            textCanvas.setVisibility(View.GONE);
            img.setVisibility(View.GONE);
            video.setVisibility(View.VISIBLE);
            captionT.setText(s.caption == null ? "" : s.caption);
            captionT.setVisibility(s.caption == null || s.caption.isEmpty() ? View.GONE : View.VISIBLE);
            video.setOnPreparedListener(mp -> {
                if(token!=playbackToken)return;
                mp.setOnInfoListener((player,what,extra)->{
                    if(what==android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START)ready(token,s,true);
                    return false;
                });
                video.start();
            });
            video.setOnCompletionListener(mp -> {if(token==playbackToken && ready)step(1);});
            String stream = com.friendbook.social.reels.ReelVideoCache.optimizeStreamUrl(this, s.mediaUrl);
            boolean[] fallback = {stream.equals(s.mediaUrl)};
            video.setOnErrorListener((mp,what,extra)->{
                H.post(() -> {
                    if (token != playbackToken) return;
                    if (!fallback[0]) { fallback[0] = true; video.setVideoURI(Uri.parse(s.mediaUrl)); }
                    else failed(token);
                });
                return true;
            });
            video.setVideoURI(com.friendbook.social.core.MediaDiskCache.uriForPlayback(this, stream)); // STEP 6: play from disk cache
        } else {
            video.stopPlayback();
            video.setVisibility(View.GONE);
            textCanvas.setVisibility(View.GONE);
            img.setVisibility(View.VISIBLE);
            captionT.setText(s.caption == null ? "" : s.caption);
            captionT.setVisibility(s.caption == null || s.caption.isEmpty() ? View.GONE : View.VISIBLE);
            loadImage(token,s,Ui.optimizeImageUrl(this,s.mediaUrl,false));
        }
    }

    private void loadImage(int token,Models.Story s,String url){
            com.bumptech.glide.Glide.with(this).load(url).fitCenter().dontAnimate()
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                    .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                        @Override public boolean onLoadFailed(com.bumptech.glide.load.engine.GlideException error,Object model,
                                com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,boolean first){
                            H.post(()->{
                                if(token!=playbackToken || isFinishing())return;
                                if(!s.mediaUrl.equals(url))loadImage(token,s,s.mediaUrl);
                                else failed(token);
                            });return true;
                        }
                        @Override public boolean onResourceReady(android.graphics.drawable.Drawable drawable,Object model,
                                com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,com.bumptech.glide.load.DataSource source,boolean first){
                            H.post(()->ready(token,s,false));return token!=playbackToken;
                        }
                    }).into(img);
    }

    private void storyMenu(View anchor) {
        if (stories.isEmpty()) return;
        Models.Story s = stories.get(index);
        boolean mine = s.authorId.equals(Fb.uid());
        PopupMenu pm = new PopupMenu(this, anchor);
        if (mine) {
            pm.getMenu().add("✨ Add to Profile Highlights");
            pm.getMenu().add("📦 Open Story Archive");
            pm.getMenu().add("🗑 Delete story");
        } else {
            pm.getMenu().add("👤 View " + s.authorName + "'s profile");
            pm.getMenu().add("🔇 Mute " + s.authorName + "'s stories");
            pm.getMenu().add("🚩 Report story");
            pm.getMenu().add("🚫 Block " + s.authorName);
        }
        pm.setOnMenuItemClickListener(it -> {
            String t = it.getTitle().toString();
            if (t.contains("Highlights")) addCurrentToHighlights();
            else if (t.contains("Archive")) startActivity(new Intent(this, ArchiveTrashActivity.class));
            else if (t.contains("Delete")) deleteCurrent();
            else if (t.contains("profile")) openProfile();
            else if (t.contains("Mute")) {
                Prefs.muteStoryAuthor(this, s.authorId, true);
                Ui.toast(this, "Muted " + s.authorName + "'s stories");
                finish();
            } else if (t.contains("Block")) {
                String myUid = Fb.uid();
                if (myUid != null && s.authorId != null) {
                    Ui.confirm(this, "Block " + s.authorName + "?",
                            "You won't see their stories, posts, or messages anymore.", "Block", () -> {
                                Fb.db().collection("users").document(myUid)
                                        .set(Fb.map("blockedUsers", FieldValue.arrayUnion(s.authorId)), SetOptions.merge());
                                if (Fb.me != null && Fb.me.blocked != null && !Fb.me.blocked.contains(s.authorId)) {
                                    Fb.me.blocked.add(s.authorId);
                                }
                                Ui.toast(this, "Blocked " + s.authorName + " 🚫");
                                finish();
                            });
                }
            } else if (t.contains("Report")) {
                final String[] reasons = {
                        "🔞 Nudity or explicit sexual content",
                        "🚨 Child safety / CSAE violation (Zero Tolerance)",
                        "🩸 Graphic violence or self-harm",
                        "😡 Harassment or hate speech",
                        "🎭 Spam or scam"
                };
                Ui.choose(this, "🚩 Report story", reasons, w -> {
                    Map<String, Object> rep = new HashMap<>();
                    rep.put("targetId", s.id);
                    rep.put("authorId", s.authorId);
                    rep.put("type", "story");
                    rep.put("reason", reasons[w]);
                    rep.put("reporterId", Fb.uid());
                    rep.put("createdAt", FieldValue.serverTimestamp());
                    Fb.db().collection("reports").add(rep);
                    if (w == 0 || w == 1 || w == 2) {
                        com.friendbook.social.core.ContentSafetyEngine.autoDeleteContent(
                                this, "stories", s.id, s.authorId,
                                w == 1 ? "csae" : (w == 0 ? "adult_nudity" : "gore_violence"),
                                reasons[w], this::finish);
                        Ui.toast(this, "🛡 Violating story removed & reported ✓");
                    } else {
                        Prefs.muteStoryAuthor(this, s.authorId, true);
                        Ui.toast(this, "Story reported & hidden ✓");
                        finish();
                    }
                });
            }
            return true;
        });
        pm.show();
    }

    private void addCurrentToHighlights() {
        if (stories.isEmpty() || Fb.uid() == null) return;
        Models.Story s = stories.get(index);
        Fb.db().collection("users").document(Fb.uid())
                .update("highlightStoryIds", FieldValue.arrayUnion(s.id))
                .addOnSuccessListener(x -> Ui.toast(this, "Pinned to Profile Highlights ✨"));
    }

    private void sendQuickReaction(String emoji) {
        if (stories.isEmpty() || Fb.uid() == null) return;
        Models.Story s = stories.get(index);
        String actor=Fb.uid();
        Fb.db().storyEngagement(s.id, "react", emoji).addOnSuccessListener(v -> {
            if (!actor.equals(Fb.uid()) || isFinishing()) return;
            Notify.send(s.authorId, "story_reaction", "reacted " + emoji + " to your story", "");
            Ui.toast(this, "Sent " + emoji + " to " + s.authorName);
        }).addOnFailureListener(e -> { if (actor.equals(Fb.uid()) && !isFinishing()) Ui.toast(this, "Couldn't send reaction. Try again."); });
    }

    private void buildSegments() {
        segments.removeAllViews();
        for (int i = 0; i < stories.size(); i++) {
            FrameLayout seg = new FrameLayout(this);
            seg.setBackgroundColor(0x55FFFFFF);
            View fill = new View(this);
            fill.setBackgroundColor(Color.WHITE);
            fill.setTag("fill");
            FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(0, -1);
            seg.addView(fill, fp);
            if (i < index) fp.width = -1;
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
            if (i > 0) p.leftMargin = Ui.dp(this, 4);
            segments.addView(seg, p);
        }
    }

    private void animateSegment(long durMs) {
        if (anim != null) anim.cancel();
        if (segments.getChildCount() <= index) return;
        View seg = segments.getChildAt(index);
        View fill = seg.findViewWithTag("fill");
        if (fill == null) return;
        FrameLayout.LayoutParams fp = (FrameLayout.LayoutParams) fill.getLayoutParams();
        int full = seg.getWidth() > 0 ? seg.getWidth()
                : getResources().getDisplayMetrics().widthPixels / Math.max(1, stories.size());
        anim = ValueAnimator.ofInt(0, full);
        anim.setDuration(durMs);
        anim.addUpdateListener(a -> {
            fp.width = (int) a.getAnimatedValue();
            fill.setLayoutParams(fp);
        });
        anim.start();
    }

    private void stopTimer() {
        if (timer != null) H.removeCallbacks(timer);
        if (anim != null) anim.cancel();
    }

    private void step(int delta) {
        if(stories.isEmpty())return;
        int next = index + delta;
        if (next < 0) next = 0;
        if (next >= stories.size()) {
            if(storyPages!=null && storyMore){stopTimer();advanceAfterPage=true;if(!storyBusy)storyPages.next();return;}
            finish();return;
        }
        index = next;
        show();
    }

    private void deleteCurrent() {
        if (stories.isEmpty()) return;
        Models.Story s = stories.get(index);
        Ui.confirm(this, "Delete this story?", "", "Delete", () -> {
            Fb.db().collection("stories").document(s.id).delete().addOnSuccessListener(v->{
                if(storyPages!=null)storyPages.forget(s.id);stories.remove(s);
                Ui.toast(this,"Story deleted");if(stories.isEmpty()){finish();return;}index=Math.min(index,stories.size()-1);show();
            }).addOnFailureListener(e -> { if (!isFinishing()) Ui.toast(this,"Couldn't delete story. Try again."); });
        });
    }

    private void showViewers() {
        if (stories.isEmpty()) return;
        Models.Story s = stories.get(index);
        List<String> viewers = new ArrayList<>(s.viewedBy);
        if (viewers.isEmpty()) { Ui.toast(this, "No views yet"); return; }
        String actor=Fb.uid();
        List<String> lines = new ArrayList<>(java.util.Collections.nCopies(viewers.size(), "User"));
        final int[] remaining={viewers.size()};
        for (int i=0;i<viewers.size();i++) {
            final int position=i;String uid=viewers.get(i);
            Fb.getUser(uid).addOnCompleteListener(task -> {
                if (isFinishing() || !java.util.Objects.equals(actor,Fb.uid())) return;
                Models.User u=task.isSuccessful()?task.getResult():null;
                String r=s.reactions.get(uid);
                lines.set(position,(u!=null && u.displayName!=null && !u.displayName.isEmpty()?u.displayName:"User")+(r!=null?"  "+r:""));
                if (--remaining[0]==0) Ui.info(this, "👁 Story viewers ("+viewers.size()+")", TextUtils.join("\n",lines));
            });
        }
    }

    private void sendReply() {
        String text = replyInput.getText().toString().trim();
        if (text.isEmpty() || stories.isEmpty()) return;
        String me = Fb.uid();
        if (me == null) return;
        Models.Story s = stories.get(index);
        String chatId = Fb.chatId(me, s.authorId);
        long now = System.currentTimeMillis();
        Map<String, Object> msg = Fb.map(
                "senderId", me,
                "receiverId", s.authorId,
                "text", "📖 Story reply: " + text,
                "imageUrl", null,
                "type", "text",
                "isSeen", false,
                "isDelivered", false,
                "createdAtMs", now,
                "createdAt", FieldValue.serverTimestamp());
        WriteBatch batch = Fb.db().batch();
        batch.set(Fb.db().collection("chats").document(chatId).collection("messages").document(), msg);
        batch.set(Fb.db().collection("chats").document(chatId), Fb.map(
                "participants", Arrays.asList(me, s.authorId),
                "lastMessage", "📖 " + text,
                "lastMessageTimeMs", now,
                "lastMessageTime", FieldValue.serverTimestamp()), SetOptions.merge());
        batch.commit().addOnSuccessListener(x -> {
            replyInput.setText("");
            Ui.hideKeyboard(this);
            Ui.toast(this, "Reply sent 💬");
        });
    }
}
