/**
 * Migration Admin API - Cloudflare Worker
 * Handles bulk data import from Firestore to D1
 * Only accessible with admin token
 */

import { timingSafeEqual } from './utils.js';


function cors(status = 200, body = null) {
  return new Response(body ? JSON.stringify(body) : null, {
    status,
    headers: {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, Authorization',
      'Content-Type': 'application/json',
    },
  });
}

/**
 * Mirrors every migrated document into the app document store as well.
 *
 * The app reads documents back through /v1/docs, which is served from
 * friendbook_cf_documents. The row-by-row writers below target the normalised tables
 * (users, posts, chats, ...). Those two shapes were never connected, so a migration that
 * reported success left D1 with millions of rows in tables nothing read, and the app
 * still looked empty. Both are written now.
 *
 * Best effort by design: a mirror failure is reported but never rolls back the primary
 * write, and never turns an otherwise successful batch into a failed one.
 *
 * This refuses to overwrite a newer row. By the time anyone runs a migration, the app has
 * already been writing to D1 for a while — so the copy source is *older* than what is in the
 * table. An unconditional upsert would take a document a user edited ten minutes ago and
 * replace it with the version Firestore had last week. Rows are therefore only replaced when
 * the copy is genuinely newer, counted by the document's own updatedAt where it has one.
 */
async function mirrorDocuments(env, collection, documents) {
  let mirrored = 0;
  const failures = [];
  try {
    // The WHERE clause is what makes this a merge rather than an overwrite: an incoming
    // document only wins if it is at least as new as the row already there. Rows with no
    // comparable timestamp are left alone, because "unknown" is not evidence of being newer.
    const stmt = env.DB.prepare(
      `INSERT INTO friendbook_cf_documents(path, collection, doc_id, owner_uid, document_json, updated_at)
       VALUES (?, ?, ?, ?, ?, ?)
       ON CONFLICT(path) DO UPDATE SET
         document_json = excluded.document_json,
         updated_at = excluded.updated_at
       WHERE CAST(json_extract(friendbook_cf_documents.document_json, '$.updatedAt') AS INTEGER)
             <= CAST(json_extract(excluded.document_json, '$.updatedAt') AS INTEGER)`
    );
    const statements = [];
    for (const doc of documents || []) {
      const id = doc && (doc.__id || doc.id);
      if (typeof id !== 'string' || !id.length || id.length > 128) continue;
      const plain = { ...doc, id };
      delete plain.__id;
      if (!plain.createdAt) plain.createdAt = Date.now();
      const owner = String(plain.authorId || plain.uid || plain.senderId || plain.toUserId || '');
      statements.push(stmt.bind(`${collection}/${id}`, collection, id, owner,
        JSON.stringify(plain), Date.now()));
    }
    // Count what the database actually changed, not what was submitted. With the merge guard
    // above, a slice of twenty stale documents legitimately changes nothing, and reporting
    // "mirrored 20" for that would read as success.
    // wrangler reports changes as result.meta.changes; node:sqlite (used by the tests) reports
    // them as result.changes. Both are read so the count is honest in either environment.
    const changesOf = (result) => Number(result?.meta?.changes ?? result?.changes ?? 0);
    for (let i = 0; i < statements.length; i += 50) {
      const slice = statements.slice(i, i + 50);
      if (typeof env.DB.batch === 'function') {
        const results = await env.DB.batch(slice);
        for (const result of results || []) mirrored += changesOf(result);
      } else {
        for (const statement of slice) mirrored += changesOf(await statement.run());
      }
    }
  } catch (error) {
    failures.push(String(error && error.message ? error.message : error).slice(0, 120));
  }
  return { mirrored, failures };
}

export async function migrationApi(request, env) {
  if (request.method === 'OPTIONS') return cors();

  // This endpoint bulk-writes arbitrary rows into every table, so it fails closed:
  //  - no MIGRATION_ADMIN_TOKEN configured means no migration API at all. There is no
  //    default token; an unconfigured deployment must not be writable.
  //  - comparison is timing-safe and length-safe, so the token cannot be recovered by
  //    timing. A plain !== leaks the shared prefix length.
  const expected = typeof env?.MIGRATION_ADMIN_TOKEN === 'string' ? env.MIGRATION_ADMIN_TOKEN : '';
  if (expected.length < 32) {
    return cors(503, { error: 'Migration API is not configured' });
  }
  const authHeader = request.headers.get('Authorization') || '';
  const match = /^Bearer (.+)$/.exec(authHeader);
  if (!match) return cors(401, { error: 'Unauthorized' });
  if (!(await timingSafeEqual(match[1], expected))) {
    return cors(403, { error: 'Invalid admin token' });
  }
  
  const url = new URL(request.url);
  const path = url.pathname;
  
  try {
    if (path === '/v1/admin/migrate/users' && request.method === 'POST') {
      return await migrateUsers(env, await request.json());
    }
    
    if (path === '/v1/admin/migrate/posts' && request.method === 'POST') {
      return await migratePosts(env, await request.json());
    }
    
    if (path === '/v1/admin/migrate/chats' && request.method === 'POST') {
      return await migrateChats(env, await request.json());
    }
    
    if (path === '/v1/admin/migrate/groups' && request.method === 'POST') {
      return await migrateGroups(env, await request.json());
    }
    
    if (path === '/v1/admin/migrate/friendRequests' && request.method === 'POST') {
      return await migrateFriendRequests(env, await request.json());
    }
    
    if (path === '/v1/admin/migrate/notifications' && request.method === 'POST') {
      return await migrateNotifications(env, await request.json());
    }
    
    if (path === '/v1/admin/migrate/stories' && request.method === 'POST') {
      return await migrateStories(env, await request.json());
    }
    
    if (path === '/v1/admin/migrate/reels' && request.method === 'POST') {
      return await migrateReels(env, await request.json());
    }
    
    if (path === '/v1/admin/migrate/comments' && request.method === 'POST') {
      return await migrateComments(env, await request.json());
    }
    
    if (path === '/v1/admin/migrate/messages' && request.method === 'POST') {
      return await migrateMessages(env, await request.json());
    }
    
    if (path === '/v1/admin/migrate/verify' && request.method === 'GET') {
      return await verifyCounts(env);
    }

    if (path === '/v1/admin/migrate/audit' && request.method === 'GET') {
      return await auditFreshness(env);
    }
    
    return cors(404, { error: 'Not found' });
    
  } catch (error) {
    console.error('Migration API error:', error);
    return cors(500, { error: error.message });
  }
}

