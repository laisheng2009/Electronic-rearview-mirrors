package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Calendar;
import org.junit.Test;

public final class CameraFpsOemDiagnosticsTest {
    private static final long START = 1_800_000_000_000L;
    private static final int CAMERA_ID = 7;
    private static final int SOURCE_INDEX = 2;
    private static final int PID = 1314;

    @Test public void logcatCursorUsesObservationStartTime() {
        Calendar start = Calendar.getInstance();
        start.clear();
        start.set(2026, Calendar.OCTOBER, 20, 12, 34, 56);
        start.set(Calendar.MILLISECOND, 123);
        String[] command = CameraFpsOemDiagnostics.logcatCommand(start.getTimeInMillis());

        assertEquals("10-20 12:34:56.123",
                command[Arrays.asList(command).indexOf("-T") + 1]);
    }

    @Test public void parsesChiFpsMetadataWithoutPromotingItToRestartEvidence() {
        String line = log(START + 100, PID, "CHI",
                "srcFps:30, get metadata[sinkFps]:[25]. camera_id=7 source_index=2 streamId=0xabc");
        CameraFpsOemDiagnostics.NativeFragment fragment = parse(line);

        assertNotNull(fragment);
        assertEquals("fps_metadata", fragment.event);
        assertEquals(30, fragment.srcFps);
        assertEquals(25, fragment.sinkFps);
        assertEquals("0xabc", fragment.streamId);

        CameraFpsOemDiagnostics.EvidenceWindow evidence = newWindow();
        evidence.accept(fragment);
        evidence.noteStage("reconnect_begin", START + 200);
        evidence.noteStage("first_fresh_frame", START + 500);
        assertEquals(CameraFpsOemDiagnostics.Outcome.INSUFFICIENT_EVIDENCE, evidence.outcome());
    }

    @Test public void parsesObservedCameraRatesAndKeepsNativeIdsOutOfStreamIdentity() {
        CameraFpsOemDiagnostics.NativeFragment fragment = parse(log(START + 100, PID,
                "BMMCamDevice", "Camera 3 id=2, fps=24 mFps=31 mStartClientNumber 2"));

        assertNotNull(fragment);
        assertEquals(3, fragment.cameraNumber);
        assertEquals("2", fragment.nativeId);
        assertNull(fragment.streamId);
        assertEquals(24, fragment.observedFps);
        assertEquals(31, fragment.mFps);
        assertEquals(2, fragment.clientCount);
        assertFalse(fragment.correlated);
    }

    @Test public void parsesCapturedHal3CameraFpsAndMfpsWithoutGuessingCameraMapping() {
        // Captured Hal3CameraCore setCameraFps format: Camera 3 id=3, fps=30 mFps=25.
        CameraFpsOemDiagnostics.NativeFragment fragment = parse(log(START + 100, PID,
                "Hal3CameraCore", "setCameraFps: Camera 3 id=3, fps=30 mFps=25"));

        assertNotNull(fragment);
        assertEquals(3, fragment.cameraNumber);
        assertEquals("3", fragment.nativeId);
        assertEquals(-1, fragment.cameraIdCandidate);
        assertEquals(30, fragment.observedFps);
        assertEquals(25, fragment.mFps);
        assertFalse(fragment.correlated);
    }

    @Test public void recognizesCapturedChiMemcpyTagButDropsUnrelatedCopyMessages() {
        CameraFpsOemDiagnostics.NativeFragment fps = parse(log(START + 100, PID,
                "CHIMEMCPY", "srcFps:30, get metadata[sinkFps]:[25]."));

        assertNotNull(fps);
        assertEquals(30, fps.srcFps);
        assertEquals(25, fps.sinkFps);
        assertFalse(fps.correlated);
        assertNull(parse(log(START + 100, PID, "CHIMEMCPY",
                "ConvertYuvCopyImage() copy image for pano_h processed frame 7050")));
    }

    @Test public void successfulSameCameraStopThenNewConfiguredStreamCanConfirmRestart() {
        CameraFpsOemDiagnostics.EvidenceWindow evidence = newWindow();
        evidence.accept(parse(log(START + 100, PID, "BMMCamDevice",
                "streamId=0xold camera_id=7 source_index=2")));
        evidence.noteStage("reconnect_begin", START + 200);
        evidence.accept(parse(log(START + 250, PID, "NormalHal3CamDevice",
                "stopStreaming success camera_id=7 source_index=2 streamId=0xold")));
        evidence.accept(parse(log(START + 300, PID, "Hal3CameraCore",
                "configFps:25 camera_id=7 source_index=2 streamId=0xnew")));
        evidence.accept(parse(log(START + 350, PID, "Hal3CameraCore",
                "startHal3Stream success camera_id=7 source_index=2 streamId=0xnew")));
        evidence.noteStage("first_fresh_frame", START + 400);

        assertEquals(CameraFpsOemDiagnostics.Outcome.RESTART_CONFIRMED, evidence.outcome());
        assertEquals(CameraFpsOemDiagnostics.Outcome.INSUFFICIENT_EVIDENCE,
                evidence.outcome(true));
    }

