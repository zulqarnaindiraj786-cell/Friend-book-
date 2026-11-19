import { isPaymentAdmin } from './payment-access.js';

// Reports are untrusted submissions. Only the configured administrator may read or dismiss them.
const FIELDS = new Set(['uid','postId','reelId','commentId','messageId','chatId','targetId',
  'targetOwnerId','targetType','authorId','collection','type','category','reason','reportedBy','reporterId','createdAt']);
export function canReadReports(env, uid) { return isPaymentAdmin(env, uid); }
export function reportForCreate(uid, input, now) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return null;
  if (Object.keys(input).some(k => !FIELDS.has(k))) return null;
  if (input.reportedBy !== uid && input.reporterId !== uid) return null;
  if (input.reportedBy !== undefined && input.reportedBy !== uid) return null;
  if (input.reporterId !== undefined && input.reporterId !== uid) return null;
  for (const [key, value] of Object.entries(input)) {
    if (key === 'createdAt') continue;
    if (typeof value !== 'string' || value.length > (key === 'reason' ? 4000 : 512)) return null;
  }
  const { createdAt, ...safe } = input;
  return { ...safe, reportedBy: uid, createdAt: {__type:'timestamp', millis:now} };
}
