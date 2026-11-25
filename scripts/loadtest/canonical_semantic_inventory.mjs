#!/usr/bin/env node
// Exact Worker document normalization; aggregate only, never log paths or values.
import {toPlainDocument} from '../../cloudflare/app-documents.js';

const PAGE=250;
const KNOWN=new Set(['id','uid','fromUid','toUid','status','participants','friendsCount','followersCount','followingCount',
  'isRead','createdAt','updatedAt','createTime','updateTime','name','username','displayName','photoUrl','avatarUrl',
  'profileImage','email','phone','text','caption','visibility','ownerUid','postId','chatId','groupId','type','title',
  'body','lastMessage','lastMessageAt','blocked','blockedUsers']);
const ORIGIN='https://api.cloudflare.com/client/v4/accounts/92f3d04598822de2e49e14561218d090/d1/database/e1c605ca-a50b-46c6-9deb-56dd1f70d163/query';
function canonicalize(value){
  if(Array.isArray(value))return value.map(canonicalize);
  if(value&&typeof value==='object')return Object.fromEntries(Object.keys(value).sort().map(key=>[key,canonicalize(value[key])]));
  return value;
}
export function equalDocuments(legacyJson,legacyId,canonicalJson,canonicalId){
  try{return JSON.stringify(canonicalize(toPlainDocument(legacyJson,legacyId)))===JSON.stringify(canonicalize(toPlainDocument(canonicalJson,canonicalId)));}
  catch{return false;}
}
export function mismatchFieldLabels(legacyJson,legacyId,canonicalJson,canonicalId){
  let left,right;
  try{left=toPlainDocument(legacyJson,legacyId);right=toPlainDocument(canonicalJson,canonicalId);}catch{return ['_malformed'];}
  if(!left||!right)return ['_malformed'];
  return [...new Set([...Object.keys(left),...Object.keys(right)])].filter(k=>JSON.stringify(canonicalize(left[k]))!==JSON.stringify(canonicalize(right[k])))
    .map(k=>KNOWN.has(k)?k:'_other');
}
async function query(sql,params){
  const token=process.env.CLOUDFLARE_API_TOKEN;
  if(!token)throw new Error('Cloudflare API token unavailable');
  const response=await fetch(ORIGIN,{method:'POST',headers:{Authorization:`Bearer ${token}`,'content-type':'application/json'},body:JSON.stringify({sql,params}),signal:AbortSignal.timeout(45000)});
  const payload=await response.json();
  const item=payload?.result?.[0];
  if(!response.ok||payload?.success!==true||item?.success!==true||!Array.isArray(item.results)||!item.meta)throw new Error('D1 aggregate scan failed');
  return item;
}
export async function inspect(pageSize=PAGE){
  if(!Number.isInteger(pageSize)||pageSize<1||pageSize>1000)throw new Error('Invalid page size');
  let cursor='';const collections={};let rowsRead=0;
  for(;;){
    const r=await query(`SELECT m.path AS path,m.collection AS collection,m.doc_id AS mirror_id,m.owner_uid AS mirror_owner,m.document_json AS mirror_json,c.doc_id AS canonical_id,c.owner_uid AS canonical_owner,c.document_json AS canonical_json FROM friendbook_cf_documents m LEFT JOIN friendbook_cf_firestore_documents c ON c.path=m.path WHERE m.path>? ORDER BY m.path LIMIT ?`,[cursor,pageSize]);
    const read=r.meta.rows_read;if(!Number.isSafeInteger(read)||read<0)throw new Error('D1 read accounting unavailable');rowsRead+=read;
    if(!r.results.length)break;
    for(const row of r.results){
      if(typeof row.path!=='string'||row.path<=cursor)throw new Error('Invalid inventory cursor');cursor=row.path;
      const name=['users','posts','reels','stories','chats','messages','friend_requests','friends','groups','comments','notifications','lives','payments','reports','pages'].includes(row.collection)?row.collection:'other';
      const x=collections[name] ||= {sampled:0,canonical_missing:0,semantic_match:0,semantic_mismatch:0,owner_match:0,owner_mismatch:0,
        owner_mismatch_canonical_empty:0,owner_mismatch_mirror_empty:0,owner_mismatch_canonical_is_doc_id:0,owner_mismatch_mirror_is_doc_id:0,mismatch_fields:{},malformed:0};
      x.sampled++;
      if(row.canonical_json==null){x.canonical_missing++;continue;}
      const id=row.canonical_id||row.mirror_id;
      if(row.canonical_owner===row.mirror_owner)x.owner_match++;else{
        x.owner_mismatch++;
        if(!row.canonical_owner)x.owner_mismatch_canonical_empty++;
        if(!row.mirror_owner)x.owner_mismatch_mirror_empty++;
        if(row.canonical_owner===id)x.owner_mismatch_canonical_is_doc_id++;
        if(row.mirror_owner===id)x.owner_mismatch_mirror_is_doc_id++;
      }
      let fields;
      try{fields=mismatchFieldLabels(row.mirror_json,row.mirror_id||id,row.canonical_json,id);}catch{fields=['_malformed'];}
      if(fields.includes('_malformed')){x.malformed++;continue;}
      if(equalDocuments(row.mirror_json,row.mirror_id||id,row.canonical_json,id))x.semantic_match++;
      else{x.semantic_mismatch++;for(const f of fields)x.mismatch_fields[f]=(x.mismatch_fields[f]||0)+1;}
    }
    if(r.results.length<pageSize)break;
  }
  const mismatchCollections=Object.fromEntries(Object.entries(collections).filter(([,x])=>x.canonical_missing||x.semantic_mismatch||x.owner_mismatch||x.malformed));
  const sum=key=>Object.values(collections).reduce((n,x)=>n+x[key],0);
  return {sampled:sum('sampled'),rows_read:rowsRead,truncated:false,page_size:pageSize,totals:Object.fromEntries(['canonical_missing','semantic_match','semantic_mismatch','owner_match','owner_mismatch','malformed'].map(k=>[k,sum(k)])),mismatch_collections:mismatchCollections};
}
if(import.meta.url===`file://${process.argv[1]}`){
  inspect().then(result=>console.log('::notice:: Worker-normalized canonical semantic inventory '+JSON.stringify(result)))
    .catch(()=>{console.error('::error::Worker-normalized inventory failed; no data changed.');process.exitCode=1;});
}
