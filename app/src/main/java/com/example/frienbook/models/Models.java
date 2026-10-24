package com.example.frienbook.models;

import com.example.frienbook.core.Format;
import com.google.firebase.firestore.DocumentSnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Firestore document models — matches the schema shared with the Flutter app. */
public class Models {

    public static class User {
        public String uid, displayName, email, photoUrl, bio, coverUrl, birthday;
        public boolean isOnline, isAdmin, banned, isVerified;
        public long lastSeen, createdAt;
        public int friendsCount;
        public int stars;     // Stars balance (monetization)
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
            u.isOnline = Format.boolOf(m, "isOnline");
            u.isAdmin = Format.boolOf(m, "isAdmin");
            u.banned = Format.boolOf(m, "banned");
            u.isVerified = Format.boolOf(m, "isVerified");
            u.lastSeen = Format.ts(m == null ? null : m.get("lastSeen"));
            u.createdAt = Format.ts(m == null ? null : m.get("createdAt"));
            u.friendsCount = Format.intOf(m, "friendsCount");
            u.stars = Format.intOf(m, "stars");
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
        public int likes, comments, shares;
        public int bgColor; // 0 = none, 1..N = Backgrounds.GRADIENTS index
        public long createdAt;
        public List<String> savedBy = new ArrayList<>();
        public List<String> mediaUrls = new ArrayList<>(); // multi-photo posts
        public List<String> pollOptions = new ArrayList<>(); // non-empty ⇒ poll post
        public Map<String, Long> pollVotes = new HashMap<>(); // uid → option index

        public boolean authorVerified; // copied from author's profile when the post is created

