import { CallRelay } from './cloudflare/call-relay.js';
export { CallRelay };
import { openLiveRelay, LiveRelay } from './cloudflare/live-relay.js';
export { LiveRelay };
import { runMaintenance } from './cloudflare/maintenance.js';
import { viewCounters } from './cloudflare/view-counters.js';
import { contentReadAllowed } from './cloudflare/document-policy.js';
import { uploadVideo, uploadPhoto, uploadPhoneVideo } from './cloudflare/video-r2.js';
import { presenceRealtime } from './cloudflare/presence-realtime.js';
import { androidRelease } from './cloudflare/android-release.js';
import { interactions } from './cloudflare/interactions.js';
import { appDocuments, requestAccountReads, saveProfileProjection } from './cloudflare/app-documents.js';
import { drainBroadcastPush } from './cloudflare/fcm-push.js';
import { firestoreDocuments, resumeOfficialBroadcasts } from './cloudflare/firestore-documents.js';
import { fcmGateway, pushIncomingCall } from './cloudflare/fcm-push.js';
import { createRemoteJWKSet, jwtVerify } from 'jose';
import { accountStatus } from './cloudflare/account-access.js';
import { isPaymentAdmin } from './cloudflare/payment-access.js';

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
    async fetch(request, env, ctx) {
      const path = new URL(request.url).pathname;
      // API namespaces must never fall through to HTML, even for unknown endpoints.
      const api = path === '/health' || path.startsWith('/health/') || path === '/v1' || path.startsWith('/v1/')
        || path === '/functions/v1/send-fcm-notification' || path === '/functions/v1/bulk-mentions';
      if (!api) {
        if (!['GET', 'HEAD'].includes(request.method)) return json({ error: 'Method not allowed' }, 405);
        if (!env.ASSETS) return json({ error: 'Frontend unavailable' }, 503);
        const assetURL = new URL(request.url);
        if (['/', '/login', '/signup', '/login/', '/signup/'].includes(path)) assetURL.pathname = '/index.html';
        try { return await env.ASSETS.fetch(new Request(assetURL, request)); }
        catch { return json({ error: 'Frontend unavailable' }, 503); }
      }
      if (path === '/v1/android-config') {
        if (request.method !== 'GET') return json({error:'Method not allowed'},405);
        // Only explicit public booleans are exposed; never serialize env or D1 rows.
        if (!['true','false'].includes(env.ADS_ENABLED_V3)) return json({error:'App config unavailable'},503);
        return json({ads_enabled_v3:env.ADS_ENABLED_V3 === 'true'});
      }
      if (path === '/v1/android-release') return request.method === 'GET' ? json(androidRelease) : json({ error: 'Method not allowed' }, 405);
      const live = /^\/v1\/live\/([A-Za-z0-9_-]{1,128})\/(publish|watch|cohost|pkguest)$/.exec(path);
      const callMedia = /^\/v1\/chat\/call\/([A-Za-z0-9_-]{1,200})\/([A-Za-z0-9_-]{1,80})$/.exec(path);
      const phoneVideo = /^\/v1\/videos\/(preview|full)$/.exec(path);
      const video = path === '/v1/videos' || Boolean(phoneVideo);
      const photo = path === '/v1/photos';
      const firestore = path === '/v1/chat/call' || path === '/v1/firestore/claim-referral' || path === '/v1/live/pk' || path === '/v1/firestore/document' || path === '/v1/firestore/query' || path === '/v1/firestore/profile-bootstrap'
        || path === '/v1/firestore/batch' || path === '/v1/firestore/transaction' || path === '/v1/firestore/count'
        || path === '/v1/admin/recover-social' || path === '/v1/admin/moderation' || path === '/v1/admin/broadcast' || path === '/v1/firestore/story-engagement';
      const fcm = path === '/functions/v1/send-fcm-notification' || path === '/functions/v1/bulk-mentions';
      const docs = path === '/v1/docs' || /^\/v1\/docs\/[^/]+\/[^/]+$/.test(path);
      const presence = path === '/v1/presence/socket' || path === '/v1/presence/me'
        || /^\/v1\/presence\/(contact|typing)\/[^/]+$/.test(path);
      const engagement = /^\/v1\/engagement\/(posts|reels)\/([^/]+)\/(reactions|comments|reactors)$/.exec(path);
      // Real view counters live in Cloudflare D1 (Firebase is auth only, never view data).
      const viewsMatch = /^\/v1\/views\/(posts|reels)\/([^/]+)$/.exec(path);
      const views = path === '/v1/views' || Boolean(viewsMatch);
      if (path !== '/health' && path !== '/health/r2' && path !== '/v1/me' && !engagement && !video && !photo && !presence && !docs && !views && !firestore && !fcm && !live && !callMedia) return json({ error: 'Not found' }, 404);
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
          return json({
            ok: row?.connected === 1,
            d1InteractionsEnabled: env.D1_INTERACTIONS_ENABLED === 'true',
            views,
          }, row?.connected === 1 ? 200 : 503);
        } catch { return json({ ok: false, d1InteractionsEnabled: env.D1_INTERACTIONS_ENABLED === 'true', views }, 503); }
      }
      if (!env.FIREBASE_PROJECT_ID) return json({ error: 'Service unavailable' }, 503);
      const bearer = /^Bearer ([^\s]+)$/i.exec(request.headers.get('Authorization') || '');
      if (!bearer || bearer[1].length > 8192) return json({ error: 'Unauthorized' }, 401);
      let uid;
      try { uid = await verify(bearer[1], env.FIREBASE_PROJECT_ID); }
      catch { return json({ error: 'Unauthorized' }, 401); }
      // Keep the own-profile read available so the Android app can detect a ban
      // and sign out, but reject all other operations for disabled accounts.
      const ownProfileRead=path==='/v1/firestore/document' && request.method==='GET'
        && new URL(request.url).searchParams.get('path')===`users/${uid}`;
      let suspended=false, viewerProfile;
      if (!ownProfileRead && !isPaymentAdmin(env,uid)) {
        try {
          const state=await accountStatus(env.DB,uid);
          if (state.disabled) return json({error:'Account disabled'},403);
          suspended=state.suspended;
          viewerProfile=state.profile;
        } catch { return json({error:'Account status unavailable'},503); }
      }
      if (suspended && ((engagement && engagement[3]==='comments' && request.method!=='GET')
          || ((video || photo) && ['POST','PUT'].includes(request.method)) || (live && live[2]!=='watch'))) return json({error:'Account suspended'},403);
      if (callMedia) {
        if (suspended) return json({error:'Account suspended'},403);
        const [,chat,call]=callMedia;
        const relay=env.CALL_RELAY.get(env.CALL_RELAY.idFromName(`${chat}/${call}`));
        return relay.fetch(new Request(`https://call.internal/?${new URLSearchParams({chat,call,uid})}`, {headers:request.headers}));
      }
      if (live) return openLiveRelay(request,env,uid,live[1],live[2]);
      if (phoneVideo) return uploadPhoneVideo(request, env, uid, phoneVideo[1]);
      if (video) return uploadVideo(request, env, uid);
      if (photo) return uploadPhoto(request, env, uid);
      if (firestore) {
        const response=await firestoreDocuments(request, env, { uid, suspended, viewerProfile, token: bearer[1] });
        if(path==='/v1/chat/call' && response.ok && ctx?.waitUntil){
          const call=await response.clone().json();
          if(call.status==='ringing') ctx.waitUntil(pushIncomingCall(env,call).catch(()=>{}));
        }
        return response;
      }
      if (fcm) return fcmGateway(request, env, { uid, token: bearer[1], viewerProfile });
      if (docs) return appDocuments(request, env, { uid, suspended, token: bearer[1], viewerProfile });
      if (views) {
        const validId=id=>typeof id==='string' && id.length>0 && id.length<=128 && !/[\\/,\x00-\x1f]/.test(id) && id!=='.' && id!=='..';
        const url=new URL(request.url);
        try {
          if (viewsMatch) {
            let id; try { id=decodeURIComponent(viewsMatch[2]); } catch { return json({error:'Invalid resource'},400); }
            if (!validId(id)) return json({error:'Invalid resource'},400);
            if (!(await contentReadAllowed(env.DB,uid,`${viewsMatch[1]}/${id}`,requestAccountReads({uid,viewerProfile})))) return json({error:'Forbidden'},403);
            return viewCounters(request,env,{uid},viewsMatch,url,Date.now,ctx);
          }
          if (request.method!=='GET') return json({error:'Method not allowed'},405);
          const collection=url.searchParams.get('collection'), raw=url.searchParams.get('ids');
          // Top lists disclose resource IDs without authorization; only explicit IDs are supported.
          if (!['posts','reels'].includes(collection) || raw===null) return json({error:'Invalid request'},400);
          const ids=[...new Set(raw.split(',').map(x=>x.trim()))];
          if (ids.length>50 || ids.some(x=>!validId(x))) return json({error:'Invalid request'},400);
          const accountReads=requestAccountReads({uid,viewerProfile});
          const allowed=await Promise.all(ids.map(id=>contentReadAllowed(env.DB,uid,`${collection}/${id}`,accountReads)));
          const safe=ids.filter((_,i)=>allowed[i]);
          if (!safe.length) return json({collection,views:{}});
          url.searchParams.set('ids',safe.join(','));
          return viewCounters(request,env,{uid},null,url,Date.now,ctx);
        } catch { return json({error:'View access unavailable'},503); }
      }
      if (presence) return presenceRealtime(request, env, { uid, token: bearer[1], viewerProfile });
      if (engagement) return interactions(request, env, { uid, token: bearer[1], project: env.FIREBASE_PROJECT_ID, db: env.DB, viewerProfile }, engagement);
      try {
        if (request.method === 'PUT') {
          let body = {};
          try { body = await request.json(); } catch { return json({ error: 'Invalid profile payload' }, 400); }
          const displayName = typeof body?.displayName === 'string' ? body.displayName.slice(0, 200) : '';
          const avatarUrl = typeof body?.photoUrl === 'string' ? body.photoUrl.slice(0, 2048) : null;
          await saveProfileProjection(env.DB,uid,displayName,avatarUrl);
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
export default {...app, scheduled(controller, env, ctx) {
  if (controller.cron === '* * * * *') {
    ctx.waitUntil(resumeOfficialBroadcasts(env));
    ctx.waitUntil(drainBroadcastPush(env));
  } else ctx.waitUntil(runMaintenance(env));
}};
