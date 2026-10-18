package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public class MotionWakeOwnershipTest {
    @Test public void sameOffProducerConsumesExactlyOneWake() {
        MotionWakeOwnership wake = new MotionWakeOwnership();
        wake.arm("session", 7, 100);
        assertTrue(wake.consume("session", 7, true, false, 200));
        assertFalse(wake.consume("session", 7, true, false, 201));
    }

    @Test public void unrelatedRecoveryCannotBeSuppressed() {
        MotionWakeOwnership wake = new MotionWakeOwnership();
        wake.arm("a", 7, 100);
        assertFalse(wake.consume("b", 7, true, false, 200));
        wake.arm("a", 7, 100);
        assertFalse(wake.consume("a", 8, true, false, 200));
        wake.arm("a", 7, 100);
        assertFalse(wake.consume("a", 7, false, false, 200));
        wake.arm("a", 7, 100);
        assertFalse(wake.consume("a", 7, true, true, 200));
        wake.arm("a", 7, 100);
        assertFalse(wake.consume("a", 7, true, false, 10_101));
    }

    @Test public void laterPowerOnRetiresOnlyTheRetainedProducerOnce() {
        MotionWakeOwnership wake = new MotionWakeOwnership();
        wake.arm("a", 7, 100);
        assertTrue(wake.consume("a", 7, true, false, 200));
        wake.clearPending();
        assertTrue(wake.takeRetainedForPowerOn(7));
        assertFalse(wake.takeRetainedForPowerOn(7));
        wake.arm("a", 7, 300);
        assertTrue(wake.consume("a", 7, true, false, 400));
        assertFalse(wake.takeRetainedForPowerOn(8));
    }
}
