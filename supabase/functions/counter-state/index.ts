import {verifyFirebaseSession} from "../_shared/firebase-session.ts";
const json=(body:unknown,status=200)=>Response.json(body,{status,headers:{"Cache-Control":"no-store","Allow":"POST"}});
async function bodyJson(req:Request) {
  if(!req.body)throw Error("INVALID_BODY");
  const reader=req.body.getReader(), chunks:Uint8Array[]=[];let size=0;
  try {
    for(;;){const part=await reader.read();if(part.done)break;size+=part.value.byteLength;
      if(size>2048){await reader.cancel();throw Error("BODY_TOO_LARGE");}chunks.push(part.value);}
  }finally{reader.releaseLock();}
  const bytes=new Uint8Array(size);let offset=0;for(const part of chunks){bytes.set(part,offset);offset+=part.length;}
  return JSON.parse(new TextDecoder().decode(bytes));
}
// No event/apply/seed actions. A client may only read/ack its own registered device.
export async function handleRequest(req:Request,verifySession=verifyFirebaseSession):Promise<Response> {
  if(req.method!=="POST")return json({error:"POST_REQUIRED"},405);
  const token=/^Bearer ([^\s]+)$/i.exec(req.headers.get("Authorization")||"")?.[1];
  if(!token)return json({error:"AUTH_REQUIRED"},401);
  let actor:string;
  try{actor=await verifySession(token);}catch{return json({error:"INVALID_FIREBASE_SESSION"},401);}
  let body;
  try{body=await bodyJson(req);}catch(e){return json({error:e instanceof Error && e.message==="BODY_TOO_LARGE"?"BODY_TOO_LARGE":"INVALID_BODY"},e instanceof Error && e.message==="BODY_TOO_LARGE"?413:400);}
  if(!body || !["snapshot","ack"].includes(body.action) || typeof body.device_id!=="string" || !/^[\w-]{1,256}$/.test(body.device_id))return json({error:"INVALID_REQUEST"},400);
  if(body.action==="ack" && (!["notifications","requests","messages"].includes(body.kind)
      || typeof body.observed!=="string" || !/^(0|[1-9][0-9]{0,18})$/.test(body.observed)
      || BigInt(body.observed)>9223372036854775807n))return json({error:"INVALID_ACK"},400);
  const url=Deno.env.get("SUPABASE_URL"), key=Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if(!url || !key)return json({error:"COUNTERS_UNAVAILABLE"},503);
  try{
    const response=await fetch(`${url}/rest/v1/rpc/counter_v1_access`,{
      method:"POST",headers:{apikey:key,Authorization:`Bearer ${key}`,"Content-Type":"application/json"},
      body:JSON.stringify({p_uid:actor,p_device:body.device_id,p_action:body.action,
        p_kind:body.action==="ack"?body.kind:null,p_observed:body.action==="ack"?body.observed:null}),
      signal:AbortSignal.timeout(8000)
    });
    if(!response.ok)return json({error:"COUNTERS_UNAVAILABLE"},503);
    const result=await response.json();
    if(result?.error){const status=({DEVICE_NOT_REGISTERED:403,RATE_LIMITED:429,COUNTER_NOT_READY:409,INVALID_REQUEST:400} as Record<string,number>)[result.error];
      if(!status)return json({error:"COUNTERS_UNAVAILABLE"},503);
      return json({error:result.error,...(status===429?{retry_after_seconds:60}:{})},status);}
    if(!result || typeof result.ready!=="boolean" || !Array.isArray(result.counters))return json({error:"COUNTERS_UNAVAILABLE"},503);
    return json(result);
  }catch{return json({error:"COUNTERS_UNAVAILABLE"},503);}
}
if(import.meta.main)Deno.serve((req:Request)=>handleRequest(req));
