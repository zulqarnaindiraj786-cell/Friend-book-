package com.friendbook.post;

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

import com.friendbook.core.Fb;
import com.friendbook.core.Format;
import com.friendbook.core.Icons;
import com.friendbook.core.Notify;
import com.friendbook.core.Prefs;
import com.friendbook.core.Ui;
import com.friendbook.models.Models;
import com.friendbook.story.StoriesAdapter;
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
        default void openFindFriends() { }
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

    /** ⚡ 0.0ms optimistic reaction refresh for a single post slot (no full list redraw!). */
    public void onFastReactionUpdate(String postId, String mine, int total) {
        if (postId == null) return;
        for (int i = 0; i < posts.size(); i++) {
            Models.Post p = posts.get(i);
            if (p != null && postId.equals(p.id)) {
                p.likes = total;
                int slot = adsEnabled ? (i + (i / 3)) : i;
                notifyItemChanged(offset() + slot);
                return;
            }
        }
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

    // ---- boosts + live + PYMK rows for the header ----
    private final java.util.List<Models.User> boostedUsers = new ArrayList<>();
    private final java.util.List<Models.User> pymkUsers = new ArrayList<>();
    private final java.util.List<java.util.Map<String, Object>> activeLives = new ArrayList<>();
    public final java.util.Set<String> boostedPostIds = new HashSet<>();
    private android.speech.tts.TextToSpeech tts;

    public void setPymk(java.util.List<Models.User> users) {
        pymkUsers.clear();
        if (users != null) pymkUsers.addAll(users);
        if (showHeader) notifyItemChanged(0);
    }

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
    private String bannerId = com.friendbook.core.AdsConfig.bannerId();
    private boolean adsEnabled = com.friendbook.core.AdsConfig.bannersEnabled();

    public void setAdsConfig(String id, boolean enabled) {
        bannerId = id == null || id.isEmpty() ? com.friendbook.core.AdsConfig.DEFAULT_BANNER_ID : id;
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
                @Override public void onFindFriends() {
                    if (host != null) host.openFindFriends();
                }
            };
            Ui.avatar(activity, hh.composeAvatar, Fb.myPhoto());
            hh.composeHint.setText("What's on your mind?");
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
                            Intent li = new Intent(activity, com.friendbook.live.LiveActivity.class);
                            li.putExtra("title", in.getText().toString().trim());
                            activity.startActivity(li);
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
            hh.liveBox.setVisibility(activeLives.isEmpty() ? View.GONE : View.VISIBLE);
            hh.liveCards.removeAllViews();
            // 👥 People You May Know horizontal rail (Facebook Lite signature widget)
            hh.pymkBox.setVisibility(pymkUsers.isEmpty() ? View.GONE : View.VISIBLE);
            hh.pymkCards.removeAllViews();
            for (final Models.User u : pymkUsers) {
                LinearLayout card = Ui.col(activity);
                card.setGravity(Gravity.CENTER_HORIZONTAL);
                card.setBackground(Ui.roundStroke(activity, Ui.card(activity), 12, Ui.div(activity), 1));
                Ui.pad(card, 10, 10, 10, 10);
                ImageView av = new ImageView(activity);
                Ui.avatar(activity, av, u.photoUrl);
                int s2 = Ui.dp(activity, 56);
                LinearLayout.LayoutParams avp = new LinearLayout.LayoutParams(s2, s2);
                avp.gravity = Gravity.CENTER;
                card.addView(av, avp);
                TextView nm = Ui.tvBold(activity, u.displayName == null ? "User" : u.displayName, 12, Ui.txt(activity));
                nm.setSingleLine(true);
                nm.setMaxWidth(Ui.dp(activity, 104));
                nm.setPadding(0, Ui.dp(activity, 6), 0, Ui.dp(activity, 6));
                card.addView(nm);
                TextView addBtn = Ui.roundChip(activity, "＋ Add Friend", Ui.BLUE, 0xFFFFFFFF, 11);
                Ui.pad(addBtn, 10, 5, 10, 5);
                addBtn.setOnClickListener(v -> sendQuickFriendRequest(u, addBtn));
                card.addView(addBtn);
                av.setOnClickListener(v -> { if (host != null) host.openProfile(u.uid); });
                nm.setOnClickListener(v -> { if (host != null) host.openProfile(u.uid); });
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(Ui.dp(activity, 118), -2);
                cp.setMargins(Ui.dp(activity, 4), Ui.dp(activity, 2), Ui.dp(activity, 4), Ui.dp(activity, 4));
                hh.pymkCards.addView(card, cp);
            }
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
                    Intent li = new Intent(activity, com.friendbook.live.LiveActivity.class);
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
            // Never leave a blank white feed unexplained — say WHY it's empty
            fh.end.setText(posts.isEmpty()
                    ? "😴 No posts to show yet.\nPull down to refresh.\nIf posts exist but stay missing, check the internet & try re-login."
                    : "You're all caught up 🎉");
            fh.more.setOnClickListener(v -> { if (host != null) host.loadMore(); });
        } else if (h instanceof AdVH) {
            ((AdVH) h).loadAd(bannerId); // fresh load on every bind = refreshed ad each time
        } else {
            // Guard: realtime list swaps between measure & bind must not crash the feed.
            // A broken post gets a small placeholder card — NEVER an invisible one
            // (invisible cards made the home feed look like a blank white screen).
            int idx = postIndexForSlot(position - offset());
            if (idx < 0 || idx >= posts.size()) {
                showBrokenCard((PostVH) null, null);
                return;
            }
            try {
                bindPost((PostVH) h, posts.get(idx));
            } catch (Throwable t) {
                android.util.Log.e("HomeFeed", "bindPost failed for "
                        + (idx < posts.size() ? String.valueOf(posts.get(idx).id) : idx) + ": " + t);
                showBrokenCard((PostVH) h, posts.get(idx));
            }
        }
    }

    /** Renders a minimal "something went wrong with this post" card instead of hiding it. */
    private void showBrokenCard(PostVH vh, Models.Post p) {
        if (vh == null) return;
        try {
            vh.image.setImageDrawable(null);
            vh.image.setVisibility(View.GONE);
            vh.playBadge.setVisibility(View.GONE);
            vh.pageBadge.setVisibility(View.GONE);
            vh.mediaWrap.setVisibility(View.GONE);
            if (vh.specialBox != null) vh.specialBox.setVisibility(View.GONE);
            vh.pollBox.setVisibility(View.GONE);
            vh.sharedBox.setVisibility(View.GONE);
            vh.tick.setVisibility(View.GONE);
            vh.name.setText(p != null && p.authorName != null ? p.authorName : "friendbook");
            vh.meta.setText(p != null ? Format.ago(p.createdAt) : "");
            vh.content.setVisibility(View.VISIBLE);
            vh.content.setText("⚠ This post couldn't be displayed.\nReport it via shake-to-feedback.");
            vh.content.setTextColor(Ui.sub(activity));
            vh.content.setTextSize(14);
            vh.like.setText("👍 Like");
            vh.comment.setText("💬 Comment");
            vh.share.setText("↗ Share");
            if (vh.tagSummary != null) {
                vh.tagSummary.setText("");
                vh.tagSummary.setVisibility(View.GONE);
            }
            // placeholder cards must still respond — comment/share/⋮ stay usable
            if (p != null) {
                vh.comment.setOnClickListener(v -> { if (host != null) host.openComments(p); });
                vh.content.setOnClickListener(v -> { if (host != null) host.openComments(p); });
                vh.share.setOnClickListener(v -> shareDialog(p));
                vh.more.setOnClickListener(v -> postMenu(vh.more, p));
                vh.like.setOnClickListener(v -> { Ui.haptic(v); Reactions.tapLike(activity, p); });
                vh.like.setOnLongClickListener(null);
            }
        } catch (Throwable ignored) { }
    }

    /**
     * #hashtags aur @mentions ko Facebook jaisa blue+bold style karo.
     * CRITICAL: regex compile per-device fail ho sakta hai (older ICU regex engines
     * reject some script properties) — pattern is precompiled ONCE in a guard and
     * ANY failure falls back to plain text. A styling helper must never break a post.
     */
    private static java.util.regex.Pattern TAG_PATTERN;

    private static java.util.regex.Pattern tagPattern() {
        if (TAG_PATTERN == null) {
            java.util.regex.Pattern tp = null;
            // unicode letters/digits (covers Urdu/Arabic) — works on modern ICU
            try { tp = java.util.regex.Pattern.compile("([#@])([\\p{L}\\p{N}_]+)"); }
            catch (Throwable t) {
                // plain-ASCII fallback for engines without \p{L}
                try { tp = java.util.regex.Pattern.compile("([#@])([A-Za-z0-9_]+)"); }
                catch (Throwable ignored) { tp = java.util.regex.Pattern.compile("a^"); } // never matches
            }
            TAG_PATTERN = tp;
        }
        return TAG_PATTERN;
    }

    static CharSequence styleHashtags(Context ctx, String text) {
        if (text == null) return "";
        try {
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(text);
            java.util.regex.Matcher m = tagPattern().matcher(text);
            while (m.find()) {
                sb.setSpan(new android.text.style.ForegroundColorSpan(Ui.BLUE), m.start(), m.end(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        m.start(), m.end(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            java.util.regex.Matcher um = android.util.Patterns.WEB_URL.matcher(text);
            while (um.find()) {
                final String url = um.group();
                sb.setSpan(new android.text.style.ClickableSpan() {
                    @Override public void onClick(@NonNull View widget) {
                        Ui.openUrl(ctx, url.startsWith("http") ? url : "https://" + url);
                    }
                }, um.start(), um.end(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return sb;
        } catch (Throwable t) {
            return text; // plain post text — styling is optional, the post is not
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
            vh.image.setImageDrawable(null);
            vh.image.setOnClickListener(null);
            vh.mediaWrap.setOnClickListener(null);
            vh.playBadge.setVisibility(View.GONE);
            vh.pageBadge.setVisibility(View.GONE);
        }
    }

    // ================= POST BIND =================

    private void bindPost(PostVH vh, Models.Post p) {
        Context c = activity;

        // ---------- header: 40dp circular avatar · bold name · gray time below · ⋮ ----------
        boolean pagePost = p.isPagePost();
        Ui.avatar(c, vh.avatar, pagePost && p.pagePhoto != null && !p.pagePhoto.isEmpty() ? p.pagePhoto : p.authorPhoto);
        boolean hasFeeling = p.feeling != null && !p.feeling.isEmpty();
        String baseAuthor = pagePost ? ("🚩 " + p.pageName) : (p.authorName == null ? "User" : p.authorName);
        String nameLine = baseAuthor + (hasFeeling ? " is feeling " + p.feeling : "");
        if (p.taggedNames != null && !p.taggedNames.isEmpty()) {
            nameLine += " — with " + p.taggedNames.get(0)
                    + (p.taggedNames.size() > 2 ? " and " + (p.taggedNames.size() - 1) + " others"
                    : p.taggedNames.size() == 2 ? " and " + p.taggedNames.get(1) : "");
        }
        if (Fb.me != null && Fb.me.favoriteUids != null && Fb.me.favoriteUids.contains(p.authorId))
            nameLine = "⭐ " + nameLine; // favorite friends stand out (they also sort first)
        vh.name.setText(nameLine);
        vh.tick.setImageDrawable(null);
        if (p.authorTickLevel != null && !"none".equals(p.authorTickLevel)) {
            vh.tick.setVisibility(View.VISIBLE);
            vh.tick.setImageResource(com.friendbook.core.Icons.badgeFor(p.authorTickLevel));
        } else {
            vh.tick.setVisibility(p.authorVerified ? View.VISIBLE : View.GONE);
            if (p.authorVerified) vh.tick.setImageResource(com.friendbook.R.drawable.ic_verified);
        }
        if (vh.boostTag != null)
            vh.boostTag.setVisibility(boostedPostIds.contains(p.id) ? View.VISIBLE : View.GONE);

        String icon = "🌐";
        if ("Friends".equalsIgnoreCase(p.privacy)) icon = "👥";
        if ("Only me".equalsIgnoreCase(p.privacy)) icon = "🔒";
        String meta = Format.ago(p.createdAt) + " · " + icon
                + (p.sharedFromId != null && !p.sharedFromId.isEmpty() ? " · shared" : "");
        if (p.location != null && !p.location.isEmpty()) meta += " · 📍 " + p.location;
        vh.meta.setText(meta);

        // ---------- taps FIRST — even if anything below fails, buttons stay alive ----------
        View.OnClickListener prof = v -> {
            if (pagePost) {
                Intent pi = new Intent(c, com.friendbook.social.PageDetailActivity.class);
                pi.putExtra("page_id", p.pageId);
                c.startActivity(pi);
            } else if (host != null) {
                host.openProfile(p.authorId);
            }
        };
        vh.avatar.setOnClickListener(prof);
        vh.name.setOnClickListener(prof);
        vh.like.setOnClickListener(v -> { Ui.haptic(v); Reactions.tapLike(c, p); });
        vh.like.setOnLongClickListener(v -> {
            Reactions.show(vh.like, type -> Reactions.apply(c, p, type));
            return true;
        });
        vh.comment.setOnClickListener(v -> { if (host != null) host.openComments(p); });
        vh.content.setOnClickListener(v -> { if (host != null) host.openComments(p); });
        vh.share.setOnClickListener(v -> shareDialog(p));
        vh.more.setOnClickListener(v -> postMenu(vh.more, p));
        if (vh.closeBtn != null) {
            vh.closeBtn.setVisibility(showHeader ? View.VISIBLE : View.GONE);
            vh.closeBtn.setOnClickListener(v -> {
                Prefs.hidePost(c, p.id);
                final int removedIdx = posts.indexOf(p);
                posts.remove(p);
                notifyDataSetChanged();
                new android.app.AlertDialog.Builder(c)
                        .setTitle("Post hidden")
                        .setMessage("We'll show fewer posts like this in your feed.")
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Undo", (d, w) -> {
                            Prefs.unhidePost(c, p.id);
                            int insertAt = (removedIdx >= 0 && removedIdx <= posts.size()) ? removedIdx : 0;
                            posts.add(insertAt, p);
                            notifyDataSetChanged();
                        })
                        .show();
            });
        }

        // ---------- Special FB-Lite cards: Life Event / Q&A / Marketplace ----------
        vh.specialBox.removeAllViews();
        vh.specialBox.setVisibility(View.GONE);
        if (p.isLifeEvent()) {
            vh.specialBox.setVisibility(View.VISIBLE);
            LinearLayout le = Ui.col(c);
            le.setGravity(Gravity.CENTER);
            le.setBackground(Ui.roundStroke(c, Ui.inputBg(c), 12, Ui.BLUE, 1));
            Ui.pad(le, 14, 14, 14, 14);
            TextView ic = Ui.tv(c, p.lifeEventIcon == null || p.lifeEventIcon.isEmpty() ? "🎉" : p.lifeEventIcon, 34, Ui.BLUE);
            ic.setGravity(Gravity.CENTER);
            TextView tl = Ui.tvBold(c, p.lifeEventTitle == null || p.lifeEventTitle.isEmpty() ? "Life Event" : p.lifeEventTitle, 17, Ui.txt(c));
            tl.setGravity(Gravity.CENTER);
            le.addView(ic);
            le.addView(tl);
            vh.specialBox.addView(le);
        } else if (p.isQna()) {
            vh.specialBox.setVisibility(View.VISIBLE);
            TextView qBanner = Ui.tvBold(c, "❓ Q&A SESSION · Tap Comment to ask a question", 12, Ui.BLUE);
            qBanner.setGravity(Gravity.CENTER);
            qBanner.setBackground(Ui.round(c, Ui.inputBg(c), 8));
            Ui.pad(qBanner, 10, 6, 10, 6);
            qBanner.setOnClickListener(v -> { if (host != null) host.openComments(p); });
            vh.specialBox.addView(qBanner);
        } else if (p.isMarketplace()) {
            vh.specialBox.setVisibility(View.VISIBLE);
            LinearLayout mk = Ui.row(c);
            mk.setBackground(Ui.roundStroke(c, Ui.inputBg(c), 10, Ui.div(c), 1));
            Ui.pad(mk, 12, 10, 12, 10);
            LinearLayout mcol = Ui.col(c);
            mcol.addView(Ui.tvBold(c, (p.marketSold ? "🔴 SOLD · " : "🛒 ")
                    + (p.marketPrice == null || p.marketPrice.isEmpty() ? "For Sale" : p.marketPrice),
                    15, p.marketSold ? Ui.RED : 0xFF00A400));
            mcol.addView(Ui.tvBold(c, p.marketTitle == null ? "" : p.marketTitle, 14, Ui.txt(c)));
            mk.addView(mcol, new LinearLayout.LayoutParams(0, -2, 1));
            TextView openMk = Ui.roundChip(c, "View item", Ui.BLUE, 0xFFFFFFFF, 12);
            Ui.pad(openMk, 10, 6, 10, 6);
            mk.addView(openMk);
            mk.setOnClickListener(v -> c.startActivity(new Intent(c, com.friendbook.settings.MarketplaceActivity.class)));
            vh.specialBox.addView(mk);
        }

        // ---------- content: Colored Background Card OR plain 16sp text ----------
        boolean hasText = p.content != null && !p.content.isEmpty();
        boolean hasMediaAttachment = ("image".equals(p.mediaType) || "images".equals(p.mediaType) || "video".equals(p.mediaType))
                && ((p.mediaUrl != null && !p.mediaUrl.isEmpty()) || (p.mediaUrls != null && !p.mediaUrls.isEmpty()));
        boolean useBgCard = hasText && p.bgColor != 0 && !hasMediaAttachment && p.content.length() <= 280;
        vh.content.setVisibility(hasText ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams clp = (LinearLayout.LayoutParams) vh.content.getLayoutParams();
        if (useBgCard) {
            int col = p.bgColor | 0xFF000000;
            vh.content.setBackground(Ui.gradient(col, shade(col, 0.58f), Ui.dp(c, 12)));
            vh.content.setTextColor(0xFFFFFFFF);
            vh.content.setTextSize(21 * Prefs.fontScale(c));
            vh.content.setTypeface(null, android.graphics.Typeface.BOLD);
            vh.content.setGravity(Gravity.CENTER);
            vh.content.setMinHeight(Ui.dp(c, 180));
            clp.setMargins(Ui.dp(c, 12), Ui.dp(c, 4), Ui.dp(c, 12), Ui.dp(c, 8));
            Ui.pad(vh.content, 20, 26, 20, 26);
            vh.content.setText(p.content);
        } else {
            vh.content.setBackground(null);
            vh.content.setTextColor(Ui.txt(c));
            vh.content.setTextSize(16 * Prefs.fontScale(c));
            vh.content.setTypeface(null, android.graphics.Typeface.NORMAL);
            vh.content.setGravity(Gravity.START | Gravity.TOP);
            vh.content.setMinHeight(0);
            clp.setMargins(0, 0, 0, 0);
            Ui.pad(vh.content, 12, 2, 12, 8);
            vh.content.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            vh.content.setText(hasText ? styleHashtags(c, p.content) : "");
        }
        vh.content.setLayoutParams(clp);

        // ---------- poll (unchanged logic, simple gray rows) ----------
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
                opt.setText((mine ? "✓ " : "") + p.pollOptions.get(i)
                        + (total > 0 ? "  —  " + pct + "% (" + votes + ")" : ""));
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
            vh.pollBox.addView(Ui.tv(c, total + (total == 1 ? " vote" : " votes")
                    + " · tap to vote / again to undo", 11, Ui.sub(c)));
        }

        // ---------- shared embed (kept, plain gray box) ----------
        boolean isShared = p.sharedFromId != null && !p.sharedFromId.isEmpty();
        vh.sharedBox.setVisibility(isShared ? View.VISIBLE : View.GONE);
        if (isShared) {
            vh.sharedName.setText("Originally shared by "
                    + (p.sharedFromAuthor == null ? "" : p.sharedFromAuthor));
            vh.sharedContent.setText(p.sharedFromContent);
            vh.sharedBox.setOnClickListener(v -> {
                Models.Post orig = new Models.Post();
                orig.id = p.sharedFromId;
                if (host != null) host.openComments(orig);
            });
        }

        // ---------- media: ONE ImageView (fully null-safe) — match_parent × wrap_content ----------
        // pick the image: single photo or the first of a multi-photo album
        String imgUrl = null;
        if (p.mediaUrls != null) {
            for (String u : p.mediaUrls) if (u != null && !u.isEmpty()) { imgUrl = u; break; }
        }
        if (imgUrl == null && "image".equals(p.mediaType)
                && p.mediaUrl != null && !p.mediaUrl.isEmpty()) imgUrl = p.mediaUrl;
        final boolean isVideo = imgUrl == null && "video".equals(p.mediaType)
                && p.mediaUrl != null && !p.mediaUrl.isEmpty();
        final String fImg = imgUrl;

        final boolean textOnlyPhotoHidden = fImg != null && Prefs.textOnlyMode(c)
                && !com.friendbook.core.FastCache.isPhotoRevealed(p.id);

        vh.image.setImageDrawable(null);
        vh.image.setOnClickListener(null);
        vh.mediaWrap.setOnClickListener(null);
        vh.playBadge.setVisibility(View.GONE);
        vh.playBadge.setTextSize(44);
        vh.pageBadge.setVisibility(View.GONE);
        ViewGroup.LayoutParams wp = vh.mediaWrap.getLayoutParams();
        wp.height = textOnlyPhotoHidden ? Ui.dp(c, 92)
                : (isVideo ? Ui.dp(c, 240) : ViewGroup.LayoutParams.WRAP_CONTENT);
        vh.mediaWrap.setLayoutParams(wp);
        vh.mediaWrap.setVisibility((fImg != null || isVideo) ? View.VISIBLE : View.GONE);

        if (textOnlyPhotoHidden) {
            // 📵 Facebook Lite Basic / Text-Only 2G Mode: 0 KB automatic photo load!
            vh.image.setVisibility(View.VISIBLE);
            vh.image.setImageDrawable(Ui.round(c, Ui.inputBg(c), 0));
            vh.playBadge.setVisibility(View.VISIBLE);
            vh.playBadge.setBackgroundColor(0xCC182438);
            vh.playBadge.setTextSize(14);
            int album = p.mediaUrls != null ? p.mediaUrls.size() : 0;
            vh.playBadge.setText(album > 1
                    ? "🖼 Tap to view " + album + " photos (~14 KB each)"
                    : "🖼 Tap to view photo (~14 KB)");
            vh.mediaWrap.setOnClickListener(v -> {
                com.friendbook.core.FastCache.revealPhoto(p.id);
                ViewGroup.LayoutParams nwp = vh.mediaWrap.getLayoutParams();
                nwp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                vh.mediaWrap.setLayoutParams(nwp);
                vh.playBadge.setVisibility(View.GONE);
                Ui.img(c, vh.image, fImg);
                vh.image.setOnClickListener(iv -> {
                    Intent i = new Intent(c, FullscreenMediaActivity.class);
                    i.putExtra("type", "image");
                    i.putExtra("url", fImg);
                    c.startActivity(i);
                });
            });
        } else if (fImg != null) {
            vh.image.setVisibility(View.VISIBLE);
            vh.playBadge.setBackgroundColor(0x99000000);
            Ui.img(c, vh.image, fImg);
            int album = p.mediaUrls != null ? p.mediaUrls.size() : 0;
            if (album > 1) {
                vh.pageBadge.setVisibility(View.VISIBLE);
                vh.pageBadge.setText("📷 " + album + " photos");
            }
            vh.image.setOnClickListener(v -> {
                Intent i = new Intent(c, FullscreenMediaActivity.class);
                i.putExtra("type", "image");
                i.putExtra("url", fImg);
                c.startActivity(i);
            });
        } else if (isVideo) {
            // video posts: lightweight dark thumbnail box + ▶ overlay → fullscreen player on tap (0 KB auto video load!)
            vh.image.setVisibility(View.VISIBLE);
            vh.image.setImageDrawable(Ui.round(c, 0xFF222222, 0));
            vh.playBadge.setVisibility(View.VISIBLE);
            vh.playBadge.setBackgroundColor(0x99000000);
            vh.playBadge.setTextSize(15);
            vh.playBadge.setText("▶ Tap to play video");
            vh.mediaWrap.setOnClickListener(v -> {
                Intent i = new Intent(c, FullscreenMediaActivity.class);
                i.putExtra("type", "video");
                i.putExtra("url", p.mediaUrl);
                c.startActivity(i);
            });
        }

        // ---------- ⚡ 0.0ms Instant Reaction & Counter Render + Exact Subcollection Sync ----------
        if (vh.reactReg != null) { vh.reactReg.remove(); vh.reactReg = null; }
        vh.itemView.setTag(p.id);
        int totalLikes = com.friendbook.core.FastCache.getReactionTotal(p.id, p.likes);
        String myReact = com.friendbook.core.FastCache.getMyReaction(p.id);
        renderReactionBar(c, vh, p, myReact, totalLikes);

        if (p.id != null && !com.friendbook.core.FastCache.isOffline(c)) {
            Reactions.syncPostReactions(c, p);
        }
    }

    private void renderReactionBar(Context c, PostVH vh, Models.Post p, String mine, int total) {
        boolean hideCounts = Prefs.hideReactionCounts(c)
                || (Fb.me != null && Fb.me.hideReactionCounts);

        // Left clickable reactions summary -> opens "People who reacted" ("Kis kis ne like kiya")!
        if (total > 0) {
            String em = com.friendbook.core.FastCache.getReactionEmojis(p.id);
            if ((em == null || em.isEmpty()) && mine != null && !mine.isEmpty()) {
                em = Reactions.emojiFor(mine);
            }
            if (em == null || em.isEmpty()) em = "👍";
            String leftTxt = hideCounts ? em : (em + " " + Format.count(total));
            vh.tagSummary.setText(leftTxt);
            vh.tagSummary.setVisibility(View.VISIBLE);
            vh.tagSummary.setOnClickListener(v -> Reactions.showWhoReacted(activity, p.id));
        } else {
            vh.tagSummary.setText("");
            vh.tagSummary.setVisibility(View.GONE);
            vh.tagSummary.setOnClickListener(null);
        }

        // Right clickable comments & shares summary -> opens PostDetailActivity (All comments)!
        if (vh.commentsSummary != null) {
            StringBuilder right = new StringBuilder();
            if (p.comments > 0) right.append(Format.count(p.comments))
                    .append(p.comments == 1 ? " comment" : " comments");
            if (p.shares > 0) {
                if (right.length() > 0) right.append(" · ");
                right.append(Format.count(p.shares)).append(p.shares == 1 ? " share" : " shares");
            }
            vh.commentsSummary.setText(right);
            vh.commentsSummary.setVisibility(right.length() > 0 ? View.VISIBLE : View.GONE);
            vh.commentsSummary.setOnClickListener(v -> { if (host != null) host.openComments(p); });
        }
        if (vh.summaryRow != null) {
            boolean anySummary = total > 0 || p.comments > 0 || p.shares > 0;
            vh.summaryRow.setVisibility(anySummary ? View.VISIBLE : View.GONE);
        }

        vh.comment.setText("💬 " + (p.comments > 0 ? Format.count(p.comments) : "Comment"));
        vh.comment.setBackground(Ui.round(c, Ui.inputBg(c), 20));
        vh.share.setText("↗ " + (p.shares > 0 ? Format.count(p.shares) : "Share"));
        vh.share.setBackground(Ui.round(c, Ui.inputBg(c), 20));

        if (mine != null && !mine.isEmpty()) {
            vh.like.setText(Reactions.emojiFor(mine) + " "
                    + (!hideCounts && total > 0 ? Format.count(total) : Reactions.labelFor(mine)));
            int col = Reactions.colorFor(mine);
            vh.like.setTextColor(col);
            vh.like.setBackground(Ui.round(c, Ui.blend(col, Ui.card(c), 0.14f), 20));
        } else {
            vh.like.setText("👍 " + (!hideCounts && total > 0 ? Format.count(total) : "Like"));
            vh.like.setTextColor(Ui.sub(c));
            vh.like.setBackground(Ui.round(c, Ui.inputBg(c), 20));
        }
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

    /** 📖 Share a post to my story (image posts reuse the photo; text → caption story). */
    private void shareToStory(Models.Post p) {
        java.util.Map<String, Object> s = new HashMap<>();
        s.put("authorId", Fb.uid());
        s.put("authorName", Fb.myName());
        s.put("authorPhotoUrl", Fb.myPhoto());
        boolean hasImg = "image".equals(p.mediaType) && p.mediaUrl != null && !p.mediaUrl.isEmpty();
        s.put("mediaUrl", hasImg ? p.mediaUrl : "");
        s.put("mediaType", hasImg ? "image" : "text");
        String cap = p.content == null ? "" : (p.content.length() > 140 ? p.content.substring(0, 140) + "…" : p.content);
        s.put("caption", "📌 " + p.authorName + (cap.isEmpty() ? "" : ": " + cap));
        s.put("sharedPostId", p.id);
        s.put("viewedBy", new ArrayList<String>());
        s.put("createdAt", com.google.firebase.firestore.FieldValue.serverTimestamp());
        s.put("expiresAt", new com.google.firebase.Timestamp(
                new java.util.Date(System.currentTimeMillis() + 24L * 3600_000L)));
        Fb.db().collection("stories").add(s)
                .addOnSuccessListener(x -> Ui.toast(activity, "Shared to your story 📖"))
                .addOnFailureListener(e -> Ui.toast(activity, "Couldn't share: " + e.getMessage()));
    }

    // ---------- post overflow menu ----------
    private void postMenu(View anchor, Models.Post p) {
        Context c = activity;
        boolean mine = p.authorId != null && p.authorId.equals(Fb.uid());
        boolean saved = p.savedBy != null && p.savedBy.contains(Fb.uid());
        boolean isAdmin = com.friendbook.core.Admin.isAdmin();
        PopupMenu menu = new PopupMenu(c, anchor);
        menu.getMenu().add(saved ? "🔖 Unsave post" : "🔖 Save post");
        boolean subscribed = p.subscribedBy != null && Fb.uid() != null && p.subscribedBy.contains(Fb.uid());
        menu.getMenu().add(subscribed ? "🔕 Turn off notifications for this post" : "🔔 Turn on notifications for this post");
        if (!boostedPostIds.contains(p.id))
            menu.getMenu().add("🚀 Boost Post — Rs " + com.friendbook.core.Admin.PRICE_BOOST_POST + " · more reach");
        if (mine) {
            boolean pinned = Fb.me != null && p.id != null && p.id.equals(Fb.me.pinnedPostId);
            menu.getMenu().add(pinned ? "📌 Unpin from profile" : "📌 Pin to profile");
            menu.getMenu().add("✏️ Edit post");
            menu.getMenu().add("🌐 Who can see this? (" + p.privacy + ")");
            menu.getMenu().add("📦 Move to Archive");
            menu.getMenu().add("🗑 Move to Recycle Bin (30d)");
            menu.getMenu().add("🗑 Delete permanently");
        } else {
            menu.getMenu().add("➕ Follow " + p.authorName);
            menu.getMenu().add("🙈 Hide this post");
            menu.getMenu().add("😴 Snooze " + p.authorName + " for 30 days");
            menu.getMenu().add("🚫 Block " + p.authorName);
        }
        if (isAdmin && !mine) menu.getMenu().add("🛡 🗑 Delete post (admin)");
        if (p.content != null && !p.content.isEmpty()) {
            menu.getMenu().add("🔊 Read post aloud");
            menu.getMenu().add("🌐 Translate post");
        }
        menu.getMenu().add("📋 Copy text");
        menu.getMenu().add("🔗 Copy link");
        menu.getMenu().add("📤 Share to other apps");
        menu.getMenu().add("👍 See who reacted");
        menu.getMenu().add("📖 Add to your story");
        if (!mine) menu.getMenu().add("❓ Why am I seeing this?");
        if (!mine) menu.getMenu().add("🚩 Report post");
        menu.setOnMenuItemClickListener(item -> {
            String t = item.getTitle().toString();
            if (t.contains("See who reacted")) Reactions.showWhoReacted(activity, p.id);
            else if (t.contains("Unsave")) toggleSave(p, true);
            else if (t.contains("notifications for this post")) {
                if (Fb.uid() != null) {
                    Fb.db().collection("posts").document(p.id).update("subscribedBy",
                            subscribed ? FieldValue.arrayRemove(Fb.uid()) : FieldValue.arrayUnion(Fb.uid()));
                    Ui.toast(activity, subscribed ? "Post notifications OFF" : "Post notifications ON 🔔");
                }
            }
            else if (t.contains("Move to Archive")) {
                Fb.db().collection("posts").document(p.id).update("archived", true);
                posts.remove(p);
                notifyDataSetChanged();
                Ui.toast(activity, "Moved to Archive 📦 (Settings → Archive & Trash)");
            }
            else if (t.contains("Move to Recycle Bin")) {
                Fb.db().collection("posts").document(p.id).update("trashedAt", System.currentTimeMillis());
                posts.remove(p);
                notifyDataSetChanged();
                Ui.toast(activity, "Moved to Recycle Bin for 30 days 🗑");
            }
            else if (t.contains("Read post aloud")) speakPost(p.content);
            else if (t.contains("Translate post")) translatePost(p.content);
            else if (t.contains("Pin to profile") || t.contains("Unpin from profile")) {
                boolean unpin = t.contains("Unpin");
                java.util.Map<String, Object> upd = new HashMap<>();
                upd.put("pinnedPostId", unpin
                        ? com.google.firebase.firestore.FieldValue.delete() : p.id);
                Fb.db().collection("users").document(Fb.uid()).update(upd)
                        .addOnSuccessListener(x -> Ui.toast(activity,
                                unpin ? "Unpinned 📌" : "Post pinned to your profile 📌"))
                        .addOnFailureListener(e -> Ui.toast(activity, e.getMessage()));
            }
            else if (t.contains("Save post")) toggleSave(p, false);
            else if (t.contains("Boost Post")) {
                Intent bi = new Intent(activity, com.friendbook.pay.PayRequestActivity.class);
                bi.putExtra("type", "boost_post");
                bi.putExtra("target_id", p.id);
                bi.putExtra("amount", com.friendbook.core.Admin.PRICE_BOOST_POST);
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
            else if (t.contains("Share to other")) {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                String body = (p.content == null ? "" : p.content)
                        + (p.mediaUrl != null && !p.mediaUrl.isEmpty() ? "\n" + p.mediaUrl : "")
                        + "\n— via friendbook";
                send.putExtra(Intent.EXTRA_TEXT, body.isEmpty() ? "friendbook://post/" + p.id : body);
                activity.startActivity(Intent.createChooser(send, "Share post"));
            }
            else if (t.contains("Add to your story")) shareToStory(p);
            else if (t.contains("Why am I seeing"))
                Ui.info(activity, "Why this post?",
                        (p.sharedFromId != null && !p.sharedFromId.isEmpty()
                                ? "You follow " + p.authorName + " and they shared this post.\n"
                                : "You follow " + p.authorName + ".\n")
                                + ("Friends".equalsIgnoreCase(p.privacy) ? "Audience: friends only.\n"
                                : "Only me".equalsIgnoreCase(p.privacy) ? "Audience: only the author.\n"
                                : "Audience: public.\n")
                                + "Posted " + Format.ago(p.createdAt) + ". Hide, snooze or block from this same menu.");
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

    private void speakPost(String content) {
        if (content == null || content.isEmpty()) return;
        try {
            if (tts == null) {
                tts = new android.speech.tts.TextToSpeech(activity.getApplicationContext(), st -> {
                    if (st == android.speech.tts.TextToSpeech.SUCCESS && tts != null) {
                        tts.speak(content, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "post_tts");
                    }
                });
            } else {
                tts.speak(content, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "post_tts");
            }
            Ui.toast(activity, "🔊 Reading post aloud…");
        } catch (Throwable t) {
            Ui.toast(activity, "Text-to-speech unavailable on this device");
        }
    }

    private void translatePost(String content) {
        if (content == null || content.isEmpty()) return;
        try {
            String q = android.net.Uri.encode(content);
            String lang = Prefs.appLang(activity);
            String tl = "en".equals(lang) || "ur-ro".equals(lang) ? "en" : lang;
            Ui.openUrl(activity, "https://translate.google.com/?sl=auto&tl=" + tl + "&text=" + q + "&op=translate");
        } catch (Throwable t) {
            Ui.toast(activity, "Translation unavailable");
        }
    }

    private void sendQuickFriendRequest(Models.User target, TextView btn) {
        String me = Fb.uid();
        if (me == null || target == null || target.uid == null) return;
        Map<String, Object> req = Fb.map(
                "fromUid", me,
                "toUid", target.uid,
                "fromUserName", Fb.myName(),
                "fromUserPhotoUrl", Fb.myPhoto(),
                "status", "pending",
                "createdAt", FieldValue.serverTimestamp());
        Fb.db().collection("friend_requests").document(me + "_" + target.uid)
                .set(req)
                .addOnSuccessListener(x -> {
                    btn.setText("Requested ✓");
                    btn.setBackground(Ui.round(activity, Ui.inputBg(activity), 12));
                    btn.setTextColor(Ui.sub(activity));
                    Notify.send(target.uid, "friend_request", "sent you a friend request", "");
                });
    }

    // ================= ViewHolders (views built in constructors) =================

    class HeaderVH extends RecyclerView.ViewHolder {
        final StoriesAdapter storiesAdapter;
        final ImageView composeAvatar;
        final TextView composeHint;
        final LinearLayout boostBox, liveBox, pymkBox, boostCards, liveCards, pymkCards;
        final TextView goLiveBtn;

        HeaderVH(Context c) {
            super(Ui.col(c));
            LinearLayout col = (LinearLayout) itemView;
            col.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));

            // 1. Authentic FB-Lite Composer Row FIRST (Avatar+Green Dot · "What's on your mind?" · Photo)
            LinearLayout row = Ui.row(c);
            row.setBackgroundColor(Ui.card(c));
            row.setGravity(Gravity.CENTER_VERTICAL);
            Ui.pad(row, 12, 10, 12, 10);

            FrameLayout avWrap = new FrameLayout(c);
            int s = Ui.dp(c, 42);
            composeAvatar = new ImageView(c);
            avWrap.addView(composeAvatar, new FrameLayout.LayoutParams(s, s));
            View onlineDot = new View(c);
            onlineDot.setBackground(Ui.roundStroke(c, Ui.GREEN, 7, Ui.card(c), 2));
            int ds = Ui.dp(c, 13);
            FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(ds, ds, Gravity.BOTTOM | Gravity.END);
            avWrap.addView(onlineDot, dlp);
            avWrap.setOnClickListener(v -> {
                if (host != null && Fb.uid() != null) host.openProfile(Fb.uid());
            });
            row.addView(avWrap, new LinearLayout.LayoutParams(s + Ui.dp(c, 2), s + Ui.dp(c, 2)));

            composeHint = Ui.tv(c, "What's on your mind?", 15, Ui.txt(c));
            composeHint.setBackground(Ui.round(c, Ui.inputBg(c), 22));
            composeHint.setPadding(Ui.dp(c, 16), Ui.dp(c, 10), Ui.dp(c, 16), Ui.dp(c, 10));
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, -2, 1);
            hp.leftMargin = Ui.dp(c, 10);
            hp.rightMargin = Ui.dp(c, 10);
            row.addView(composeHint, hp);

            LinearLayout photoCol = Ui.col(c);
            photoCol.setGravity(Gravity.CENTER);
            ImageView photo = com.friendbook.core.Icons.icon(
                    c, com.friendbook.core.Icons.IMAGE, 24, Ui.GREEN);
            photoCol.addView(photo);
            TextView photoLbl = Ui.tv(c, "Photo", 11, Ui.txt(c));
            photoCol.addView(photoLbl);
            photoCol.setOnClickListener(v -> {
                Intent i = new Intent(c, CreatePostActivity.class);
                i.putExtra("quick_mode", "photo");
                c.startActivity(i);
            });
            row.addView(photoCol);

            composeHint.setOnClickListener(v -> c.startActivity(new Intent(c, CreatePostActivity.class)));
            col.addView(row);
            col.addView(Ui.divider(c));

            // 2. Tall Vertical Rounded Story Cards Rail SECOND (108dp × 190dp + "Find friends" card)
            RecyclerView rail = new RecyclerView(c);
            rail.setBackgroundColor(Ui.card(c));
            rail.setLayoutManager(new LinearLayoutManager(c, RecyclerView.HORIZONTAL, false));
            Ui.pad(rail, 8, 8, 8, 8);
            storiesAdapter = new StoriesAdapter();
            rail.setAdapter(storiesAdapter);
            col.addView(rail, new LinearLayout.LayoutParams(-1, Ui.dp(c, 206)));

            View gap = new View(c);
            gap.setBackgroundColor(Ui.bg(c));
            col.addView(gap, new LinearLayout.LayoutParams(-1, Ui.dp(c, 6)));

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

            // 👥 People You May Know strip (Facebook-Lite Home suggestion rail)
            pymkBox = Ui.col(c);
            pymkBox.setBackgroundColor(Ui.card(c));
            pymkBox.setVisibility(View.GONE);
            TextView pymkTitle = Ui.tvBold(c, "👥 People You May Know", 14, Ui.txt(c));
            Ui.pad(pymkTitle, 12, 10, 12, 4);
            pymkBox.addView(pymkTitle);
            android.widget.HorizontalScrollView psv = new android.widget.HorizontalScrollView(c);
            psv.setHorizontalScrollBarEnabled(false);
            pymkCards = Ui.row(c);
            Ui.pad(pymkCards, 8, 0, 8, 8);
            psv.addView(pymkCards);
            pymkBox.addView(psv);
            col.addView(pymkBox);
        }

        private TextView quickComposerChip(Context c, String label, Runnable action) {
            TextView t = Ui.tvBold(c, label, 12, Ui.sub(c));
            t.setGravity(Gravity.CENTER);
            Ui.pad(t, 4, 4, 4, 4);
            t.setOnClickListener(v -> action.run());
            return t;
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
            // Guard: devices without Google Play Services / ads provider throw here —
            // that used to crash the whole home feed on bind.
            try {
                adView = new com.google.android.gms.ads.AdView(box.getContext());
                adView.setAdSize(com.google.android.gms.ads.AdSize.BANNER);
                adView.setAdUnitId(bannerId);
                box.addView(adView, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
                adView.loadAd(new com.google.android.gms.ads.AdRequest.Builder().build());
                Ui.pad(box, 0, 0, 0, 8);
            } catch (Throwable t) {
                adView = null;
                box.removeAllViews();
                itemView.setVisibility(View.GONE);
            }
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
        TextView name, meta, more, closeBtn, content, like, comment, share;
        TextView boostTag, tagSummary, commentsSummary;
        TextView sharedName, sharedContent, playBadge, pageBadge;
        LinearLayout specialBox, sharedBox, pollBox, summaryRow;
        FrameLayout mediaWrap;
        ListenerRegistration reactReg;

        PostVH(Context c) {
            super(cardRoot(c));
            LinearLayout card = (LinearLayout) itemView;

            // ---- top row: 42dp circular avatar · bold name (+time below) · •••  ✕ ----
            LinearLayout head = Ui.row(c);
            head.setGravity(Gravity.CENTER_VERTICAL);
            Ui.pad(head, 12, 10, 8, 6);
            avatar = new ImageView(c);
            int s = Ui.dp(c, 42);
            head.addView(avatar, new LinearLayout.LayoutParams(s, s));
            LinearLayout nameCol = Ui.col(c);
            LinearLayout.LayoutParams ncp = new LinearLayout.LayoutParams(0, -2, 1);
            ncp.leftMargin = Ui.dp(c, 10);
            LinearLayout nameRow = Ui.row(c);
            nameRow.setGravity(Gravity.CENTER_VERTICAL);
            name = Ui.tvBold(c, "", 15, Ui.txt(c));
            tick = new ImageView(c);
            tick.setImageResource(com.friendbook.R.drawable.ic_verified);
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
            more = Ui.tvBold(c, "•••", 16, Ui.sub(c));
            more.setPadding(Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 8), Ui.dp(c, 6));
            head.addView(more);
            closeBtn = Ui.tvBold(c, "✕", 16, Ui.sub(c));
            closeBtn.setPadding(Ui.dp(c, 8), Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6));
            head.addView(closeBtn);
            card.addView(head);

            // ---- special FB-Lite banner (Life Event / Q&A / Marketplace) ----
            specialBox = Ui.col(c);
            Ui.pad(specialBox, 12, 2, 12, 6);
            specialBox.setVisibility(View.GONE);
            card.addView(specialBox);

            // ---- content: 16sp text OR Colored Background Card ----
            content = Ui.tv(c, "", 16, Ui.txt(c));
            Ui.pad(content, 12, 2, 12, 8);
            card.addView(content, new LinearLayout.LayoutParams(-1, -2));

            // ---- poll rows ----
            pollBox = Ui.col(c);
            Ui.pad(pollBox, 12, 2, 12, 6);
            card.addView(pollBox);

            // ---- shared embed ----
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

            // ---- media: single full-width edge-to-edge image (video via ▶ overlay) ----
            mediaWrap = new FrameLayout(c);
            card.addView(mediaWrap, new LinearLayout.LayoutParams(-1, -2));
            image = new ImageView(c);
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setMaxHeight(Ui.dp(c, 460));
            mediaWrap.addView(image, new FrameLayout.LayoutParams(-1, -2));
            playBadge = Ui.tv(c, "▶", 44, 0xEEFFFFFF);
            playBadge.setGravity(Gravity.CENTER);
            playBadge.setBackgroundColor(0x99000000);
            playBadge.setVisibility(View.GONE);
            mediaWrap.addView(playBadge, new FrameLayout.LayoutParams(-1, -1));
            pageBadge = Ui.tvBold(c, "", 12, 0xFFFFFFFF);
            pageBadge.setBackground(Ui.round(c, 0x99000000, 20));
            Ui.pad(pageBadge, 8, 4, 8, 4);
            pageBadge.setVisibility(View.GONE);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP);
            blp.setMargins(0, Ui.dp(c, 10), Ui.dp(c, 10), 0);
            mediaWrap.addView(pageBadge, blp);

            // ---- FB-Lite Pro Clickable Summary Row: Left="👍❤️ 24" (Who liked!) | Right="8 comments · 2 shares" ----
            summaryRow = Ui.row(c);
            summaryRow.setGravity(Gravity.CENTER_VERTICAL);
            Ui.pad(summaryRow, 12, 8, 12, 4);
            summaryRow.setVisibility(View.GONE);
            tagSummary = Ui.tvBold(c, "", 13, Ui.sub(c));
            tagSummary.setPadding(0, Ui.dp(c, 2), Ui.dp(c, 12), Ui.dp(c, 2));
            summaryRow.addView(tagSummary);
            View spacer = new View(c);
            summaryRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
            commentsSummary = Ui.tv(c, "", 13, Ui.sub(c));
            commentsSummary.setPadding(Ui.dp(c, 8), Ui.dp(c, 2), 0, Ui.dp(c, 2));
            summaryRow.addView(commentsSummary);
            card.addView(summaryRow);

            // ---- 3 Rounded Facebook Lite v525 Pill Capsule Buttons (Like · Comment · Share) ----
            LinearLayout actions = Ui.row(c);
            Ui.pad(actions, 10, 6, 10, 10);
            like = actionBtn(c, "👍 Like");
            comment = actionBtn(c, "💬 Comment");
            share = actionBtn(c, "↗ Share");
            LinearLayout.LayoutParams a1 = new LinearLayout.LayoutParams(0, -2, 1);
            a1.rightMargin = Ui.dp(c, 6);
            LinearLayout.LayoutParams a2 = new LinearLayout.LayoutParams(0, -2, 1);
            a2.rightMargin = Ui.dp(c, 6);
            LinearLayout.LayoutParams a3 = new LinearLayout.LayoutParams(0, -2, 1);
            actions.addView(like, a1);
            actions.addView(comment, a2);
            actions.addView(share, a3);
            card.addView(actions);

            // FB-Lite gap between posts
            View gap = new View(c);
            gap.setBackgroundColor(Ui.bg(c));
            card.addView(gap, new LinearLayout.LayoutParams(-1, Ui.dp(c, 6)));
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
            t.setBackground(Ui.round(c, Ui.inputBg(c), 20));
            t.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 8));
            return t;
        }
    }
}