// ============ MIGRATION FUNCTIONS ============

async function migrateUsers(env, body) {
  const { documents } = body;
  if (!documents || !Array.isArray(documents)) {
    return cors(400, { error: 'Missing documents array' });
  }
  
  let inserted = 0;
  let updated = 0;
  let errors = 0;
  
  for (const doc of documents) {
    try {
      const uid = doc.__id || doc.uid;
      if (!uid) { errors++; continue; }
      
      await env.DB.prepare(`
        INSERT INTO users (
          uid, display_name, email, photo_url, bio, cover_url, birthday, work, education,
          current_city, hometown, relationship_status, relationship, website, pronouns,
          phone, phone_number, is_admin, is_moderator, banned, is_verified,
          suspended_until, suspended_lifetime, deactivated, login_alerts, tag_review_enabled,
          professional_mode, hide_reaction_counts, show_active_status, last_seen, created_at,
          friends_count, stars, tick_level, subscriptions_enabled, subscription_price,
          profile_locked, profile_pic_guard, lookup_privacy, friends_list_privacy,
          email_privacy, contact_privacy, contact_info_privacy, followers_privacy,
          tag_privacy, two_factor_pin, favorite_uids, restricted_uids, close_friends_uids,
          highlight_story_ids, saved_reel_ids, hobbies, pinned_post_id, status_note,
          status_note_at, daily_streak, last_bonus_at, followers, following, blocked
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(uid) DO UPDATE SET
          display_name = excluded.display_name,
          email = excluded.email,
          photo_url = excluded.photo_url,
          bio = excluded.bio,
          cover_url = excluded.cover_url,
          birthday = excluded.birthday,
          work = excluded.work,
          education = excluded.education,
          current_city = excluded.current_city,
          hometown = excluded.hometown,
          relationship_status = excluded.relationship_status,
          relationship = excluded.relationship,
          website = excluded.website,
          pronouns = excluded.pronouns,
          phone = excluded.phone,
          phone_number = excluded.phone_number,
          is_admin = excluded.is_admin,
          is_moderator = excluded.is_moderator,
          banned = excluded.banned,
          is_verified = excluded.is_verified,
          suspended_until = excluded.suspended_until,
          suspended_lifetime = excluded.suspended_lifetime,
          deactivated = excluded.deactivated,
          login_alerts = excluded.login_alerts,
          tag_review_enabled = excluded.tag_review_enabled,
          professional_mode = excluded.professional_mode,
          hide_reaction_counts = excluded.hide_reaction_counts,
          show_active_status = excluded.show_active_status,
          last_seen = excluded.last_seen,
          created_at = excluded.created_at,
          friends_count = excluded.friends_count,
          stars = excluded.stars,
          tick_level = excluded.tick_level,
          subscriptions_enabled = excluded.subscriptions_enabled,
          subscription_price = excluded.subscription_price,
          profile_locked = excluded.profile_locked,
          profile_pic_guard = excluded.profile_pic_guard,
          lookup_privacy = excluded.lookup_privacy,
          friends_list_privacy = excluded.friends_list_privacy,
          email_privacy = excluded.email_privacy,
          contact_privacy = excluded.contact_privacy,
          contact_info_privacy = excluded.contact_info_privacy,
          followers_privacy = excluded.followers_privacy,
          tag_privacy = excluded.tag_privacy,
          two_factor_pin = excluded.two_factor_pin,
          favorite_uids = excluded.favorite_uids,
          restricted_uids = excluded.restricted_uids,
          close_friends_uids = excluded.close_friends_uids,
          highlight_story_ids = excluded.highlight_story_ids,
          saved_reel_ids = excluded.saved_reel_ids,
          hobbies = excluded.hobbies,
          pinned_post_id = excluded.pinned_post_id,
          status_note = excluded.status_note,
          status_note_at = excluded.status_note_at,
          daily_streak = excluded.daily_streak,
          last_bonus_at = excluded.last_bonus_at,
          followers = excluded.followers,
          following = excluded.following,
          blocked = excluded.blocked
      `).bind(
        uid,
        doc.displayName || 'User',
        doc.email || '',
        doc.photoUrl || '',
        doc.bio || '',
        doc.coverUrl || '',
        doc.birthday || '',
        doc.work || '',
        doc.education || '',
        doc.currentCity || '',
        doc.hometown || '',
        doc.relationshipStatus || '',
        doc.relationship || '',
        doc.website || '',
        doc.pronouns || '',
        doc.phone || '',
        doc.phoneNumber || '',
        doc.isAdmin ? 1 : 0,
        doc.isModerator ? 1 : 0,
        doc.banned ? 1 : 0,
        doc.isVerified ? 1 : 0,
        doc.suspendedUntil || 0,
        doc.suspendedLifetime ? 1 : 0,
        doc.deactivated ? 1 : 0,
        doc.loginAlerts ? 1 : 0,
        doc.tagReviewEnabled ? 1 : 0,
        doc.professionalMode ? 1 : 0,
        doc.hideReactionCounts ? 1 : 0,
        doc.showActiveStatus !== false ? 1 : 0,
        doc.lastSeen || 0,
        doc.createdAt || Date.now(),
        doc.friendsCount || 0,
        doc.stars || 0,
        doc.tickLevel || 'none',
        doc.subscriptionsEnabled ? 1 : 0,
        doc.subscriptionPrice || 0,
        doc.profileLocked ? 1 : 0,
        doc.profilePicGuard ? 1 : 0,
        doc.lookupPrivacy || 'Everyone',
        doc.friendsListPrivacy || 'Public',
        doc.emailPrivacy || 'Only me',
        doc.contactPrivacy || 'Only me',
        doc.contactInfoPrivacy || 'Only me',
        doc.followersPrivacy || 'Public',
        doc.tagPrivacy || 'Everyone',
        doc.twoFactorPin || '',
        JSON.stringify(doc.favoriteUids || []),
        JSON.stringify(doc.restrictedUids || []),
        JSON.stringify(doc.closeFriendsUids || []),
        JSON.stringify(doc.highlightStoryIds || []),
        JSON.stringify(doc.savedReelIds || []),
        JSON.stringify(doc.hobbies || []),
        doc.pinnedPostId || '',
        doc.statusNote || '',
        doc.statusNoteAt || 0,
        doc.dailyStreak || 0,
        doc.lastBonusAt || 0,
        JSON.stringify(doc.followers || []),
        JSON.stringify(doc.following || []),
        JSON.stringify(doc.blocked || [])
      ).run();
      
      inserted++;
    } catch (e) {
      console.error('User insert error:', e);
      errors++;
    }
  }
  
  const mirror = await mirrorDocuments(env, 'users', documents);
  return cors(200, { inserted, updated, errors, total: documents.length , mirrored: mirror.mirrored, mirrorErrors: mirror.failures.length });
}

