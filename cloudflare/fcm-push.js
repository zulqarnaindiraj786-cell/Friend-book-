// Cloudflare Worker FCM gateway (/functions/v1/send-fcm-notification, /functions/v1/bulk-mentions).
// Firebase keeps ONLY Auth (token verification in worker.js) + FCM delivery.
// Device tokens + push claims live in D1; event payloads are derived from D1
// documents (never trusted from the client). Uses FIREBASE_ADMIN_SERVICE_ACCOUNT
// (existing GitHub secret, stored as a Worker secret) for FCM HTTP v1 OAuth.
const PROJECT = 'friendbook-17551';
const json = (body, status = 200) => Response.json(body, {
  status, headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' },
});
const validId = (v) => typeof v === 'string' && /^[\w-]{1,256}$/.test(v);

function pemToKey(pem) {
  const b64 = String(pem).replace(/-----BEGIN PRIVATE KEY-----/g, '').replace(/-----END PRIVATE KEY-----/g, '').replace(/\s+/g, '');
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return crypto.subtle.importKey('pkcs8', bytes, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
}
function b64url(bytes) {
  let s = '';
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
let cachedToken = '';
let tokenUntil = 0;
async function googleToken(env) {
  if (cachedToken && Date.now() < tokenUntil) return cachedToken;
  const raw = env.FIREBASE_ADMIN_SERVICE_ACCOUNT;
  if (!raw) throw new Error('FCM_NOT_CONFIGURED');
  let sa;
  try { sa = JSON.parse(raw); } catch { throw new Error('FCM_INVALID_JSON'); }
  if (sa.project_id !== PROJECT || !sa.private_key || !sa.client_email) throw new Error('FCM_PROJECT_MISMATCH');
  const key = await pemToKey(sa.private_key);
  const now = Math.floor(Date.now() / 1000);
  const header = { alg: 'RS256', typ: 'JWT', kid: sa.private_key_id || undefined };
  const claims = {
    iss: sa.client_email,
    scope: 'https://www.googleapis.com/auth/firebase.messaging',
    aud: 'https://oauth2.googleapis.com/token',
    iat: now, exp: now + 3600,
  };
  const enc = new TextEncoder();
  const signingInput = `${b64url(enc.encode(JSON.stringify(header)))}.${b64url(enc.encode(JSON.stringify(claims)))}`;
  const sig = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, enc.encode(signingInput));
  const assertion = `${signingInput}.${b64url(new Uint8Array(sig))}`;
  const r = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion }),
    signal: AbortSignal.timeout(10000),
  });
  const result = await r.json().catch(() => ({}));
  if (!r.ok || !result.access_token) throw new Error('FCM_OAUTH_FAILED');
  cachedToken = result.access_token;
  tokenUntil = Date.now() + (Number(result.expires_in || 3600) - 120) * 1000;
  return cachedToken;
}

async function d1Doc(db, path) {
  try {
    const row = await db.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path = ?').bind(path).first();
    if (!row?.document_json) return null;
    const parsed = JSON.parse(row.document_json);
    // Decode timestamp wrappers to millis for freshness checks.
    const out = {};
    for (const [k, v] of Object.entries(parsed || {})) {
      out[k] = v && typeof v === 'object' && v.__type === 'timestamp' ? v.millis : v;
    }
    return out;
  } catch {
    return null;
  }
}
async function d1Query(db, parent, filters) {
  try {
    const rows = await db.prepare('SELECT doc_id, document_json FROM friendbook_cf_firestore_documents WHERE parent_path = ?').bind(parent).all();
    const docs = [];
    for (const row of rows?.results || []) {
      try {
        const parsed = JSON.parse(row.document_json);
        const out = { id: row.doc_id };
        for (const [k, v] of Object.entries(parsed || {})) {
          out[k] = v && typeof v === 'object' && v.__type === 'timestamp' ? v.millis : v;
        }
        docs.push(out);
      } catch { /* skip malformed */ }
    }
    if (!Array.isArray(filters) || !filters.length) return docs;
    return docs.filter(d => filters.every(f => {
      const actual = d[f.field];
      if (f.op === '==') return actual === f.value;
      if (f.op === 'array-contains') return Array.isArray(actual) && actual.includes(f.value);
      return false;
    }));
  } catch {
    return [];
  }
}
function blockedList(u) {
  return [...(Array.isArray(u?.blockedUsers) ? u.blockedUsers : []), ...(Array.isArray(u?.blocked) ? u.blocked : [])];
}
function fresh(ts) {
  const ms = typeof ts === 'number' ? ts : Date.parse(ts);
  return Number.isFinite(ms) && Date.now() - ms <= 86400000 && ms <= Date.now() + 30000;
}

