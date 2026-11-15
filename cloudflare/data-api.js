/**
 * Friendbook Data API - Cloudflare Worker
 * Replaces Firestore/RTDB/Storage with D1 + R2
 * 
 * Endpoints:
 * - /v1/users/* - User profiles, search, friends, blocks, settings
 * - /v1/posts/* - Posts, feed, reactions, comments, shares
 * - /v1/chats/* - Chats, messages, typing
 * - /v1/groups/* - Groups, membership
 * - /v1/friend-requests/* - Friend requests
 * - /v1/notifications/* - Notifications
 * - /v1/stories/* - Stories
 * - /v1/reels/* - Reels
 * - /v1/calls/* - WebRTC signaling
 * - /v1/me - Current user
 * - /v1/upload/* - R2 upload (photos/videos)
 */

import { json, verifyAuth } from './utils.js';

const CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, Authorization',
  'Cache-Control': 'no-store',
  'X-Content-Type-Options': 'nosniff',
};

function cors(status = 200, body = null) {
  return new Response(body ? JSON.stringify(body) : null, {
    status,
    headers: { ...CORS_HEADERS, 'Content-Type': 'application/json' },
  });
}

function ok(data) { return json(data, 200, CORS_HEADERS); }
function created(data) { return json(data, 201, CORS_HEADERS); }
function badRequest(msg) { return json({ error: msg }, 400, CORS_HEADERS); }
function unauthorized(msg) { return json({ error: msg }, 401, CORS_HEADERS); }
function notFound(msg) { return json({ error: msg }, 404, CORS_HEADERS); }
function serverError(msg) { return json({ error: msg }, 500, CORS_HEADERS); }

export async function dataApi(request, env, identity) {
  if (request.method === 'OPTIONS') return cors();
  
  const uid = identity?.uid;
  if (!uid) return unauthorized('Unauthorized');
  
  const url = new URL(request.url);
  const path = url.pathname;
  
  try {
    // ============ USER ENDPOINTS ============
    if (path === '/v1/me') {
      if (request.method === 'GET') return await getMe(env, uid);
      if (request.method === 'PUT') return await updateMe(env, uid, await request.json());
    }
    
    const userMatch = path.match(/^\/v1\/users\/(.+)$/);
    if (userMatch) {
      const targetUid = decodeURIComponent(userMatch[1]);
      if (request.method === 'GET') return await getUser(env, targetUid, uid);
      if (request.method === 'PUT') return await updateUser(env, targetUid, uid, await request.json());
    }
    
    if (path === '/v1/users/search' && request.method === 'GET') {
      const q = url.searchParams.get('q') || '';
      const limit = parseInt(url.searchParams.get('limit') || '20');
      return await searchUsers(env, q, limit, uid);
    }
    
    if (path === '/v1/users/friends' && request.method === 'GET') {
      return await getFriends(env, uid);
    }
    
    if (path === '/v1/users/blocked' && request.method === 'GET') {
      return await getBlocked(env, uid);
    }
    
    if (path === '/v1/users/block' && request.method === 'POST') {
      const { targetUid } = await request.json();
      return await blockUser(env, uid, targetUid);
    }
    
    if (path === '/v1/users/unblock' && request.method === 'POST') {
      const { targetUid } = await request.json();
      return await unblockUser(env, uid, targetUid);
    }
    
    if (path === '/v1/users/follow' && request.method === 'POST') {
      const { targetUid } = await request.json();
      return await followUser(env, uid, targetUid);
    }
    
    if (path === '/v1/users/unfollow' && request.method === 'POST') {
      const { targetUid } = await request.json();
      return await unfollowUser(env, uid, targetUid);
    }
    
    // ============ POST ENDPOINTS ============
    if (path === '/v1/posts' && request.method === 'POST') {
      return await createPost(env, uid, await request.json());
    }
    
    const postMatch = path.match(/^\/v1\/posts\/([^/]+)(?:\/(.*))?$/);
    if (postMatch) {
      const postId = decodeURIComponent(postMatch[1]);
      const action = postMatch[2];
      
      if (!action) {
        if (request.method === 'GET') return await getPost(env, postId, uid);
        if (request.method === 'PUT') return await updatePost(env, postId, uid, await request.json());
        if (request.method === 'DELETE') return await deletePost(env, postId, uid);
      }
      
      if (action === 'reactions') {
        if (request.method === 'GET') return await getPostReactions(env, postId);
        if (request.method === 'POST') return await addReaction(env, postId, uid, await request.json());
        if (request.method === 'DELETE') return await removeReaction(env, postId, uid);
      }
      
      if (action === 'comments') {
        if (request.method === 'GET') return await getComments(env, postId);
        if (request.method === 'POST') return await addComment(env, postId, uid, await request.json());
      }
      
      if (action === 'comment' && request.method === 'PUT') {
        const commentId = url.searchParams.get('commentId');
        return await updateComment(env, postId, commentId, uid, await request.json());
      }
      
      if (action === 'comment' && request.method === 'DELETE') {
        const commentId = url.searchParams.get('commentId');
        return await deleteComment(env, postId, commentId, uid);
      }
      
      if (action === 'share' && request.method === 'POST') {
        return await sharePost(env, postId, uid, await request.json());
      }
      
      if (action === 'save' && request.method === 'POST') {
        return await savePost(env, postId, uid);
      }
      
      if (action === 'unsave' && request.method === 'POST') {
        return await unsavePost(env, postId, uid);
      }
      
      if (action === 'view' && request.method === 'POST') {
        return await recordView(env, postId, uid);
      }
    }
    
    if (path === '/v1/posts/feed' && request.method === 'GET') {
      const cursor = url.searchParams.get('cursor');
      const limit = parseInt(url.searchParams.get('limit') || '20');
      const type = url.searchParams.get('type') || 'home'; // home, explore, favorites, group
      const groupId = url.searchParams.get('groupId');
      return await getFeed(env, uid, cursor, limit, type, groupId);
    }
    
    // ============ CHAT ENDPOINTS ============
    if (path === '/v1/chats' && request.method === 'POST') {
      return await createChat(env, uid, await request.json());
    }
    
    const chatMatch = path.match(/^\/v1\/chats\/([^/]+)(?:\/(.*))?$/);
    if (chatMatch) {
      const chatId = decodeURIComponent(chatMatch[1]);
      const action = chatMatch[2];
      
      if (!action) {
        if (request.method === 'GET') return await getChat(env, chatId, uid);
        if (request.method === 'PUT') return await updateChat(env, chatId, uid, await request.json());
      }
      
      if (action === 'messages') {
        if (request.method === 'GET') return await getMessages(env, chatId, uid, url.searchParams);
        if (request.method === 'POST') return await sendMessage(env, chatId, uid, await request.json());
      }
      
      if (action === 'message' && request.method === 'PUT') {
        const messageId = url.searchParams.get('messageId');
        return await updateMessage(env, chatId, messageId, uid, await request.json());
      }
      
      if (action === 'message' && request.method === 'DELETE') {
        const messageId = url.searchParams.get('messageId');
        return await deleteMessage(env, chatId, messageId, uid);
      }
      
      if (action === 'typing' && request.method === 'PUT') {
        const { typing } = await request.json();
        return await setTyping(env, chatId, uid, typing);
      }
      
      if (action === 'read' && request.method === 'POST') {
        const { messageId } = await request.json();
        return await markRead(env, chatId, uid, messageId);
      }
      
      if (action === 'participants') {
        if (request.method === 'GET') return await getParticipants(env, chatId, uid);
        if (request.method === 'POST') return await addParticipant(env, chatId, uid, await request.json());
        if (request.method === 'DELETE') return await removeParticipant(env, chatId, uid, url.searchParams.get('userId'));
      }
      
      if (action === 'admins') {
        if (request.method === 'POST') return await addAdmin(env, chatId, uid, await request.json());
        if (request.method === 'DELETE') return await removeAdmin(env, chatId, uid, url.searchParams.get('userId'));
      }
    }
    
    // ============ GROUP ENDPOINTS ============
    if (path === '/v1/groups' && request.method === 'POST') {
      return await createGroup(env, uid, await request.json());
    }
    
    const groupMatch = path.match(/^\/v1\/groups\/([^/]+)(?:\/(.*))?$/);
    if (groupMatch) {
      const groupId = decodeURIComponent(groupMatch[1]);
      const action = groupMatch[2];
      
      if (!action) {
        if (request.method === 'GET') return await getGroup(env, groupId, uid);
        if (request.method === 'PUT') return await updateGroup(env, groupId, uid, await request.json());
      }
      
      if (action === 'join' && request.method === 'POST') {
        return await joinGroup(env, groupId, uid);
      }
      
      if (action === 'leave' && request.method === 'POST') {
        return await leaveGroup(env, groupId, uid);
      }
      
      if (action === 'members' && request.method === 'GET') {
        return await getGroupMembers(env, groupId, uid);
      }
      
      if (action === 'pending' && request.method === 'GET') {
        return await getPendingMembers(env, groupId, uid);
      }
      
      if (action === 'approve' && request.method === 'POST') {
        const { userId } = await request.json();
        return await approveMember(env, groupId, uid, userId);
      }
      
      if (action === 'posts' && request.method === 'GET') {
        const cursor = url.searchParams.get('cursor');
        const limit = parseInt(url.searchParams.get('limit') || '20');
        return await getGroupPosts(env, groupId, uid, cursor, limit);
      }
    }
    
    // ============ FRIEND REQUEST ENDPOINTS ============
    if (path === '/v1/friend-requests' && request.method === 'GET') {
      const status = url.searchParams.get('status') || 'pending';
      return await getFriendRequests(env, uid, status);
    }
    
    if (path === '/v1/friend-requests' && request.method === 'POST') {
      return await sendFriendRequest(env, uid, await request.json());
    }
    
    const frMatch = path.match(/^\/v1\/friend-requests\/([^/]+)$/);
    if (frMatch) {
      const requestId = decodeURIComponent(frMatch[1]);
      const action = url.searchParams.get('action');
      
      if (action === 'accept' && request.method === 'POST') {
        return await acceptFriendRequest(env, requestId, uid);
      }
      
      if (action === 'decline' && request.method === 'POST') {
        return await declineFriendRequest(env, requestId, uid);
      }
      
      if (action === 'cancel' && request.method === 'POST') {
        return await cancelFriendRequest(env, requestId, uid);
      }
    }
    
    // ============ NOTIFICATION ENDPOINTS ============
    if (path === '/v1/notifications' && request.method === 'GET') {
      const cursor = url.searchParams.get('cursor');
      const limit = parseInt(url.searchParams.get('limit') || '50');
      const unreadOnly = url.searchParams.get('unread') === 'true';
      return await getNotifications(env, uid, cursor, limit, unreadOnly);
    }
    
    if (path === '/v1/notifications/read' && request.method === 'POST') {
      const { notificationIds } = await request.json();
      return await markNotificationsRead(env, uid, notificationIds);
    }
    
    if (path === '/v1/notifications/read-all' && request.method === 'POST') {
      return await markAllNotificationsRead(env, uid);
    }
    
    // ============ STORIES ENDPOINTS ============
    if (path === '/v1/stories' && request.method === 'POST') {
      return await createStory(env, uid, await request.json());
    }
    
    if (path === '/v1/stories/feed' && request.method === 'GET') {
      return await getStoriesFeed(env, uid);
    }
    
    const storyMatch = path.match(/^\/v1\/stories\/([^/]+)$/);
    if (storyMatch) {
      const storyId = decodeURIComponent(storyMatch[1]);
      if (request.method === 'GET') return await getStory(env, storyId, uid);
      if (request.method === 'DELETE') return await deleteStory(env, storyId, uid);
      if (request.method === 'POST') {
        const { action } = await request.json();
        if (action === 'view') return await viewStory(env, storyId, uid);
        if (action === 'react') return await reactStory(env, storyId, uid, await request.json());
      }
    }
    
    // ============ REELS ENDPOINTS ============
    if (path === '/v1/reels' && request.method === 'POST') {
      return await createReel(env, uid, await request.json());
    }
    
    if (path === '/v1/reels/feed' && request.method === 'GET') {
      const cursor = url.searchParams.get('cursor');
      const limit = parseInt(url.searchParams.get('limit') || '20');
      return await getReelsFeed(env, uid, cursor, limit);
    }
    
    const reelMatch = path.match(/^\/v1\/reels\/([^/]+)(?:\/(.*))?$/);
    if (reelMatch) {
      const reelId = decodeURIComponent(reelMatch[1]);
      const action = reelMatch[2];
      
      if (!action) {
        if (request.method === 'GET') return await getReel(env, reelId, uid);
        if (request.method === 'PUT') return await updateReel(env, reelId, uid, await request.json());
        if (request.method === 'DELETE') return await deleteReel(env, reelId, uid);
      }
      
      if (action === 'reactions') {
        if (request.method === 'GET') return await getReelReactions(env, reelId);
        if (request.method === 'POST') return await addReelReaction(env, reelId, uid, await request.json());
        if (request.method === 'DELETE') return await removeReelReaction(env, reelId, uid);
      }
      
      if (action === 'comments') {
        if (request.method === 'GET') return await getReelComments(env, reelId);
        if (request.method === 'POST') return await addReelComment(env, reelId, uid, await request.json());
      }
      
      if (action === 'save' && request.method === 'POST') {
        return await saveReel(env, reelId, uid);
      }
      
      if (action === 'unsave' && request.method === 'POST') {
        return await unsaveReel(env, reelId, uid);
      }
      
      if (action === 'view' && request.method === 'POST') {
        return await recordReelView(env, reelId, uid);
      }
    }
    
    // ============ CALLS ENDPOINTS (WebRTC Signaling) ============
    if (path === '/v1/calls/offer' && request.method === 'POST') {
      return await sendCallOffer(env, uid, await request.json());
    }
    
    if (path === '/v1/calls/answer' && request.method === 'POST') {
      return await sendCallAnswer(env, uid, await request.json());
    }
    
    if (path === '/v1/calls/ice' && request.method === 'POST') {
      return await sendIceCandidate(env, uid, await request.json());
    }
    
    if (path === '/v1/calls/end' && request.method === 'POST') {
      return await endCall(env, uid, await request.json());
    }
    
    if (path === '/v1/calls/state' && request.method === 'GET') {
      const chatId = url.searchParams.get('chatId');
      return await getCallState(env, chatId, uid);
    }
    
    // ============ SETTINGS ENDPOINTS ============
    if (path === '/v1/settings' && request.method === 'GET') {
      return await getSettings(env, uid);
    }
    
    if (path === '/v1/settings' && request.method === 'PUT') {
      return await updateSettings(env, uid, await request.json());
    }
    
    // ============ UPLOAD ENDPOINTS (R2) ============
    if (path.startsWith('/v1/upload/')) {
      return await handleUpload(env, uid, request, path);
    }
    
    return notFound('Endpoint not found');
    
  } catch (error) {
    console.error('Data API error:', error);
    return serverError(error.message || 'Internal server error');
  }
}

