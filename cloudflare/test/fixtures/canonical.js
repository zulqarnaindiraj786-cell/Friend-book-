// Production keeps exactly one canonical row for every historical document: the
// coverage check reports zero legacy-only rows and the Worker reads canonical
// storage only. Tests that describe an existing document seed the mirror, so
// these helpers copy that row into canonical storage, exactly like production.

// Production backfilled legacy rows into canonical storage after unwrapping the
// Firestore field envelope, so a mirrored row must not keep `fields.*`.
function plain(json) {
  let doc;
  try { doc = JSON.parse(json); } catch { return json; }
  if (!doc || typeof doc !== 'object' || !doc.fields || typeof doc.fields !== 'object') return json;
  const out = {};
  for (const [key, value] of Object.entries(doc.fields)) {
    if (value && typeof value === 'object') {
      if ('stringValue' in value) out[key] = value.stringValue;
      else if ('integerValue' in value) out[key] = Number(value.integerValue);
      else if ('doubleValue' in value) out[key] = Number(value.doubleValue);
      else if ('booleanValue' in value) out[key] = value.booleanValue;
      else if ('arrayValue' in value) out[key] = (value.arrayValue.values || []).map(v => v.stringValue ?? v.integerValue ?? null);
      else if ('nullValue' in value) out[key] = null;
      else out[key] = value;
    }
  }
  return JSON.stringify(out);
}

export function mirrorLegacy(sql, path) {
  const row = sql.prepare('SELECT * FROM friendbook_cf_documents WHERE path = ?').get(path);
  if (!row) return;
  sql.prepare(`INSERT INTO friendbook_cf_firestore_documents
      (path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time)
    VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET document_json=excluded.document_json,update_time=excluded.update_time`)
    .run(row.path,row.collection,row.collection,row.doc_id,row.owner_uid,plain(row.document_json),row.updated_at,row.updated_at);
}

export function mirrorAllLegacy(sql) {
  const rows=sql.prepare(`SELECT l.* FROM friendbook_cf_documents l
    WHERE NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents f WHERE f.path=l.path)`).all();
  const insert=sql.prepare(`INSERT INTO friendbook_cf_firestore_documents
      (path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time)
    VALUES (?,?,?,?,?,?,?,?)`);
  for (const row of rows) insert.run(row.path,row.collection,row.collection,row.doc_id,row.owner_uid,plain(row.document_json),row.updated_at,row.updated_at);
}

// Wraps a test database so every legacy insert also produces its canonical twin.
export function autoMirror(sql) {
  const prepare = sql.prepare.bind(sql);
  sql.prepare = (query) => {
    const stmt = prepare(query);
    if (!/INSERT\s+(OR\s+\w+\s+)?INTO\s+friendbook_cf_documents/i.test(query)) return stmt;
    return new Proxy(stmt, { get(target, prop) {
      if (prop === 'run') return (...args) => { const result = target.run(...args); mirrorAllLegacy(sql); return result; };
      const value = target[prop];
      return typeof value === 'function' ? value.bind(target) : value;
    } });
  };
  return sql;
}

// Accounts are the one collection every read path re-authorizes: without their
// canonical row the whole request fails closed, so fixtures mirror them first.
export function mirrorAccounts(sql) {
  sql.prepare(`INSERT INTO friendbook_cf_firestore_documents
      (path, parent_path, collection_id, doc_id, owner_uid, document_json, create_time, update_time)
    SELECT path, collection, collection, doc_id, owner_uid, document_json, updated_at, updated_at
    FROM friendbook_cf_documents l
    WHERE collection='users'
      AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents f WHERE f.path = l.path)`).run();
}

// A historical fixture is seeded directly into canonical storage. This is an
// explicit seed helper, not a read-time fallback or database interception.
export function canonicalSeed(sql) {
  const decode = v => {
    if (Array.isArray(v)) return v.map(decode);
    if (!v || typeof v !== 'object') return v;
    if ('stringValue' in v) return String(v.stringValue);
    if ('integerValue' in v) return Number(v.integerValue);
    if ('doubleValue' in v) return Number(v.doubleValue);
    if ('booleanValue' in v) return Boolean(v.booleanValue);
    if ('timestampValue' in v) return {__type:'timestamp',millis:Date.parse(v.timestampValue)||0};
    if ('nullValue' in v) return null;
    if ('arrayValue' in v) return (v.arrayValue.values||[]).map(decode);
    if ('mapValue' in v) return decode(v.mapValue.fields||{});
    return Object.fromEntries(Object.entries(v).map(([k,x])=>[k,decode(x)]));
  };
  return {run(path,collection,id,owner,raw,time) {
    let doc=JSON.parse(raw), created=Date.parse(doc.createTime);
    doc=decode(doc.fields||doc);
    if(collection==='posts' && doc.createdAt==null && Number.isSafeInteger(created)) doc.createdAt={__type:'timestamp',millis:created};
    delete doc.id;
    return sql.prepare(`INSERT INTO friendbook_cf_firestore_documents
      (path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time)
      VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(path) DO NOTHING`)
      .run(path,collection,collection,id,owner,JSON.stringify(doc),Number(doc.createdAt)||0,time);
  }};
}
