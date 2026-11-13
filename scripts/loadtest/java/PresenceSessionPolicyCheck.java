import com.friendbook.social.core.PresenceSessionPolicy;
/** Deterministic JVM policy checks only; no Android, Firebase or socket adapter. */
public final class PresenceSessionPolicyCheck {
 private static void check(boolean ok){if(!ok)throw new AssertionError("Presence lifecycle policy regression");}
 public static void main(String[] args){
  PresenceSessionPolicy p=new PresenceSessionPolicy();check(p.begin()==0);
  p.configure("a",true,false);check(p.begin()==0);p.configure("a",true,true);
  long a=p.begin();check(a>0&&p.begin()==0);check(p.configure(null,false,false));check(!p.opened(a,60000,0)&&p.failed(a)==-1);
  p.configure("a",true,true);a=p.begin();check(p.opened(a,60000,1000));
  check(p.canSend(a,30999)&&!p.canSend(a,31000)&&p.needsRefresh(31000)&&!p.canSend(a,999));
  check(!p.configure("a",true,true)&&p.begin()==0);
  check(p.configure("a",false,true)&&!p.canSend(a,1001));
  p.configure("b",true,true);long b=p.begin();check(!p.opened(a,60000,1001));check(p.opened(b,60000,1001));
  check(p.configure("b",true,false)&&!p.canSend(b,1002)&&p.begin()==0);
  p.configure("b",true,true);
  for(long delay:new long[]{1000,2000,4000,8000,16000,32000,60000,60000}){
   long t=p.begin();check(p.failed(t)==delay);check(p.retryIsCurrent(p.generation()));
  }
  long retry=p.generation();p.configure("c",true,true);check(!p.retryIsCurrent(retry));
  long t=p.begin();check(!p.opened(t,30000,1000));t=p.begin();check(p.opened(t,60000,1000));check(p.failed(t)==1000);
  System.out.println("PASS: foreground/privacy, logout/background, account switch, stale callbacks/retries, lease expiry, duplicate opens and capped reconnect backoff");
 }
}
