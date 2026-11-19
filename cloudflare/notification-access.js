// Private inbox policy shared by both document APIs. Unknown recipient or actor fails closed.
export function canReadNotification(path, uid, data) {
  const parts = path.split('/');
  return parts.length !== 2 || parts[0] !== 'notifications' || data?.toUserId === uid;
}
export function notificationWriteAllowed(path, uid, before, after) {
  const parts = path.split('/');
  if (parts.length !== 2 || parts[0] !== 'notifications') return true;
  if (!before) return !parts[1].startsWith('broadcast_') && !!after && after.fromUserId === uid && typeof after.toUserId === 'string'
    && after.toUserId.length > 0 && after.toUserId.length <= 512
    && !/[\\/\x00-\x1f]/.test(after.toUserId) && after.isRead === false;
  if (before.toUserId !== uid) return false;
  if (!after) return true;
  for (const key of new Set([...Object.keys(before), ...Object.keys(after)])) {
    if (key !== 'isRead' && JSON.stringify(before[key]) !== JSON.stringify(after[key])) return false;
  }
  return typeof after.isRead === 'boolean';
}
