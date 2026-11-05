import { uploadVideo, retryVideoCleanup } from './cloudflare/video-r2.js';
import { androidRelease } from './cloudflare/android-release.js';
import { interactions } from './cloudflare/interactions.js';
import { createRemoteJWKSet, jwtVerify } from 'jose';

const googleKeys = createRemoteJWKSet(new URL(
  'https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com'
));
export async function verifyFirebaseToken(token, project, keys = googleKeys) {
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
  return payload.sub;
}
const json = (body, status = 200) => Response.json(body, {
  status, headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' },
});
export function createWorker(verify = verifyFirebaseToken) {
  return {
    async fetch(request, env) {
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
      const video = path === '/v1/videos';
      const engagement = /^\/v1\/engagement\/(posts|reels)\/([^/]+)\/(reactions|comments|reactors)$/.exec(path);
      if (path !== '/health' && path !== '/v1/me' && !engagement && !video) return json({ error: 'Not found' }, 404);
      if (!engagement && !video && request.method !== 'GET') return json({ error: 'Method not allowed' }, 405);
      if (path === '/health') {
        if (!env.DB) return json({ ok: false }, 503);
        try {
          const row = await env.DB.prepare('SELECT 1 AS connected').first();
          return json({ ok: row?.connected === 1 }, row?.connected === 1 ? 200 : 503);
        } catch { return json({ ok: false }, 503); }
      }
      if (!env.FIREBASE_PROJECT_ID) return json({ error: 'Service unavailable' }, 503);
      const bearer = /^Bearer ([^\s]+)$/i.exec(request.headers.get('Authorization') || '');
      if (!bearer || bearer[1].length > 8192) return json({ error: 'Unauthorized' }, 401);
      let uid;
      try { uid = await verify(bearer[1], env.FIREBASE_PROJECT_ID); }
      catch { return json({ error: 'Unauthorized' }, 401); }
      if (video) return uploadVideo(request, env, uid);
      if (engagement) return interactions(request, env, { uid, token: bearer[1], project: env.FIREBASE_PROJECT_ID }, engagement);
      try {
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
