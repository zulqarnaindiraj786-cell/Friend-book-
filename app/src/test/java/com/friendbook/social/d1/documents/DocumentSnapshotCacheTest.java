package com.friendbook.social.d1.documents;
import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;
public class DocumentSnapshotCacheTest {
 @Test public void cachedCopyPreservesDataButDoesNotPretendToBeServerFresh() {
  DocumentSnapshot fresh=new DocumentSnapshot(null,"users/a","a",Collections.singletonMap("name","A"),true,new SnapshotMetadata(false,false),100,200);
  DocumentSnapshot cached=fresh.asCached();
  assertTrue(cached.exists()); assertEquals("A",cached.getString("name"));
  assertTrue(cached.getMetadata().isFromCache()); assertFalse(fresh.getMetadata().isFromCache());
  assertEquals("users/a",cached.getPath());
 }
}
