import { createRemoteJWKSet, jwtVerify } from "npm:jose@5.9.6";
import { getD1Document, queryD1Documents, putD1Document } from "../_shared/d1-documents.ts";

const PROJECT = "friendbook-17551";
const keys = createRemoteJWKSet(new URL("https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"));
const json = (body: unknown, status = 200) => Response.json(body, { status });
const validId = (v: unknown): v is string => typeof v === "string" && /^[\w-]{1,256}$/.test(v);
// Audience is derived exclusively from persisted identities/relationships, never client recipient lists.
async function bulkMention(body: any, actor: string, token: string, rest: any,
    send: (target: string, id: string) => Promise<Response>): Promise<Response> {
  if (!validId(body.post_id) || (body.comment_id && !validId(body.comment_id))) return json({error:"INVALID_SOURCE"},400);
  const get=(path:string) => getD1Document(path,token);
  const [post,sender]=await Promise.all([get(`posts/${body.post_id}`),get(`users/${actor}`)]);
  if(!post || !sender || sender.banned || String(post.privacy).toLowerCase()!=="public" || post.groupId || post.pageId || post.isDeleted || post.safetyFlagged)
    return json({error:"BULK_PUBLIC_POST_REQUIRED"},403);
  let source;
  if(body.comment_id && body.comment_backend==="d1") {
    const r=await fetch(`https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev/v1/engagement/posts/${encodeURIComponent(body.post_id)}/comments?id=${encodeURIComponent(body.comment_id)}`,
      {headers,signal:AbortSignal.timeout(10000)});
    if(!r.ok) return json({error:"D1_SOURCE_UNAVAILABLE"},503);
    source=(await r.json()).comment;
  } else source=body.comment_id ? (await get(`posts/${body.post_id}/comments/${body.comment_id}`) || (post.commentsMap||{})[body.comment_id]) : post;
  if(!source || source.authorId!==actor) return json({error:"BULK_SOURCE_FORBIDDEN"},403);
  const text=String(source.content||"");
  const tags=new Set((Array.isArray(source.mentions)?source.mentions:[]).filter((m:any)=>
    ["bulk:followers","bulk:highlight"].includes(m.uid) && Number.isInteger(m.start) && Number.isInteger(m.end) && m.start>=0 && m.end>m.start && m.end<=text.length &&
    m.label===`@${m.uid.slice(5)}` && text.slice(m.start,m.end)===m.label).map((m:any)=>m.uid));
  if(!tags.size) return json({error:"NO_PERSISTED_BULK_MENTION"},400);
  if(tags.has("bulk:followers") && post.authorId!==actor) return json({error:"FOLLOWERS_REQUIRES_POST_OWNER"},403);
  const created=typeof source.createdAt==="number"?source.createdAt:Date.parse(source.createdAt);
  if(!Number.isFinite(created) || Date.now()-created>86400000 || created>Date.now()+30000) return json({error:"BULK_SOURCE_EXPIRED"},400);
  const candidates=new Set<string>(), highlightIds=new Set<string>();
  if(tags.has("bulk:followers")) for(const uid of Array.isArray(sender.followers)?sender.followers:[]) if(validId(uid) && candidates.size<40) candidates.add(uid);
  if(tags.has("bulk:highlight")) {
    const rows=await queryD1Documents('friends',[{field:'participants',op:'array-contains',value:actor}],40,token);
    for(const item of rows) for(const uid of item.data?.participants||[]) if(validId(uid) && candidates.size<80) { candidates.add(uid); highlightIds.add(uid); }
  }
  candidates.delete(actor);
  const blocked=(u:any,id:string) => [...(Array.isArray(u.blockedUsers)?u.blockedUsers:[]),...(Array.isArray(u.blocked)?u.blocked:[])].includes(id);
  const eligible:string[]=[];
  for(const uid of candidates) {
    if(eligible.length>=20) break;
    if(blocked(sender,uid)) continue;
    const recipient=await get(`users/${uid}`);
    if(!recipient || recipient.banned || blocked(recipient,actor) || recipient.bulkMentionsEnabled===false) continue;
    // A forged follower array alone is insufficient; require the recipient's reciprocal following edge.
    const follower=tags.has("bulk:followers") && Array.isArray(recipient.following) && recipient.following.includes(actor);
    let friend=false;
    if(tags.has("bulk:highlight")) {
      // Query above is server-side persisted membership; re-check recipient's presence in that result below.
      friend=highlightIds.has(uid);
    }
    if(follower || friend) eligible.push(uid);
  }
  if(!eligible.length) return json({inbox:0,accepted:0,note:"No eligible recipients"});
  const event=`${body.post_id}:${body.comment_id||"post"}`;
  const reservation=await rest("rpc/reserve_bulk_mention","POST",{p_actor:actor,p_event:event,p_audience:eligible});
  if(!reservation || reservation.limited) return json({error:"BULK_DAILY_LIMIT",cooldown_hours:24},429);
  let inbox=0,accepted=0,failed=0;
  const audience=Array.isArray(reservation.audience)?reservation.audience.slice(0,20):[];
  // Five concurrent bounded recipients; deterministic notification IDs + gateway device claims deduplicate retries.
  for(let offset=0;offset<audience.length;offset+=5) await Promise.all(audience.slice(offset,offset+5).map(async(uid:string)=>{
    if(!eligible.includes(uid)) return; // Current blocks/relationship changes override an earlier reservation.
    const digest=await crypto.subtle.digest("SHA-256",new TextEncoder().encode(`${actor}:${event}:${uid}`));
    const id="bulk_"+Array.from(new Uint8Array(digest),x=>x.toString(16).padStart(2,"0")).join("");
    const values:any={toUserId:uid,fromUserId:actor,fromUserName:String(sender.displayName||"friendbook User"),fromUserPhotoUrl:String(sender.photoUrl||""),type:"mention",text:"mentioned their audience in a post",postId:body.post_id,isRead:false,createdAt:new Date().toISOString()};
    try {
      await putD1Document(`notifications/${id}`,values,token,true);
    } catch {
      const existing=await get(`notifications/${id}`);
      if(!existing || existing.fromUserId!==actor || existing.toUserId!==uid || existing.postId!==body.post_id || existing.type!=="mention") { failed++; return; }
    }
    inbox++;
    const delivered=await send(uid,id); const result=await delivered.json();
    accepted+=Number(result.accepted||0);
    if(delivered.status>=500 || delivered.status===429) failed++;
  }));
  return json({inbox,accepted,failed,note:"Some eligible people may receive notifications; FCM acceptance is not delivery"},failed?503:200);
}

