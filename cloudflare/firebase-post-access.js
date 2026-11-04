// Live source authorization until a reviewed D1 ACL synchronization exists.
// Never trust an author/visibility/UID supplied by an Android request.
export async function authorizePost({ project, token, uid }, collection, id, fetcher = fetch, context) {
  const root = `https://firestore.googleapis.com/v1/projects/${encodeURIComponent(project)}/databases/(default)/documents`;
  const headers = { Authorization: `Bearer ${token}` };
  async function fields(path) {
    const r = await fetcher(`${root}/${path}`, { headers, signal: AbortSignal.timeout(8000) });
    if (!r.ok) throw Error('Source access unavailable');
    return (await r.json()).fields || {};
  }
  const string = (f, key) => f[key]?.stringValue || '';
  const list = (f, key) => [...(f[key]?.arrayValue?.values || []),...(key==='blocked'?(f.blockedUsers?.arrayValue?.values||[]):[])].map(v => v.stringValue);
  const post = await fields(`${collection}/${encodeURIComponent(id)}`);
  const owner = string(post, 'authorId');
  if (!owner || post.archived?.booleanValue || post.trashedAt?.integerValue > 0) return false;
  const me = await fields(`users/${encodeURIComponent(uid)}`);
  if (me.banned?.booleanValue || me.deactivated?.booleanValue) return false;
  if(context)Object.assign(context,{owner,name:string(me,'displayName'),photo:string(me,'photoUrl'),blocked:list(me,'blocked'),moderator:!!(me.isAdmin?.booleanValue||me.isModerator?.booleanValue)});
  const groupId=string(post,'groupId');
  if(groupId){const group=await fields(`groups/${encodeURIComponent(groupId)}`);
    if(list(group,'bannedMemberIds').includes(uid))return false;
    const manager=string(group,'adminId')===uid||list(group,'adminIds').includes(uid)||list(group,'moderatorIds').includes(uid);
    if(context&&manager)context.moderator=true;
    const privacy=(string(group,'privacy')||'').toLowerCase();
    if(privacy!=='public' && !(privacy==='private'&&(manager||list(group,'members').includes(uid))))return false;
  }
  if (owner === uid) return true;
  const author = await fields(`users/${encodeURIComponent(owner)}`);
  if (author.banned?.booleanValue || author.deactivated?.booleanValue || list(me, 'blocked').includes(owner) || list(author, 'blocked').includes(uid)) return false;
  if (post.privacy && typeof post.privacy.stringValue !== 'string') return false;
  const privacy = (string(post, 'privacy') || 'Public').toLowerCase();
  if (privacy === 'public') return true;
  if (privacy !== 'friends') return false;
  const array = values => ({ arrayValue: { values: values.map(stringValue => ({ stringValue })) } });
  const r = await fetcher(`${root}:runQuery`, {
    method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' }, signal: AbortSignal.timeout(8000),
    body: JSON.stringify({ structuredQuery: { from: [{ collectionId: 'friends' }],
      where: { fieldFilter: { field: { fieldPath: 'participants' }, op: 'IN',
        value: { arrayValue: { values: [array([uid, owner]), array([owner, uid])] } } } }, limit: 1 } }),
  });
  if (!r.ok) throw Error('Friendship verification unavailable');
  return (await r.json()).some(row => row.document?.fields);
}
