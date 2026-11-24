package com.friendbook.social.d1.documents;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=28,application=android.app.Application.class,manifest=org.robolectric.annotation.Config.NONE)
public class FriendshipFactsResponseTest {
 @Test public void acceptsOnlyCompleteBooleanFacts()throws Exception{
  List<String> ids=Arrays.asList("a","b");
  assertEquals(Map.of("a",true,"b",false),FriendshipFactsResponse.parse(new JSONObject("{\"facts\":[{\"uid\":\"b\",\"friend\":false},{\"uid\":\"a\",\"friend\":true}]}"),ids));
  for(String json:new String[]{"{}","{\"facts\":[]}","{\"facts\":[{\"uid\":\"a\",\"friend\":true},{\"uid\":\"a\",\"friend\":false}]}","{\"facts\":[{\"uid\":\"a\",\"friend\":true},{\"uid\":\"other\",\"friend\":false}]}","{\"facts\":[{\"uid\":\"a\",\"friend\":true},{\"uid\":\"b\",\"friend\":\"false\"}]}"}){
   try{FriendshipFactsResponse.parse(new JSONObject(json),ids);fail("accepted "+json);}catch(IllegalArgumentException expected){}
  }
 }
}
