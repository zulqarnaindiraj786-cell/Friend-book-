package com.friendbook.social.core;

import android.content.Context;
import android.content.SharedPreferences;

import com.friendbook.social.models.Models;
import com.friendbook.social.story.StoriesAdapter;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 🧠 Friendbook-Style Dynamic Feed, Video/Reels & Sponsored Ad Ranking Engine ("EdgeRank Pro"):
 * - Guarantees fresh Posts, fresh Videos/Reels, fresh PYMK & fresh Sponsored Ad auctions on:
 *     1) Every App Cold Launch (closing & reopening the app)
 *     2) Every App Warm Resume after backgrounding (>= 15 seconds)
 *     3) Every Pull-to-Refresh or Active-Tab Tap on Home / Reels / Watch
 * - Tracks viewed Post & Video impressions across sessions (`friendbook_algo` SharedPreferences)
 *   so already-seen top items cool down and UNSEEN / NEW / DIVERSE content surfaces at #1!
 * - Deterministic within a single scroll session (zero card jumping when liking/commenting),
 *   and dynamically re-ranked the instant a new refresh epoch starts!
 */
public final class FeedAlgorithm {

    private static final String PREF = "friendbook_algo_v1";
    private static final String K_EPOCH = "epoch";
    private static final String K_SEED = "seed";
    private static final String K_POST_SEEN_EPOCH = "post_seen_epoch";
    private static final String K_POST_SEEN_COUNT = "post_seen_count";
    private static final String K_VIDEO_SEEN_EPOCH = "video_seen_epoch";
    private static final String K_VIDEO_SEEN_COUNT = "video_seen_count";
    private static final String K_CREATOR_AFFINITY = "creator_affinity";
    private static final String K_LAST_TOP_POST = "last_top_post";
    private static final String K_LAST_TOP_REEL = "last_top_reel";

    private static final int MAX_TRACKED = 350;

    private static boolean initialized = false;
    private static int currentEpoch = 1;
    private static long currentSeed = System.currentTimeMillis();
    private static long lastBackgroundAt = 0L;
    private static String lastTopPostId = "";
    private static String lastTopReelId = "";

    private static final Map<String, Integer> postLastEpoch = new HashMap<>();
    private static final Map<String, Integer> postSeenCount = new HashMap<>();
    private static final Map<String, Integer> videoLastEpoch = new HashMap<>();
    private static final Map<String, Integer> videoSeenCount = new HashMap<>();
    private static final Map<String, Integer> creatorAffinity = new HashMap<>();

    // Impressions collected during the CURRENT viewing session; flushed when next refresh/reopen happens
    // so cards never jump around mid-scroll while the user is reading or reacting!
    private static final Set<String> pendingPostImpressions = new HashSet<>();
    private static final Set<String> pendingVideoImpressions = new HashSet<>();

    private FeedAlgorithm() {}

    public static synchronized void ensureLoaded(Context c) {
        if (initialized || c == null) return;
        initialized = true;
        SharedPreferences sp = c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        currentEpoch = sp.getInt(K_EPOCH, 1);
        currentSeed = sp.getLong(K_SEED, mix64(System.nanoTime() ^ System.currentTimeMillis()));
        lastTopPostId = sp.getString(K_LAST_TOP_POST, "");
        lastTopReelId = sp.getString(K_LAST_TOP_REEL, "");
        loadMap(sp.getString(K_POST_SEEN_EPOCH, "{}"), postLastEpoch);
        loadMap(sp.getString(K_POST_SEEN_COUNT, "{}"), postSeenCount);
        loadMap(sp.getString(K_VIDEO_SEEN_EPOCH, "{}"), videoLastEpoch);
        loadMap(sp.getString(K_VIDEO_SEEN_COUNT, "{}"), videoSeenCount);
        loadMap(sp.getString(K_CREATOR_AFFINITY, "{}"), creatorAffinity);
    }

    /** Records positive user engagement (like/comment/share/save/follow) with a creator for BOTH Posts & Reels! */
    public static synchronized void recordCreatorAffinity(Context c, String authorId, int points) {
        if (authorId == null || authorId.isEmpty() || points <= 0) return;
        ensureLoaded(c);
        int cur = getOrZero(creatorAffinity, authorId);
        creatorAffinity.put(authorId, Math.min(200, cur + points));
        persist(c);
    }

