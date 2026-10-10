package com.byd.extend;

import static org.junit.Assert.*;

import org.junit.Test;

/** Exercises the real event policy and queue together, without Android or audio. */
public final class AvasAutomaticPlaybackIntegrationTest {

    @Test(timeout = 2000)
    public void twoEligibleEdgesFromOneSnapshotKeepPowerThenUnlockBeforeWorkerStarts()
            throws Exception {
        AvasEventPolicy policy = new AvasEventPolicy();
        AvasPlaybackQueue queue = new AvasPlaybackQueue();
        assertTrue(policy.sample(0, 2, 2, false, false).isEmpty());
        for (String profile : policy.sample(100, 0, 1, false, false)) {
            queue.enqueueExterior(profile, false);
        }
        assertEquals(2, queue.pendingCount());
        AvasPlaybackQueue.Request powerOff = queue.take();
        assertEquals("power_off", powerOff.profile);
        assertFalse(powerOff.cancelled.get());
        queue.finish(powerOff);
        AvasPlaybackQueue.Request unlock = queue.take();
        assertEquals("unlock", unlock.profile);
        assertFalse(unlock.cancelled.get());
        queue.close();
    }
}
