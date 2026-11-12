// Authenticated Cloudflare Realtime Presence & Typing over WebSockets, HTTP heartbeats and D1.
//
// Firebase Realtime Database is not part of this product any more: presence and typing are Cloudflare
// state. D1 is the source of truth so a contact served by a different isolate (or a different
// colo) still sees the same online/last-seen/typing facts. The in-process maps only coalesce writes
// and shorten reads - losing an isolate loses no state.
export const PRESENCE_TTL_MS = 300_000;
export const WRITE_COALESCE_MS = 90_000;
export const TYPING_TTL_MS = 8_000;
const READ_CACHE_MS = 5_000;
const TYPING_WRITE_COALESCE_MS = 2_000;
const MAX_BODY_BYTES = 256;

const json = (body, status = 200) => Response.json(body, {
  status, headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' },
});

export function validId(value) {
  return typeof value === 'string' && value.length > 0 && value.length <= 128
    && !/[\/\x00-\x1f]/.test(value) && !['.', '..'].includes(value);
}

export function isFreshOnline(online, visible, lastSeen, now) {
  return Boolean(online) && visible !== false && visible !== 0
    && Number.isFinite(lastSeen) && lastSeen > 0 && lastSeen <= now && (now - lastSeen) < PRESENCE_TTL_MS;
}

const presenceCache = new WeakMap();
const presenceReads = new WeakMap();
const typingCache = new Map();
const typingWrites = new Map();

function getPresenceMap(db) {
  let map = presenceCache.get(db);
  if (!map) {
    map = new Map();
    presenceCache.set(db, map);
  }
  return map;
}

function getReadMap(db) {
  let map = presenceReads.get(db);
  if (!map) {
    map = new Map();
    presenceReads.set(db, map);
  }
  return map;
}

