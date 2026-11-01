import { createRemoteJWKSet, jwtVerify, importPKCS8, SignJWT } from "npm:jose@5.9.6";

const PROJECT = "friendbook-17551";
const keys = createRemoteJWKSet(new URL("https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"));
const json = (body: unknown, status = 200) => Response.json(body, { status });
const validId = (v: unknown): v is string => typeof v === "string" && /^[\w-]{1,256}$/.test(v);
function decode(v: any): any {
  if (!v) return null;
  if ("stringValue" in v) return v.stringValue;
  if ("booleanValue" in v) return v.booleanValue;
  if ("timestampValue" in v) return v.timestampValue;
  if ("arrayValue" in v) return (v.arrayValue.values || []).map(decode);
  if ("mapValue" in v) return Object.fromEntries(Object.entries(v.mapValue.fields || {}).map(([k, x]) => [k, decode(x)]));
  return null;
}
let cachedToken = "", tokenUntil = 0;
async function googleToken() {
  if (cachedToken && Date.now() < tokenUntil) return cachedToken;
  const raw = Deno.env.get("FCM_SERVICE_ACCOUNT_JSON");
  if (!raw) throw new Error("FCM_NOT_CONFIGURED");
  const sa = JSON.parse(raw);
  if (sa.project_id !== PROJECT || !sa.private_key || !sa.client_email) throw new Error("FCM_PROJECT_MISMATCH");
  const key = await importPKCS8(sa.private_key, "RS256");
  const assertion = await new SignJWT({ scope: "https://www.googleapis.com/auth/firebase.messaging" })
    .setProtectedHeader({ alg: "RS256", kid: sa.private_key_id }).setIssuer(sa.client_email)
    .setAudience("https://oauth2.googleapis.com/token").setIssuedAt().setExpirationTime("1h").sign(key);
  const r = await fetch("https://oauth2.googleapis.com/token", { method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
    signal: AbortSignal.timeout(10000) });
  const result = await r.json();
  if (!r.ok || !result.access_token) throw new Error("FCM_OAUTH_FAILED");
  cachedToken = result.access_token;
  tokenUntil = Date.now() + (Number(result.expires_in || 3600) - 120) * 1000;
  return cachedToken;
}
async function verifyFirebaseSession(token: string): Promise<string> {
  const { payload } = await jwtVerify(token, keys, { issuer: `https://securetoken.google.com/${PROJECT}`, audience: PROJECT, algorithms: ["RS256"] });
  if (!validId(payload.sub)) throw new Error("Invalid subject");
  return payload.sub;
}
// Dependency injection is ONLY for local tests; HTTP callers cannot select or bypass the verifier.
export async function handleRequest(req: Request, verifySession = verifyFirebaseSession): Promise<Response> {
  if (req.method !== "POST") return json({ error: "POST required", provider: "fcm", project: PROJECT }, 405);
  const url = Deno.env.get("SUPABASE_URL"), serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !serviceKey) return json({ error: "SERVER_NOT_CONFIGURED" }, 503);
  const token = req.headers.get("Authorization")?.replace(/^Bearer /i, "");
  if (!token) return json({ error: "AUTH_REQUIRED" }, 401);
  let actor: string;
  try {
    actor = await verifySession(token);
  } catch { return json({ error: "INVALID_FIREBASE_SESSION" }, 401); }
  const headers = { apikey: serviceKey, Authorization: `Bearer ${serviceKey}`, "Content-Type": "application/json" };
  const rest = async (path: string, method = "GET", body?: unknown) => {
    const r = await fetch(`${url}/rest/v1/${path}`, { method, headers: { ...headers, Prefer: "resolution=merge-duplicates,return=representation" },
      body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(10000) });
    if (!r.ok) throw new Error("TOKEN_STORE_UNAVAILABLE");
    const raw = await r.text(); return raw ? JSON.parse(raw) : null;
  };
  try {
    const raw = await req.text();
    if (raw.length > 12000) return json({ error: "REQUEST_TOO_LARGE" }, 413);
    const body = JSON.parse(raw), input = body.data;
    const action = body.action || "send";
    if (["register", "unregister"].includes(action)) {
      if (!validId(body.device_id)) return json({ error: "INVALID_DEVICE" }, 400);
      if (action === "unregister") {
        await rest(`fcm_device_tokens?device_id=eq.${body.device_id}&user_id=eq.${actor}`, "DELETE");
        return json({ unregistered: true });
      }
      if (typeof body.token !== "string" || body.token.length < 20 || body.token.length > 4096) return json({ error: "INVALID_TOKEN" }, 400);
      // A device belongs to one current account. Upsert atomically reassigns it on account switch.
      await rest("fcm_device_tokens?on_conflict=device_id", "POST", {
        device_id: body.device_id, user_id: actor, token: body.token, updated_at: new Date().toISOString()
      });
      return json({ registered: true, provider: "fcm" });
    }
    if (action === "status") {
      const devices = await rest(`fcm_device_tokens?user_id=eq.${actor}&select=device_id,updated_at`);
      return json({ provider: "fcm", project: PROJECT, registered_devices: devices.length,
        server_configured: !!Deno.env.get("FCM_SERVICE_ACCOUNT_JSON") });
    }
    const target = action === "self_test" ? actor : body.target_user_id;
    if (!validId(target) || (action !== "self_test" && (!input || typeof input !== "object" || target === actor))) return json({ error: "INVALID_TARGET" }, 400);
    if (!["send", "self_test"].includes(action)) return json({ error: "INVALID_ACTION" }, 400);
    let data: Record<string, string>, message: string, event: string, title: string;
    if (action === "self_test") {
      // Unique per minute, self-only: cannot be used to send arbitrary text or target another user.
      event = `selftest_${actor}_${Math.floor(Date.now() / 60000)}`;
      title = "friendbook push test"; message = "Direct FCM reached your phone. Notification permission and delivery are working.";
      data = { type: "push_test", toUserId: actor, fromUserId: actor, eventId: event, title, body: message };
    } else {
    const doc = async (path: string) => {
      const r = await fetch(`https://firestore.googleapis.com/v1/projects/${PROJECT}/databases/(default)/documents/${path}`, {
        headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(10000),
      });
      if (!r.ok) throw new Error("Event unavailable");
      const d = await r.json();
      return Object.fromEntries(Object.entries(d.fields || {}).map(([k, v]) => [k, decode(v)]));
    };
    const [sender, recipient] = await Promise.all([doc(`users/${actor}`), doc(`users/${target}`)]);
    if (sender.banned || recipient.banned) return json({ error: "Unavailable account" }, 403);
    if ([...(recipient.blockedUsers || []), ...(recipient.blocked || [])].includes(actor)) return json({ skipped: "blocked" });
    // Never trust client title/message/routing. Derive them from a persisted event and verified actor.
    if (input.type === "message" && validId(input.chatId) && validId(input.messageId)) {
      const [chat, m] = await Promise.all([doc(`chats/${input.chatId}`), doc(`chats/${input.chatId}/messages/${input.messageId}`)]);
      if (m.senderId !== actor || !chat.participants?.includes(actor) || !chat.participants?.includes(target) || m.isDeleted) return json({ error: "Forbidden event" }, 403);
      if (!Number.isFinite(Date.parse(m.createdAt)) || Date.now() - Date.parse(m.createdAt) > 86400000) return json({ error: "Expired event" }, 400);
      event = `message_${input.chatId}_${input.messageId}_${target}`;
      message = String(m.text || "Sent an attachment").slice(0, 500);
      data = { type: "message", chatId: input.chatId, isGroup: String(chat.isGroup === true) };
    } else if (validId(input.notificationId)) {
      const n = await doc(`notifications/${input.notificationId}`);
      if (n.fromUserId !== actor || n.toUserId !== target) return json({ error: "Forbidden event" }, 403);
      if (!Number.isFinite(Date.parse(n.createdAt)) || Date.now() - Date.parse(n.createdAt) > 86400000) return json({ error: "Expired event" }, 400);
      event = `notification_${input.notificationId}`;
      message = String(n.text || "New notification").slice(0, 500);
      data = { type: n.type, postId: n.postId || "", contentType: n.contentType || "", notificationId: input.notificationId, groupId: n.groupId || "" };
    } else return json({ error: "Persisted event required" }, 400);
    title = String(sender.displayName || sender.name || "friendbook").slice(0, 100);
    Object.assign(data, { toUserId: target, fromUserId: actor, eventId: event, title, body: message });
    }
    const devices = await rest(`fcm_device_tokens?user_id=eq.${target}&select=device_id,token`);
    if (!devices.length) return json({ error: "RECIPIENT_NOT_REGISTERED", accepted: 0 }, 409);
    // Obtain credentials BEFORE claiming so a missing/rotated key doesn't permanently consume events.
    const access = await googleToken();
    let accepted = 0, duplicate = 0, invalid = 0, failed = 0;
    for (const device of devices) {
      const claimKey = `fcm:${event}:${device.device_id}`;
      const claim = await rest("rpc/claim_push", "POST", { p_event: claimKey, p_actor: actor });
      if (!claim) {
        const previous = await rest(`push_delivery_claims?event_key=eq.${encodeURIComponent(claimKey)}&select=event_key`);
        if (previous.length) { duplicate++; continue; }
        return json({ error: "RATE_LIMITED", accepted }, 429);
      }
      try {
        const r = await fetch(`https://fcm.googleapis.com/v1/projects/${PROJECT}/messages:send`, {
          method: "POST", headers: { Authorization: `Bearer ${access}`, "Content-Type": "application/json" },
          // Data-only avoids Android auto-display bypassing account/prefs/PIN and event dedup logic.
          body: JSON.stringify({ message: { token: device.token, data,
            android: { priority: "HIGH", ttl: "86400s" } } }), signal: AbortSignal.timeout(15000)
        });
        const result = await r.json();
        if (r.ok && result.name) { accepted++; continue; }
        const code = result.error?.details?.find((x: any) => x.errorCode)?.errorCode;
        if (code === "UNREGISTERED") {
          // Exact token predicate prevents deleting a concurrently rotated registration.
          await rest(`fcm_device_tokens?device_id=eq.${device.device_id}&token=eq.${encodeURIComponent(device.token)}`, "DELETE");
          invalid++;
        } else {
          if (r.status === 401) { cachedToken = ""; tokenUntil = 0; }
          console.error("FCM rejected", r.status, code || result.error?.status);
          failed++;
          await rest(`push_delivery_claims?event_key=eq.${encodeURIComponent(claimKey)}`, "DELETE");
        }
      } catch {
        failed++;
        // Ambiguous network failure can retry. Client-side eventId dedup suppresses repeats.
        await rest(`push_delivery_claims?event_key=eq.${encodeURIComponent(claimKey)}`, "DELETE");
      }
    }
    if (failed) return json({ error: "FCM_DELIVERY_FAILED", accepted, failed, invalid }, 503);
    if (!accepted && !duplicate) return json({ error: "NO_VALID_RECIPIENT_DEVICE", accepted, invalid }, 409);
    return json({ provider: "fcm", accepted, duplicate, invalid, note: "FCM acceptance is not a device receipt" });
  } catch (error) {
    const code = error instanceof Error ? error.message : "INVALID_REQUEST";
    const known = ["FCM_NOT_CONFIGURED", "FCM_PROJECT_MISMATCH", "FCM_OAUTH_FAILED", "TOKEN_STORE_UNAVAILABLE"];
    return json({ error: known.includes(code) ? code : "INVALID_OR_UNAVAILABLE_EVENT" }, known.includes(code) ? 503 : 400);
  }
}
export const handler = (req: Request) => handleRequest(req);
if (import.meta.main) Deno.serve(handler);
