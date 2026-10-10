package com.byd.extend;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public final class CameraFrameRateTest {
    @Test public void defaultsAndValidatesStoredFpsWithoutRewritingPreferences() {
        TestSharedPreferences prefs = new TestSharedPreferences();
        assertEquals(25, CameraFrameRate.load(prefs));
        assertFalse(prefs.contains(CameraFrameRate.PREF));
        for (int fps : new int[]{20, 25, 30}) {
            prefs.edit().putInt(CameraFrameRate.PREF, fps).apply();
            assertEquals(fps, CameraFrameRate.load(prefs));
            assertEquals(fps, ProductionStateSnapshot.readProductionUiState(prefs, false, false)
                    .getSettings().getCameraOutput().getFrameRate());
        }
        prefs.edit().putInt(CameraFrameRate.PREF, 60).apply();
        assertEquals(25, CameraFrameRate.load(prefs));
        assertEquals(60, prefs.getInt(CameraFrameRate.PREF, 0));
        prefs.edit().putString(CameraFrameRate.PREF, "30").apply();
        assertEquals(25, CameraFrameRate.load(prefs));
    }

    @Test public void mapsExactlyThreeSelections() {
        for (int i = 0; i < 3; i++) assertEquals(20 + i * 5, CameraFrameRate.fromSelection(i));
        for (int i : new int[]{-1, 3, 60}) {
            try { CameraFrameRate.fromSelection(i); fail("invalid index accepted"); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void invokesOnlyOemMethodAndPreservesFalseResult() throws Exception {
        FakeCamera camera = new FakeCamera();
        for (int fps : new int[]{20, 25, 30}) {
            assertTrue(CameraFrameRate.request(camera, fps));
            assertEquals(fps, camera.requested);
        }
        camera.accepted = false;
        assertFalse(CameraFrameRate.request(camera, 25));
        try { CameraFrameRate.request(camera, 60); fail("unsupported FPS accepted"); }
        catch (IllegalArgumentException expected) { }
        assertEquals(25, camera.requested);
        try { CameraFrameRate.request(new Object(), 25); fail("missing API hidden"); }
        catch (NoSuchMethodException expected) { }
        camera.fail = true;
        try { CameraFrameRate.request(camera, 30); fail("native failure hidden"); }
        catch (java.lang.reflect.InvocationTargetException expected) {
            assertEquals("native failure", expected.getCause().getMessage());
        }
    }

    @Test public void presetImportDoesNotReplaceGlobalFrameRate() {
        TestSharedPreferences source = new TestSharedPreferences();
        source.edit().putInt(CameraFrameRate.PREF, 30).apply();
        assertFalse(CameraSettingsTransfer.exportCameraPreset(source).contains(CameraFrameRate.PREF));
    }

    @Test public void appliesBeforeEachStartAndOnSettingsSyncWithoutChangingGpuPath() throws Exception {
        String helper = source("java/com/byd/extend/CameraHelperMain.java");
        assertTrue(helper.contains("requestCameraFrameRate(opened, requestedCameraId, requestedCameraTag, \"open\");"));
        assertTrue(helper.contains("requestCameraFrameRate(opened, requestedCameraId, \"pano_h\", \"open_multi\");"));
        assertTrue(helper.contains("requestCameraFrameRate(opened, requestedCameraId, \"pano_h\", \"open_persistent\");"));
        assertTrue(helper.indexOf("\"open_persistent\");") < helper.indexOf("persistentSession.startProducer("));
        assertTrue(TurnSignalShellProtocol.VERSION > 23);
        String restart = helper.substring(helper.indexOf("synchronized void applyCameraFrameRate()"),
                helper.indexOf("synchronized String closeCamera(String reason)"));
        assertTrue(restart.indexOf("\"stopPreview\"") < restart.indexOf("requestCameraFrameRate(camera"));
        assertTrue(restart.contains("stopPreview returned false"));
        assertTrue(restart.indexOf("requestCameraFrameRate(camera") < restart.indexOf("port.start()"));
        assertTrue(restart.contains("requestedFrameRate == CameraFrameRate.load(settings)"));
        assertTrue(restart.contains("tearDownPersistentProducer(\"frame_rate_restart_failed\""));
        String runtime = source("java/com/byd/extend/CameraRuntimeHost.java");
        assertTrue(runtime.contains("context.settings.updateFromClient(settings);\n                    helper.applyCameraFrameRate();"));
        assertTrue(helper.contains("\"effective_fps_verified\", false"));
        assertTrue(helper.contains("\"fps_window_stable\""));
        assertTrue(helper.contains("\"new_buffer_fps\""));
        assertTrue(helper.contains("\"measured_new_buffer_fps\""));
        assertTrue(helper.contains("\"camera_fps_window_stats\""));
        int statsStart = helper.indexOf("public void onStats(int index");
        int statsEnd = helper.indexOf("public void onTargetStall(", statsStart);
        String stats = helper.substring(statsStart, statsEnd);
        assertTrue(stats.contains("if (stats.fpsWindow)"));
        assertTrue(stats.contains("emit(\"camera_fps_window_stats\""));
        assertTrue(stats.contains("if (extended)"));
        assertTrue(stats.contains("emit(\"camera_source_hub_stats\""));
        assertTrue(stats.contains("\"requested_fps\", requestedFrameRate"));
        assertFalse(stats.contains("emit(extended ?"));
        assertTrue(helper.contains("collectStatsForWindow(requestedFrameRate)"));
        assertTrue(helper.contains("\"new_buffer_fps_valid\", stats.producerTimestampInvalid == 0"));
        String screen = source("kotlin/com/byd/extend/ui/SettingsScreen.kt");
        assertTrue(screen.indexOf("row(\"quality\")") < screen.indexOf("row(\"frame-rate\")"));
        assertTrue(screen.indexOf("row(\"frame-rate\")") < screen.indexOf("row(\"corner-radius\")"));
    }

    private static String source(String relative) throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/" + relative)),
                java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    public static final class FakeCamera {
        int requested;
        boolean accepted = true;
        boolean fail;
        public boolean setCameraFps(int fps) {
            if (fail) throw new IllegalStateException("native failure");
            requested = fps;
            return accepted;
        }
    }
}