async function smallBody(request) {
  const length = Number(request.headers.get('Content-Length') || '0');
  if (!Number.isFinite(length) || length < 0 || length > MAX_BODY_BYTES) return null;
  try {
    const text = await request.text();
    if (text.length > MAX_BODY_BYTES) return null;
    const parsed = JSON.parse(text);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

/**
 * Records presence in D1. Writes are coalesced to one per WRITE_COALESCE_MS per uid, except the
 * transition to offline, which is written immediately so "last seen" never overstates a session.
 */
export async function writePresence(db, uid, online, visible, now) {
  if (!db || !validId(uid)) return false;
  const map = getPresenceMap(db);
  const prev = map.get(uid);
  const bestSeen = prev && prev.last_seen > now ? prev.last_seen : now;
  const state = { online: online ? 1 : 0, visible: visible ? 1 : 0, last_seen: bestSeen };
  const wentOffline = Boolean(prev?.online) && !online;
  const due = !prev || wentOffline || !prev.written_at || (now - prev.written_at) >= WRITE_COALESCE_MS;
  map.set(uid, { ...state, written_at: due ? now : prev.written_at });
  if (!due) return false;
  try {
    await db.prepare(
      `INSERT INTO friendbook_cf_presence(uid, online, visible, last_seen, updated_at)
       VALUES (?, ?, ?, ?, ?)
       ON CONFLICT(uid) DO UPDATE SET
         online = excluded.online,
         visible = excluded.visible,
         last_seen = max(friendbook_cf_presence.last_seen, excluded.last_seen),
         updated_at = excluded.updated_at`
    ).bind(uid, state.online, state.visible, state.last_seen, now).run();
    getReadMap(db).delete(uid);
    return true;
  } catch {
    return false;
  }
}

export async function readPresence(db, uid, now) {
  const reads = getReadMap(db);
  const cached = reads.get(uid);
  if (cached && cached.expiresAt > now) {
    return project(cached.row, uid, now);
  }
  let row = null;
  try {
    row = await db.prepare(
      'SELECT online, visible, last_seen FROM friendbook_cf_presence WHERE uid = ?'
    ).bind(uid).first();
  } catch {
    row = null;
  }
  reads.set(uid, { row: row || null, expiresAt: now + READ_CACHE_MS });
  return project(row, uid, now);
}

function project(row, uid, now) {
  if (!row || row.visible === 0) return { uid, online: false, lastSeen: 0 };
  const lastSeen = Number(row.last_seen) || 0;
  return { uid, online: isFreshOnline(row.online === 1, true, lastSeen, now), lastSeen };
}

/** Typing is D1-backed too, so the peer sees it even when the two clients land on different isolates. */
async function writeTyping(db, chatId, uid, typing, now) {
  const key = `${chatId}:${uid}`;
  const chatMap = typingCache.get(chatId);
  if (chatMap) {
    if (typing) chatMap.set(uid, now);
    else chatMap.delete(uid);
  }
  const last = typingWrites.get(key);
  // A repeat of the same state inside the coalescing window is not worth a D1 write.
  if (last && last.typing === typing && (now - last.at) < TYPING_WRITE_COALESCE_MS) return false;
  typingWrites.set(key, { typing, at: now });
  try {
    if (typing) {
      await db.prepare(
        `INSERT INTO friendbook_cf_typing(chat_id, uid, typing, updated_at) VALUES (?, ?, 1, ?)
         ON CONFLICT(chat_id, uid) DO UPDATE SET typing = 1, updated_at = excluded.updated_at`
      ).bind(chatId, uid, now).run();
    } else {
      await db.prepare(
        'UPDATE friendbook_cf_typing SET typing = 0, updated_at = ? WHERE chat_id = ? AND uid = ?'
      ).bind(now, chatId, uid).run();
    }
    return true;
  } catch {
    return false;
  }
}

async function readTyping(db, chatId, uid, now) {
  const since = now - TYPING_TTL_MS;
  let active = false;
  try {
    const rows = await db.prepare(
      'SELECT uid FROM friendbook_cf_typing WHERE chat_id = ? AND typing = 1 AND updated_at > ? LIMIT 8'
    ).bind(chatId, since).all();
    for (const row of rows?.results || []) {
      if (row.uid && row.uid !== uid) { active = true; break; }
    }
  } catch {
    active = false;
  }
  if (!active) {
    // Same-isolate fast path: a keystroke that has not reached D1 yet is still true for the peer here.
    const chatMap = typingCache.get(chatId);
    if (chatMap) {
      for (const [peerUid, ts] of chatMap.entries()) {
        if (now - ts <= TYPING_TTL_MS) {
          if (peerUid !== uid) { active = true; break; }
        } else {
          chatMap.delete(peerUid);
        }
      }
    }
  }
  return active;
}

export async function presenceRealtime(request, env, identity) {
  if (env.REALTIME_WS_ENABLED === 'false' || !env.DB) return json({ error: 'Presence unavailable' }, 503);
  const url = new URL(request.url);
  if (url.search) return json({ error: 'Query parameters forbidden' }, 400);
  const path = url.pathname;
  const uid = identity?.uid;
  if (!validId(uid)) return json({ error: 'Unauthorized' }, 401);
  const now = Date.now();

  if (path === '/v1/presence/me') {
    // PUT is the HTTP heartbeat for clients without a WebSocket (the Android app): it carries the
    // same fact the socket carries, and D1 keeps it true for every other isolate.
    if (request.method === 'PUT') {
      const body = await smallBody(request);
      if (!body || typeof body.online !== 'boolean') return json({ error: 'Invalid presence state' }, 400);
      const visible = body.visible === undefined ? true : Boolean(body.visible);
      await writePresence(env.DB, uid, body.online, visible, now);
      return json(await readPresence(env.DB, uid, Date.now()));
    }
    if (request.method !== 'GET') return json({ error: 'Method not allowed' }, 405);
    try { return json(await readPresence(env.DB, uid, now)); }
    catch { return json({ error: 'Presence unavailable' }, 503); }
  }

  const contact = /^\/v1\/presence\/contact\/([^/]+)$/.exec(path);
  if (contact) {
    if (request.method !== 'GET') return json({ error: 'Method not allowed' }, 405);
    let target;
    try { target = decodeURIComponent(contact[1]); } catch { return json({ error: 'Invalid target' }, 400); }
    if (!validId(target)) return json({ error: 'Invalid target' }, 400);
    try { return json(await readPresence(env.DB, target, now)); }
    catch { return json({ error: 'Presence unavailable' }, 503); }
  }

  const typing = /^\/v1\/presence\/typing\/([^/]+)$/.exec(path);
  if (typing) {
    let chatId;
    try { chatId = decodeURIComponent(typing[1]); } catch { return json({ error: 'Invalid chat' }, 400); }
    if (!validId(chatId)) return json({ error: 'Invalid chat' }, 400);
    if (request.method === 'PUT') {
      const body = await smallBody(request);
      if (!body || typeof body.typing !== 'boolean') return json({ error: 'Invalid typing state' }, 400);
      let chatMap = typingCache.get(chatId);
      if (!chatMap) {
        chatMap = new Map();
        typingCache.set(chatId, chatMap);
      }
      if (body.typing) chatMap.set(uid, now);
      else chatMap.delete(uid);
      await writeTyping(env.DB, chatId, uid, body.typing, now);
      return json({ chatId, typing: body.typing });
    }
    if (request.method === 'GET') {
      return json({ chatId, typing: await readTyping(env.DB, chatId, uid, now) });
    }
    return json({ error: 'Method not allowed' }, 405);
  }

  if (path === '/v1/presence/socket') {
    if (request.method !== 'GET') return json({ error: 'Method not allowed' }, 405);
    if (request.headers.get('Upgrade')?.toLowerCase() !== 'websocket') return json({ error: 'WebSocket required' }, 426);
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    server.accept();
    const state = { uid, active: true, visible: true, lastSeen: now, lastWrite: 0, lastMessage: 0 };
    writePresence(env.DB, uid, true, true, state.lastSeen).then(() => { state.lastWrite = state.lastSeen; }).catch(() => {});

    server.addEventListener('message', async event => {
      const at = Date.now();
      if ( typeof event.data !== 'string' || new TextEncoder().encode(event.data).length > 4096) {
        server.close(1009, 'Invalid payload'); return;
      }
      if (event.data === 'ping') { server.send('pong'); return; }
      let data;
      try { data = JSON.parse(event.data); } catch { server.close(1008, 'Invalid JSON'); return; }
      if (!data || Array.isArray(data) || Object.keys(data).some(k => k !== 'type')
          || !['active', 'offline', 'me'].includes(data.type)) {
        server.close(1008, 'Unsupported message'); return;
      }
      if (state.lastMessage && at - state.lastMessage < 500) {
        server.close(1008, 'Message rate exceeded'); return;
      }
      state.lastMessage = at;
      if (data.type === 'active') {
        state.active = true; state.lastSeen = at; state.lastWrite = at;
        try { await writePresence(env.DB, uid, true, state.visible, at); } catch {}
      } else if (data.type === 'offline') {
        state.active = false; state.lastSeen = at; state.lastWrite = at;
        try { await writePresence(env.DB, uid, false, state.visible, at); } catch {}
      }
      const online = isFreshOnline(state.active, state.visible, state.lastSeen, at);
      server.send(JSON.stringify({ uid, online, lastSeen: state.visible ? state.lastSeen : 0 }));
    });

    const markOffline = () => {
      const at = Date.now();
      state.active = false; state.lastSeen = at;
      writePresence(env.DB, uid, false, state.visible, at).catch(() => {});
    };
    server.addEventListener('close', markOffline);
    server.addEventListener('error', markOffline);
    return new Response(null, { status: 101, webSocket: client });
  }

  return json({ error: 'Not found' }, 404);
}