    /** Called in MainActivity.onCreate (when user opens the app after closing it). */
    public static synchronized int onColdAppLaunch(Context c) {
        ensureLoaded(c);
        return advanceEpochInternal(c);
    }

    /** Called when app goes to background (MainActivity.onPause / onStop). */
    public static synchronized void onAppBackgrounded(Context c) {
        ensureLoaded(c);
        lastBackgroundAt = System.currentTimeMillis();
        flushPendingImpressionsOnly(c);
    }

    /**
     * Called in MainActivity.onResume:
     * Returns true if the user was away >= 15s and a fresh friendbook-style feed/video/ad epoch was triggered!
     */
    public static synchronized boolean onAppResumed(Context c) {
        ensureLoaded(c);
        if (lastBackgroundAt > 0L && (System.currentTimeMillis() - lastBackgroundAt) >= 15_000L) {
            lastBackgroundAt = 0L;
            advanceEpochInternal(c);
            return true;
        }
        lastBackgroundAt = 0L;
        return false;
    }

    /** Called on Pull-to-Refresh or re-tapping active Home / Reels / Watch tab! */
    public static synchronized int nextRefreshGeneration(Context c) {
        ensureLoaded(c);
        return advanceEpochInternal(c);
    }

    public static synchronized int epoch() {
        return currentEpoch;
    }

    public static synchronized long seed() {
        return currentSeed;
    }

    /** Dynamic first Sponsored Ad slot (after 2, 3, or 4 posts) + spacing per epoch like real friendbook. */
    public static synchronized int adFirstPostCount() {
        int mod = Math.abs(currentEpoch) % 3;
        return 2 + mod; // 2, 3, or 4 posts before first Sponsored card
    }

    public static synchronized int adIntervalPosts() {
        return (Math.abs(currentEpoch) % 2 == 0) ? 3 : 4; // every 3 or 4 posts thereafter
    }

    /** Record that the user saw a post in the Home feed (takes effect on NEXT refresh / reopen). */
    public static synchronized void recordPostImpression(Context c, String postId) {
        if (postId == null || postId.isEmpty()) return;
        ensureLoaded(c);
        pendingPostImpressions.add(postId);
    }

    /** Record that the user watched a Reel / Video (flushed on pause/refresh for zero scroll jank!). */
    public static synchronized void recordVideoImpression(Context c, String reelOrPostId) {
        if (reelOrPostId == null || reelOrPostId.isEmpty()) return;
        ensureLoaded(c);
        pendingVideoImpressions.add(reelOrPostId);
        videoLastEpoch.put(reelOrPostId, currentEpoch);
        videoSeenCount.put(reelOrPostId, getOrZero(videoSeenCount, reelOrPostId) + 1);
        lastTopReelId = reelOrPostId;
    }

    /** Optional user control in Feed Preferences: clear seen history so everything feels brand new. */
    public static synchronized void resetHistory(Context c) {
        ensureLoaded(c);
        postLastEpoch.clear();
        postSeenCount.clear();
        videoLastEpoch.clear();
        videoSeenCount.clear();
        pendingPostImpressions.clear();
        pendingVideoImpressions.clear();
        lastTopPostId = "";
        lastTopReelId = "";
        advanceEpochInternal(c);
    }

    private static void flushPendingImpressionsOnly(Context c) {
        if (pendingPostImpressions.isEmpty() && pendingVideoImpressions.isEmpty()) return;
        for (String pid : pendingPostImpressions) {
            postLastEpoch.put(pid, currentEpoch);
            postSeenCount.put(pid, getOrZero(postSeenCount, pid) + 1);
        }
        pendingPostImpressions.clear();
        for (String vid : pendingVideoImpressions) {
            videoLastEpoch.put(vid, currentEpoch);
            videoSeenCount.put(vid, getOrZero(videoSeenCount, vid) + 1);
        }
        pendingVideoImpressions.clear();
        trimMap(postLastEpoch, postSeenCount);
        trimMap(videoLastEpoch, videoSeenCount);
        persist(c);
    }