        public boolean isPoll() { return pollOptions != null && pollOptions.size() >= 2; }
        public boolean isMultiPhoto() { return mediaUrls != null && mediaUrls.size() > 1; }
        public boolean isBgPost() { return bgColor != 0; }

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
            p.bgColor = Format.intOf(m, "bgColor");
            p.likes = Format.intOf(m, "likes");
            p.comments = Format.intOf(m, "comments");
            p.shares = Format.intOf(m, "shares");
            p.createdAt = Format.ts(m == null ? null : m.get("createdAt"));
            p.authorVerified = Format.boolOf(m, "authorVerified");
            p.sharedFromId = Format.str(m, "sharedFromId");
            p.sharedFromAuthor = Format.str(m, "sharedFromAuthor");
            p.sharedFromContent = Format.str(m, "sharedFromContent");
            p.sharedFromMediaUrl = Format.str(m, "sharedFromMediaUrl");
            p.sharedFromMediaType = Format.str(m, "sharedFromMediaType");
            p.savedBy = Format.listOf(m, "savedBy");
            p.mediaUrls = Format.listOf(m, "mediaUrls");
            p.pollOptions = Format.listOf(m, "pollOptions");
            p.pollVotes = new HashMap<>();
            Object pv = m == null ? null : m.get("pollVotes");
            if (pv instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) pv).entrySet()) {
                    if (e.getValue() instanceof Number)
                        p.pollVotes.put(String.valueOf(e.getKey()), ((Number) e.getValue()).longValue());
                }
            }
            return p;
        }
    }

    public static class Comment {
        public String id, postId, authorId, authorName, authorPhoto, content, imageUrl, parentId;
        public int likes;
        public long createdAt;
        public List<String> likedBy = new ArrayList<>();

        public static Comment fromDoc(DocumentSnapshot d) {
            Comment c = new Comment();
            Map<String, Object> m = d.getData();
            c.id = d.getId();
            c.postId = Format.str(m, "postId");
            c.authorId = Format.str(m, "authorId");
            c.authorName = Format.strOr(m, "authorName", "User");
            c.authorPhoto = Format.str(m, "authorPhotoUrl");
            c.content = Format.str(m, "content");
            c.imageUrl = Format.str(m, "imageUrl");
            c.parentId = Format.str(m, "parentId");
            c.likes = Format.intOf(m, "likes");
            c.createdAt = Format.ts(m == null ? null : m.get("createdAt"));
            c.likedBy = Format.listOf(m, "likedBy");
            return c;
        }
    }

    public static class Story {
        public String id, authorId, authorName, authorPhoto, mediaUrl, mediaType, caption;
        public long createdAt, expiresAt;
        public List<String> viewedBy = new ArrayList<>();

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
            s.createdAt = Format.ts(m == null ? null : m.get("createdAt"));
            long exp = Format.ts(m == null ? null : m.get("expiresAt"));
            s.expiresAt = exp > 0 ? exp : System.currentTimeMillis() + 86400_000L;
            s.viewedBy = Format.listOf(m, "viewedBy");
            return s;
        }
    }

    public static class Reel {
        public String id, authorId, authorName, authorPhoto, videoUrl, caption;
        public int likes, comments, shares;
        public long createdAt;
        public List<String> likedBy = new ArrayList<>();

        public static Reel fromDoc(DocumentSnapshot d) {
            Reel r = new Reel();
            Map<String, Object> m = d.getData();
            r.id = d.getId();
            r.authorId = Format.str(m, "authorId");
            r.authorName = Format.strOr(m, "authorName", "User");
            r.authorPhoto = Format.str(m, "authorPhotoUrl");
            r.videoUrl = Format.str(m, "videoUrl");
            r.caption = Format.str(m, "caption");
            r.likes = Format.intOf(m, "likes");
            r.comments = Format.intOf(m, "comments");
            r.shares = Format.intOf(m, "shares");
            r.createdAt = Format.ts(m == null ? null : m.get("createdAt"));
            r.likedBy = Format.listOf(m, "likedBy");
            return r;
        }
    }

    public static class Message {
        public String id, senderId, receiverId, text, imageUrl, type, voiceUrl;
        public int voiceDurationSec;
        public boolean isSeen, isDelivered, isDeleted;
        public long createdAt;
        public Map<String, String> reactions = new HashMap<>(); // uid → emoji

        @SuppressWarnings("unchecked")
        public static Message fromDoc(DocumentSnapshot d) {
            Message m0 = new Message();
            Map<String, Object> m = d.getData();
            m0.id = d.getId();
            m0.senderId = Format.str(m, "senderId");
            m0.receiverId = Format.str(m, "receiverId");
            m0.text = Format.str(m, "text");
            m0.imageUrl = Format.str(m, "imageUrl");
            m0.voiceUrl = Format.str(m, "voiceUrl");
            m0.voiceDurationSec = Format.intOf(m, "voiceDurationSec");
            m0.type = Format.strOr(m, "type", "text");
            m0.isSeen = Format.boolOf(m, "isSeen");
            m0.isDelivered = Format.boolOf(m, "isDelivered");
            m0.isDeleted = Format.boolOf(m, "isDeleted");
            m0.createdAt = Format.ts(m == null ? null : m.get("createdAt"));
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
        public List<String> participants = new ArrayList<>();
        public Map<String, Boolean> typing = new HashMap<>();

        public String otherUid(String me) {
            for (String p : participants) if (!p.equals(me)) return p;
            return participants.isEmpty() ? "" : participants.get(0);
        }

        public static ChatRoom fromDoc(DocumentSnapshot d) {
            ChatRoom c = new ChatRoom();
            Map<String, Object> m = d.getData();
            c.id = d.getId();
            c.participants = Format.listOf(m, "participants");
            c.lastMessage = Format.str(m, "lastMessage");
            c.lastMessageTime = Format.ts(m == null ? null : m.get("lastMessageTime"));
            Object t = m == null ? null : m.get("typing");
            if (t instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) t).entrySet()) {
                    if (e.getValue() instanceof Boolean)
                        c.typing.put(String.valueOf(e.getKey()), (Boolean) e.getValue());
                }
            }
            return c;
        }
    }

    public static class Group {
        public String id, name, about, photoUrl, adminId;
        public long createdAt;
        public List<String> members = new ArrayList<>();

        public static Group fromDoc(DocumentSnapshot d) {
            Group g = new Group();
            Map<String, Object> m = d.getData();
            g.id = d.getId();
            g.name = Format.strOr(m, "name", "Group");
            g.about = Format.str(m, "about");
            g.photoUrl = Format.str(m, "photoUrl");
            g.adminId = Format.str(m, "adminId");
            g.createdAt = Format.ts(m == null ? null : m.get("createdAt"));
            g.members = Format.listOf(m, "members");
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
        public String id, toUserId, fromUserId, fromUserName, fromUserPhoto, type, postId, text;
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
            n.text = Format.str(m, "text");
            n.isRead = Format.boolOf(m, "isRead");
            n.createdAt = Format.ts(m == null ? null : m.get("createdAt"));
            return n;
        }
    }
}
