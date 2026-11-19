import { isPaymentAdmin } from './payment-access.js';

// Reports are untrusted submissions. Only the configured administrator may read or dismiss them.
const FIELDS = new Set(['uid','postId','reelId','commentId','messageId','chatId','targetId',
  'targetOwnerId','targetType','authorId','collection','type','category','reason','reportedBy','reporterId','createdAt']);
export function canReadReports(env, uid) { return isPaymentAdmin(env, uid); }
// Feedback is a private admin inbox, not an editable user profile or public collection.
export function feedbackForCreate(uid, input, now) {
  if (!input || typeof input!=='object' || Array.isArray(input) || input.uid!==uid) return null;
  if (Object.keys(input).some(k=>!['uid','name','text','app','createdAt'].includes(k))) return null;
  if (typeof input.text!=='string' || !input.text.trim() || input.text.length>4000) return null;
  for (const key of ['name','app']) if (input[key]!==undefined && (typeof input[key]!=='string' || input[key].length>256)) return null;
  return {uid,name:input.name||'',text:input.text,app:input.app||'',createdAt:{__type:'timestamp',millis:now}};
}
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
