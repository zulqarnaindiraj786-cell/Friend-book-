package com.friendbook.social.core;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class FriendCandidatePagesTest {
    @Test public void includesBothLegacyParticipantOrdersInBoundedProbe(){
        List<List<String>> result=FriendCandidatePages.pairs("me",Arrays.asList("a","b"));
        assertEquals(Arrays.asList(Arrays.asList("me","a"),Arrays.asList("a","me"),Arrays.asList("me","b"),Arrays.asList("b","me")),result);
    }
    @Test(expected=IllegalArgumentException.class)public void refusesOversizedProbe(){FriendCandidatePages.pairs("me",Collections.nCopies(11,"other"));}
    @Test public void unresolvedOrExistingRelationshipsNeverEnableRequest(){
        for(int mask=0;mask<16;mask++)assertEquals(mask==1,FriendCandidatePages.canOfferRequest((mask&1)!=0,(mask&2)!=0,(mask&4)!=0,(mask&8)!=0));
    }
    @Test public void fiveThousandVirtualSuggestionPagesStayWithinBatchBudget(){
        List<String> candidates=new ArrayList<>();for(int i=0;i<10;i++)candidates.add("candidate"+i);
        for(int user=0;user<5000;user++)assertEquals(20,FriendCandidatePages.pairs("viewer"+user,candidates).size());
    }
}