async function migratePosts(env, body) {
  const { documents } = body;
  if (!documents || !Array.isArray(documents)) {
    return cors(400, { error: 'Missing documents array' });
  }
  
  let inserted = 0;
  let errors = 0;
  
  for (const doc of documents) {
    try {
      const id = doc.__id || doc.id;
      if (!id) { errors++; continue; }
      
      await env.DB.prepare(`
        INSERT INTO posts (
          id, author_id, author_name, author_photo, content, media_url, media_type,
          mentions, feeling, privacy, shared_from_id, shared_from_author, shared_from_content,
          shared_from_media_url, shared_from_media_type, location, group_id, post_type,
          group_approval_status, life_event, life_event_icon, life_event_title, qa_prompt,
          market_title, market_price, market_category, market_condition, market_sold,
          page_id, page_name, page_photo, archived, trashed_at, bg_color, likes, comments,
          shares, views, created_at, saved_by, subscribed_uids, subscribed_by, media_urls,
          tagged_uids, tagged_names, poll_options, poll_votes, pinned_comment_id,
          inline_comments, reactions_map, author_verified, author_tick_level
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
          author_id = excluded.author_id,
          author_name = excluded.author_name,
          author_photo = excluded.author_photo,
          content = excluded.content,
          media_url = excluded.media_url,
          media_type = excluded.media_type,
          mentions = excluded.mentions,
          feeling = excluded.feeling,
          privacy = excluded.privacy,
          shared_from_id = excluded.shared_from_id,
          shared_from_author = excluded.shared_from_author,
          shared_from_content = excluded.shared_from_content,
          shared_from_media_url = excluded.shared_from_media_url,
          shared_from_media_type = excluded.shared_from_media_type,
          location = excluded.location,
          group_id = excluded.group_id,
          post_type = excluded.post_type,
          group_approval_status = excluded.group_approval_status,
          life_event = excluded.life_event,
          life_event_icon = excluded.life_event_icon,
          life_event_title = excluded.life_event_title,
          qa_prompt = excluded.qa_prompt,
          market_title = excluded.market_title,
          market_price = excluded.market_price,
          market_category = excluded.market_category,
          market_condition = excluded.market_condition,
          market_sold = excluded.market_sold,
          page_id = excluded.page_id,
          page_name = excluded.page_name,
          page_photo = excluded.page_photo,
          archived = excluded.archived,
          trashed_at = excluded.trashed_at,
          bg_color = excluded.bg_color,
          likes = excluded.likes,
          comments = excluded.comments,
          shares = excluded.shares,
          views = excluded.views,
          created_at = excluded.created_at,
          saved_by = excluded.saved_by,
          subscribed_uids = excluded.subscribed_uids,
          subscribed_by = excluded.subscribed_by,
          media_urls = excluded.media_urls,
          tagged_uids = excluded.tagged_uids,
          tagged_names = excluded.tagged_names,
          poll_options = excluded.poll_options,
          poll_votes = excluded.poll_votes,
          pinned_comment_id = excluded.pinned_comment_id,
          inline_comments = excluded.inline_comments,
          reactions_map = excluded.reactions_map,
          author_verified = excluded.author_verified,
          author_tick_level = excluded.author_tick_level
      `).bind(
        id,
        doc.authorId || '',
        doc.authorName || 'User',
        doc.authorPhoto || '',
        doc.content || '',
        doc.mediaUrl || '',
        doc.mediaType || 'none',
        JSON.stringify(doc.mentions || []),
        doc.feeling || '',
        doc.privacy || 'Public',
        doc.sharedFromId || '',
        doc.sharedFromAuthor || '',
        doc.sharedFromContent || '',
        doc.sharedFromMediaUrl || '',
        doc.sharedFromMediaType || '',
        doc.location || '',
        doc.groupId || '',
        doc.postType || 'regular',
        doc.groupApprovalStatus || 'approved',
        doc.lifeEvent || '',
        doc.lifeEventIcon || '',
        doc.lifeEventTitle || '',
        doc.qaPrompt || '',
        doc.marketTitle || '',
        doc.marketPrice || '',
        doc.marketCategory || '',
        doc.marketCondition || '',
        doc.marketSold ? 1 : 0,
        doc.pageId || '',
        doc.pageName || '',
        doc.pagePhoto || '',
        doc.archived ? 1 : 0,
        doc.trashedAt || 0,
        doc.bgColor || 0,
        doc.likes || 0,
        doc.comments || 0,
        doc.shares || 0,
        doc.views || 0,
        doc.createdAt || Date.now(),
        JSON.stringify(doc.savedBy || []),
        JSON.stringify(doc.subscribedUids || []),
        JSON.stringify(doc.subscribedBy || []),
        JSON.stringify(doc.mediaUrls || []),
        JSON.stringify(doc.taggedUids || []),
        JSON.stringify(doc.taggedNames || []),
        JSON.stringify(doc.pollOptions || []),
        JSON.stringify(doc.pollVotes || {}),
        doc.pinnedCommentId || '',
        JSON.stringify(doc.inlineComments || []),
        JSON.stringify(doc.reactionsMap || {}),
        doc.authorVerified ? 1 : 0,
        doc.authorTickLevel || 'none'
      ).run();
      
      inserted++;
    } catch (e) {
      console.error('Post insert error:', e);
      errors++;
    }
  }
  
  const mirror = await mirrorDocuments(env, 'posts', documents);
  return cors(200, { inserted, errors, total: documents.length , mirrored: mirror.mirrored, mirrorErrors: mirror.failures.length });
}

