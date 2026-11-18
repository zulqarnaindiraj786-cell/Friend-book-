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
const equal=(a,b)=>JSON.stringify(a)===JSON.stringify(b);
const validId=x=>typeof x==='string' && x.length>0 && x.length<=128 && !/[\/\x00-\x1f]/.test(x);
const validList=a=>Array.isArray(a) && a.every(validId) && new Set(a).size===a.length;
async function groupChatOwner(db,path) {
  const row=await db.prepare('SELECT owner_uid FROM friendbook_cf_trusted_group_chats WHERE chat_path=?').bind(path).first();
  return row?.owner_uid || null;
}
function validGroupRoom(room) {
  return room?.isGroup===true && validId(room.createdBy) && validList(room.participants) && validList(room.adminIds)
    && room.participants.includes(room.createdBy) && room.adminIds.includes(room.createdBy)
    && room.adminIds.every(id=>room.participants.includes(id)) && typeof room.onlyAdminsCanSend==='boolean';
}
function sameExcept(a,b,keys) {
  return [...new Set([...Object.keys(a),...Object.keys(b)])].every(k=>keys.has(k) || equal(a[k],b[k]));
}
export async function chatWriteAllowed(db,path,uid,before,after,pendingRooms = new Map(), roomSnapshot = undefined) {
  const parts=path.split('/');
  const id=chatIdFor(path,before || after);
  if (id === null) return true;
  if (!id) return false;
  if (parts[0] === 'chats' && parts.length === 2) {
    if (!before) {
      if (!after || !member(after,uid) || !validList(after.participants)) return false;
      if (after.isGroup===true) return validGroupRoom(after) && after.createdBy===uid && equal(after.adminIds,[uid]);
      return !after.createdBy && !after.creatorId && !after.adminIds && !after.onlyAdminsCanSend;
    }
    if (!member(before,uid)) return false;
    if (!after) return before.isGroup===true && validGroupRoom(before) && before.createdBy===uid && await groupChatOwner(db,path)===uid;
    if (before.isGroup!==after.isGroup) return false;
    const protectedFields = ['participants','adminIds','creatorId','createdBy','onlyAdminsCanSend'];
    if (before.isGroup!==true) {
      // Direct-chat clients serialize [sender, recipient], so the same two
      // participants can legitimately appear in opposite order on replies.
      if (!validList(before.participants) || !validList(after.participants)
          || before.participants.length!==after.participants.length
          || !before.participants.every(id=>after.participants.includes(id))) return false;
      return protectedFields.filter(k=>k!=='participants').every(k=>equal(before[k],after[k]));
    }
    const owner=await groupChatOwner(db,path);
    // Never grant authority based solely on a historically client-writable role.
    if (!owner || owner!==before.createdBy || !validGroupRoom(before) || !validGroupRoom(after))
      return protectedFields.every(k=>equal(before[k],after[k]));
    if (before.creatorId!==after.creatorId) return false;
    const isAdmin=before.adminIds.includes(uid);
    if (!isAdmin) {
      // Members may leave themselves; they cannot add others or alter roles/settings.
      const leaving=before.participants.includes(uid) && !after.participants.includes(uid);
      if (!sameExcept(before,after,new Set(['participants','adminIds','unreadCount','lastMessage','lastMessageTime','lastMessageTimeMs','participantNames','participantPhotos']))) return false;
      // A member may refresh only their own name/photo, not impersonate another member.
      for (const key of ['participantNames','participantPhotos']) {
        const a=before[key]||{}, b=after[key]||{};
        if (!a || !b || typeof a!=='object' || typeof b!=='object' || Array.isArray(a) || Array.isArray(b)
            || !sameExcept(a,b,new Set([uid]))) return false;
      }
      const remaining=before.participants.filter(x=>x!==uid);
      return equal(after.adminIds,before.adminIds) && (equal(after.participants,before.participants) || (leaving && equal(after.participants,remaining)));
    }
    // Ownership transfer is only possible when the creator leaves, to an existing member.
    if (after.createdBy!==owner && (uid!==owner || after.participants.includes(owner)
        || !before.participants.includes(after.createdBy))) return false;
    if (after.createdBy===owner && !after.participants.includes(owner)) return false;
    return true;
  }

  const room = roomSnapshot === undefined ? (await loadChat(db,id) || pendingRooms.get(id)) : roomSnapshot;
  if (!member(room,uid)) return false;
  if (!before && room.isGroup===true && room.onlyAdminsCanSend===true) {
    const owner=await groupChatOwner(db,`chats/${id}`);
    if (!owner || owner!==room.createdBy || !validGroupRoom(room) || !room.adminIds.includes(uid)) return false;
  }
  // Creation is attributable to the authenticated sender; room cannot be re-bound.
  if (!before) return !!after && after.senderId === uid && (parts[0] !== 'messages' || after.chatId === id);
  if (!after) return before.senderId === uid;
  if (before.senderId !== after.senderId || (parts[0] === 'messages' && before.chatId !== after.chatId)) return false;
  if (before.senderId === uid) return true;
  const allowed=new Set(['isSeen','isDelivered','seenBy','deliveredTo']);
  return [...new Set([...Object.keys(before),...Object.keys(after)])]
    .every(k=>allowed.has(k) || JSON.stringify(before[k])===JSON.stringify(after[k]));
}
