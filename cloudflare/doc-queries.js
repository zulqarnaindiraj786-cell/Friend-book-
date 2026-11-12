// SQL building for the Cloudflare document store: filtered lists, keyset pages, counts and atomic
// field operations. Every statement is prepared with bound parameters - a field name only ever
// reaches SQL after an allowlist check, and a value never reaches SQL as text at all.
import { queryable, listScope } from './doc-access.js';

export const MAX_LIMIT = 50;
export const DEFAULT_LIMIT = 20;
export const MAX_FILTERS = 4;
/** A single `in`/`inId` filter is bounded so one request cannot smuggle a full table scan in. */
export const MAX_IN = 30;
export const MAX_OPS = 20;
export const MAX_DOC_BYTES = 95_000;
/** A count is capped so one request can never turn into a full-table scan on a big collection. */
export const COUNT_CAP = 5_000;

/** Fields that hold numbers (or booleans, which SQLite extracts as 1/0). Everything else is text. */
const NUMERIC = new Set([
  'createdAt', 'updatedAt', 'likes', 'comments', 'shares', 'stars', 'views', 'expiresAt',
  'lastMessageAt', 'startsAt', 'followers', 'following', 'friendsCount', 'members', 'viewers',
  'isRead', 'banned', 'isAdmin', 'isModerator', 'deactivated', 'archived', 'count', 'amount',
]);

export class QueryError extends Error {
  constructor(message, status = 400) {
    super(message);
    this.status = status;
  }
}

export function isNumericField(field) {
  return NUMERIC.has(field);
}

/**
 * The SQL expression that reads one top-level document field, typed the way it is stored.
 *
 * Two shapes live in the same table and both must answer a filter: plain JSON documents written
 * through this Worker, and historical rows copied from the Firestore REST export, which wrap every
 * value as `{fields: {name: {stringValue|integerValue|doubleValue|booleanValue|timestampValue}}}`.
 * Reading only the plain shape would silently hide migrated history from every query, so each field
 * falls back to its wrapped form - integers and timestamps included - before defaulting.
 */
export function fieldExpr(field) {
  if (!/^[A-Za-z_][A-Za-z0-9_]{0,39}$/.test(field)) throw new QueryError('Invalid field name');
  const plain = `json_extract(document_json, '$.${field}')`;
  const wrapped = `$.fields.${field}`;
  if (isNumericField(field)) {
    return `COALESCE(${plain},`
      + ` CAST(json_extract(document_json, '${wrapped}.integerValue') AS REAL),`
      + ` json_extract(document_json, '${wrapped}.doubleValue'),`
      + ` json_extract(document_json, '${wrapped}.booleanValue'),`
      + ` CAST(strftime('%s', json_extract(document_json, '${wrapped}.timestampValue')) AS REAL) * 1000,`
      + ` 0)`;
  }
  return `COALESCE(${plain}, json_extract(document_json, '${wrapped}.stringValue'), '')`;
}

/** A row whose id is one of a bounded set (Firestore's whereIn, used for FieldPath.documentId()). */
export function idInExpr() {
  return 'doc_id IN (%PLACEHOLDER%)';
}

/** Array-contains that works on a plain array and on a wrapped Firestore `arrayValue`. */
export function containsExpr(field) {
  if (!/^[A-Za-z_][A-Za-z0-9_]{0,39}$/.test(field)) throw new QueryError('Invalid field name');
  return `(EXISTS (SELECT 1 FROM json_each(json_extract(document_json, '$.${field}')) WHERE value = ?)`
    + ` OR EXISTS (SELECT 1 FROM json_each(json_extract(document_json, '$.fields.${field}.arrayValue.values'))`
    + ` WHERE json_extract(value, '$.stringValue') = ?))`;
}

/** Coerces a query-string value to the type the field is stored as, so comparisons never lie. */
export function coerce(field, raw) {
  const value = String(raw);
  if (value.length > 512) throw new QueryError('Filter value too large');
  if (!isNumericField(field)) return value;
  if (value === 'true') return 1;
  if (value === 'false') return 0;
  const num = Number(value);
  if (!Number.isFinite(num)) throw new QueryError('Numeric filter value required');
  return num;
}

export function orderExpr(field) {
  if (field === 'updatedAt') return 'updated_at';
  return fieldExpr(field);
}

