// Administrative authority is supplied by server configuration, never a profile field.
export function isPaymentAdmin(env, uid) {
  if (typeof uid !== 'string' || !uid || typeof env?.PAYMENT_ADMIN_UIDS !== 'string') return false;
  return env.PAYMENT_ADMIN_UIDS.split(',').map(s=>s.trim()).filter(Boolean).includes(uid);
}
export function canReadPayment(env,uid,data) {
  return !!data && (data.userId===uid || isPaymentAdmin(env,uid));
}
export function canCreateFreeReview(uid,data) {
  if (!data || data.userId!==uid || data.status!=='pending' || data.amount!==0 || data.currency!=='FREE'
      || !['tick_blue','tick_gold','tick_diamond'].includes(data.type)) return false;
  const keys=new Set(['userId','userEmail','type','amount','currency','status','screenshotUrl','date']);
  if (!Object.keys(data).every(k=>keys.has(k))) return false;
  for (const k of ['userEmail','screenshotUrl']) if (data[k]!==undefined && (typeof data[k]!=='string' || data[k].length>2048)) return false;
  return true;
}
