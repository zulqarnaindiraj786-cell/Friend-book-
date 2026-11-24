// Read-only synthetic SQL compatibility check. No application requests or user data.
import {pathToFileURL} from 'node:url';
import {legacyCommentCountsStatement,legacyCommentCountsWithCandidatesStatement} from '../../cloudflare/comment-api.js';
const endpoint='https://api.cloudflare.com/client/v4/accounts/92f3d04598822de2e49e14561218d090/d1/database/e1c605ca-a50b-46c6-9deb-56dd1f70d163/query';
const builder={prepare(sql){return {bind(...params){return {sql,params};}};}};

export async function checkCommentEpoch(fetchFn,token,nonce){
 if(!token || !/^[a-zA-Z0-9_-]{16,80}$/.test(nonce))throw Error('Invalid smoke configuration');
 const resources=Array.from({length:10},(_,i)=>`posts/__quota_epoch_${nonce}_${i}`);
 async function query(statement){
  const response=await fetchFn(endpoint,{method:'POST',headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},body:JSON.stringify(statement)});
  if(!response.ok)throw Error('D1 read-only smoke request failed');
  const payload=await response.json(),result=payload?.result?.[0];
  if(!payload?.success || payload.result.length!==1 || !result?.success || !Array.isArray(result.results))throw Error('D1 smoke returned incomplete results');
  return result;
 }
 const epoch=await query({sql:'SELECT count(*) AS n FROM friendbook_cf_legacy_comment_epoch WHERE id=1 AND length(generation)=32 AND revision>=0',params:[]});
 const old=await query(legacyCommentCountsStatement(builder,resources,[]));
 const candidate=await query(legacyCommentCountsWithCandidatesStatement(builder,resources,[]));
 if(epoch.results.length!==1 || epoch.results[0].n!==1 || old.results.length!==10 || candidate.results.length!==10)throw Error('Unexpected smoke result count');
 const ordered=rows=>[...rows].sort((a,b)=>a.slot-b.slot);
 const before=ordered(old.results),after=ordered(candidate.results);
 for(let i=0;i<10;i++)if(before[i].slot!==i || after[i].slot!==i || before[i].n!==0 || after[i].n!==0 || after[i].has_candidates!==0)throw Error('Synthetic count parity failed');
 return {resources:10,epoch_rows_read:epoch.meta?.rows_read??null,old_rows_read:old.meta?.rows_read??null,candidate_rows_read:candidate.meta?.rows_read??null};
}

if(process.argv[1] && import.meta.url===pathToFileURL(process.argv[1]).href){
 try {
  const nonce=crypto.randomUUID().replaceAll('-','');
  console.log('::notice:: Synthetic comment-epoch SQL smoke '+JSON.stringify(await checkCommentEpoch(fetch,process.env.CLOUDFLARE_API_TOKEN,nonce)));
 } catch { console.error('Synthetic read-only comment SQL smoke failed; no production writes were made.');process.exitCode=1; }
}
