package com.byd.extend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class CameraShellRecoveryGateTest {
    @Test
    public void newerAttachClaimsRecoveryOnlyOnce() {
        CameraShellRecoveryGate gate = new CameraShellRecoveryGate();

        assertTrue(gate.onDeath(4, true));
        assertTrue(gate.pending());
        assertFalse(gate.claim(4, true));
        assertTrue(gate.claim(5, true));
        assertFalse(gate.pending());
        assertFalse(gate.claim(5, true));
        assertFalse(gate.claim(6, true));
    }

    @Test
    public void staleEventsAndLostIntentDoNotRecover() {
        CameraShellRecoveryGate gate = new CameraShellRecoveryGate();

        assertTrue(gate.isNewDeath(3));
        assertFalse(gate.onDeath(3, false));
        assertFalse(gate.isNewDeath(3));
        assertFalse(gate.onDeath(3, true));
        assertFalse(gate.claim(4, true));
        assertTrue(gate.onDeath(5, true));
        assertFalse(gate.claim(6, false));
        assertFalse(gate.claim(7, true));
    }

    @Test
    public void laterDeathCanScheduleAnotherEpoch() {
        CameraShellRecoveryGate gate = new CameraShellRecoveryGate();

        assertTrue(gate.onDeath(1, true));
        assertTrue(gate.claim(2, true));
        assertTrue(gate.onDeath(2, true));
        assertTrue(gate.claim(3, true));
        gate.clear();
        assertFalse(gate.pending());
    }

    @Test
    public void leftAndRightOwnersEachRecoverOnceForTheSharedShellEpoch() {
        CameraShellRecoveryGate left = new CameraShellRecoveryGate();
        CameraShellRecoveryGate right = new CameraShellRecoveryGate();

        assertTrue(left.onDeath(8, true));
        assertTrue(right.onDeath(8, true));
        assertFalse(left.onDeath(8, true));
        assertFalse(right.onDeath(7, true));

        assertTrue(left.claim(9, true));
        assertTrue(right.claim(9, true));
        assertFalse(left.claim(9, true));
        assertFalse(right.claim(10, true));
    }

    @Test
    public void cameraRestartUsesCameraProtocolAndContinuesAfterShutdownFailure()
            throws Exception {
        String source = readMainSource("TurnSignalController.java");
        int restart = source.indexOf("private IBinder restartCameraHelper(");
        int shutdown = source.indexOf(
                "transactCameraNoArgs(expected, CameraShellProtocol.TX_SHUTDOWN);", restart);
        int shutdownFailure = source.indexOf(
                "emit(\"shell_shutdown_failed\", \"helper\", \"camera\"", shutdown);
        int invalidation = source.indexOf(
                "cameraHelperLost(expected, expectedEpoch,", shutdownFailure);
        int fresh = source.indexOf("ensureCameraHelper(true, expected)", invalidation);
        int cameraTransaction = source.indexOf("private static void transactCameraNoArgs(");
        int requireTransaction = source.indexOf("private static void requireTransact(", cameraTransaction);
        String cameraTransactionBody = source.substring(cameraTransaction, requireTransaction);

        assertTrue(restart >= 0);
        assertTrue(shutdown > restart);
        assertTrue(shutdownFailure > shutdown);
        assertTrue(invalidation > shutdownFailure);
        assertTrue(fresh > invalidation);
        assertTrue(cameraTransactionBody.contains("CameraShellProtocol.DESCRIPTOR"));
        assertFalse(cameraTransactionBody.contains("TurnSignalShellProtocol.DESCRIPTOR"));
        assertTrue(source.contains(
                "transactNoArgs(value, TurnSignalShellProtocol.TX_SHUTDOWN);"));
    }

    @Test
    public void plannedRestartInvalidatesBothPrepareCallersAndOwnsTheOnlyRecoveryLaunch()
            throws Exception {
        String controller = normalize(readMainSource("TurnSignalController.java"));
        String helper = normalize(readMainSource("CameraHelperMain.java"));

        int overlayStart = controller.indexOf("void prepareCameraOverlay(");
        int overlayEnd = controller.indexOf("void armCameraOverlayFrame(", overlayStart);
        int overlayRestart = controller.indexOf(
                "restartCameraHelper(value, epoch, \"overlay_prepare\")", overlayStart);
        int overlayResumeCheck = controller.indexOf(
                "if (!isCurrentPendingOverlay(pending)) return;", overlayRestart);
        int overlayRetry = controller.indexOf(
                "prepareResult = transactOverlayPrepare(value, spec);", overlayResumeCheck);
        assertTrue(overlayStart >= 0 && overlayEnd > overlayStart);
        assertTrue(overlayRestart > overlayStart && overlayResumeCheck > overlayRestart);
        assertTrue(overlayRetry > overlayResumeCheck && overlayRetry < overlayEnd);
        int overlayCatch = controller.indexOf("} catch (Throwable error) {", overlayRetry);
        int overlayCatchGuard = controller.indexOf(
                "if (!isCurrentPendingOverlay(pending)) return;", overlayCatch);
        int overlayError = controller.indexOf(
                "emit(\"camera_overlay_error\", \"stage\", \"prepare\"", overlayCatch);
        assertTrue(overlayCatchGuard > overlayCatch && overlayCatchGuard < overlayError);

        int reverseStart = controller.indexOf("void prepareReverseOverlay(");
        int reverseEnd = controller.indexOf("void acquireReverseSurfacesForFallback(", reverseStart);
        int reverseRestart = controller.indexOf(
                "restartCameraHelper(value, epoch, \"reverse_prepare\")", reverseStart);
        int reverseResumeCheck = controller.indexOf(
                "if (!isCurrentPendingReverse(spec.requestId)) return;", reverseRestart);
        int reverseRetry = controller.indexOf(
                "prepareResult = transactReversePrepare(value, spec);", reverseResumeCheck);
        assertTrue(reverseStart >= 0 && reverseEnd > reverseStart);
        assertTrue(reverseRestart > reverseStart && reverseResumeCheck > reverseRestart);
        assertTrue(reverseRetry > reverseResumeCheck && reverseRetry < reverseEnd);
        int reverseCatch = controller.indexOf("} catch (Throwable error) {", reverseRetry);
        int reverseCatchGuard = controller.indexOf(
                "if (!isCurrentPendingReverse(spec.requestId, surfaceSink)) return;",
                reverseCatch);
        int reverseError = controller.indexOf(
                "emit(\"reverse_overlay_error\", \"stage\", \"prepare\"", reverseCatch);
        assertTrue(reverseCatchGuard > reverseCatch && reverseCatchGuard < reverseError);

        int lostStart = controller.indexOf(
                "private void cameraHelperLost(\n            IBinder value, long epoch, String error, boolean plannedRestart)");
        int lostEnd = controller.indexOf("private boolean avmShellLost(", lostStart);
        String lost = controller.substring(lostStart, lostEnd);
        assertTrue(lostStart >= 0 && lostEnd > lostStart);
        assertTrue(lost.indexOf("cameraHelperInvalidationGeneration++;")
                < lost.indexOf("clearPendingOverlays();"));
        assertTrue(lost.indexOf("clearPendingOverlays();")
                < lost.indexOf("pendingReverseRequestId = 0;"));
        assertTrue(lost.indexOf("pendingReverseRequestId = 0;")
                < lost.indexOf("emit(\"camera_shell_died\""));
        assertTrue(lost.contains("\"planned_restart\", plannedRestart"));

        int eventStart = helper.indexOf("private void acceptControllerEvent(");
        int eventEnd = helper.indexOf("private synchronized boolean hasReverseStockContext(", eventStart);
        String event = helper.substring(eventStart, eventEnd);
        assertTrue(event.indexOf("cameraShellDiedCleanup();")
                < event.indexOf("if (!Boolean.parseBoolean(fieldString(forwarded, \"planned_restart\")))"));
        assertTrue(event.indexOf("if (!Boolean.parseBoolean(fieldString(forwarded, \"planned_restart\")))")
                < event.indexOf("turnController.recoverCameraHelper();"));

        int generationField = controller.indexOf("cameraHelperInvalidationGeneration;");
        assertTrue(generationField >= 0);
        assertTrue(controller.indexOf("long shellGeneration = cameraHelperInvalidationGeneration;",
                overlayStart) < controller.indexOf("worker.execute(() -> {", overlayStart));
        assertTrue(controller.indexOf("if (!isCurrentCameraShellGeneration(shellGeneration)) return;",
                overlayStart) < controller.indexOf("pendingOverlays[spec.cameraId] = pending;", overlayStart));
        assertTrue(controller.indexOf("long shellGeneration = cameraHelperInvalidationGeneration;",
                reverseStart) < controller.indexOf("worker.execute(() -> {", reverseStart));
        assertTrue(controller.indexOf("if (!isCurrentCameraShellGeneration(shellGeneration)) return;",
                reverseStart) < controller.indexOf("pendingReverseRequestId = spec.requestId;", reverseStart));
    }

    @Test
    public void retiredSurfaceCallbacksCannotReportErrorsAgainstReplacementRequests()
            throws Exception {
        String source = normalize(readMainSource("TurnSignalController.java"));

        int overlayAcquire = source.indexOf("private void acquireCameraOverlaySurface(");
        int overlayEnd = source.indexOf("private void clearPendingOverlaySurface(", overlayAcquire);
        String overlay = source.substring(overlayAcquire, overlayEnd);
        assertTrue(overlay.indexOf("pendingOverlays[cameraId] != pending")
                < overlay.indexOf("emit(\"camera_overlay_error\", \"stage\", \"acquire_surface\""));

        int reverseAcquire = source.indexOf(
                "private void acquireReverseSurfaces(int requestId, boolean forceTabletFallback)");
        int reverseEnd = source.indexOf("private void clearPendingReverseSurfaces(", reverseAcquire);
        String reverse = source.substring(reverseAcquire, reverseEnd);
        assertTrue(reverse.indexOf("pendingReverseSurfaceSink != sink")
                < reverse.indexOf("emit(\"reverse_overlay_error\", \"stage\", \"acquire_surfaces\""));
    }

    private static String normalize(String source) {
        return source.replace("\r\n", "\n");
    }

    private static String readMainSource(String name) throws Exception {
        Path path = Paths.get("src/main/java/com/byd/extend", name);
        if (!Files.exists(path)) path = Paths.get("app/src/main/java/com/byd/extend", name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