    private static int advanceEpochInternal(Context c) {
        for (String pid : pendingPostImpressions) {
            postLastEpoch.put(pid, currentEpoch);
            postSeenCount.put(pid, getOrZero(postSeenCount, pid) + 1);
        }
        pendingPostImpressions.clear();
        for (String vid : pendingVideoImpressions) {
            videoLastEpoch.put(vid, currentEpoch);
            videoSeenCount.put(vid, getOrZero(videoSeenCount, vid) + 1);
        }
        pendingVideoImpressions.clear();

        currentEpoch++;
        currentSeed = mix64(System.nanoTime() ^ (currentEpoch * 0x9E3779B97F4A7C15L) ^ System.currentTimeMillis());
        trimMap(postLastEpoch, postSeenCount);
        trimMap(videoLastEpoch, videoSeenCount);
        persist(c);
        return currentEpoch;
    }

    // ==================== 1. HOME NEWS FEED RANKING ====================

    public static synchronized List<Models.Post> rankHomeFeed(
            Context c,
            List<Models.Post> input,
            Set<String> friendIds,
            List<String> favoriteUids,
            Set<String> boostedPostIds) {

        ensureLoaded(c);
        if (input == null || input.isEmpty()) return new ArrayList<>();
        if (input.size() == 1) {
            pendingPostImpressions.add(input.get(0).id);
            return new ArrayList<>(input);
        }

        String me = Fb.uid();
        long now = System.currentTimeMillis();
        Set<String> favs = favoriteUids == null ? Collections.emptySet() : new HashSet<>(favoriteUids);
        Set<String> friends = friendIds == null ? Collections.emptySet() : friendIds;
        Set<String> boosted = boostedPostIds == null ? Collections.emptySet() : boostedPostIds;

        List<ScoredPost> scored = new ArrayList<>(input.size());
        for (Models.Post p : input) {
            if (p == null || p.id == null) continue;
            double s = scorePost(p, me, now, friends, favs, boosted);
            scored.add(new ScoredPost(p, s));
        }

        Collections.sort(scored, (a, b) -> Double.compare(b.score, a.score));

        // Author diversity + Video discovery blender (just like real friendbook News Feed!)
        List<Models.Post> blended = blendAuthorsAndPromoteFreshVideo(scored);

        // Guarantee slot #0 rotates away from previous epoch's #0 whenever >= 2 posts exist
        // (unless the user literally JUST published their own post in the last 45s!)
        if (blended.size() >= 2 && lastTopPostId != null && !lastTopPostId.isEmpty()
                && lastTopPostId.equals(blended.get(0).id)) {
            Models.Post top = blended.get(0);
            boolean justCreatedByMe = me != null && me.equals(top.authorId)
                    && (now - top.createdAt) < 45_000L && !postSeenCount.containsKey(top.id);
            if (!justCreatedByMe) {
                Models.Post swap = blended.remove(1);
                blended.add(0, swap);
            }
        }

        if (!blended.isEmpty() && blended.get(0).id != null) {
            lastTopPostId = blended.get(0).id;
            // Pre-mark top 2 slots as seen in this epoch so even a quick open -> close -> reopen rotates them!
            pendingPostImpressions.add(blended.get(0).id);
            if (blended.size() > 1 && blended.get(1).id != null) {
                pendingPostImpressions.add(blended.get(1).id);
            }
        }
        return blended;
    }