/** Cursor: the order value plus the path, so a page can never skip or repeat a document. */
export function encodeCursor(value, path) {
  const json = JSON.stringify({ v: value === null || value === undefined ? '' : value, p: path });
  return btoa(json).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function decodeCursor(raw) {
  if (!raw) return null;
  const padded = String(raw).replace(/-/g, '+').replace(/_/g, '/');
  let parsed;
  try { parsed = JSON.parse(atob(padded + '='.repeat((4 - (padded.length % 4)) % 4))); }
  catch { throw new QueryError('Invalid cursor'); }
  if (!parsed || typeof parsed !== 'object' || typeof parsed.p !== 'string' || parsed.p.length > 300) {
    throw new QueryError('Invalid cursor');
  }
  return parsed;
}

/**
 * Builds a list/count statement from validated query parameters.
 *
 * Supported: `eq[field]=value` (max 4), `has[field]=value` (array-contains, max 2), `in[field]=a,b,c`,
 * `inId=a,b,c`, `gt/gte/lt/lte[field]=value` (max 2, numbers only), `order=field`, `dir=asc|desc`,
 * `limit`, `after=<cursor>`, `since=<ms>` (documents changed after a moment, which is how a client
 * watches for updates) and `count=1`. Soft-deleted documents are always excluded.
 *
 * The comparison operators exist because the app asks questions Firestore answered with them - "live
 * stories that have not expired yet", "boosts still running" - and a numeric field can only be
 * answered by the database, not by shipping a whole collection to a phone.
 */
export function buildListQuery(collection, params, uid, admin, { count = false } = {}) {
  const spec = queryable(collection);
  if (!spec) throw new QueryError('Unsupported collection');

  const where = ['collection = ?', "COALESCE(json_extract(document_json, '$.deleted'), 0) = 0"];
  const binds = [collection];

  const scope = listScope(collection, uid, admin);
  if (scope.sql) {
    where.push(`(${scope.sql.replace(/^ AND /, '')})`);
    binds.push(...scope.binds);
  }

  const seenFilters = new Set();
  let filterCount = 0;
  for (const [key, raw] of params) {
    const match = /^(eq|has|in|gt|gte|lt|lte)\[([A-Za-z_][A-Za-z0-9_]{0,39})\]$/.exec(key);
    const idIn = key === 'inId';
    if (!match && !idIn) continue;
    if (idIn) {
      // Firestore's whereIn(FieldPath.documentId(), ids): a bounded set of document ids.
      const ids = [...new Set(String(raw).split(',').map(id => id.trim()).filter(Boolean))].slice(0, MAX_IN);
      if (!ids.length) throw new QueryError('inId requires at least one id');
      where.push(`doc_id IN (${ids.map(() => '?').join(', ')})`);
      binds.push(...ids);
      continue;
    }
    const kind = match[1];
    const field = match[2];
    if (kind === 'eq' || kind === 'in') {
      if (!spec.eq.includes(field)) throw new QueryError(`Cannot filter on ${field}`);
    } else if (kind === 'has') {
      if (!spec.has.includes(field)) throw new QueryError(`Cannot filter on ${field}`);
    } else if (!spec.eq.includes(field) && !spec.order.includes(field)) {
      // A comparison only makes sense on a field the collection is allowed to be filtered or sorted by.
      throw new QueryError(`Cannot compare ${field}`);
    }
    if (kind === 'has' && ++filterCount > MAX_FILTERS) throw new QueryError('Too many filters');
    if (kind !== 'has' && ++filterCount > MAX_FILTERS) throw new QueryError('Too many filters');
    if (seenFilters.has(`${kind}:${field}`)) continue;
    seenFilters.add(`${kind}:${field}`);
    if (kind === 'eq') {
      where.push(`${fieldExpr(field)} = ?`);
      binds.push(coerce(field, raw));
    } else if (kind === 'in') {
      const values = [...new Set(String(raw).split(',').map(v => v.trim()).filter(v => v !== ''))];
      if (!values.length) throw new QueryError('in requires at least one value');
      if (values.length > MAX_IN) throw new QueryError('Too many values in filter');
      const coerced = values.map(value => coerce(field, value));
      where.push(`${fieldExpr(field)} IN (${coerced.map(() => '?').join(', ')})`);
      binds.push(...coerced);
    } else if (kind === 'has') {
      where.push(containsExpr(field));
      const value = String(raw).slice(0, 512);
      binds.push(value, value);
    } else {
      // gt/gte/lt/lte are numeric only: comparing text across mixed shapes would silently misorder.
      if (!isNumericField(field)) throw new QueryError(`${field} cannot be compared`);
      const operator = { gt: '>', gte: '>=', lt: '<', lte: '<=' }[kind];
      where.push(`${fieldExpr(field)} ${operator} ?`);
      binds.push(coerce(field, raw));
    }
  }

  const since = params.get('since');
  if (since !== null && since !== '') {
    const ms = Number(since);
    if (!Number.isFinite(ms) || ms < 0) throw new QueryError('Invalid since');
    where.push('updated_at > ?');
    binds.push(Math.trunc(ms));
  }

  const dir = String(params.get('dir') || 'desc').toLowerCase() === 'asc' ? 'ASC' : 'DESC';
  const orderField = String(params.get('order') || 'updatedAt');
  if (!spec.order.includes(orderField)) throw new QueryError(`Cannot order by ${orderField}`);
  const order = orderExpr(orderField);

  const rawLimit = Number(params.get('limit') || DEFAULT_LIMIT);
  const limit = Number.isFinite(rawLimit) ? Math.max(1, Math.min(MAX_LIMIT, Math.trunc(rawLimit))) : DEFAULT_LIMIT;

  const cursor = decodeCursor(params.get('after'));
  if (cursor) {
    // Keyset pagination: strictly after the last document of the previous page, ties broken by path.
    where.push(dir === 'DESC'
      ? `(${order} < ? OR (${order} = ? AND path > ?))`
      : `(${order} > ? OR (${order} = ? AND path < ?))`);
    const value = orderField === 'updatedAt' ? Number(cursor.v) || 0 : cursor.v;
    binds.push(value, value, cursor.p);
  }

  const whereSql = ` WHERE ${where.join(' AND ')}`;
  if (count) {
    return {
      sql: `SELECT COUNT(*) AS total FROM (SELECT path FROM friendbook_cf_documents${whereSql} LIMIT ${COUNT_CAP})`,
      binds, limit, orderField, dir, order,
    };
  }
  return {
    sql: `SELECT path, doc_id, document_json, updated_at, ${order} AS order_value`
      + ` FROM friendbook_cf_documents${whereSql}`
      + ` ORDER BY ${order} ${dir}, path ${dir === 'DESC' ? 'ASC' : 'DESC'} LIMIT ?`,
    binds: [...binds, limit + 1], limit, orderField, dir, order,
  };
}

/**
 * Applies field operations to a document and returns the new document.
 *
 * The write itself is guarded by compare-and-swap on the stored JSON (see app-documents.js), so a
 * counter increment or an array-union can never clobber a concurrent writer: whoever loses the swap
 * re-reads and re-applies. Doing the arithmetic here instead of nesting SQLite json expressions keeps
 * every operation exact - and keeps the SQL to a single bound statement with no duplicated binds.
 *
 *   set    {field: value}    replace fields, keeping their JSON type
 *   inc    {field: n}        add n (numbers only, bounded)
 *   add    {field: [values]} array-union, de-duplicated
 *   remove {field: [values]} array-remove
 *   unset  [fields]          drop fields
 *   stamp  [fields]          server timestamp (the Worker's clock, never the client's)
 */
export function applyOps(doc, ops, now) {
  const out = { ...(doc || {}) };
  const field = (name) => {
    if (!/^[A-Za-z_][A-Za-z0-9_]{0,39}$/.test(name)) throw new QueryError('Invalid field name');
    return name;
  };
  const valuesOf = (raw) => (Array.isArray(raw) ? raw : [raw]).filter(v => v !== null && v !== undefined);

  if (ops && Array.isArray(ops.stamp)) {
    for (const name of ops.stamp.slice(0, 8)) out[field(name)] = now;
  }
  if (ops && ops.set && typeof ops.set === 'object' && !Array.isArray(ops.set)) {
    for (const [name, value] of Object.entries(ops.set).slice(0, 60)) {
      if (value === undefined) continue;
      out[field(name)] = value;
    }
  }
  if (ops && ops.inc && typeof ops.inc === 'object' && !Array.isArray(ops.inc)) {
    for (const [name, raw] of Object.entries(ops.inc).slice(0, 12)) {
      const delta = Number(raw);
      if (!Number.isFinite(delta) || Math.abs(delta) > 1_000_000) throw new QueryError('Invalid increment');
      const key = field(name);
      const current = Number(out[key]);
      out[key] = (Number.isFinite(current) ? current : 0) + delta;
    }
  }
  if (ops && ops.add && typeof ops.add === 'object' && !Array.isArray(ops.add)) {
    for (const [name, raw] of Object.entries(ops.add).slice(0, 8)) {
      const key = field(name);
      const list = Array.isArray(out[key]) ? [...out[key]] : [];
      for (const value of valuesOf(raw).slice(0, 50)) {
        if (!list.some(item => item === value)) list.push(value);
      }
      if (list.length > 5_000) throw new QueryError('Array too large');
      out[key] = list;
    }
  }
  if (ops && ops.remove && typeof ops.remove === 'object' && !Array.isArray(ops.remove)) {
    for (const [name, raw] of Object.entries(ops.remove).slice(0, 8)) {
      const key = field(name);
      const drop = valuesOf(raw).slice(0, 50);
      out[key] = (Array.isArray(out[key]) ? out[key] : []).filter(item => !drop.some(value => value === item));
    }
  }
  if (ops && Array.isArray(ops.unset)) {
    for (const name of ops.unset.slice(0, 12)) delete out[field(name)];
  }
  out.updatedAt = now;
  return out;
}

/** The document a set of operations produces when the row does not exist yet (create-on-patch). */
export function documentFromOps(ops, docId, now) {
  return applyOps({ id: docId, createdAt: now }, ops, now);
}

/** Validates a batch body: bounded, allowlisted, and each op carries its own collection and id. */
export function parseBatch(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) throw new QueryError('Batch object required');
  const ops = body.ops;
  if (!Array.isArray(ops) || !ops.length) throw new QueryError('Batch operations required');
  if (ops.length > MAX_OPS) throw new QueryError('Batch too large');
  let bytes = 0;
  for (const op of ops) {
    if (!op || typeof op !== 'object') throw new QueryError('Invalid batch operation');
    if (!['set', 'update', 'delete'].includes(op.op)) throw new QueryError('Unsupported batch operation');
    if (typeof op.collection !== 'string' || typeof op.id !== 'string') throw new QueryError('Batch operation needs collection and id');
    bytes += JSON.stringify(op).length;
    if (bytes > 400_000) throw new QueryError('Batch too large');
  }
  return ops;
}
