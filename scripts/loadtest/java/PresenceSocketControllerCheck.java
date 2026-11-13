import com.friendbook.social.core.PresenceSocketController;
import java.util.*;
/** Executable coordinator tests using fake clock, auth, scheduler and sockets. Not Android/socket I/O. */
public final class PresenceSocketControllerCheck {
 static void check(boolean ok){if(!ok)throw new AssertionError("Socket coordinator regression");}
 static class Socket implements PresenceSocketController.Connection {
  int closed; boolean failSend; List<String> sent=new ArrayList<>();
  public boolean send(String text){if(failSend)return false;sent.add(text);return true;}
  public void close(){closed++;}
 }
 static class Job implements PresenceSocketController.Cancel {
  long at;Runnable run;boolean cancelled;
  Job(long at,Runnable run){this.at=at;this.run=run;}
  public void cancel(){cancelled=true;}
 }
 static class Attempt {
  Socket socket=new Socket();PresenceSocketController.Events events;
  Attempt(PresenceSocketController.Events events){this.events=events;}
  void open(){events.opened(socket);}
 }
 static class Port implements PresenceSocketController.Runtime {
  long now;String actor="a";List<Job> jobs=new ArrayList<>();
  List<PresenceSocketController.TokenResult> tokens=new ArrayList<>();List<Attempt> attempts=new ArrayList<>();
  public long elapsed(){return now;}
  public double random(){return 1;}
  public boolean currentActor(String uid){return uid.equals(actor);}
  public PresenceSocketController.Cancel later(long delay,Runnable task){Job j=new Job(now+delay,task);jobs.add(j);return j;}
  public void token(String uid,PresenceSocketController.TokenResult result){check(uid.equals(actor));tokens.add(result);}
  public PresenceSocketController.Connection connect(String token,PresenceSocketController.Events events){
   check("synthetic-token".equals(token));Attempt a=new Attempt(events);attempts.add(a);return a.socket;
  }
  void answer(long lease){tokens.get(tokens.size()-1).ready("synthetic-token",lease);}
  void advance(long delta){
   long target=now+delta;int steps=0;
   while(true){Job next=null;for(Job j:jobs)if(!j.cancelled&&j.at<=target&&(next==null||j.at<next.at))next=j;
    if(next==null)break;check(++steps<100);next.cancelled=true;now=next.at;next.run.run();}
   now=target;
  }
 }
 public static void main(String[] args){
  Port p=new Port();PresenceSocketController c=new PresenceSocketController(p);
  c.update("a",true,true);check(p.tokens.size()==1);p.answer(3600000);Attempt a=p.attempts.get(0);a.open();
  check(a.socket.sent.size()==1);a.open();check(a.socket.closed==0&&a.socket.sent.size()==1);
  c.update("a",true,true);check(p.tokens.size()==1);p.advance(180000);check(a.socket.sent.size()==2);
  c.update("a",false,true);check(a.socket.closed==1&&a.socket.sent.get(2).contains("offline"));p.advance(180000);check(p.tokens.size()==1);
  c.update("a",true,true);PresenceSocketController.TokenResult old=p.tokens.get(1);c.stop();old.ready("synthetic-token",3600000);check(p.attempts.size()==1);
  p.actor="b";c.update("b",true,true);p.answer(3600000);Attempt b=p.attempts.get(1);b.open();
  a.open();check(b.socket.closed==0);c.update("b",true,false);check(b.socket.closed==1);

  p=new Port();c=new PresenceSocketController(p);c.update("a",true,true);p.answer(60000);p.advance(10000);a=p.attempts.get(0);a.open();
  p.advance(19999);check(p.tokens.size()==1);p.advance(1);check(p.tokens.size()==2&&a.socket.closed==1);

  p=new Port();c=new PresenceSocketController(p);c.update("a",true,true);old=p.tokens.get(0);p.advance(15000);c.update("a",true,true);
  check(p.tokens.size()==1);p.advance(1000);check(p.tokens.size()==2);old.ready("synthetic-token",3600000);check(p.attempts.isEmpty());
  p.answer(3600000);a=p.attempts.get(0);a.events.failed(a.socket,false);p.advance(60000);check(p.tokens.size()==2);

  p=new Port();c=new PresenceSocketController(p);c.update("a",true,true);p.answer(3600000);p.advance(15000);check(p.attempts.get(0).socket.closed==1);
  p.advance(1000);check(p.tokens.size()==2);c.stop();p.advance(60000);check(p.tokens.size()==2);

  p=new Port();c=new PresenceSocketController(p);c.update("a",true,true);p.answer(3600000);a=p.attempts.get(0);a.socket.failSend=true;a.open();
  p.advance(1000);check(p.tokens.size()==2);p.actor="b";p.answer(3600000);check(p.attempts.size()==1);
  System.out.println("PASS: token/WebSocket timeouts, heartbeats, refresh deadline, background/logout/privacy close, duplicate/stale callbacks, account switch, retry jitter/backoff and permanent refusal");
 }
}
