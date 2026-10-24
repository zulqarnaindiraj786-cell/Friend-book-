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
import androidx.viewpager2.widget.ViewPager2;

import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Format;
import com.example.frienbook.core.Icons;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private static final int VT_HEADER = 0, VT_POST = 1, VT_FOOTER = 2, VT_AD = 3;

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

    // ---- boosts + live rows for the header ----
    private final java.util.List<Models.User> boostedUsers = new ArrayList<>();
    private final java.util.List<java.util.Map<String, Object>> activeLives = new ArrayList<>();
    public final java.util.Set<String> boostedPostIds = new HashSet<>();

    public void setBoosted(java.util.List<Models.User> users, java.util.Set<String> postIds) {
        boostedUsers.clear();
        if (users != null) boostedUsers.addAll(users);
        boostedPostIds.clear();
        if (postIds != null) boostedPostIds.addAll(postIds);
        notifyDataSetChanged();
    }

    public void setLives(java.util.List<java.util.Map<String, Object>> lives) {
        activeLives.clear();
        if (lives != null) activeLives.addAll(lives);
        if (showHeader) notifyItemChanged(0);
    }

    private int offset() { return showHeader ? 1 : 0; }
    private int footerCount() { return showFooter ? 1 : 0; }

    // ---- 📢 AdMob banner slots: one banner after every 3 posts ----
    private String bannerId = com.example.frienbook.core.AdsConfig.bannerId();
    private boolean adsEnabled = com.example.frienbook.core.AdsConfig.bannersEnabled();

    public void setAdsConfig(String id, boolean enabled) {
        bannerId = id == null || id.isEmpty() ? com.example.frienbook.core.AdsConfig.DEFAULT_BANNER_ID : id;
        adsEnabled = enabled;
        notifyDataSetChanged();
    }

    private int adCount() { return adsEnabled ? posts.size() / 3 : 0; }

    /** Does this feed slot (after the header) hold an ad? Slots 3, 7, 11… */
    private boolean isAdSlot(int slot) {
        return adsEnabled && (slot + 1) % 4 == 0 && slot / 4 < adCount();
    }

    private int postIndexForSlot(int slot) {
        if (!adsEnabled) return slot;
        return slot - ((slot + 1) / 4);
    }

    private int slotCount() { return posts.size() + adCount(); }

    @Override public int getItemCount() { return offset() + slotCount() + footerCount(); }

    @Override public int getItemViewType(int position) {
        if (showHeader && position == 0) return VT_HEADER;
        if (showFooter && position == getItemCount() - 1) return VT_FOOTER;
        if (isAdSlot(position - offset())) return VT_AD;
        return VT_POST;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Context c = parent.getContext();
        if (viewType == VT_HEADER) return new HeaderVH(c);
        if (viewType == VT_FOOTER) return new FooterVH(c);
        if (viewType == VT_AD) return new AdVH(c);
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
            // 🚀 boosted profiles cards
            hh.boostBox.setVisibility(boostedUsers.isEmpty() ? View.GONE : View.VISIBLE);
            hh.boostCards.removeAllViews();
            for (final Models.User u : boostedUsers) {
                LinearLayout card = Ui.col(activity);
                card.setGravity(android.view.Gravity.CENTER);
                card.setBackground(Ui.roundStroke(activity, Ui.card(activity), 14, Ui.BLUE, 1));
                Ui.pad(card, 12, 10, 12, 10);
                ImageView av = new ImageView(activity);
                Ui.avatar(activity, av, u.photoUrl);
                int s = Ui.dp(activity, 54);
                LinearLayout.LayoutParams avp = new LinearLayout.LayoutParams(s, s);
                avp.gravity = Gravity.CENTER;
                card.addView(av, avp);
                TextView nm = Ui.tvBold(activity, u.displayName, 12, Ui.txt(activity));
                nm.setMaxWidth(Ui.dp(activity, 90));
                nm.setSingleLine(true);
                card.addView(nm);
                ImageView tk = new ImageView(activity);
                Icons.setTickBadge(tk, u.tickLevel, false);
                int ts2 = Ui.dp(activity, 14);
                card.addView(tk, new LinearLayout.LayoutParams(ts2, ts2));
                TextView tag = Ui.roundChip(activity, "BOOSTED", 0x22E41E3F, 0xFFE41E3F, 9);
                card.addView(tag);
                card.setOnClickListener(v -> { if (host != null) host.openProfile(u.uid); });
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
                cp.setMargins(Ui.dp(activity, 4), Ui.dp(activity, 2), Ui.dp(activity, 4), Ui.dp(activity, 4));
                hh.boostCards.addView(card, cp);
            }
            // 🔴 live rooms + go-live button
            hh.goLiveBtn.setOnClickListener(v -> {
                android.widget.EditText in = Ui.input(activity, "Live title (optional)");
                new android.app.AlertDialog.Builder(activity)
                        .setTitle("🔴 Go Live")
                        .setView(in)
                        .setPositiveButton("Start", (d, w) -> {
                            Intent li = new Intent(activity, com.example.frienbook.live.LiveActivity.class);
                            li.putExtra("title", in.getText().toString().trim());
                            activity.startActivity(li);
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
            hh.liveBox.setVisibility(activeLives.isEmpty() ? View.VISIBLE : View.VISIBLE);
            hh.liveCards.removeAllViews();
            for (final java.util.Map<String, Object> live : activeLives) {
                LinearLayout card = Ui.col(activity);
                card.setGravity(Gravity.CENTER);
                card.setBackground(Ui.round(activity, Ui.inputBg(activity), 14));
                Ui.pad(card, 12, 8, 12, 8);
                ImageView av = new ImageView(activity);
                Ui.avatar(activity, av, Format.str(live, "hostPhotoUrl"));
                int s = Ui.dp(activity, 46);
                LinearLayout.LayoutParams avp = new LinearLayout.LayoutParams(s, s);
                avp.gravity = Gravity.CENTER;
                card.addView(av, avp);
                TextView nm = Ui.tvBold(activity, Format.strOr(live, "hostName", "Live"), 11, Ui.txt(activity));
                nm.setSingleLine(true);
                nm.setMaxWidth(Ui.dp(activity, 84));
                card.addView(nm);
                card.addView(Ui.roundChip(activity, "🔴 " + Format.count(Format.intOf(live, "viewers")),
                        0xFFE41E3F, 0xFFFFFFFF, 9));
                final String lid = Format.str(live, "_id");
                final String hostId = Format.str(live, "hostId");
                card.setOnClickListener(v -> {
                    Intent li = new Intent(activity, com.example.frienbook.live.LiveActivity.class);
                    li.putExtra("live_id", lid);
                    li.putExtra("host_id", hostId);
                    activity.startActivity(li);
                });
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
                cp.setMargins(Ui.dp(activity, 4), Ui.dp(activity, 2), Ui.dp(activity, 4), Ui.dp(activity, 4));
                hh.liveCards.addView(card, cp);
            }
        } else if (h instanceof FooterVH) {
            FooterVH fh = (FooterVH) h;
            boolean more = host != null && host.hasMore();
            fh.more.setVisibility(more ? View.VISIBLE : View.GONE);
            fh.end.setVisibility(more ? View.GONE : View.VISIBLE);
            fh.more.setOnClickListener(v -> { if (host != null) host.loadMore(); });
        } else if (h instanceof AdVH) {
            ((AdVH) h).loadAd(bannerId); // fresh load on every bind = refreshed ad each time
        } else {
            bindPost((PostVH) h, posts.get(postIndexForSlot(position - offset())));
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
        if (h instanceof AdVH) ((AdVH) h).destroyAd();
        if (h instanceof PostVH) {
            PostVH vh = (PostVH) h;
            if (vh.reactReg != null) { vh.reactReg.remove(); vh.reactReg = null; }
            if (vh.pageCb != null) { vh.pager.unregisterOnPageChangeCallback(vh.pageCb); vh.pageCb = null; }
            vh.video.stopPlayback();
            vh.video.setVisibility(View.GONE);
            vh.playBadge.setVisibility(View.GONE);
            vh.pager.setVisibility(View.GONE);
            vh.pageBadge.setVisibility(View.GONE);
            vh.mediaBox.setOnTouchListener(null);
        }
    }

    // ================= POST BIND =================

    private void bindPost(PostVH vh, Models.Post p) {
        Context c = activity;
        Ui.avatar(c, vh.avatar, p.authorPhoto);
        boolean hasFeeling = p.feeling != null && !p.feeling.isEmpty();
        vh.name.setText(p.authorName + (hasFeeling ? " is feeling " + p.feeling : ""));
        vh.tick.setImageDrawable(null);
        if (!"none".equals(p.authorTickLevel) && p.authorTickLevel != null) {
            vh.tick.setVisibility(View.VISIBLE);
            vh.tick.setImageResource(com.example.frienbook.core.Icons.badgeFor(p.authorTickLevel));
        } else {
            vh.tick.setVisibility(p.authorVerified ? View.VISIBLE : View.GONE);
            if (p.authorVerified) vh.tick.setImageResource(com.example.frienbook.R.drawable.ic_verified);
        }
        if (vh.boostTag != null)
            vh.boostTag.setVisibility(boostedPostIds.contains(p.id) ? View.VISIBLE : View.GONE);
        String icon = "🌐";
        if ("Friends".equalsIgnoreCase(p.privacy)) icon = "👥";
        if ("Only me".equalsIgnoreCase(p.privacy)) icon = "🔒";
        vh.meta.setText(Format.ago(p.createdAt) + " · " + icon
                + (p.sharedFromId != null && !p.sharedFromId.isEmpty() ? " · shared" : ""));
        // colored-background text posts (Facebook style)
        if (p.isBgPost() && p.content != null && !p.content.isEmpty()) {
            vh.content.setBackground(Ui.gradient(p.bgColor, shade(p.bgColor, 0.55f), Ui.dp(c, 12)));
            vh.content.setTextColor(0xFFFFFFFF);
            vh.content.setTextSize(23);
            vh.content.setGravity(Gravity.CENTER);
            vh.content.setMinHeight(Ui.dp(c, 200));
            vh.content.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
            clp.setMargins(Ui.dp(c, 12), Ui.dp(c, 4), Ui.dp(c, 12), Ui.dp(c, 8));
            vh.content.setLayoutParams(clp);
            Ui.pad(vh.content, 18, 24, 18, 24);
        } else {
            vh.content.setBackground(null);
            vh.content.setTextColor(Ui.txt(c));
            vh.content.setTextSize(15);
            vh.content.setGravity(Gravity.START);
            vh.content.setMinHeight(0);
            vh.content.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            vh.content.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
            Ui.pad(vh.content, 12, 4, 12, 8);
        }
        vh.content.setText(p.content);
        vh.content.setVisibility(p.content == null || p.content.isEmpty() ? View.GONE : View.VISIBLE);
        // location line
        if (p.location != null && !p.location.isEmpty())
            vh.meta.append(" · 📍 " + p.location);

        // ---------- poll ----------
        vh.pollBox.removeAllViews();
        vh.pollBox.setVisibility(p.isPoll() ? View.VISIBLE : View.GONE);
        if (p.isPoll()) {
            String meId = Fb.uid();
            int total = p.pollVotes != null ? p.pollVotes.size() : 0;
            java.util.Map<String, Integer> perOpt = new HashMap<>();
            if (p.pollVotes != null) {
                for (Long ix : p.pollVotes.values()) {
                    if (ix == null) continue;
                    Integer cur = perOpt.get(String.valueOf(ix.intValue()));
                    perOpt.put(String.valueOf(ix.intValue()), cur == null ? 1 : cur + 1);
                }
            }
            Integer myVote = p.pollVotes != null && meId != null && p.pollVotes.containsKey(meId)
                    ? (p.pollVotes.get(meId) == null ? null : p.pollVotes.get(meId).intValue()) : null;
            for (int i = 0; i < p.pollOptions.size(); i++) {
                final int idx = i;
                Integer count = perOpt.get(String.valueOf(i));
                int votes = count == null ? 0 : count;
                int pct = total == 0 ? 0 : Math.round(votes * 100f / total);
                boolean mine = myVote != null && myVote == i;
                TextView opt = new TextView(c);
                String txt = (mine ? "✓ " : "") + p.pollOptions.get(i)
                        + (total > 0 ? "  —  " + pct + "% (" + votes + ")" : "");
                opt.setText(txt);
                opt.setTextSize(15);
                opt.setTextColor(mine ? Ui.BLUE : Ui.txt(c));
                if (mine) opt.setTypeface(null, android.graphics.Typeface.BOLD);
                opt.setBackground(Ui.roundStroke(c,
                        mine ? Ui.blend(Ui.BLUE, Ui.card(c), 0.18f) : Ui.inputBg(c), 8, Ui.div(c), 1));
                Ui.pad(opt, 12, 10, 12, 10);
                LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-1, -2);
                olp.bottomMargin = Ui.dp(c, 8);
                opt.setLayoutParams(olp);
                opt.setOnClickListener(v -> votePoll(p, idx));
                vh.pollBox.addView(opt);
            }
            TextView hint = Ui.tv(c, total + (total == 1 ? " vote" : " votes") + " · tap to vote / again to undo",
                    11, Ui.sub(c));
            vh.pollBox.addView(hint);
        }

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
        vh.pager.setVisibility(View.GONE);
        vh.pageBadge.setVisibility(View.GONE);
        if (vh.pageCb != null) { vh.pager.unregisterOnPageChangeCallback(vh.pageCb); vh.pageCb = null; }
        vh.mediaBox.setOnClickListener(null);
        vh.mediaBox.setOnTouchListener(null);
        boolean multiPhoto = p.isMultiPhoto();
        boolean hasMedia = multiPhoto || (p.mediaType != null && !"none".equals(p.mediaType)
                && p.mediaUrl != null && !p.mediaUrl.isEmpty());
        ViewGroup.LayoutParams mlp = vh.mediaBox.getLayoutParams();
        mlp.height = Ui.dp(c, hasMedia ? 280 : 0);
        vh.mediaBox.setLayoutParams(mlp);
        vh.mediaBox.setVisibility(hasMedia ? View.VISIBLE : View.GONE);
        if (multiPhoto) {
            // multi-photo carousel (Facebook-style swipeable album)
            vh.pager.setVisibility(View.VISIBLE);
            vh.pageBadge.setVisibility(View.VISIBLE);
            vh.pager.setAdapter(new PhotoPager(p.mediaUrls));
            vh.pageBadge.setText("1/" + p.mediaUrls.size());
            vh.pageCb = new ViewPager2.OnPageChangeCallback() {
                @Override public void onPageSelected(int position) {
                    vh.pageBadge.setText((position + 1) + "/" + p.mediaUrls.size());
                }
            };
            vh.pager.registerOnPageChangeCallback(vh.pageCb);
        } else if ("image".equals(p.mediaType) && p.mediaUrl != null && !p.mediaUrl.isEmpty()) {
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

    private void votePoll(Models.Post p, int idx) {
        String me = Fb.uid();
        if (me == null) return;
        Long cur = p.pollVotes != null ? p.pollVotes.get(me) : null;
        java.util.Map<String, Object> upd = new HashMap<>();
        if (cur != null && cur.intValue() == idx) {
            // toggle off
            upd.put("pollVotes." + me, FieldValue.delete());
            if (p.pollVotes != null) p.pollVotes.remove(me);
        } else {
            upd.put("pollVotes." + me, idx);
            if (p.pollVotes == null) p.pollVotes = new HashMap<>();
            p.pollVotes.put(me, (long) idx);
        }
        Fb.db().collection("posts").document(p.id).update(upd)
                .addOnFailureListener(e -> Ui.toast(activity, "Vote failed: " + e.getMessage()));
        notifyDataSetChanged();
    }

    private static int shade(int color, float factor) {
        int a = (color >> 24) & 0xFF;
        int r = (int) (((color >> 16) & 0xFF) * factor);
        int g = (int) (((color >> 8) & 0xFF) * factor);
        int b = (int) ((color & 0xFF) * factor);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Tiny pager adapter for multi-photo posts (full-screen zoom on tap). */
    class PhotoPager extends RecyclerView.Adapter<PhotoPager.PVH> {
        final List<String> urls;
        PhotoPager(List<String> urls) { this.urls = urls; }
        @Override public int getItemCount() { return urls.size(); }

        @NonNull
        @Override
        public PVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ImageView img = new ImageView(parent.getContext());
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
            return new PVH(img);
        }

        @Override
        public void onBindViewHolder(@NonNull PVH h, int position) {
            Ui.img(activity, h.img, urls.get(position));
            h.img.setOnClickListener(v -> {
                Intent i = new Intent(activity, FullscreenMediaActivity.class);
                i.putExtra("type", "image");
                i.putExtra("url", urls.get(position));
                i.putExtra("urls", new ArrayList<>(urls));
                i.putExtra("index", position);
                activity.startActivity(i);
            });
        }

        class PVH extends RecyclerView.ViewHolder {
            ImageView img;
            PVH(ImageView v) { super(v); img = v; }
        }
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
        boolean isAdmin = com.example.frienbook.core.Admin.isAdmin();
        PopupMenu menu = new PopupMenu(c, anchor);
        menu.getMenu().add(saved ? "🔖 Unsave post" : "🔖 Save post");
        if (!boostedPostIds.contains(p.id))
            menu.getMenu().add("🚀 Boost Post — Rs " + com.example.frienbook.core.Admin.PRICE_BOOST_POST + " · more reach");
        if (mine) {
            menu.getMenu().add("✏️ Edit post");
            menu.getMenu().add("🌐 Who can see this? (" + p.privacy + ")");
            menu.getMenu().add("🗑 Delete post");
        } else {
            menu.getMenu().add("➕ Follow " + p.authorName);
            menu.getMenu().add("🙈 Hide this post");
            menu.getMenu().add("😴 Snooze " + p.authorName + " for 30 days");
            menu.getMenu().add("🚫 Block " + p.authorName);
        }
        if (isAdmin && !mine) menu.getMenu().add("🛡 🗑 Delete post (admin)");
        menu.getMenu().add("📋 Copy text");
        menu.getMenu().add("🔗 Copy link");
        if (!mine) menu.getMenu().add("🚩 Report post");
        menu.setOnMenuItemClickListener(item -> {
            String t = item.getTitle().toString();
            if (t.contains("Unsave")) toggleSave(p, true);
            else if (t.contains("Save post")) toggleSave(p, false);
            else if (t.contains("Boost Post")) {
                Intent bi = new Intent(activity, com.example.frienbook.pay.PayRequestActivity.class);
                bi.putExtra("type", "boost_post");
                bi.putExtra("target_id", p.id);
                bi.putExtra("amount", com.example.frienbook.core.Admin.PRICE_BOOST_POST);
                bi.putExtra("title", "Boost Post");
                activity.startActivity(bi);
            }
            else if (t.contains("Edit post")) { if (host != null) host.editPost(p); }
            else if (t.contains("Who can see")) changePrivacyDialog(p);
            else if (t.contains("Hide this post")) {
                Prefs.hidePost(c, p.id);
                posts.remove(p);
                notifyDataSetChanged();
                Ui.toast(activity, "Hidden. Manage in Settings → Posts you've hidden");
            } else if (t.contains("Snooze")) {
                Prefs.snooze(c, p.authorId, 30);
                Ui.toast(activity, "Snoozed " + p.authorName + " for 30 days 😴");
                java.util.Iterator<Models.Post> it = posts.iterator();
                while (it.hasNext()) if (p.authorId.equals(it.next().authorId)) it.remove();
                notifyDataSetChanged();
            } else if (t.contains("Delete post (admin)")) deletePost(p);
            else if (t.contains("Delete")) deletePost(p);
            else if (t.contains("Copy link")) copyText("friendbook://post/" + p.id);
            else if (t.contains("Copy text")) copyText(p.content);
            else if (t.contains("Follow")) followUser(p.authorId);
            else if (t.contains("Block")) blockUser(p.authorId);
            else if (t.contains("Report")) reportPost(p);
            return true;
        });
        menu.show();
    }

    private void changePrivacyDialog(Models.Post p) {
        String[] opts = {"🌐 Public", "👥 Friends", "🔒 Only me"};
        String[] vals = {"Public", "Friends", "Only me"};
        Ui.choose(activity, "Who can see this post?", opts, w -> {
            Fb.db().collection("posts").document(p.id).update("privacy", vals[w]);
            p.privacy = vals[w];
            notifyDataSetChanged();
            Ui.toast(activity, "Privacy updated: " + vals[w]);
        });
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
                            "authorVerified", Fb.me != null && Fb.me.isVerified,
                            "authorTickLevel", Fb.me != null ? Fb.me.tickLevel : "none",
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
        final LinearLayout boostBox, liveBox, boostCards, liveCards;
        final TextView goLiveBtn;

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
            ImageView photo = com.example.frienbook.core.Icons.icon(
                    c, com.example.frienbook.core.Icons.IMAGE, 24, Ui.GREEN);
            photo.setPadding(Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4));
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

            // 🔴 GO LIVE button + active lives strip (Facebook-Lite style)
            liveBox = Ui.col(c);
            liveBox.setBackgroundColor(Ui.card(c));
            liveBox.setVisibility(View.GONE);
            LinearLayout liveTop = Ui.row(c);
            liveTop.setGravity(Gravity.CENTER_VERTICAL);
            Ui.pad(liveTop, 12, 10, 12, 4);
            goLiveBtn = Ui.roundChip(c, "🔴 Go Live", 0xFFE41E3F, 0xFFFFFFFF, 13);
            Ui.pad(goLiveBtn, 16, 8, 16, 8);
            liveTop.addView(goLiveBtn);
            TextView liveTitle = Ui.tvBold(c, "Live now", 14, Ui.txt(c));
            liveTitle.setPadding(Ui.dp(c, 10), 0, 0, 0);
            liveTop.addView(liveTitle);
            liveBox.addView(liveTop);
            android.widget.HorizontalScrollView lsv = new android.widget.HorizontalScrollView(c);
            lsv.setHorizontalScrollBarEnabled(false);
            liveCards = Ui.row(c);
            Ui.pad(liveCards, 8, 0, 8, 8);
            lsv.addView(liveCards);
            liveBox.addView(lsv);
            col.addView(liveBox);

            // 🚀 boosted profiles strip (paid — pinned on top of Home)
            boostBox = Ui.col(c);
            boostBox.setBackgroundColor(Ui.card(c));
            boostBox.setVisibility(View.GONE);
            TextView boostTitle = Ui.tvBold(c, "🚀 Boosted creators", 14, Ui.txt(c));
            Ui.pad(boostTitle, 12, 10, 12, 2);
            boostBox.addView(boostTitle);
            android.widget.HorizontalScrollView bsv = new android.widget.HorizontalScrollView(c);
            bsv.setHorizontalScrollBarEnabled(false);
            boostCards = Ui.row(c);
            Ui.pad(boostCards, 8, 0, 8, 8);
            bsv.addView(boostCards);
            boostBox.addView(bsv);
            col.addView(boostBox);
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

    /** 📢 AdMob banner slot — Google BANNER ad, loaded fresh on every bind. */
    static class AdVH extends RecyclerView.ViewHolder {
        final FrameLayout box;
        com.google.android.gms.ads.AdView adView;

        AdVH(Context c) {
            super(PostVH.cardRoot(c));
            LinearLayout card = (LinearLayout) itemView;
            TextView label = Ui.tv(c, "Sponsored", 11, Ui.sub(c));
            label.setGravity(Gravity.CENTER);
            Ui.pad(label, 0, 8, 0, 2);
            card.addView(label, new LinearLayout.LayoutParams(-1, -2));
            box = new FrameLayout(c);
            card.addView(box, new LinearLayout.LayoutParams(-1, -2));
        }

        void loadAd(String bannerId) {
            destroyAd();
            adView = new com.google.android.gms.ads.AdView(box.getContext());
            adView.setAdSize(com.google.android.gms.ads.AdSize.BANNER);
            adView.setAdUnitId(bannerId);
            box.addView(adView, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            adView.loadAd(new com.google.android.gms.ads.AdRequest.Builder().build());
            Ui.pad(box, 0, 0, 0, 8);
        }

        void destroyAd() {
            if (adView != null) {
                box.removeView(adView);
                try { adView.destroy(); } catch (Throwable ignored) { }
                adView = null;
            }
        }
    }

    static class PostVH extends RecyclerView.ViewHolder {
        ImageView avatar, image, tick;
        TextView name, meta, more, content, like, comment, share;
        TextView boostTag;
        TextView sharedName, sharedContent, rEmojis, rCount, rRight, playBadge, pageBadge;
        LinearLayout sharedBox, pollBox;
        FrameLayout mediaBox;
        VideoView video;
        ViewPager2 pager;
        ViewPager2.OnPageChangeCallback pageCb;
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
            LinearLayout nameRow = Ui.row(c);
            name = Ui.tvBold(c, "", 15, Ui.txt(c));
            tick = new ImageView(c);
            tick.setImageResource(com.example.frienbook.R.drawable.ic_verified);
            tick.setVisibility(View.GONE);
            int ts = Ui.dp(c, 15);
            LinearLayout.LayoutParams tll = new LinearLayout.LayoutParams(ts, ts);
            tll.leftMargin = Ui.dp(c, 5);
            nameRow.addView(name);
            nameRow.addView(tick, tll);
            boostTag = Ui.roundChip(c, "🚀 BOOSTED", 0xFFE41E3F, 0xFFFFFFFF, 10);
            boostTag.setVisibility(View.GONE);
            LinearLayout.LayoutParams btl = new LinearLayout.LayoutParams(-2, -2);
            btl.leftMargin = Ui.dp(c, 6);
            nameRow.addView(boostTag, btl);
            meta = Ui.tv(c, "", 12, Ui.sub(c));
            nameCol.addView(nameRow);
            nameCol.addView(meta);
            head.addView(nameCol, ncp);
            more = Ui.tv(c, "•••", 16, Ui.sub(c));
            more.setPadding(Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6));
            head.addView(more);
            card.addView(head);

            content = Ui.tv(c, "", 15, Ui.txt(c));
            Ui.pad(content, 12, 4, 12, 8);
            card.addView(content);

            // poll options go here (dynamically rebuilt per post)
            pollBox = Ui.col(c);
            Ui.pad(pollBox, 12, 2, 12, 6);
            card.addView(pollBox);

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
            pager = new ViewPager2(c);
            pager.setVisibility(View.GONE);
            mediaBox.addView(pager, new FrameLayout.LayoutParams(-1, -1));
            video = new VideoView(c);
            video.setVisibility(View.GONE);
            mediaBox.addView(video, new FrameLayout.LayoutParams(-1, -1));
            playBadge = Ui.tv(c, "▶", 34, 0xEEFFFFFF);
            playBadge.setGravity(Gravity.CENTER);
            playBadge.setVisibility(View.GONE);
            mediaBox.addView(playBadge, new FrameLayout.LayoutParams(-1, -1));
            pageBadge = Ui.tvBold(c, "", 12, 0xFFFFFFFF);
            pageBadge.setBackground(Ui.round(c, 0x99000000, 20));
            Ui.pad(pageBadge, 8, 4, 8, 4);
            pageBadge.setVisibility(View.GONE);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP);
            blp.setMargins(0, Ui.dp(c, 10), Ui.dp(c, 10), 0);
            mediaBox.addView(pageBadge, blp);

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