    @Test public void productionUnverifiedNativeCameraNamespaceCannotConfirmRestart() {
        CameraFpsOemDiagnostics.EvidenceWindow evidence =
                new CameraFpsOemDiagnostics.EvidenceWindow(-1, SOURCE_INDEX, 25, START);
        evidence.accept(parse(log(START + 100, PID, "BMMCamDevice",
                "streamId=0xold camera_id=7 source_index=2"), -1));
        evidence.noteStage("reconnect_begin", START + 200);
        evidence.accept(parse(log(START + 250, PID, "NormalHal3CamDevice",
                "stopStreaming success camera_id=7 source_index=2 streamId=0xold"), -1));
        evidence.accept(parse(log(START + 300, PID, "Hal3CameraCore",
                "configFps:25 camera_id=7 source_index=2 streamId=0xnew"), -1));
        evidence.accept(parse(log(START + 350, PID, "Hal3CameraCore",
                "startHal3Stream success camera_id=7 source_index=2 streamId=0xnew"), -1));
        evidence.noteStage("first_fresh_frame", START + 400);

        assertEquals("unverified", evidence.nativeIdentityMapping());
        assertEquals(-1, evidence.expectedNativeCameraId());
        assertEquals(CameraFpsOemDiagnostics.Outcome.INSUFFICIENT_EVIDENCE, evidence.outcome());
    }

    @Test public void baselineUsesNativeEventTimeWhenReaderArrivalIsOutOfOrder() {
        CameraFpsOemDiagnostics.EvidenceWindow evidence = newWindow();
        evidence.accept(parse(log(START + 100, PID, "BMMCamDevice",
                "camera_id=7 source_index=2 streamId=0xold")));
        // This post-boundary line can be delivered before the reconnect callback gets the lock.
        evidence.accept(parse(log(START + 250, PID, "BMMCamDevice",
                "camera_id=7 source_index=2 streamId=0xnew")));
        evidence.noteStage("reconnect_begin", START + 200);
        evidence.accept(parse(log(START + 260, PID, "NormalHal3CamDevice",
                "stopStreaming success camera_id=7 source_index=2 streamId=0xold")));
        evidence.accept(parse(log(START + 280, PID, "Hal3CameraCore",
                "configFps:25 camera_id=7 source_index=2 streamId=0xnew")));
        evidence.accept(parse(log(START + 300, PID, "Hal3CameraCore",
                "startHal3Stream success camera_id=7 source_index=2 streamId=0xnew")));
        evidence.noteStage("first_fresh_frame", START + 400);

        assertEquals(CameraFpsOemDiagnostics.Outcome.RESTART_CONFIRMED, evidence.outcome());
    }

    @Test public void explicitSharedClientStopSkipAndLaterFrameIdentifiesContinuedStream() {
        CameraFpsOemDiagnostics.EvidenceWindow evidence = newWindow();
        evidence.accept(parse(log(START + 100, PID, "BMMCamDevice",
                "streamId=0xold camera_id=7 source_index=2")));
        evidence.noteStage("reconnect_begin", START + 200);
        evidence.accept(parse(log(START + 250, PID, "BMMCamDevice",
                "stopPreview skip stopStreaming active count=2 camera_id=7 source_index=2 streamId=0xold")));
        evidence.noteStage("first_fresh_frame", START + 400);

        assertEquals(CameraFpsOemDiagnostics.Outcome.OLD_STREAM_CONTINUED, evidence.outcome());
    }

    @Test public void aLaterSuccessfulHalStopPreventsOldStreamContinuedClassification() {
        CameraFpsOemDiagnostics.EvidenceWindow evidence = newWindow();
        evidence.accept(parse(log(START + 100, PID, "BMMCamDevice",
                "streamId=0xold camera_id=7 source_index=2")));
        evidence.noteStage("reconnect_begin", START + 200);
        evidence.accept(parse(log(START + 250, PID, "BMMCamDevice",
                "stopPreview skip stopStreaming active count=2 camera_id=7 source_index=2 streamId=0xold")));
        evidence.accept(parse(log(START + 300, PID, "NormalHal3CamDevice",
                "stopStreaming success camera_id=7 source_index=2 streamId=0xold")));
        evidence.noteStage("first_fresh_frame", START + 400);

        assertEquals(CameraFpsOemDiagnostics.Outcome.INSUFFICIENT_EVIDENCE, evidence.outcome());
    }