    private static double scorePost(
            Models.Post p,
            String me,
            long now,
            Set<String> friends,
            Set<String> favs,
            Set<String> boosted) {

        double score = 0.0;

        // 1. Brand-new post by current user within last 45s gets immediate confirmation pin once
        long ageMs = Math.max(0L, now - p.createdAt);
        int seen = getOrZero(postSeenCount, p.id);
        int lastEp = getOrZero(postLastEpoch, p.id);

        if (me != null && me.equals(p.authorId) && ageMs < 45_000L && seen == 0) {
            return 10_000.0;
        }

        // 2. Unseen Discovery Boost (posts the user has NEVER seen jump to the front!)
        if (seen == 0) {
            score += 195.0;
        }

        // 3. Smooth Time-Decay Freshness (hours)
        double ageHours = ageMs / 3_600_000.0;
        score += 125.0 / (1.0 + (ageHours / 9.0));
        if (ageHours < 1.0 && seen == 0) {
            score += 55.0; // breaking fresh unseen post
        }

        // 4. Social Graph + Creator Affinity (Favorites, Friends, Engaged Creators, Verified)
        if (p.authorId != null && favs.contains(p.authorId)) score += 68.0;
        if (p.authorId != null && friends.contains(p.authorId)) score += 34.0;
        if (p.authorId != null) {
            score += Math.min(60.0, getOrZero(creatorAffinity, p.authorId) * 2.2);
        }
        if ("diamond".equals(p.authorTickLevel)) score += 26.0;
        else if ("gold".equals(p.authorTickLevel)) score += 18.0;
        else if ("blue".equals(p.authorTickLevel) || p.authorVerified) score += 12.0;

        // 5. Rich Media & Video Discovery Signal (friendbook prioritizes native video & visual posts)
        boolean isVid = "video".equals(p.mediaType);
        if (isVid) {
            score += (seen == 0 ? 52.0 : 28.0);
        } else if ("images".equals(p.mediaType) || "image".equals(p.mediaType)) {
            score += 20.0;
        } else if (p.bgColor != 0 || (p.pollOptions != null && !p.pollOptions.isEmpty())) {
            score += 15.0;
        }

        // 6. Engagement Velocity (Reactions + Comments + Shares)
        double engagement = Math.max(0, p.likes) * 2.0
                + Math.max(0, p.comments) * 4.5
                + Math.max(0, p.shares) * 6.5;
        score += Math.log1p(engagement) * 15.0;

        // 7. friendbook Impression Cool-Down Penalty (rotates away posts seen in recent refreshes/sessions!)
        if (seen > 0) {
            int epochsAgo = Math.max(1, currentEpoch - lastEp);
            if (epochsAgo == 1) score -= 275.0;      // just seen right before refresh/close -> push down!
            else if (epochsAgo == 2) score -= 145.0; // seen 2 refreshes ago -> moderate cool-down
            else if (epochsAgo == 3) score -= 65.0;
            score -= Math.min(seen, 7) * 34.0;
        }
        if (p.id.equals(lastTopPostId)) {
            score -= 220.0;
        }

        // 8. Per-Epoch Exploration Jitter (0..+110 pts, 100% deterministic for currentEpoch!)
        // Ensures even when all posts have been seen, EVERY refresh & app reopen surfaces a fresh mix!
        score += jitter01(currentSeed, p.id) * 110.0;

        return score;
    }

    private static List<Models.Post> blendAuthorsAndPromoteFreshVideo(List<ScoredPost> sorted) {
        List<Models.Post> remaining = new ArrayList<>(sorted.size());
        for (ScoredPost sp : sorted) remaining.add(sp.post);

        List<Models.Post> out = new ArrayList<>(remaining.size());
        String prevAuthor = null;

        while (!remaining.isEmpty()) {
            int pickIdx = 0;
            if (prevAuthor != null) {
                for (int i = 0; i < Math.min(remaining.size(), 6); i++) {
                    String a = remaining.get(i).authorId;
                    if (a == null || !a.equals(prevAuthor)) {
                        pickIdx = i;
                        break;
                    }
                }
            }
            Models.Post chosen = remaining.remove(pickIdx);
            out.add(chosen);
            prevAuthor = chosen.authorId;
        }

        // Ensure at least one Video post appears inside the Top 3 slots if any video exists in the feed!
        boolean videoInTop3 = false;
        for (int i = 0; i < Math.min(3, out.size()); i++) {
            if ("video".equals(out.get(i).mediaType)) {
                videoInTop3 = true;
                break;
            }
        }
        if (!videoInTop3 && out.size() > 3) {
            for (int i = 3; i < out.size(); i++) {
                if ("video".equals(out.get(i).mediaType)) {
                    Models.Post vid = out.remove(i);
                    int targetSlot = (Math.abs(currentEpoch) % 2 == 0) ? 1 : 2;
                    out.add(Math.min(targetSlot, out.size()), vid);
                    break;
                }
            }
        }
        return out;
    }

