const {test} = require('node:test');
const assert = require('node:assert/strict');
const {notificationData, canSharePost} = require('./payload');
test('FCM data values are strings and preserve exact destinations', () => {
  const data = notificationData({toUserId:'u', fromUserId:'a', type:'reel_comment', postId:'r'}, 'n');
  assert.equal(data.postId, 'r'); assert.equal(data.eventId, 'notification_n');
  assert.ok(Object.values(data).every(x => typeof x === 'string'));
});
test('private, group, page and flagged posts never fan out', () => {
  for (const extra of [{privacy:'Only me'}, {groupId:'g'}, {pageId:'p'}, {safetyFlagged:true}])
    assert.equal(canSharePost({authorId:'a', ...extra}), false);
  assert.equal(canSharePost({authorId:'a', privacy:'Friends'}), true);
  assert.equal(canSharePost({authorId:'a', privacy:'Public'}), true);
});
