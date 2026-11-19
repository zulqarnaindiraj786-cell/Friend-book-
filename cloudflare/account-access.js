// Read account status from D1 on each authenticated request; never trust client flags
// or a stale mirror when a canonical row exists. Missing profiles are allowed so new
// accounts can create their first profile. Storage failures fail closed at the caller.
export async function accountDisabled(db, uid) {
  if (!db) throw new Error('Database unavailable');
  const path=`users/${uid}`;
  const canonical=await db.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path = ?').bind(path).first();
  const row=canonical || await db.prepare('SELECT document_json FROM friendbook_cf_documents WHERE path = ?').bind(path).first();
  if (!row) return false;
  const value=JSON.parse(row.document_json);
  if (!value || typeof value!=='object' || Array.isArray(value)) throw new Error('Invalid account record');
  if (value.fields && typeof value.fields==='object') {
    return value.fields.banned?.booleanValue===true || value.fields.deactivated?.booleanValue===true;
  }
  return value.banned===true || value.deactivated===true;
}