    // ==================== 2. REELS & WATCH VIDEO DISCOVERY (FULL POST-GRADE ALGORITHM) ====================

    public static synchronized List<Models.Reel> rankReels(
            Context c,
            List<Models.Reel> input,
            String pinnedInitialReelId) {

        ensureLoaded(c);
        if (input == null || input.isEmpty()) return new ArrayList<>();
        if (input.size() == 1) {
            if (input.get(0).id != null) pendingVideoImpressions.add(input.get(0).id);
            return new ArrayList<>(input);
        }

        String me = Fb.uid();
        long now = System.currentTimeMillis();
        Set<String> following = (Fb.me != null && Fb.me.following != null)
                ? new HashSet<>(Fb.me.following) : Collections.emptySet();
        Set<String> favs = (Fb.me != null && Fb.me.favoriteUids != null)
                ? new HashSet<>(Fb.me.favoriteUids) : Collections.emptySet();

        long freshSalt = mix64(currentSeed ^ System.nanoTime() ^ (currentEpoch * 0x517CC1B727220A95L));
        List<ScoredReel> scored = new ArrayList<>(input.size());
        for (Models.Reel r : input) {
            if (r == null || r.id == null) continue;
            long ageMs = Math.max(0L, now - r.createdAt);
            int seen = getOrZero(videoSeenCount, r.id);
            int lastEp = getOrZero(videoLastEpoch, r.id);

            double s = 0.0;
            // 1. Creator's brand-new reel (<45s) gets instant #1 confirmation once
            if (me != null && me.equals(r.authorId) && ageMs < 45_000L && seen == 0) {
                s = 10_000.0;
            } else {
                // 2. TikTok Unwatched Discovery Priority: unwatched videos ALWAYS outrank watched ones!
                if (seen == 0) s += 2500.0;

                // 3. Smooth Time-Decay Freshness + Breaking Upload Boost
                double ageHours = ageMs / 3_600_000.0;
                s += 130.0 / (1.0 + ageHours / 12.0);
                if (ageHours < 2.0 && seen == 0) s += 80.0;

                // 4. Social Graph + Post/Reel Cross-Affinity (Favorites, Following, Creator Engagement, Verified)
                if (r.authorId != null) {
                    if (favs.contains(r.authorId)) s += 74.0;
                    if (following.contains(r.authorId)) s += 46.0;
                    s += Math.min(80.0, getOrZero(creatorAffinity, r.authorId) * 2.5);
                    String tick = TickCache.peek(r.authorId);
                    if ("diamond".equals(tick)) s += 28.0;
                    else if ("gold".equals(tick)) s += 20.0;
                    else if ("blue".equals(tick)) s += 14.0;
                }

                // 5. Caption & Hashtag Content Quality Signal
                if (r.caption != null && !r.caption.trim().isEmpty()) {
                    s += 10.0;
                    if (r.caption.contains("#")) s += 10.0;
                }

                // 6. Multi-Signal Engagement + Viral Velocity (Reactions, Comments, Shares, Saves)
                int rx = Math.max(r.likes, r.reactions != null ? r.reactions.size() : 0);
                int cm = Math.max(r.comments, r.inlineComments != null ? r.inlineComments.size() : 0);
                int sh = Math.max(0, r.shares);
                int sv = r.savedBy != null ? r.savedBy.size() : 0;
                double eng = rx * 2.8 + cm * 5.2 + sh * 7.5 + sv * 6.0;
                s += Math.log1p(eng) * 18.0;
                s += Math.min(55.0, (eng / (ageHours + 2.0)) * 3.2);

                // 7. Strong TikTok-Style Watched Cool-Down & Anti-Repeat Penalty
                if (seen > 0) {
                    int epAgo = Math.max(1, currentEpoch - lastEp);
                    if (epAgo == 1) s -= 650.0;
                    else if (epAgo == 2) s -= 320.0;
                    else if (epAgo == 3) s -= 150.0;
                    s -= Math.min(seen, 12) * 420.0;
                }
                if (r.id.equals(lastTopReelId)) s -= 1800.0;

                // 8. High-Entropy TikTok For-You Exploration Shuffle (guarantees a fresh video every time!)
                s += jitter01(freshSalt, r.id) * 680.0;
            }
            scored.add(new ScoredReel(r, s));
        }

        Collections.sort(scored, (a, b) -> Double.compare(b.score, a.score));

        // Space out consecutive videos from the same creator
        List<Models.Reel> pool = new ArrayList<>(scored.size());
        for (ScoredReel sr : scored) pool.add(sr.reel);

        List<Models.Reel> out = new ArrayList<>(pool.size());
        String prevCreator = null;
        while (!pool.isEmpty()) {
            int idx = 0;
            if (prevCreator != null) {
                for (int i = 0; i < Math.min(pool.size(), 5); i++) {
                    String cr = pool.get(i).authorId;
                    if (cr == null || !cr.equals(prevCreator)) {
                        idx = i;
                        break;
                    }
                }
            }
            Models.Reel picked = pool.remove(idx);
            out.add(picked);
            prevCreator = picked.authorId;
        }

        if (pinnedInitialReelId != null && !pinnedInitialReelId.isEmpty()) {
            for (int i = 0; i < out.size(); i++) {
                if (pinnedInitialReelId.equals(out.get(i).id)) {
                    out.add(0, out.remove(i));
                    break;
                }
            }
        } else if (out.size() >= 2 && lastTopReelId != null && lastTopReelId.equals(out.get(0).id)) {
            Models.Reel second = out.remove(1);
            out.add(0, second);
        }

        if (!out.isEmpty() && out.get(0).id != null) {
            lastTopReelId = out.get(0).id;
            pendingVideoImpressions.add(out.get(0).id);
        }
        return out;
    }