async function migrateChats(env, body) {
  const { documents } = body;
  if (!documents || !Array.isArray(documents)) {
    return cors(400, { error: 'Missing documents array' });
  }
  
  let inserted = 0;
  let errors = 0;
  
  for (const doc of documents) {
    try {
      const id = doc.__id || doc.id;
      if (!id) { errors++; continue; }
      
      await env.DB.prepare(`
        INSERT INTO chats (
          id, last_message, last_message_time, is_group, group_name, group_photo,
          created_by, only_admins_can_send, admin_ids, participants, typing,
          participant_names, participant_photos
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
          last_message = excluded.last_message,
          last_message_time = excluded.last_message_time,
          is_group = excluded.is_group,
          group_name = excluded.group_name,
          group_photo = excluded.group_photo,
          created_by = excluded.created_by,
          only_admins_can_send = excluded.only_admins_can_send,
          admin_ids = excluded.admin_ids,
          participants = excluded.participants,
          typing = excluded.typing,
          participant_names = excluded.participant_names,
          participant_photos = excluded.participant_photos
      `).bind(
        id,
        doc.lastMessage || '',
        doc.lastMessageTime || 0,
        doc.isGroup ? 1 : 0,
        doc.groupName || '',
        doc.groupPhoto || '',
        doc.createdBy || '',
        doc.onlyAdminsCanSend ? 1 : 0,
        JSON.stringify(doc.adminIds || []),
        JSON.stringify(doc.participants || []),
        JSON.stringify(doc.typing || {}),
        JSON.stringify(doc.participantNames || {}),
        JSON.stringify(doc.participantPhotos || {})
      ).run();
      
      inserted++;
    } catch (e) {
      console.error('Chat insert error:', e);
      errors++;
    }
  }
  
  const mirror = await mirrorDocuments(env, 'chats', documents);
  return cors(200, { inserted, errors, total: documents.length , mirrored: mirror.mirrored, mirrorErrors: mirror.failures.length });
}

