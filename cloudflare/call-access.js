import { readD1DocumentFields } from './app-documents.js';

const blocked=(profile,uid)=>['blocked','blockedUsers'].some(k=>(profile?.[k]?.arrayValue?.values||[]).some(v=>v.stringValue===uid));
const active=profile=>profile && profile.banned?.booleanValue!==true && profile.deactivated?.booleanValue!==true
  && profile.suspendedLifetime?.booleanValue!==true && !(Number(profile.suspendedUntil?.integerValue ?? profile.suspendedUntil?.doubleValue)>Date.now());
export async function callAccountsAllowed(db,a,b) {
  const [first,second]=await Promise.all([readD1DocumentFields(db,`users/${a}`),readD1DocumentFields(db,`users/${b}`)]);
  return active(first) && active(second) && !blocked(first,b) && !blocked(second,a);
}
export async function authorizeCallMedia(db,chat,call,uid) {
  const row=await db.prepare("SELECT 1 AS ok FROM friendbook_cf_chat_calls WHERE chat_id=? AND call_id=? AND status='connected' AND expires_at>?").bind(chat,call,Date.now()).first();
  if (!row) return null;
  const fields=await readD1DocumentFields(db,`chats/${chat}`);
  const members=fields?.participants?.arrayValue?.values?.map(v=>v.stringValue);
  const signal=fields?.activeCall?.mapValue?.fields;
  if (!Array.isArray(members) || members.length!==2 || !members.includes(uid) || fields.isGroup?.booleanValue===true
      || signal?.callId?.stringValue!==call || signal?.status?.stringValue!=='connected') return null;
  const peer=members.find(id=>id && id!==uid);
  if (!peer || !await callAccountsAllowed(db,uid,peer)) return null;
  return signal.callType?.stringValue==='video'?'video':'voice';
}
