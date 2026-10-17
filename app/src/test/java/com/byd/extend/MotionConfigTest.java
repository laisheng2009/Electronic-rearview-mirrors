package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public class MotionConfigTest {
    @Test public void defaultsAreOptInAndKeepIndependentBeforeAfter() throws Exception {
        MotionConfig defaults=MotionConfig.parse("{}");
        assertFalse(defaults.anyEnabled());
        assertFalse(defaults.audioEnabled);
        assertFalse(defaults.fallbackEnabled);
        assertEquals(1920,defaults.width());
        assertEquals(1080,defaults.height());
        MotionConfig selected=MotionConfig.parse("{\"warning\":true,\"enabled\":true,\"before\":2,\"after\":28,\"order\":\"3,2,1,0\"}");
        MotionConfig reopened=MotionConfig.parse(selected.toJson());
        assertTrue(reopened.warningEnabled);
        assertTrue(reopened.recordingEnabled);
        assertEquals(2,reopened.beforeSeconds);
        assertEquals(28,reopened.afterSeconds);
        assertArrayEquals(new int[]{3,2,1,0},reopened.cameraOrder);
    }

    @Test public void malformedOrderAndOutOfRangeValuesCannotReachEncoder() throws Exception {
        MotionConfig config=MotionConfig.parse("{\"before\":0,\"after\":99,\"quality\":9,\"internalLimit\":-2,\"order\":\"0,1,1,3\"}");
        assertEquals(1,config.beforeSeconds);
        assertEquals(30,config.afterSeconds);
        assertEquals(2560,config.width());
        assertEquals(1,config.internalLimitGb);
        assertArrayEquals(new int[]{0,1,2,3},config.cameraOrder);
    }

    @Test public void powerAndLockFiltersRequireActualEligibleValues() throws Exception {
        MotionConfig any=MotionConfig.parse("{\"enabled\":true}");
        MotionConfig locked=MotionConfig.parse("{\"enabled\":true,\"lock\":1}");
        MotionConfig unlocked=MotionConfig.parse("{\"warning\":true,\"lock\":2}");
        assertTrue(any.eligible(0,-1));
        assertFalse(any.eligible(-1,2));
        assertFalse(any.eligible(1,2));
        assertTrue(locked.eligible(0,2));
        assertFalse(locked.eligible(0,1));
        assertFalse(locked.eligible(0,-1));
        assertTrue(unlocked.eligible(0,1));
        assertFalse(unlocked.eligible(0,2));
        assertFalse(MotionConfig.parse("{}").eligible(0,2));
    }
}
