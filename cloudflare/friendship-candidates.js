// Candidate rows only; callers still apply the original duplicate predicates.
// The exception indexes preserve longer/object arrays and any representation
// which cannot be proven complete by the existing two position indexes.
export const canonicalFullFriendCandidates=`
  SELECT path,document_json FROM friendbook_cf_firestore_documents WHERE parent_path='friends'`;
export const fullFriendCandidates=`
  ${canonicalFullFriendCandidates}
  UNION ALL SELECT a.path,a.document_json FROM friendbook_cf_documents a WHERE a.collection='friends'
    AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents f WHERE f.path=a.path)`;

const canonicalException=`(json_type(document_json,'$.participants')='object'
  OR json_array_length(document_json,'$.participants')>2
  OR json_type(document_json,'$.fields.participants.arrayValue.values') IS NOT NULL)`;
const legacyException=`(json_type(a.document_json,'$.fields')='object'
  OR json_type(a.document_json,'$.participants')='object'
  OR json_array_length(a.document_json,'$.participants')>2)`;
const legacyPosition=i=>`(CASE WHEN json_type(a.document_json,'$.fields')='object'
  THEN json_extract(a.document_json,'$.fields.participants.arrayValue.values[${i}].stringValue')
  ELSE json_extract(a.document_json,'$.participants[${i}]') END)`;
const shadow=`NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents f WHERE f.path=a.path)`;
const actorInCandidates=(json,uid='?')=>`(
  EXISTS (SELECT 1 FROM json_each(${json},'$.participants') p WHERE p.value=${uid})
  OR EXISTS (SELECT 1 FROM json_each(${json},'$.fields.participants.arrayValue.values') p
    WHERE json_valid(p.value)=1 AND json_extract(p.value,'$.stringValue')=${uid})
)`;
export const canonicalIndexedFriendCandidates=`
  SELECT path,document_json FROM (
  SELECT path,document_json FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_friend_participant_0
    WHERE parent_path='friends' AND json_extract(document_json,'$.participants[0]')=?
  UNION SELECT path,document_json FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_friend_participant_1
    WHERE parent_path='friends' AND json_extract(document_json,'$.participants[1]')=?
  UNION SELECT path,document_json FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_friend_guard_exceptions
    WHERE parent_path='friends' AND ${canonicalException} AND ${actorInCandidates('document_json')}
  )`;
export const indexedFriendCandidates=`
  ${canonicalIndexedFriendCandidates} UNION ALL SELECT path,document_json FROM (
  SELECT a.path,a.document_json FROM friendbook_cf_documents a INDEXED BY idx_cf_legacy_friend_participant_0
    WHERE a.collection='friends' AND ${legacyPosition(0)}=? AND ${shadow}
  UNION SELECT a.path,a.document_json FROM friendbook_cf_documents a INDEXED BY idx_cf_legacy_friend_participant_1
    WHERE a.collection='friends' AND ${legacyPosition(1)}=? AND ${shadow}
  UNION SELECT a.path,a.document_json FROM friendbook_cf_documents a INDEXED BY idx_cf_legacy_friend_guard_exceptions
    WHERE a.collection='friends' AND ${legacyException} AND ${shadow} AND ${actorInCandidates('a.document_json')}
  )`;

// Transaction guards know both participants. Seek their exact two-member rows
// by pair instead of first materializing every edge in the actor's graph. Rare
// historical object/long-array envelopes still come from the exception indexes.
const canonicalPair=(left,right)=>`SELECT path,document_json FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_friend_exact_pair
  WHERE parent_path='friends' AND json_extract(document_json,'$.participants[0]')=${left} AND json_extract(document_json,'$.participants[1]')=${right}`;
const canonicalPairExceptions=`SELECT path,document_json FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_friend_guard_exceptions
  WHERE parent_path='friends' AND ${canonicalException} AND ${actorInCandidates('document_json')}`;
export const canonicalIndexedFriendPairCandidates=`${canonicalPair('?','?')} UNION ${canonicalPair('?','?')} UNION ${canonicalPairExceptions}`;
export const indexedFriendPairCandidates=`
  SELECT path,document_json FROM (${canonicalIndexedFriendPairCandidates})
  UNION ALL SELECT path,document_json FROM (
    SELECT a.path,a.document_json FROM friendbook_cf_documents a INDEXED BY idx_cf_legacy_friend_participant_0
      WHERE a.collection='friends' AND ${legacyPosition(0)}=? AND ${legacyPosition(1)}=? AND ${shadow}
    UNION SELECT a.path,a.document_json FROM friendbook_cf_documents a INDEXED BY idx_cf_legacy_friend_participant_0
      WHERE a.collection='friends' AND ${legacyPosition(0)}=? AND ${legacyPosition(1)}=? AND ${shadow}
    UNION SELECT a.path,a.document_json FROM friendbook_cf_documents a INDEXED BY idx_cf_legacy_friend_guard_exceptions
      WHERE a.collection='friends' AND ${legacyException} AND ${shadow} AND ${actorInCandidates('a.document_json')}
  )`;

