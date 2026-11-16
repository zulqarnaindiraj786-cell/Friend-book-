/**
 * Utility functions for Cloudflare Workers
 */

export const json = (body, status = 200, headers = {}) => Response.json(body, {
  status,
  headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', ...headers },
});

/**
 * Verify Firebase ID token and extract user identity
 * Uses the Firebase Admin SDK approach via REST API
 */
export async function verifyAuth(request, env) {
  const authHeader = request.headers.get('Authorization');
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return null;
  }
  
  const token = authHeader.slice(7);
  
  try {
    // Verify token with Firebase Auth REST API
    const response = await fetch(
      `https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=${env.FIREBASE_API_KEY}`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ idToken: token }),
      }
    );
    
    if (!response.ok) return null;
    
    const data = await response.json();
    if (!data.users || !data.users.length) return null;
    
    const user = data.users[0];
    return {
      uid: user.localId,
      email: user.email,
      emailVerified: user.emailVerified,
      displayName: user.displayName,
      photoUrl: user.photoUrl,
      phoneNumber: user.phoneNumber,
      providerId: user.providerUserInfo?.[0]?.providerId,
    };
  } catch (error) {
    console.error('Auth verification failed:', error);
    return null;
  }
}

/**
 * Generate a unique ID
 */
export function generateId() {
  return crypto.randomUUID();
}

/**
 * Current timestamp in milliseconds
 */
export function now() {
  return Date.now();
}

/**
 * Parse JSON safely
 */
export function safeParse(jsonString, defaultValue = null) {
  try {
    return JSON.parse(jsonString);
  } catch {
    return defaultValue;
  }
}

/**
 * Stringify JSON safely
 */
export function safeStringify(obj, defaultValue = '{}') {
  try {
    return JSON.stringify(obj);
  } catch {
    return defaultValue;
  }
}

/**
 * Paginate results with cursor
 */
export function paginate(results, limit, cursorField = 'created_at') {
  let hasMore = results.length > limit;
  const items = hasMore ? results.slice(0, limit) : results;
  const nextCursor = hasMore ? items[items.length - 1][cursorField] : null;
  return { items, nextCursor, hasMore };
}

/**
 * Validate required fields
 */
export function validateRequired(data, fields) {
  const missing = fields.filter(f => data[f] === undefined || data[f] === null || data[f] === '');
  return missing.length === 0 ? null : missing;
}

/**
 * Sanitize string input
 */
export function sanitize(str, maxLength = 1000) {
  if (typeof str !== 'string') return '';
  return str.slice(0, maxLength).trim();
}

/**
 * Check if user is admin
 */
export async function isAdmin(env, uid) {
  const user = await env.DB.prepare('SELECT is_admin FROM users WHERE uid = ?').bind(uid).first();
  return user?.is_admin === 1;
}

/**
 * Check if user is moderator
 */
export async function isModerator(env, uid) {
  const user = await env.DB.prepare('SELECT is_moderator FROM users WHERE uid = ?').bind(uid).first();
  return user?.is_moderator === 1;
}

/**
 * Rate limiting helper (simple in-memory)
 */
const rateLimits = new Map();

export function checkRateLimit(uid, action, maxRequests = 60, windowMs = 60000) {
  const key = `${uid}:${action}`;
  const now = Date.now();
  const limit = rateLimits.get(key) || { count: 0, resetAt: now + windowMs };
  
  if (now > limit.resetAt) {
    limit.count = 0;
    limit.resetAt = now + windowMs;
  }
  
  limit.count++;
  rateLimits.set(key, limit);
  
  return {
    allowed: limit.count <= maxRequests,
    remaining: Math.max(0, maxRequests - limit.count),
    resetAt: limit.resetAt,
  };
}