package com.friendbook.social.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class TimeAgoUtilTest {
    private static final long NOW = 2_000_000_000_000L;
    @Test public void formatsMinuteBoundaries() {
        assertEquals("", TimeAgoUtil.formatLastSeen(0, NOW));
        assertEquals("Active now", TimeAgoUtil.formatLastSeen(NOW - 59_999, NOW));
        assertEquals("Active 1m ago", TimeAgoUtil.formatLastSeen(NOW - 60_000, NOW));
        assertEquals("28m", TimeAgoUtil.formatShortLastSeen(NOW - 28 * 60_000, NOW));
        assertEquals("Active 59m ago", TimeAgoUtil.formatLastSeen(NOW - 59 * 60_000, NOW));
    }
    @Test public void formatsHoursAndDaysWithoutWeeks() {
        assertEquals("Active 1h ago", TimeAgoUtil.formatLastSeen(NOW - 3_600_000, NOW));
        assertEquals("14h", TimeAgoUtil.formatShortLastSeen(NOW - 14 * 3_600_000, NOW));
        assertEquals("Active 23h ago", TimeAgoUtil.formatLastSeen(NOW - 23 * 3_600_000, NOW));
        assertEquals("Active 1d ago", TimeAgoUtil.formatLastSeen(NOW - 86_400_000, NOW));
        assertEquals("Active 7d ago", TimeAgoUtil.formatLastSeen(NOW - 7 * 86_400_000L, NOW));
        assertEquals("14d", TimeAgoUtil.formatShortLastSeen(NOW - 14 * 86_400_000L, NOW));
    }
    @Test public void clampsFutureClockSkewWithoutNegativeText() {
        assertEquals("Active now", TimeAgoUtil.formatLastSeen(NOW + 3000, NOW));
    }
}
