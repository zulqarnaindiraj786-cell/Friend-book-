// Read-only synthetic SQL compatibility check. No application requests or user data.
import {pathToFileURL} from 'node:url';
import {legacyCommentCountsStatement,legacyCommentCountsWithCandidatesStatement} from '../../cloudflare/comment-api.js';
const endpoint='https://api.cloudflare.com/client/v4/accounts/92f3d04598822de2e49e14561218d090/d1/database/e1c605ca-a50b-46c6-9deb-56dd1f70d163/query';
const builder={prepare(sql){return {bind(...params){return {sql,params};}};}};

export async function checkCommentEpoch(fetchFn,token,nonce){
 if(!token || !/^[a-zA-Z0-9_-]{16,80}$/.test(nonce))throw Error('Invalid smoke configuration');
 const resources=Array.from({length:20},(_,i)=>`posts/__quota_epoch_${nonce}_${i}`);
 async function query(statement){
  const response=await fetchFn(endpoint,{method:'POST',headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},body:JSON.stringify(statement)});
  if(!response.ok){
   let isCompoundLimit=false;
   try {const data=await response.json();isCompoundLimit=/too many terms in compound select/i.test(String(data?.errors?.[0]?.message||''));} catch {}
   if(isCompoundLimit)return {compoundLimit:true};
   throw Error('D1 read-only smoke request failed');
  }
  const payload=await response.json(),result=payload?.result?.[0];
  if(!payload?.success || payload.result.length!==1 || !result?.success || !Array.isArray(result.results))throw Error('D1 smoke returned incomplete results');
  return result;
 }
 const epoch=await query({sql:'SELECT count(*) AS n FROM friendbook_cf_legacy_comment_epoch WHERE id=1 AND length(generation)=32 AND revision>=0',params:[]});
 if(epoch.results?.length!==1 || epoch.results[0].n!==1)throw Error('Unexpected epoch result');
 const measurements=[];
 for(const size of [1,2,3,4,5,10,20])for(const kind of ['original','candidate']){
  const res=resources.slice(0,size),statement=kind==='original'?legacyCommentCountsStatement(builder,res,[]):legacyCommentCountsWithCandidatesStatement(builder,res,[]);
  const result=await query(statement);
  if(result.compoundLimit){measurements.push({size,kind,compound_limit:true});continue;}
  const rows=[...result.results].sort((a,b)=>a.slot-b.slot);
  if(rows.length!==size || rows.some((row,i)=>row.slot!==i || row.n!==0 || (kind==='candidate' && row.has_candidates!==0)))throw Error('Synthetic count parity failed');
  measurements.push({size,kind,rows_read:result.meta?.rows_read??null});
 }
 if(measurements.some(x=>x.size===1 && x.compound_limit))throw Error('Single resource SQL unsupported');
 return {epoch_rows_read:epoch.meta?.rows_read??null,measurements};
}
if(process.argv[1] && import.meta.url===pathToFileURL(process.argv[1]).href){
 try {console.log('::notice:: Synthetic comment-epoch SQL smoke '+JSON.stringify(await checkCommentEpoch(fetch,process.env.CLOUDFLARE_API_TOKEN,crypto.randomUUID().replaceAll('-',''))));}
 catch {console.error('::error::Synthetic read-only comment SQL smoke failed; no production writes were made.');process.exitCode=1;}
}