export async function handleRequest(req: Request, verifySession = async(token: string): Promise<string> => {
  const {payload}=await jwtVerify(token,keys,{issuer:`https://securetoken.google.com/${PROJECT}`,audience:PROJECT,algorithms:["RS256"]});
  if(!validId(payload.sub)) throw Error("Invalid subject"); return payload.sub;
}): Promise<Response> {
  if(req.method!=="POST") return json({error:"POST_REQUIRED"},405);
  const token=req.headers.get("Authorization")?.replace(/^Bearer /i,"");
  if(!token) return json({error:"AUTH_REQUIRED"},401);
  let actor:string; try { actor=await verifySession(token); } catch { return json({error:"INVALID_FIREBASE_SESSION"},401); }
  try {
    const raw=await req.text(); if(raw.length>2000) return json({error:"REQUEST_TOO_LARGE"},413);
    const body=JSON.parse(raw); if(body.action!=="bulk_mention") return json({error:"INVALID_ACTION"},400);
    const url=Deno.env.get("SUPABASE_URL"),key=Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    if(!url||!key) return json({error:"BULK_NOT_CONFIGURED"},503);
    const rest=async(path:string,method:string,data:any)=>{
      const r=await fetch(`${url}/rest/v1/${path}`,{method,headers:{apikey:key,Authorization:`Bearer ${key}`,"Content-Type":"application/json"},body:JSON.stringify(data),signal:AbortSignal.timeout(10000)});
      if(!r.ok) throw Error("BULK_STORE_UNAVAILABLE"); return await r.json();
    };
    return await bulkMention(body,actor,token,rest,(target,id)=>fetch(`${url}/functions/v1/send-fcm-notification`,{method:"POST",headers:{Authorization:`Bearer ${token}`,"Content-Type":"application/json"},body:JSON.stringify({action:"send",target_user_id:target,data:{notificationId:id,type:"mention"}}),signal:AbortSignal.timeout(20000)}));
  } catch { return json({error:"BULK_UNAVAILABLE"},503); }
}
if(import.meta.main) Deno.serve(handleRequest);
