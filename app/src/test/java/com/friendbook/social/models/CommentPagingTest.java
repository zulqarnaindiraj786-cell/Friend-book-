package com.friendbook.social.models;
import com.friendbook.social.post.CommentsEngine;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class)
public class CommentPagingTest {
    private Map<String,Object> comment(String id,String text,long time) {
        Map<String,Object> m=new HashMap<>();m.put("id",id);m.put("authorId","alice");m.put("content",text);m.put("createdAt",time);return m;
    }
    @Test public void boundedPreviewWinsOverDuplicateFrozenLegacyRecords() {
        Map<String,Object> parent=new HashMap<>();parent.put("lastComment",comment("c1","Edited",20));
        parent.put("commentsMap",Collections.singletonMap("c1",comment("c1","Old",10)));
        parent.put("commentsList",Arrays.asList(comment("c1","Oldest",5),comment("c2","Second",15)));
        List<Models.Comment> out=Models.Comment.parseEmbeddedComments("p1",parent,"c1");
        assertEquals(2,out.size());assertEquals("c2",out.get(0).id);assertEquals("Edited",out.get(1).content);
        assertEquals("p1",out.get(1).postId);assertTrue(out.get(1).pinned);
    }
    @Test public void legacyMapRemainsUnmodifiedAndCanonicalMapKeepsMentionsAndIdentity() {
        Map<String,Object> old=comment("c1","@Ali",123);Map<String,Object> mention=new HashMap<>();
        mention.put("uid","ali");mention.put("label","@Ali");mention.put("start",0);mention.put("end",4);old.put("mentions",Collections.singletonList(mention));
        Models.Comment c=Models.Comment.fromMap("c1",old);Map<String,Object> stored=CommentsEngine.toMap(c,"post1");
        assertEquals("post1",stored.get("postId"));assertEquals("alice",stored.get("authorId"));
        assertEquals(123L,stored.get("createdAt"));assertEquals(c.mentions,stored.get("mentions"));
        assertFalse(c.canonicalStored);assertFalse(old.containsKey("postId"));
    }
    @Test public void recordsWithoutLegacyListIdentityAreNotRendered() {
        Map<String,Object> parent=new HashMap<>();parent.put("commentsList",Collections.singletonList(comment("","Unknown",10)));
        assertTrue(Models.Comment.parseEmbeddedComments("post1",parent,"").isEmpty());
    }
}
