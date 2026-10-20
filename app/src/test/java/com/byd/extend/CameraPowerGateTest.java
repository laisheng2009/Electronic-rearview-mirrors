package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public class CameraPowerGateTest {
    @Test public void confirmedOffBlocksUntilValidPowerReturns() {
        CameraSourceDemand.PowerGate gate = new CameraSourceDemand.PowerGate();
        assertFalse(gate.observe(-1));
        assertTrue(gate.ordinaryAllowed());
        assertTrue(gate.observe(0));
        assertFalse(gate.ordinaryAllowed());
        assertFalse(gate.observe(0));
        assertFalse(gate.observe(-1));
        assertFalse(gate.observe(99));
        assertFalse(gate.ordinaryAllowed());
        assertTrue(gate.observe(1)); // ACC is not vehicle OFF.
        assertTrue(gate.ordinaryAllowed());
        assertFalse(gate.observe(2));
    }

    @Test public void allKnownPoweredStatesReleaseTheGate() {
        CameraSourceDemand.PowerGate gate = new CameraSourceDemand.PowerGate();
        for (int raw = 1; raw <= 4; raw++) {
            assertTrue(gate.observe(0));
            assertTrue(gate.observe(raw));
            assertTrue(gate.ordinaryAllowed());
        }
    }
}
