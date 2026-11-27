// SQL aliases only: no logging, identifiers, extra queries or persisted data.
// Both inputs are reduced to a fixed vocabulary before SQL interpolation.
const writers=new Set(['document','chat_root','chat_message','broadcast','legacy','batch']);
const roots=new Set(['users','posts','reels','stories','chats','messages','groups','pages','notifications','lives']);
export function writeAttribution(writer,path) {
  const family=writers.has(writer)?writer:'other';
  const root=typeof path==='string'?path.split('/')[0]:'';
  return `quota_writer_${family}_${roots.has(root)?root:'other'}`;
}