async function migrateGroups(env, body) {
  const { documents } = body;
  if (!documents || !Array.isArray(documents)) {
    return cors(400, { error: 'Missing documents array' });
  }
  
  let inserted = 0;
  let errors = 0;
  
  for (const doc of documents) {
    try {
      const id = doc.__id || doc.id;
      if (!id) { errors++; continue; }
      
      await env.DB.prepare(`
        INSERT INTO groups (
          id, name, about, category, photo_url, cover_url, admin_id, privacy, rules,
          pinned_post_id, post_permission, require_post_approval, require_join_approval,
          created_at, members, admin_ids, moderator_ids, pending_member_ids,
          banned_member_ids, invited_uids
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
          name = excluded.name,
          about = excluded.about,
          category = excluded.category,
          photo_url = excluded.photo_url,
          cover_url = excluded.cover_url,
          admin_id = excluded.admin_id,
          privacy = excluded.privacy,
          rules = excluded.rules,
          pinned_post_id = excluded.pinned_post_id,
          post_permission = excluded.post_permission,
          require_post_approval = excluded.require_post_approval,
          require_join_approval = excluded.require_join_approval,
          created_at = excluded.created_at,
          members = excluded.members,
          admin_ids = excluded.admin_ids,
          moderator_ids = excluded.moderator_ids,
          pending_member_ids = excluded.pending_member_ids,
          banned_member_ids = excluded.banned_member_ids,
          invited_uids = excluded.invited_uids
      `).bind(
        id,
        doc.name || 'Group',
        doc.about || '',
        doc.category || 'Community',
        doc.photoUrl || '',
        doc.coverUrl || '',
        doc.adminId || '',
        doc.privacy || 'Public',
        doc.rules || '',
        doc.pinnedPostId || '',
        doc.postPermission || 'Everyone',
        doc.requirePostApproval ? 1 : 0,
        doc.requireJoinApproval ? 1 : 0,
        doc.createdAt || Date.now(),
        JSON.stringify(doc.members || []),
        JSON.stringify(doc.adminIds || []),
        JSON.stringify(doc.moderatorIds || []),
        JSON.stringify(doc.pendingMemberIds || []),
        JSON.stringify(doc.bannedMemberIds || []),
        JSON.stringify(doc.invitedUids || [])
      ).run();
      
      inserted++;
    } catch (e) {
      console.error('Group insert error:', e);
      errors++;
    }
  }
  
  const mirror = await mirrorDocuments(env, 'groups', documents);
  return cors(200, { inserted, errors, total: documents.length , mirrored: mirror.mirrored, mirrorErrors: mirror.failures.length });
}

async function migrateFriendRequests(env, body) {
  const { documents } = body;
  if (!documents || !Array.isArray(documents)) {
    return cors(400, { error: 'Missing documents array' });
  }
  
  let inserted = 0;
  let errors = 0;
  
  for (const doc of documents) {
    try {
      const id = doc.__id || doc.id;
      if (!id) { errors++; continue; }
      
      await env.DB.prepare(`
        INSERT INTO friend_requests (id, from_uid, from_name, from_photo, to_uid, status, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
          from_uid = excluded.from_uid,
          from_name = excluded.from_name,
          from_photo = excluded.from_photo,
          to_uid = excluded.to_uid,
          status = excluded.status,
          created_at = excluded.created_at
      `).bind(
        id,
        doc.fromUid || '',
        doc.fromName || 'User',
        doc.fromPhoto || '',
        doc.toUid || '',
        doc.status || 'pending',
        doc.createdAt || Date.now()
      ).run();
      
      inserted++;
    } catch (e) {
      console.error('FriendRequest insert error:', e);
      errors++;
    }
  }
  
  const mirror = await mirrorDocuments(env, 'friend_requests', documents);
  return cors(200, { inserted, errors, total: documents.length , mirrored: mirror.mirrored, mirrorErrors: mirror.failures.length });
}

async function migrateNotifications(env, body) {
  const { documents } = body;
  if (!documents || !Array.isArray(documents)) {
    return cors(400, { error: 'Missing documents array' });
  }
  
  let inserted = 0;
  let errors = 0;
  
  for (const doc of documents) {
    try {
      const id = doc.__id || doc.id;
      if (!id) { errors++; continue; }
      
      await env.DB.prepare(`
        INSERT INTO notifications (id, to_user_id, from_user_id, from_user_name, from_user_photo, type, post_id, group_id, text, is_read, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
          to_user_id = excluded.to_user_id,
          from_user_id = excluded.from_user_id,
          from_user_name = excluded.from_user_name,
          from_user_photo = excluded.from_user_photo,
          type = excluded.type,
          post_id = excluded.post_id,
          group_id = excluded.group_id,
          text = excluded.text,
          is_read = excluded.is_read,
          created_at = excluded.created_at
      `).bind(
        id,
        doc.toUserId || '',
        doc.fromUserId || '',
        doc.fromUserName || 'Someone',
        doc.fromUserPhoto || '',
        doc.type || '',
        doc.postId || '',
        doc.groupId || '',
        doc.text || '',
        doc.isRead ? 1 : 0,
        doc.createdAt || Date.now()
      ).run();
      
      inserted++;
    } catch (e) {
      console.error('Notification insert error:', e);
      errors++;
    }
  }
  
  const mirror = await mirrorDocuments(env, 'notifications', documents);
  return cors(200, { inserted, errors, total: documents.length , mirrored: mirror.mirrored, mirrorErrors: mirror.failures.length });
}

