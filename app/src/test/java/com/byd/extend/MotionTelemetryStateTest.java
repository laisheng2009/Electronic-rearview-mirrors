package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public class MotionTelemetryStateTest {
    @Test public void invalidationRequiresFreshPowerAndLockBaseline() throws Exception {
        MotionConfig config = MotionConfig.parse("{\"warning\":true}");
        MotionTelemetryState state = new MotionTelemetryState();

        state.power(0, true);
        assertFalse(state.eligible(config));
        state.lock(2, true);
        assertTrue(state.eligible(config));

        state.lock(0, false);
        assertFalse(state.ready());
        assertEquals(0, state.power());
        assertEquals(-1, state.lock());

        state.power(0, true);
        state.lock(2, false);
        assertFalse(state.ready());
        state.lock(2, true);
        assertTrue(state.eligible(config));
    }

    @Test public void offModeNeedsOnlyPowerAndInvalidLockPreservesIt() throws Exception {
        MotionConfig off = MotionConfig.parse("{\"warning\":true,\"lock\":0}");
        MotionTelemetryState state = new MotionTelemetryState();

        state.power(0, true);
        assertTrue(state.eligible(off));
        state.lock(0, false);
        assertEquals(0, state.power());
        assertTrue(state.eligible(off));

        state.power(1, false);
        assertFalse(state.eligible(off));
        state.power(0, false);
        assertTrue(state.eligible(off));
    }

    @Test public void lockedModeRequiresBothBaselinesAndCurrentLockedState() throws Exception {
        MotionConfig locked = MotionConfig.parse("{\"warning\":true,\"lock\":1}");
        MotionTelemetryState state = new MotionTelemetryState();

        state.power(0, true);
        assertFalse(state.eligible(locked));
        state.lock(2, false);
        assertFalse(state.eligible(locked));
        state.lock(2, true);
        assertTrue(state.eligible(locked));
        state.lock(1, false);
        assertEquals(0, state.power());
        assertFalse(state.eligible(locked));
        state.lock(2, false);
        assertTrue(state.eligible(locked));
    }
}
