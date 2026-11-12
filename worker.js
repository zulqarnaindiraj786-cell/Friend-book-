import { uploadVideo, uploadPhoto, retryVideoCleanup } from './cloudflare/video-r2.js';
import { presenceRealtime } from './cloudflare/presence-realtime.js';
import { androidRelease } from './cloudflare/android-release.js';
import { appConfig } from './cloudflare/app-config.js';
import { interactions } from './cloudflare/interactions.js';
import { appDocuments } from './cloudflare/app-documents.js';
import { legacyMigration } from './cloudflare/legacy-migration.js';
import { viewCounters } from './cloudflare/view-counters.js';
import { createRemoteJWKSet, jwtVerify } from 'jose';

const googleKeys = createRemoteJWKSet(new URL(
  'https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com'
));
export async function verifyFirebaseIdentity(token, project, keys = googleKeys) {
  if (!project) throw new Error('Firebase project is not configured');
  const { payload } = await jwtVerify(token, keys, {
    algorithms: ['RS256'], audience: project,
    issuer: `https://securetoken.google.com/${project}`,
    requiredClaims: ['exp', 'iat', 'sub', 'auth_time'],
    maxTokenAge: '1h', clockTolerance: 5,
  });
  if (typeof payload.sub !== 'string' || !payload.sub.length || payload.sub.length > 128 ||
      typeof payload.auth_time !== 'number' || payload.auth_time > Date.now() / 1000 + 5) {
    throw new Error('Invalid Firebase identity');
  }
  // The email claim only ever decides the owner-admin shortcut; the uid is the identity.
  return { uid: payload.sub, email: typeof payload.email === 'string' ? payload.email.slice(0, 200) : '' };
}
export async function verifyFirebaseToken(token, project, keys = googleKeys) {
  return (await verifyFirebaseIdentity(token, project, keys)).uid;
}
const json = (body, status = 200) => Response.json(body, {
  status, headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' },
});
export function createWorker(verify = verifyFirebaseIdentity) {
  return {
    async fetch(request, env, ctx) {
      const path = new URL(request.url).pathname;
      // API namespaces must never fall through to HTML, even for unknown endpoints.
      const api = path === '/health' || path.startsWith('/health/') || path === '/v1' || path.startsWith('/v1/');
      if (!api) {
        if (!['GET', 'HEAD'].includes(request.method)) return json({ error: 'Method not allowed' }, 405);
        if (!env.ASSETS) return json({ error: 'Frontend unavailable' }, 503);
        const assetURL = new URL(request.url);
        if (['/', '/login', '/signup', '/login/', '/signup/'].includes(path)) assetURL.pathname = '/index.html';
        try { return await env.ASSETS.fetch(new Request(assetURL, request)); }
        catch { return json({ error: 'Frontend unavailable' }, 503); }
      }
      if (path === '/v1/android-release') return request.method === 'GET' ? json(androidRelease) : json({ error: 'Method not allowed' }, 405);
      // Public runtime switches (ads kill switch, presence flag). No identity, no data, no secrets:
      // this is what replaced Firebase Remote Config, so it must answer before the auth gate.
      if (path === '/v1/config') return appConfig(request, env);
      const video = path === '/v1/videos';
      const photo = path === '/v1/photos';
      // The one-time carry-over of a user's own legacy documents, run here because the phone no longer
      // ships that store's SDK. Reads with the caller's own token, writes into D1, never deletes.
      const migrate = path === '/v1/migrate/legacy';
      // Two segments address a document, four address a document inside a nested collection
      // (chats/<chatId>/messages/<messageId>); anything else is not a document path at all.
      const docs = path === '/v1/docs' || path === '/v1/docs/batch'
        || /^\/v1\/docs\/[^/]+\/[^/]+$/.test(path)
        || /^\/v1\/docs\/[^/]+\/[^/]+\/[^/]+\/[^/]+$/.test(path);
      const presence = path === '/v1/presence/socket' || path === '/v1/presence/me'
        || /^\/v1\/presence\/(contact|typing)\/[^/]+$/.test(path);
      const engagement = /^\/v1\/engagement\/(posts|reels)\/([^/]+)\/(reactions|comments|reactors)$/.exec(path);
      // Real view counters live in Cloudflare D1 (Firebase is auth only, never view data).
      const viewsMatch = /^\/v1\/views\/(posts|reels)\/([^/]+)$/.exec(path);
      const views = path === '/v1/views' || Boolean(viewsMatch);
      if (path !== '/health' && path !== '/health/r2' && path !== '/v1/me' && !engagement && !video && !photo && !presence && !docs && !views && !migrate) return json({ error: 'Not found' }, 404);
      if ((path === '/health' || path === '/health/r2') && request.method !== 'GET') return json({ error: 'Method not allowed' }, 405);
      if (path === '/v1/me' && request.method !== 'GET' && request.method !== 'PUT') return json({ error: 'Method not allowed' }, 405);
      if (path === '/health/r2') {
        if (!env.VIDEO_BUCKET || env.VIDEO_R2_ENABLED !== 'true' || env.VIDEO_R2_RETENTION_VERIFIED !== 'true') {
          return json({ ok: false, r2Bound: Boolean(env.VIDEO_BUCKET), videoR2Enabled: env.VIDEO_R2_ENABLED === 'true' }, 503);
        }
        try {
          await env.VIDEO_BUCKET.head('videos/__healthcheck__');
          return json({ ok: true, r2Bound: true, videoR2Enabled: true });
        } catch { return json({ ok: false, r2Bound: true, videoR2Enabled: true }, 503); }
      }
      if (path === '/health') {
        if (!env.DB) return json({ ok: false }, 503);
        // Binding flags only - no data, no counts. It proves what a deploy actually shipped:
        // views run on Analytics Engine (+ KV), and D1 is never written by the view path.
        const views = { ae: Boolean(env.ANALYTICS_ENGINE), kv: Boolean(env.VIEWS), r2Snapshot: !env.VIEWS && Boolean(env.VIDEO_BUCKET), aeReadToken: Boolean(env.CF_AE_TOKEN) };
        try {
          const row = await env.DB.prepare('SELECT 1 AS connected').first();
          return json({ ok: row?.connected === 1, views }, row?.connected === 1 ? 200 : 503);
        } catch { return json({ ok: false, views }, 503); }
      }
      if (!env.FIREBASE_PROJECT_ID) return json({ error: 'Service unavailable' }, 503);
      const bearer = /^Bearer ([^\s]+)$/i.exec(request.headers.get('Authorization') || '');
      if (!bearer || bearer[1].length > 8192) return json({ error: 'Unauthorized' }, 401);
      let uid, email = '';
      try {
        // A verifier may answer with a uid string (tests, older callers) or with the identity object.
        const verified = await verify(bearer[1], env.FIREBASE_PROJECT_ID);
        uid = typeof verified === 'string' ? verified : verified?.uid;
        email = typeof verified === 'object' && verified && typeof verified.email === 'string' ? verified.email : '';
      } catch { return json({ error: 'Unauthorized' }, 401); }
      if (typeof uid !== 'string' || !uid.length || uid.length > 128) return json({ error: 'Unauthorized' }, 401);
      if (video) return uploadVideo(request, env, uid);
      if (photo) return uploadPhoto(request, env, uid);
      // `LEGACY_FETCH` is the seam the tests use to answer for the legacy store; production leaves it
      // unset and the Worker's own fetch runs.
      if (migrate) return legacyMigration(request, env, { uid, email, token: bearer[1] }, env.LEGACY_FETCH || fetch);
      if (docs) return appDocuments(request, env, { uid, email, token: bearer[1] });
      // View counters run on memory/KV (zero D1 row reads); ctx lets the hourly D1 flush and the
      // KV aggregate write happen after the response is already sent.
      if (views) return viewCounters(request, env, { uid, token: bearer[1] }, viewsMatch, new URL(request.url), Date.now, ctx);
      if (presence) return presenceRealtime(request, env, { uid, token: bearer[1] });
      if (engagement) return interactions(request, env, { uid, token: bearer[1], project: env.FIREBASE_PROJECT_ID, db: env.DB }, engagement);
      try {
        if (request.method === 'PUT') {
          let body = {};
          try { body = await request.json(); } catch { return json({ error: 'Invalid profile payload' }, 400); }
          const displayName = typeof body?.displayName === 'string' ? body.displayName.slice(0, 200) : '';
          const avatarUrl = typeof body?.photoUrl === 'string' ? body.photoUrl.slice(0, 2048) : null;
          await env.DB.prepare(
            'INSERT INTO friendbook_cf_profiles(uid, display_name, cloudinary_avatar_url) VALUES (?, ?, ?) '
            + 'ON CONFLICT(uid) DO UPDATE SET display_name = excluded.display_name, cloudinary_avatar_url = excluded.cloudinary_avatar_url'
          ).bind(uid, displayName, avatarUrl).run();
        }
        // UID comes only from a verified Firebase token, never from query/client JSON.
        const profile = await env.DB.prepare(
          'SELECT uid, display_name, cloudinary_avatar_url FROM friendbook_cf_profiles WHERE uid = ?'
        ).bind(uid).first();
        return json({ uid, profile: profile || null });
      } catch { return json({ error: 'Database unavailable; existing app data is unchanged' }, 503); }
    },
  };
}
const app = createWorker();
export default {...app, scheduled(_controller, env, ctx) { ctx.waitUntil(retryVideoCleanup(env)); }};