// The original duplicate guard can raise on malformed text within historical
// Firestore envelopes. Indexed UNION ordering can otherwise short-circuit before
// such a row. Detect dangerous unshadowed exceptions *inside the transaction*
// and use the original full guard when any exist. Do not expose the malformed
// value to json_extract during this probe.
export const duplicateExists=candidates=>`EXISTS (SELECT 1 FROM (${candidates}) d WHERE (
  (EXISTS (SELECT 1 FROM json_each(d.document_json,'$.participants') WHERE value=?)
   AND EXISTS (SELECT 1 FROM json_each(d.document_json,'$.participants') WHERE value=?))
  OR (EXISTS (SELECT 1 FROM json_each(d.document_json,'$.fields.participants.arrayValue.values')
      WHERE json_valid(value)=1 AND json_extract(value,'$.stringValue')=?)
   AND EXISTS (SELECT 1 FROM json_each(d.document_json,'$.fields.participants.arrayValue.values')
      WHERE json_valid(value)=1 AND json_extract(value,'$.stringValue')=?))
))`;
export function friendshipPairSelection(candidates,uid,other) {
  if(!candidates.pairIndexed)return candidates;
  const canonicalOnly=candidates.canonicalOnly===true;
  const sql=canonicalOnly?canonicalIndexedFriendPairCandidates:indexedFriendPairCandidates;
  const args=canonicalOnly?[uid,other,other,uid,uid,uid]:[uid,other,other,uid,uid,uid,uid,other,other,uid,uid,uid];
  return {sql,args};
}
export function friendshipDuplicateGuard(candidates,uid,other) {
  const pair=[uid,other,uid,other];
  const canonicalOnly=candidates.canonicalOnly===true;
  const full=canonicalOnly?canonicalFullFriendCandidates:fullFriendCandidates;
  const indexed=canonicalOnly?canonicalIndexedFriendCandidates:indexedFriendCandidates;
  if(candidates.sql===fullFriendCandidates || candidates.sql===canonicalFullFriendCandidates) return {sql:`NOT ${duplicateExists(full)}`,args:pair};
  const selected=friendshipPairSelection(candidates,uid,other);
  return {sql:`NOT ${duplicateExists(selected.sql)}`,args:[...selected.args,...pair]};
}

// Compile the complete candidate query even on an empty database. Missing
// additive indexes use the previous full-scan guard; never hide other errors.
export async function friendshipCandidates(db,uid,enabled=false,canonicalOnly=false) {
  const full=canonicalOnly?canonicalFullFriendCandidates:fullFriendCandidates;
  const indexed=canonicalOnly?canonicalIndexedFriendCandidates:indexedFriendCandidates;
  const indexedArgs=canonicalOnly?[uid,uid,uid,uid]:[uid,uid,uid,uid,uid,uid,uid,uid];
  let pairReady=false;
  const candidates=args=>({sql:args?indexed:full,args:args?indexedArgs:[],...(args===true?{pairIndexed:pairReady}:{}),...(canonicalOnly?{canonicalOnly:true}:{})});
  // The feature can be disabled for rollback without dropping indexes.
  if (!enabled) return candidates(false);
  try {
    await db.prepare(`SELECT path FROM (${indexed}) LIMIT 0`).bind(...indexedArgs).all();
    const pairQuery=canonicalOnly?canonicalIndexedFriendPairCandidates:indexedFriendPairCandidates;
    const pairArgs=canonicalOnly?['a','b','b','a','a','a']:['a','b','b','a','a','a','a','b','b','a','a','a'];
    try {
      await db.prepare(`SELECT path FROM (${pairQuery}) LIMIT 0`).bind(...pairArgs).all();
      pairReady=true;
    } catch(error) {
      if(!/no such index: idx_cf_friend_exact_pair/i.test(String(error?.message||error))) throw error;
    }
    return candidates(true);
  } catch(error) {
    if (!/no such index: idx_cf_(?:legacy_)?friend_(?:participant_[01]|guard_exceptions)/i.test(String(error?.message||error))) throw error;
    return candidates(false);
  }
}
