import { readPreferredDocumentJson } from './app-documents.js';

// Read account status from D1 on each authenticated request; never trust client flags
// or a stale mirror when a canonical row exists. Missing profiles are allowed so new
// accounts can create their first profile. Storage failures fail closed at the caller.
export async function accountStatus(db, uid, accountRow) {
  if (!db) throw new Error('Database unavailable');
  const path=`users/${uid}`;
  const row=accountRow===undefined ? await readPreferredDocumentJson(db,path) : accountRow;
  if (!row) return {disabled:false,suspended:false,profile:null};
  const value=JSON.parse(row.document_json);
  if (!value || typeof value!=='object' || Array.isArray(value)) throw new Error('Invalid account record');
  if (value.fields && typeof value.fields==='object') {
    const f=value.fields;
    return {profile:value,disabled:f.banned?.booleanValue===true || f.deactivated?.booleanValue===true,
      suspended:f.suspendedLifetime?.booleanValue===true || Number(f.suspendedUntil?.integerValue ?? f.suspendedUntil?.doubleValue)>Date.now()};
  }
  return {profile:value,disabled:value.banned===true || value.deactivated===true,
    suspended:value.suspendedLifetime===true || Number(value.suspendedUntil)>Date.now()};
}
