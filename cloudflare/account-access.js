// Read account status from D1 on each authenticated request; never trust client flags
// or a stale mirror when a canonical row exists. Missing profiles are allowed so new
// accounts can create their first profile. Storage failures fail closed at the caller.
export async function accountStatus(db, uid) {
  if (!db) throw new Error('Database unavailable');
  const path=`users/${uid}`;
  const canonical=await db.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path = ?').bind(path).first();
  const row=canonical || await db.prepare('SELECT document_json FROM friendbook_cf_documents WHERE path = ?').bind(path).first();
  if (!row) return {disabled:false,suspended:false};
  const value=JSON.parse(row.document_json);
  if (!value || typeof value!=='object' || Array.isArray(value)) throw new Error('Invalid account record');
  if (value.fields && typeof value.fields==='object') {
    const f=value.fields;
    return {disabled:f.banned?.booleanValue===true || f.deactivated?.booleanValue===true,
      suspended:f.suspendedLifetime?.booleanValue===true || Number(f.suspendedUntil?.integerValue ?? f.suspendedUntil?.doubleValue)>Date.now()};
  }
  return {disabled:value.banned===true || value.deactivated===true,
    suspended:value.suspendedLifetime===true || Number(value.suspendedUntil)>Date.now()};
}
