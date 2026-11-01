package com.friendbook.social.models;

import com.friendbook.social.core.Format;
import com.google.firebase.firestore.DocumentSnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Firestore document models — friendbook social schema. */
public class Models {

    public static class User {
        public String uid, displayName, email, photoUrl, bio, coverUrl, birthday;
        public String work, education, currentCity, hometown, relationshipStatus, relationship, website, pronouns, phone, phoneNumber;
        public boolean isOnline, isAdmin, isModerator, banned, isVerified;
        public boolean deactivated, loginAlerts, tagReviewEnabled, professionalMode;
        public boolean hideReactionCounts;
        public boolean showActiveStatus = true;
        public long lastSeen, createdAt;
        public int friendsCount;
        public int stars;     // Stars balance (monetization)
        public String tickLevel = "none"; // none | blue | gold | diamond
        public boolean subscriptionsEnabled;
        public int subscriptionPrice;
        public boolean profileLocked;     // 🔒 locked profile
        public boolean profilePicGuard;   // 🛡 Profile Picture Guard shield
        public String lookupPrivacy = "Everyone";    // Everyone | Friends | Only me
        public String friendsListPrivacy = "Public"; // Public | Friends | Only me
        public String emailPrivacy = "Only me";      // Only me (default) | Friends | Public
        public String contactPrivacy = "Only me";    // Only me (default) | Friends | Public
        public String contactInfoPrivacy = "Only me";
        public String followersPrivacy = "Public";   // Public | Friends
        public String tagPrivacy = "Everyone";       // Everyone | Friends | Only me
        public String twoFactorPin = "";
        public List<String> favoriteUids = new ArrayList<>(); // feed "Favorites" first
        public List<String> restrictedUids = new ArrayList<>(); // friends who only see Public posts
        public List<String> closeFriendsUids = new ArrayList<>();
        public List<String> highlightStoryIds = new ArrayList<>(); // ✨ Story Highlights on profile
        public List<String> savedReelIds = new ArrayList<>();
        public List<String> hobbies = new ArrayList<>();
        public String pinnedPostId;       // 📌 post pinned on own profile
        public String statusNote;         // 💭 24h Note bubble ("Share a thought")
        public long statusNoteAt;         // 💭 timestamp of Note bubble
        public int dailyStreak;           // 🎁 consecutive daily-bonus days
        public long lastBonusAt;          // 🎁 last daily-bonus claim (epoch ms)

        /** 0 none, 1 blue, 2 gold, 3 diamond — used for "only upgrade" checks. */
        public int tickRank() {
            if (tickLevel == null) return isVerified ? 1 : 0;
            switch (tickLevel) {
                case "blue": return 1;
                case "gold": return 2;
                case "diamond": return 3;
                default: return isVerified ? 1 : 0;
            }
        }
        public List<String> followers = new ArrayList<>();
        public List<String> following = new ArrayList<>();
        public List<String> blocked = new ArrayList<>();

