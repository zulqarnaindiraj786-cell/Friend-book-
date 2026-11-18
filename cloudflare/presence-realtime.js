// Authenticated Cloudflare Realtime Presence & Typing over WebSockets + D1.
import { loadChat } from './chat-access.js';
import { readD1DocumentFields, hasD1Friendship } from './app-documents.js';
export const PRESENCE_TTL_MS = 300_000; // 5 minutes (300 seconds)
export const WRITE_COALESCE_MS = 30_000; // Write every 30s (matches heartbeat)
export const TYPING_TTL_MS = 8_000;
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
const typingCache = new Map();

function getPresenceMap(db) {
  let map = presenceCache.get(db);
  if (!map) {
    map = new Map();
    presenceCache.set(db, map);
  }
  return map;
}

async function writePresence(db, uid, online, visible, now) {
  if (!db) return;
  const map = getPresenceMap(db);
  const prev = map.get(uid);
  const bestSeen = prev && prev.last_seen > now ? prev.last_seen : now;
  map.set(uid, { online: online ? 1 : 0, visible: visible ? 1 : 0, last_seen: bestSeen });
}
async function readPresence(db, uid, now) {
  const map = getPresenceMap(db);
  let row = map.get(uid);
  if (row === undefined) {
    row = await db.prepare('SELECT online, visible, last_seen FROM friendbook_cf_presence WHERE uid = ?').bind(uid).first();
    map.set(uid, row || null);
  }
  if (!row || row.visible === 0) return { uid, online: false, lastSeen: 0 };
  const lastSeen = Number(row.last_seen) || 0;
  return { uid, online: isFreshOnline(row.online === 1, true, lastSeen, now), lastSeen };
}
export async function presenceRealtime(request, env, identity) {
  if (env.REALTIME_WS_ENABLED === 'false' || !env.DB) return json({ error: 'Presence unavailable' }, 503);
  const url = new URL(request.url);
  if (url.search) return json({ error: 'Query parameters forbidden' }, 400);
  const path = url.pathname;
  const uid = identity?.uid;
  if (!validId(uid)) return json({ error: 'Unauthorized' }, 401);

  if (path === '/v1/presence/me') {
    if (request.method !== 'GET') return json({ error: 'Method not allowed' }, 405);
    try { return json(await readPresence(env.DB, uid, Date.now())); }
    catch { return json({ error: 'Presence unavailable' }, 503); }
  }

  const contact = /^\/v1\/presence\/contact\/([^/]+)$/.exec(path);
  if (contact) {
    if (request.method !== 'GET') return json({ error: 'Method not allowed' }, 405);
    let target;
    try { target = decodeURIComponent(contact[1]); } catch { return json({ error: 'Invalid target' }, 400); }
    if (!validId(target)) return json({ error: 'Invalid target' }, 400);
    try {
      if (target !== uid) {
        const me = await readD1DocumentFields(env.DB, `users/${uid}`);
        const peer = await readD1DocumentFields(env.DB, `users/${target}`);
        const blocked = (fields, id) => ['blocked','blockedUsers'].some(key =>
          (fields?.[key]?.arrayValue?.values || []).some(value => value.stringValue === id));
        if (!me || !peer || me.banned?.booleanValue || peer.banned?.booleanValue
            || me.deactivated?.booleanValue || peer.deactivated?.booleanValue
            || peer.showActiveStatus?.booleanValue === false || blocked(me,target) || blocked(peer,uid)
            || await hasD1Friendship(env.DB, uid, target) !== true) return json({ error: 'Forbidden' }, 403);
      }
      return json(await readPresence(env.DB, target, Date.now()));
    } catch { return json({ error: 'Presence unavailable' }, 503); }
  }

  const typing = /^\/v1\/presence\/typing\/([^/]+)$/.exec(path);
  if (typing) {
    let chatId;
    try { chatId = decodeURIComponent(typing[1]); } catch { return json({ error: 'Invalid chat' }, 400); }
    if (!validId(chatId)) return json({ error: 'Invalid chat' }, 400);
    try {
      const room = await loadChat(env.DB, chatId);
      if (!Array.isArray(room?.participants) || !room.participants.includes(uid)) return json({ error: 'Forbidden' }, 403);
    } catch { return json({ error: 'Presence unavailable' }, 503); }
    const now = Date.now();
    if (request.method === 'PUT') {
      let body;
      try { body = await request.json(); } catch { return json({ error: 'Invalid JSON' }, 400); }
      if (!body || typeof body.typing !== 'boolean') return json({ error: 'Invalid typing state' }, 400);
      let chatMap = typingCache.get(chatId);
      if (!chatMap) {
        chatMap = new Map();
        typingCache.set(chatId, chatMap);
      }
      if (body.typing) chatMap.set(uid, now);
      else chatMap.delete(uid);
      return json({ chatId, typing: body.typing });
    }
    if (request.method === 'GET') {
      const chatMap = typingCache.get(chatId);
      let active = false;
      if (chatMap) {
        for (const [peerUid, ts] of chatMap.entries()) {
          if (now - ts <= TYPING_TTL_MS) {
            if (peerUid !== uid) active = true;
          } else {
            chatMap.delete(peerUid);
          }
        }
      }
      return json({ chatId, typing: active });
    }
    return json({ error: 'Method not allowed' }, 405);
  }

  if (path === '/v1/presence/socket') {
    if (request.method !== 'GET') return json({ error: 'Method not allowed' }, 405);
    if (request.headers.get('Upgrade')?.toLowerCase() !== 'websocket') return json({ error: 'WebSocket required' }, 426);
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    server.accept();
    const state = { uid, active: true, visible: true, lastSeen: Date.now(), lastWrite: 0, lastMessage: 0 };
    writePresence(env.DB, uid, true, true, state.lastSeen).then(() => { state.lastWrite = state.lastSeen; }).catch(() => {});

    server.addEventListener('message', async event => {
      const now = Date.now();
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
      if (state.lastMessage && now - state.lastMessage < 500) {
        server.close(1008, 'Message rate exceeded'); return;
      }
      state.lastMessage = now;
      if (data.type === 'active') {
        const becameActive = !state.active;
        state.active = true; state.lastSeen = now;
        if (becameActive || now - state.lastWrite >= WRITE_COALESCE_MS) {
          state.lastWrite = now;
          try { await writePresence(env.DB, uid, true, state.visible, now); } catch {}
        }
      } else if (data.type === 'offline') {
        state.active = false; state.lastSeen = now; state.lastWrite = now;
        try { await writePresence(env.DB, uid, false, state.visible, now); } catch {}
      }
      const online = isFreshOnline(state.active, state.visible, state.lastSeen, now);
      server.send(JSON.stringify({ uid, online, lastSeen: state.visible ? state.lastSeen : 0 }));
    });

    const markOffline = () => {
      const now = Date.now();
      state.active = false; state.lastSeen = now;
      writePresence(env.DB, uid, false, state.visible, now).catch(() => {});
    };
    server.addEventListener('close', markOffline);
    server.addEventListener('error', markOffline);
    return new Response(null, { status: 101, webSocket: client });
  }

  return json({ error: 'Not found' }, 404);
}