// ============ IMPLEMENTATIONS ============

// User functions
async function getMe(env, uid) {
  const user = await env.DB.prepare('SELECT * FROM users WHERE uid = ?').bind(uid).first();
  if (!user) return notFound('User not found');
  return ok(user);
}

async function updateMe(env, uid, data) {
  const allowed = ['display_name', 'bio', 'cover_url', 'work', 'education', 'current_city', 'hometown',
    'relationship_status', 'website', 'pronouns', 'phone', 'phone_number', 'login_alerts',
    'tag_review_enabled', 'professional_mode', 'hide_reaction_counts', 'show_active_status',
    'lookup_privacy', 'friends_list_privacy', 'email_privacy', 'contact_privacy',
    'contact_info_privacy', 'followers_privacy', 'tag_privacy', 'two_factor_pin',
    'pinned_post_id', 'status_note'];
  
  const updates = [];
  const values = [];
  for (const key of allowed) {
    if (data[key] !== undefined) {
      updates.push(`${key} = ?`);
      values.push(typeof data[key] === 'object' ? JSON.stringify(data[key]) : data[key]);
    }
  }
  
  if (updates.length === 0) return badRequest('No valid fields to update');
  
  values.push(uid);
  await env.DB.prepare(`UPDATE users SET ${updates.join(', ')} WHERE uid = ?`).bind(...values).run();
  
  const user = await env.DB.prepare('SELECT * FROM users WHERE uid = ?').bind(uid).first();
  return ok(user);
}

async function getUser(env, targetUid, requesterUid) {
  const user = await env.DB.prepare('SELECT * FROM users WHERE uid = ?').bind(targetUid).first();
  if (!user) return notFound('User not found');
  
  // Check privacy settings
  const requester = await env.DB.prepare('SELECT * FROM users WHERE uid = ?').bind(requesterUid).first();
  const isFriend = requester && user.followers && JSON.parse(user.followers).includes(requesterUid);
  
  if (user.lookup_privacy === 'Only me' && targetUid !== requesterUid) {
    return notFound('User not found');
  }
  if (user.lookup_privacy === 'Friends' && !isFriend && targetUid !== requesterUid) {
    return notFound('User not found');
  }
  
  // Filter private fields
  const filtered = { ...user };
  if (user.email_privacy === 'Only me' && targetUid !== requesterUid) delete filtered.email;
  if (user.contact_privacy === 'Only me' && targetUid !== requesterUid) {
    delete filtered.phone;
    delete filtered.phone_number;
  }
  
  return ok(filtered);
}

async function updateUser(env, targetUid, requesterUid, data) {
  if (targetUid !== requesterUid) return unauthorized('Cannot update other user');
  return updateMe(env, targetUid, data);
}

async function searchUsers(env, query, limit, requesterUid) {
  if (!query || query.length < 2) return ok([]);
  
  const users = await env.DB.prepare(
    `SELECT uid, display_name, photo_url, bio, is_verified, tick_level, friends_count
     FROM users 
     WHERE (display_name LIKE ? OR uid LIKE ?) AND deactivated = 0 AND banned = 0
     LIMIT ?`
  ).bind(`%${query}%`, `%${query}%`, limit).all();
  
  // Filter by privacy
  const requester = await env.DB.prepare('SELECT followers FROM users WHERE uid = ?').bind(requesterUid).first();
  const requesterFollowers = requester ? JSON.parse(requester.followers || '[]') : [];
  
  const filtered = users.results.filter(u => {
    if (u.uid === requesterUid) return true;
    if (u.lookup_privacy === 'Only me') return false;
    if (u.lookup_privacy === 'Friends' && !requesterFollowers.includes(u.uid)) return false;
    return true;
  });
  
  return ok(filtered);
}

async function getFriends(env, uid) {
  const user = await env.DB.prepare('SELECT following, followers FROM users WHERE uid = ?').bind(uid).first();
  if (!user) return notFound('User not found');
  
  const following = JSON.parse(user.following || '[]');
  const followers = JSON.parse(user.followers || '[]');
  const friends = following.filter(f => followers.includes(f));
  
  if (friends.length === 0) return ok([]);
  
  const placeholders = friends.map(() => '?').join(',');
  const friendDetails = await env.DB.prepare(
    `SELECT uid, display_name, photo_url, is_verified, tick_level, last_seen, show_active_status
     FROM users WHERE uid IN (${placeholders})`
  ).bind(...friends).all();
  
  return ok(friendDetails.results);
}

async function getBlocked(env, uid) {
  const user = await env.DB.prepare('SELECT blocked FROM users WHERE uid = ?').bind(uid).first();
  if (!user) return notFound('User not found');
  
  const blocked = JSON.parse(user.blocked || '[]');
  if (blocked.length === 0) return ok([]);
  
  const placeholders = blocked.map(() => '?').join(',');
  const blockedDetails = await env.DB.prepare(
    `SELECT uid, display_name, photo_url FROM users WHERE uid IN (${placeholders})`
  ).bind(...blocked).all();
  
  return ok(blockedDetails.results);
}

async function blockUser(env, uid, targetUid) {
  if (uid === targetUid) return badRequest('Cannot block yourself');
  
  await env.DB.prepare(
    `UPDATE users SET blocked = json_array_append(COALESCE(blocked, '[]'), '$', ?) 
     WHERE uid = ? AND json_type(blocked) = 'array' AND json_extract(blocked, '$') NOT LIKE '%' || ? || '%'`
  ).bind(targetUid, uid, targetUid).run();
  
  // Also remove from friends/followers
  await env.DB.prepare(
    `UPDATE users SET 
      following = json_remove(following, json_extract(following, '$[*]'), ?),
      followers = json_remove(followers, json_extract(followers, '$[*]'), ?)
     WHERE uid = ?`
  ).bind(targetUid, targetUid, uid).run();
  
  return ok({ success: true });
}

async function unblockUser(env, uid, targetUid) {
  await env.DB.prepare(
    `UPDATE users SET blocked = json_remove(blocked, json_extract(blocked, '$[*]'), ?) WHERE uid = ?`
  ).bind(targetUid, uid).run();
  
  return ok({ success: true });
}

async function followUser(env, uid, targetUid) {
  if (uid === targetUid) return badRequest('Cannot follow yourself');
  
  const target = await env.DB.prepare('SELECT followers, lookup_privacy FROM users WHERE uid = ?').bind(targetUid).first();
  if (!target) return notFound('User not found');
  
  const followers = JSON.parse(target.followers || '[]');
  if (followers.includes(uid)) return ok({ success: true, alreadyFollowing: true });
  
  // Check privacy - if target has "Only me" or "Friends" lookup privacy, may need approval
  // For now, just add
  followers.push(uid);
  await env.DB.prepare('UPDATE users SET followers = ? WHERE uid = ?').bind(JSON.stringify(followers), targetUid).run();
  
  // Add to requester's following
  const requester = await env.DB.prepare('SELECT following FROM users WHERE uid = ?').bind(uid).first();
  const following = JSON.parse(requester?.following || '[]');
  if (!following.includes(targetUid)) {
    following.push(targetUid);
    await env.DB.prepare('UPDATE users SET following = ? WHERE uid = ?').bind(JSON.stringify(following), uid).run();
  }
  
  return ok({ success: true });
}

