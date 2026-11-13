'use strict';
const {onDocumentCreated} = require('firebase-functions/v2/firestore');
const {initializeApp} = require('firebase-admin/app');
const {getFirestore, FieldValue} = require('firebase-admin/firestore');
const {getMessaging} = require('firebase-admin/messaging');
const {notificationData, canSharePost} = require('./payload');
initializeApp();
const db = getFirestore();
const options = document => ({document, region: 'us-central1', retry: true, maxInstances: 10});

// Data-only high priority wakes Android's messaging service in background as well as foreground.
// No notification payload: the client checks the current account, prefs and event dedup BEFORE displaying.
async function send(uid, data) {
  if (!uid) return;
  const [user, devices] = await Promise.all([
    db.doc(`users/${uid}`).get(), db.collection(`pushDevices/${uid}/devices`).get()
  ]);
  if (!user.exists || user.get('banned')) return;
  const blocked = user.get('blockedUsers') || user.get('blocked') || [];
  if (Array.isArray(blocked) && blocked.includes(data.fromUserId)) return;
  const docs = devices.docs.filter(d => typeof d.get('token') === 'string' && d.get('token'));
  for (let i = 0; i < docs.length; i += 500) {
    const batch = docs.slice(i, i + 500);
    const response = await getMessaging().sendEachForMulticast({
      tokens: batch.map(d => d.get('token')), data,
      android: {priority: 'high', ttl: 24 * 60 * 60 * 1000}
    });
    let transient = false;
    await Promise.all(response.responses.map(async (r, j) => {
      if (r.success) return;
      const code = r.error?.code;
      if (['messaging/registration-token-not-registered', 'messaging/invalid-registration-token'].includes(code)) {
        // Rotation may race delivery: do not delete a newer token.
        await db.runTransaction(async tx => {
          const latest = await tx.get(batch[j].ref);
          if (latest.get('token') === batch[j].get('token')) tx.delete(batch[j].ref);
        });
      } else { console.error('FCM delivery failure', uid, code); transient = true; }
    }));
    if (transient) throw new Error('Transient FCM failure; retry event');
  }
}
exports.socialNotification = onDocumentCreated(options('notifications/{id}'), async event => {
  const n = event.data.data();
  if (n.toUserId === n.fromUserId && n.type !== 'security') return;
  await send(n.toUserId, notificationData(n, event.params.id));
});
exports.chatMessage = onDocumentCreated(options('chats/{chatId}/messages/{messageId}'), async event => {
  const m = event.data.data();
  if (!m.senderId || m.isDeleted || m.deleted || m.type === 'system') return;
  const chat = await db.doc(`chats/${event.params.chatId}`).get();
  const members = chat.get('participants');
  if (!Array.isArray(members) || !members.includes(m.senderId)) return;
  for (const uid of members.filter(uid => uid !== m.senderId)) {
    const data = {eventId: `message_${event.params.chatId}_${event.params.messageId}`, toUserId: uid,
      fromUserId: m.senderId, type: 'message', title: m.senderName || 'New message',
      body: String(m.text || (m.type === 'text' ? 'Sent a message' : 'Sent an attachment')).slice(0, 500),
      chatId: event.params.chatId, isGroup: String(chat.get('isGroup') === true)};
    await send(uid, data);
  }
});
exports.friendPost = onDocumentCreated(options('posts/{postId}'), async event => {
  const p = event.data.data();
  if (!canSharePost(p)) return;
  const friends = await db.collection('friends').where('participants', 'array-contains', p.authorId).get();
  const recipients = new Set(friends.docs.flatMap(d => d.get('participants') || []).filter(uid => uid !== p.authorId));
  for (const uid of recipients) {
    // Deterministic document IDs deduplicate Firestore trigger retries and feed the same push path.
    const ref = db.collection('notifications').doc(`friendpost_${event.params.postId}_${uid}`);
    try {
      await ref.create({toUserId: uid, fromUserId: p.authorId, fromUserName: p.authorName || 'Your friend',
        fromUserPhotoUrl: p.authorPhotoUrl || '', type: 'friend_post', text: 'shared a new post',
        postId: event.params.postId, isRead: false, createdAt: FieldValue.serverTimestamp()});
    } catch (e) { if (e.code !== 6 && e.code !== 'already-exists') throw e; }
  }
});