        public static User fromDoc(DocumentSnapshot d) {
            User u = new User();
            Map<String, Object> m = d.getData();
            u.uid = d.getId();
            u.displayName = Format.strOr(m, "displayName", "User");
            u.email = Format.str(m, "email");
            u.photoUrl = Format.str(m, "photoUrl");
            u.bio = Format.str(m, "bio");
            u.coverUrl = Format.str(m, "coverUrl");
            u.birthday = Format.str(m, "birthday");
            u.work = Format.str(m, "work");
            u.education = Format.str(m, "education");
            u.currentCity = Format.str(m, "currentCity");
            u.hometown = Format.str(m, "hometown");
            u.relationshipStatus = Format.str(m, "relationshipStatus");
            u.relationship = !Format.str(m, "relationship").isEmpty()
                    ? Format.str(m, "relationship") : u.relationshipStatus;
            if (u.relationshipStatus.isEmpty()) u.relationshipStatus = u.relationship;
            u.website = Format.str(m, "website");
            u.pronouns = Format.str(m, "pronouns");
            String pn = Format.str(m, "phoneNumber");
            String ph = Format.str(m, "phone");
            u.phoneNumber = !pn.isEmpty() ? pn : ph;
            u.phone = !ph.isEmpty() ? ph : u.phoneNumber;
            u.isAdmin = Format.boolOf(m, "isAdmin");
            u.isModerator = Format.boolOf(m, "isModerator");
            u.banned = Format.boolOf(m, "banned");
            u.isVerified = Format.boolOf(m, "isVerified");
            u.deactivated = Format.boolOf(m, "deactivated");
            u.loginAlerts = Format.boolOf(m, "loginAlerts");
            u.tagReviewEnabled = Format.boolOf(m, "tagReviewEnabled");
            u.professionalMode = Format.boolOf(m, "professionalMode");
            u.hideReactionCounts = Format.boolOf(m, "hideReactionCounts");
            u.showActiveStatus = m == null || !m.containsKey("showActiveStatus") || Format.boolOf(m, "showActiveStatus");
            long lsTs = Format.ts(m == null ? null : m.get("lastSeen"));
            long lsMs = (m != null && m.get("lastSeenMs") instanceof Number)
                    ? ((Number) m.get("lastSeenMs")).longValue() : 0L;
            u.lastSeen = u.showActiveStatus ? Math.max(lsTs, lsMs) : 0L;
            boolean rawOnline = Format.boolOf(m, "isOnline");
            u.isOnline = u.showActiveStatus && rawOnline && u.lastSeen > 0L
                    && (System.currentTimeMillis() - u.lastSeen) <= com.friendbook.social.core.Presence.ONLINE_TTL_MS;
            u.createdAt = Format.ts(m == null ? null : m.get("createdAt"));
            u.friendsCount = Format.intOf(m, "friendsCount");
            u.stars = Format.intOf(m, "stars");
            u.tickLevel = Format.strOr(m, "tickLevel", u.isVerified ? "blue" : "none");
            u.subscriptionsEnabled = Format.boolOf(m, "subscriptionsEnabled");
            u.subscriptionPrice = Format.intOf(m, "subscriptionPrice");
            u.profileLocked = Format.boolOf(m, "profileLocked");
            u.profilePicGuard = Format.boolOf(m, "profilePicGuard");
            u.lookupPrivacy = Format.strOr(m, "lookupPrivacy", "Everyone");
            u.friendsListPrivacy = Format.strOr(m, "friendsListPrivacy", "Public");
            u.contactPrivacy = Format.strOr(m, "contactPrivacy", "Only me");
            u.contactInfoPrivacy = !Format.str(m, "contactInfoPrivacy").isEmpty()
                    ? Format.str(m, "contactInfoPrivacy") : u.contactPrivacy;
            u.emailPrivacy = !Format.str(m, "emailPrivacy").isEmpty()
                    ? Format.str(m, "emailPrivacy") : u.contactPrivacy;
            u.followersPrivacy = Format.strOr(m, "followersPrivacy", "Public");
            u.tagPrivacy = Format.strOr(m, "tagPrivacy", "Everyone");
            u.twoFactorPin = Format.str(m, "twoFactorPin");
            u.favoriteUids = Format.listOf(m, "favoriteUids");
            u.restrictedUids = Format.listOf(m, "restrictedUids");
            u.closeFriendsUids = Format.listOf(m, "closeFriendsUids");
            u.highlightStoryIds = Format.listOf(m, "highlightStoryIds");
            u.savedReelIds = Format.listOf(m, "savedReelIds");
            u.hobbies = Format.listOf(m, "hobbies");
            u.pinnedPostId = Format.str(m, "pinnedPostId");
            u.statusNote = Format.str(m, "statusNote");
            Object sna = m == null ? null : m.get("statusNoteAt");
            u.statusNoteAt = sna instanceof Number ? ((Number) sna).longValue() : Format.ts(sna);
            u.dailyStreak = Format.intOf(m, "dailyStreak");
            Object lb = m == null ? null : m.get("lastBonusAt");
            u.lastBonusAt = lb instanceof Number ? ((Number) lb).longValue() : Format.ts(lb);
            u.followers = Format.listOf(m, "followers");
            u.following = Format.listOf(m, "following");
            u.blocked = Format.listOf(m, "blockedUsers");
            return u;
        }
    }

    public static class Post {
        public String id, authorId, authorName, authorPhoto, content, mediaUrl, mediaType,
                feeling, privacy, sharedFromId, sharedFromAuthor, sharedFromContent,
                sharedFromMediaUrl, sharedFromMediaType, location, groupId;
        public String postType = "regular";
        public String groupApprovalStatus = "approved"; // approved | pending | declined
        public String lifeEvent, lifeEventIcon, lifeEventTitle, qaPrompt;
        public String marketTitle, marketPrice, marketCategory, marketCondition;
        public boolean marketSold;
        public String pageId, pageName, pagePhoto;
        public boolean archived;
        public long trashedAt;
        public int likes, comments, shares;
        public int bgColor; // 0 = none, or ARGB / Backgrounds index
        public long createdAt;
        public List<String> savedBy = new ArrayList<>();
        public List<String> subscribedUids = new ArrayList<>();
        public List<String> subscribedBy = new ArrayList<>();
        public List<String> mediaUrls = new ArrayList<>(); // multi-photo posts
        public List<String> taggedUids = new ArrayList<>();  // 👥 friends tagged in this post
        public List<String> taggedNames = new ArrayList<>(); // denormalized for fast render
        public List<String> pollOptions = new ArrayList<>(); // non-empty ⇒ poll post
        public Map<String, Long> pollVotes = new HashMap<>(); // uid → option index
        public String pinnedCommentId = "";
        public List<Comment> inlineComments = new ArrayList<>(); // synced multi-user commentsMap on post doc
        public Map<String, Map<String, Object>> reactionsMap = new HashMap<>(); // synced multi-user reactions on post doc

