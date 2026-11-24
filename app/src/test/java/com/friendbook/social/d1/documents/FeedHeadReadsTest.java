package com.friendbook.social.d1.documents;
import com.friendbook.social.core.FeedHeadReads;

import com.friendbook.social.d1.documents.*;
import com.google.android.gms.tasks.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=28,application=android.app.Application.class,manifest=org.robolectric.annotation.Config.NONE)
public class FeedHeadReadsTest {
 static class Head extends Query {
  boolean reusable=true;int network;long age;
  QuerySnapshot page=new QuerySnapshot(Collections.emptyList(),null,new SnapshotMetadata(true,false));
  Head(){super(null,"posts");}
  @Override public boolean hasFreshLocalPage(long max){age=max;return reusable;}
  @Override public Task<QuerySnapshot> get(Source source){if(source!=Source.CACHE)network++;return Tasks.forResult(page);}
  @Override public Task<QuerySnapshot> refreshCachedHead(QuerySnapshot saved){network++;return Tasks.forResult(page);}
 }
 void drain(){org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();}
 @Test public void hundredWarmEntriesDoNotFetchAndStaleHeadIsShownBeforeOneRefresh(){
  Head q=new Head();List<String> events=new ArrayList<>();
  for(int i=0;i<100;i++)FeedHeadReads.load(q,false,()->true,(s,f)->events.add("cached"),s->events.add("server"),e->fail());
  drain();assertEquals(0,q.network);assertEquals(120000,q.age);assertEquals(100,events.size());
  events.clear();q.reusable=false;
  FeedHeadReads.load(q,false,()->true,(s,f)->events.add("cached"),s->events.add("server"),e->fail());
  drain();assertEquals(Arrays.asList("cached","server"),events);assertEquals(1,q.network);
 }
 @Test public void explicitRefreshBypassesCacheAndInactiveActorNeverFetches(){
  Head q=new Head();FeedHeadReads.load(q,true,()->true,(s,f)->fail(),s->{},e->fail());drain();assertEquals(1,q.network);
  FeedHeadReads.load(q,true,()->false,(s,f)->fail(),s->fail(),e->fail());drain();assertEquals(1,q.network);
 }
}