async function migrateStories(env, body) {
  const { documents } = body;
  if (!documents || !Array.isArray(documents)) {
    return cors(400, { error: 'Missing documents array' });
  }
  
  let inserted = 0;
  let errors = 0;
  
  for (const doc of documents) {
    try {
      const id = doc.__id || doc.id;
      if (!id) { errors++; continue; }
      
      await env.DB.prepare(`
        INSERT INTO stories (id, author_id, author_name, author_photo, media_url, media_type, caption, music_mood, privacy, bg_color, font_style, created_at, expires_at, viewed_by, reactions)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
          author_id = excluded.author_id,
          author_name = excluded.author_name,
          author_photo = excluded.author_photo,
          media_url = excluded.media_url,
          media_type = excluded.media_type,
          caption = excluded.caption,
          music_mood = excluded.music_mood,
          privacy = excluded.privacy,
          bg_color = excluded.bg_color,
          font_style = excluded.font_style,
          created_at = excluded.created_at,
          expires_at = excluded.expires_at,
          viewed_by = excluded.viewed_by,
          reactions = excluded.reactions
      `).bind(
        id,
        doc.authorId || '',
        doc.authorName || 'User',
        doc.authorPhoto || '',
        doc.mediaUrl || '',
        doc.mediaType || 'image',
        doc.caption || '',
        doc.musicMood || '',
        doc.privacy || 'Public',
        doc.bgColor || 0,
        doc.fontStyle || 0,
        doc.createdAt || Date.now(),
        doc.expiresAt || (Date.now() + 86400000),
        JSON.stringify(doc.viewedBy || []),
        JSON.stringify(doc.reactions || {})
      ).run();
      
      inserted++;
    } catch (e) {
      console.error('Story insert error:', e);
      errors++;
    }
  }
  
  const mirror = await mirrorDocuments(env, 'stories', documents);
  return cors(200, { inserted, errors, total: documents.length , mirrored: mirror.mirrored, mirrorErrors: mirror.failures.length });
}

async function migrateReels(env, body) {
  const { documents } = body;
  if (!documents || !Array.isArray(documents)) {
    return cors(400, { error: 'Missing documents array' });
  }
  
  let inserted = 0;
  let errors = 0;
  
  for (const doc of documents) {
    try {
      const id = doc.__id || doc.id;
      if (!id) { errors++; continue; }
      
      await env.DB.prepare(`
        INSERT INTO reels (
          id, author_id, author_name, author_photo, video_url, thumb_url, caption,
          likes, comments, shares, favorites, views, duration_sec, created_at,
          is_from_post, mirror_horizontal, overlay_text, overlay_color, overlay_bg_color,
          overlay_x, overlay_y, sticker_text, sticker_x, sticker_y, sticker_scale,
          filter_preset, filter_overlay_color, beauty_level, playback_speed,
          mute_original_audio, audio_title, audio_url, sound_author_photo,
          trim_start_ms, trim_end_ms, liked_by, saved_by, reactions, inline_comments
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
          author_id = excluded.author_id,
          author_name = excluded.author_name,
          author_photo = excluded.author_photo,
          video_url = excluded.video_url,
          thumb_url = excluded.thumb_url,
          caption = excluded.caption,
          likes = excluded.likes,
          comments = excluded.comments,
          shares = excluded.shares,
          favorites = excluded.favorites,
          views = excluded.views,
          duration_sec = excluded.duration_sec,
          created_at = excluded.created_at,
          is_from_post = excluded.is_from_post,
          mirror_horizontal = excluded.mirror_horizontal,
          overlay_text = excluded.overlay_text,
          overlay_color = excluded.overlay_color,
          overlay_bg_color = excluded.overlay_bg_color,
          overlay_x = excluded.overlay_x,
          overlay_y = excluded.overlay_y,
          sticker_text = excluded.sticker_text,
          sticker_x = excluded.sticker_x,
          sticker_y = excluded.sticker_y,
          sticker_scale = excluded.sticker_scale,
          filter_preset = excluded.filter_preset,
          filter_overlay_color = excluded.filter_overlay_color,
          beauty_level = excluded.beauty_level,
          playback_speed = excluded.playback_speed,
          mute_original_audio = excluded.mute_original_audio,
          audio_title = excluded.audio_title,
          audio_url = excluded.audio_url,
          sound_author_photo = excluded.sound_author_photo,
          trim_start_ms = excluded.trim_start_ms,
          trim_end_ms = excluded.trim_end_ms,
          liked_by = excluded.liked_by,
          saved_by = excluded.saved_by,
          reactions = excluded.reactions,
          inline_comments = excluded.inline_comments
      `).bind(
        id,
        doc.authorId || '',
        doc.authorName || 'User',
        doc.authorPhoto || '',
        doc.videoUrl || '',
        doc.thumbUrl || '',
        doc.caption || '',
        doc.likes || 0,
        doc.comments || 0,
        doc.shares || 0,
        doc.favorites || 0,
        doc.views || 0,
        doc.durationSec || 0,
        doc.createdAt || Date.now(),
        doc.isFromPost ? 1 : 0,
        doc.mirrorHorizontal ? 1 : 0,
        doc.overlayText || '',
        doc.overlayColor || 0xFFFFFFFF,
        doc.overlayBgColor || 0xCC000000,
        doc.overlayX || 0.5,
        doc.overlayY || 0.24,
        doc.stickerText || '',
        doc.stickerX || 0.78,
        doc.stickerY || 0.32,
        doc.stickerScale || 1.0,
        doc.filterPreset || 'none',
        doc.filterOverlayColor || 0,
        doc.beautyLevel || 0,
        doc.playbackSpeed || 1.0,
        doc.muteOriginalAudio ? 1 : 0,
        doc.audioTitle || '',
        doc.audioUrl || '',
        doc.soundAuthorPhoto || '',
        doc.trimStartMs || 0,
        doc.trimEndMs || 0,
        JSON.stringify(doc.likedBy || []),
        JSON.stringify(doc.savedBy || []),
        JSON.stringify(doc.reactions || {}),
        JSON.stringify(doc.inlineComments || [])
      ).run();
      
      inserted++;
    } catch (e) {
      console.error('Reel insert error:', e);
      errors++;
    }
  }
  
  const mirror = await mirrorDocuments(env, 'reels', documents);
  return cors(200, { inserted, errors, total: documents.length , mirrored: mirror.mirrored, mirrorErrors: mirror.failures.length });
}

