// Query engine for the Cloudflare D1 app-document store.
//
// The Android app was built against Firestore, where a query is
//   collection.where(f, op, v).orderBy(f, dir).limit(n).startAfter(cursor)
// The original /v1/docs endpoint could only list a whole collection (max 50, no
// filtering, no ordering, no pagination), so nothing but trivial screens could move
// off Firestore. This module implements the same shape of query against the
// friendbook_cf_documents table, using SQLite's JSON1 functions on document_json.
//
// Wire format (all values are bound parameters, never interpolated):
//   GET /v1/docs?collection=posts
//       &where=authorId:eq:abc            (repeatable)
//       &orderBy=createdAt:desc
//       &limit=20
//       &after=<opaque cursor>
//
// Supported operators: eq, ne, gt, gte, lt, lte, in, nin, array_contains,
// array_contains_any, exists, not_exists.

const MAX_LIMIT = 100;
const DEFAULT_LIMIT = 20;

// Field names are interpolated into a JSON path, so they must be strictly validated.
const FIELD_RE = /^[A-Za-z0-9_]{1,64}$/;

export class QueryError extends Error {
  constructor(message, status = 400) {
    super(message);
    this.status = status;
  }
}

function jsonPath(field) {
  if (!FIELD_RE.test(field)) throw new QueryError('Invalid field name');
  return `$.${field}`;
}

/** Field used for ordering must be a real, validated identifier. */
function orderField(field) {
  if (!FIELD_RE.test(field)) throw new QueryError('Invalid orderBy field');
  return field;
}

/**
 * Parses one `where` parameter of the form `field:op:value`.
 * `value` may itself contain colons, so only the first two are separators.
 */
export function parseWhere(raw) {
  if (typeof raw !== 'string' || raw.length === 0) throw new QueryError('Empty where clause');
  const first = raw.indexOf(':');
  if (first <= 0) throw new QueryError('Malformed where clause');
  const second = raw.indexOf(':', first + 1);
  if (second <= first + 1) throw new QueryError('Malformed where clause');

  const field = raw.slice(0, first);
  const op = raw.slice(first + 1, second);
  const value = raw.slice(second + 1);
  if (!FIELD_RE.test(field)) throw new QueryError('Invalid where field');

  const allowed = new Set(['eq', 'ne', 'gt', 'gte', 'lt', 'lte', 'in', 'nin',
    'array_contains', 'array_contains_any', 'exists', 'not_exists']);
  if (!allowed.has(op)) throw new QueryError('Unsupported operator: ' + op);
  return { field, op, value };
}

/** SQL fragment + bound params for one predicate. */
function predicateSql(predicate, params) {
  const path = jsonPath(predicate.field);
  const expr = `json_extract(document_json, '${path}')`;
  const { op, value } = predicate;

  switch (op) {
    case 'eq':
      // Compare as text so numbers/booleans/strings behave like Firebase equality
      // on the value the client sent.
      params.push(value);
      return `CAST(${expr} AS TEXT) = ?`;
    case 'ne':
      params.push(value);
      return `(CAST(${expr} AS TEXT) IS NULL OR CAST(${expr} AS TEXT) <> ?)`;
    case 'gt': case 'gte': case 'lt': case 'lte': {
      const sqlOp = { gt: '>', gte: '>=', lt: '<', lte: '<=' }[op];
      // Numeric first; fall back to text for ISO timestamps.
      const numeric = Number(value);
      if (Number.isFinite(numeric) && value.trim() !== '') {
        params.push(numeric);
        return `(CASE WHEN json_type(document_json, '${path}') IN ('integer','real') THEN ${expr} END) ${sqlOp} ?`;
      }
      params.push(value);
      return `CAST(${expr} AS TEXT) ${sqlOp} ?`;
    }
    case 'in': case 'nin': {
      const values = value.split(',').filter(v => v.length > 0);
      if (values.length === 0) throw new QueryError('Empty value list');
      if (values.length > 50) throw new QueryError('Too many values');
      const placeholders = values.map(() => '?').join(',');
      for (const v of values) params.push(v);
      const inner = `CAST(${expr} AS TEXT) IN (${placeholders})`;
      return op === 'in' ? inner : `NOT (${inner})`;
    }
    case 'array_contains':
      params.push(value);
      return `EXISTS (SELECT 1 FROM json_each(${expr}) WHERE CAST(json_each.value AS TEXT) = ?)`;
    case 'array_contains_any': {
      const values = value.split(',').filter(v => v.length > 0);
      if (values.length === 0) throw new QueryError('Empty value list');
      if (values.length > 50) throw new QueryError('Too many values');
      const placeholders = values.map(() => '?').join(',');
      const list = values.map(v => { params.push(v); return '?'; }).join(',');
      return `EXISTS (SELECT 1 FROM json_each(${expr}) WHERE CAST(json_each.value AS TEXT) IN (${list || placeholders}))`;
    }
    case 'exists':
      return `${expr} IS NOT NULL`;
    case 'not_exists':
      return `${expr} IS NULL`;
    default:
      throw new QueryError('Unsupported operator');
  }
}

