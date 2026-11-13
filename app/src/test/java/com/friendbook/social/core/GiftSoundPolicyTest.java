package com.friendbook.social.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class GiftSoundPolicyTest {
    @Test public void recognizesCanonicalAndLegacyLionGift() {
        assertEquals("lion",GiftSoundPolicy.effect("lion","🦁","Golden Lion"));
        assertEquals("lion",GiftSoundPolicy.effect("","🦁","Gift"));
    }
    @Test public void recognizesFlightIdsAndExistingUniverseWithoutPriceChanges() {
        for(String id:new String[]{"plane","airplane","aeroplane","jet","universe","rocket"})
            assertEquals(id,"airplane",GiftSoundPolicy.effect(id,"","Gift"));
        assertEquals("airplane",GiftSoundPolicy.effect("","✈️","Gift"));
        assertEquals("airplane",GiftSoundPolicy.effect("","🚀","Gift"));
    }
    @Test public void otherGiftsHaveASoftGenericSound() {
        assertEquals("gift",GiftSoundPolicy.effect("rose","🌹","Rose"));
        assertEquals("gift",GiftSoundPolicy.effect(null,null,null));
    }
}
