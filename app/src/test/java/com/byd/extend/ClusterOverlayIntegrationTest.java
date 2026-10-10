package com.byd.extend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class ClusterOverlayIntegrationTest {
    @Test
    public void displayEventsOnlyAffectActiveMatchingClusterDestination() {
        assertTrue(ShellCameraOverlay.isCurrentClusterDisplayEvent(true, 1, 4, 4));
        assertFalse(ShellCameraOverlay.isCurrentClusterDisplayEvent(false, 1, 4, 4));
        assertFalse(ShellCameraOverlay.isCurrentClusterDisplayEvent(true, 0, 0, 0));
        assertFalse(ShellCameraOverlay.isCurrentClusterDisplayEvent(true, 1, 4, 5));
        assertFalse(ShellCameraOverlay.isCurrentClusterDisplayEvent(true, 1, -1, -1));
    }

    @Test
    public void staleHideCannotAffectNewRequestOrSurface() {
        assertTrue(ShellCameraOverlay.matchesSurfaceRequest(true, 5, 2, 5, 2));
        assertFalse(ShellCameraOverlay.matchesSurfaceRequest(true, 5, 2, 4, 2));
        assertFalse(ShellCameraOverlay.matchesSurfaceRequest(true, 5, 2, 5, 1));
        assertFalse(ShellCameraOverlay.matchesSurfaceRequest(false, 5, 2, 5, 2));
        assertFalse(ShellCameraOverlay.matchesSurfaceRequest(true, 0, 0, 0, 0));
    }

    @Test
    public void readinessAndAcquisitionCheckAttachmentBeforeHandingSurfaceToConsumer()
            throws Exception {
        String source = new String(Files.readAllBytes(Path.of(
                "src/main/java/com/byd/extend/ShellCameraOverlay.java")), StandardCharsets.UTF_8);
        String ready = source.substring(source.indexOf("private void emitSurfaceReady("),
                source.indexOf("private void requireCurrent("));
        assertTrue(ready.indexOf("ensureClusterDestination();") >= 0
                && ready.indexOf("\"state\", \"ready\"") > ready.indexOf("ensureClusterDestination();"));
        String acquire = source.substring(source.indexOf("SurfaceSnapshot acquireSurface("),
                source.indexOf("void armFirstFrame("));
        assertTrue(acquire.indexOf("ensureClusterDestination();") >= 0
                && acquire.indexOf("return new SurfaceSnapshot(") > acquire.indexOf("ensureClusterDestination();"));
        assertTrue(source.contains("if (nextVisible) requireCurrentClusterDisplay();"));
        assertTrue(source.contains("\"stage\", \"cluster_destination_unavailable\""));
    }

    @Test
    public void shellDisplayObserverUsesExistingMainHandlerAndIsRemovedAtShutdown()
            throws Exception {
        String source = new String(Files.readAllBytes(Path.of(
                "src/main/java/com/byd/extend/CameraShellMain.java")), StandardCharsets.UTF_8);
        assertTrue(source.contains("registerDisplayListener(displayListener, handler)"));
        assertTrue(source.contains("unregisterDisplayListener(displayListener)"));
        assertTrue(source.contains("overlay.onClusterDisplayChanged(displayId, removed)"));
    }

    @Test
    public void shellSurfaceTransactionsTransferTheGpuEndpointBothWays() throws Exception {
        String helper = new String(Files.readAllBytes(Path.of(
                "src/main/java/com/byd/extend/CameraShellMain.java")), StandardCharsets.UTF_8);
        String controller = new String(Files.readAllBytes(Path.of(
                "src/main/java/com/byd/extend/TurnSignalController.java")), StandardCharsets.UTF_8);
        String overlayWrite = between(helper,
                "if (code == CameraShellProtocol.TX_OVERLAY_ACQUIRE_SURFACE)",
                "if (code == CameraShellProtocol.TX_OVERLAY_ARM_FRAME)");
        String reverseWrite = between(helper,
                "if (code == CameraShellProtocol.TX_REVERSE_ACQUIRE_SURFACES)",
                "if (code == CameraShellProtocol.TX_REVERSE_ARM_FRAMES)");
        String overlayRead = between(controller,
                "private static OverlaySurface transactOverlayAcquire(",
                "private static void transactOverlayFrame(");
        String reverseRead = between(controller,
                "private static ReverseSurfaces transactReverseAcquire(",
                "private static void transactReverseFrames(");

        assertTrue(overlayWrite.contains("CameraGpuOutput.writeSurface(reply, snapshot.surface)"));
        assertTrue(reverseWrite.contains(
                "CameraGpuOutput.writeSurface(reply, snapshot.surfaces[index])"));
        assertTrue(overlayRead.contains("CameraGpuOutput.readSurface(reply)"));
        assertTrue(reverseRead.contains("CameraGpuOutput.readSurface(reply)"));
        assertFalse(overlayRead.contains("Surface.CREATOR.createFromParcel(reply)"));
        assertFalse(reverseRead.contains("Surface.CREATOR.createFromParcel(reply)"));
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        return source.substring(from, source.indexOf(end, from));
    }
}
