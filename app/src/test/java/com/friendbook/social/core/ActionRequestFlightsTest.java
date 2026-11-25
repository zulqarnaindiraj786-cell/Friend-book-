package com.friendbook.social.core;

import com.google.android.gms.tasks.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class ActionRequestFlightsTest {
 @Test public void hundredOverlapsAndCompletedDoubleTapShareOneAction(){
  AtomicLong now=new AtomicLong(1000);AtomicInteger calls=new AtomicInteger();
  ActionRequestFlights<String,Void> flights=new ActionRequestFlights<>(now::get);
  TaskCompletionSource<Void> pending=new TaskCompletionSource<>();
  java.util.concurrent.Callable<Task<Void>> start=()->{calls.incrementAndGet();return pending.getTask();};
  Task<Void> first=flights.run("actor/follow/target",start);
  for(int i=0;i<100;i++)assertSame(first,flights.run("actor/follow/target",start));
  assertEquals(1,calls.get());pending.setResult(null);
  assertSame(first,flights.run("actor/follow/target",start));
  now.addAndGet(1000);flights.run("actor/follow/target",start);assertEquals(2,calls.get());
 }
 @Test public void actorAndTargetAreIsolatedAndFailuresCanRetryAfterCooldown(){
  AtomicLong now=new AtomicLong(1000);AtomicInteger calls=new AtomicInteger();
  ActionRequestFlights<String,Void> flights=new ActionRequestFlights<>(now::get);
  java.util.concurrent.Callable<Task<Void>> start=()->{calls.incrementAndGet();return Tasks.forException(new Exception("offline"));};
  assertFalse(flights.run("a/x",start).isSuccessful());flights.run("b/x",start);flights.run("a/y",start);
  flights.run("a/x",start);assertEquals(3,calls.get());now.addAndGet(1000);flights.run("a/x",start);assertEquals(4,calls.get());
 }
 @Test public void cooldownBoundaryAndActorIsolation(){
  InteractionCooldown gate=new InteractionCooldown();
  assertTrue(gate.acquireAt("a","post",1000));
  for(int i=0;i<100;i++)assertFalse(gate.acquireAt("a","post",1999));
  assertTrue(gate.acquireAt("b","post",1999));assertTrue(gate.acquireAt("a","post",2000));
 }
}
