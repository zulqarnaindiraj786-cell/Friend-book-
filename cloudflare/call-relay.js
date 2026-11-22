import { authorizeCallMedia } from './call-access.js';
const MAX=98304;
export class CallRelay {
  constructor(state,env) { this.state=state; this.env=env; }
  async allowed(chat,call,uid) {
    // authorizeCallMedia already reads and checks both current accounts,
    // including bans, suspensions and blocks, on every renewal. A separate
    // accountStatus lookup would reread the same caller on every heartbeat.
    return authorizeCallMedia(this.env.DB,chat,call,uid);
  }
  async fetch(request) {
    const {chat,call,uid}=Object.fromEntries(new URL(request.url).searchParams);
    if (request.headers.get('Upgrade')?.toLowerCase()!=='websocket') return new Response(null,{status:426});
    const type=await this.allowed(chat,call,uid);
    if (!type) return new Response(null,{status:403});
    for (const socket of this.state.getWebSockets(uid)) socket.close(1000,'Replaced');
    const [client,server]=Object.values(new WebSocketPair());
    this.state.acceptWebSocket(server,[uid]);
    server.serializeAttachment({chat,call,uid,type,until:Date.now()+12000,checked:Date.now(),window:0,count:0});
    return new Response(null,{status:101,webSocket:client});
  }
  async webSocketMessage(socket,message) {
    const a=socket.deserializeAttachment(), now=Date.now();
    if (now>=a.until) { socket.close(1008,'Expired'); return; }
    if (message==='renew') {
      if (now-a.checked<3500) return;
      a.checked=now; socket.serializeAttachment(a);
      try {
        if (!await this.allowed(a.chat,a.call,a.uid)) { socket.close(1008,'Call ended'); return; }
        const current=socket.deserializeAttachment();
        current.until=Date.now()+12000; socket.serializeAttachment(current);
      } catch { socket.close(1011,'Unavailable'); }
      return;
    }
    if (typeof message!=='string' || message.length>MAX) return;
    if (now-a.window>=1000) { a.window=now; a.count=0; }
    if (a.count>=40) return;
    a.count++; socket.serializeAttachment(a);
    let packet; try { packet=JSON.parse(message); } catch { return; }
    const {kind,data,seq}=packet||{};
    if (!Number.isSafeInteger(seq) || seq<1 || typeof data!=='string' || !/^[A-Za-z0-9+/]+={0,2}$/.test(data)
        || !(kind==='audio' ? data.length<=16000 : a.type==='video' && (kind==='video' || (kind==='camera' && data==='AA==')))) return;
    for (const peer of this.state.getWebSockets()) {
      const p=peer.deserializeAttachment();
      if (p.uid!==a.uid && now<p.until && peer.readyState===1) {
        try { peer.send(JSON.stringify({kind,data,seq})); } catch { peer.close(1011,'Unavailable'); }
      }
    }
  }
  webSocketClose(socket,code) { socket.close(code===1005||code===1006?1000:code); }
}
