// Candidate rows only; callers still apply the original duplicate predicates.
// The exception indexes preserve longer/object arrays and any representation
// which cannot be proven complete by the existing two position indexes.
export const fullFriendCandidates=`
  SELECT path,document_json FROM friendbook_cf_firestore_documents WHERE parent_path='friends'
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
export const indexedFriendCandidates=`
  SELECT path,document_json FROM (
  SELECT path,document_json FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_friend_participant_0
    WHERE parent_path='friends' AND json_extract(document_json,'$.participants[0]')=?
  UNION SELECT path,document_json FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_friend_participant_1
    WHERE parent_path='friends' AND json_extract(document_json,'$.participants[1]')=?
  UNION SELECT path,document_json FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_friend_guard_exceptions
    WHERE parent_path='friends' AND ${canonicalException}
  ) UNION ALL SELECT path,document_json FROM (
  SELECT a.path,a.document_json FROM friendbook_cf_documents a INDEXED BY idx_cf_legacy_friend_participant_0
    WHERE a.collection='friends' AND ${legacyPosition(0)}=? AND ${shadow}
  UNION SELECT a.path,a.document_json FROM friendbook_cf_documents a INDEXED BY idx_cf_legacy_friend_participant_1
    WHERE a.collection='friends' AND ${legacyPosition(1)}=? AND ${shadow}
  UNION SELECT a.path,a.document_json FROM friendbook_cf_documents a INDEXED BY idx_cf_legacy_friend_guard_exceptions
    WHERE a.collection='friends' AND ${legacyException} AND ${shadow}
  )`;

// The original duplicate guard can raise on malformed text within historical
// Firestore envelopes. Indexed UNION ordering can otherwise short-circuit before
// such a row. Detect dangerous unshadowed exceptions *inside the transaction*
// and use the original full guard when any exist. Do not expose the malformed
// value to json_extract during this probe.
const dangerousWrappedRow=`EXISTS (SELECT 1 FROM json_each(a.document_json,'$.fields.participants.arrayValue.values') e
  WHERE typeof(e.value)='text' AND json_valid(e.value)=0)`;
export const malformedFriendProbe=`EXISTS (
  SELECT 1 FROM friendbook_cf_firestore_documents a INDEXED BY idx_cf_friend_guard_exceptions
    WHERE a.parent_path='friends' AND ${canonicalException} AND ${dangerousWrappedRow}
  UNION ALL
  SELECT 1 FROM friendbook_cf_documents a INDEXED BY idx_cf_legacy_friend_guard_exceptions
    WHERE a.collection='friends' AND ${legacyException} AND ${shadow} AND ${dangerousWrappedRow}
)`;
const duplicateExists=candidates=>`EXISTS (SELECT 1 FROM (${candidates}) d WHERE (
  (EXISTS (SELECT 1 FROM json_each(d.document_json,'$.participants') WHERE value=?)
   AND EXISTS (SELECT 1 FROM json_each(d.document_json,'$.participants') WHERE value=?))
  OR (EXISTS (SELECT 1 FROM json_each(d.document_json,'$.fields.participants.arrayValue.values') WHERE json_extract(value,'$.stringValue')=?)
   AND EXISTS (SELECT 1 FROM json_each(d.document_json,'$.fields.participants.arrayValue.values') WHERE json_extract(value,'$.stringValue')=?))
))`;
export function friendshipDuplicateGuard(candidates,uid,other) {
  const pair=[uid,other,uid,other];
  if(candidates.sql===fullFriendCandidates) return {sql:`NOT ${duplicateExists(fullFriendCandidates)}`,args:pair};
  return {sql:`CASE WHEN ${malformedFriendProbe} THEN NOT ${duplicateExists(fullFriendCandidates)}
    ELSE NOT ${duplicateExists(indexedFriendCandidates)} END`,args:[...pair,...candidates.args,...pair]};
}

// Compile the complete candidate query even on an empty database. Missing
// additive indexes use the previous full-scan guard; never hide other errors.
export async function friendshipCandidates(db,uid,enabled=false) {
  // The feature can be disabled for rollback without dropping indexes.
  if (!enabled) return {sql:fullFriendCandidates,args:[]};
  try {
    await db.prepare(`SELECT path FROM (${indexedFriendCandidates}) LIMIT 0`).bind(uid,uid,uid,uid).all();
    return {sql:indexedFriendCandidates,args:[uid,uid,uid,uid]};
  } catch(error) {
    if (!/no such index: idx_cf_(?:legacy_)?friend_(?:participant_[01]|guard_exceptions)/i.test(String(error?.message||error))) throw error;
    return {sql:fullFriendCandidates,args:[]};
  }
}