    @Test public void entryStringsAndMismatchedCameraDoNotConfirmAStopOrStart() {
        CameraFpsOemDiagnostics.EvidenceWindow evidence = newWindow();
        evidence.accept(parse(log(START + 100, PID, "BMMCamDevice",
                "streamId=0xold camera_id=7 source_index=2")));
        evidence.noteStage("reconnect_begin", START + 200);
        evidence.accept(parse(log(START + 250, PID, "NormalHal3CamDevice",
                "stopStreaming camera_id=7 source_index=2 streamId=0xold")));
        evidence.accept(parse(log(START + 300, PID, "Hal3CameraCore",
                "configFps:25 camera_id=8 source_index=2 streamId=0xnew")));
        evidence.accept(parse(log(START + 350, PID, "Hal3CameraCore",
                "startHal3Stream camera_id=8 source_index=2 streamId=0xnew")));
        evidence.noteStage("first_fresh_frame", START + 400);

        assertEquals(CameraFpsOemDiagnostics.Outcome.INSUFFICIENT_EVIDENCE, evidence.outcome());
    }

    @Test public void gotoAndUnsuccessfulNativeMarkersCannotConfirmRestart() {
        CameraFpsOemDiagnostics.NativeFragment gotoStart = parse(log(START + 100, PID,
                "Hal3CameraCore", "goto startHal3Stream success camera_id=7"
                        + " source_index=2 streamId=0xnew"));
        CameraFpsOemDiagnostics.NativeFragment unsuccessfulStop = parse(log(START + 100, PID,
                "NormalHal3CamDevice", "stopStreaming unsuccessful camera_id=7"
                        + " source_index=2 streamId=0xold"));

        assertNotNull(gotoStart);
        assertNotNull(unsuccessfulStop);
        assertFalse(gotoStart.successfulStart);
        assertFalse(unsuccessfulStop.successfulStop);
    }

    @Test public void normalCamClientStopPreviewIsNotHalStopProof() {
        CameraFpsOemDiagnostics.NativeFragment clientStop = parse(log(START + 100, PID,
                "NormalCamClient", "NormalCamClient::stopPreview success camera_id=7"
                        + " source_index=2 streamId=0xold"));

        assertNotNull(clientStop);
        assertEquals("client_lifecycle", clientStop.event);
        assertFalse(clientStop.successfulStop);
    }

    @Test public void unrelatedAndMissingIdentityLinesAreNotPersistableOemFragments() {
        assertNull(parse(log(START + 100, PID, "ActivityManager", "camera_id=7 streamId=0xold")));
        CameraFpsOemDiagnostics.NativeFragment missingCamera = parse(log(START + 100, PID,
                "Hal3CameraCore", "startHal3Stream success streamId=0xold"));
        assertNotNull(missingCamera);
        assertFalse(missingCamera.correlated);
        CameraFpsOemDiagnostics.NativeFragment missingSource = parse(log(START + 100, PID,
                "Hal3CameraCore", "camera_id=7 streamId=0xold"));
        assertNotNull(missingSource);
        assertFalse(missingSource.correlated);
        CameraFpsOemDiagnostics.NativeFragment nativeCameraId = parse(log(START + 100, PID,
                "NormalHal3CamDevice", "cameraId:7 source_index=2 streamId=0xold"));
        assertNotNull(nativeCameraId);
        assertEquals(-1, nativeCameraId.cameraIdCandidate);
        assertEquals(7, nativeCameraId.nativeCameraIdCandidate);
        assertFalse(nativeCameraId.correlated);
    }

    private static CameraFpsOemDiagnostics.EvidenceWindow newWindow() {
        return new CameraFpsOemDiagnostics.EvidenceWindow(CAMERA_ID, SOURCE_INDEX, 25, START);
    }

    private static CameraFpsOemDiagnostics.NativeFragment parse(String line) {
        return parse(line, CAMERA_ID);
    }

    private static CameraFpsOemDiagnostics.NativeFragment parse(String line,
            int expectedNativeCameraId) {
        return CameraFpsOemDiagnostics.EvidenceWindow.parseLine(
                line, expectedNativeCameraId, SOURCE_INDEX, START, START + 1_000);
    }

    private static String log(long epochMs, int pid, String tag, String message) {
        long seconds = epochMs / 1_000;
        long millis = epochMs % 1_000;
        return String.format(java.util.Locale.ROOT, "%d.%03d %d %d I %s: %s",
                seconds, millis, pid, pid + 1, tag, message);
    }
}