async function migrateComments(env, body) {
  const { documents } = body;
  if (!documents || !Array.isArray(documents)) {
    return cors(400, { error: 'Missing documents array' });
  }
  
  let inserted = 0;
  let errors = 0;
  
  for (const doc of documents) {
    try {
      const id = doc.__id || doc.id;
      if (!id) { errors++; continue; }
      
      await env.DB.prepare(`
        INSERT INTO comments (id, post_id, author_id, author_name, author_photo, content, image_url, parent_id, edited, pinned, likes, created_at, liked_by, reactions)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
          post_id = excluded.post_id,
          author_id = excluded.author_id,
          author_name = excluded.author_name,
          author_photo = excluded.author_photo,
          content = excluded.content,
          image_url = excluded.image_url,
          parent_id = excluded.parent_id,
          edited = excluded.edited,
          pinned = excluded.pinned,
          likes = excluded.likes,
          created_at = excluded.created_at,
          liked_by = excluded.liked_by,
          reactions = excluded.reactions
      `).bind(
        id,
        doc.postId || '',
        doc.authorId || '',
        doc.authorName || 'User',
        doc.authorPhoto || '',
        doc.content || '',
        doc.imageUrl || '',
        doc.parentId || '',
        doc.edited ? 1 : 0,
        doc.pinned ? 1 : 0,
        doc.likes || 0,
        doc.createdAt || Date.now(),
        JSON.stringify(doc.likedBy || []),
        JSON.stringify(doc.reactions || {})
      ).run();
      
      inserted++;
    } catch (e) {
      console.error('Comment insert error:', e);
      errors++;
    }
  }
  
  const mirror = await mirrorDocuments(env, 'comments', documents);
  return cors(200, { inserted, errors, total: documents.length , mirrored: mirror.mirrored, mirrorErrors: mirror.failures.length });
}

async function migrateMessages(env, body) {
  const { documents } = body;
  if (!documents || !Array.isArray(documents)) {
    return cors(400, { error: 'Missing documents array' });
  }
  
  let inserted = 0;
  let errors = 0;
  
  for (const doc of documents) {
    try {
      const id = doc.__id || doc.id;
      if (!id) { errors++; continue; }
      
      await env.DB.prepare(`
        INSERT INTO messages (
          id, chat_id, sender_id, sender_name, sender_photo, receiver_id, text, image_url, video_url,
          type, voice_url, broadcast_id, live_id, live_host_id, live_host_name, live_host_photo,
          live_title, reply_to_text, reply_to_sender, call_type, call_status, call_duration_sec,
          forwarded, voice_duration_sec, is_seen, is_delivered, is_deleted, created_at,
          reactions, reactor_names, reactor_photos
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
          chat_id = excluded.chat_id,
          sender_id = excluded.sender_id,
          sender_name = excluded.sender_name,
          sender_photo = excluded.sender_photo,
          receiver_id = excluded.receiver_id,
          text = excluded.text,
          image_url = excluded.image_url,
          video_url = excluded.video_url,
          type = excluded.type,
          voice_url = excluded.voice_url,
          broadcast_id = excluded.broadcast_id,
          live_id = excluded.live_id,
          live_host_id = excluded.live_host_id,
          live_host_name = excluded.live_host_name,
          live_host_photo = excluded.live_host_photo,
          live_title = excluded.live_title,
          reply_to_text = excluded.reply_to_text,
          reply_to_sender = excluded.reply_to_sender,
          call_type = excluded.call_type,
          call_status = excluded.call_status,
          call_duration_sec = excluded.call_duration_sec,
          forwarded = excluded.forwarded,
          voice_duration_sec = excluded.voice_duration_sec,
          is_seen = excluded.is_seen,
          is_delivered = excluded.is_delivered,
          is_deleted = excluded.is_deleted,
          created_at = excluded.created_at,
          reactions = excluded.reactions,
          reactor_names = excluded.reactor_names,
          reactor_photos = excluded.reactor_photos
      `).bind(
        id,
        doc.chatId || '',
        doc.senderId || '',
        doc.senderName || 'User',
        doc.senderPhoto || '',
        doc.receiverId || '',
        doc.text || '',
        doc.imageUrl || '',
        doc.videoUrl || '',
        doc.type || 'text',
        doc.voiceUrl || '',
        doc.broadcastId || '',
        doc.liveId || '',
        doc.liveHostId || '',
        doc.liveHostName || '',
        doc.liveHostPhoto || '',
        doc.liveTitle || '',
        doc.replyToText || '',
        doc.replyToSender || '',
        doc.callType || '',
        doc.callStatus || '',
        doc.callDurationSec || 0,
        doc.forwarded ? 1 : 0,
        doc.voiceDurationSec || 0,
        doc.isSeen ? 1 : 0,
        doc.isDelivered ? 1 : 0,
        doc.isDeleted ? 1 : 0,
        doc.createdAt || Date.now(),
        JSON.stringify(doc.reactions || {}),
        JSON.stringify(doc.reactorNames || {}),
        JSON.stringify(doc.reactorPhotos || {})
      ).run();
      
      inserted++;
    } catch (e) {
      console.error('Message insert error:', e);
      errors++;
    }
  }
  
  const mirror = await mirrorDocuments(env, 'messages', documents);
  return cors(200, { inserted, errors, total: documents.length , mirrored: mirror.mirrored, mirrorErrors: mirror.failures.length });
}

