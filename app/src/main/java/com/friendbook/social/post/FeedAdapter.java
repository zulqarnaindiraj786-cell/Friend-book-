package com.friendbook.social.post;

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

import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Icons;
import com.friendbook.social.core.Notify;
import com.friendbook.social.core.Prefs;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.friendbook.social.story.StoriesAdapter;
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
        default boolean automaticPaging(){return false;}
        default boolean isLoadingMore(){return false;}
        default boolean hasPageError(){return false;}
        default void openFindFriends() { }
        default void loadMoreStories() { }
        default boolean hasMoreStories() { return false; }
    }

    private static final int VT_HEADER = 0, VT_POST = 1, VT_FOOTER = 2, VT_AD = 3;

    private final Activity activity;
    private final Host host;
    private final List<Models.Post> posts = new ArrayList<>();
    private List<StoriesAdapter.Group> storyGroups = new ArrayList<>();
    public boolean showHeader = true;
    public boolean showFooter = true;
    public boolean showInlineComments = true;
    /** When >= 0 (in PostDetailActivity for a Sponsored Ad), renders ONLY that 1 real AdVH and ZERO PostVH! */
    public int standaloneAdSlotOrdinal = -1;

    public FeedAdapter(Activity a, Host h) {
        this.activity = a;
        this.host = h;
        com.friendbook.social.core.AdsConfig.warmUpPools(a);
    }

    public void setPosts(List<Models.Post> list) {
        if (list == null) list = new ArrayList<>();
        // ⚡ Anti-Jump In-Place Diff: if post IDs & order are identical, NEVER call notifyDataSetChanged()!
        if (!posts.isEmpty() && posts.size() == list.size()) {
            boolean sameOrder = true;
            for (int i = 0; i < posts.size(); i++) {
                Models.Post oldP = posts.get(i);
                Models.Post newP = list.get(i);
                String oldId = oldP == null ? null : oldP.id;
                String newId = newP == null ? null : newP.id;
                if (oldId == null || !oldId.equals(newId)) {
                    sameOrder = false;
                    break;
                }
            }
            if (sameOrder) {
                for (int i = 0; i < list.size(); i++) {
                    Models.Post oldP = posts.get(i);
                    Models.Post newP = list.get(i);
                    posts.set(i, newP);
                    boolean metaChanged = oldP.likes != newP.likes
                            || oldP.comments != newP.comments
                            || oldP.shares != newP.shares
                            || (oldP.inlineComments == null ? 0 : oldP.inlineComments.size())
                            != (newP.inlineComments == null ? 0 : newP.inlineComments.size())
                            || !sameReactionsSignature(oldP, newP);
                    if (metaChanged) {
                        notifyItemChanged(offset() + slotForPostIndex(i), "META_ONLY");
                    }
                }
                return;
            }
        }
        posts.clear();
        java.util.Set<String> existing=new java.util.HashSet<>();
        for(Models.Post post:posts) if(post!=null) existing.add(post.id);
        for(Models.Post post:list) if(post!=null && existing.add(post.id)) posts.add(post);
        notifyDataSetChanged();
    }

    private static boolean sameReactionsSignature(Models.Post a, Models.Post b) {
        Map<String, Map<String, Object>> ma = a == null ? null : a.reactionsMap;
        Map<String, Map<String, Object>> mb = b == null ? null : b.reactionsMap;
        int sa = ma == null ? 0 : ma.size();
        int sb = mb == null ? 0 : mb.size();
        if (sa != sb) return false;
        if (sa == 0) return true;
        for (Map.Entry<String, Map<String, Object>> e : ma.entrySet()) {
            Map<String, Object> other = mb.get(e.getKey());
            if (other == null) return false;
            String ta = Format.strOr(e.getValue(), "type", "like");
            String tb = Format.strOr(other, "type", "like");
            if (!ta.equals(tb)) return false;
        }
        return true;
    }

    /** Exact adapter slot (excluding header) for a post index, accounting for Feed Native Ads. */
    private int slotForPostIndex(int postIdx) {
        if (!adsActive() || posts.size() < NATIVE_AD_EVERY_N_POSTS) return postIdx;
        return postIdx + (postIdx / NATIVE_AD_EVERY_N_POSTS);
    }

    /** ⚡ 0.0ms optimistic reaction refresh for a single post slot (payload-only — ZERO height/scroll jump!). */
    public void onFastReactionUpdate(String postId, String mine, int total) {
        if (postId == null) return;
        if (standaloneAdSlotOrdinal >= 0 && postId.startsWith("sponsored_ad_")) {
            notifyItemChanged(0);
            return;
        }
        String me = Fb.uid();
        for (int i = 0; i < posts.size(); i++) {
            Models.Post p = posts.get(i);
            if (p != null && postId.equals(p.id)) {
                p.likes = total;
                if (me != null && !me.isEmpty()) {
                    if (p.reactionsMap == null) p.reactionsMap = new HashMap<>();
                    if (mine == null || mine.isEmpty()) {
                        p.reactionsMap.remove(me);
                    } else {
                        Map<String, Object> entry = p.reactionsMap.get(me);
                        if (entry == null) entry = new HashMap<>();
                        entry.put("userId", me);
                        entry.put("type", mine);
                        p.reactionsMap.put(me, entry);
                    }
                }
                notifyItemChanged(offset() + slotForPostIndex(i), "REACTION_ONLY");
                return;
            }
        }
    }

    /** Removes a post immediately when auto-deleted by ContentSafetyEngine. */
    public void removePostById(String postId) {
        if (postId == null) return;
        for (int i = 0; i < posts.size(); i++) {
            Models.Post p = posts.get(i);
            if (p != null && postId.equals(p.id)) {
                posts.remove(i);
                notifyDataSetChanged();
                return;
            }
        }
    }

    public List<Models.Post> getPosts() {
        return posts;
    }

    public void addPosts(List<Models.Post> list) {
        if (list == null || list.isEmpty()) return;
        int oldItemCount = getItemCount();
        posts.addAll(list);
        int newItemCount = getItemCount();
        if (newItemCount > oldItemCount) {
            int insertStart = Math.max(0, oldItemCount - footerCount());
            notifyItemRangeInserted(insertStart, newItemCount - oldItemCount);
            if (showFooter) notifyItemChanged(newItemCount - 1, "paging");
        } else {
            notifyDataSetChanged();
        }
    }

    public void setStories(List<StoriesAdapter.Group> groups) {
        storyGroups = groups == null ? new ArrayList<>() : groups;
        if (showHeader) notifyItemChanged(0);
    }

    // ---- live + PYMK rows for the header ----
    private final java.util.List<Models.User> pymkUsers = new ArrayList<>();
    private final java.util.List<java.util.Map<String, Object>> activeLives = new ArrayList<>();
    private android.speech.tts.TextToSpeech tts;

    public void setPymk(java.util.List<Models.User> users) {
        pymkUsers.clear();
        if (users != null) pymkUsers.addAll(users);
        if (showHeader) notifyItemChanged(0);
    }

    public void setLives(java.util.List<java.util.Map<String, Object>> lives) {
        activeLives.clear();
        if (lives != null) activeLives.addAll(lives);
        if (showHeader) notifyItemChanged(0);
    }

    private int offset() { return showHeader ? 1 : 0; }
    private int footerCount() { return showFooter ? 1 : 0; }

    // ---- 📢 Google AdMob Feed Native Ad Slots (`ca-app-pub-3387480177023669/4957447582`) ----
    private static final int NATIVE_AD_EVERY_N_POSTS = 3;
    private static final int NATIVE_AD_STEP = NATIVE_AD_EVERY_N_POSTS + 1; // 3 posts + 1 Native Ad = 4

    private String nativeAdUnitId = com.friendbook.social.core.AdsConfig.feedNativeId();
    private boolean adsEnabled = com.friendbook.social.core.AdsConfig.nativeAdsEnabled();

    public void setAdsConfig(String id, boolean enabled) {
        String cleanId = com.friendbook.social.core.AdsConfig.isRealProductionUnit(id)
                ? id.trim()
                : com.friendbook.social.core.AdsConfig.FEED_NATIVE_ID;
        boolean nextEnabled = enabled && !cleanId.isEmpty();
        if (cleanId.equals(nativeAdUnitId) && nextEnabled == adsEnabled) {
            notifyAdSlotsOnly();
            return;
        }
        nativeAdUnitId = cleanId;
        adsEnabled = nextEnabled;
        notifyDataSetChanged();
    }

    /** Rebinds only Feed Ad slots that are still waiting for an ad — never touches already-loaded Ad slots! */
    public void notifyAdSlotsOnly() {
        if (!adsActive() || posts.isEmpty()) return;
        int total = getItemCount();
        for (int pos = offset(); pos < total - footerCount(); pos++) {
            if (isAdSlot(pos - offset())) {
                int slotOrd = Math.max(0, (pos - offset()) / NATIVE_AD_STEP);
                if (!com.friendbook.social.core.AdsConfig.hasFeedSlotAd(slotOrd)) {
                    notifyItemChanged(pos);
                }
            }
        }
    }

    /** Triggers fresh Native Ad requests on Feed refresh — rotates in preloaded ads from pool. */
    public void refreshSponsoredAds() {
        com.friendbook.social.core.AdsConfig.clearFeedSlotAds(null);
        notifyDataSetChanged();
    }

    public void clearCachedNativeAds() {
        // Fresh-load architecture: no shared NativeAd cache is retained.
    }

    private boolean adsActive() {
        return adsEnabled
                && nativeAdUnitId != null
                && !nativeAdUnitId.isEmpty();
    }

    private int adCount() {
        if (standaloneAdSlotOrdinal >= 0) return 1;
        if (!adsActive() || posts.isEmpty()) return 0;
        if (posts.size() < NATIVE_AD_EVERY_N_POSTS) return 1;
        return posts.size() / NATIVE_AD_EVERY_N_POSTS;
    }

    /** Does this feed slot (after the header) hold a Native Ad? */
    private boolean isAdSlot(int slot) {
        if (standaloneAdSlotOrdinal >= 0) return slot == 0;
        if (!adsActive() || posts.isEmpty()) return false;
        if (posts.size() < NATIVE_AD_EVERY_N_POSTS) {
            return slot == posts.size();
        }
        return ((slot + 1) % NATIVE_AD_STEP == 0)
                && ((slot / NATIVE_AD_STEP) < adCount());
    }

    private int postIndexForSlot(int slot) {
        if (!adsActive() || posts.size() < NATIVE_AD_EVERY_N_POSTS) return slot;
        return slot - ((slot + 1) / NATIVE_AD_STEP);
    }

    private int slotCount() {
        if (standaloneAdSlotOrdinal >= 0) return 1;
        return posts.size() + adCount();
    }

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
        if (viewType == VT_AD) return new AdVH(parent);
        return new PostVH(c);
    }



    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position,
                                 @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && h instanceof PostVH) {
            int idx = postIndexForSlot(position - offset());
            if (idx >= 0 && idx < posts.size()) {
                Models.Post p = posts.get(idx);
                PostVH vh = (PostVH) h;
                Reactions.hydrateFromPostInstant(p);
                int totalLikes = com.friendbook.social.core.FastCache.getReactionTotal(p.id, p.likes);
                String myReact = Reactions.resolveCurrentUserReaction(p);
                renderReactionBar(activity, vh, p, myReact, totalLikes);
                if (payloads.contains("META_ONLY")) {
                    bindInlineComments(activity, vh, p);
                }
                return;
            }
        }
        super.onBindViewHolder(h, position, payloads);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
        if (h instanceof HeaderVH) {
            HeaderVH hh = (HeaderVH) h;
            hh.storiesAdapter.setData(storyGroups);
            hh.moreStories.setVisibility(host!=null && host.hasMoreStories()?View.VISIBLE:View.GONE);
            hh.moreStories.setOnClickListener(v -> { if(host!=null) host.loadMoreStories(); });
            hh.storiesAdapter.listener = new StoriesAdapter.Listener() {
                @Override public void onGroup(StoriesAdapter.Group g) { if (host != null) host.openStoryGroup(g); }
                @Override public void onCreate() { if (host != null) host.createStory(); }
                @Override public void onFindFriends() {
                    if (host != null) host.openFindFriends();
                }
            };
            Ui.avatar(activity, hh.composeAvatar, Fb.myPhoto());
            String myFull = Fb.myName();
            String first = (myFull != null && !myFull.trim().isEmpty())
                    ? myFull.trim().split("\\s+")[0] : "";
            hh.composeHint.setText(first.isEmpty() || "FriendBook".equalsIgnoreCase(first)
                    ? "What's on your mind?"
                    : "What's on your mind, " + first + "?");
            // 🔴 live rooms + ALWAYS-VISIBLE Go-Live & Live Hub bar!
            hh.goLiveBtn.setOnClickListener(v -> {
                Intent li = new Intent(activity, com.friendbook.social.live.LiveActivity.class);
                li.putExtra("host", true);
                activity.startActivity(li);
            });
            hh.liveBox.setVisibility(View.VISIBLE);
            hh.liveCards.removeAllViews();
            hh.liveCards.setVisibility(activeLives.isEmpty() ? View.GONE : View.VISIBLE);
            // 👥 People You May Know horizontal rail (friendbook signature widget)
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
                card.setBackground(Ui.roundStroke(activity, Ui.inputBg(activity), 14, 0x55E41E3F, 1));
                Ui.pad(card, 12, 8, 12, 8);
                ImageView av = new ImageView(activity);
                String liveAv = Format.str(live, "hostPhotoUrl");
                if (liveAv.isEmpty()) liveAv = Format.str(live, "hostPhoto");
                Ui.avatar(activity, av, liveAv);
                int s = Ui.dp(activity, 46);
                FrameLayout ring = new FrameLayout(activity);
                ring.setBackground(Ui.roundStroke(activity, 0x00000000, 30, 0xFFE41E3F, 2));
                Ui.pad(ring, 2, 2, 2, 2);
                ring.addView(av, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
                LinearLayout.LayoutParams avp = new LinearLayout.LayoutParams(-2, -2);
                avp.gravity = Gravity.CENTER;
                card.addView(ring, avp);
                TextView nm = Ui.tvBold(activity, Format.strOr(live, "hostName", "Live"), 11, Ui.txt(activity));
                nm.setSingleLine(true);
                nm.setMaxWidth(Ui.dp(activity, 88));
                nm.setPadding(0, Ui.dp(activity, 3), 0, Ui.dp(activity, 2));
                card.addView(nm);
                int vCnt = Math.max(1, Math.max(Format.intOf(live, "viewers"), Format.intOf(live, "viewerCount")));
                card.addView(Ui.roundChip(activity, "🔴 " + Format.count(vCnt),
                        0xFFE41E3F, 0xFFFFFFFF, 9));
                final String lid = Format.str(live, "_id");
                final String hostId = Format.str(live, "hostId");
                card.setOnClickListener(v -> {
                    Intent li = new Intent(activity, com.friendbook.social.live.LiveActivity.class);
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
            boolean loading=host!=null && host.isLoadingMore(), retry=host!=null && host.hasPageError();
            fh.spinner.setVisibility(loading?View.VISIBLE:View.GONE);
            fh.more.setVisibility(!loading && host!=null && (retry || (more && !host.automaticPaging()))?View.VISIBLE:View.GONE);
            fh.more.setText(retry?"Tap to continue loading":"Load more posts");
            fh.end.setVisibility(more || loading || retry?View.GONE:View.VISIBLE);
            // Never leave a blank white feed unexplained — say WHY it's empty
            fh.end.setText(posts.isEmpty()
                    ? "😴 No posts to show yet.\nPull down to refresh.\nIf posts exist but stay missing, check the internet & try re-login."
                    : "You're all caught up 🎉");
            fh.more.setOnClickListener(v -> { if (host != null) host.loadMore(); });
        } else if (h instanceof AdVH) {
            int adSlotOrdinal = standaloneAdSlotOrdinal >= 0
                    ? standaloneAdSlotOrdinal
                    : Math.max(0, (position - offset()) / NATIVE_AD_STEP);
            ((AdVH) h).loadFreshFeedNativeAd(nativeAdUnitId, adSlotOrdinal, standaloneAdSlotOrdinal >= 0);
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
            renderReactionBar(activity, vh, p != null ? p : new Models.Post(), null, 0);
            if (vh.reactSummaryWrap != null) {
                vh.reactSummaryWrap.setVisibility(View.GONE);
            }
            // placeholder cards must still respond — comment/share/⋮ stay usable
            if (p != null) {
                vh.comment.setOnClickListener(v -> { if (host != null) host.openComments(p); });
                vh.content.setOnClickListener(v -> { if (host != null) host.openComments(p); });
                vh.share.setOnClickListener(v -> shareDialog(p));
                vh.more.setOnClickListener(v -> postMenu(vh.more, p));
                vh.like.setOnClickListener(v -> {
                    Ui.haptic(v);
                    Reactions.tapLike(activity, p);
                    renderReactionBar(activity, vh, p,
                            Reactions.resolveCurrentUserReaction(p),
                            com.friendbook.social.core.FastCache.getReactionTotal(p.id, p.likes));
                    animateLikePop(vh);
                });
                vh.like.setOnLongClickListener(null);
            }
        } catch (Throwable ignored) { }
    }

    /**
     * #hashtags aur @mentions ko friendbook jaisa blue+bold style karo.
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
        if (h instanceof AdVH) ((AdVH) h).onRecycled();
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
        if (showHeader && p != null && p.id != null) {
            com.friendbook.social.core.FeedAlgorithm.recordPostImpression(c, p.id);
            if ("video".equals(p.mediaType)) {
                com.friendbook.social.core.FeedAlgorithm.recordVideoImpression(c, p.id);
            }
        }

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
            vh.tick.setImageResource(com.friendbook.social.core.Icons.badgeFor(p.authorTickLevel));
        } else {
            vh.tick.setVisibility(p.authorVerified ? View.VISIBLE : View.GONE);
            if (p.authorVerified) vh.tick.setImageResource(com.friendbook.social.R.drawable.ic_verified);
        }

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
                Intent pi = new Intent(c, com.friendbook.social.social.PageDetailActivity.class);
                pi.putExtra("page_id", p.pageId);
                c.startActivity(pi);
            } else if (host != null) {
                host.openProfile(p.authorId);
            }
        };
        vh.avatar.setOnClickListener(prof);
        vh.avatar.setOnLongClickListener(v -> {
            String dpUrl = pagePost ? p.pagePhoto : p.authorPhoto;
            if (dpUrl != null && !dpUrl.isEmpty()) {
                Intent fullDp = new Intent(c, FullscreenMediaActivity.class);
                fullDp.putExtra("type", "image");
                fullDp.putExtra("url", dpUrl);
                fullDp.putExtra("title", (pagePost ? p.pageName : p.authorName) + "'s Profile Picture");
                c.startActivity(fullDp);
                return true;
            }
            return false;
        });
        vh.name.setOnClickListener(prof);
        vh.like.setOnClickListener(v -> {
            Ui.haptic(v);
            Reactions.tapLike(c, p);
            renderReactionBar(c, vh, p,
                    Reactions.resolveCurrentUserReaction(p),
                    com.friendbook.social.core.FastCache.getReactionTotal(p.id, p.likes));
            animateLikePop(vh);
        });
        vh.like.setOnLongClickListener(v -> {
            Reactions.show(vh.like, type -> {
                Reactions.apply(c, p, type);
                renderReactionBar(c, vh, p,
                        Reactions.resolveCurrentUserReaction(p),
                        com.friendbook.social.core.FastCache.getReactionTotal(p.id, p.likes));
                animateLikePop(vh);
            });
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

        // ---------- Special friendbook cards: Life Event / Q&A / Marketplace ----------
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
            mk.setOnClickListener(v -> c.startActivity(new Intent(c, com.friendbook.social.settings.MarketplaceActivity.class)));
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
            vh.content.setText(hasText ? styleHashtags(c, p.content) : "");
        }
        vh.content.setText(com.friendbook.social.core.Mentions.styled(vh.content.getText(), p.mentions));
        com.friendbook.social.core.LinkUtils.makeLinksClickable(vh.content);
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
                && !com.friendbook.social.core.FastCache.isPhotoRevealed(p.id);

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
            // 📵 friendbook Basic / Text-Only 2G Mode: 0 KB automatic photo load!
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
                com.friendbook.social.core.FastCache.revealPhoto(p.id);
                ViewGroup.LayoutParams nwp = vh.mediaWrap.getLayoutParams();
                nwp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                vh.mediaWrap.setLayoutParams(nwp);
                vh.playBadge.setVisibility(View.GONE);
                Ui.imgFeed(c, vh.image, fImg);
                vh.image.setOnClickListener(iv -> {
                    Intent i = new Intent(c, FullscreenMediaActivity.class);
                    i.putExtra("type", "image");
                    i.putExtra("url", fImg);
                    i.putExtra("post_id", p.id);
                    i.putExtra("title", p.authorName);
                    c.startActivity(i);
                });
            });
        } else if (fImg != null) {
            vh.image.setVisibility(View.VISIBLE);
            vh.playBadge.setBackgroundColor(0x99000000);
            Ui.imgFeedModerated(c, vh.image, fImg, "posts", p.id, p.authorId, p.content, () -> {
                removePostById(p.id);
                Ui.toast(c, "🛡 Unsafe post automatically deleted");
            });
            int album = p.mediaUrls != null ? p.mediaUrls.size() : 0;
            if (album > 1) {
                vh.pageBadge.setVisibility(View.VISIBLE);
                vh.pageBadge.setText("📷 " + album + " photos");
            }
            android.view.GestureDetector gd = new android.view.GestureDetector(c,
                    new android.view.GestureDetector.SimpleOnGestureListener() {
                        @Override public boolean onDown(android.view.MotionEvent e) { return true; }
                        @Override public boolean onSingleTapConfirmed(android.view.MotionEvent e) {
                            Intent i = new Intent(c, FullscreenMediaActivity.class);
                            i.putExtra("type", "image");
                            i.putExtra("url", fImg);
                            i.putExtra("post_id", p.id);
                            i.putExtra("title", p.authorName);
                            c.startActivity(i);
                            return true;
                        }
                        @Override public boolean onDoubleTap(android.view.MotionEvent e) {
                            if (Prefs.doubleTapToLike(c)) {
                                Ui.haptic(vh.image);
                                String curRx = Reactions.resolveCurrentUserReaction(p);
                                if (!"love".equals(curRx)) {
                                    Reactions.apply(c, p, "love");
                                }
                                renderReactionBar(c, vh, p,
                                        Reactions.resolveCurrentUserReaction(p),
                                        com.friendbook.social.core.FastCache.getReactionTotal(p.id, p.likes));
                                animateLikePop(vh);
                                Ui.toast(c, "❤️ Loved!");
                                return true;
                            }
                            return false;
                        }
                    });
            vh.image.setOnTouchListener((v, ev) -> gd.onTouchEvent(ev));
        } else if (isVideo) {
            // Video posts: instant poster frame without downloading remote .mp4 files in Glide during scroll!
            vh.image.setVisibility(View.VISIBLE);
            String vThumb = com.friendbook.social.reels.ReelVideoCache.deriveThumbUrl(p.mediaUrl, "");
            java.io.File localCachedVideo = vThumb.isEmpty()
                    ? com.friendbook.social.reels.ReelVideoCache.getCachedFile(c, p.mediaUrl)
                    : null;
            if (!vThumb.isEmpty()) {
                Ui.imgFeed(c, vh.image, vThumb);
            } else if (localCachedVideo != null) {
                com.bumptech.glide.Glide.with(c.getApplicationContext())
                        .load(localCachedVideo)
                        .centerCrop()
                        .dontAnimate()
                        .into(vh.image);
            } else if (p.authorPhoto != null && !p.authorPhoto.isEmpty()) {
                Ui.imgFeed(c, vh.image, p.authorPhoto);
            } else {
                try { com.bumptech.glide.Glide.with(c.getApplicationContext()).clear(vh.image); } catch (Throwable ignored) {}
                vh.image.setImageDrawable(Ui.round(c, 0xFF18191A, 0));
            }
            // Poster only. Full media is requested by the user's tap, never by feed binding.
            vh.playBadge.setVisibility(View.VISIBLE);
            vh.playBadge.setBackgroundColor(0x66000000);
            vh.playBadge.setTextSize(15);
            vh.playBadge.setText("▶ Tap to watch Reel");
            vh.mediaWrap.setOnClickListener(v -> {
                Intent i = new Intent(c, com.friendbook.social.reels.ReelPlayerActivity.class);
                i.putExtra("reel_id", p.id);
                i.putExtra("is_post", true);
                i.putExtra("video_url", p.mediaUrl);
                i.putExtra("author_id", p.authorId);
                i.putExtra("author_name", p.authorName);
                i.putExtra("author_photo", p.authorPhoto);
                i.putExtra("caption", p.content);
                i.putExtra("likes", p.likes);
                i.putExtra("comments", p.comments);
                i.putExtra("shares", p.shares);
                c.startActivity(i);
            });
        }

        // ---------- ⚡ 0.0ms Instant Reaction & Counter Render + Exact Subcollection Sync ----------
        if (vh.reactReg != null) { vh.reactReg.remove(); vh.reactReg = null; }
        vh.itemView.setTag(p.id);
        // Hydrate embedded post.reactionsMap BEFORE reading currentUserId's reaction!
        Reactions.hydrateFromPostInstant(p);
        int totalLikes = com.friendbook.social.core.FastCache.getReactionTotal(p.id, p.likes);
        String myReact = Reactions.resolveCurrentUserReaction(p);
        renderReactionBar(c, vh, p, myReact, totalLikes);
        bindInlineComments(c, vh, p);

        // Render post counters/embedded own reaction without a 250-document reconciliation per bind.
        // Explicit reaction-detail screens retain their bounded lookups.
    }

    /**
     * 💬 Authentic friendbook Inline Multi-User Comments Preview + Quick Comment Pill right on Home Feed!
     */
    private void bindInlineComments(Context c, PostVH vh, Models.Post p) {
        if (vh.inlineCommentsBox == null) return;
        vh.inlineCommentsBox.removeAllViews();
        if (!showInlineComments || !Prefs.inlineFeedComments(c)) {
            vh.inlineCommentsBox.setVisibility(View.GONE);
            return;
        }
        vh.inlineCommentsBox.setVisibility(View.VISIBLE);
        vh.inlineCommentsBox.addView(Ui.divider(c));

        List<Models.Comment> list = p.inlineComments != null ? p.inlineComments : new ArrayList<>();
        int total = Math.max(p.comments, list.size());

        // Show "View all X comments" link when more than 2 comments exist or comments need full open
        if (total > 2 || (total > 0 && list.isEmpty())) {
            TextView viewAll = Ui.tvBold(c, "View all " + total + (total == 1 ? " comment…" : " comments…"), 13, Ui.sub(c));
            Ui.pad(viewAll, 12, 8, 12, 4);
            viewAll.setOnClickListener(v -> { if (host != null) host.openComments(p); });
            vh.inlineCommentsBox.addView(viewAll);
        }

        // Show up to the latest 2 comments from ALL users directly on the feed card!
        int startIdx = Math.max(0, list.size() - 2);
        for (int i = startIdx; i < list.size(); i++) {
            final Models.Comment cm = list.get(i);
            if (cm == null) continue;
            LinearLayout row = Ui.row(c);
            row.setGravity(Gravity.TOP);
            Ui.pad(row, 12, 5, 12, 4);

            ImageView av = new ImageView(c);
            int s = Ui.dp(c, 28);
            row.addView(av, new LinearLayout.LayoutParams(s, s));
            Models.User cachedU = com.friendbook.social.core.FastCache.getUser(cm.authorId);
            String uPhoto = (cachedU != null && cachedU.photoUrl != null && !cachedU.photoUrl.isEmpty())
                    ? cachedU.photoUrl : cm.authorPhoto;
            String uName = (cachedU != null && cachedU.displayName != null && !cachedU.displayName.isEmpty())
                    ? cachedU.displayName : (cm.authorName == null || cm.authorName.isEmpty() ? "friendbook User" : cm.authorName);
            Ui.avatar(c, av, uPhoto);
            av.setOnClickListener(v -> { if (host != null) host.openProfile(cm.authorId); });

            LinearLayout bubble = Ui.col(c);
            bubble.setBackground(Ui.round(c, Ui.inputBg(c), 14));
            Ui.pad(bubble, 10, 6, 10, 6);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
            bp.leftMargin = Ui.dp(c, 8);

            TextView authorT = Ui.tvBold(c, (cm.pinned ? "📌 " : "") + uName, 12.5f, Ui.txt(c));
            if(cm.authorId!=null && cm.authorId.equals(p.authorId)) {
                authorT.append("  ◆ Creator"); authorT.setTextColor(0xFF1A73E8);
            }
            authorT.setOnClickListener(v -> { if(host!=null) host.openProfile(cm.authorId); });
            bubble.addView(authorT);
            String bodyTxt = cm.content != null && !cm.content.isEmpty()
                    ? cm.content : (cm.imageUrl != null && !cm.imageUrl.isEmpty() ? "📷 Sent a photo" : "");
            TextView bodyT = Ui.tv(c, bodyTxt, 13.5f, Ui.txt(c));
            bodyT.setText(com.friendbook.social.core.Mentions.styled(bodyTxt,cm.mentions));
            com.friendbook.social.core.LinkUtils.makeLinksClickable(bodyT);
            bodyT.setMaxLines(3);
            bubble.addView(bodyT);
            bubble.setOnClickListener(v -> { if (host != null) host.openComments(p); });
            row.addView(bubble, bp);
            vh.inlineCommentsBox.addView(row);
        }

        // Quick "Write a comment…" pill right below every post (friendbook signature!)
        LinearLayout quickRow = Ui.row(c);
        quickRow.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(quickRow, 12, 6, 12, 9);
        ImageView myAv = new ImageView(c);
        int ms = Ui.dp(c, 26);
        quickRow.addView(myAv, new LinearLayout.LayoutParams(ms, ms));
        Ui.avatar(c, myAv, Fb.myPhoto());
        TextView hintPill = Ui.tv(c, "Write a comment…", 13, Ui.sub(c));
        hintPill.setBackground(Ui.round(c, Ui.inputBg(c), 18));
        Ui.pad(hintPill, 12, 6, 12, 6);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(0, -2, 1);
        hlp.leftMargin = Ui.dp(c, 8);
        quickRow.addView(hintPill, hlp);
        quickRow.setOnClickListener(v -> { if (host != null) host.openComments(p); });
        vh.inlineCommentsBox.addView(quickRow);
    }

    private static void animateLikePop(PostVH vh) {
        if (vh == null) return;
        View target = vh.likeIcon.getVisibility() == View.VISIBLE ? vh.likeIcon : vh.likeEmojiTv;
        target.setScaleX(0.78f);
        target.setScaleY(0.78f);
        target.animate().scaleX(1.16f).scaleY(1.16f).setDuration(110L)
                .withEndAction(() -> target.animate().scaleX(1f).scaleY(1f).setDuration(90L).start())
                .start();
    }

    private void renderReactionBar(Context c, PostVH vh, Models.Post p, String mine, int total) {
        String currentUid = Fb.uid();
        if (currentUid == null || currentUid.isEmpty()) {
            mine = null;
        }
        boolean hideCounts = Prefs.hideReactionCounts(c)
                || (Fb.me != null && Fb.me.hideReactionCounts);
        boolean iReacted = mine != null && !mine.isEmpty();
        int effectiveTotal = iReacted ? Math.max(1, total) : Math.max(0, total);

        // 1. Separate Total Count Summary Row above the buttons:
        //    Shows total count ("1", "5", or "You and 4 others") separately from the user's own reaction button!
        if (effectiveTotal > 0) {
            List<String> activeTypes = com.friendbook.social.core.FastCache.getReactionTypes(p.id);
            if (activeTypes.isEmpty() && p.reactionsMap != null && !p.reactionsMap.isEmpty()) {
                activeTypes = Reactions.computeTopTypesFromMap(p.reactionsMap);
            }
            List<String> displayTypes = new ArrayList<>(activeTypes);
            if (iReacted && !displayTypes.contains(mine)) {
                displayTypes.add(0, mine);
            }
            if (displayTypes.isEmpty()) {
                displayTypes.add(iReacted ? mine : "like");
            }

            boolean showThumbBadge = displayTypes.contains("like");
            StringBuilder extraEmojis = new StringBuilder();
            int emojiCount = showThumbBadge ? 1 : 0;
            for (String rType : displayTypes) {
                if ("like".equals(rType)) continue;
                if (emojiCount >= 3) break;
                extraEmojis.append(Reactions.emojiFor(rType));
                emojiCount++;
            }

            vh.reactBadgeIv.setVisibility(showThumbBadge ? View.VISIBLE : View.GONE);
            if (extraEmojis.length() > 0) {
                vh.extraReactsTv.setText(extraEmojis.toString());
                vh.extraReactsTv.setPadding(showThumbBadge ? Ui.dp(c, 2) : 0, 0, 0, 0);
                vh.extraReactsTv.setVisibility(View.VISIBLE);
            } else {
                vh.extraReactsTv.setText("");
                vh.extraReactsTv.setVisibility(View.GONE);
            }

            String summaryLabel;
            if (iReacted) {
                if (hideCounts) {
                    summaryLabel = effectiveTotal > 1 ? "You and others" : "You";
                } else if (effectiveTotal == 1) {
                    summaryLabel = "You";
                } else if (effectiveTotal == 2) {
                    summaryLabel = "You and 1 other";
                } else {
                    summaryLabel = "You and " + Format.count(effectiveTotal - 1) + " others";
                }
            } else {
                summaryLabel = hideCounts ? "Reactions" : Format.count(effectiveTotal);
            }
            vh.tagSummary.setText(summaryLabel);
            vh.tagSummary.setVisibility(summaryLabel.isEmpty() ? View.GONE : View.VISIBLE);
            vh.reactSummaryWrap.setVisibility(View.VISIBLE);
            vh.reactSummaryWrap.setOnClickListener(v -> Reactions.showWhoReacted(activity, p.id));
        } else {
            vh.reactSummaryWrap.setVisibility(View.GONE);
            vh.reactSummaryWrap.setOnClickListener(null);
        }

        // 2. Right clickable comments & shares summary -> opens PostDetailActivity (All comments)!
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
            boolean anySummary = effectiveTotal > 0 || p.comments > 0 || p.shares > 0;
            vh.summaryRow.setVisibility(anySummary ? View.VISIBLE : View.GONE);
        }

        // 3. Three Rounded Pill Capsule Buttons (#F0F2F5 Light / inputBg Dark):
        //    STRICT PER-USER RULE:
        //    - If `currentUserId` has NOT reacted (`!iReacted`) -> 100% UNCOLORED neutral outline button!
        //    - If `currentUserId` HAS reacted (`iReacted`) -> ONLY `currentUserId`'s own reaction is colored!
        int pillBg = Ui.inputBg(c);
        int iconTint = Ui.txt(c);
        int neutralTint = Ui.sub(c);

        if (!iReacted) {
            vh.like.setBackground(Ui.round(c, pillBg, 20));
            vh.likeIcon.setVisibility(View.VISIBLE);
            vh.likeIcon.setImageResource(com.friendbook.social.R.drawable.ic_like_outline);
            vh.likeIcon.setColorFilter(neutralTint);
            vh.likeEmojiTv.setVisibility(View.GONE);
            vh.likeTxt.setText("Like");
            vh.likeTxt.setTextColor(neutralTint);
            vh.likeTxt.setTypeface(android.graphics.Typeface.DEFAULT);
            vh.likeTxt.setVisibility(View.VISIBLE);
        } else if ("like".equals(mine)) {
            vh.like.setBackground(Ui.roundStroke(c, 0x1A1877F2, 20, 0x551877F2, 1));
            vh.likeIcon.setVisibility(View.VISIBLE);
            vh.likeIcon.setImageResource(com.friendbook.social.R.drawable.ic_like_filled);
            vh.likeIcon.clearColorFilter(); // Pure solid #1877F2 blue vector thumb!
            vh.likeEmojiTv.setVisibility(View.GONE);
            vh.likeTxt.setText("Like");
            vh.likeTxt.setTextColor(Ui.BLUE);
            vh.likeTxt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            vh.likeTxt.setVisibility(View.VISIBLE);
        } else {
            // Current user's own non-like reaction (❤️ Love, 😂 Haha, 😮 Wow, 😢 Sad, 😡 Angry)
            int rxColor = Reactions.colorFor(mine);
            int tintBg = (rxColor & 0x00FFFFFF) | 0x1A000000;
            int tintStroke = (rxColor & 0x00FFFFFF) | 0x55000000;
            vh.like.setBackground(Ui.roundStroke(c, tintBg, 20, tintStroke, 1));
            vh.likeIcon.setVisibility(View.GONE);
            vh.likeEmojiTv.setText(Reactions.emojiFor(mine));
            vh.likeEmojiTv.setVisibility(View.VISIBLE);
            vh.likeTxt.setText(Reactions.labelFor(mine));
            vh.likeTxt.setTextColor(rxColor);
            vh.likeTxt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            vh.likeTxt.setVisibility(View.VISIBLE);
        }

        // Pill 2: COMMENT ([Hollow Bubble] + count when > 0)
        vh.comment.setBackground(Ui.round(c, pillBg, 20));
        vh.commentIcon.setColorFilter(iconTint);
        if (p.comments > 0) {
            vh.commentTxt.setText(Format.count(p.comments));
            vh.commentTxt.setTextColor(iconTint);
            vh.commentTxt.setVisibility(View.VISIBLE);
        } else {
            vh.commentTxt.setText("");
            vh.commentTxt.setVisibility(View.GONE);
        }

        // Pill 3: SHARE ([Hollow Curved Arrow] + count when > 0)
        vh.share.setBackground(Ui.round(c, pillBg, 20));
        vh.shareIcon.setColorFilter(iconTint);
        if (p.shares > 0) {
            vh.shareTxt.setText(Format.count(p.shares));
            vh.shareTxt.setTextColor(iconTint);
            vh.shareTxt.setVisibility(View.VISIBLE);
        } else {
            vh.shareTxt.setText("");
            vh.shareTxt.setVisibility(View.GONE);
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
        boolean isAdmin = com.friendbook.social.core.Admin.isAdmin();
        boolean isModerator = !isAdmin && com.friendbook.social.core.Admin.isModerator();
        PopupMenu menu = new PopupMenu(c, anchor);
        menu.getMenu().add(saved ? "🔖 Unsave post" : "🔖 Save post");
        boolean subscribed = p.subscribedBy != null && Fb.uid() != null && p.subscribedBy.contains(Fb.uid());
        menu.getMenu().add(subscribed ? "🔕 Turn off notifications for this post" : "🔔 Turn on notifications for this post");
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
        if (isAdmin && !mine) {
            menu.getMenu().add("🛡 🗑 Delete post (admin)");
        } else if (isModerator && !mine) {
            menu.getMenu().add("🛡 🗑 Delete post (moderator)");
            menu.getMenu().add("🛡 🚫 Ban user (moderator)");
        }
        if (p.content != null && !p.content.isEmpty()) {
            menu.getMenu().add("🔊 Read post aloud");
            menu.getMenu().add("🌐 Translate post");
        }
        menu.getMenu().add("📋 Copy text");
        menu.getMenu().add("🔗 Copy link");
        menu.getMenu().add("📤 Share to other apps");
        menu.getMenu().add("👍 See who reacted");
        menu.getMenu().add("📊 View post insights");
        if (p.authorPhoto != null && !p.authorPhoto.isEmpty()) {
            menu.getMenu().add("👁 View full profile picture");
        }
        menu.getMenu().add("📖 Add to your story");
        if (!mine) menu.getMenu().add("❓ Why am I seeing this?");
        if (!mine) menu.getMenu().add("🚩 Report post");
        menu.setOnMenuItemClickListener(item -> {
            String t = item.getTitle().toString();
            if (t.contains("See who reacted")) Reactions.showWhoReacted(activity, p.id);
            else if (t.contains("View post insights")) {
                int rxCount = com.friendbook.social.core.FastCache.getReactionTotal(p.id, p.likes);
                int cmCount = Math.max(p.comments, p.inlineComments != null ? p.inlineComments.size() : 0);
                int reach = Math.max(12, rxCount * 11 + cmCount * 14 + p.shares * 22 + 18);
                new android.app.AlertDialog.Builder(c)
                        .setTitle("📊 Post Insights")
                        .setMessage("👁 Estimated Reach: " + Format.count(reach) + " people\n\n"
                                + "👍 Total Reactions: " + rxCount + "\n"
                                + "💬 Total Comments: " + cmCount + "\n"
                                + "↗ Total Shares: " + p.shares + "\n"
                                + "🔖 Total Saves: " + (p.savedBy != null ? p.savedBy.size() : 0))
                        .setPositiveButton("Who Reacted", (d2, w2) -> Reactions.showWhoReacted(activity, p.id))
                        .setNeutralButton("All Comments", (d2, w2) -> { if (host != null) host.openComments(p); })
                        .setNegativeButton("Close", null)
                        .show();
            }
            else if (t.contains("View full profile picture")) {
                Intent fullDp = new Intent(c, FullscreenMediaActivity.class);
                fullDp.putExtra("type", "image");
                fullDp.putExtra("url", p.authorPhoto);
                fullDp.putExtra("title", p.authorName + "'s Profile Picture");
                c.startActivity(fullDp);
            }
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
            else if (t.contains("Delete post (moderator)")) {
                Fb.getUser(p.authorId).addOnSuccessListener(u -> {
                    if (com.friendbook.social.core.Admin.isProtectedStaff(u)) {
                        Ui.toast(activity, "Admin posts cannot be removed by a moderator");
                        return;
                    }
                    deletePost(p);
                });
            }
            else if (t.contains("Ban user (moderator)")) {
                Fb.getUser(p.authorId).addOnSuccessListener(u -> {
                    if (com.friendbook.social.core.Admin.isProtectedStaff(u)) {
                        Ui.toast(activity, "Admin accounts cannot be banned");
                        return;
                    }
                    Ui.confirm(activity, "🛡 Ban " + p.authorName + "?",
                            "This user will be banned from using friendbook.",
                            "Ban User", () -> Fb.db().collection("users").document(p.authorId)
                                    .set(Fb.map("banned", true), com.google.firebase.firestore.SetOptions.merge())
                                    .addOnSuccessListener(x -> {
                                        Notify.sendOfficialTeamMessage(p.authorId, "ban",
                                                "Your account was suspended by a friendbook Moderator.", false);
                                        Ui.toast(activity, "Banned " + p.authorName + " 🚫");
                                    })
                                    .addOnFailureListener(e -> Ui.toast(activity, e.getMessage())));
                });
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
            com.friendbook.social.core.QueryActions.deleteMatching(Fb.db().collection("comments").whereEqualTo("postId",p.id))
                    .addOnFailureListener(e->Ui.toast(activity,"Post removed; some old comments could not be cleaned up."));
            com.friendbook.social.core.QueryActions.deleteMatching(Fb.db().collection("posts").document(p.id).collection("reactions"))
                    .addOnFailureListener(e->Ui.toast(activity,"Some reactions could not be cleaned up."));
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
        Notify.follow(uid, true);
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
        final String[] reasons = {
                "🔞 Nudity or explicit sexual content",
                "🚨 Child safety / CSAE violation (Zero Tolerance)",
                "🩸 Graphic violence, gore or self-harm",
                "😡 Hate speech, bullying or harassment",
                "🎭 Spam, scam or impersonation"
        };
        Ui.choose(activity, "🚩 Report post", reasons, w -> {
            String reason = reasons[w];
            Map<String, Object> m = Fb.map(
                    "postId", p.id,
                    "type", "post",
                    "reason", reason,
                    "reportedBy", Fb.uid(),
                    "authorId", p.authorId,
                    "createdAt", FieldValue.serverTimestamp());
            Fb.db().collection("reports").add(m);
            Prefs.hidePost(activity, p.id);
            removePostById(p.id);
            com.friendbook.social.core.FastCache.removeFeedPost(activity, p.id);
            if (w == 0 || w == 1 || w == 2) {
                // Severe safety violation -> flag & auto-delete violating post
                com.friendbook.social.core.ContentSafetyEngine.autoDeleteContent(
                        activity, "posts", p.id, p.authorId,
                        w == 1 ? "csae" : (w == 0 ? "adult_nudity" : "gore_violence"),
                        reason, null);
                Ui.toast(activity, "🛡 Violating post removed & reported ✓");
            } else {
                Ui.toast(activity, "Post hidden & reported to moderation team ✓");
            }
        });
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
        final TextView moreStories;
        final LinearLayout liveBox, pymkBox, liveCards, pymkCards;
        final TextView goLiveBtn;

        HeaderVH(Context c) {
            super(Ui.col(c));
            LinearLayout col = (LinearLayout) itemView;
            col.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));

            // 1. Authentic friendbook Composer Row FIRST (Avatar+Green Dot · "What's on your mind?" · Photo)
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
            composeHint.setSingleLine(true);
            composeHint.setEllipsize(android.text.TextUtils.TruncateAt.END);
            composeHint.setBackground(Ui.round(c, Ui.inputBg(c), 22));
            composeHint.setPadding(Ui.dp(c, 16), Ui.dp(c, 11), Ui.dp(c, 16), Ui.dp(c, 11));
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, -2, 1);
            hp.leftMargin = Ui.dp(c, 10);
            hp.rightMargin = Ui.dp(c, 8);
            row.addView(composeHint, hp);

            // Big, bold friendbook-style Stacked Photo/Video gallery button -> opens Photos + Videos!
            LinearLayout photoCol = Ui.col(c);
            photoCol.setGravity(Gravity.CENTER);
            photoCol.setMinimumWidth(Ui.dp(c, 50));
            Ui.pad(photoCol, 6, 4, 4, 4);
            ImageView photo = new ImageView(c);
            photo.setImageResource(com.friendbook.social.R.drawable.ic_fb_photo_video);
            int pvSize = Ui.dp(c, 34);
            photoCol.addView(photo, new LinearLayout.LayoutParams(pvSize, pvSize));
            TextView photoLbl = Ui.tvBold(c, "Photo", 11, Ui.txt(c));
            photoCol.addView(photoLbl);
            photoCol.setOnClickListener(v -> {
                Intent i = new Intent(c, CreatePostActivity.class);
                i.putExtra("quick_mode", "photo_video");
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
            moreStories=Ui.tvBold(c,"More stories ›",13,Ui.BLUE);
            Ui.pad(moreStories,12,8,12,8); col.addView(moreStories);
            rail.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override public void onScrolled(RecyclerView view,int dx,int dy) {
                    LinearLayoutManager manager=(LinearLayoutManager)view.getLayoutManager();
                    if(dx>0 && manager!=null && manager.findLastVisibleItemPosition()>=storiesAdapter.getItemCount()-2
                            && host!=null && host.hasMoreStories()) host.loadMoreStories();
                }
            });

            View gap = new View(c);
            gap.setBackgroundColor(Ui.bg(c));
            col.addView(gap, new LinearLayout.LayoutParams(-1, Ui.dp(c, 6)));

            // 🔴 GO LIVE + 🎬 UPLOAD REEL + Live Hub strip (friendbook style)
            liveBox = Ui.col(c);
            liveBox.setBackgroundColor(Ui.card(c));
            LinearLayout liveTop = Ui.row(c);
            liveTop.setGravity(Gravity.CENTER_VERTICAL);
            Ui.pad(liveTop, 12, 8, 12, 6);
            goLiveBtn = Ui.roundChip(c, "🔴 Go Live", 0xFFE41E3F, 0xFFFFFFFF, 12);
            Ui.pad(goLiveBtn, 12, 7, 12, 7);
            liveTop.addView(goLiveBtn);

            TextView uploadReelChip = Ui.roundChip(c, "🎬 + Upload Reel", Ui.BLUE, 0xFFFFFFFF, 12);
            Ui.pad(uploadReelChip, 12, 7, 12, 7);
            LinearLayout.LayoutParams urlp = new LinearLayout.LayoutParams(-2, -2);
            urlp.leftMargin = Ui.dp(c, 8);
            uploadReelChip.setOnClickListener(v -> c.startActivity(
                    new Intent(c, com.friendbook.social.reels.AddReelActivity.class)));
            liveTop.addView(uploadReelChip, urlp);

            liveTop.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
            TextView hubBtn = Ui.roundChip(c, "📺 Live Hub ›", Ui.inputBg(c), Ui.BLUE, 11);
            Ui.pad(hubBtn, 10, 6, 10, 6);
            hubBtn.setOnClickListener(v -> c.startActivity(
                    new Intent(c, com.friendbook.social.live.LiveHubActivity.class)));
            liveTop.addView(hubBtn);
            liveBox.addView(liveTop);
            android.widget.HorizontalScrollView lsv = new android.widget.HorizontalScrollView(c);
            lsv.setHorizontalScrollBarEnabled(false);
            liveCards = Ui.row(c);
            Ui.pad(liveCards, 8, 0, 8, 8);
            lsv.addView(liveCards);
            liveBox.addView(lsv);
            col.addView(liveBox);

            // 👥 People You May Know strip (friendbook Home suggestion rail)
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

    public void pagingChanged(){if(getItemCount()>0)notifyItemChanged(getItemCount()-1,"paging");}

    class FooterVH extends RecyclerView.ViewHolder {
        final TextView more, end;
        final android.widget.ProgressBar spinner;
        FooterVH(Context c) {
            super(Ui.col(c));
            LinearLayout col = (LinearLayout) itemView;
            col.setGravity(Gravity.CENTER);
            Ui.pad(col, 16, 12, 16, 16);
            col.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            spinner=new android.widget.ProgressBar(c);
            spinner.setContentDescription("Loading more posts");
            col.addView(spinner,new LinearLayout.LayoutParams(Ui.dp(c,28),Ui.dp(c,28)));
            more = Ui.btnGrey(c, "Load more posts");
            col.addView(more, new LinearLayout.LayoutParams(-1, Ui.dp(c, 42)));
            end = Ui.tv(c, "You're all caught up 🎉", 13, Ui.sub(c));
            end.setGravity(Gravity.CENTER);
            Ui.pad(end, 0, 10, 0, 0);
            col.addView(end, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    /**
     * 📢 Google AdMob Production Feed Native ViewHolder (`R.layout.native_ad_layout`):
     * - Uses ONLY real production Feed Native ID (`ca-app-pub-3387480177023669/4957447582`).
     * - Loads a FRESH NativeAd via `AdLoader.Builder` on every bind (destroying any previous ad,
     *   never reusing an old or failed ad).
     * - Attaches `AdListener` and prints exact `adError.getCode()` + details in Logcat on failure.
     */
    static class AdVH extends RecyclerView.ViewHolder {

        final com.google.android.gms.ads.nativead.NativeAdView nativeAdView;
        final ImageView iconView;
        final TextView headlineView, advertiserView, bodyView, storeView, priceView, badgeView;
        final android.widget.RatingBar starsView;
        final com.google.android.gms.ads.nativead.MediaView mediaView;
        final android.widget.Button ctaButton;
        final LinearLayout ctaBar;
        final LinearLayout adShowcaseBanner, adInlineCommentsCol;
        final TextView adBannerBadgeTv, adBannerTitleTv, adBannerSubTv;
        final LinearLayout summaryRow, reactSummaryWrap, like, comment, share;
        final ImageView reactBadgeIv, likeIcon, commentIcon, shareIcon;
        final TextView extraReactsTv, tagSummary, commentsSummary, likeEmojiTv, likeTxt, commentTxt, shareTxt;
        com.google.android.gms.ads.nativead.NativeAd currentNativeAd;
        com.google.android.gms.ads.nativead.NativeAd boundNativeAdViewTarget;
        int requestSeq = 0;
        int boundSlotOrdinal = -1;
        boolean adRequestInFlight = false;
        boolean inDetailScreen = false;

        AdVH(ViewGroup parent) {
            super(android.view.LayoutInflater.from(parent.getContext())
                    .inflate(com.friendbook.social.R.layout.native_ad_layout, parent, false));
            final Context c = parent.getContext();
            nativeAdView = (com.google.android.gms.ads.nativead.NativeAdView) itemView;
            nativeAdView.setBackground(Ui.roundStroke(c, Ui.card(c), 10, Ui.div(c), 1));

            iconView = nativeAdView.findViewById(com.friendbook.social.R.id.ad_app_icon);
            headlineView = nativeAdView.findViewById(com.friendbook.social.R.id.ad_headline);
            advertiserView = nativeAdView.findViewById(com.friendbook.social.R.id.ad_advertiser);
            badgeView = nativeAdView.findViewById(com.friendbook.social.R.id.ad_badge);
            starsView = nativeAdView.findViewById(com.friendbook.social.R.id.ad_stars);
            bodyView = nativeAdView.findViewById(com.friendbook.social.R.id.ad_body);
            mediaView = nativeAdView.findViewById(com.friendbook.social.R.id.ad_media);
            storeView = nativeAdView.findViewById(com.friendbook.social.R.id.ad_store);
            priceView = nativeAdView.findViewById(com.friendbook.social.R.id.ad_price);
            ctaButton = nativeAdView.findViewById(com.friendbook.social.R.id.ad_call_to_action);
            ctaBar = nativeAdView.findViewById(com.friendbook.social.R.id.native_ad_cta_bar);

            // Apply theme colors (Light / Dark mode friendly)
            headlineView.setTextColor(Ui.txt(c));
            bodyView.setTextColor(Ui.txt(c));
            advertiserView.setTextColor(Ui.sub(c));
            storeView.setTextColor(Ui.sub(c));
            priceView.setTextColor(Ui.sub(c));
            badgeView.setBackground(Ui.round(c, 0xFFFFF3CD, 4));
            badgeView.setTextColor(0xFF7A5200);
            ctaBar.setBackgroundColor(Ui.inputBg(c));
            ctaButton.setBackground(Ui.round(c, Ui.BLUE, 8));
            mediaView.setImageScaleType(ImageView.ScaleType.CENTER_CROP);

            LinearLayout cardInner = nativeAdView.findViewById(com.friendbook.social.R.id.native_ad_card_inner);

            // Vibrant Unseen-Ad Visual Banner
            adShowcaseBanner = Ui.col(c);
            adShowcaseBanner.setGravity(Gravity.CENTER_VERTICAL);
            Ui.pad(adShowcaseBanner, 18, 22, 18, 22);
            adBannerBadgeTv = Ui.roundChip(c, "SPONSORED", 0x33FFFFFF, 0xFFFFFFFF, 10.5f);
            Ui.pad(adBannerBadgeTv, 10, 4, 10, 4);
            adShowcaseBanner.addView(adBannerBadgeTv, new LinearLayout.LayoutParams(-2, -2));

            adBannerTitleTv = Ui.tvBold(c, "", 20f, 0xFFFFFFFF);
            adBannerTitleTv.setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 4));
            adShowcaseBanner.addView(adBannerTitleTv);

            adBannerSubTv = Ui.tv(c, "", 13f, 0xEEFFFFFF);
            adShowcaseBanner.addView(adBannerSubTv);
            adShowcaseBanner.setVisibility(View.GONE);

            // ---- 100% Identical to PostVH: Clickable Summary Row ([🔵👍 / ❤️😂] "You and X others" | "X comments") ----
            summaryRow = Ui.row(c);
            summaryRow.setGravity(Gravity.CENTER_VERTICAL);
            summaryRow.setMinimumHeight(Ui.dp(c, 32));
            Ui.pad(summaryRow, 12, 6, 12, 4);
            summaryRow.setVisibility(View.GONE);

            reactSummaryWrap = Ui.row(c);
            reactSummaryWrap.setGravity(Gravity.CENTER_VERTICAL);
            reactSummaryWrap.setMinimumHeight(Ui.dp(c, 22));
            reactSummaryWrap.setPadding(0, 0, Ui.dp(c, 12), 0);

            reactBadgeIv = new ImageView(c);
            reactBadgeIv.setImageResource(com.friendbook.social.R.drawable.ic_reaction_like_badge);
            int badgeSz = Ui.dp(c, 18);
            reactSummaryWrap.addView(reactBadgeIv, new LinearLayout.LayoutParams(badgeSz, badgeSz));

            extraReactsTv = Ui.tv(c, "", 13.5f, Ui.txt(c));
            extraReactsTv.setIncludeFontPadding(false);
            extraReactsTv.setGravity(Gravity.CENTER_VERTICAL);
            extraReactsTv.setPadding(Ui.dp(c, 2), 0, 0, 0);
            extraReactsTv.setVisibility(View.GONE);
            reactSummaryWrap.addView(extraReactsTv, new LinearLayout.LayoutParams(-2, Ui.dp(c, 20)));

            tagSummary = Ui.tv(c, "", 13.5f, Ui.sub(c));
            tagSummary.setIncludeFontPadding(false);
            tagSummary.setGravity(Gravity.CENTER_VERTICAL);
            tagSummary.setPadding(Ui.dp(c, 6), 0, 0, 0);
            reactSummaryWrap.addView(tagSummary);

            summaryRow.addView(reactSummaryWrap);
            summaryRow.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1));
            commentsSummary = Ui.tv(c, "", 13.5f, Ui.sub(c));
            commentsSummary.setIncludeFontPadding(false);
            commentsSummary.setPadding(Ui.dp(c, 8), 0, 0, 0);
            summaryRow.addView(commentsSummary);

            // ---- 100% Identical to PostVH: 3 Rounded Pill Capsule Buttons (Like · Comment · Share) ----
            LinearLayout actions = Ui.row(c);
            Ui.pad(actions, 10, 6, 10, 10);

            like = Ui.row(c);
            like.setGravity(Gravity.CENTER);
            like.setBackground(Ui.round(c, Ui.inputBg(c), 20));
            like.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 10), 0);
            likeIcon = new ImageView(c);
            likeIcon.setImageResource(com.friendbook.social.R.drawable.ic_like_outline);
            like.addView(likeIcon, new LinearLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20)));
            likeEmojiTv = Ui.tv(c, "", 15.5f, Ui.txt(c));
            likeEmojiTv.setIncludeFontPadding(false);
            likeEmojiTv.setGravity(Gravity.CENTER);
            likeEmojiTv.setVisibility(View.GONE);
            like.addView(likeEmojiTv, new LinearLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20)));
            likeTxt = Ui.tv(c, "Like", 13.5f, Ui.sub(c));
            likeTxt.setIncludeFontPadding(false);
            likeTxt.setPadding(Ui.dp(c, 6), 0, 0, 0);
            like.addView(likeTxt);

            comment = Ui.row(c);
            comment.setGravity(Gravity.CENTER);
            comment.setBackground(Ui.round(c, Ui.inputBg(c), 20));
            comment.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 10), 0);
            commentIcon = new ImageView(c);
            commentIcon.setImageResource(com.friendbook.social.R.drawable.ic_comment_bubble_outline);
            comment.addView(commentIcon, new LinearLayout.LayoutParams(Ui.dp(c, 19), Ui.dp(c, 19)));
            commentTxt = Ui.tv(c, "", 13.5f, Ui.txt(c));
            commentTxt.setPadding(Ui.dp(c, 6), 0, 0, 0);
            commentTxt.setVisibility(View.GONE);
            comment.addView(commentTxt);

            share = Ui.row(c);
            share.setGravity(Gravity.CENTER);
            share.setBackground(Ui.round(c, Ui.inputBg(c), 20));
            share.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 10), 0);
            shareIcon = new ImageView(c);
            shareIcon.setImageResource(com.friendbook.social.R.drawable.ic_share_curved_outline);
            share.addView(shareIcon, new LinearLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20)));
            shareTxt = Ui.tv(c, "", 13.5f, Ui.txt(c));
            shareTxt.setPadding(Ui.dp(c, 6), 0, 0, 0);
            shareTxt.setVisibility(View.GONE);
            share.addView(shareTxt);

            int pillH = Ui.dp(c, 38);
            LinearLayout.LayoutParams a1 = new LinearLayout.LayoutParams(0, pillH, 1);
            a1.rightMargin = Ui.dp(c, 8);
            LinearLayout.LayoutParams a2 = new LinearLayout.LayoutParams(0, pillH, 1);
            a2.rightMargin = Ui.dp(c, 8);
            LinearLayout.LayoutParams a3 = new LinearLayout.LayoutParams(0, pillH, 1);
            actions.addView(like, a1);
            actions.addView(comment, a2);
            actions.addView(share, a3);

            // ---- 100% Identical to PostVH: Inline Comments + Circular Avatar + "Write a comment…" Bar ----
            adInlineCommentsCol = Ui.col(c);
            adInlineCommentsCol.setBackgroundColor(Ui.card(c));

            if (cardInner != null) {
                int ctaIndex = cardInner.indexOfChild(ctaBar);
                if (ctaIndex >= 0) {
                    cardInner.addView(adShowcaseBanner, ctaIndex, new LinearLayout.LayoutParams(-1, Ui.dp(c, 168)));
                } else {
                    cardInner.addView(adShowcaseBanner, new LinearLayout.LayoutParams(-1, Ui.dp(c, 168)));
                }
                cardInner.addView(summaryRow);
                cardInner.addView(actions);
                cardInner.addView(adInlineCommentsCol);
            }

            like.setOnClickListener(v -> {
                Ui.haptic(v);
                View popTarget = likeIcon.getVisibility() == View.VISIBLE ? likeIcon : likeEmojiTv;
                popTarget.setScaleX(0.78f);
                popTarget.setScaleY(0.78f);
                popTarget.animate().scaleX(1.16f).scaleY(1.16f).setDuration(110L)
                        .withEndAction(() -> popTarget.animate().scaleX(1f).scaleY(1f).setDuration(90L).start())
                        .start();
                com.friendbook.social.core.AdsConfig.setOrToggleAdReaction(
                        c, boundSlotOrdinal, null, () -> refreshAdSocialUi(c));
            });
            like.setOnLongClickListener(v -> {
                Reactions.show(like, picked ->
                        com.friendbook.social.core.AdsConfig.setOrToggleAdReaction(
                                c, boundSlotOrdinal, picked, () -> refreshAdSocialUi(c)));
                return true;
            });
            View.OnClickListener openCmts = v -> {
                if (inDetailScreen) return;
                com.friendbook.social.core.AdsConfig.openFeedAdPostDetail(
                        c, boundSlotOrdinal, () -> refreshAdSocialUi(c));
            };
            comment.setOnClickListener(openCmts);
            commentsSummary.setOnClickListener(openCmts);
            reactSummaryWrap.setOnClickListener(v ->
                    com.friendbook.social.core.AdsConfig.showWhoReactedToAd(c, boundSlotOrdinal));
            share.setOnClickListener(v ->
                    com.friendbook.social.core.AdsConfig.shareSponsoredAd(
                            c, boundSlotOrdinal, headlineView.getText(), bodyView.getText(),
                            () -> refreshAdSocialUi(c)));

            // Register all clickable NativeAdView asset views
            nativeAdView.setHeadlineView(headlineView);
            nativeAdView.setBodyView(bodyView);
            nativeAdView.setCallToActionView(ctaButton);
            nativeAdView.setIconView(iconView);
            nativeAdView.setPriceView(priceView);
            nativeAdView.setStarRatingView(starsView);
            nativeAdView.setStoreView(storeView);
            nativeAdView.setAdvertiserView(advertiserView);
            nativeAdView.setMediaView(mediaView);
        }

        private void refreshAdSocialUi(Context c) {
            com.friendbook.social.core.AdsConfig.resolveUniqueAdId(c, boundSlotOrdinal);
            Models.Post adPost = com.friendbook.social.core.AdsConfig.getOrCreateAdPost(boundSlotOrdinal);
            String mine = com.friendbook.social.core.AdsConfig.getAdMyReaction(c, boundSlotOrdinal);
            if (Fb.uid() == null || Fb.uid().isEmpty()) mine = "";
            boolean iReacted = mine != null && !mine.isEmpty();
            int total = com.friendbook.social.core.AdsConfig.getAdLikeCount(c, boundSlotOrdinal);
            int effectiveTotal = iReacted ? Math.max(1, total) : Math.max(0, total);
            int cmts = com.friendbook.social.core.AdsConfig.getAdCommentCount(c, boundSlotOrdinal);
            int shares = com.friendbook.social.core.AdsConfig.getAdShareCount(c, boundSlotOrdinal);

            // 1. Reaction Summary Row (100% identical to PostVH.renderReactionBar!)
            if (effectiveTotal > 0) {
                List<String> activeTypes = com.friendbook.social.core.FastCache.getReactionTypes(adPost.id);
                if (activeTypes.isEmpty() && adPost.reactionsMap != null && !adPost.reactionsMap.isEmpty()) {
                    activeTypes = Reactions.computeTopTypesFromMap(adPost.reactionsMap);
                }
                List<String> displayTypes = new ArrayList<>(activeTypes);
                if (iReacted && !displayTypes.contains(mine)) displayTypes.add(0, mine);
                if (displayTypes.isEmpty()) displayTypes.add(iReacted ? mine : "like");

                boolean showThumbBadge = displayTypes.contains("like");
                StringBuilder extraEmojis = new StringBuilder();
                int emojiCount = showThumbBadge ? 1 : 0;
                for (String rType : displayTypes) {
                    if ("like".equals(rType)) continue;
                    if (emojiCount >= 3) break;
                    extraEmojis.append(Reactions.emojiFor(rType));
                    emojiCount++;
                }
                reactBadgeIv.setVisibility(showThumbBadge ? View.VISIBLE : View.GONE);
                if (extraEmojis.length() > 0) {
                    extraReactsTv.setText(extraEmojis.toString());
                    extraReactsTv.setPadding(showThumbBadge ? Ui.dp(c, 2) : 0, 0, 0, 0);
                    extraReactsTv.setVisibility(View.VISIBLE);
                } else {
                    extraReactsTv.setText("");
                    extraReactsTv.setVisibility(View.GONE);
                }

                String summaryLabel;
                if (iReacted) {
                    if (effectiveTotal == 1) summaryLabel = "You";
                    else if (effectiveTotal == 2) summaryLabel = "You and 1 other";
                    else summaryLabel = "You and " + Format.count(effectiveTotal - 1) + " others";
                } else {
                    summaryLabel = Format.count(effectiveTotal);
                }
                tagSummary.setText(summaryLabel);
                tagSummary.setVisibility(View.VISIBLE);
                reactSummaryWrap.setVisibility(View.VISIBLE);
            } else {
                reactSummaryWrap.setVisibility(View.GONE);
            }

            StringBuilder right = new StringBuilder();
            if (cmts > 0) right.append(Format.count(cmts)).append(cmts == 1 ? " comment" : " comments");
            if (shares > 0) {
                if (right.length() > 0) right.append(" · ");
                right.append(Format.count(shares)).append(shares == 1 ? " share" : " shares");
            }
            commentsSummary.setText(right);
            commentsSummary.setVisibility(right.length() > 0 ? View.VISIBLE : View.GONE);
            summaryRow.setVisibility((effectiveTotal > 0 || cmts > 0 || shares > 0) ? View.VISIBLE : View.GONE);

            // 2. Three Rounded Pill Capsule Buttons (100% identical to PostVH!)
            int pillBg = Ui.inputBg(c);
            int iconTint = Ui.txt(c);
            int neutralTint = Ui.sub(c);

            if (!iReacted) {
                like.setBackground(Ui.round(c, pillBg, 20));
                likeIcon.setVisibility(View.VISIBLE);
                likeIcon.setImageResource(com.friendbook.social.R.drawable.ic_like_outline);
                likeIcon.setColorFilter(neutralTint);
                likeEmojiTv.setVisibility(View.GONE);
                likeTxt.setText("Like");
                likeTxt.setTextColor(neutralTint);
                likeTxt.setTypeface(android.graphics.Typeface.DEFAULT);
                likeTxt.setVisibility(View.VISIBLE);
            } else if ("like".equals(mine)) {
                like.setBackground(Ui.roundStroke(c, 0x1A1877F2, 20, 0x551877F2, 1));
                likeIcon.setVisibility(View.VISIBLE);
                likeIcon.setImageResource(com.friendbook.social.R.drawable.ic_like_filled);
                likeIcon.clearColorFilter();
                likeEmojiTv.setVisibility(View.GONE);
                likeTxt.setText("Like");
                likeTxt.setTextColor(Ui.BLUE);
                likeTxt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                likeTxt.setVisibility(View.VISIBLE);
            } else {
                int rxColor = Reactions.colorFor(mine);
                int tintBg = (rxColor & 0x00FFFFFF) | 0x1A000000;
                int tintStroke = (rxColor & 0x00FFFFFF) | 0x55000000;
                like.setBackground(Ui.roundStroke(c, tintBg, 20, tintStroke, 1));
                likeIcon.setVisibility(View.GONE);
                likeEmojiTv.setText(Reactions.emojiFor(mine));
                likeEmojiTv.setVisibility(View.VISIBLE);
                likeTxt.setText(Reactions.labelFor(mine));
                likeTxt.setTextColor(rxColor);
                likeTxt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                likeTxt.setVisibility(View.VISIBLE);
            }

            comment.setBackground(Ui.round(c, pillBg, 20));
            commentIcon.setColorFilter(iconTint);
            if (cmts > 0) {
                commentTxt.setText(Format.count(cmts));
                commentTxt.setTextColor(iconTint);
                commentTxt.setVisibility(View.VISIBLE);
            } else {
                commentTxt.setText("");
                commentTxt.setVisibility(View.GONE);
            }

            share.setBackground(Ui.round(c, pillBg, 20));
            shareIcon.setColorFilter(iconTint);
            if (shares > 0) {
                shareTxt.setText(Format.count(shares));
                shareTxt.setTextColor(iconTint);
                shareTxt.setVisibility(View.VISIBLE);
            } else {
                shareTxt.setText("");
                shareTxt.setVisibility(View.GONE);
            }

            // 3. Inline Comments + Circular Avatar + "Write a comment…" Bar (100% identical to PostVH!)
            adInlineCommentsCol.removeAllViews();
            if (inDetailScreen) {
                adInlineCommentsCol.setVisibility(View.GONE);
                return;
            }
            adInlineCommentsCol.setVisibility(View.VISIBLE);
            adInlineCommentsCol.addView(Ui.divider(c));

            List<Models.Comment> list = adPost.inlineComments != null ? adPost.inlineComments : new ArrayList<>();
            if (cmts > 2 || (cmts > 0 && list.isEmpty())) {
                TextView viewAll = Ui.tvBold(c, "View all " + cmts + (cmts == 1 ? " comment…" : " comments…"), 13, Ui.sub(c));
                Ui.pad(viewAll, 12, 8, 12, 4);
                viewAll.setOnClickListener(v -> com.friendbook.social.core.AdsConfig.openFeedAdPostDetail(
                        c, boundSlotOrdinal, () -> refreshAdSocialUi(c)));
                adInlineCommentsCol.addView(viewAll);
            }

            int startIdx = Math.max(0, list.size() - 2);
            for (int i = startIdx; i < list.size(); i++) {
                final Models.Comment cm = list.get(i);
                if (cm == null) continue;
                LinearLayout row = Ui.row(c);
                row.setGravity(Gravity.TOP);
                Ui.pad(row, 12, 5, 12, 4);

                ImageView av = new ImageView(c);
                int s = Ui.dp(c, 28);
                row.addView(av, new LinearLayout.LayoutParams(s, s));
                Models.User cachedU = com.friendbook.social.core.FastCache.getUser(cm.authorId);
                String uPhoto = (cachedU != null && cachedU.photoUrl != null && !cachedU.photoUrl.isEmpty())
                        ? cachedU.photoUrl : cm.authorPhoto;
                String uName = (cachedU != null && cachedU.displayName != null && !cachedU.displayName.isEmpty())
                        ? cachedU.displayName : (cm.authorName == null || cm.authorName.isEmpty() ? "friendbook User" : cm.authorName);
                Ui.avatar(c, av, uPhoto);

                LinearLayout bubble = Ui.col(c);
                bubble.setBackground(Ui.round(c, Ui.inputBg(c), 14));
                Ui.pad(bubble, 10, 6, 10, 6);
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
                bp.leftMargin = Ui.dp(c, 8);
                bubble.addView(Ui.tvBold(c, uName, 12.5f, Ui.txt(c)));
                String bodyTxt = cm.content != null && !cm.content.isEmpty()
                        ? cm.content : (cm.imageUrl != null && !cm.imageUrl.isEmpty() ? "📷 Sent a photo" : "");
                TextView bodyT = Ui.tv(c, bodyTxt, 13.5f, Ui.txt(c));
                bodyT.setText(com.friendbook.social.core.Mentions.styled(bodyTxt, cm.mentions));
                bodyT.setMaxLines(3);
                bubble.addView(bodyT);
                bubble.setOnClickListener(v -> com.friendbook.social.core.AdsConfig.openFeedAdPostDetail(
                        c, boundSlotOrdinal, () -> refreshAdSocialUi(c)));
                row.addView(bubble, bp);
                adInlineCommentsCol.addView(row);
            }

            LinearLayout quickRow = Ui.row(c);
            quickRow.setGravity(Gravity.CENTER_VERTICAL);
            Ui.pad(quickRow, 12, 6, 12, 9);
            ImageView myAv = new ImageView(c);
            int ms = Ui.dp(c, 26);
            quickRow.addView(myAv, new LinearLayout.LayoutParams(ms, ms));
            Ui.avatar(c, myAv, Fb.myPhoto());
            TextView hintPill = Ui.tv(c, "Write a comment…", 13, Ui.sub(c));
            hintPill.setBackground(Ui.round(c, Ui.inputBg(c), 18));
            Ui.pad(hintPill, 12, 6, 12, 6);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(0, -2, 1);
            hlp.leftMargin = Ui.dp(c, 8);
            quickRow.addView(hintPill, hlp);
            quickRow.setOnClickListener(v -> com.friendbook.social.core.AdsConfig.openFeedAdPostDetail(
                    c, boundSlotOrdinal, () -> refreshAdSocialUi(c)));
            adInlineCommentsCol.addView(quickRow);
        }

        private void populateShowcaseFallback(Context c, int slotOrdinal) {
            com.friendbook.social.core.AdsConfig.SponsoredCreative cr =
                    com.friendbook.social.core.AdsConfig.getCreativeForSlot(c, slotOrdinal);
            headlineView.setText(cr.headline);
            advertiserView.setText(cr.advertiser + " · Sponsored");
            advertiserView.setVisibility(View.VISIBLE);
            bodyView.setText(cr.body);
            bodyView.setVisibility(View.VISIBLE);
            ctaButton.setText(cr.cta);
            ctaButton.setVisibility(View.VISIBLE);
            iconView.setImageDrawable(com.friendbook.social.core.AdsConfig.createCompanyLogoDrawable(c, cr));
            iconView.setVisibility(View.VISIBLE);
            mediaView.setVisibility(View.GONE);

            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    new int[]{cr.bannerColorStart, cr.bannerColorEnd});
            gd.setCornerRadius(0f);
            adShowcaseBanner.setBackground(gd);
            adBannerBadgeTv.setText(cr.bannerBadge);
            adBannerTitleTv.setText(cr.bannerBigTitle);
            adBannerSubTv.setText(cr.bannerSubTitle);
            adShowcaseBanner.setVisibility(View.VISIBLE);

            starsView.setRating(cr.stars);
            starsView.setVisibility(View.VISIBLE);
            storeView.setText(cr.storeOrPrice);
            storeView.setVisibility(View.VISIBLE);
            priceView.setText("Verified Partner");
            priceView.setVisibility(View.VISIBLE);
        }

        private void destroyCurrentAd() {
            currentNativeAd = null;
            boundNativeAdViewTarget = null;
        }

        private void collapse() {
            itemView.setVisibility(View.GONE);
            ViewGroup.LayoutParams lp = itemView.getLayoutParams();
            if (lp != null) {
                lp.height = 0;
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                    mlp.topMargin = 0;
                    mlp.bottomMargin = 0;
                    mlp.leftMargin = 0;
                    mlp.rightMargin = 0;
                }
                itemView.setLayoutParams(lp);
            }
        }

        private void expand(Context c) {
            itemView.setVisibility(View.VISIBLE);
            ViewGroup.LayoutParams lp = itemView.getLayoutParams();
            if (lp != null) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                    // 14dp top & bottom buffer keeps safe policy distance from Like/Comment buttons
                    mlp.topMargin = Ui.dp(c, 14);
                    mlp.bottomMargin = Ui.dp(c, 14);
                    mlp.leftMargin = Ui.dp(c, 8);
                    mlp.rightMargin = Ui.dp(c, 8);
                }
                itemView.setLayoutParams(lp);
            }
        }

        void loadFreshFeedNativeAd(final String rawUnitId, final int slotOrdinal) {
            loadFreshFeedNativeAd(rawUnitId, slotOrdinal, false);
        }

        void loadFreshFeedNativeAd(final String rawUnitId, final int slotOrdinal, final boolean preserveExistingSlotAd) {
            this.inDetailScreen = preserveExistingSlotAd;
            final Context ctx = itemView.getContext();
            if (this.boundSlotOrdinal == slotOrdinal && currentNativeAd != null) {
                if (boundNativeAdViewTarget != currentNativeAd) {
                    expand(ctx);
                    populateNativeAdView(currentNativeAd, ctx);
                    refreshAdSocialUi(ctx);
                }
                return;
            }
            if (this.boundSlotOrdinal == slotOrdinal && adRequestInFlight) {
                com.google.android.gms.ads.nativead.NativeAd nowReady =
                        com.friendbook.social.core.AdsConfig.getOrPollFeedAd(ctx, slotOrdinal);
                if (nowReady != null) {
                    ++requestSeq;
                    adRequestInFlight = false;
                    currentNativeAd = nowReady;
                    expand(ctx);
                    populateNativeAdView(nowReady, ctx);
                    refreshAdSocialUi(ctx);
                }
                return;
            }

            final int mySeq = ++requestSeq;
            this.boundSlotOrdinal = slotOrdinal;
            this.adRequestInFlight = false;
            destroyCurrentAd();

            com.friendbook.social.core.AdsConfig.resolveUniqueAdId(ctx, slotOrdinal);
            com.friendbook.social.core.AdsConfig.hydrateAdSocialFromFirestore(ctx, slotOrdinal, () -> {
                if (mySeq == requestSeq && currentNativeAd != null) refreshAdSocialUi(ctx);
            });
            final String unitId = com.friendbook.social.core.AdsConfig.isRealProductionUnit(rawUnitId)
                    ? rawUnitId.trim()
                    : com.friendbook.social.core.AdsConfig.FEED_NATIVE_ID;

            com.friendbook.social.core.ConsentManager.ensureInitialized(ctx);
            com.google.android.gms.ads.nativead.NativeAd preloaded =
                    com.friendbook.social.core.AdsConfig.getOrPollFeedAd(ctx, slotOrdinal);
            if (preloaded != null) {
                currentNativeAd = preloaded;
                expand(ctx);
                populateNativeAdView(preloaded, ctx);
                refreshAdSocialUi(ctx);
                return;
            }

            // Keep collapsed until real AdMob NativeAd arrives (never show "Sponsored • Loading Ad..." box!)
            adRequestInFlight = true;
            collapse();

            com.friendbook.social.core.ConsentManager.runWhenInitialized(ctx, () -> {
                if (mySeq != requestSeq) return;
                com.google.android.gms.ads.nativead.NativeAd readyAfterInit =
                        com.friendbook.social.core.AdsConfig.getOrPollFeedAd(ctx, slotOrdinal);
                if (readyAfterInit != null) {
                    adRequestInFlight = false;
                    currentNativeAd = readyAfterInit;
                    expand(ctx);
                    populateNativeAdView(readyAfterInit, ctx);
                    refreshAdSocialUi(ctx);
                    return;
                }
                requestRealFeedAdFromUnit(ctx, unitId, slotOrdinal, mySeq, true);
            });
        }

        private void requestRealFeedAdFromUnit(
                final Context ctx,
                final String unitId,
                final int slotOrdinal,
                final int mySeq,
                final boolean allowBackupUnitRetry) {
            com.friendbook.social.core.AdsConfig.recordAdRequest(ctx);
            android.util.Log.i(com.friendbook.social.core.AdsConfig.LOG_TAG,
                    "[FeedNative] Requesting fresh NativeAd slot=" + slotOrdinal + " unitId=" + unitId);

            try {
                com.google.android.gms.ads.VideoOptions videoOptions =
                        new com.google.android.gms.ads.VideoOptions.Builder()
                                .setStartMuted(true)
                                .build();
                com.google.android.gms.ads.nativead.NativeAdOptions adOptions =
                        new com.google.android.gms.ads.nativead.NativeAdOptions.Builder()
                                .setAdChoicesPlacement(
                                        com.google.android.gms.ads.nativead.NativeAdOptions.ADCHOICES_TOP_RIGHT)
                                .setMediaAspectRatio(
                                        com.google.android.gms.ads.nativead.NativeAdOptions.NATIVE_MEDIA_ASPECT_RATIO_ANY)
                                .setVideoOptions(videoOptions)
                                .build();

                com.google.android.gms.ads.AdLoader adLoader =
                        new com.google.android.gms.ads.AdLoader.Builder(ctx, unitId)
                                .forNativeAd(nativeAd -> {
                                    com.friendbook.social.core.AdsConfig.onNativeAdLoaded(ctx);
                                    nativeAd.setOnPaidEventListener(adValue ->
                                            com.friendbook.social.core.AdsConfig.recordPaidEvent(ctx, adValue));
                                    com.friendbook.social.core.AdsConfig.putFeedSlotAd(slotOrdinal, nativeAd);
                                    if (mySeq != requestSeq || currentNativeAd != null) {
                                        return;
                                    }
                                    adRequestInFlight = false;
                                    currentNativeAd = nativeAd;

                                    // Expand BEFORE setNativeAd so Google Mobile Ads viewability registers impression
                                    expand(ctx);
                                    populateNativeAdView(nativeAd, ctx);
                                    refreshAdSocialUi(ctx);
                                })
                                .withAdListener(new com.google.android.gms.ads.AdListener() {
                                    @Override
                                    public void onAdLoaded() {
                                        android.util.Log.i(com.friendbook.social.core.AdsConfig.LOG_TAG,
                                                "[FeedNative] Ad loaded successfully (slot=" + slotOrdinal
                                                        + ", unitId=" + unitId + ")");
                                    }

                                    @Override
                                    public void onAdImpression() {
                                        android.util.Log.i(com.friendbook.social.core.AdsConfig.LOG_TAG,
                                                "[FeedNative] Ad impression recorded (slot=" + slotOrdinal
                                                        + ", unitId=" + unitId + ")");
                                        com.friendbook.social.core.AdsConfig.recordAdImpression(ctx);
                                    }

                                    @Override
                                    public void onAdClicked() {
                                        android.util.Log.i(com.friendbook.social.core.AdsConfig.LOG_TAG,
                                                "[FeedNative] Ad clicked (slot=" + slotOrdinal
                                                        + ", unitId=" + unitId + ")");
                                        com.friendbook.social.core.AdsConfig.recordAdClick(ctx);
                                    }

                                    @Override
                                    public void onAdFailedToLoad(@NonNull com.google.android.gms.ads.LoadAdError adError) {
                                        android.util.Log.e(com.friendbook.social.core.AdsConfig.LOG_TAG,
                                                "[FeedNative] Ad failed to load! errorCode=" + adError.getCode()
                                                        + ", message=" + adError.getMessage()
                                                        + ", domain=" + adError.getDomain()
                                                        + ", slot=" + slotOrdinal
                                                        + ", unitId=" + unitId
                                                        + ", responseInfo=" + adError.getResponseInfo());
                                        com.friendbook.social.core.AdsConfig.onNativeAdFailed(ctx, adError.getCode());
                                        if (mySeq == requestSeq) {
                                            com.google.android.gms.ads.nativead.NativeAd fallback =
                                                    com.friendbook.social.core.AdsConfig.getOrPollFeedAd(ctx, slotOrdinal);
                                            if (fallback != null) {
                                                adRequestInFlight = false;
                                                currentNativeAd = fallback;
                                                expand(ctx);
                                                populateNativeAdView(fallback, ctx);
                                                refreshAdSocialUi(ctx);
                                                nativeAdView.requestLayout();
                                            } else if (allowBackupUnitRetry) {
                                                String altUnit = com.friendbook.social.core.AdsConfig.FEED_NATIVE_ID.equals(unitId)
                                                        ? com.friendbook.social.core.AdsConfig.REELS_NATIVE_ID
                                                        : com.friendbook.social.core.AdsConfig.FEED_NATIVE_ID;
                                                requestRealFeedAdFromUnit(ctx, altUnit, slotOrdinal, mySeq, false);
                                            } else {
                                                adRequestInFlight = false;
                                                collapse();
                                            }
                                        }
                                    }
                                })
                                .withNativeAdOptions(adOptions)
                                .build();

                adLoader.loadAd(com.friendbook.social.core.AdsConfig.buildHighValueAdRequest(false));
            } catch (Throwable t) {
                adRequestInFlight = false;
                android.util.Log.e(com.friendbook.social.core.AdsConfig.LOG_TAG,
                        "[FeedNative] Exception loading ad (unitId=" + unitId + "): " + t.getMessage(), t);
                com.friendbook.social.core.AdsConfig.onNativeAdFailed(ctx, -1);
                collapse();
            }
        }

        private void populateNativeAdView(com.google.android.gms.ads.nativead.NativeAd ad, Context c) {
            if (boundNativeAdViewTarget == ad) return;
            boundNativeAdViewTarget = ad;
            adShowcaseBanner.setVisibility(View.GONE);
            headlineView.setText(ad.getHeadline() != null ? ad.getHeadline() : "Sponsored");

            if (ad.getMediaContent() != null) {
                mediaView.setMediaContent(ad.getMediaContent());
                mediaView.setVisibility(View.VISIBLE);
            } else {
                mediaView.setVisibility(View.GONE);
            }

            if (ad.getBody() == null || ad.getBody().isEmpty()) {
                bodyView.setVisibility(View.GONE);
            } else {
                bodyView.setVisibility(View.VISIBLE);
                bodyView.setText(ad.getBody());
            }

            if (ad.getCallToAction() == null || ad.getCallToAction().isEmpty()) {
                ctaButton.setVisibility(View.GONE);
            } else {
                ctaButton.setVisibility(View.VISIBLE);
                ctaButton.setText(ad.getCallToAction());
            }

            if (ad.getIcon() == null || ad.getIcon().getDrawable() == null) {
                iconView.setVisibility(View.GONE);
            } else {
                iconView.setImageDrawable(ad.getIcon().getDrawable());
                iconView.setClipToOutline(true);
                iconView.setBackground(Ui.circle(Ui.inputBg(c)));
                iconView.setVisibility(View.VISIBLE);
            }

            if (ad.getAdvertiser() == null || ad.getAdvertiser().isEmpty()) {
                advertiserView.setVisibility(View.GONE);
            } else {
                advertiserView.setText(ad.getAdvertiser());
                advertiserView.setVisibility(View.VISIBLE);
            }

            if (ad.getStarRating() == null) {
                starsView.setVisibility(View.GONE);
            } else {
                starsView.setRating(ad.getStarRating().floatValue());
                starsView.setVisibility(View.VISIBLE);
            }

            if (ad.getStore() == null || ad.getStore().isEmpty()) {
                storeView.setVisibility(View.GONE);
            } else {
                storeView.setVisibility(View.VISIBLE);
                storeView.setText(ad.getStore());
            }

            if (ad.getPrice() == null || ad.getPrice().isEmpty()) {
                priceView.setVisibility(View.GONE);
            } else {
                priceView.setVisibility(View.VISIBLE);
                priceView.setText(ad.getPrice());
            }

            nativeAdView.setNativeAd(ad);
        }

        void onRecycled() {
            // Keep slot ad in FEED_SLOT_ADS during scroll so scrolling up/down is 0.0ms with zero layout jump!
        }
    }

    static class PostVH extends RecyclerView.ViewHolder {
        ImageView avatar, image, tick;
        ImageView reactBadgeIv, likeIcon, commentIcon, shareIcon;
        TextView name, meta, more, closeBtn, content;
        TextView extraReactsTv, likeEmojiTv, likeTxt, commentTxt, shareTxt;
        TextView tagSummary, commentsSummary;
        TextView sharedName, sharedContent, playBadge, pageBadge;
        LinearLayout like, comment, share, reactSummaryWrap;
        LinearLayout specialBox, sharedBox, pollBox, summaryRow, inlineCommentsBox;
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
            tick.setImageResource(com.friendbook.social.R.drawable.ic_verified);
            tick.setVisibility(View.GONE);
            int ts = Ui.dp(c, 15);
            LinearLayout.LayoutParams tll = new LinearLayout.LayoutParams(ts, ts);
            tll.leftMargin = Ui.dp(c, 5);
            nameRow.addView(name);
            nameRow.addView(tick, tll);
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

            // ---- special friendbook banner (Life Event / Q&A / Marketplace) ----
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
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setMaxHeight(Ui.dp(c, 680));
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

            // ---- Pro Clickable Summary Row: Left=[🔵👍 / ❤️😂] "2" or "You and 2 others" | Right="2 comments" ----
            summaryRow = Ui.row(c);
            summaryRow.setGravity(Gravity.CENTER_VERTICAL);
            summaryRow.setMinimumHeight(Ui.dp(c, 32));
            Ui.pad(summaryRow, 12, 6, 12, 4);
            summaryRow.setVisibility(View.GONE);

            reactSummaryWrap = Ui.row(c);
            reactSummaryWrap.setGravity(Gravity.CENTER_VERTICAL);
            reactSummaryWrap.setMinimumHeight(Ui.dp(c, 22));
            reactSummaryWrap.setPadding(0, 0, Ui.dp(c, 12), 0);

            reactBadgeIv = new ImageView(c);
            reactBadgeIv.setImageResource(com.friendbook.social.R.drawable.ic_reaction_like_badge);
            int badgeSz = Ui.dp(c, 18);
            reactSummaryWrap.addView(reactBadgeIv, new LinearLayout.LayoutParams(badgeSz, badgeSz));

            extraReactsTv = Ui.tv(c, "", 13.5f, Ui.txt(c));
            extraReactsTv.setIncludeFontPadding(false);
            extraReactsTv.setGravity(Gravity.CENTER_VERTICAL);
            extraReactsTv.setPadding(Ui.dp(c, 2), 0, 0, 0);
            extraReactsTv.setVisibility(View.GONE);
            reactSummaryWrap.addView(extraReactsTv, new LinearLayout.LayoutParams(-2, Ui.dp(c, 20)));

            tagSummary = Ui.tv(c, "", 13.5f, Ui.sub(c));
            tagSummary.setIncludeFontPadding(false);
            tagSummary.setGravity(Gravity.CENTER_VERTICAL);
            tagSummary.setPadding(Ui.dp(c, 6), 0, 0, 0);
            reactSummaryWrap.addView(tagSummary);

            summaryRow.addView(reactSummaryWrap);
            View spacer = new View(c);
            summaryRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
            commentsSummary = Ui.tv(c, "", 13.5f, Ui.sub(c));
            commentsSummary.setIncludeFontPadding(false);
            commentsSummary.setPadding(Ui.dp(c, 8), 0, 0, 0);
            summaryRow.addView(commentsSummary);
            card.addView(summaryRow);

            // ---- 3 Rounded Pill Capsule Buttons (Hollow Outline Unliked -> Solid #1877F2 Blue Liked / Emoji Reacted!) ----
            LinearLayout actions = Ui.row(c);
            Ui.pad(actions, 10, 4, 10, 10);

            like = pillContainer(c);
            likeIcon = new ImageView(c);
            likeIcon.setImageResource(com.friendbook.social.R.drawable.ic_like_outline);
            like.addView(likeIcon, new LinearLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20)));
            likeEmojiTv = Ui.tv(c, "", 15.5f, Ui.txt(c));
            likeEmojiTv.setIncludeFontPadding(false);
            likeEmojiTv.setGravity(Gravity.CENTER);
            likeEmojiTv.setVisibility(View.GONE);
            like.addView(likeEmojiTv, new LinearLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20)));
            likeTxt = Ui.tv(c, "", 13.5f, Ui.txt(c));
            likeTxt.setIncludeFontPadding(false);
            likeTxt.setPadding(Ui.dp(c, 6), 0, 0, 0);
            likeTxt.setVisibility(View.GONE);
            like.addView(likeTxt);

            comment = pillContainer(c);
            commentIcon = new ImageView(c);
            commentIcon.setImageResource(com.friendbook.social.R.drawable.ic_comment_bubble_outline);
            comment.addView(commentIcon, new LinearLayout.LayoutParams(Ui.dp(c, 19), Ui.dp(c, 19)));
            commentTxt = Ui.tv(c, "", 13.5f, Ui.txt(c));
            commentTxt.setPadding(Ui.dp(c, 6), 0, 0, 0);
            commentTxt.setVisibility(View.GONE);
            comment.addView(commentTxt);

            share = pillContainer(c);
            shareIcon = new ImageView(c);
            shareIcon.setImageResource(com.friendbook.social.R.drawable.ic_share_curved_outline);
            share.addView(shareIcon, new LinearLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20)));
            shareTxt = Ui.tv(c, "", 13.5f, Ui.txt(c));
            shareTxt.setPadding(Ui.dp(c, 6), 0, 0, 0);
            shareTxt.setVisibility(View.GONE);
            share.addView(shareTxt);

            int pillH = Ui.dp(c, 38);
            LinearLayout.LayoutParams a1 = new LinearLayout.LayoutParams(0, pillH, 1);
            a1.rightMargin = Ui.dp(c, 8);
            LinearLayout.LayoutParams a2 = new LinearLayout.LayoutParams(0, pillH, 1);
            a2.rightMargin = Ui.dp(c, 8);
            LinearLayout.LayoutParams a3 = new LinearLayout.LayoutParams(0, pillH, 1);
            actions.addView(like, a1);
            actions.addView(comment, a2);
            actions.addView(share, a3);
            card.addView(actions);

            // ---- Inline Multi-User Comments Preview + Quick Comment Bar (friendbook Pro!) ----
            inlineCommentsBox = Ui.col(c);
            inlineCommentsBox.setBackgroundColor(Ui.card(c));
            inlineCommentsBox.setVisibility(View.GONE);
            card.addView(inlineCommentsBox, new LinearLayout.LayoutParams(-1, -2));

            // friendbook gap between posts
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

        private static LinearLayout pillContainer(Context c) {
            LinearLayout pill = Ui.row(c);
            pill.setGravity(Gravity.CENTER);
            pill.setBackground(Ui.round(c, Ui.inputBg(c), 20));
            pill.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 10), 0);
            return pill;
        }
    }
}
