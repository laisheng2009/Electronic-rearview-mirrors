package com.byd.extend;

import static org.junit.Assert.*;

import org.junit.Test;

/** Exercises the real event policy and queue together, without Android or audio. */
public final class AvasAutomaticPlaybackIntegrationTest {

    @org.junit.Rule public final org.junit.rules.TemporaryFolder files = new org.junit.rules.TemporaryFolder();

    @Test public void powerOffSettingsAreExclusiveAndSurviveOtherProfileEdits() {
        AvasConfig.Profile original = new AvasConfig.Profile("power_off", true, false, 15,
                "", java.util.Collections.emptyList(), true);
        assertFalse(original.onlyWhenLockingPoweredOff);
        AvasConfig.Profile deferred = original.withSettings(true, false, 15, "", true, true);
        assertTrue(deferred.onlyWhenLockingPoweredOff);
        assertFalse(deferred.skipConcurrentLockUnlock);
        AvasConfig.Profile disabled = deferred.withSettings(false, false, 25, "")
                .withAssets(java.util.Collections.emptyList(), "");
        assertTrue(disabled.onlyWhenLockingPoweredOff);
        assertFalse(disabled.skipConcurrentLockUnlock);
        assertFalse(disabled.enabled);
        AvasConfig.Profile skip = disabled.withSettings(true, false, 25, "", true, false);
        assertTrue(skip.skipConcurrentLockUnlock);
        assertFalse(skip.onlyWhenLockingPoweredOff);
    }

    @Test public void deferredOffIsConsumedOnceAcrossHelperRecovery() throws Exception {
        java.io.File path = new java.io.File(files.getRoot(), "power-off.bin");
        AvasEventPolicy first = new AvasEventPolicy(new AvasPowerOffLatch(path, 10001));
        first.reconcile(2, 1);
        assertTrue(first.sample(100, 0, 1, false, false, true, true).isEmpty());
        assertTrue(new AvasPowerOffLatch(path, 10001).pending());
        AvasEventPolicy recovered = new AvasEventPolicy(new AvasPowerOffLatch(path, 10001));
        recovered.reconcile(0, 1);
        assertEquals(java.util.List.of("power_off"),
                recovered.sample(300_000, 0, 2, false, false, true, true));
        assertFalse(new AvasPowerOffLatch(path, 10001).pending());
        AvasEventPolicy afterConsumption = new AvasEventPolicy(new AvasPowerOffLatch(path, 10001));
        afterConsumption.reconcile(0, 2);
        assertEquals(java.util.List.of("unlock"),
                afterConsumption.sample(301_000, 0, 1, false, false, true, true));
        assertEquals(java.util.List.of("lock"),
                afterConsumption.sample(302_000, 0, 2, false, false, true, true));
    }

    @Test public void snapshotsNeverArmOrReplayAndUnavailableOffDoesNotSwallowLock() throws Exception {
        java.io.File path = new java.io.File(files.getRoot(), "power-off.bin");
        AvasEventPolicy policy = new AvasEventPolicy(new AvasPowerOffLatch(path, 10001));
        policy.reconcile(0, 1);
        assertEquals(java.util.List.of("lock"), policy.sample(10, 0, 2, false, false, true, true));
        policy.sample(20, 2, 1, false, false, true, true);
        policy.sample(30, 0, 1, false, false, true, true);
        assertEquals(java.util.List.of("lock"), policy.sample(40, 0, 2, false, false, true, false));
        assertFalse(new AvasPowerOffLatch(path, 10001).pending());
        policy.sample(50, 2, 1, false, false, true, true);
        policy.sample(60, 0, 1, false, false, true, true);
        AvasEventPolicy recoveredLocked = new AvasEventPolicy(new AvasPowerOffLatch(path, 10001));
        recoveredLocked.reconcile(0, 2);
        assertFalse(new AvasPowerOffLatch(path, 10001).pending());
    }

    @Test public void powerOnCancelsAndNewPowerOffRearmsWithSimultaneousLock() throws Exception {
        AvasEventPolicy policy = new AvasEventPolicy();
        policy.reconcile(2, 1);
        policy.sample(10, 0, 1, false, false, true, true);
        assertEquals(java.util.List.of("power_on"), policy.sample(20, 2, 1, false, false, true, true));
        assertEquals(java.util.List.of("lock"), policy.sample(30, 2, 2, false, false, true, true));
        policy.sample(40, 2, 1, false, false, true, true);
        assertEquals(java.util.List.of("power_off"), policy.sample(50, 0, 2, false, false, true, true));
        policy.sample(60, 0, 1, false, false, true, true);
        assertEquals(java.util.List.of("lock"), policy.sample(70, 0, 2, false, false, true, true));
    }

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