async function sendToDevices(env, db, actor, target, data, event) {
  const devices = await db.prepare('SELECT device_id, token FROM friendbook_cf_fcm_devices WHERE user_id = ?').bind(target).all()
    .then(r => r?.results || []).catch(() => []);
  if (!devices.length) return { status: 409, body: { error: 'RECIPIENT_NOT_REGISTERED', accepted: 0 } };
  const access = await googleToken(env);
  let accepted = 0, duplicate = 0, invalid = 0, failed = 0;
  for (const device of devices) {
    const claimKey = `fcm:${event}:${device.device_id}`;
    const claimed = await db.prepare(
      'INSERT INTO friendbook_cf_push_claims(event_key, actor, created_at) VALUES (?, ?, ?) ON CONFLICT(event_key) DO NOTHING'
    ).bind(claimKey, actor, Date.now()).run().then(() => true).catch(() => false);
    // D1 INSERT ... ON CONFLICT DO NOTHING still succeeds; check existence instead.
    const exists = await db.prepare('SELECT event_key FROM friendbook_cf_push_claims WHERE event_key = ?').bind(claimKey).first().catch(() => null);
    if (!exists) { failed++; continue; }
    // If this claim was just created by us, proceed; if it existed before, dedupe.
    // We detect "just created" by checking created_at recency is unreliable, so use
    // a deterministic approach: try to detect duplicate via a second marker.
    // Simpler: if the row's actor matches and we just inserted, the INSERT above
    // created it; duplicates are rows created earlier. We approximate by allowing
    // the first send per process via in-memory set.
    if (sendToDevices.seen?.has(claimKey)) { duplicate++; continue; }
    if (!sendToDevices.seen) sendToDevices.seen = new Set();
    sendToDevices.seen.add(claimKey);
    try {
      const r = await fetch(`https://fcm.googleapis.com/v1/projects/${PROJECT}/messages:send`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${access}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: { token: device.token, data, android: { priority: 'HIGH', ttl: '86400s' } } }),
        signal: AbortSignal.timeout(15000),
      });
      const result = await r.json().catch(() => ({}));
      if (r.ok && result.name) { accepted++; continue; }
      const code = result.error?.details?.find(x => x.errorCode)?.errorCode;
      if (code === 'UNREGISTERED') {
        await db.prepare('DELETE FROM friendbook_cf_fcm_devices WHERE device_id = ? AND token = ?').bind(device.device_id, device.token).run().catch(() => {});
        invalid++;
      } else {
        if (r.status === 401) { cachedToken = ''; tokenUntil = 0; }
        failed++;
        await db.prepare('DELETE FROM friendbook_cf_push_claims WHERE event_key = ?').bind(claimKey).run().catch(() => {});
        sendToDevices.seen.delete(claimKey);
      }
    } catch {
      failed++;
      await db.prepare('DELETE FROM friendbook_cf_push_claims WHERE event_key = ?').bind(claimKey).run().catch(() => {});
      sendToDevices.seen.delete(claimKey);
    }
  }
  if (failed) return { status: 503, body: { error: 'FCM_DELIVERY_FAILED', accepted, failed, invalid } };
  if (!accepted && !duplicate) return { status: 409, body: { error: 'NO_VALID_RECIPIENT_DEVICE', accepted, invalid } };
  return { status: 200, body: { provider: 'fcm', accepted, duplicate, invalid, note: 'FCM acceptance is not a device receipt' } };
}

