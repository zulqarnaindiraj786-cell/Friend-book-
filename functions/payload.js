'use strict';
function notificationData(n, id) {
  return {eventId: `notification_${id}`, notificationId: id, toUserId: n.toUserId || '',
    fromUserId: n.fromUserId || '', type: n.type || 'social', title: n.fromUserName || 'friendbook',
    body: n.text || 'New notification', postId: n.postId || '', contentType: n.contentType || '',
    chatId: n.chatId || '', isGroup: String(n.isGroup === true)};
}
function canSharePost(p) {
  return !!p.authorId && !p.groupId && !p.pageId && !p.safetyFlagged &&
    ['public', 'friends'].includes(String(p.privacy || 'Public').toLowerCase());
}
module.exports = {notificationData, canSharePost};
