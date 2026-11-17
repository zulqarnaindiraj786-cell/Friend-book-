package com.friendbook.social.core;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.friendbook.social.App;
import com.friendbook.social.BuildConfig;
import com.friendbook.social.models.Models;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cloudflare API Client - Replaces Firestore/RTDB calls with Workers API
 * All data operations go through Cloudflare Workers + D1
 * Auth, FCM, Crashlytics, Analytics, Remote Config, App Check remain on Firebase
 */
public final class CfClient {
    private static final String TAG = "CfClient";
    private static final String BASE_URL = "https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev/v1";
    /** Pooled so unrelated worker calls do not serialize behind each other. */
    private static final ExecutorService IO = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long REQUEST_TIMEOUT_MS = 30000;
    private static final long UPLOAD_TIMEOUT_MS = 300000;

    private CfClient() {}

    public static boolean enabled() {
        return BuildConfig.CLOUDFLARE_DATA_ENABLED;
    }

    /** Get current user's ID token for authenticated requests (cached via AuthToken). */
    private static Task<String> getIdToken() {
        TaskCompletionSource<String> tcs = new TaskCompletionSource<>();
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            tcs.setException(new IllegalStateException("Not signed in"));
            return tcs.getTask();
        }
        AuthToken.with(user, tcs::trySetResult,
                () -> tcs.trySetException(new IllegalStateException("Failed to get ID token")));
        return tcs.getTask();
    }

    /** Execute authenticated HTTP request */
    private static Task<JSONObject> request(String method, String path, JSONObject body, Map<String, String> queryParams) {
        TaskCompletionSource<JSONObject> tcs = new TaskCompletionSource<>();

        if (!enabled()) {
            tcs.setException(new IllegalStateException("Cloudflare data API disabled"));
            return tcs.getTask();
        }

        getIdToken().addOnSuccessListener(token -> {
            IO.execute(() -> {
                HttpURLConnection conn = null;
                try {
                    StringBuilder urlBuilder = new StringBuilder(BASE_URL).append(path);
                    if (queryParams != null && !queryParams.isEmpty()) {
                        urlBuilder.append('?');
                        boolean first = true;
                        for (Map.Entry<String, String> e : queryParams.entrySet()) {
                            if (!first) urlBuilder.append('&');
                            first = false;
                            urlBuilder.append(Uri.encode(e.getKey())).append('=').append(Uri.encode(e.getValue()));
                        }
                    }

                    URL url = new URL(urlBuilder.toString());
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout((int) REQUEST_TIMEOUT_MS);
                    conn.setInstanceFollowRedirects(false);
                    conn.setRequestMethod(method);
                    conn.setRequestProperty("Authorization", "Bearer " + token);
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setRequestProperty("Content-Type", "application/json");

                    if (body != null && (method.equals("POST") || method.equals("PUT") || method.equals("PATCH"))) {
                        conn.setDoOutput(true);
                        byte[] bytes = body.toString().getBytes("UTF-8");
                        conn.setFixedLengthStreamingMode(bytes.length);
                        try (OutputStream out = new BufferedOutputStream(conn.getOutputStream())) {
                            out.write(bytes);
                        }
                    }

                    int status = conn.getResponseCode();
                    InputStream inputStream = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
                    String responseBody = inputStream != null ? readAll(inputStream) : "";

                    if (status >= 200 && status < 300) {
                        JSONObject response = responseBody.isEmpty() ? new JSONObject() : new JSONObject(responseBody);
                        MAIN.post(() -> tcs.setResult(response));
                    } else {
                        String errorMsg = "HTTP " + status;
                        try {
                            JSONObject errJson = new JSONObject(responseBody);
                            errorMsg = errJson.optString("error", errorMsg);
                        } catch (JSONException ignored) {}
                        final String finalError = errorMsg;
                        MAIN.post(() -> tcs.setException(new IOException(finalError)));
                    }
                } catch (Exception e) {
                    MAIN.post(() -> tcs.setException(e));
                } finally {
                    if (conn != null) conn.disconnect();
                }
            });
        }).addOnFailureListener(tcs::setException);

        return tcs.getTask();
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
        return out.toString("UTF-8");
    }

    // ============ USER API ============

    public static Task<Models.User> getMe() {
        return request("GET", "/me", null, null).onSuccessTask(task -> {
            try {
                Models.User user = parseUser(task);
                FastCache.saveMe(App.instance, user);
                return Tasks.forResult(user);
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<Models.User> updateMe(Map<String, Object> fields) {
        return request("PUT", "/me", toJson(fields), null).onSuccessTask(task -> {
            try {
                Models.User user = parseUser(task);
                FastCache.saveMe(App.instance, user);
                return Tasks.forResult(user);
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<Models.User> getUser(String uid) {
        if (uid == null || uid.isEmpty()) return Tasks.forResult(null);
        return request("GET", "/users/" + Uri.encode(uid), null, null).onSuccessTask(task -> {
            try {
                return Tasks.forResult(parseUser(task));
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<List<Models.User>> searchUsers(String query, int limit) {
        Map<String, String> params = new HashMap<>();
        params.put("q", query);
        params.put("limit", String.valueOf(limit));
        return request("GET", "/users/search", null, params).onSuccessTask(task -> {
            try {
                JSONArray arr = task.optJSONArray("results");
                List<Models.User> users = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        users.add(parseUser(arr.getJSONObject(i)));
                    }
                }
                return Tasks.forResult(users);
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<List<Models.User>> getFriends() {
        return request("GET", "/users/friends", null, null).onSuccessTask(task -> {
            try {
                JSONArray arr = task.optJSONArray("results");
                List<Models.User> users = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        users.add(parseUser(arr.getJSONObject(i)));
                    }
                }
                return Tasks.forResult(users);
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<List<Models.User>> getBlocked() {
        return request("GET", "/users/blocked", null, null).onSuccessTask(task -> {
            try {
                JSONArray arr = task.optJSONArray("results");
                List<Models.User> users = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        users.add(parseUser(arr.getJSONObject(i)));
                    }
                }
                return Tasks.forResult(users);
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<Void> blockUser(String targetUid) {
        return request("POST", "/users/block", object("targetUid", targetUid), null).continueWith(t -> null);
    }

    public static Task<Void> unblockUser(String targetUid) {
        return request("POST", "/users/unblock", object("targetUid", targetUid), null).continueWith(t -> null);
    }

    public static Task<Void> followUser(String targetUid) {
        return request("POST", "/users/follow", object("targetUid", targetUid), null).continueWith(t -> null);
    }

    public static Task<Void> unfollowUser(String targetUid) {
        return request("POST", "/users/unfollow", object("targetUid", targetUid), null).continueWith(t -> null);
    }

    // ============ POST API ============

    public static Task<String> createPost(Map<String, Object> postData) {
        return request("POST", "/posts", toJson(postData), null).onSuccessTask(task -> {
            try {
                return Tasks.forResult(task.optString("postId"));
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<Models.Post> getPost(String postId) {
        return request("GET", "/posts/" + Uri.encode(postId), null, null).onSuccessTask(task -> {
            try {
                return Tasks.forResult(parsePost(task));
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<Void> updatePost(String postId, Map<String, Object> fields) {
        return request("PUT", "/posts/" + Uri.encode(postId), toJson(fields), null).continueWith(t -> null);
    }

    public static Task<Void> deletePost(String postId) {
        return request("DELETE", "/posts/" + Uri.encode(postId), null, null).continueWith(t -> null);
    }

    public static Task<List<Map<String, Object>>> getPostReactions(String postId) {
        return request("GET", "/posts/" + Uri.encode(postId) + "/reactions", null, null).onSuccessTask(task -> {
            try {
                JSONObject reactions = task.optJSONObject("reactions");
                List<Map<String, Object>> list = new ArrayList<>();
                if (reactions != null) {
                    Iterator<String> keys = reactions.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        Map<String, Object> r = new HashMap<>();
                        r.put("userId", key);
                        Object v = reactions.get(key);
                        if (v instanceof JSONObject) {
                            Iterator<String> subKeys = ((JSONObject) v).keys();
                            while (subKeys.hasNext()) {
                                String k = subKeys.next();
                                r.put(k, ((JSONObject) v).opt(k));
                            }
                        }
                        list.add(r);
                    }
                }
                return Tasks.forResult(list);
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<Map<String, Object>> addReaction(String postId, String type) {
        return request("POST", "/posts/" + Uri.encode(postId) + "/reactions", object("type", type), null).onSuccessTask(task -> {
            try {
                return Tasks.forResult(D1Client.map(task));
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<Void> removeReaction(String postId) {
        return request("DELETE", "/posts/" + Uri.encode(postId) + "/reactions", null, null).continueWith(t -> null);
    }

    public static Task<List<Models.Comment>> getComments(String postId) {
        return request("GET", "/posts/" + Uri.encode(postId) + "/comments", null, null).onSuccessTask(task -> {
            try {
                JSONArray arr = task.optJSONArray("comments");
                List<Models.Comment> comments = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        comments.add(parseComment(arr.getJSONObject(i)));
                    }
                }
                return Tasks.forResult(comments);
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<Models.Comment> addComment(String postId, Map<String, Object> commentData) {
        return request("POST", "/posts/" + Uri.encode(postId) + "/comments", toJson(commentData), null).onSuccessTask(task -> {
            try {
                return Tasks.forResult(parseComment(task));
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<Void> updateComment(String postId, String commentId, Map<String, Object> fields) {
        Map<String, String> params = new HashMap<>();
        params.put("commentId", commentId);
        return request("PUT", "/posts/" + Uri.encode(postId) + "/comment", toJson(fields), params).continueWith(t -> null);
    }

    public static Task<Void> deleteComment(String postId, String commentId) {
        Map<String, String> params = new HashMap<>();
        params.put("commentId", commentId);
        return request("DELETE", "/posts/" + Uri.encode(postId) + "/comment", null, params).continueWith(t -> null);
    }

    public static Task<String> sharePost(String postId, Map<String, Object> shareData) {
        return request("POST", "/posts/" + Uri.encode(postId) + "/share", toJson(shareData), null).onSuccessTask(task -> {
            try {
                return Tasks.forResult(task.optString("postId"));
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<Void> savePost(String postId) {
        return request("POST", "/posts/" + Uri.encode(postId) + "/save", null, null).continueWith(t -> null);
    }

    public static Task<Void> unsavePost(String postId) {
        return request("POST", "/posts/" + Uri.encode(postId) + "/unsave", null, null).continueWith(t -> null);
    }

    public static Task<Void> recordView(String postId) {
        return request("POST", "/posts/" + Uri.encode(postId) + "/view", null, null).continueWith(t -> null);
    }

    public static Task<Map<String, Object>> getFeed(String cursor, int limit, String type, String groupId) {
        Map<String, String> params = new HashMap<>();
        params.put("limit", String.valueOf(limit));
        params.put("type", type);
        if (cursor != null) params.put("cursor", cursor);
        if (groupId != null) params.put("groupId", groupId);
        return request("GET", "/posts/feed", null, params).onSuccessTask(task -> {
            try {
                return Tasks.forResult(D1Client.map(task));
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    // ============ CHAT API ============

    public static Task<String> createChat(Map<String, Object> chatData) {
        return request("POST", "/chats", toJson(chatData), null).onSuccessTask(task -> {
            try {
                return Tasks.forResult(task.optString("chatId"));
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<Models.ChatRoom> getChat(String chatId) {
        return request("GET", "/chats/" + Uri.encode(chatId), null, null).onSuccessTask(task -> {
            try {
                return Tasks.forResult(parseChatRoom(task));
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<Void> updateChat(String chatId, Map<String, Object> fields) {
        return request("PUT", "/chats/" + Uri.encode(chatId), toJson(fields), null).continueWith(t -> null);
    }

    public static Task<Map<String, Object>> getMessages(String chatId, String cursor, int limit) {
        Map<String, String> params = new HashMap<>();
        params.put("limit", String.valueOf(limit));
        if (cursor != null) params.put("cursor", cursor);
        return request("GET", "/chats/" + Uri.encode(chatId) + "/messages", null, params).onSuccessTask(task -> {
            try {
                return Tasks.forResult(D1Client.map(task));
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<Models.Message> sendMessage(String chatId, Map<String, Object> messageData) {
        return request("POST", "/chats/" + Uri.encode(chatId) + "/messages", toJson(messageData), null).onSuccessTask(task -> {
            try {
                return Tasks.forResult(parseMessage(task));
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<Void> updateMessage(String chatId, String messageId, Map<String, Object> fields) {
        Map<String, String> params = new HashMap<>();
        params.put("messageId", messageId);
        return request("PUT", "/chats/" + Uri.encode(chatId) + "/message", toJson(fields), params).continueWith(t -> null);
    }

    public static Task<Void> deleteMessage(String chatId, String messageId) {
        Map<String, String> params = new HashMap<>();
        params.put("messageId", messageId);
        return request("DELETE", "/chats/" + Uri.encode(chatId) + "/message", null, params).continueWith(t -> null);
    }

    public static Task<Void> setTyping(String chatId, boolean typing) {
        return request("PUT", "/chats/" + Uri.encode(chatId) + "/typing", object("typing", typing), null).continueWith(t -> null);
    }

    public static Task<Void> markRead(String chatId, String messageId) {
        return request("POST", "/chats/" + Uri.encode(chatId) + "/read", object("messageId", messageId), null).continueWith(t -> null);
    }

    public static Task<List<Models.User>> getParticipants(String chatId) {
        return request("GET", "/chats/" + Uri.encode(chatId) + "/participants", null, null).onSuccessTask(task -> {
            try {
                JSONArray arr = task.optJSONArray("participants");
                List<Models.User> users = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        users.add(parseUser(arr.getJSONObject(i)));
                    }
                }
                return Tasks.forResult(users);
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<Void> addParticipant(String chatId, String userId) {
        return request("POST", "/chats/" + Uri.encode(chatId) + "/participants", object("userId", userId), null).continueWith(t -> null);
    }

    public static Task<Void> removeParticipant(String chatId, String userId) {
        Map<String, String> params = new HashMap<>();
        params.put("userId", userId);
        return request("DELETE", "/chats/" + Uri.encode(chatId) + "/participants", null, params).continueWith(t -> null);
    }

    public static Task<Void> addAdmin(String chatId, String userId) {
        return request("POST", "/chats/" + Uri.encode(chatId) + "/admins", object("userId", userId), null).continueWith(t -> null);
    }

    public static Task<Void> removeAdmin(String chatId, String userId) {
        Map<String, String> params = new HashMap<>();
        params.put("userId", userId);
        return request("DELETE", "/chats/" + Uri.encode(chatId) + "/admins", null, params).continueWith(t -> null);
    }

    // ============ GROUP API ============

    public static Task<String> createGroup(Map<String, Object> groupData) {
        return request("POST", "/groups", toJson(groupData), null).onSuccessTask(task -> {
            try {
                return Tasks.forResult(task.optString("id"));
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<Models.Group> getGroup(String groupId) {
        return request("GET", "/groups/" + Uri.encode(groupId), null, null).onSuccessTask(task -> {
            try {
                return Tasks.forResult(parseGroup(task));
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<Void> updateGroup(String groupId, Map<String, Object> fields) {
        return request("PUT", "/groups/" + Uri.encode(groupId), toJson(fields), null).continueWith(t -> null);
    }

    public static Task<Void> joinGroup(String groupId) {
        return request("POST", "/groups/" + Uri.encode(groupId) + "/join", null, null).continueWith(t -> null);
    }

    public static Task<Void> leaveGroup(String groupId) {
        return request("POST", "/groups/" + Uri.encode(groupId) + "/leave", null, null).continueWith(t -> null);
    }

    public static Task<List<Models.User>> getGroupMembers(String groupId) {
        return request("GET", "/groups/" + Uri.encode(groupId) + "/members", null, null).onSuccessTask(task -> {
            try {
                JSONArray arr = task.optJSONArray("members");
                List<Models.User> users = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        users.add(parseUser(arr.getJSONObject(i)));
                    }
                }
                return Tasks.forResult(users);
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<List<Models.User>> getPendingMembers(String groupId) {
        return request("GET", "/groups/" + Uri.encode(groupId) + "/pending", null, null).onSuccessTask(task -> {
            try {
                JSONArray arr = task.optJSONArray("members");
                List<Models.User> users = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        users.add(parseUser(arr.getJSONObject(i)));
                    }
                }
                return Tasks.forResult(users);
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<Void> approveMember(String groupId, String userId) {
        return request("POST", "/groups/" + Uri.encode(groupId) + "/approve", object("userId", userId), null).continueWith(t -> null);
    }

    public static Task<Map<String, Object>> getGroupPosts(String groupId, String cursor, int limit) {
        Map<String, String> params = new HashMap<>();
        params.put("limit", String.valueOf(limit));
        if (cursor != null) params.put("cursor", cursor);
        return request("GET", "/groups/" + Uri.encode(groupId) + "/posts", null, params).onSuccessTask(task -> {
            try {
                return Tasks.forResult(D1Client.map(task));
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    // ============ FRIEND REQUEST API ============

    public static Task<List<Map<String, Object>>> getFriendRequests(String status) {
        Map<String, String> params = new HashMap<>();
        params.put("status", status);
        return request("GET", "/friend-requests", null, params).onSuccessTask(task -> {
            try {
                JSONArray arr = task.optJSONArray("requests");
                List<Map<String, Object>> list = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        list.add(D1Client.map(arr.getJSONObject(i)));
                    }
                }
                return Tasks.forResult(list);
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<String> sendFriendRequest(String toUid, String fromName, String fromPhoto) {
        return request("POST", "/friend-requests", object("toUid", toUid, "fromName", fromName, "fromPhoto", fromPhoto), null).onSuccessTask(task -> {
            try {
                return Tasks.forResult(task.optString("requestId"));
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<Void> acceptFriendRequest(String requestId) {
        Map<String, String> params = new HashMap<>();
        params.put("action", "accept");
        return request("POST", "/friend-requests/" + Uri.encode(requestId), null, params).continueWith(t -> null);
    }

    public static Task<Void> declineFriendRequest(String requestId) {
        Map<String, String> params = new HashMap<>();
        params.put("action", "decline");
        return request("POST", "/friend-requests/" + Uri.encode(requestId), null, params).continueWith(t -> null);
    }

    public static Task<Void> cancelFriendRequest(String requestId) {
        Map<String, String> params = new HashMap<>();
        params.put("action", "cancel");
        return request("POST", "/friend-requests/" + Uri.encode(requestId), null, params).continueWith(t -> null);
    }

    // ============ NOTIFICATION API ============

    public static Task<Map<String, Object>> getNotifications(String cursor, int limit, boolean unreadOnly) {
        Map<String, String> params = new HashMap<>();
        params.put("limit", String.valueOf(limit));
        if (unreadOnly) params.put("unread", "true");
        if (cursor != null) params.put("cursor", cursor);
        return request("GET", "/notifications", null, params).onSuccessTask(task -> {
            try {
                return Tasks.forResult(D1Client.map(task));
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<Void> markNotificationsRead(List<String> notificationIds) {
        return request("POST", "/notifications/read", object("notificationIds", notificationIds), null).continueWith(t -> null);
    }

    public static Task<Void> markAllNotificationsRead() {
        return request("POST", "/notifications/read-all", null, null).continueWith(t -> null);
    }

    // ============ STORY API ============

    public static Task<Models.Story> createStory(Map<String, Object> storyData) {
        return request("POST", "/stories", toJson(storyData), null).onSuccessTask(task -> {
            try {
                return Tasks.forResult(parseStory(task));
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<Map<String, Object>> getStoriesFeed() {
        return request("GET", "/stories/feed", null, null).onSuccessTask(task -> {
            try {
                return Tasks.forResult(D1Client.map(task));
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<Models.Story> getStory(String storyId) {
        return request("GET", "/stories/" + Uri.encode(storyId), null, null).onSuccessTask(task -> {
            try {
                return Tasks.forResult(parseStory(task));
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<Void> deleteStory(String storyId) {
        return request("DELETE", "/stories/" + Uri.encode(storyId), null, null).continueWith(t -> null);
    }

    public static Task<Void> viewStory(String storyId) {
        return request("POST", "/stories/" + Uri.encode(storyId), object("action", "view"), null).continueWith(t -> null);
    }

    public static Task<Void> reactStory(String storyId, String type) {
        return request("POST", "/stories/" + Uri.encode(storyId), object("action", "react", "type", type), null).continueWith(t -> null);
    }

    // ============ REEL API ============

    public static Task<Models.Reel> createReel(Map<String, Object> reelData) {
        return request("POST", "/reels", toJson(reelData), null).onSuccessTask(task -> {
            try {
                return Tasks.forResult(parseReel(task));
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<Map<String, Object>> getReelsFeed(String cursor, int limit) {
        Map<String, String> params = new HashMap<>();
        params.put("limit", String.valueOf(limit));
        if (cursor != null) params.put("cursor", cursor);
        return request("GET", "/reels/feed", null, params).onSuccessTask(task -> {
            try {
                return Tasks.forResult(D1Client.map(task));
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<Models.Reel> getReel(String reelId) {
        return request("GET", "/reels/" + Uri.encode(reelId), null, null).onSuccessTask(task -> {
            try {
                return Tasks.forResult(parseReel(task));
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<Void> updateReel(String reelId, Map<String, Object> fields) {
        return request("PUT", "/reels/" + Uri.encode(reelId), toJson(fields), null).continueWith(t -> null);
    }

    public static Task<Void> deleteReel(String reelId) {
        return request("DELETE", "/reels/" + Uri.encode(reelId), null, null).continueWith(t -> null);
    }

    public static Task<List<Map<String, Object>>> getReelReactions(String reelId) {
        return request("GET", "/reels/" + Uri.encode(reelId) + "/reactions", null, null).onSuccessTask(task -> {
            try {
                JSONObject reactions = task.optJSONObject("reactions");
                List<Map<String, Object>> list = new ArrayList<>();
                if (reactions != null) {
                    Iterator<String> keys = reactions.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        Map<String, Object> r = new HashMap<>();
                        r.put("userId", key);
                        r.put("type", reactions.optString(key));
                        list.add(r);
                    }
                }
                return Tasks.forResult(list);
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<Void> addReelReaction(String reelId, String type) {
        return request("POST", "/reels/" + Uri.encode(reelId) + "/reactions", object("type", type), null).continueWith(t -> null);
    }

    public static Task<Void> removeReelReaction(String reelId) {
        return request("DELETE", "/reels/" + Uri.encode(reelId) + "/reactions", null, null).continueWith(t -> null);
    }

    public static Task<List<Models.Comment>> getReelComments(String reelId) {
        return request("GET", "/reels/" + Uri.encode(reelId) + "/comments", null, null).onSuccessTask(task -> {
            try {
                JSONArray arr = task.optJSONArray("comments");
                List<Models.Comment> comments = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        comments.add(parseComment(arr.getJSONObject(i)));
                    }
                }
                return Tasks.forResult(comments);
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<Models.Comment> addReelComment(String reelId, Map<String, Object> commentData) {
        return request("POST", "/reels/" + Uri.encode(reelId) + "/comments", toJson(commentData), null).onSuccessTask(task -> {
            try {
                return Tasks.forResult(parseComment(task));
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<Void> saveReel(String reelId) {
        return request("POST", "/reels/" + Uri.encode(reelId) + "/save", null, null).continueWith(t -> null);
    }

    public static Task<Void> unsaveReel(String reelId) {
        return request("POST", "/reels/" + Uri.encode(reelId) + "/unsave", null, null).continueWith(t -> null);
    }

    public static Task<Void> recordReelView(String reelId) {
        return request("POST", "/reels/" + Uri.encode(reelId) + "/view", null, null).continueWith(t -> null);
    }

    // ============ CALL API (WebRTC Signaling) ============

    public static Task<Void> sendCallOffer(String chatId, Map<String, Object> offer) {
        return request("POST", "/calls/offer", object("chatId", chatId, "offer", offer), null).continueWith(t -> null);
    }

    public static Task<Void> sendCallAnswer(String chatId, Map<String, Object> answer) {
        return request("POST", "/calls/answer", object("chatId", chatId, "answer", answer), null).continueWith(t -> null);
    }

    public static Task<Void> sendIceCandidate(String chatId, Map<String, Object> candidate) {
        return request("POST", "/calls/ice", object("chatId", chatId, "candidate", candidate), null).continueWith(t -> null);
    }

    public static Task<Void> endCall(String chatId) {
        return request("POST", "/calls/end", object("chatId", chatId), null).continueWith(t -> null);
    }

    public static Task<Map<String, Object>> getCallState(String chatId) {
        Map<String, String> params = new HashMap<>();
        params.put("chatId", chatId);
        return request("GET", "/calls/state", null, params).onSuccessTask(task -> {
            try {
                return Tasks.forResult(D1Client.map(task));
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    // ============ SETTINGS API ============

    public static Task<Map<String, Object>> getSettings() {
        return request("GET", "/settings", null, null).onSuccessTask(task -> {
            try {
                return Tasks.forResult(D1Client.map(task));
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public static Task<Void> updateSettings(Map<String, Object> settings) {
        return request("PUT", "/settings", toJson(settings), null).continueWith(t -> null);
    }

    // ============ UPLOAD API (R2) ============

    public static Task<String> uploadPhoto(byte[] data, String mime) {
        return uploadToR2("/v1/photos", data, mime);
    }

    public static Task<String> uploadVideo(byte[] data, String mime) {
        return uploadToR2("/v1/videos", data, mime);
    }

    private static Task<String> uploadToR2(String path, byte[] data, String mime) {
        TaskCompletionSource<String> tcs = new TaskCompletionSource<>();
        IO.execute(() -> {
            HttpURLConnection conn = null;
            try {
                FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                if (user == null) throw new IllegalStateException("Not signed in");
                
                String token = Tasks.await(user.getIdToken(false), 20, java.util.concurrent.TimeUnit.SECONDS).getToken();
                if (token == null) throw new IllegalStateException("Missing auth token");

                URL url = new URL(BASE_URL + path);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(20000);
                conn.setReadTimeout((int) UPLOAD_TIMEOUT_MS);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(data.length);
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setRequestProperty("Content-Type", mime);

                try (OutputStream out = new BufferedOutputStream(conn.getOutputStream())) {
                    out.write(data);
                }

                int code = conn.getResponseCode();
                InputStream rs = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                StringBuilder sb = new StringBuilder();
                if (rs != null) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(rs))) {
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                    }
                }

                if (code >= 200 && code < 300) {
                    JSONObject json = new JSONObject(sb.toString());
                    String r2Url = json.optString("url", "");
                    MAIN.post(() -> tcs.setResult(r2Url));
                } else {
                    MAIN.post(() -> tcs.setException(new IOException("Upload failed: " + code + " " + sb)));
                }
            } catch (Exception e) {
                MAIN.post(() -> tcs.setException(e));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
        return tcs.getTask();
    }

    // ============ PARSERS ============

    private static Models.User parseUser(JSONObject json) throws JSONException {
        Models.User u = new Models.User();
        u.uid = json.optString("uid", "");
        u.displayName = json.optString("display_name", "User");
        u.email = json.optString("email", "");
        u.photoUrl = json.optString("photo_url", "");
        u.bio = json.optString("bio", "");
        u.coverUrl = json.optString("cover_url", "");
        u.birthday = json.optString("birthday", "");
        u.work = json.optString("work", "");
        u.education = json.optString("education", "");
        u.currentCity = json.optString("current_city", "");
        u.hometown = json.optString("hometown", "");
        u.relationshipStatus = json.optString("relationship_status", "");
        u.relationship = json.optString("relationship", "");
        u.website = json.optString("website", "");
        u.pronouns = json.optString("pronouns", "");
        u.phone = json.optString("phone", "");
        u.phoneNumber = json.optString("phone_number", "");
        u.isAdmin = json.optInt("is_admin", 0) == 1;
        u.isModerator = json.optInt("is_moderator", 0) == 1;
        u.banned = json.optInt("banned", 0) == 1;
        u.isVerified = json.optInt("is_verified", 0) == 1;
        u.suspendedUntil = json.optLong("suspended_until", 0);
        u.suspendedLifetime = json.optInt("suspended_lifetime", 0) == 1;
        u.deactivated = json.optInt("deactivated", 0) == 1;
        u.loginAlerts = json.optInt("login_alerts", 0) == 1;
        u.tagReviewEnabled = json.optInt("tag_review_enabled", 0) == 1;
        u.professionalMode = json.optInt("professional_mode", 0) == 1;
        u.hideReactionCounts = json.optInt("hide_reaction_counts", 0) == 1;
        u.showActiveStatus = json.optInt("show_active_status", 1) == 1;
        u.lastSeen = json.optLong("last_seen", 0);
        u.createdAt = json.optLong("created_at", 0);
        u.friendsCount = json.optInt("friends_count", 0);
        u.stars = json.optInt("stars", 0);
        u.tickLevel = json.optString("tick_level", "none");
        u.subscriptionsEnabled = json.optInt("subscriptions_enabled", 0) == 1;
        u.subscriptionPrice = json.optInt("subscription_price", 0);
        u.profileLocked = json.optInt("profile_locked", 0) == 1;
        u.profilePicGuard = json.optInt("profile_pic_guard", 0) == 1;
        u.lookupPrivacy = json.optString("lookup_privacy", "Everyone");
        u.friendsListPrivacy = json.optString("friends_list_privacy", "Public");
        u.contactPrivacy = json.optString("contact_privacy", "Only me");
        u.contactInfoPrivacy = json.optString("contact_info_privacy", "Only me");
        u.emailPrivacy = json.optString("email_privacy", "Only me");
        u.followersPrivacy = json.optString("followers_privacy", "Public");
        u.tagPrivacy = json.optString("tag_privacy", "Everyone");
        u.twoFactorPin = json.optString("two_factor_pin", "");
        u.pinnedPostId = json.optString("pinned_post_id", "");
        u.statusNote = json.optString("status_note", "");
        u.statusNoteAt = json.optLong("status_note_at", 0);
        u.dailyStreak = json.optInt("daily_streak", 0);
        u.lastBonusAt = json.optLong("last_bonus_at", 0);
        
        // Parse JSON arrays
        u.favoriteUids = parseStringArray(json, "favorite_uids");
        u.restrictedUids = parseStringArray(json, "restricted_uids");
        u.closeFriendsUids = parseStringArray(json, "close_friends_uids");
        u.highlightStoryIds = parseStringArray(json, "highlight_story_ids");
        u.savedReelIds = parseStringArray(json, "saved_reel_ids");
        u.hobbies = parseStringArray(json, "hobbies");
        u.followers = parseStringArray(json, "followers");
        u.following = parseStringArray(json, "following");
        u.blocked = parseStringArray(json, "blocked");
        
        return u;
    }

    private static Models.Post parsePost(JSONObject json) throws JSONException {
        Models.Post p = new Models.Post();
        p.id = json.optString("id", "");
        p.authorId = json.optString("author_id", "");
        p.authorName = json.optString("author_name", "User");
        p.authorPhoto = json.optString("author_photo", "");
        p.content = json.optString("content", "");
        p.mediaUrl = json.optString("media_url", "");
        p.mediaType = json.optString("media_type", "none");
        p.mentions = Mentions.read(json.opt("mentions"));
        p.feeling = json.optString("feeling", "");
        p.privacy = json.optString("privacy", "Public");
        p.sharedFromId = json.optString("shared_from_id", "");
        p.sharedFromAuthor = json.optString("shared_from_author", "");
        p.sharedFromContent = json.optString("shared_from_content", "");
        p.sharedFromMediaUrl = json.optString("shared_from_media_url", "");
        p.sharedFromMediaType = json.optString("shared_from_media_type", "");
        p.location = json.optString("location", "");
        p.groupId = json.optString("group_id", "");
        p.postType = json.optString("post_type", "regular");
        p.groupApprovalStatus = json.optString("group_approval_status", "approved");
        p.lifeEvent = json.optString("life_event", "");
        p.lifeEventIcon = json.optString("life_event_icon", "");
        p.lifeEventTitle = json.optString("life_event_title", "");
        p.qaPrompt = json.optString("qa_prompt", "");
        p.marketTitle = json.optString("market_title", "");
        p.marketPrice = json.optString("market_price", "");
        p.marketCategory = json.optString("market_category", "");
        p.marketCondition = json.optString("market_condition", "");
        p.marketSold = json.optInt("market_sold", 0) == 1;
        p.pageId = json.optString("page_id", "");
        p.pageName = json.optString("page_name", "");
        p.pagePhoto = json.optString("page_photo", "");
        p.archived = json.optInt("archived", 0) == 1;
        p.trashedAt = json.optLong("trashed_at", 0);
        p.bgColor = json.optInt("bg_color", 0);
        p.likes = json.optInt("likes", 0);
        p.comments = json.optInt("comments", 0);
        p.shares = json.optInt("shares", 0);
        p.views = json.optInt("views", 0);
        p.createdAt = json.optLong("created_at", 0);
        p.authorVerified = json.optInt("author_verified", 0) == 1;
        p.authorTickLevel = json.optString("author_tick_level", "none");
        
        // Parse JSON fields
        p.savedBy = parseStringArray(json, "saved_by");
        p.subscribedUids = parseStringArray(json, "subscribed_uids");
        p.subscribedBy = parseStringArray(json, "subscribed_by");
        p.mediaUrls = parseStringArray(json, "media_urls");
        p.taggedUids = parseStringArray(json, "tagged_uids");
        p.taggedNames = parseStringArray(json, "tagged_names");
        p.pollOptions = parseStringArray(json, "poll_options");
        p.pollVotes = parseLongMap(json, "poll_votes");
        p.pinnedCommentId = json.optString("pinned_comment_id", "");
        p.inlineComments = parseComments(json, "inline_comments");
        p.reactionsMap = parseReactionsMap(json, "reactions_map");
        
        return p;
    }

    private static Models.Comment parseComment(JSONObject json) throws JSONException {
        Models.Comment c = new Models.Comment();
        c.id = json.optString("id", "");
        c.postId = json.optString("post_id", "");
        c.authorId = json.optString("author_id", "");
        c.authorName = json.optString("author_name", "User");
        c.authorPhoto = json.optString("author_photo", "");
        c.content = json.optString("content", "");
        c.imageUrl = json.optString("image_url", "");
        c.parentId = json.optString("parent_id", "");
        c.edited = json.optInt("edited", 0) == 1;
        c.pinned = json.optInt("pinned", 0) == 1;
        c.likes = json.optInt("likes", 0);
        c.createdAt = json.optLong("created_at", 0);
        c.likedBy = parseStringArray(json, "liked_by");
        c.reactions = parseStringMap(json, "reactions");
        return c;
    }

    private static Models.ChatRoom parseChatRoom(JSONObject json) throws JSONException {
        Models.ChatRoom c = new Models.ChatRoom();
        c.id = json.optString("id", "");
        c.lastMessage = json.optString("last_message", "");
        c.lastMessageTime = json.optLong("last_message_time", 0);
        c.isGroup = json.optInt("is_group", 0) == 1;
        c.groupName = json.optString("group_name", "");
        c.groupPhoto = json.optString("group_photo", "");
        c.createdBy = json.optString("created_by", "");
        c.onlyAdminsCanSend = json.optInt("only_admins_can_send", 0) == 1;
        c.adminIds = parseStringArray(json, "admin_ids");
        c.participants = parseStringArray(json, "participants");
        c.typing = parseBooleanMap(json, "typing");
        c.participantNames = parseStringMap(json, "participant_names");
        c.participantPhotos = parseStringMap(json, "participant_photos");
        return c;
    }

    private static Models.Message parseMessage(JSONObject json) throws JSONException {
        Models.Message m = new Models.Message();
        m.id = json.optString("id", "");
        m.senderId = json.optString("sender_id", "");
        m.senderName = json.optString("sender_name", "User");
        m.senderPhoto = json.optString("sender_photo", "");
        m.receiverId = json.optString("receiver_id", "");
        m.text = json.optString("text", "");
        m.imageUrl = json.optString("image_url", "");
        m.videoUrl = json.optString("video_url", "");
        m.type = json.optString("type", "text");
        m.voiceUrl = json.optString("voice_url", "");
        m.broadcastId = json.optString("broadcast_id", "");
        m.liveId = json.optString("live_id", "");
        m.liveHostId = json.optString("live_host_id", "");
        m.liveHostName = json.optString("live_host_name", "");
        m.liveHostPhoto = json.optString("live_host_photo", "");
        m.liveTitle = json.optString("live_title", "");
        m.replyToText = json.optString("reply_to_text", "");
        m.replyToSender = json.optString("reply_to_sender", "");
        m.callType = json.optString("call_type", "");
        m.callStatus = json.optString("call_status", "");
        m.callDurationSec = json.optInt("call_duration_sec", 0);
        m.forwarded = json.optInt("forwarded", 0) == 1;
        m.voiceDurationSec = json.optInt("voice_duration_sec", 0);
        m.isSeen = json.optInt("is_seen", 0) == 1;
        m.isDelivered = json.optInt("is_delivered", 0) == 1;
        m.isDeleted = json.optInt("is_deleted", 0) == 1;
        m.createdAt = json.optLong("created_at", 0);
        m.reactions = parseStringMap(json, "reactions");
        m.reactorNames = parseStringMap(json, "reactor_names");
        m.reactorPhotos = parseStringMap(json, "reactor_photos");
        return m;
    }

    private static Models.Group parseGroup(JSONObject json) throws JSONException {
        Models.Group g = new Models.Group();
        g.id = json.optString("id", "");
        g.name = json.optString("name", "Group");
        g.about = json.optString("about", "");
        g.category = json.optString("category", "Community");
        g.photoUrl = json.optString("photo_url", "");
        g.coverUrl = json.optString("cover_url", "");
        g.adminId = json.optString("admin_id", "");
        g.privacy = json.optString("privacy", "Public");
        g.rules = json.optString("rules", "");
        g.pinnedPostId = json.optString("pinned_post_id", "");
        g.postPermission = json.optString("post_permission", "Everyone");
        g.requirePostApproval = json.optInt("require_post_approval", 0) == 1;
        g.requireJoinApproval = json.optInt("require_join_approval", 0) == 1;
        g.createdAt = json.optLong("created_at", 0);
        g.members = parseStringArray(json, "members");
        g.adminIds = parseStringArray(json, "admin_ids");
        g.moderatorIds = parseStringArray(json, "moderator_ids");
        g.pendingMemberIds = parseStringArray(json, "pending_member_ids");
        g.bannedMemberIds = parseStringArray(json, "banned_member_ids");
        g.invitedUids = parseStringArray(json, "invited_uids");
        return g;
    }

    private static Models.Story parseStory(JSONObject json) throws JSONException {
        Models.Story s = new Models.Story();
        s.id = json.optString("id", "");
        s.authorId = json.optString("author_id", "");
        s.authorName = json.optString("author_name", "User");
        s.authorPhoto = json.optString("author_photo", "");
        s.mediaUrl = json.optString("media_url", "");
        s.mediaType = json.optString("media_type", "image");
        s.caption = json.optString("caption", "");
        s.musicMood = json.optString("music_mood", "");
        s.privacy = json.optString("privacy", "Public");
        s.bgColor = json.optInt("bg_color", 0);
        s.fontStyle = json.optInt("font_style", 0);
        s.createdAt = json.optLong("created_at", 0);
        s.expiresAt = json.optLong("expires_at", System.currentTimeMillis() + 86400000L);
        s.viewedBy = parseStringArray(json, "viewed_by");
        s.reactions = parseStringMap(json, "reactions");
        return s;
    }

    private static Models.Reel parseReel(JSONObject json) throws JSONException {
        Models.Reel r = new Models.Reel();
        r.id = json.optString("id", "");
        r.authorId = json.optString("author_id", "");
        r.authorName = json.optString("author_name", "User");
        r.authorPhoto = json.optString("author_photo", "");
        r.videoUrl = json.optString("video_url", "");
        r.thumbUrl = json.optString("thumb_url", "");
        r.caption = json.optString("caption", "");
        r.likes = json.optInt("likes", 0);
        r.comments = json.optInt("comments", 0);
        r.shares = json.optInt("shares", 0);
        r.favorites = json.optInt("favorites", 0);
        r.views = json.optInt("views", 0);
        r.durationSec = json.optInt("duration_sec", 0);
        r.createdAt = json.optLong("created_at", 0);
        r.isFromPost = json.optInt("is_from_post", 0) == 1;
        r.mirrorHorizontal = json.optInt("mirror_horizontal", 0) == 1;
        r.overlayText = json.optString("overlay_text", "");
        r.overlayColor = json.optInt("overlay_color", 0xFFFFFFFF);
        r.overlayBgColor = json.optInt("overlay_bg_color", 0xCC000000);
        r.overlayX = (float) json.optDouble("overlay_x", 0.5);
        r.overlayY = (float) json.optDouble("overlay_y", 0.24);
        r.stickerText = json.optString("sticker_text", "");
        r.stickerX = (float) json.optDouble("sticker_x", 0.78);
        r.stickerY = (float) json.optDouble("sticker_y", 0.32);
        r.stickerScale = (float) json.optDouble("sticker_scale", 1.0);
        r.filterPreset = json.optString("filter_preset", "none");
        r.filterOverlayColor = json.optInt("filter_overlay_color", 0);
        r.beautyLevel = json.optInt("beauty_level", 0);
        r.playbackSpeed = (float) json.optDouble("playback_speed", 1.0);
        r.muteOriginalAudio = json.optInt("mute_original_audio", 0) == 1;
        r.audioTitle = json.optString("audio_title", "");
        r.audioUrl = json.optString("audio_url", "");
        r.soundAuthorPhoto = json.optString("sound_author_photo", "");
        r.trimStartMs = json.optInt("trim_start_ms", 0);
        r.trimEndMs = json.optInt("trim_end_ms", 0);
        r.likedBy = parseStringArray(json, "liked_by");
        r.savedBy = parseStringArray(json, "saved_by");
        r.reactions = parseStringMap(json, "reactions");
        r.inlineComments = parseComments(json, "inline_comments");
        return r;
    }

    // Helper methods for parsing
    private static List<String> parseStringArray(JSONObject json, String key) {
        List<String> list = new ArrayList<>();
        try {
            JSONArray arr = json.optJSONArray(key);
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String val = arr.optString(i, "");
                    if (!val.isEmpty()) list.add(val);
                }
            } else {
                // Try parsing as JSON string
                String str = json.optString(key, "");
                if (!str.isEmpty()) {
                    JSONArray arr2 = new JSONArray(str);
                    for (int i = 0; i < arr2.length(); i++) {
                        String val = arr2.optString(i, "");
                        if (!val.isEmpty()) list.add(val);
                    }
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    private static Map<String, String> parseStringMap(JSONObject json, String key) {
        Map<String, String> map = new HashMap<>();
        try {
            JSONObject obj = json.optJSONObject(key);
            if (obj != null) {
                Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    map.put(k, String.valueOf(obj.opt(k)));
                }
            }
        } catch (Exception ignored) {}
        return map;
    }

    private static Map<String, Long> parseLongMap(JSONObject json, String key) {
        Map<String, Long> map = new HashMap<>();
        try {
            JSONObject obj = json.optJSONObject(key);
            if (obj != null) {
                Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    Object v = obj.opt(k);
                    if (v instanceof Number) map.put(k, ((Number) v).longValue());
                }
            }
        } catch (Exception ignored) {}
        return map;
    }

    private static Map<String, Boolean> parseBooleanMap(JSONObject json, String key) {
        Map<String, Boolean> map = new HashMap<>();
        try {
            JSONObject obj = json.optJSONObject(key);
            if (obj != null) {
                Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    map.put(k, obj.optBoolean(k, false));
                }
            }
        } catch (Exception ignored) {}
        return map;
    }

    private static List<Models.Comment> parseComments(JSONObject json, String key) {
        List<Models.Comment> list = new ArrayList<>();
        try {
            JSONArray arr = json.optJSONArray(key);
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    list.add(parseComment(arr.getJSONObject(i)));
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    private static Map<String, Map<String, Object>> parseReactionsMap(JSONObject json, String key) {
        Map<String, Map<String, Object>> map = new HashMap<>();
        try {
            JSONObject obj = json.optJSONObject(key);
            if (obj != null) {
                Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    Object v = obj.opt(k);
                    if (v instanceof JSONObject) {
                        Map<String, Object> sub = new HashMap<>();
                        JSONObject subObj = (JSONObject) v;
                        Iterator<String> subKeys = subObj.keys();
                        while (subKeys.hasNext()) {
                            String sk = subKeys.next();
                            sub.put(sk, subObj.opt(sk));
                        }
                        map.put(k, sub);
                    }
                }
            }
        } catch (Exception ignored) {}
        return map;
    }

    // JSON helper
    public static JSONObject toJson(Map<String, Object> map) {
        if (map == null) return new JSONObject();
        JSONObject out = new JSONObject();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            try {
                Object v = e.getValue();
                if (v == null) continue;
                if (v instanceof Map) {
                    out.put(e.getKey(), toJson((Map<String, Object>) v));
                } else if (v instanceof List) {
                    JSONArray arr = new JSONArray();
                    for (Object item : (List<?>) v) {
                        if (item instanceof Map) {
                            arr.put(toJson((Map<String, Object>) item));
                        } else {
                            arr.put(item);
                        }
                    }
                    out.put(e.getKey(), arr);
                } else if (v instanceof Boolean || v instanceof Number || v instanceof String) {
                    out.put(e.getKey(), v);
                }
            } catch (JSONException ignored) {}
        }
        return out;
    }

    public static JSONObject object(Object... values) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < values.length; i += 2) {
                o.put((String) values[i], values[i + 1] == null ? JSONObject.NULL : values[i + 1]);
            }
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
        return o;
    }
}