async function unfollowUser(env, uid, targetUid) {
  const target = await env.DB.prepare('SELECT followers FROM users WHERE uid = ?').bind(targetUid).first();
  if (target) {
    const followers = JSON.parse(target.followers || '[]').filter(f => f !== uid);
    await env.DB.prepare('UPDATE users SET followers = ? WHERE uid = ?').bind(JSON.stringify(followers), targetUid).run();
  }
  
  const requester = await env.DB.prepare('SELECT following FROM users WHERE uid = ?').bind(uid).first();
  if (requester) {
    const following = JSON.parse(requester.following || '[]').filter(f => f !== targetUid);
    await env.DB.prepare('UPDATE users SET following = ? WHERE uid = ?').bind(JSON.stringify(following), uid).run();
  }
  
  return ok({ success: true });
}

// Post functions
async function createPost(env, uid, data) {
  const postId = crypto.randomUUID();
  const now = Date.now();
  
  const post = {
    id: postId,
    author_id: uid,
    author_name: data.authorName || 'User',
    author_photo: data.authorPhoto || '',
    content: data.content || '',
    media_url: data.mediaUrl || '',
    media_type: data.mediaType || 'none',
    mentions: JSON.stringify(data.mentions || []),
    feeling: data.feeling || '',
    privacy: data.privacy || 'Public',
    location: data.location || '',
    group_id: data.groupId || '',
    post_type: data.postType || 'regular',
    bg_color: data.bgColor || 0,
    created_at: now,
    author_verified: data.authorVerified || 0,
    author_tick_level: data.authorTickLevel || 'none',
  };
  
  await env.DB.prepare(
    `INSERT INTO posts (id, author_id, author_name, author_photo, content, media_url, media_type,
      mentions, feeling, privacy, location, group_id, post_type, bg_color, created_at,
      author_verified, author_tick_level)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
  ).bind(
    post.id, post.author_id, post.author_name, post.author_photo, post.content,
    post.media_url, post.media_type, post.mentions, post.feeling, post.privacy,
    post.location, post.group_id, post.post_type, post.bg_color, post.created_at,
    post.author_verified, post.author_tick_level
  ).run();
  
  // Update user's posts count (optional, could use a separate counter table)
  await env.DB.prepare('UPDATE users SET friends_count = friends_count + 0 WHERE uid = ?').bind(uid).run();
  
  return created({ postId, ...post });
}

async function getPost(env, postId, uid) {
  const post = await env.DB.prepare('SELECT * FROM posts WHERE id = ?').bind(postId).first();
  if (!post) return notFound('Post not found');
  
  // Check privacy
  if (post.privacy === 'Only me' && post.author_id !== uid) return notFound('Post not found');
  if (post.privacy === 'Friends') {
    const author = await env.DB.prepare('SELECT followers FROM users WHERE uid = ?').bind(post.author_id).first();
    const followers = JSON.parse(author?.followers || '[]');
    if (!followers.includes(uid) && post.author_id !== uid) return notFound('Post not found');
  }
  
  // Parse JSON fields
  post.mentions = JSON.parse(post.mentions || '[]');
  post.saved_by = JSON.parse(post.saved_by || '[]');
  post.subscribed_uids = JSON.parse(post.subscribed_uids || '[]');
  post.subscribed_by = JSON.parse(post.subscribed_by || '[]');
  post.media_urls = JSON.parse(post.media_urls || '[]');
  post.tagged_uids = JSON.parse(post.tagged_uids || '[]');
  post.tagged_names = JSON.parse(post.tagged_names || '[]');
  post.poll_options = JSON.parse(post.poll_options || '[]');
  post.poll_votes = JSON.parse(post.poll_votes || '{}');
  post.inline_comments = JSON.parse(post.inline_comments || '[]');
  post.reactions_map = JSON.parse(post.reactions_map || '{}');
  
  return ok(post);
}

async function updatePost(env, postId, uid, data) {
  const post = await env.DB.prepare('SELECT author_id FROM posts WHERE id = ?').bind(postId).first();
  if (!post) return notFound('Post not found');
  if (post.author_id !== uid) return unauthorized('Not your post');
  
  const allowed = ['content', 'privacy', 'feeling', 'location', 'bg_color', 'pinned_comment_id'];
  const updates = [];
  const values = [];
  
  for (const key of allowed) {
    if (data[key] !== undefined) {
      updates.push(`${key} = ?`);
      values.push(typeof data[key] === 'object' ? JSON.stringify(data[key]) : data[key]);
    }
  }
  
  if (updates.length === 0) return badRequest('No valid fields to update');
  
  values.push(postId);
  await env.DB.prepare(`UPDATE posts SET ${updates.join(', ')} WHERE id = ?`).bind(...values).run();
  
  return ok({ success: true });
}

async function deletePost(env, postId, uid) {
  const post = await env.DB.prepare('SELECT author_id FROM posts WHERE id = ?').bind(postId).first();
  if (!post) return notFound('Post not found');
  if (post.author_id !== uid) return unauthorized('Not your post');
  
  await env.DB.prepare('UPDATE posts SET archived = 1, trashed_at = ? WHERE id = ?').bind(Date.now(), postId).run();
  return ok({ success: true });
}

async function getPostReactions(env, postId) {
  const post = await env.DB.prepare('SELECT reactions_map FROM posts WHERE id = ?').bind(postId).first();
  if (!post) return notFound('Post not found');
  
  return ok(JSON.parse(post.reactions_map || '{}'));
}

async function addReaction(env, postId, uid, data) {
  const { type } = data; // like, love, haha, wow, sad, angry
  const validTypes = ['like', 'love', 'haha', 'wow', 'sad', 'angry'];
  if (!validTypes.includes(type)) return badRequest('Invalid reaction type');
  
  const post = await env.DB.prepare('SELECT reactions_map, likes FROM posts WHERE id = ?').bind(postId).first();
  if (!post) return notFound('Post not found');
  
  const reactions = JSON.parse(post.reactions_map || '{}');
  const isNew = !reactions[uid];
  reactions[uid] = { userId: uid, type, createdAt: Date.now() };
  
  await env.DB.prepare(
    'UPDATE posts SET reactions_map = ?, likes = ? WHERE id = ?'
  ).bind(JSON.stringify(reactions), Object.keys(reactions).length, postId).run();
  
  return ok({ success: true, reaction: reactions[uid], isNew });
}

async function removeReaction(env, postId, uid) {
  const post = await env.DB.prepare('SELECT reactions_map FROM posts WHERE id = ?').bind(postId).first();
  if (!post) return notFound('Post not found');
  
  const reactions = JSON.parse(post.reactions_map || '{}');
  delete reactions[uid];
  
  await env.DB.prepare(
    'UPDATE posts SET reactions_map = ?, likes = ? WHERE id = ?'
  ).bind(JSON.stringify(reactions), Object.keys(reactions).length, postId).run();
  
  return ok({ success: true });
}

async function getComments(env, postId) {
  const comments = await env.DB.prepare(
    'SELECT * FROM comments WHERE post_id = ? ORDER BY created_at ASC'
  ).bind(postId).all();
  
  // Parse JSON fields
  const parsed = comments.results.map(c => ({
    ...c,
    liked_by: JSON.parse(c.liked_by || '[]'),
    reactions: JSON.parse(c.reactions || '{}'),
    mentions: JSON.parse(c.mentions || '[]'),
  }));
  
  return ok(parsed);
}

async function addComment(env, postId, uid, data) {
  // Check daily comment limit (500 per day)
  const dayStart = new Date();
  dayStart.setHours(0, 0, 0, 0);
  const dayStartMs = dayStart.getTime();
  const dayEndMs = dayStartMs + 86400000;
  
  const commentsToday = await env.DB.prepare(
    'SELECT COUNT(*) as count FROM comments WHERE author_id = ? AND created_at >= ? AND created_at < ?'
  ).bind(uid, dayStartMs, dayEndMs).first();
  
  if (commentsToday && commentsToday.count >= 500) {
    return badRequest('Daily comment limit reached (500 per day). Try again tomorrow.');
  }
  
  const commentId = crypto.randomUUID();
  const now = Date.now();
  
  const comment = {
    id: commentId,
    post_id: postId,
    author_id: uid,
    author_name: data.authorName || 'User',
    author_photo: data.authorPhoto || '',
    content: data.content || '',
    image_url: data.imageUrl || '',
    parent_id: data.parentId || '',
    created_at: now,
    liked_by: '[]',
    reactions: '{}',
  };
  
  await env.DB.prepare(
    `INSERT INTO comments (id, post_id, author_id, author_name, author_photo, content,
      image_url, parent_id, created_at, liked_by, reactions)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
  ).bind(
    comment.id, comment.post_id, comment.author_id, comment.author_name,
    comment.author_photo, comment.content, comment.image_url, comment.parent_id,
    comment.created_at, comment.liked_by, comment.reactions
  ).run();
  
  // Increment comment count on post
  await env.DB.prepare('UPDATE posts SET comments = comments + 1 WHERE id = ?').bind(postId).run();
  
  return created(comment);
}

async function updateComment(env, postId, commentId, uid, data) {
  const comment = await env.DB.prepare('SELECT author_id FROM comments WHERE id = ? AND post_id = ?').bind(commentId, postId).first();
  if (!comment) return notFound('Comment not found');
  if (comment.author_id !== uid) return unauthorized('Not your comment');
  
  const allowed = ['content', 'image_url'];
  const updates = [];
  const values = [];
  
  for (const key of allowed) {
    if (data[key] !== undefined) {
      updates.push(`${key} = ?`);
      values.push(data[key]);
    }
  }
  
  if (updates.length === 0) return badRequest('No valid fields to update');
  
  updates.push('edited = 1');
  values.push(commentId);
  
  await env.DB.prepare(`UPDATE comments SET ${updates.join(', ')} WHERE id = ?`).bind(...values).run();
  
  return ok({ success: true });
}

async function deleteComment(env, postId, commentId, uid) {
  const comment = await env.DB.prepare('SELECT author_id FROM comments WHERE id = ? AND post_id = ?').bind(commentId, postId).first();
  if (!comment) return notFound('Comment not found');
  
  // Allow author or post author to delete
  const post = await env.DB.prepare('SELECT author_id FROM posts WHERE id = ?').bind(postId).first();
  if (comment.author_id !== uid && post?.author_id !== uid) return unauthorized('Cannot delete this comment');
  
  await env.DB.prepare('DELETE FROM comments WHERE id = ?').bind(commentId).run();
  await env.DB.prepare('UPDATE posts SET comments = comments - 1 WHERE id = ? AND comments > 0').bind(postId).run();
  
  return ok({ success: true });
}