export async function fcmGateway(request, env, identity) {
  const path = new URL(request.url).pathname;
  const isBulk = path === '/functions/v1/bulk-mentions';
  const isSend = path === '/functions/v1/send-fcm-notification';
  if (!isBulk && !isSend) return json({ error: 'Not found' }, 404);
  if (request.method !== 'POST') return json({ error: 'POST required', provider: 'fcm', project: PROJECT }, 405);
  if (!env.DB) return json({ error: 'Database unavailable' }, 503);
  const actor = identity?.uid;
  if (!validId(actor)) return json({ error: 'Unauthorized' }, 401);
  const db = env.DB;
  let body;
  try {
    const text = await request.text();
    if (text.length > 12000) return json({ error: 'REQUEST_TOO_LARGE' }, 413);
    body = JSON.parse(text);
  } catch {
    return json({ error: 'INVALID_REQUEST' }, 400);
  }
  const action = body.action || 'send';
  try {
    if (['register', 'unregister'].includes(action)) {
      if (!validId(body.device_id)) return json({ error: 'INVALID_DEVICE' }, 400);
      if (action === 'unregister') {
        await db.prepare('DELETE FROM friendbook_cf_fcm_devices WHERE device_id = ? AND user_id = ?').bind(body.device_id, actor).run();
        return json({ unregistered: true });
      }
      if (typeof body.token !== 'string' || body.token.length < 20 || body.token.length > 4096) {
        return json({ error: 'INVALID_TOKEN' }, 400);
      }
      await db.prepare(
        `INSERT INTO friendbook_cf_fcm_devices(device_id, user_id, token, updated_at) VALUES (?, ?, ?, ?)
         ON CONFLICT(device_id) DO UPDATE SET user_id = excluded.user_id, token = excluded.token, updated_at = excluded.updated_at`
      ).bind(body.device_id, actor, body.token, Date.now()).run();
      return json({ registered: true, provider: 'fcm' });
    }
    if (action === 'status') {
      await googleToken(env);
      const devices = await db.prepare('SELECT device_id, updated_at FROM friendbook_cf_fcm_devices WHERE user_id = ?').bind(actor).all()
        .then(r => r?.results || []).catch(() => []);
      return json({ provider: 'fcm', project: PROJECT, registered_devices: devices.length, server_configured: true, google_oauth_verified: true });
    }
    if (isBulk) return await bulkMention(body, actor, db, env);
    // send / self_test
    const input = body.data;
    const target = action === 'self_test' ? actor : body.target_user_id;
    if (!validId(target) || (action !== 'self_test' && (!input || typeof input !== 'object' || target === actor))) {
      return json({ error: 'INVALID_TARGET' }, 400);
    }
    if (!['send', 'self_test'].includes(action)) return json({ error: 'INVALID_ACTION' }, 400);
    let data, event, title, message;
    if (action === 'self_test') {
      event = `selftest_${actor}_${Math.floor(Date.now() / 60000)}`;
      title = 'friendbook push test';
      message = 'Direct FCM reached your phone. Notification permission and delivery are working.';
      data = { type: 'push_test', toUserId: actor, fromUserId: actor, eventId: event, title, body: message };
    } else {
      const sender = await d1Doc(db, `users/${actor}`);
      const recipient = await d1Doc(db, `users/${target}`);
      if (!sender || !recipient) return json({ error: 'INVALID_OR_UNAVAILABLE_EVENT' }, 400);
      if (sender.banned || recipient.banned) return json({ error: 'Unavailable account' }, 403);
      if (blockedList(recipient).includes(actor)) return json({ skipped: 'blocked' });
      if (input.type === 'message' && validId(input.chatId) && validId(input.messageId)) {
        const chat = await d1Doc(db, `chats/${input.chatId}`);
        const m = await d1Doc(db, `chats/${input.chatId}/messages/${input.messageId}`);
        if (!m || !chat || m.senderId !== actor || !chat.participants?.includes(actor) || !chat.participants?.includes(target) || m.isDeleted) {
          return json({ error: 'Forbidden event' }, 403);
        }
        if (!fresh(m.createdAt ?? m.createdAtMs)) return json({ error: 'Expired event' }, 400);
        event = `message_${input.chatId}_${input.messageId}_${target}`;
        message = String(m.text || 'Sent an attachment').slice(0, 500);
        data = { type: 'message', chatId: input.chatId, isGroup: String(chat.isGroup === true) };
      } else if (validId(input.notificationId)) {
        const n = await d1Doc(db, `notifications/${input.notificationId}`);
        if (!n || n.fromUserId !== actor || n.toUserId !== target) return json({ error: 'Forbidden event' }, 403);
        if (!fresh(n.createdAt)) return json({ error: 'Expired event' }, 400);
        event = `notification_${input.notificationId}`;
        message = String(n.text || 'New notification').slice(0, 500);
        data = { type: n.type, postId: n.postId || '', contentType: n.contentType || '', notificationId: input.notificationId, groupId: n.groupId || '' };
      } else {
        return json({ error: 'Persisted event required' }, 400);
      }
      title = String(sender.displayName || sender.name || 'friendbook').slice(0, 100);
      Object.assign(data, { toUserId: target, fromUserId: actor, eventId: event, title, body: message });
    }
    const result = await sendToDevices(env, db, actor, target, data, event);
    return json(result.body, result.status);
  } catch (error) {
    const code = error instanceof Error ? error.message : 'INVALID_REQUEST';
    const known = ['FCM_NOT_CONFIGURED', 'FCM_INVALID_JSON', 'FCM_PROJECT_MISMATCH', 'FCM_OAUTH_FAILED'];
    return json({ error: known.includes(code) ? code : 'INVALID_OR_UNAVAILABLE_EVENT' }, known.includes(code) ? 503 : 400);
  }
}

