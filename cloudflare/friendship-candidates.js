// Candidate rows only; callers still apply the original duplicate predicates.
// The exception indexes preserve longer/object arrays and any representation
// which cannot be proven complete by the existing two position indexes.
export const canonicalFullFriendCandidates=`
  SELECT path,document_json FROM friendbook_cf_firestore_documents WHERE parent_path='friends'`;
export const fullFriendCandidates=`
  ${canonicalFullFriendCandidates}
  UNION ALL SELECT a.path,a.document_json FROM friendbook_cf_documents a WHERE a.collection='friends'
    AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents f WHERE f.path=a.path)`;

const canonicalWrappedPair=`COALESCE((json_array_length(document_json,'$.fields.participants.arrayValue.values')=2
  AND json_type(document_json,'$.fields.participants.arrayValue.values[0].stringValue')='text'
  AND json_type(document_json,'$.fields.participants.arrayValue.values[1].stringValue')='text'),0)=1`;
const canonicalException=`(json_type(document_json,'$.participants')='object'
  OR json_array_length(document_json,'$.participants')>2
  OR (json_type(document_json,'$.fields.participants.arrayValue.values') IS NOT NULL AND NOT (${canonicalWrappedPair})))`;
const legacyException=`(json_type(a.document_json,'$.fields')='object'
  OR json_type(a.document_json,'$.participants')='object'
  OR json_array_length(a.document_json,'$.participants')>2)`;
const legacyPosition=i=>`(CASE WHEN json_type(a.document_json,'$.fields')='object'
  THEN json_extract(a.document_json,'$.fields.participants.arrayValue.values[${i}].stringValue')
  ELSE json_extract(a.document_json,'$.participants[${i}]') END)`;
const shadow=`NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents f WHERE f.path=a.path)`;
export const canonicalIndexedFriendCandidates=`
  SELECT path,document_json FROM (
  SELECT path,document_json FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_friend_participant_0
    WHERE parent_path='friends' AND json_extract(document_json,'$.participants[0]')=?
  UNION SELECT path,document_json FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_friend_participant_1
    WHERE parent_path='friends' AND json_extract(document_json,'$.participants[1]')=?
  UNION SELECT path,document_json FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_friend_wrapped_participant_0
    WHERE parent_path='friends' AND ${canonicalWrappedPair}
      AND json_extract(document_json,'$.fields.participants.arrayValue.values[0].stringValue')=?
  UNION SELECT path,document_json FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_friend_wrapped_participant_1
    WHERE parent_path='friends' AND ${canonicalWrappedPair}
      AND json_extract(document_json,'$.fields.participants.arrayValue.values[1].stringValue')=?
  UNION SELECT path,document_json FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_friend_guard_exceptions_v2
    WHERE parent_path='friends' AND ${canonicalException}
  )`;
export const indexedFriendCandidates=`
  ${canonicalIndexedFriendCandidates} UNION ALL SELECT path,document_json FROM (
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
export const malformedCanonicalFriendProbe=`EXISTS (
  SELECT 1 FROM friendbook_cf_firestore_documents a INDEXED BY idx_cf_friend_guard_exceptions_v2
    WHERE a.parent_path='friends' AND ${canonicalException} AND ${dangerousWrappedRow}
)`;
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
  const canonicalOnly=candidates.canonicalOnly===true;
  const full=canonicalOnly?canonicalFullFriendCandidates:fullFriendCandidates;
  const indexed=canonicalOnly?canonicalIndexedFriendCandidates:indexedFriendCandidates;
  const malformed=canonicalOnly?malformedCanonicalFriendProbe:malformedFriendProbe;
  if(candidates.sql===fullFriendCandidates || candidates.sql===canonicalFullFriendCandidates) return {sql:`NOT ${duplicateExists(full)}`,args:pair};
  return {sql:`CASE WHEN ${malformed} THEN NOT ${duplicateExists(full)}
    ELSE NOT ${duplicateExists(indexed)} END`,args:[...pair,...candidates.args,...pair]};
}

// Compile the complete candidate query even on an empty database. Missing
// additive indexes use the previous full-scan guard; never hide other errors.
export async function friendshipCandidates(db,uid,enabled=false,canonicalOnly=false) {
  const full=canonicalOnly?canonicalFullFriendCandidates:fullFriendCandidates;
  const indexed=canonicalOnly?canonicalIndexedFriendCandidates:indexedFriendCandidates;
  const candidates=args=>({sql:args?indexed:full,args:args?(canonicalOnly?[uid,uid,uid,uid]:[uid,uid,uid,uid,uid,uid]):[],...(canonicalOnly?{canonicalOnly:true}:{})});
  // The feature can be disabled for rollback without dropping indexes.
  if (!enabled) return candidates(false);
  try {
    await db.prepare(`SELECT path FROM (${indexed}) LIMIT 0`).bind(...(canonicalOnly?[uid,uid]:[uid,uid,uid,uid])).all();
    return candidates(true);
  } catch(error) {
    if (!/no such index: idx_cf_(?:legacy_)?friend_(?:participant_[01]|wrapped_participant_[01]|guard_exceptions(?:_v2)?)/i.test(String(error?.message||error))) throw error;
    return candidates(false);
  }
}