async function sharePost(env, postId, uid, data) {
  // Create a new post as a share
  const sharedPostId = crypto.randomUUID();
  const now = Date.now();
  
  const original = await env.DB.prepare('SELECT * FROM posts WHERE id = ?').bind(postId).first();
  if (!original) return notFound('Post not found');
  
  const sharedPost = {
    id: sharedPostId,
    author_id: uid,
    author_name: data.authorName || 'User',
    author_photo: data.authorPhoto || '',
    content: data.content || '',
    media_url: '',
    media_type: 'none',
    mentions: '[]',
    feeling: '',
    privacy: data.privacy || 'Public',
    location: '',
    group_id: '',
    post_type: 'shared',
    bg_color: 0,
    created_at: now,
    author_verified: data.authorVerified || 0,
    author_tick_level: data.authorTickLevel || 'none',
    shared_from_id: postId,
    shared_from_author: original.author_name,
    shared_from_content: original.content,
    shared_from_media_url: original.media_url,
    shared_from_media_type: original.media_type,
  };
  
  await env.DB.prepare(
    `INSERT INTO posts (id, author_id, author_name, author_photo, content, media_url, media_type,
      mentions, feeling, privacy, group_id, post_type, bg_color, created_at,
      author_verified, author_tick_level, shared_from_id, shared_from_author,
      shared_from_content, shared_from_media_url, shared_from_media_type)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
  ).bind(
    sharedPost.id, sharedPost.author_id, sharedPost.author_name, sharedPost.author_photo,
    sharedPost.content, sharedPost.media_url, sharedPost.media_type, sharedPost.mentions,
    sharedPost.feeling, sharedPost.privacy, sharedPost.group_id, sharedPost.post_type,
    sharedPost.bg_color, sharedPost.created_at, sharedPost.author_verified,
    sharedPost.author_tick_level, sharedPost.shared_from_id, sharedPost.shared_from_author,
    sharedPost.shared_from_content, sharedPost.shared_from_media_url, sharedPost.shared_from_media_type
  ).run();
  
  // Increment share count on original
  await env.DB.prepare('UPDATE posts SET shares = shares + 1 WHERE id = ?').bind(postId).run();
  
  return created({ postId: sharedPostId });
}

async function savePost(env, postId, uid) {
  const post = await env.DB.prepare('SELECT saved_by FROM posts WHERE id = ?').bind(postId).first();
  if (!post) return notFound('Post not found');
  
  const saved = JSON.parse(post.saved_by || '[]');
  if (saved.includes(uid)) return ok({ success: true, alreadySaved: true });
  
  saved.push(uid);
  await env.DB.prepare('UPDATE posts SET saved_by = ? WHERE id = ?').bind(JSON.stringify(saved), postId).run();
  
  return ok({ success: true });
}

async function unsavePost(env, postId, uid) {
  const post = await env.DB.prepare('SELECT saved_by FROM posts WHERE id = ?').bind(postId).first();
  if (!post) return notFound('Post not found');
  
  const saved = JSON.parse(post.saved_by || '[]').filter(s => s !== uid);
  await env.DB.prepare('UPDATE posts SET saved_by = ? WHERE id = ?').bind(JSON.stringify(saved), postId).run();
  
  return ok({ success: true });
}

async function recordView(env, postId, uid) {
  // View counting is handled by Cloudflare Analytics Engine
  // This endpoint just acknowledges the view
  await env.DB.prepare('UPDATE posts SET views = views + 1 WHERE id = ?').bind(postId).run();
  return ok({ success: true });
}

async function getFeed(env, uid, cursor, limit, type, groupId) {
  let query = '';
  let params = [];
  
  if (type === 'favorites') {
    const user = await env.DB.prepare('SELECT favorite_uids FROM users WHERE uid = ?').bind(uid).first();
    const favorites = JSON.parse(user?.favorite_uids || '[]');
    if (favorites.length === 0) return ok({ posts: [], cursor: null });
    
    const placeholders = favorites.map(() => '?').join(',');
    query = `SELECT * FROM posts WHERE author_id IN (${placeholders}) AND archived = 0 AND privacy IN ('Public', 'Friends')`;
    params = favorites;
  } else if (type === 'group' && groupId) {
    query = `SELECT * FROM posts WHERE group_id = ? AND archived = 0 AND group_approval_status = 'approved'`;
    params = [groupId];
  } else {
    // Home feed - posts from friends + public posts
    const user = await env.DB.prepare('SELECT following FROM users WHERE uid = ?').bind(uid).first();
    const following = JSON.parse(user?.following || '[]');
    following.push(uid); // Include own posts
    
    if (following.length === 0) {
      // Just public posts
      query = `SELECT * FROM posts WHERE archived = 0 AND privacy = 'Public'`;
      params = [];
    } else {
      const placeholders = following.map(() => '?').join(',');
      query = `SELECT * FROM posts WHERE archived = 0 AND (author_id IN (${placeholders}) OR privacy = 'Public')`;
      params = following;
    }
  }
  
  query += ` ORDER BY created_at DESC LIMIT ?`;
  params.push(limit + 1); // +1 to check if more
  
  if (cursor) {
    query += ` AND created_at < ?`;
    params.push(parseInt(cursor));
  }
  
  const posts = await env.DB.prepare(query).bind(...params).all();
  
  let hasMore = posts.results.length > limit;
  const results = hasMore ? posts.results.slice(0, limit) : posts.results;
  const nextCursor = hasMore ? results[results.length - 1].created_at : null;
  
  // Parse JSON fields
  const parsed = results.map(p => ({
    ...p,
    mentions: JSON.parse(p.mentions || '[]'),
    saved_by: JSON.parse(p.saved_by || '[]'),
    subscribed_uids: JSON.parse(p.subscribed_uids || '[]'),
    subscribed_by: JSON.parse(p.subscribed_by || '[]'),
    media_urls: JSON.parse(p.media_urls || '[]'),
    tagged_uids: JSON.parse(p.tagged_uids || '[]'),
    tagged_names: JSON.parse(p.tagged_names || '[]'),
    poll_options: JSON.parse(p.poll_options || '[]'),
    poll_votes: JSON.parse(p.poll_votes || '{}'),
    inline_comments: JSON.parse(p.inline_comments || '[]'),
    reactions_map: JSON.parse(p.reactions_map || '{}'),
  }));
  
  return ok({ posts: parsed, cursor: nextCursor });
}

// Chat functions
async function createChat(env, uid, data) {
  const { participantIds, isGroup, groupName, groupPhoto } = data;
  
  if (isGroup) {
    // Create group chat
    const chatId = crypto.randomUUID();
    const now = Date.now();
    
    const participants = [uid, ...(participantIds || [])];
    const adminIds = [uid];
    
    await env.DB.prepare(
      `INSERT INTO chats (id, is_group, group_name, group_photo, created_by, admin_ids, participants, last_message_time)
       VALUES (?, 1, ?, ?, ?, ?, ?, ?)`
    ).bind(chatId, groupName || 'Group', groupPhoto || '', uid, JSON.stringify(adminIds), JSON.stringify(participants), now).run();
    
    return created({ chatId });
  } else {
    // 1:1 chat - canonical ID
    const otherUid = participantIds?.[0];
    if (!otherUid) return badRequest('Missing participant');
    
    const chatId = [uid, otherUid].sort().join('_');
    
    // Check if exists
    const existing = await env.DB.prepare('SELECT id FROM chats WHERE id = ?').bind(chatId).first();
    if (existing) return ok({ chatId, exists: true });
    
    const now = Date.now();
    await env.DB.prepare(
      `INSERT INTO chats (id, is_group, participants, created_by, last_message_time)
       VALUES (?, 0, ?, ?, ?)`
    ).bind(chatId, JSON.stringify([uid, otherUid]), uid, now).run();
    
    return created({ chatId, exists: false });
  }
}

async function getChat(env, chatId, uid) {
  const chat = await env.DB.prepare('SELECT * FROM chats WHERE id = ?').bind(chatId).first();
  if (!chat) return notFound('Chat not found');
  
  // Verify user is participant
  const participants = JSON.parse(chat.participants || '[]');
  if (!participants.includes(uid)) return unauthorized('Not a participant');
  
  // Parse JSON fields
  chat.admin_ids = JSON.parse(chat.admin_ids || '[]');
  chat.participants = participants;
  chat.typing = JSON.parse(chat.typing || '{}');
  chat.participant_names = JSON.parse(chat.participant_names || '{}');
  chat.participant_photos = JSON.parse(chat.participant_photos || '{}');
  
  return ok(chat);
}

async function updateChat(env, chatId, uid, data) {
  const chat = await env.DB.prepare('SELECT created_by, admin_ids FROM chats WHERE id = ?').bind(chatId).first();
  if (!chat) return notFound('Chat not found');
  
  const adminIds = JSON.parse(chat.admin_ids || '[]');
  const isAdmin = adminIds.includes(uid) || chat.created_by === uid;
  if (!isAdmin) return unauthorized('Not admin');
  
  const allowed = ['group_name', 'group_photo', 'only_admins_can_send'];
  const updates = [];
  const values = [];
  
  for (const key of allowed) {
    if (data[key] !== undefined) {
      updates.push(`${key} = ?`);
      values.push(data[key]);
    }
  }
  
  if (updates.length === 0) return badRequest('No valid fields to update');
  
  values.push(chatId);
  await env.DB.prepare(`UPDATE chats SET ${updates.join(', ')} WHERE id = ?`).bind(...values).run();
  
  return ok({ success: true });
}

async function getMessages(env, chatId, uid, searchParams) {
  const cursor = searchParams.get('cursor');
  const limit = parseInt(searchParams.get('limit') || '50');
  
  const chat = await env.DB.prepare('SELECT participants FROM chats WHERE id = ?').bind(chatId).first();
  if (!chat) return notFound('Chat not found');
  
  const participants = JSON.parse(chat.participants || '[]');
  if (!participants.includes(uid)) return unauthorized('Not a participant');
  
  let query = 'SELECT * FROM messages WHERE chat_id = ? AND is_deleted = 0 ORDER BY created_at DESC LIMIT ?';
  let params = [chatId, limit + 1];
  
  if (cursor) {
    query = 'SELECT * FROM messages WHERE chat_id = ? AND is_deleted = 0 AND created_at < ? ORDER BY created_at DESC LIMIT ?';
    params = [chatId, parseInt(cursor), limit + 1];
  }
  
  const messages = await env.DB.prepare(query).bind(...params).all();
  
  let hasMore = messages.results.length > limit;
  const results = hasMore ? messages.results.slice(0, limit) : messages.results;
  const nextCursor = hasMore ? results[results.length - 1].created_at : null;
  
  // Parse JSON fields
  const parsed = results.map(m => ({
    ...m,
    reactions: JSON.parse(m.reactions || '{}'),
    reactor_names: JSON.parse(m.reactor_names || '{}'),
    reactor_photos: JSON.parse(m.reactor_photos || '{}'),
  }));
  
  return ok({ messages: parsed, cursor: nextCursor });
}

async function sendMessage(env, chatId, uid, data) {
  const chat = await env.DB.prepare('SELECT participants, is_group FROM chats WHERE id = ?').bind(chatId).first();
  if (!chat) return notFound('Chat not found');
  
  const participants = JSON.parse(chat.participants || '[]');
  if (!participants.includes(uid)) return unauthorized('Not a participant');
  
  const messageId = crypto.randomUUID();
  const now = Date.now();
  
  const message = {
    id: messageId,
    chat_id: chatId,
    sender_id: uid,
    sender_name: data.senderName || 'User',
    sender_photo: data.senderPhoto || '',
    receiver_id: data.receiverId || (chat.is_group ? '' : participants.find(p => p !== uid) || ''),
    text: data.text || '',
    image_url: data.imageUrl || '',
    video_url: data.videoUrl || '',
    type: data.type || 'text',
    voice_url: data.voiceUrl || '',
    broadcast_id: data.broadcastId || '',
    live_id: data.liveId || '',
    live_host_id: data.liveHostId || '',
    live_host_name: data.liveHostName || '',
    live_host_photo: data.liveHostPhoto || '',
    live_title: data.liveTitle || '',
    reply_to_text: data.replyToText || '',
    reply_to_sender: data.replyToSender || '',
    call_type: data.callType || '',
    call_status: data.callStatus || '',
    call_duration_sec: data.callDurationSec || 0,
    forwarded: data.forwarded || 0,
    voice_duration_sec: data.voiceDurationSec || 0,
    is_seen: 0,
    is_delivered: 1,
    is_deleted: 0,
    created_at: now,
    reactions: '{}',
    reactor_names: '{}',
    reactor_photos: '{}',
  };
  
  await env.DB.prepare(
    `INSERT INTO messages (id, chat_id, sender_id, sender_name, sender_photo, receiver_id, text,
      image_url, video_url, type, voice_url, broadcast_id, live_id, live_host_id, live_host_name,
      live_host_photo, live_title, reply_to_text, reply_to_sender, call_type, call_status,
      call_duration_sec, forwarded, voice_duration_sec, is_seen, is_delivered, is_deleted,
      created_at, reactions, reactor_names, reactor_photos)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
  ).bind(
    message.id, message.chat_id, message.sender_id, message.sender_name, message.sender_photo,
    message.receiver_id, message.text, message.image_url, message.video_url, message.type,
    message.voice_url, message.broadcast_id, message.live_id, message.live_host_id,
    message.live_host_name, message.live_host_photo, message.live_title, message.reply_to_text,
    message.reply_to_sender, message.call_type, message.call_status, message.call_duration_sec,
    message.forwarded, message.voice_duration_sec, message.is_seen, message.is_delivered,
    message.is_deleted, message.created_at, message.reactions, message.reactor_names, message.reactor_photos
  ).run();
  
  // Update chat last message
  const lastMessage = message.text || (message.image_url ? '📷 Photo' : message.video_url ? '🎥 Video' : message.voice_url ? '🎤 Voice' : 'Message');
  await env.DB.prepare(
    'UPDATE chats SET last_message = ?, last_message_time = ? WHERE id = ?'
  ).bind(lastMessage, now, chatId).run();
  
  return created({ messageId, ...message });
}