    public static synchronized List<Models.Post> rankWatchVideos(Context c, List<Models.Post> videos) {
        ensureLoaded(c);
        if (videos == null || videos.size() <= 1) return videos == null ? new ArrayList<>() : new ArrayList<>(videos);
        long now = System.currentTimeMillis();
        List<ScoredPost> scored = new ArrayList<>(videos.size());
        for (Models.Post v : videos) {
            if (v == null || v.id == null) continue;
            int seen = getOrZero(videoSeenCount, v.id) + getOrZero(postSeenCount, v.id);
            int lastEp = Math.max(getOrZero(videoLastEpoch, v.id), getOrZero(postLastEpoch, v.id));
            double s = (seen == 0 ? 210.0 : 0.0);
            double ageHours = Math.max(0L, now - v.createdAt) / 3_600_000.0;
            s += 105.0 / (1.0 + ageHours / 12.0);
            s += Math.log1p(Math.max(0, v.likes) * 3.0 + Math.max(0, v.comments) * 5.0 + Math.max(0, v.shares) * 7.0) * 16.0;
            if (seen > 0) {
                int epAgo = Math.max(1, currentEpoch - lastEp);
                if (epAgo == 1) s -= 260.0;
                else if (epAgo == 2) s -= 130.0;
                s -= Math.min(seen, 6) * 32.0;
            }
            s += jitter01(currentSeed ^ 0x2545F4914F6CDD1DL, v.id) * 115.0;
            scored.add(new ScoredPost(v, s));
        }
        Collections.sort(scored, (a, b) -> Double.compare(b.score, a.score));
        List<Models.Post> out = new ArrayList<>(scored.size());
        for (ScoredPost sp : scored) out.add(sp.post);
        if (!out.isEmpty() && out.get(0).id != null) {
            pendingVideoImpressions.add(out.get(0).id);
        }
        return out;
    }

    // ==================== 3. STORIES & PYMK ROTATION ====================