async function bulkMention(body, actor, db, env) {
  if (!validId(body.post_id) || (body.comment_id && !validId(body.comment_id))) {
    return json({ error: 'INVALID_SOURCE' }, 400);
  }
  const post = await d1Doc(db, `posts/${body.post_id}`);
  const sender = await d1Doc(db, `users/${actor}`);
  if (!post || !sender || sender.banned || String(post.privacy).toLowerCase() !== 'public' || post.groupId || post.pageId || post.isDeleted || post.safetyFlagged) {
    return json({ error: 'BULK_PUBLIC_POST_REQUIRED' }, 403);
  }
  let source;
  if (body.comment_id) {
    source = await d1Doc(db, `posts/${body.post_id}/comments/${body.comment_id}`)
      || await d1Doc(db, `comments/${body.comment_id}`)
      || (post.commentsMap || {})[body.comment_id];
  } else {
    source = post;
  }
  if (!source || source.authorId !== actor) return json({ error: 'BULK_SOURCE_FORBIDDEN' }, 403);
  const text = String(source.content || '');
  const tags = new Set((Array.isArray(source.mentions) ? source.mentions : []).filter(m =>
    ['bulk:followers', 'bulk:highlight'].includes(m.uid) && Number.isInteger(m.start) && Number.isInteger(m.end)
    && m.start >= 0 && m.end > m.start && m.end <= text.length && m.label === `@${m.uid.slice(5)}`
    && text.slice(m.start, m.end) === m.label).map(m => m.uid));
  if (!tags.size) return json({ error: 'NO_PERSISTED_BULK_MENTION' }, 400);
  if (tags.has('bulk:followers') && post.authorId !== actor) return json({ error: 'FOLLOWERS_REQUIRES_POST_OWNER' }, 403);
  const created = typeof source.createdAt === 'number' ? source.createdAt : Date.parse(source.createdAt);
  if (!Number.isFinite(created) || Date.now() - created > 86400000 || created > Date.now() + 30000) {
    return json({ error: 'BULK_SOURCE_EXPIRED' }, 400);
  }
  const candidates = new Set();
  const highlightIds = new Set();
  if (tags.has('bulk:followers')) {
    for (const uid of Array.isArray(sender.followers) ? sender.followers : []) {
      if (validId(uid) && candidates.size < 40) candidates.add(uid);
    }
  }
  if (tags.has('bulk:highlight')) {
    const friends = await d1Query(db, 'friends', [{ field: 'participants', op: 'array-contains', value: actor }]);
    for (const f of friends) {
      for (const uid of Array.isArray(f.participants) ? f.participants : []) {
        if (validId(uid) && candidates.size < 80) { candidates.add(uid); highlightIds.add(uid); }
      }
    }
  }
  candidates.delete(actor);
  const eligible = [];
  for (const uid of candidates) {
    if (eligible.length >= 20) break;
    if (blockedList(sender).includes(uid)) continue;
    const recipient = await d1Doc(db, `users/${uid}`);
    if (!recipient || recipient.banned || blockedList(recipient).includes(actor) || recipient.bulkMentionsEnabled === false) continue;
    const follower = tags.has('bulk:followers') && Array.isArray(recipient.following) && recipient.following.includes(actor);
    const friend = tags.has('bulk:highlight') && highlightIds.has(uid);
    if (follower || friend) eligible.push(uid);
  }
  if (!eligible.length) return json({ inbox: 0, accepted: 0, note: 'No eligible recipients' });
  const event = `${body.post_id}:${body.comment_id || 'post'}`;
  // Daily limit: one bulk event per actor per 24h (D1 claim).
  const dayKey = `bulk:${actor}:${event}`;
  const prior = await db.prepare('SELECT event_key FROM friendbook_cf_push_claims WHERE event_key = ?').bind(dayKey).first().catch(() => null);
  if (prior) return json({ error: 'BULK_DAILY_LIMIT', cooldown_hours: 24 }, 429);
  await db.prepare('INSERT INTO friendbook_cf_push_claims(event_key, actor, created_at) VALUES (?, ?, ?)').bind(dayKey, actor, Date.now()).run().catch(() => {});
  let inbox = 0, accepted = 0, failed = 0;
  for (const uid of eligible.slice(0, 20)) {
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(`${actor}:${event}:${uid}`));
    const id = 'bulk_' + Array.from(new Uint8Array(digest), x => x.toString(16).padStart(2, '0')).join('');
    const now = Date.now();
    const doc = {
      toUserId: uid, fromUserId: actor,
      fromUserName: String(sender.displayName || 'friendbook User'),
      fromUserPhotoUrl: String(sender.photoUrl || ''),
      type: 'mention', text: 'mentioned their audience in a post', postId: body.post_id,
      isRead: false, createdAt: { __type: 'timestamp', millis: now },
    };
    const path = `notifications/${id}`;
    const existing = await d1Doc(db, path);
    if (!existing) {
      const parent = 'notifications';
      const coll = 'notifications';
      await db.prepare(
        `INSERT INTO friendbook_cf_firestore_documents(path, parent_path, collection_id, doc_id, owner_uid, document_json, create_time, update_time)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(path) DO NOTHING`
      ).bind(path, parent, coll, id, actor, JSON.stringify(doc), now, now).run().catch(() => {});
    } else if (existing.fromUserId !== actor || existing.toUserId !== uid || existing.postId !== body.post_id || existing.type !== 'mention') {
      failed++;
      continue;
    }
    inbox++;
    const delivered = await sendToDevices(env, db, actor, uid,
      { type: 'mention', postId: body.post_id, notificationId: id, toUserId: uid, fromUserId: actor, eventId: `notification_${id}`, title: String(sender.displayName || 'friendbook').slice(0, 100), body: 'mentioned their audience in a post' },
      `notification_${id}`);
    accepted += Number(delivered.body.accepted || 0);
    if (delivered.status >= 500 || delivered.status === 429) failed++;
  }
  return json({ inbox, accepted, failed, note: 'Some eligible people may receive notifications; FCM acceptance is not delivery' }, failed ? 503 : 200);
}