async function updateMessage(env, chatId, messageId, uid, data) {
  const message = await env.DB.prepare('SELECT sender_id FROM messages WHERE id = ? AND chat_id = ?').bind(messageId, chatId).first();
  if (!message) return notFound('Message not found');
  if (message.sender_id !== uid) return unauthorized('Not your message');
  
  const allowed = ['text', 'is_seen', 'is_delivered'];
  const updates = [];
  const values = [];
  
  for (const key of allowed) {
    if (data[key] !== undefined) {
      updates.push(`${key} = ?`);
      values.push(data[key]);
    }
  }
  
  if (updates.length === 0) return badRequest('No valid fields to update');
  
  values.push(messageId);
  await env.DB.prepare(`UPDATE messages SET ${updates.join(', ')} WHERE id = ?`).bind(...values).run();
  
  return ok({ success: true });
}

async function deleteMessage(env, chatId, messageId, uid) {
  const message = await env.DB.prepare('SELECT sender_id FROM messages WHERE id = ? AND chat_id = ?').bind(messageId, chatId).first();
  if (!message) return notFound('Message not found');
  if (message.sender_id !== uid) return unauthorized('Not your message');
  
  await env.DB.prepare('UPDATE messages SET is_deleted = 1 WHERE id = ?').bind(messageId).run();
  return ok({ success: true });
}

async function setTyping(env, chatId, uid, typing) {
  const chat = await env.DB.prepare('SELECT participants FROM chats WHERE id = ?').bind(chatId).first();
  if (!chat) return notFound('Chat not found');
  
  const participants = JSON.parse(chat.participants || '[]');
  if (!participants.includes(uid)) return unauthorized('Not a participant');
  
  const typingMap = JSON.parse(chat.typing || '{}');
  if (typing) typingMap[uid] = true;
  else delete typingMap[uid];
  
  await env.DB.prepare('UPDATE chats SET typing = ? WHERE id = ?').bind(JSON.stringify(typingMap), chatId).run();
  
  return ok({ success: true });
}

async function markRead(env, chatId, uid, messageId) {
  await env.DB.prepare(
    'UPDATE messages SET is_seen = 1 WHERE chat_id = ? AND id <= ? AND sender_id != ?'
  ).bind(chatId, messageId, uid).run();
  
  return ok({ success: true });
}

async function getParticipants(env, chatId, uid) {
  const chat = await env.DB.prepare('SELECT participants, participant_names, participant_photos FROM chats WHERE id = ?').bind(chatId).first();
  if (!chat) return notFound('Chat not found');
  
  const participants = JSON.parse(chat.participants || '[]');
  if (!participants.includes(uid)) return unauthorized('Not a participant');
  
  const names = JSON.parse(chat.participant_names || '{}');
  const photos = JSON.parse(chat.participant_photos || '{}');
  
  const details = await Promise.all(participants.map(async (p) => {
    const user = await env.DB.prepare('SELECT uid, display_name, photo_url, is_verified, tick_level FROM users WHERE uid = ?').bind(p).first();
    return user || { uid: p, display_name: names[p] || 'User', photo_url: photos[p] || '' };
  }));
  
  return ok(details);
}

async function addParticipant(env, chatId, uid, data) {
  const chat = await env.DB.prepare('SELECT participants, admin_ids, created_by, is_group FROM chats WHERE id = ?').bind(chatId).first();
  if (!chat) return notFound('Chat not found');
  if (!chat.is_group) return badRequest('Cannot add participants to 1:1 chat');
  
  const adminIds = JSON.parse(chat.admin_ids || '[]');
  const isAdmin = adminIds.includes(uid) || chat.created_by === uid;
  if (!isAdmin) return unauthorized('Not admin');
  
  const { userId } = data;
  const participants = JSON.parse(chat.participants || '[]');
  if (participants.includes(userId)) return ok({ success: true, alreadyParticipant: true });
  
  participants.push(userId);
  await env.DB.prepare('UPDATE chats SET participants = ? WHERE id = ?').bind(JSON.stringify(participants), chatId).run();
  
  return ok({ success: true });
}

async function removeParticipant(env, chatId, uid, userId) {
  const chat = await env.DB.prepare('SELECT participants, admin_ids, created_by, is_group FROM chats WHERE id = ?').bind(chatId).first();
  if (!chat) return notFound('Chat not found');
  if (!chat.is_group) return badRequest('Cannot remove from 1:1 chat');
  
  const adminIds = JSON.parse(chat.admin_ids || '[]');
  const isAdmin = adminIds.includes(uid) || chat.created_by === uid;
  if (!isAdmin && uid !== userId) return unauthorized('Not admin');
  
  const participants = JSON.parse(chat.participants || '[]').filter(p => p !== userId);
  await env.DB.prepare('UPDATE chats SET participants = ? WHERE id = ?').bind(JSON.stringify(participants), chatId).run();
  
  return ok({ success: true });
}

async function addAdmin(env, chatId, uid, data) {
  const chat = await env.DB.prepare('SELECT admin_ids, created_by FROM chats WHERE id = ?').bind(chatId).first();
  if (!chat) return notFound('Chat not found');
  if (chat.created_by !== uid) return unauthorized('Only creator can add admins');
  
  const { userId } = data;
  const adminIds = JSON.parse(chat.admin_ids || '[]');
  if (adminIds.includes(userId)) return ok({ success: true, alreadyAdmin: true });
  
  adminIds.push(userId);
  await env.DB.prepare('UPDATE chats SET admin_ids = ? WHERE id = ?').bind(JSON.stringify(adminIds), chatId).run();
  
  return ok({ success: true });
}

async function removeAdmin(env, chatId, uid, userId) {
  const chat = await env.DB.prepare('SELECT admin_ids, created_by FROM chats WHERE id = ?').bind(chatId).first();
  if (!chat) return notFound('Chat not found');
  if (chat.created_by !== uid) return unauthorized('Only creator can remove admins');
  if (userId === chat.created_by) return badRequest('Cannot remove creator');
  
  const adminIds = JSON.parse(chat.admin_ids || '[]').filter(a => a !== userId);
  await env.DB.prepare('UPDATE chats SET admin_ids = ? WHERE id = ?').bind(JSON.stringify(adminIds), chatId).run();
  
  return ok({ success: true });
}