    public static synchronized void rankStories(Context c, List<StoriesAdapter.Group> groups, String myUid) {
        ensureLoaded(c);
        if (groups == null || groups.size() <= 1) return;
        Collections.sort(groups, (a, b) -> {
            boolean aMe = myUid != null && myUid.equals(a.authorId);
            boolean bMe = myUid != null && myUid.equals(b.authorId);
            if (aMe != bMe) return aMe ? -1 : 1;
            if (a.unseen != b.unseen) return a.unseen ? -1 : 1;
            long at = (a.stories == null || a.stories.isEmpty()) ? 0L : a.stories.get(a.stories.size() - 1).createdAt;
            long bt = (b.stories == null || b.stories.isEmpty()) ? 0L : b.stories.get(b.stories.size() - 1).createdAt;
            double sa = jitter01(currentSeed ^ 0x1111L, a.authorId) * 72.0 + (at / 3_600_000.0);
            double sb = jitter01(currentSeed ^ 0x1111L, b.authorId) * 72.0 + (bt / 3_600_000.0);
            return Double.compare(sb, sa);
        });
    }

    public static synchronized List<Models.User> rankPymk(Context c, List<Models.User> candidates, int maxCount) {
        ensureLoaded(c);
        if (candidates == null || candidates.isEmpty()) return new ArrayList<>();
        List<Models.User> copy = new ArrayList<>(candidates);
        Collections.sort(copy, (a, b) -> Double.compare(
                jitter01(currentSeed ^ 0x77AA33L, b.uid),
                jitter01(currentSeed ^ 0x77AA33L, a.uid)));
        return copy.size() > maxCount ? new ArrayList<>(copy.subList(0, maxCount)) : copy;
    }

    // ==================== INTERNAL HELPERS ====================

    private static final class ScoredPost {
        final Models.Post post;
        final double score;
        ScoredPost(Models.Post p, double s) { post = p; score = s; }
    }

    private static final class ScoredReel {
        final Models.Reel reel;
        final double score;
        ScoredReel(Models.Reel r, double s) { reel = r; score = s; }
    }

    private static int getOrZero(Map<String, Integer> m, String k) {
        Integer v = m.get(k);
        return v == null ? 0 : v;
    }

    private static double jitter01(long seed, String id) {
        long h = id == null ? 0L : id.hashCode();
        long z = mix64(seed ^ (h * 0x9E3779B97F4A7C15L));
        return ((z >>> 11) * 0x1.0p-53);
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static void trimMap(Map<String, Integer> epochMap, Map<String, Integer> countMap) {
        if (epochMap.size() <= MAX_TRACKED) return;
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(epochMap.entrySet());
        Collections.sort(entries, (a, b) -> Integer.compare(a.getValue(), b.getValue()));
        int removeCount = entries.size() - MAX_TRACKED;
        for (int i = 0; i < removeCount; i++) {
            String key = entries.get(i).getKey();
            epochMap.remove(key);
            countMap.remove(key);
        }
    }

    private static void persist(Context c) {
        if (c == null) return;
        try {
            SharedPreferences sp = c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
            sp.edit()
                    .putInt(K_EPOCH, currentEpoch)
                    .putLong(K_SEED, currentSeed)
                    .putString(K_LAST_TOP_POST, lastTopPostId == null ? "" : lastTopPostId)
                    .putString(K_LAST_TOP_REEL, lastTopReelId == null ? "" : lastTopReelId)
                    .putString(K_POST_SEEN_EPOCH, toJson(postLastEpoch))
                    .putString(K_POST_SEEN_COUNT, toJson(postSeenCount))
                    .putString(K_VIDEO_SEEN_EPOCH, toJson(videoLastEpoch))
                    .putString(K_VIDEO_SEEN_COUNT, toJson(videoSeenCount))
                    .putString(K_CREATOR_AFFINITY, toJson(creatorAffinity))
                    .apply();
        } catch (Throwable ignored) {}
    }

    private static String toJson(Map<String, Integer> map) {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, Integer> e : map.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) o.put(e.getKey(), e.getValue().intValue());
            }
            return o.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    private static void loadMap(String json, Map<String, Integer> out) {
        out.clear();
        if (json == null || json.isEmpty()) return;
        try {
            JSONObject o = new JSONObject(json);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                out.put(k, o.optInt(k, 0));
            }
        } catch (Throwable ignored) {}
    }
}