        public boolean authorVerified; // copied from author's profile when the post is created
        public String authorTickLevel = "none"; // none | blue | gold | diamond (denormalized)

        public boolean isPoll() { return pollOptions != null && pollOptions.size() >= 2; }
        public boolean isMultiPhoto() { return mediaUrls != null && mediaUrls.size() > 1; }
        public boolean isBgPost() { return bgColor != 0; }
        public boolean isPagePost() { return pageId != null && !pageId.isEmpty(); }
        public boolean isMarketplace() {
            return "marketplace".equals(postType)
                    || (marketTitle != null && !marketTitle.isEmpty())
                    || (marketPrice != null && !marketPrice.isEmpty());
        }
        public boolean isLifeEvent() {
            return "life_event".equals(postType)
                    || (lifeEventTitle != null && !lifeEventTitle.isEmpty())
                    || (lifeEvent != null && !lifeEvent.isEmpty());
        }
        public boolean isQa() { return qaPrompt != null && !qaPrompt.isEmpty(); }
        public boolean isQna() { return "qna".equals(postType) || isQa(); }

        @SuppressWarnings("unchecked")
        public static Post fromDoc(DocumentSnapshot d) {
            Post p = new Post();
            Map<String, Object> m = d.getData();
            p.id = d.getId();
            p.authorId = Format.str(m, "authorId");
            p.authorName = Format.strOr(m, "authorName", "User");
            p.authorPhoto = Format.str(m, "authorPhotoUrl");
            p.content = Format.str(m, "content");
            p.mediaUrl = Format.str(m, "mediaUrl");
            p.mediaType = Format.strOr(m, "mediaType", "none");
            p.feeling = Format.str(m, "feeling");
            p.privacy = Format.strOr(m, "privacy", "Public");
            p.location = Format.str(m, "location");
            p.groupId = Format.str(m, "groupId");
            p.groupApprovalStatus = Format.strOr(m, "groupApprovalStatus", "approved");
            p.postType = Format.strOr(m, "postType", "regular");
            p.lifeEvent = Format.str(m, "lifeEvent");
            p.lifeEventIcon = Format.str(m, "lifeEventIcon");
            p.lifeEventTitle = !Format.str(m, "lifeEventTitle").isEmpty()
                    ? Format.str(m, "lifeEventTitle") : p.lifeEvent;
            p.qaPrompt = Format.str(m, "qaPrompt");
            p.marketTitle = Format.str(m, "marketTitle");
            p.marketPrice = Format.str(m, "marketPrice");
            p.marketCategory = Format.str(m, "marketCategory");
            p.marketCondition = Format.str(m, "marketCondition");
            p.marketSold = Format.boolOf(m, "marketSold");
            p.pageId = Format.str(m, "pageId");
            p.pageName = Format.str(m, "pageName");
            p.pagePhoto = Format.str(m, "pagePhoto");
            p.archived = Format.boolOf(m, "archived");
            Object tr = m == null ? null : m.get("trashedAt");
            p.trashedAt = tr instanceof Number ? ((Number) tr).longValue() : Format.ts(tr);
            p.bgColor = Format.intOf(m, "bgColor");
            p.likes = Format.intOf(m, "likes");
            p.comments = Format.intOf(m, "comments");
            p.shares = Format.intOf(m, "shares");
            p.createdAt = Format.ts(m == null ? null : m.get("createdAt"));
            p.authorVerified = Format.boolOf(m, "authorVerified");
            p.authorTickLevel = Format.strOr(m, "authorTickLevel", p.authorVerified ? "blue" : "none");
            p.sharedFromId = Format.str(m, "sharedFromId");
            p.sharedFromAuthor = Format.str(m, "sharedFromAuthor");
            p.sharedFromContent = Format.str(m, "sharedFromContent");
            p.sharedFromMediaUrl = Format.str(m, "sharedFromMediaUrl");
            p.sharedFromMediaType = Format.str(m, "sharedFromMediaType");
            p.savedBy = Format.listOf(m, "savedBy");
            p.subscribedUids = Format.listOf(m, "subscribedUids");
            p.subscribedBy = Format.listOf(m, "subscribedBy");
            if (p.subscribedBy.isEmpty() && !p.subscribedUids.isEmpty()) p.subscribedBy = p.subscribedUids;
            p.mediaUrls = Format.listOf(m, "mediaUrls");
            p.taggedUids = Format.listOf(m, "taggedUids");
            p.taggedNames = Format.listOf(m, "taggedNames");
            p.pollOptions = Format.listOf(m, "pollOptions");
            p.pollVotes = new HashMap<>();
            Object pv = m == null ? null : m.get("pollVotes");
            if (pv instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) pv).entrySet()) {
                    if (e.getValue() instanceof Number)
                        p.pollVotes.put(String.valueOf(e.getKey()), ((Number) e.getValue()).longValue());
                }
            }
            p.pinnedCommentId = Format.str(m, "pinnedCommentId");
            p.inlineComments = Comment.parseEmbeddedComments(p.id, m, p.pinnedCommentId);
            if (p.inlineComments.size() > p.comments) {
                p.comments = p.inlineComments.size();
            }
            Object rxm = m == null ? null : m.get("reactionsMap");
            if (rxm instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) rxm).entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) continue;
                    String uidKey = String.valueOf(e.getKey()).trim();
                    if (uidKey.isEmpty()) continue;
                    if (e.getValue() instanceof Map) {
                        Map<String, Object> sub = new HashMap<>();
                        for (Map.Entry<?, ?> se : ((Map<?, ?>) e.getValue()).entrySet()) {
                            if (se.getKey() != null) sub.put(String.valueOf(se.getKey()), se.getValue());
                        }
                        String norm = normalizeReactionType(sub.get("type"), "like");
                        sub.put("type", norm);
                        sub.put("userId", uidKey);
                        p.reactionsMap.put(uidKey, sub);
                    } else if (e.getValue() instanceof String) {
                        String norm = normalizeReactionType(e.getValue(), "like");
                        Map<String, Object> sub = new HashMap<>();
                        sub.put("userId", uidKey);
                        sub.put("type", norm);
                        p.reactionsMap.put(uidKey, sub);
                    }
                }
            }
            // Also parse legacy `reactions` map on posts (keyed by userId) so older video/photo reactions stay per-user!
            Object legacyRx = m == null ? null : m.get("reactions");
            if (legacyRx instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) legacyRx).entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) continue;
                    String uidKey = String.valueOf(e.getKey()).trim();
                    if (uidKey.isEmpty() || p.reactionsMap.containsKey(uidKey)) continue;
                    Map<String, Object> sub = new HashMap<>();
                    sub.put("userId", uidKey);
                    if (e.getValue() instanceof Map) {
                        Map<?, ?> rawMap = (Map<?, ?>) e.getValue();
                        sub.put("type", normalizeReactionType(rawMap.get("type"), "like"));
                        Object nm = rawMap.get("userName") != null ? rawMap.get("userName") : rawMap.get("name");
                        Object ph = rawMap.get("userPhoto") != null ? rawMap.get("userPhoto") : rawMap.get("photo");
                        if (nm != null) sub.put("userName", String.valueOf(nm));
                        if (ph != null) sub.put("userPhoto", String.valueOf(ph));
                    } else {
                        sub.put("type", normalizeReactionType(e.getValue(), "like"));
                    }
                    p.reactionsMap.put(uidKey, sub);
                }
            }
            p.likes = Math.max(0, p.likes);
            if (p.reactionsMap.size() > p.likes) p.likes = p.reactionsMap.size();
            return p;
        }
    }

    /** Normalizes any raw reaction object (String or Map) into a valid reaction type ("like".."angry"). */
    public static String normalizeReactionType(Object raw, String defaultType) {
        String s = null;
        if (raw instanceof String) {
            s = ((String) raw).trim().toLowerCase(java.util.Locale.US);
        } else if (raw instanceof Map) {
            Object t = ((Map<?, ?>) raw).get("type");
            if (t != null) s = String.valueOf(t).trim().toLowerCase(java.util.Locale.US);
        } else if (raw != null) {
            s = String.valueOf(raw).trim().toLowerCase(java.util.Locale.US);
        }
        if ("like".equals(s) || "love".equals(s) || "haha".equals(s)
                || "wow".equals(s) || "sad".equals(s) || "angry".equals(s)) {
            return s;
        }
        return defaultType;
    }

    public static class Comment {
        public String id, postId, authorId, authorName, authorPhoto, content, imageUrl, parentId;
        public boolean edited;
        public boolean pinned;
        public int likes;
        public long createdAt;
        public List<String> likedBy = new ArrayList<>();
        public Map<String, String> reactions = new HashMap<>(); // uid -> like|love|haha|wow|sad|angry

        public static Comment fromDoc(DocumentSnapshot d) {
            return fromMap(d.getId(), d.getData());
        }

        @SuppressWarnings("unchecked")
        public static Comment fromMap(String id, Map<String, Object> m) {
            Comment c = new Comment();
            c.id = id != null && !id.isEmpty() ? id : Format.str(m, "id");
            c.postId = Format.strOr(m, "postId", Format.str(m, "post_id"));
            c.authorId = Format.strOr(m, "authorId", Format.strOr(m, "userId", Format.str(m, "uid")));
            c.authorName = Format.strOr(m, "authorName",
                    Format.strOr(m, "userName", Format.strOr(m, "name", "friendbook User")));
            c.authorPhoto = Format.strOr(m, "authorPhotoUrl",
                    Format.strOr(m, "authorPhoto", Format.str(m, "userPhoto")));
            c.content = Format.strOr(m, "content",
                    Format.strOr(m, "text", Format.str(m, "message")));
            c.imageUrl = Format.strOr(m, "imageUrl", Format.str(m, "mediaUrl"));
            c.parentId = Format.str(m, "parentId");
            c.edited = m != null && Boolean.TRUE.equals(m.get("edited"));
            c.pinned = m != null && Boolean.TRUE.equals(m.get("pinned"));
            c.likes = Format.intOf(m, "likes");
            c.createdAt = m == null ? 0L : Format.ts(m.get("createdAt"));
            c.likedBy = Format.listOf(m, "likedBy");
            Object rx = m == null ? null : m.get("reactions");
            if (rx instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) rx).entrySet()) {
                    if (e.getValue() != null)
                        c.reactions.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
            if (c.likedBy != null) {
                for (String uid : c.likedBy) {
                    if (uid != null && !uid.isEmpty() && !c.reactions.containsKey(uid)) {
                        c.reactions.put(uid, "like");
                    }
                }
            }
            c.likes = Math.max(c.likes, c.reactions.size());
            return c;
        }

        @SuppressWarnings("unchecked")
        public static List<Comment> parseEmbeddedComments(String parentDocId, Map<String, Object> docMap, String pinnedId) {
            List<Comment> out = new ArrayList<>();
            if (docMap == null) return out;
            Object cm = docMap.get("commentsMap");
            if (cm instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) cm).entrySet()) {
                    if (e.getKey() != null && e.getValue() instanceof Map) {
                        Map<String, Object> sub = new HashMap<>();
                        for (Map.Entry<?, ?> se : ((Map<?, ?>) e.getValue()).entrySet()) {
                            if (se.getKey() != null) sub.put(String.valueOf(se.getKey()), se.getValue());
                        }
                        Comment c = fromMap(String.valueOf(e.getKey()), sub);
                        if (c.postId == null || c.postId.isEmpty()) c.postId = parentDocId;
                        if (pinnedId != null && !pinnedId.isEmpty() && pinnedId.equals(c.id)) c.pinned = true;
                        out.add(c);
                    }
                }
            }
            Object cl = docMap.get("commentsList");
            if (cl instanceof List) {
                for (Object item : (List<?>) cl) {
                    if (item instanceof Map) {
                        Map<String, Object> sub = new HashMap<>();
                        for (Map.Entry<?, ?> se : ((Map<?, ?>) item).entrySet()) {
                            if (se.getKey() != null) sub.put(String.valueOf(se.getKey()), se.getValue());
                        }
                        Comment c = fromMap(Format.str(sub, "id"), sub);
                        if (c.postId == null || c.postId.isEmpty()) c.postId = parentDocId;
                        out.add(c);
                    }
                }
            }
            java.util.Collections.sort(out, (a, b) -> Long.compare(a.createdAt, b.createdAt));
            return out;
        }
    }

    public static class Story {
        public String id, authorId, authorName, authorPhoto, mediaUrl, mediaType, caption;
        public String musicMood, privacy;
        public int bgColor, fontStyle;
        public long createdAt, expiresAt;
        public List<String> viewedBy = new ArrayList<>();
        public Map<String, String> reactions = new HashMap<>();

        public boolean expired() { return System.currentTimeMillis() > expiresAt; }

        public static Story fromDoc(DocumentSnapshot d) {
            Story s = new Story();
            Map<String, Object> m = d.getData();
            s.id = d.getId();
            s.authorId = Format.str(m, "authorId");
            s.authorName = Format.strOr(m, "authorName", "User");
            s.authorPhoto = Format.str(m, "authorPhotoUrl");
            s.mediaUrl = Format.str(m, "mediaUrl");
            s.mediaType = Format.strOr(m, "mediaType", "image");
            s.caption = Format.str(m, "caption");
            s.musicMood = Format.str(m, "musicMood");
            s.privacy = Format.strOr(m, "privacy", "Public");
            s.bgColor = Format.intOf(m, "bgColor");
            s.fontStyle = Format.intOf(m, "fontStyle");
            s.createdAt = Format.ts(m == null ? null : m.get("createdAt"));
            long exp = Format.ts(m == null ? null : m.get("expiresAt"));
            s.expiresAt = exp > 0 ? exp : System.currentTimeMillis() + 86400_000L;
            s.viewedBy = Format.listOf(m, "viewedBy");
            Object r = m == null ? null : m.get("reactions");
            if (r instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) r).entrySet()) {
                    if (e.getValue() != null)
                        s.reactions.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
            return s;
        }
    }

    public static class Reel {
        public String id, authorId, authorName, authorPhoto, videoUrl, thumbUrl, caption;
        public int likes, comments, shares, durationSec;
        public long createdAt;
        public boolean isFromPost; // true when sourced from posts (mediaType == "video")
        public List<String> likedBy = new ArrayList<>();
        public List<String> savedBy = new ArrayList<>();
        public Map<String, String> reactions = new HashMap<>();
        public List<Comment> inlineComments = new ArrayList<>();

        public String collectionName() {
            return isFromPost ? "posts" : "reels";
        }

        public static Reel fromDoc(DocumentSnapshot d) {
            Reel r = new Reel();
            Map<String, Object> m = d.getData();
            r.id = d.getId();
            r.authorId = Format.str(m, "authorId");
            r.authorName = Format.strOr(m, "authorName", "User");
            r.authorPhoto = Format.str(m, "authorPhotoUrl");
            r.videoUrl = Format.strOr(m, "videoUrl", Format.str(m, "mediaUrl"));
            r.thumbUrl = Format.strOr(m, "thumbUrl", Format.str(m, "thumbnailUrl"));
            r.caption = Format.strOr(m, "caption", Format.str(m, "content"));
            r.likes = Format.intOf(m, "likes");
            r.comments = Format.intOf(m, "comments");
            r.shares = Format.intOf(m, "shares");
            r.durationSec = Format.intOf(m, "durationSec");
            r.createdAt = Format.ts(m == null ? null : m.get("createdAt"));
            r.likedBy = Format.listOf(m, "likedBy");
            r.savedBy = Format.listOf(m, "savedBy");
            Object rx = m == null ? null : m.get("reactions");
            if (rx instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) rx).entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) continue;
                    String uidKey = String.valueOf(e.getKey()).trim();
                    if (uidKey.isEmpty()) continue;
                    String norm = normalizeReactionType(e.getValue(), "love");
                    r.reactions.put(uidKey, norm);
                }
            }
            Object rxm = m == null ? null : m.get("reactionsMap");
            if (rxm instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) rxm).entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) continue;
                    String uidKey = String.valueOf(e.getKey()).trim();
                    if (uidKey.isEmpty() || r.reactions.containsKey(uidKey)) continue;
                    r.reactions.put(uidKey, normalizeReactionType(e.getValue(), "like"));
                }
            }
            for (String u : r.likedBy) {
                if (u != null && !u.trim().isEmpty() && !r.reactions.containsKey(u.trim())) {
                    r.reactions.put(u.trim(), "love");
                }
            }
            if (m != null && (m.containsKey("reactions") || m.containsKey("likedBy") || m.containsKey("reactionsMap"))) {
                r.likes = r.reactions.size();
            } else {
                r.likes = Math.max(0, r.likes);
            }
            r.inlineComments = Comment.parseEmbeddedComments(r.id, m, null);
            if (r.inlineComments.size() > r.comments) r.comments = r.inlineComments.size();
            return r;
        }

        public static Reel fromVideoPost(Post p) {
            Reel r = new Reel();
            r.id = p.id;
            r.isFromPost = true;
            r.authorId = p.authorId;
            r.authorName = p.authorName;
            r.authorPhoto = p.authorPhoto;
            r.videoUrl = p.mediaUrl;
            r.caption = p.content;
            r.likes = Math.max(0, p.likes);
            r.comments = p.comments;
            r.shares = p.shares;
            r.createdAt = p.createdAt;
            r.savedBy = p.savedBy != null ? new ArrayList<>(p.savedBy) : new ArrayList<>();
            r.inlineComments = p.inlineComments != null ? new ArrayList<>(p.inlineComments) : new ArrayList<>();
            if (p.reactionsMap != null) {
                for (Map.Entry<String, Map<String, Object>> e : p.reactionsMap.entrySet()) {
                    if (e.getKey() == null || e.getKey().trim().isEmpty()) continue;
                    String uidKey = e.getKey().trim();
                    String t = normalizeReactionType(e.getValue(), "like");
                    r.reactions.put(uidKey, t);
                    if (!r.likedBy.contains(uidKey)) r.likedBy.add(uidKey);
                }
                if (!r.reactions.isEmpty()) {
                    r.likes = Math.max(r.likes, r.reactions.size());
                }
            }
            return r;
        }
    }

    public static class Message {
        public String id, senderId, senderName, senderPhoto, receiverId, text, imageUrl, type, voiceUrl;
        public String replyToText, replyToSender;
        public String callType, callStatus;
        public int callDurationSec;
        public boolean forwarded;
        public int voiceDurationSec;
        public boolean isSeen, isDelivered, isDeleted;
        public long createdAt;
        public Map<String, String> reactions = new HashMap<>(); // uid → emoji

        @SuppressWarnings("unchecked")
        public static Message fromDoc(DocumentSnapshot d) {
            Message m0 = new Message();
            Map<String, Object> m = d.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE);
            m0.id = d.getId();
            m0.senderId = Format.str(m, "senderId");
            m0.senderName = Format.str(m, "senderName");
            m0.senderPhoto = Format.str(m, "senderPhoto");
            m0.receiverId = Format.str(m, "receiverId");
            m0.text = Format.str(m, "text");
            m0.imageUrl = Format.str(m, "imageUrl");
            m0.voiceUrl = Format.str(m, "voiceUrl");
            m0.replyToText = Format.str(m, "replyToText");
            m0.replyToSender = Format.str(m, "replyToSender");
            m0.callType = Format.str(m, "callType");
            m0.callStatus = Format.str(m, "callStatus");
            m0.callDurationSec = Format.intOf(m, "callDurationSec");
            m0.forwarded = Format.boolOf(m, "forwarded");
            m0.voiceDurationSec = Format.intOf(m, "voiceDurationSec");
            m0.type = Format.strOr(m, "type", "text");
            m0.isSeen = Format.boolOf(m, "isSeen");
            m0.isDelivered = Format.boolOf(m, "isDelivered");
            m0.isDeleted = Format.boolOf(m, "isDeleted");
            long ts = Format.ts(m == null ? null : m.get("createdAt"));
            if (ts <= 0 && m != null && m.get("createdAtMs") instanceof Number) {
                ts = ((Number) m.get("createdAtMs")).longValue();
            }
            m0.createdAt = ts > 0 ? ts : System.currentTimeMillis();
            m0.reactions = new HashMap<>();
            Object r = m == null ? null : m.get("reactions");
            if (r instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) r).entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof String || v instanceof Boolean)
                        m0.reactions.put(String.valueOf(e.getKey()),
                                v instanceof String ? (String) v : "❤️");
                }
            }
            return m0;
        }

        /** grouped display like "❤️2 👍1". */
        public String reactionSummary() {
            if (reactions.isEmpty()) return "";
            Map<String, Integer> agg = new HashMap<>();
            for (String e : reactions.values()) {
                Integer x = agg.get(e);
                agg.put(e, x == null ? 1 : x + 1);
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : agg.entrySet()) {
                sb.append(e.getKey());
                if (e.getValue() > 1) sb.append(e.getValue());
                sb.append(" ");
            }
            return sb.toString().trim();
        }
    }

    public static class ChatRoom {
        public String id, lastMessage;
        public long lastMessageTime;
        public boolean isGroup;
        public String groupName, groupPhoto, createdBy;
        public boolean onlyAdminsCanSend;
        public List<String> adminIds = new ArrayList<>();
        public List<String> participants = new ArrayList<>();
        public Map<String, Boolean> typing = new HashMap<>();
        public Map<String, String> participantNames = new HashMap<>();
        public Map<String, String> participantPhotos = new HashMap<>();

        public boolean isGroupAdmin(String uid) {
            if (uid == null || uid.isEmpty()) return false;
            return uid.equals(createdBy) || (adminIds != null && adminIds.contains(uid));
        }

        public String otherUid(String me) {
            if (isGroup) return id;
            for (String p : participants) if (p != null && !p.equals(me)) return p;
            return participants.isEmpty() ? "" : participants.get(0);
        }

        public static ChatRoom fromDoc(DocumentSnapshot d) {
            ChatRoom c = new ChatRoom();
            Map<String, Object> m = d.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE);
            c.id = d.getId();
            c.isGroup = Format.boolOf(m, "isGroup");
            c.groupName = Format.str(m, "groupName");
            c.groupPhoto = Format.str(m, "groupPhoto");
            c.createdBy = Format.str(m, "createdBy");
            c.onlyAdminsCanSend = Format.boolOf(m, "onlyAdminsCanSend");
            c.adminIds = Format.listOf(m, "adminIds");
            if (c.createdBy != null && !c.createdBy.isEmpty() && !c.adminIds.contains(c.createdBy)) {
                c.adminIds.add(c.createdBy);
            }
            c.participants = Format.listOf(m, "participants");
            c.lastMessage = Format.str(m, "lastMessage");
            long ts = Format.ts(m == null ? null : m.get("lastMessageTime"));
            if (ts <= 0 && m != null && m.get("lastMessageTimeMs") instanceof Number) {
                ts = ((Number) m.get("lastMessageTimeMs")).longValue();
            }
            c.lastMessageTime = ts;
            Object t = m == null ? null : m.get("typing");
            if (t instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) t).entrySet()) {
                    if (e.getValue() instanceof Boolean)
                        c.typing.put(String.valueOf(e.getKey()), (Boolean) e.getValue());
                }
            }
            Object pn = m == null ? null : m.get("participantNames");
            if (pn instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) pn).entrySet()) {
                    if (e.getKey() != null && e.getValue() != null)
                        c.participantNames.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
            Object pp = m == null ? null : m.get("participantPhotos");
            if (pp instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) pp).entrySet()) {
                    if (e.getKey() != null && e.getValue() != null)
                        c.participantPhotos.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
            return c;
        }
    }

    public static class Group {
        public String id, name, about, category, photoUrl, coverUrl, adminId, privacy, rules, pinnedPostId;
        public String postPermission = "Everyone"; // Everyone | Admins & Moderators
        public boolean requirePostApproval, requireJoinApproval;
        public long createdAt;
        public List<String> members = new ArrayList<>();
        public List<String> adminIds = new ArrayList<>();
        public List<String> moderatorIds = new ArrayList<>();
        public List<String> pendingMemberIds = new ArrayList<>();
        public List<String> bannedMemberIds = new ArrayList<>();
        public List<String> invitedUids = new ArrayList<>();

        public boolean isGroupAdmin(String uid) {
            if (uid == null || uid.isEmpty()) return false;
            return uid.equals(adminId) || (adminIds != null && adminIds.contains(uid));
        }

        public boolean isGroupModerator(String uid) {
            if (uid == null || uid.isEmpty()) return false;
            return moderatorIds != null && moderatorIds.contains(uid);
        }

        public boolean canManageGroup(String uid) {
            return isGroupAdmin(uid) || isGroupModerator(uid);
        }

        public static Group fromDoc(DocumentSnapshot d) {
            Group g = new Group();
            Map<String, Object> m = d.getData();
            g.id = d.getId();
            g.name = Format.strOr(m, "name", "Group");
            g.about = Format.str(m, "about");
            g.category = Format.strOr(m, "category", "Community");
            g.photoUrl = Format.str(m, "photoUrl");
            g.coverUrl = Format.str(m, "coverUrl");
            g.adminId = Format.str(m, "adminId");
            g.privacy = Format.strOr(m, "privacy", "Public");
            g.rules = Format.str(m, "rules");
            g.pinnedPostId = Format.str(m, "pinnedPostId");
            g.postPermission = Format.strOr(m, "postPermission", "Everyone");
            g.requirePostApproval = Format.boolOf(m, "requirePostApproval");
            g.requireJoinApproval = Format.boolOf(m, "requireJoinApproval")
                    || ("Private".equalsIgnoreCase(g.privacy) && (m == null || !m.containsKey("requireJoinApproval")));
            g.createdAt = Format.ts(m == null ? null : m.get("createdAt"));
            g.members = Format.listOf(m, "members");
            g.adminIds = Format.listOf(m, "adminIds");
            if (g.adminId != null && !g.adminId.isEmpty() && !g.adminIds.contains(g.adminId)) {
                g.adminIds.add(g.adminId);
            }
            g.moderatorIds = Format.listOf(m, "moderatorIds");
            g.pendingMemberIds = Format.listOf(m, "pendingMemberIds");
            g.bannedMemberIds = Format.listOf(m, "bannedMemberIds");
            g.invitedUids = Format.listOf(m, "invitedUids");
            return g;
        }
    }

    public static class FriendRequest {
        public String id, fromUid, fromName, fromPhoto, toUid, status;
        public long createdAt;

        public static FriendRequest fromDoc(DocumentSnapshot d) {
            FriendRequest r = new FriendRequest();
            Map<String, Object> m = d.getData();
            r.id = d.getId();
            r.fromUid = Format.str(m, "fromUid");
            r.fromName = Format.str(m, "fromUserName");
            r.fromPhoto = Format.str(m, "fromUserPhotoUrl");
            r.toUid = Format.str(m, "toUid");
            r.status = Format.strOr(m, "status", "pending");
            r.createdAt = Format.ts(m == null ? null : m.get("createdAt"));
            return r;
        }
    }

    public static class Notif {
        public String id, toUserId, fromUserId, fromUserName, fromUserPhoto, type, postId, groupId, text;
        public boolean isRead;
        public long createdAt;

        public static Notif fromDoc(DocumentSnapshot d) {
            Notif n = new Notif();
            Map<String, Object> m = d.getData();
            n.id = d.getId();
            n.toUserId = Format.str(m, "toUserId");
            n.fromUserId = Format.str(m, "fromUserId");
            n.fromUserName = Format.strOr(m, "fromUserName", "Someone");
            n.fromUserPhoto = Format.str(m, "fromUserPhotoUrl");
            n.type = Format.str(m, "type");
            n.postId = Format.str(m, "postId");
            n.groupId = Format.str(m, "groupId");
            n.text = Format.str(m, "text");
            n.isRead = Format.boolOf(m, "isRead");
            n.createdAt = Format.ts(m == null ? null : m.get("createdAt"));
            return n;
        }
    }
}