// Group functions
async function createGroup(env, uid, data) {
  const groupId = crypto.randomUUID();
  const now = Date.now();
  
  const group = {
    id: groupId,
    name: data.name || 'Group',
    about: data.about || '',
    category: data.category || 'Community',
    photo_url: data.photoUrl || '',
    cover_url: data.coverUrl || '',
    admin_id: uid,
    privacy: data.privacy || 'Public',
    rules: data.rules || '',
    pinned_post_id: '',
    post_permission: data.postPermission || 'Everyone',
    require_post_approval: data.requirePostApproval ? 1 : 0,
    require_join_approval: data.requireJoinApproval ? 1 : 0,
    created_at: now,
    members: JSON.stringify([uid]),
    admin_ids: JSON.stringify([uid]),
    moderator_ids: JSON.stringify([]),
    pending_member_ids: JSON.stringify([]),
    banned_member_ids: JSON.stringify([]),
    invited_uids: JSON.stringify([]),
  };
  
  await env.DB.prepare(
    `INSERT INTO groups (id, name, about, category, photo_url, cover_url, admin_id, privacy, rules,
      pinned_post_id, post_permission, require_post_approval, require_join_approval, created_at,
      members, admin_ids, moderator_ids, pending_member_ids, banned_member_ids, invited_uids)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
  ).bind(
    group.id, group.name, group.about, group.category, group.photo_url, group.cover_url,
    group.admin_id, group.privacy, group.rules, group.pinned_post_id, group.post_permission,
    group.require_post_approval, group.require_join_approval, group.created_at,
    group.members, group.admin_ids, group.moderator_ids, group.pending_member_ids,
    group.banned_member_ids, group.invited_uids
  ).run();
  
  return created(group);
}

async function getGroup(env, groupId, uid) {
  const group = await env.DB.prepare('SELECT * FROM groups WHERE id = ?').bind(groupId).first();
  if (!group) return notFound('Group not found');
  
  // Check privacy
  if (group.privacy === 'Private') {
    const members = JSON.parse(group.members || '[]');
    const pending = JSON.parse(group.pending_member_ids || '[]');
    if (!members.includes(uid) && !pending.includes(uid)) return notFound('Group not found');
  }
  
  // Parse JSON fields
  group.members = JSON.parse(group.members || '[]');
  group.admin_ids = JSON.parse(group.admin_ids || '[]');
  group.moderator_ids = JSON.parse(group.moderator_ids || '[]');
  group.pending_member_ids = JSON.parse(group.pending_member_ids || '[]');
  group.banned_member_ids = JSON.parse(group.banned_member_ids || '[]');
  group.invited_uids = JSON.parse(group.invited_uids || '[]');
  
  return ok(group);
}

async function updateGroup(env, groupId, uid, data) {
  const group = await env.DB.prepare('SELECT admin_id, admin_ids FROM groups WHERE id = ?').bind(groupId).first();
  if (!group) return notFound('Group not found');
  
  const adminIds = JSON.parse(group.admin_ids || '[]');
  const isAdmin = adminIds.includes(uid) || group.admin_id === uid;
  if (!isAdmin) return unauthorized('Not admin');
  
  const allowed = ['name', 'about', 'category', 'photo_url', 'cover_url', 'privacy', 'rules',
    'pinned_post_id', 'post_permission', 'require_post_approval', 'require_join_approval'];
  
  const updates = [];
  const values = [];
  
  for (const key of allowed) {
    if (data[key] !== undefined) {
      updates.push(`${key} = ?`);
      values.push(typeof data[key] === 'boolean' ? (data[key] ? 1 : 0) : data[key]);
    }
  }
  
  if (updates.length === 0) return badRequest('No valid fields to update');
  
  values.push(groupId);
  await env.DB.prepare(`UPDATE groups SET ${updates.join(', ')} WHERE id = ?`).bind(...values).run();
  
  return ok({ success: true });
}

async function joinGroup(env, groupId, uid) {
  const group = await env.DB.prepare('SELECT privacy, members, pending_member_ids, require_join_approval FROM groups WHERE id = ?').bind(groupId).first();
  if (!group) return notFound('Group not found');
  
  const members = JSON.parse(group.members || '[]');
  if (members.includes(uid)) return ok({ success: true, alreadyMember: true });
  
  const pending = JSON.parse(group.pending_member_ids || '[]');
  
  if (group.privacy === 'Public' && !group.require_join_approval) {
    // Direct join
    members.push(uid);
    await env.DB.prepare('UPDATE groups SET members = ? WHERE id = ?').bind(JSON.stringify(members), groupId).run();
    return ok({ success: true, status: 'joined' });
  } else {
    // Request to join
    if (!pending.includes(uid)) {
      pending.push(uid);
      await env.DB.prepare('UPDATE groups SET pending_member_ids = ? WHERE id = ?').bind(JSON.stringify(pending), groupId).run();
    }
    return ok({ success: true, status: 'pending' });
  }
}

async function leaveGroup(env, groupId, uid) {
  const group = await env.DB.prepare('SELECT admin_id, members, admin_ids FROM groups WHERE id = ?').bind(groupId).first();
  if (!group) return notFound('Group not found');
  
  if (group.admin_id === uid) return badRequest('Admin cannot leave, transfer ownership first');
  
  let members = JSON.parse(group.members || '[]');
  members = members.filter(m => m !== uid);
  
  let adminIds = JSON.parse(group.admin_ids || '[]');
  adminIds = adminIds.filter(a => a !== uid);
  
  await env.DB.prepare('UPDATE groups SET members = ?, admin_ids = ? WHERE id = ?')
    .bind(JSON.stringify(members), JSON.stringify(adminIds), groupId).run();
  
  return ok({ success: true });
}

async function getGroupMembers(env, groupId, uid) {
  const group = await env.DB.prepare('SELECT members, admin_ids, moderator_ids FROM groups WHERE id = ?').bind(groupId).first();
  if (!group) return notFound('Group not found');
  
  const members = JSON.parse(group.members || '[]');
  if (!members.includes(uid)) return unauthorized('Not a member');
  
  const adminIds = JSON.parse(group.admin_ids || '[]');
  const moderatorIds = JSON.parse(group.moderator_ids || '[]');
  
  const placeholders = members.map(() => '?').join(',');
  const memberDetails = await env.DB.prepare(
    `SELECT uid, display_name, photo_url, is_verified, tick_level FROM users WHERE uid IN (${placeholders})`
  ).bind(...members).all();
  
  const enriched = memberDetails.results.map(m => ({
    ...m,
    isAdmin: adminIds.includes(m.uid),
    isModerator: moderatorIds.includes(m.uid),
  }));
  
  return ok(enriched);
}

async function getPendingMembers(env, groupId, uid) {
  const group = await env.DB.prepare('SELECT admin_id, admin_ids, pending_member_ids FROM groups WHERE id = ?').bind(groupId).first();
  if (!group) return notFound('Group not found');
  
  const adminIds = JSON.parse(group.admin_ids || '[]');
  const isAdmin = adminIds.includes(uid) || group.admin_id === uid;
  if (!isAdmin) return unauthorized('Not admin');
  
  const pending = JSON.parse(group.pending_member_ids || '[]');
  if (pending.length === 0) return ok([]);
  
  const placeholders = pending.map(() => '?').join(',');
  const pendingDetails = await env.DB.prepare(
    `SELECT uid, display_name, photo_url FROM users WHERE uid IN (${placeholders})`
  ).bind(...pending).all();
  
  return ok(pendingDetails.results);
}

async function approveMember(env, groupId, uid, userId) {
  const group = await env.DB.prepare('SELECT admin_id, admin_ids, members, pending_member_ids FROM groups WHERE id = ?').bind(groupId).first();
  if (!group) return notFound('Group not found');
  
  const adminIds = JSON.parse(group.admin_ids || '[]');
  const isAdmin = adminIds.includes(uid) || group.admin_id === uid;
  if (!isAdmin) return unauthorized('Not admin');
  
  let pending = JSON.parse(group.pending_member_ids || '[]');
  pending = pending.filter(p => p !== userId);
  
  let members = JSON.parse(group.members || '[]');
  if (!members.includes(userId)) members.push(userId);
  
  await env.DB.prepare('UPDATE groups SET pending_member_ids = ?, members = ? WHERE id = ?')
    .bind(JSON.stringify(pending), JSON.stringify(members), groupId).run();
  
  return ok({ success: true });
}

async function getGroupPosts(env, groupId, uid, cursor, limit) {
  const group = await env.DB.prepare('SELECT members, privacy FROM groups WHERE id = ?').bind(groupId).first();
  if (!group) return notFound('Group not found');
  
  const members = JSON.parse(group.members || '[]');
  if (!members.includes(uid) && group.privacy !== 'Public') return unauthorized('Not a member');
  
  let query = 'SELECT * FROM posts WHERE group_id = ? AND archived = 0 AND group_approval_status = \'approved\' ORDER BY created_at DESC LIMIT ?';
  let params = [groupId, limit + 1];
  
  if (cursor) {
    query = 'SELECT * FROM posts WHERE group_id = ? AND archived = 0 AND group_approval_status = \'approved\' AND created_at < ? ORDER BY created_at DESC LIMIT ?';
    params = [groupId, parseInt(cursor), limit + 1];
  }
  
  const posts = await env.DB.prepare(query).bind(...params).all();
  
  let hasMore = posts.results.length > limit;
  const results = hasMore ? posts.results.slice(0, limit) : posts.results;
  const nextCursor = hasMore ? results[results.length - 1].created_at : null;
  
  const parsed = results.map(p => ({
    ...p,
    mentions: JSON.parse(p.mentions || '[]'),
    saved_by: JSON.parse(p.saved_by || '[]'),
    subscribed_uids: JSON.parse(p.subscribed_uids || '[]'),
    subscribed_by: JSON.parse(p.subscribed_by || '[]'),
    media_urls: JSON.parse(p.media_urls || '[]'),
    tagged_uids: JSON.parse(p.tagged_uids || '[]'),
    tagged_names: JSON.parse(p.tagged_names || '[]'),
    poll_options: JSON.parse(p.poll_options || '[]'),
    poll_votes: JSON.parse(p.poll_votes || '{}'),
    inline_comments: JSON.parse(p.inline_comments || '[]'),
    reactions_map: JSON.parse(p.reactions_map || '{}'),
  }));
  
  return ok({ posts: parsed, cursor: nextCursor });
}

// Friend request functions
async function getFriendRequests(env, uid, status) {
  const requests = await env.DB.prepare(
    'SELECT * FROM friend_requests WHERE to_uid = ? AND status = ? ORDER BY created_at DESC'
  ).bind(uid, status).all();
  
  return ok(requests.results);
}

async function sendFriendRequest(env, uid, data) {
  const { toUid } = data;
  if (!toUid) return badRequest('Missing toUid');
  if (uid === toUid) return badRequest('Cannot send request to yourself');
  
  // Check daily friend request limit (500 per day)
  const dayStart = new Date();
  dayStart.setHours(0, 0, 0, 0);
  const dayStartMs = dayStart.getTime();
  const dayEndMs = dayStartMs + 86400000;
  
  const sentToday = await env.DB.prepare(
    'SELECT COUNT(*) as count FROM friend_requests WHERE from_uid = ? AND created_at >= ? AND created_at < ?'
  ).bind(uid, dayStartMs, dayEndMs).first();
  
  if (sentToday && sentToday.count >= 500) {
    return badRequest('Daily friend request limit reached (500 per day). Try again tomorrow.');
  }
  
  const existing = await env.DB.prepare(
    'SELECT * FROM friend_requests WHERE from_uid = ? AND to_uid = ? AND status = \'pending\''
  ).bind(uid, toUid).first();
  
  if (existing) return ok({ success: true, alreadyPending: true, requestId: existing.id });
  
  const requestId = crypto.randomUUID();
  const now = Date.now();
  
  await env.DB.prepare(
    'INSERT INTO friend_requests (id, from_uid, from_name, from_photo, to_uid, status, created_at) VALUES (?, ?, ?, ?, ?, \'pending\', ?)'
  ).bind(requestId, uid, data.fromName || 'User', data.fromPhoto || '', toUid, now).run();
  
  // Create notification
  await env.DB.prepare(
    'INSERT INTO notifications (id, to_user_id, from_user_id, from_user_name, from_user_photo, type, text, is_read, created_at) VALUES (?, ?, ?, ?, ?, \'friend_request\', ?, 0, ?)'
  ).bind(crypto.randomUUID(), toUid, uid, data.fromName || 'User', data.fromPhoto || '', 'sent you a friend request', now).run();
  
  return created({ requestId });
}

async function acceptFriendRequest(env, requestId, uid) {
  const request = await env.DB.prepare('SELECT * FROM friend_requests WHERE id = ? AND to_uid = ?').bind(requestId, uid).first();
  if (!request) return notFound('Request not found');
  if (request.status !== 'pending') return badRequest('Request not pending');
  
  const fromUid = request.from_uid;
  const now = Date.now();
  
  // Check friend limit (5000 max) for both users before accepting
  const fromUser = await env.DB.prepare('SELECT followers, following, friends_count FROM users WHERE uid = ?').bind(fromUid).first();
  const toUser = await env.DB.prepare('SELECT followers, following, friends_count FROM users WHERE uid = ?').bind(uid).first();
  
  const MAX_FRIENDS = 5000;
  const fromFriendsCount = fromUser?.friends_count || 0;
  const toFriendsCount = toUser?.friends_count || 0;
  
  if (fromFriendsCount >= MAX_FRIENDS || toFriendsCount >= MAX_FRIENDS) {
    return badRequest(`Friend limit reached (max ${MAX_FRIENDS} friends). Users with 5000+ friends can only have followers.`);
  }
  
  // Update request status
  await env.DB.prepare('UPDATE friend_requests SET status = \'accepted\' WHERE id = ?').bind(requestId).run();
  
  // Add to each other's friends/followers
  let fromFollowers = JSON.parse(fromUser?.followers || '[]');
  let fromFollowing = JSON.parse(fromUser?.following || '[]');
  let toFollowers = JSON.parse(toUser?.followers || '[]');
  let toFollowing = JSON.parse(toUser?.following || '[]');
  
  if (!fromFollowers.includes(uid)) fromFollowers.push(uid);
  if (!fromFollowing.includes(uid)) fromFollowing.push(uid);
  if (!toFollowers.includes(fromUid)) toFollowers.push(fromUid);
  if (!toFollowing.includes(fromUid)) toFollowing.push(fromUid);
  
  // Increment friends_count for both users
  const newFromFriendsCount = fromFriendsCount + 1;
  const newToFriendsCount = toFriendsCount + 1;
  
  await env.DB.prepare('UPDATE users SET followers = ?, following = ?, friends_count = ? WHERE uid = ?')
    .bind(JSON.stringify(fromFollowers), JSON.stringify(fromFollowing), newFromFriendsCount, fromUid).run();
  await env.DB.prepare('UPDATE users SET followers = ?, following = ?, friends_count = ? WHERE uid = ?')
    .bind(JSON.stringify(toFollowers), JSON.stringify(toFollowing), newToFriendsCount, uid).run();
  
  // Create notification for sender
  await env.DB.prepare(
    'INSERT INTO notifications (id, to_user_id, from_user_id, from_user_name, from_user_photo, type, text, is_read, created_at) VALUES (?, ?, ?, ?, ?, \'friend_accept\', ?, 0, ?)'
  ).bind(crypto.randomUUID(), fromUid, uid, request.from_name, request.from_photo, 'accepted your friend request', now).run();
  
  return ok({ success: true });
}

async function declineFriendRequest(env, requestId, uid) {
  const request = await env.DB.prepare('SELECT * FROM friend_requests WHERE id = ? AND to_uid = ?').bind(requestId, uid).first();
  if (!request) return notFound('Request not found');
  
  await env.DB.prepare('UPDATE friend_requests SET status = \'declined\' WHERE id = ?').bind(requestId).run();
  
  return ok({ success: true });
}

async function cancelFriendRequest(env, requestId, uid) {
  const request = await env.DB.prepare('SELECT * FROM friend_requests WHERE id = ? AND from_uid = ?').bind(requestId, uid).first();
  if (!request) return notFound('Request not found');
  
  await env.DB.prepare('UPDATE friend_requests SET status = \'cancelled\' WHERE id = ?').bind(requestId).run();
  
  return ok({ success: true });
}

// Notification functions
async function getNotifications(env, uid, cursor, limit, unreadOnly) {
  let query = 'SELECT * FROM notifications WHERE to_user_id = ?';
  let params = [uid];
  
  if (unreadOnly) {
    query += ' AND is_read = 0';
  }
  
  query += ' ORDER BY created_at DESC LIMIT ?';
  params.push(limit + 1);
  
  if (cursor) {
    query += ' AND created_at < ?';
    params.push(parseInt(cursor));
  }
  
  const notifications = await env.DB.prepare(query).bind(...params).all();
  
  let hasMore = notifications.results.length > limit;
  const results = hasMore ? notifications.results.slice(0, limit) : notifications.results;
  const nextCursor = hasMore ? results[results.length - 1].created_at : null;
  
  return ok({ notifications: results, cursor: nextCursor });
}

async function markNotificationsRead(env, uid, notificationIds) {
  if (!notificationIds || !notificationIds.length) return badRequest('Missing notificationIds');
  
  const placeholders = notificationIds.map(() => '?').join(',');
  await env.DB.prepare(
    `UPDATE notifications SET is_read = 1 WHERE to_user_id = ? AND id IN (${placeholders})`
  ).bind(uid, ...notificationIds).run();
  
  return ok({ success: true });
}

async function markAllNotificationsRead(env, uid) {
  await env.DB.prepare('UPDATE notifications SET is_read = 1 WHERE to_user_id = ? AND is_read = 0').bind(uid).run();
  return ok({ success: true });
}

// Story functions
async function createStory(env, uid, data) {
  const storyId = crypto.randomUUID();
  const now = Date.now();
  const expiresAt = now + 24 * 60 * 60 * 1000; // 24 hours
  
  const story = {
    id: storyId,
    author_id: uid,
    author_name: data.authorName || 'User',
    author_photo: data.authorPhoto || '',
    media_url: data.mediaUrl || '',
    media_type: data.mediaType || 'image',
    caption: data.caption || '',
    music_mood: data.musicMood || '',
    privacy: data.privacy || 'Public',
    bg_color: data.bgColor || 0,
    font_style: data.fontStyle || 0,
    created_at: now,
    expires_at: expiresAt,
    viewed_by: '[]',
    reactions: '{}',
  };
  
  await env.DB.prepare(
    `INSERT INTO stories (id, author_id, author_name, author_photo, media_url, media_type, caption,
      music_mood, privacy, bg_color, font_style, created_at, expires_at, viewed_by, reactions)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
  ).bind(
    story.id, story.author_id, story.author_name, story.author_photo, story.media_url,
    story.media_type, story.caption, story.music_mood, story.privacy, story.bg_color,
    story.font_style, story.created_at, story.expires_at, story.viewed_by, story.reactions
  ).run();
  
  return created(story);
}