/**
 * How fresh is what D1 holds?
 *
 * <p>Counts alone cannot answer "is this a copy or the live data". A copied row and a row the
 * app wrote a minute ago are identical in a COUNT. What separates them is the timestamp, and
 * there is exactly one place that has one: friendbook_cf_documents, where the document API
 * stamps updated_at on every put. The per-feature tables (users, posts, chats, ...) are
 * projections written by the copy and carry no timestamp of their own, so they are counted
 * for completeness but cannot speak to recency.
 *
 * <p>So the answer is derived from the document table's spread: a newest row minutes old means
 * the app is writing here right now, and an oldest row older than the copy means those rows
 * came from it.
 */
async function auditFreshness(env) {
  const now = Date.now();
  const summary = {};
  const iso = (ms) => (ms ? new Date(ms).toISOString() : null);

  // Per-collection recency from the one table that actually records writes.
  try {
    const rows = await env.DB.prepare(
      `SELECT collection,
              COUNT(*) AS total,
              MIN(updated_at) AS oldest,
              MAX(updated_at) AS newest
       FROM friendbook_cf_documents
       GROUP BY collection
       ORDER BY total DESC`
    ).all();
    for (const row of rows.results || []) {
      const newest = row.newest || 0;
      summary[row.collection] = {
        total: row.total || 0,
        oldest: iso(row.oldest),
        newest: iso(newest),
        newestAgeMinutes: newest ? Math.round((now - newest) / 60000) : null,
        writtenToday:
          newest > 0 && new Date(newest).toISOString().slice(0, 10) === new Date(now).toISOString().slice(0, 10),
      };
    }
  } catch (error) {
    summary.documents = { error: String(error && error.message ? error.message : error).slice(0, 160) };
  }

  // Whole-table recency, which is the headline number.
  try {
    const row = await env.DB.prepare(
      `SELECT COUNT(*) AS total, MIN(updated_at) AS oldest, MAX(updated_at) AS newest
       FROM friendbook_cf_documents`
    ).first();
    const newest = row?.newest || 0;
    summary.totals = {
      total: row?.total || 0,
      oldest: iso(row?.oldest),
      newest: iso(newest),
      newestAgeMinutes: newest ? Math.round((now - newest) / 60000) : null,
    };
  } catch (error) {
    summary.totals = { error: String(error && error.message ? error.message : error).slice(0, 160) };
  }

  // The projections, counted only — they carry no timestamp, so they can show coverage but
  // never recency.
  const projections = {};
  for (const table of ['users', 'posts', 'chats', 'groups', 'notifications', 'comments', 'messages']) {
    try {
      const row = await env.DB.prepare(`SELECT COUNT(*) AS total FROM ${table}`).first();
      projections[table] = { total: row?.total || 0 };
    } catch (error) {
      projections[table] = { error: String(error && error.message ? error.message : error).slice(0, 120) };
    }
  }
  summary.projections = projections;
  summary.serverTime = new Date(now).toISOString();
  return cors(200, summary);
}

async function verifyCounts(env) {
  const tables = [
    'users', 'posts', 'chats', 'groups', 'friend_requests',
    'notifications', 'stories', 'reels', 'comments', 'messages', 'active_calls'
  ];
  
  const results = {};
  for (const table of tables) {
    try {
      const count = await env.DB.prepare(`SELECT COUNT(*) as c FROM ${table}`).first();
      results[table] = count?.c || 0;
    } catch (e) {
      results[table] = -1;
    }
  }
  
  return cors(200, { counts: results, timestamp: Date.now() });
}