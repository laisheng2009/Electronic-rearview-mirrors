package com.byd.extend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/** Host-side wiring checks; EGL/Binder lifetime still needs parked vehicle validation. */
public final class CalibrationInputLeaseContractTest {
    private static String source(String name) throws Exception {
        return new String(Files.readAllBytes(Path.of("src/main/java/com/byd/extend/" + name + ".java")),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test public void nonFusedInputRegistersLeaseBeforeReportingReady() throws Exception {
        String renderer = source("CameraDewarpRenderer");
        String create = renderer.substring(renderer.indexOf("private void createCameraInput()"),
                renderer.indexOf("private void releaseCameraInput()"));
        assertTrue(create.contains("CameraGpuOutput.register(cameraSurface, inputLease)"));
        assertTrue(create.contains("event -> { }, true"));
        String release = renderer.substring(renderer.indexOf("void releaseAsync()"),
                renderer.indexOf("private void startInternal()"));
        assertTrue(release.contains("lease.release(() -> activeHandler.post(() -> cleanupAndStop(null)))"));
        assertFalse(release.contains("cameraTexture.release()"));
    }

    @Test public void retiringRendererDrainsWithoutDrawing() throws Exception {
        String renderer = source("CameraDewarpRenderer");
        String frame = renderer.substring(renderer.indexOf("private void renderFrame()"),
                renderer.indexOf("private void drawAndSwap("));
        int drain = frame.indexOf("cameraTexture.updateTexImage()");
        int retiring = frame.indexOf("if (released.get()) return");
        assertTrue(drain >= 0 && retiring > drain);
        assertTrue(retiring < frame.indexOf("drawAndSwap(eglSurface"));
    }

    @Test public void lifecycleOnlyWireRetainsRawRenderingAndDetachAcknowledgement() throws Exception {
        String endpoint = source("CameraGpuOutput");
        assertTrue(endpoint.contains("reply.writeInt(lifecycleOnly ? 1 : 0)"));
        assertTrue(endpoint.contains("lifecycleOnly = reply.readInt() != 0"));
        assertTrue(endpoint.contains("private final DeathRecipient death = this::disconnected"));
        assertTrue(endpoint.contains("if (sender != control)"));
        assertTrue(endpoint.contains("if (code == DETACHED)"));
        String hub = source("DirectCameraSourceHub");
        assertTrue(hub.contains("target.mapping.lifecycleOnly"));
    }
}
