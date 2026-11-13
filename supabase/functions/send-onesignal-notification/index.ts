import { createRemoteJWKSet, jwtVerify } from "npm:jose@5.9.6";

const APP_ID = "6921b7fa-7c0a-40e3-8dd9-e698429a038a";
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
Deno.serve(async req => {
  if (req.method !== "POST") return json({ error: "POST required" }, 405);
  const secret = Deno.env.get("ONESIGNAL_REST_API_KEY");
  const url = Deno.env.get("SUPABASE_URL");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!secret || !url || !serviceKey) return json({ error: "Push service not configured" }, 503);
  const token = req.headers.get("Authorization")?.replace(/^Bearer /i, "");
  if (!token) return json({ error: "Authentication required" }, 401);
  let actor: string;
  try {
    const { payload } = await jwtVerify(token, keys, { issuer: `https://securetoken.google.com/${PROJECT}`, audience: PROJECT, algorithms: ["RS256"] });
    if (!validId(payload.sub)) throw new Error("Invalid subject");
    actor = payload.sub;
  } catch { return json({ error: "Invalid Firebase session" }, 401); }
  try {
    if (Number(req.headers.get("content-length") || 0) > 12000) return json({ error: "Request too large" }, 413);
    const raw = await req.text();
    if (raw.length > 12000) return json({ error: "Request too large" }, 413);
    const { target_user_id: target, data: input } = JSON.parse(raw);
    if (!validId(target) || !input || typeof input !== "object" || target === actor) return json({ error: "Invalid target" }, 400);
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
    let data: Record<string, string>, message: string, event: string;
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
    const title = String(sender.displayName || sender.name || "friendbook").slice(0, 100);
    Object.assign(data, { toUserId: target, fromUserId: actor, eventId: event, title });
    const headers = { apikey: serviceKey, Authorization: `Bearer ${serviceKey}`, "Content-Type": "application/json" };
    const claim = await fetch(`${url}/rest/v1/rpc/claim_push`, {
      method: "POST", headers, body: JSON.stringify({ p_event: event, p_actor: actor }), signal: AbortSignal.timeout(10000),
    });
    if (!claim.ok) return json({ error: "Delivery guard unavailable" }, 503);
    if (!(await claim.json())) return json({ skipped: "duplicate_or_rate_limited" });
    const response = await fetch("https://api.onesignal.com/notifications", {
      method: "POST", headers: { Authorization: `Key ${secret}`, "Content-Type": "application/json" },
      body: JSON.stringify({ app_id: APP_ID, include_aliases: { external_id: [target] }, target_channel: "push", headings: { en: title },
        contents: { en: message }, data, small_icon: "ic_stat_friendbook", existing_android_channel_id: "friendbook_social_v1" }),
      signal: AbortSignal.timeout(15000),
    });
    const result = await response.json();
    if (!response.ok || result.errors || !result.id) {
      console.error("OneSignal delivery rejected", response.status); // Never log keys, JWTs or message bodies.
      return json({ error: "Push not accepted; check provider configuration/subscriptions" }, 502);
    }
    return json({ accepted: true, id: result.id }); // Accepted is NOT proof of device delivery.
  } catch { return json({ error: "Invalid or unavailable event" }, 400); }
});