async function getStoriesFeed(env, uid) {
  const user = await env.DB.prepare('SELECT following FROM users WHERE uid = ?').bind(uid).first();
  const following = JSON.parse(user?.following || '[]');
  following.push(uid); // Include own stories
  
  if (following.length === 0) return ok({ stories: [] });
  
  const now = Date.now();
  const placeholders = following.map(() => '?').join(',');
  
  const stories = await env.DB.prepare(
    `SELECT * FROM stories WHERE author_id IN (${placeholders}) AND expires_at > ? ORDER BY created_at DESC`
  ).bind(...following, now).all();
  
  const parsed = stories.results.map(s => ({
    ...s,
    viewed_by: JSON.parse(s.viewed_by || '[]'),
    reactions: JSON.parse(s.reactions || '{}'),
  }));
  
  return ok({ stories: parsed });
}

async function getStory(env, storyId, uid) {
  const story = await env.DB.prepare('SELECT * FROM stories WHERE id = ?').bind(storyId).first();
  if (!story) return notFound('Story not found');
  
  // Check privacy
  if (story.privacy === 'Only me' && story.author_id !== uid) return notFound('Story not found');
  if (story.privacy === 'Friends') {
    const author = await env.DB.prepare('SELECT followers FROM users WHERE uid = ?').bind(story.author_id).first();
    const followers = JSON.parse(author?.followers || '[]');
    if (!followers.includes(uid) && story.author_id !== uid) return notFound('Story not found');
  }
  
  story.viewed_by = JSON.parse(story.viewed_by || '[]');
  story.reactions = JSON.parse(story.reactions || '{}');
  
  return ok(story);
}

async function deleteStory(env, storyId, uid) {
  const story = await env.DB.prepare('SELECT author_id FROM stories WHERE id = ?').bind(storyId).first();
  if (!story) return notFound('Story not found');
  if (story.author_id !== uid) return unauthorized('Not your story');
  
  await env.DB.prepare('DELETE FROM stories WHERE id = ?').bind(storyId).run();
  return ok({ success: true });
}

async function viewStory(env, storyId, uid) {
  const story = await env.DB.prepare('SELECT viewed_by FROM stories WHERE id = ?').bind(storyId).first();
  if (!story) return notFound('Story not found');
  
  const viewed = JSON.parse(story.viewed_by || '[]');
  if (!viewed.includes(uid)) {
    viewed.push(uid);
    await env.DB.prepare('UPDATE stories SET viewed_by = ? WHERE id = ?').bind(JSON.stringify(viewed), storyId).run();
  }
  
  return ok({ success: true });
}

async function reactStory(env, storyId, uid, data) {
  const { type } = data;
  const validTypes = ['like', 'love', 'haha', 'wow', 'sad', 'angry', 'fire'];
  if (!validTypes.includes(type)) return badRequest('Invalid reaction type');
  
  const story = await env.DB.prepare('SELECT reactions FROM stories WHERE id = ?').bind(storyId).first();
  if (!story) return notFound('Story not found');
  
  const reactions = JSON.parse(story.reactions || '{}');
  reactions[uid] = type;
  
  await env.DB.prepare('UPDATE stories SET reactions = ? WHERE id = ?').bind(JSON.stringify(reactions), storyId).run();
  
  return ok({ success: true, reaction: { uid, type } });
}

