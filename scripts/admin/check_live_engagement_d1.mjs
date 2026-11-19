// Read-only production D1 integration check. No Firebase calls, user IDs or post IDs are printed.
import { interactions } from '../../cloudflare/interactions.js';
const base='https://api.cloudflare.com/client/v4/accounts/92f3d04598822de2e49e14561218d090/d1/database/e1c605ca-a50b-46c6-9deb-56dd1f70d163/query';
if(!process.env.CLOUDFLARE_API_TOKEN) throw Error('Missing Cloudflare API token');
async function execute(sql,params=[]) {
  if(!/^\s*SELECT\b/i.test(sql))throw Error('Only SELECT statements are allowed');
  const response=await fetch(base,{method:'POST',headers:{Authorization:`Bearer ${process.env.CLOUDFLARE_API_TOKEN}`,'Content-Type':'application/json'},body:JSON.stringify({sql,params})});
  const data=await response.json();
  if(!response.ok || !data.success)throw Error(`D1 query failed (HTTP ${response.status}): ${(data.errors||[]).map(x=>x.message).join(', ')}`);
  return data.result?.[0]?.results || [];
}
const sample=(await execute("SELECT resource FROM friendbook_cf_interaction_resources WHERE state='verified' AND (resource LIKE 'posts/%' OR resource LIKE 'reels/%') LIMIT 1"))[0]?.resource;
if(!sample){console.log('::notice::No verified engagement resource available for a read-only check');process.exit(0);}
const slash=sample.indexOf('/'), collection=sample.slice(0,slash), id=sample.slice(slash+1);
const db={withSession(){return this;},prepare(sql){return {bind(...args){return {first:async()=>(await execute(sql,args))[0]||null,all:async()=>({results:await execute(sql,args)}),run:async()=>{throw Error('Diagnostic is read-only');}};}};}};
const response=await interactions(new Request(`https://test.invalid/v1/engagement/${collection}/${encodeURIComponent(id)}/reactions`),
 {DB:db,D1_INTERACTIONS_ENABLED:'true'},{uid:'diagnostic-no-account'},[null,collection,encodeURIComponent(id),'reactions'],async(_identity,_collection,_id,_unused,context)=>{context.blocked=[];return true;});
if(response.status!==200)throw Error(`Real D1 engagement summary failed with HTTP ${response.status}`);
const data=await response.json();
if(!Array.isArray(data.types) || !Array.isArray(data.preview) || typeof data.comments!=='number')throw Error('Invalid engagement summary');
console.log('::notice title=D1 engagement diagnostic::Read-only summary query succeeded against a verified resource; no authenticated app flow was tested');
