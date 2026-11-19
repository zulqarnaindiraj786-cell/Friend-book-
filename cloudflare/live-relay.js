import { authorizeLiveMedia, coHostGrant, pkGuestGrant } from './live-access.js';

const MAX_PACKET = 96 * 1024;
const MAX_VIEWERS = 48;
const LEASE_MS = 5000;
const MAX_CONNECTION_MS = 60 * 60 * 1000;
const json = (error, status) => Response.json({error},{status,headers:{'Cache-Control':'no-store'}});

// A per-room relay. No audio/video is persisted in D1 or R2. Role and identity
// headers are supplied only by the authenticated Worker, never by the client.
export class LiveRelay {
  constructor(state, env) {
    this.env=env;
    this.publisher=null;
    this.coHost=null;
    this.pkGuest=null;
    this.coSequence=0;
    this.viewers=new Set();
  }
  async fetch(request) {
    const room=request.headers.get('X-Live-Room');
    const uid=request.headers.get('X-Live-Uid');
    const role=request.headers.get('X-Live-Role');
    if (request.headers.get('Upgrade')?.toLowerCase()!=='websocket') return json('WebSocket required',426);
    let grant=null;
    try {
      if (!(role==='cohost' ? (grant=await coHostGrant(this.env.DB,room,uid)) : role==='pkguest' ? (grant=await pkGuestGrant(this.env.DB,room,uid)) : await authorizeLiveMedia(this.env.DB,room,uid,role))) return json('Forbidden',403);
    } catch { return json('Live permissions unavailable',503); }
    const now=Date.now();
    if (this.publisher && now>=this.publisher.expires) this.disconnect(this.publisher);
    for (const viewer of this.viewers) if (now>=viewer.expires) this.disconnect(viewer);
    if (this.coHost && (now>=this.coHost.expires || (grant && grant!==this.coHost.grant))) this.disconnect(this.coHost);
    if (this.pkGuest && (now>=this.pkGuest.expires || (role==='pkguest' && grant!==this.pkGuest.grant))) this.disconnect(this.pkGuest);
    if (role==='pkguest' && this.pkGuest) return json('PK guest already connected',409);
    if (role==='cohost' && this.coHost) return json('Co-host already connected',409);
    if (role==='publish' && this.publisher) return json('Publisher already connected',409);
    if (role==='watch' && this.viewers.size>=MAX_VIEWERS) return json('Room at capacity',429);
    const [client,socket]=Object.values(new WebSocketPair());
    const entry={socket,room,uid,role,grant,expires:Date.now()+LEASE_MS,deadline:Date.now()+MAX_CONNECTION_MS,nextFrame:0,closed:false};
    const release=()=>{
      entry.closed=true;
      if (entry===this.publisher) this.publisher=null;
      if (entry===this.coHost) this.coHost=null;
    if (entry===this.pkGuest) this.pkGuest=null;
      this.viewers.delete(entry);
    };
    socket.accept();
    if (role==='publish') this.publisher=entry;
    else if (role==='cohost') this.coHost=entry;
    else if (role==='pkguest') this.pkGuest=entry;
    else this.viewers.add(entry);
    socket.addEventListener('close',release);
    socket.addEventListener('error',release);
    socket.addEventListener('message',event=>{
      if (entry.closed) return;
      if (event.data==='renew') {
        this.renew(entry).catch(()=>this.disconnect(entry));
        return;
      }
      if ((entry!==this.publisher && entry!==this.coHost && entry!==this.pkGuest) || typeof event.data!=='string' || event.data.length>MAX_PACKET || Date.now()>=entry.expires) {
        this.disconnect(entry); return;
      }
      const now=Date.now();
      if (now<entry.nextFrame) return;
      entry.nextFrame=now+25;
      if (new TextEncoder().encode(event.data).length>MAX_PACKET) { this.disconnect(entry); return; }
      // Only relay media; publishers cannot inject server control packets.
      let media;
      try { media=JSON.parse(event.data); } catch { return; }
      if (media?.kind!=='video' && media?.kind!=='audio') return;
      const coHost=entry===this.coHost || entry===this.pkGuest;
      const packet=coHost ? JSON.stringify({kind:`${entry===this.pkGuest?'pk':'co'}_${media.kind}`,data:media.data,seq:(this.coSequence=Math.max(now*100,this.coSequence+1))}) : event.data;
      const recipients=coHost && this.publisher ? [...this.viewers,this.publisher] : this.viewers;
      for (const viewer of recipients) {
        if (viewer.uid===entry.uid) continue;
        if (now>=viewer.expires || viewer.socket.bufferedAmount>MAX_PACKET*2) { this.disconnect(viewer); continue; }
        try { viewer.socket.send(packet); } catch { this.disconnect(viewer); }
      }
    });
    return new Response(null,{status:101,webSocket:client});
  }
  async renew(entry) {
    const now=Date.now();
    if (now>=entry.deadline || now>=entry.expires) { this.disconnect(entry); return; }
    if (entry.renewing || entry.expires-now>3500) return;
    entry.renewing=true;
    try {
      const allowed=entry.role==='cohost' ? await coHostGrant(this.env.DB,entry.room,entry.uid)===entry.grant
        : entry.role==='pkguest' ? await pkGuestGrant(this.env.DB,entry.room,entry.uid)===entry.grant
        : await authorizeLiveMedia(this.env.DB,entry.room,entry.uid,entry.role);
      if (!allowed || Date.now()>=entry.expires) this.disconnect(entry);
      else if (!entry.closed) {
        entry.expires=now+LEASE_MS;
        entry.socket.send(JSON.stringify({kind:'audience',count:this.viewerCount(Date.now())}));
      }
    } finally { entry.renewing=false; }
  }
  viewerCount(now) {
    const accounts=new Set();
    for (const entry of this.viewers) {
      if (!entry.closed && now<entry.expires && now<entry.deadline && entry.uid!==this.publisher?.uid) accounts.add(entry.uid);
    }
    return accounts.size;
  }
  disconnect(entry) {
    entry.closed=true;
    if (entry===this.publisher) this.publisher=null;
    if (entry===this.coHost) this.coHost=null;
    if (entry===this.pkGuest) this.pkGuest=null;
    this.viewers.delete(entry);
    try { entry.socket.close(1008,'Live access expired'); } catch { /* already closed */ }
  }
}

export async function openLiveRelay(request, env, uid, room, role) {
  if (!env.LIVE_RELAY) return json('Live relay unavailable',503);
  if (request.method!=='GET') return json('Method not allowed',405);
  if (new URL(request.url).search) return json('Query parameters forbidden',400);
  if (request.headers.get('Upgrade')?.toLowerCase()!=='websocket') return json('WebSocket required',426);
  const stub=env.LIVE_RELAY.get(env.LIVE_RELAY.idFromName(room));
  try { return await stub.fetch(new Request(request.url,{headers:{Upgrade:'websocket','X-Live-Room':room,'X-Live-Uid':uid,'X-Live-Role':role}})); }
  catch { return json('Live relay unavailable',503); }
}