// Reel functions
async function createReel(env, uid, data) {
  const reelId = crypto.randomUUID();
  const now = Date.now();
  
  const reel = {
    id: reelId,
    author_id: uid,
    author_name: data.authorName || 'User',
    author_photo: data.authorPhoto || '',
    video_url: data.videoUrl || '',
    thumb_url: data.thumbUrl || '',
    caption: data.caption || '',
    duration_sec: data.durationSec || 0,
    created_at: now,
    is_from_post: data.isFromPost ? 1 : 0,
    mirror_horizontal: data.mirrorHorizontal ? 1 : 0,
    overlay_text: data.overlayText || '',
    overlay_color: data.overlayColor || 0xFFFFFFFF,
    overlay_bg_color: data.overlayBgColor || 0xCC000000,
    overlay_x: data.overlayX || 0.5,
    overlay_y: data.overlayY || 0.24,
    sticker_text: data.stickerText || '',
    sticker_x: data.stickerX || 0.78,
    sticker_y: data.stickerY || 0.32,
    sticker_scale: data.stickerScale || 1.0,
    filter_preset: data.filterPreset || 'none',
    filter_overlay_color: data.filterOverlayColor || 0,
    beauty_level: data.beautyLevel || 0,
    playback_speed: data.playbackSpeed || 1.0,
    mute_original_audio: data.muteOriginalAudio ? 1 : 0,
    audio_title: data.audioTitle || '',
    audio_url: data.audioUrl || '',
    sound_author_photo: data.soundAuthorPhoto || '',
    trim_start_ms: data.trimStartMs || 0,
    trim_end_ms: data.trimEndMs || 0,
  };
  
  await env.DB.prepare(
    `INSERT INTO reels (id, author_id, author_name, author_photo, video_url, thumb_url, caption,
      duration_sec, created_at, is_from_post, mirror_horizontal, overlay_text, overlay_color,
      overlay_bg_color, overlay_x, overlay_y, sticker_text, sticker_x, sticker_y, sticker_scale,
      filter_preset, filter_overlay_color, beauty_level, playback_speed, mute_original_audio,
      audio_title, audio_url, sound_author_photo, trim_start_ms, trim_end_ms)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
  ).bind(
    reel.id, reel.author_id, reel.author_name, reel.author_photo, reel.video_url, reel.thumb_url,
    reel.caption, reel.duration_sec, reel.created_at, reel.is_from_post, reel.mirror_horizontal,
    reel.overlay_text, reel.overlay_color, reel.overlay_bg_color, reel.overlay_x, reel.overlay_y,
    reel.sticker_text, reel.sticker_x, reel.sticker_y, reel.sticker_scale, reel.filter_preset,
    reel.filter_overlay_color, reel.beauty_level, reel.playback_speed, reel.mute_original_audio,
    reel.audio_title, reel.audio_url, reel.sound_author_photo, reel.trim_start_ms, reel.trim_end_ms
  ).run();
  
  return created(reel);
}

async function getReelsFeed(env, uid, cursor, limit) {
  let query = 'SELECT * FROM reels WHERE 1=1 ORDER BY created_at DESC LIMIT ?';
  let params = [limit + 1];
  
  if (cursor) {
    query = 'SELECT * FROM reels WHERE 1=1 AND created_at < ? ORDER BY created_at DESC LIMIT ?';
    params = [parseInt(cursor), limit + 1];
  }
  
  const reels = await env.DB.prepare(query).bind(...params).all();
  
  let hasMore = reels.results.length > limit;
  const results = hasMore ? reels.results.slice(0, limit) : reels.results;
  const nextCursor = hasMore ? results[results.length - 1].created_at : null;
  
  const parsed = results.map(r => ({
    ...r,
    liked_by: JSON.parse(r.liked_by || '[]'),
    saved_by: JSON.parse(r.saved_by || '[]'),
    reactions: JSON.parse(r.reactions || '{}'),
    inline_comments: JSON.parse(r.inline_comments || '[]'),
  }));
  
  return ok({ reels: parsed, cursor: nextCursor });
}

async function getReel(env, reelId, uid) {
  const reel = await env.DB.prepare('SELECT * FROM reels WHERE id = ?').bind(reelId).first();
  if (!reel) return notFound('Reel not found');
  
  reel.liked_by = JSON.parse(reel.liked_by || '[]');
  reel.saved_by = JSON.parse(reel.saved_by || '[]');
  reel.reactions = JSON.parse(reel.reactions || '{}');
  reel.inline_comments = JSON.parse(reel.inline_comments || '[]');
  
  return ok(reel);
}

async function updateReel(env, reelId, uid, data) {
  const reel = await env.DB.prepare('SELECT author_id FROM reels WHERE id = ?').bind(reelId).first();
  if (!reel) return notFound('Reel not found');
  if (reel.author_id !== uid) return unauthorized('Not your reel');
  
  const allowed = ['caption', 'audio_title', 'audio_url', 'sound_author_photo', 'filter_preset'];
  const updates = [];
  const values = [];
  
  for (const key of allowed) {
    if (data[key] !== undefined) {
      updates.push(`${key} = ?`);
      values.push(data[key]);
    }
  }
  
  if (updates.length === 0) return badRequest('No valid fields to update');
  
  values.push(reelId);
  await env.DB.prepare(`UPDATE reels SET ${updates.join(', ')} WHERE id = ?`).bind(...values).run();
  
  return ok({ success: true });
}

async function deleteReel(env, reelId, uid) {
  const reel = await env.DB.prepare('SELECT author_id FROM reels WHERE id = ?').bind(reelId).first();
  if (!reel) return notFound('Reel not found');
  if (reel.author_id !== uid) return unauthorized('Not your reel');
  
  await env.DB.prepare('DELETE FROM reels WHERE id = ?').bind(reelId).run();
  return ok({ success: true });
}

async function getReelReactions(env, reelId) {
  const reel = await env.DB.prepare('SELECT reactions FROM reels WHERE id = ?').bind(reelId).first();
  if (!reel) return notFound('Reel not found');
  
  return ok(JSON.parse(reel.reactions || '{}'));
}

async function addReelReaction(env, reelId, uid, data) {
  const { type } = data;
  const validTypes = ['like', 'love', 'haha', 'wow', 'sad', 'angry', 'fire'];
  if (!validTypes.includes(type)) return badRequest('Invalid reaction type');
  
  const reel = await env.DB.prepare('SELECT reactions FROM reels WHERE id = ?').bind(reelId).first();
  if (!reel) return notFound('Reel not found');
  
  const reactions = JSON.parse(reel.reactions || '{}');
  reactions[uid] = type;
  
  await env.DB.prepare('UPDATE reels SET reactions = ?, likes = ? WHERE id = ?')
    .bind(JSON.stringify(reactions), Object.keys(reactions).length, reelId).run();
  
  return ok({ success: true, reaction: { uid, type } });
}

async function removeReelReaction(env, reelId, uid) {
  const reel = await env.DB.prepare('SELECT reactions FROM reels WHERE id = ?').bind(reelId).first();
  if (!reel) return notFound('Reel not found');
  
  const reactions = JSON.parse(reel.reactions || '{}');
  delete reactions[uid];
  
  await env.DB.prepare('UPDATE reels SET reactions = ?, likes = ? WHERE id = ?')
    .bind(JSON.stringify(reactions), Object.keys(reactions).length, reelId).run();
  
  return ok({ success: true });
}

async function getReelComments(env, reelId) {
  // For now, comments are embedded in reel.inline_comments
  const reel = await env.DB.prepare('SELECT inline_comments FROM reels WHERE id = ?').bind(reelId).first();
  if (!reel) return notFound('Reel not found');
  
  return ok(JSON.parse(reel.inline_comments || '[]'));
}

async function addReelComment(env, reelId, uid, data) {
  const reel = await env.DB.prepare('SELECT inline_comments, comments FROM reels WHERE id = ?').bind(reelId).first();
  if (!reel) return notFound('Reel not found');
  
  const comments = JSON.parse(reel.inline_comments || '[]');
  const comment = {
    id: crypto.randomUUID(),
    authorId: uid,
    authorName: data.authorName || 'User',
    authorPhoto: data.authorPhoto || '',
    content: data.content || '',
    createdAt: Date.now(),
    likes: 0,
    likedBy: [],
    reactions: {},
  };
  
  comments.push(comment);
  await env.DB.prepare('UPDATE reels SET inline_comments = ?, comments = ? WHERE id = ?')
    .bind(JSON.stringify(comments), comments.length, reelId).run();
  
  return created(comment);
}

async function saveReel(env, reelId, uid) {
  const reel = await env.DB.prepare('SELECT saved_by FROM reels WHERE id = ?').bind(reelId).first();
  if (!reel) return notFound('Reel not found');
  
  const saved = JSON.parse(reel.saved_by || '[]');
  if (saved.includes(uid)) return ok({ success: true, alreadySaved: true });
  
  saved.push(uid);
  await env.DB.prepare('UPDATE reels SET saved_by = ?, favorites = ? WHERE id = ?')
    .bind(JSON.stringify(saved), saved.length, reelId).run();
  
  return ok({ success: true });
}

async function unsaveReel(env, reelId, uid) {
  const reel = await env.DB.prepare('SELECT saved_by FROM reels WHERE id = ?').bind(reelId).first();
  if (!reel) return notFound('Reel not found');
  
  const saved = JSON.parse(reel.saved_by || '[]').filter(s => s !== uid);
  await env.DB.prepare('UPDATE reels SET saved_by = ?, favorites = ? WHERE id = ?')
    .bind(JSON.stringify(saved), saved.length, reelId).run();
  
  return ok({ success: true });
}

async function recordReelView(env, reelId, uid) {
  await env.DB.prepare('UPDATE reels SET views = views + 1 WHERE id = ?').bind(reelId).run();
  return ok({ success: true });
}

// Call functions (WebRTC Signaling)
async function sendCallOffer(env, uid, data) {
  const { chatId, offer } = data;
  if (!chatId || !offer) return badRequest('Missing chatId or offer');
  
  const chat = await env.DB.prepare('SELECT participants FROM chats WHERE id = ?').bind(chatId).first();
  if (!chat) return notFound('Chat not found');
  
  const participants = JSON.parse(chat.participants || '[]');
  if (!participants.includes(uid)) return unauthorized('Not a participant');
  
  const calleeUid = participants.find(p => p !== uid);
  if (!calleeUid) return badRequest('No callee found');
  
  const now = Date.now();
  await env.DB.prepare(
    `INSERT INTO active_calls (chat_id, caller_uid, callee_uid, offer, status, created_at, updated_at)
     VALUES (?, ?, ?, ?, 'ringing', ?, ?)
     ON CONFLICT(chat_id) DO UPDATE SET offer = ?, status = 'ringing', updated_at = ?`
  ).bind(chatId, uid, calleeUid, JSON.stringify(offer), now, now, JSON.stringify(offer), now).run();
  
  // Send FCM notification to callee (handled by push service)
  
  return ok({ success: true });
}

async function sendCallAnswer(env, uid, data) {
  const { chatId, answer } = data;
  if (!chatId || !answer) return badRequest('Missing chatId or answer');
  
  const call = await env.DB.prepare('SELECT caller_uid, callee_uid FROM active_calls WHERE chat_id = ?').bind(chatId).first();
  if (!call) return notFound('Call not found');
  if (call.callee_uid !== uid) return unauthorized('Not the callee');
  
  const now = Date.now();
  await env.DB.prepare(
    'UPDATE active_calls SET answer = ?, status = \'connected\', updated_at = ? WHERE chat_id = ?'
  ).bind(JSON.stringify(answer), now, chatId).run();
  
  return ok({ success: true });
}

async function sendIceCandidate(env, uid, data) {
  const { chatId, candidate } = data;
  if (!chatId || !candidate) return badRequest('Missing chatId or candidate');
  
  const call = await env.DB.prepare('SELECT ice_candidates FROM active_calls WHERE chat_id = ?').bind(chatId).first();
  if (!call) return notFound('Call not found');
  
  const candidates = JSON.parse(call.ice_candidates || '[]');
  candidates.push({ ...candidate, from: uid, timestamp: Date.now() });
  
  await env.DB.prepare(
    'UPDATE active_calls SET ice_candidates = ?, updated_at = ? WHERE chat_id = ?'
  ).bind(JSON.stringify(candidates), Date.now(), chatId).run();
  
  return ok({ success: true });
}

async function endCall(env, uid, data) {
  const { chatId } = data;
  if (!chatId) return badRequest('Missing chatId');
  
  const call = await env.DB.prepare('SELECT caller_uid, callee_uid FROM active_calls WHERE chat_id = ?').bind(chatId).first();
  if (!call) return notFound('Call not found');
  if (call.caller_uid !== uid && call.callee_uid !== uid) return unauthorized('Not a participant');
  
  await env.DB.prepare(
    'UPDATE active_calls SET status = \'ended\', updated_at = ? WHERE chat_id = ?'
  ).bind(Date.now(), chatId).run();
  
  return ok({ success: true });
}

async function getCallState(env, chatId, uid) {
  const call = await env.DB.prepare('SELECT * FROM active_calls WHERE chat_id = ?').bind(chatId).first();
  if (!call) return ok({ status: 'none' });
  
  if (call.caller_uid !== uid && call.callee_uid !== uid) return unauthorized('Not a participant');
  
  call.offer = call.offer ? JSON.parse(call.offer) : null;
  call.answer = call.answer ? JSON.parse(call.answer) : null;
  call.ice_candidates = JSON.parse(call.ice_candidates || '[]');
  
  return ok(call);
}

// Settings functions
async function getSettings(env, uid) {
  const settings = await env.DB.prepare('SELECT settings_json FROM user_settings WHERE uid = ?').bind(uid).first();
  return ok(settings ? JSON.parse(settings.settings_json) : {});
}

async function updateSettings(env, uid, data) {
  const now = Date.now();
  await env.DB.prepare(
    `INSERT INTO user_settings (uid, settings_json, updated_at)
     VALUES (?, ?, ?)
     ON CONFLICT(uid) DO UPDATE SET settings_json = ?, updated_at = ?`
  ).bind(uid, JSON.stringify(data), now, JSON.stringify(data), now).run();
  
  return ok({ success: true });
}

// Upload functions (R2)
async function handleUpload(env, uid, request, path) {
  // This would integrate with the existing R2 upload logic
  // For now, return the upload URL
  return ok({ uploadUrl: `https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev/v1/photos` });
}