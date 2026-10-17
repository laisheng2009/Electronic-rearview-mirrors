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
        assertEquals(-1, state.power());
        assertEquals(-1, state.lock());

        state.power(0, true);
        state.lock(2, false);
        assertFalse(state.ready());
        state.lock(2, true);
        assertTrue(state.eligible(config));
    }
}
