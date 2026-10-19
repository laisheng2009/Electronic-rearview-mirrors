package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public class MotionSessionRuntimeTest {
    @Test public void cameraLossRearmsFromValidTelemetryWithoutWaitingForAnotherBaseline() throws Exception {
        MotionSessionRuntime runtime = new MotionSessionRuntime();
        runtime.configure("0123456789abcdef0123456789abcdef",
                MotionConfig.parse("{\"enabled\":true,\"lock\":1}"));
        runtime.telemetry("epoch", "power", 0, true, 1_000);
        runtime.telemetry("epoch", "lock", 2, true, 1_000);
        assertEquals(MotionSessionRuntime.Change.ARMED, runtime.advance(3_500));

        runtime.cameraLost();
        assertFalse(runtime.needsTelemetry());
        assertEquals(MotionSessionRuntime.Change.ARMING, runtime.advance(4_000));
        assertEquals(MotionSessionRuntime.Change.ARMED, runtime.advance(6_500));
        assertEquals(0, runtime.power());
        assertEquals(2, runtime.lock());

        runtime.telemetry("epoch", "power", -1, true, 7_000);
        runtime.cameraLost();
        assertTrue(runtime.needsTelemetry());
        assertEquals(MotionSessionRuntime.Change.NONE, runtime.advance(70_000));
        assertEquals(MotionSessionRuntime.Change.ARMING,
                runtime.telemetry("epoch", "power", 0, true, 70_100));
        runtime.telemetry("epoch", "lock", 1, false, 70_200);
        assertEquals(MotionSessionRuntime.Change.NONE, runtime.advance(80_000));
    }

    @Test public void cameraReadinessRejectsTheStaleStreamFlagSeenWhileIdcWasOffline() {
        assertFalse(MotionCameraPreparation.sourceReady(0, 0, 1));
        assertFalse(MotionCameraPreparation.sourceReady(0, 1, 0));
        assertFalse(MotionCameraPreparation.sourceReady(0, -10011, 1));
        assertFalse(MotionCameraPreparation.sourceReady(0, 1, -10011));
        assertFalse(MotionCameraPreparation.sourceReady(-1, 1, 1));
        assertFalse(MotionCameraPreparation.sourceReady(2, 1, 1));
        assertTrue(MotionCameraPreparation.sourceReady(0, 1, 1));
    }

    @Test public void telemetryCompletesArmingAtTheOriginalDeadlineWithoutWaitingForTheTimer() throws Exception {
        MotionSessionRuntime runtime = new MotionSessionRuntime();
        runtime.configure("0123456789abcdef0123456789abcdef",
                MotionConfig.parse("{\"enabled\":true,\"lock\":0}"));
        assertEquals(MotionSessionRuntime.Change.ARMING,
                runtime.telemetry("epoch", "power", 0, true, 1_000));
        assertEquals(2_500L, runtime.remainingArmDelayMs(1_000));
        assertEquals(MotionSessionRuntime.Change.NONE,
                runtime.telemetry("epoch", "lock", 2, false, 2_000));
        assertEquals(1_500L, runtime.remainingArmDelayMs(2_000));
        assertEquals(MotionSessionRuntime.Change.NONE,
                runtime.telemetry("epoch", "power", 0, true, 3_499));
        assertEquals(1L, runtime.remainingArmDelayMs(3_499));
        assertEquals(MotionSessionRuntime.Change.ARMED,
                runtime.telemetry("epoch", "power", 0, false, 3_500));
        assertEquals(0L, runtime.remainingArmDelayMs(3_500));
        assertEquals(MotionSessionRuntime.Change.NONE, runtime.advance(4_500));
        assertTrue(runtime.armed());
    }

    @Test public void requiredConditionLossCancelsAndRestartsTheFullDelay() throws Exception {
        MotionSessionRuntime runtime = new MotionSessionRuntime();
        runtime.configure("0123456789abcdef0123456789abcdef",
                MotionConfig.parse("{\"enabled\":true,\"lock\":1}"));
        runtime.telemetry("epoch", "power", 0, true, 1_000);
        runtime.telemetry("epoch", "lock", 2, true, 1_100);
        assertEquals(1_500L, runtime.remainingArmDelayMs(2_100));
        assertEquals(MotionSessionRuntime.Change.DISARMED,
                runtime.telemetry("epoch", "lock", 1, false, 2_100));
        assertEquals(0L, runtime.remainingArmDelayMs(2_100));
        assertEquals(MotionSessionRuntime.Change.ARMING,
                runtime.telemetry("epoch", "lock", 2, false, 2_200));
        assertEquals(2_500L, runtime.remainingArmDelayMs(2_200));
        assertEquals(MotionSessionRuntime.Change.NONE, runtime.advance(4_699));
        assertEquals(MotionSessionRuntime.Change.ARMED, runtime.advance(4_700));
    }

    @Test public void recordingRevisionIgnoresUnknownTelemetryAndUnchangedReconnect() throws Exception {
        MotionSessionRuntime runtime = new MotionSessionRuntime();
        runtime.configure("0123456789abcdef0123456789abcdef",
                MotionConfig.parse("{\"enabled\":true,\"lock\":0}"));
        runtime.telemetry("epoch", "power", 0, true, 1_000);
        long revision = runtime.recordingRevision();
        runtime.telemetry("epoch", "lock", 1, true, 1_100);
        runtime.telemetry("epoch", "power", -1, false, 1_200);
        runtime.invalidateTelemetry();
        runtime.telemetry("epoch", "power", 0, true, 1_300);
        assertEquals(revision, runtime.recordingRevision());
        runtime.telemetry("epoch", "power", 1, false, 1_400);
        assertTrue(runtime.recordingRevision() > revision);
        revision = runtime.recordingRevision();
        runtime.telemetry("new-camera-host", "power", 0, true, 1_500);
        assertTrue(runtime.recordingRevision() > revision);
    }

    @Test public void modeZeroArmsFromFreshPowerAloneAndEligibilityLossRestartsWarmup() throws Exception {
        MotionSessionRuntime runtime = new MotionSessionRuntime();
        runtime.configure("0123456789abcdef0123456789abcdef",
                MotionConfig.parse("{\"enabled\":true,\"lock\":0}"));
        assertEquals(MotionSessionRuntime.Change.ARMING,
                runtime.telemetry("epoch-a", "power", 0, true, 1_000));
        assertFalse(runtime.armed());
        assertEquals(MotionSessionRuntime.Change.NONE, runtime.advance(3_499));
        assertEquals(MotionSessionRuntime.Change.ARMED, runtime.advance(3_500));
        assertTrue(runtime.armed());

        assertEquals(MotionSessionRuntime.Change.DISARMED,
                runtime.telemetry("epoch-a", "power", 1, false, 3_600));
        assertFalse(runtime.armed());
        assertEquals(MotionSessionRuntime.Change.ARMING,
                runtime.telemetry("epoch-a", "power", 0, false, 4_000));
        assertEquals(MotionSessionRuntime.Change.NONE, runtime.advance(6_499));
        assertEquals(MotionSessionRuntime.Change.ARMED, runtime.advance(6_500));
    }

    @Test public void modeOneRequiresFreshLockAndSourceEpochInvalidatesOldBaseline() throws Exception {
        MotionSessionRuntime runtime = new MotionSessionRuntime();
        runtime.configure("abcdef0123456789abcdef0123456789",
                MotionConfig.parse("{\"warning\":true,\"lock\":2}"));
        assertEquals(MotionSessionRuntime.Change.NONE,
                runtime.telemetry("epoch-a", "power", 0, true, 100));
        assertFalse(runtime.armed());
        assertEquals(MotionSessionRuntime.Change.ARMING,
                runtime.telemetry("epoch-a", "lock", 2, true, 200));
        assertEquals(MotionSessionRuntime.Change.EPOCH_CHANGED,
                runtime.telemetry("epoch-b", "lock", 2, false, 250));
        assertFalse(runtime.armed());
        assertEquals(MotionSessionRuntime.Change.NONE,
                runtime.telemetry("epoch-b", "power", 0, false, 300));
        assertEquals(MotionSessionRuntime.Change.NONE,
                runtime.telemetry("epoch-b", "power", 0, true, 400));
        assertEquals(MotionSessionRuntime.Change.ARMING,
                runtime.telemetry("epoch-b", "lock", 2, true, 500));
        assertEquals(MotionSessionRuntime.Change.ARMED, runtime.advance(3_000));
    }

    @Test public void lockChangesDoNotDisarmPowerOnlyModeAndHostStopSuspendsUntilConfigure() throws Exception {
        MotionSessionRuntime runtime = new MotionSessionRuntime();
        runtime.configure("0123456789abcdef0123456789abcdef",
                MotionConfig.parse("{\"enabled\":true,\"lock\":0}"));
        runtime.telemetry("epoch", "power", 0, true, 0);
        runtime.advance(MotionDetectorProtocol.WARMUP_MS);
        assertTrue(runtime.armed());
        assertEquals(MotionSessionRuntime.Change.NONE,
                runtime.telemetry("epoch", "lock", -1, false, 3_000));
        assertTrue(runtime.armed());

        runtime.suspendForHostShutdown();
        assertFalse(runtime.armed());
        assertTrue(runtime.suspended());
        assertEquals(MotionSessionRuntime.Change.NONE,
                runtime.telemetry("epoch", "power", 0, true, 4_000));
        assertTrue(runtime.suspended());
    }

    @Test public void warningRetryStaysBlockedAcrossReconnectUntilVehiclePreconditionChanges() throws Exception {
        MotionSessionRuntime runtime = new MotionSessionRuntime();
        runtime.configure("0123456789abcdef0123456789abcdef",
                MotionConfig.parse("{\"warning\":true,\"lock\":0}"));
        runtime.telemetry("epoch-a", "power", 0, true, 0);
        runtime.blockWarningRetry();

        assertTrue(runtime.warningRetryBlocked());
        assertEquals(MotionSessionRuntime.Change.EPOCH_CHANGED,
                runtime.telemetry("epoch-b", "power", 0, true, 1_000));
        assertTrue(runtime.warningRetryBlocked());
        runtime.telemetry("epoch-b", "power", -1, false, 1_050);
        assertTrue(runtime.warningRetryBlocked());

        runtime.telemetry("epoch-b", "power", 1, false, 1_100);
        assertFalse(runtime.warningRetryBlocked());
    }
}