export function parseOrderBy(raw) {
  if (!raw) return null;
  const [field, dirRaw] = String(raw).split(':');
  const dir = (dirRaw || 'asc').toLowerCase();
  if (dir !== 'asc' && dir !== 'desc') throw new QueryError('Invalid orderBy direction');
  return { field: orderField(field), dir };
}

export function encodeCursor(value, docId) {
  const payload = JSON.stringify({ v: value === undefined ? null : value, id: docId });
  // base64url without padding
  return btoa(payload).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function decodeCursor(raw) {
  if (!raw) return null;
  try {
    const b64 = String(raw).replace(/-/g, '+').replace(/_/g, '/');
    const padded = b64 + '='.repeat((4 - (b64.length % 4)) % 4);
    const parsed = JSON.parse(atob(padded));
    if (!parsed || typeof parsed !== 'object' || typeof parsed.id !== 'string') return null;
    return parsed;
  } catch {
    throw new QueryError('Invalid cursor');
  }
}

/**
 * Builds the SELECT for a query. Returns { sql, params } ready for db.prepare().
 * Ordering is always deterministic by (orderValue, doc_id) so cursors never skip
 * or repeat a document between pages.
 */
/**
 * Builds a COUNT over the same filters a query would use.
 *
 * <p>Counting by listing was the alternative, and it is what the app used to do: fetch up to a
 * page of documents and take the length. That reads every document to answer "how many", turns
 * a cheap question into an expensive one, and reports the page ceiling as the answer once the
 * real count is larger.
 */
export function buildCountQuery({ collection, wheres = [] }) {
  const params = [];
  const clauses = ['collection = ?'];
  params.push(collection);
  for (const raw of wheres) {
    const predicate = typeof raw === 'string' ? parseWhere(raw) : raw;
    clauses.push(predicateSql(predicate, params));
  }
  return {
    sql: `SELECT COUNT(*) AS total FROM friendbook_cf_documents WHERE ${clauses.join(' AND ')}`,
    params,
  };
}

export function buildQuery({ collection, wheres = [], orderBy = null, limit = DEFAULT_LIMIT, after = null }) {
  const params = [];
  const clauses = ['collection = ?'];
  params.push(collection);

  for (const raw of wheres) {
    const predicate = typeof raw === 'string' ? parseWhere(raw) : raw;
    clauses.push(predicateSql(predicate, params));
  }

  const sortField = orderBy ? orderField(orderBy.field) : 'updatedAt';
  const dir = orderBy ? orderBy.dir.toUpperCase() : 'DESC';
  const sortPath = `$.${sortField}`;
  const sortExpr = `json_extract(document_json, '${sortPath}')`;

  if (after) {
    const cursor = typeof after === 'string' ? decodeCursor(after) : after;
    if (cursor) {
      // Keyset pagination must mirror SQLite's own ordering, which is:
      //   ASC  -> NULL band first, then values ascending
      //   DESC -> values descending, then NULL band
      // The comparison uses the raw expression - the same one ORDER BY uses - so
      // numbers stay numeric and ISO timestamps stay textual.
      const cmp = dir === 'ASC' ? '>' : '<';
      if (cursor.v === null) {
        // Still inside the NULL band. ASC has the whole value band ahead of it;
        // DESC is at the very end, so only later doc_ids remain.
        clauses.push(dir === 'ASC'
          ? `((${sortExpr} IS NULL AND doc_id ${cmp} ?) OR ${sortExpr} IS NOT NULL)`
          : `(${sortExpr} IS NULL AND doc_id ${cmp} ?)`);
        params.push(cursor.id);
      } else {
        const valueSql = `(${sortExpr} ${cmp} ? OR (${sortExpr} = ? AND doc_id ${cmp} ?))`;
        // ASC: the value band only. DESC: the NULL band has not been reached yet.
        clauses.push(dir === 'ASC'
          ? `(${sortExpr} IS NOT NULL AND ${valueSql})`
          : `(${sortExpr} IS NULL OR ${valueSql})`);
        params.push(cursor.v, cursor.v, cursor.id);
      }
    }
  }

  const safeLimit = Math.max(1, Math.min(MAX_LIMIT, Number.isFinite(Number(limit)) ? Math.trunc(Number(limit)) : DEFAULT_LIMIT));
  // Fetch one extra row to learn whether another page exists without a COUNT.
  params.push(safeLimit + 1);

  const sql = `SELECT doc_id, document_json
    FROM friendbook_cf_documents
    WHERE ${clauses.join(' AND ')}
    ORDER BY ${sortExpr} ${dir}, doc_id ${dir}
    LIMIT ?`;
  return { sql, params, sortField, dir, limit: safeLimit };
}

export function parseQueryParams(url) {
  const search = url.searchParams;
  const collection = search.get('collection') || '';
  const wheres = search.getAll('where');
  const orderBy = parseOrderBy(search.get('orderBy'));
  const limitRaw = search.get('limit');
  const limit = limitRaw === null ? DEFAULT_LIMIT : Number(limitRaw);
  if (!Number.isFinite(limit) || limit < 1) throw new QueryError('Invalid limit');
  const after = search.get('after');
  return { collection, wheres, orderBy, limit, after };
}
