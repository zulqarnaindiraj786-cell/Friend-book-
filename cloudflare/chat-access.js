// Chat authorization always uses stored membership; never trust sender or recipient IDs alone.
function unpack(raw) {
  if (!raw || typeof raw !== 'object') return null;
  if (!raw.fields) return raw;
  const out={};
  for (const [k,v] of Object.entries(raw.fields)) {
    if (typeof v?.stringValue === 'string') out[k]=v.stringValue;
    else if (v?.arrayValue) out[k]=(v.arrayValue.values||[]).map(x=>x.stringValue).filter(x=>typeof x==='string');
  }
  return out;
}
export async function loadChat(db, chatId) {
  let row = await db.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path = ?').bind(`chats/${chatId}`).first();
  if (!row) row = await db.prepare('SELECT document_json FROM friendbook_cf_documents WHERE path = ?').bind(`chats/${chatId}`).first();
  if (!row) return null;
  return unpack(JSON.parse(row.document_json));
}
const member=(room,uid)=>Array.isArray(room?.participants) && room.participants.includes(uid);
function chatIdFor(path, data) {
  const parts=path.split('/');
  if (parts[0] === 'chats' && parts.length >= 2) return parts[1];
  if (parts[0] === 'messages' && parts.length === 2) return typeof data?.chatId === 'string' ? data.chatId : '';
  return null;
}
export async function chatReadAllowed(db,path,uid,data) {
  const parts=path.split('/');
  const id=chatIdFor(path,data);
  if (id === null) return true;
  if (!id) return false;
  // A legacy app-table mirror can be stale. Always consult the canonical room before
  // authorizing its root document as well as nested messages.
  return member(await loadChat(db,id),uid);
}
export async function chatWriteAllowed(db,path,uid,before,after,pendingRooms = new Map(), roomSnapshot = undefined) {
  const parts=path.split('/');
  const id=chatIdFor(path,before || after);
  if (id === null) return true;
  if (!id) return false;
  if (parts[0] === 'chats' && parts.length === 2) {
    if (!before) return !!after && member(after,uid) && after.participants.every(x=>typeof x==='string' && x.length>0);
    if (!member(before,uid)) return false;
    if (!after) return false; // room deletion and membership management need dedicated authorization
    // Historical room role flags were client-writable. Freeze authority-bearing
    // fields until a trusted group-chat role transition exists.
    const protectedFields = ['participants','adminIds','creatorId','createdBy','onlyAdminsCanSend'];
    return protectedFields.every(k=>JSON.stringify(before[k])===JSON.stringify(after[k]));
  }
  const room = roomSnapshot === undefined ? (await loadChat(db,id) || pendingRooms.get(id)) : roomSnapshot;
  if (!member(room,uid)) return false;
  // Creation is attributable to the authenticated sender; room cannot be re-bound.
  if (!before) return !!after && after.senderId === uid && (parts[0] !== 'messages' || after.chatId === id);
  if (!after) return before.senderId === uid;
  if (before.senderId !== after.senderId || (parts[0] === 'messages' && before.chatId !== after.chatId)) return false;
  if (before.senderId === uid) return true;
  const allowed=new Set(['isSeen','isDelivered','seenBy','deliveredTo']);
  return [...new Set([...Object.keys(before),...Object.keys(after)])]
    .every(k=>allowed.has(k) || JSON.stringify(before[k])===JSON.stringify(after[k]));
}
