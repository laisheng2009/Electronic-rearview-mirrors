package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public final class Sl06CameraRecoveryTest {
    @Test public void sleepIdentityDoesNotDependOnControllerRestartPolicy() throws Exception {
        String shell = CameraRuntimeContinuityTest.source("TurnSignalShellMain");
        String death = shell.substring(shell.indexOf("private void controllerDiedOnHandler"),
                shell.indexOf("private void platformSignal"));
        assertTrue(death.indexOf("powerStateChanged(\"controller_died\", interactive)")
                < death.indexOf("if (!restart) return"));
        assertEquals(CameraRuntimeHost.POWER_SCREEN_OFF,
                CameraRuntimeClient.powerEvent("android.intent.action.SCREEN_OFF"));
        assertEquals(CameraRuntimeHost.POWER_SCREEN_ON,
                CameraRuntimeClient.powerEvent("android.intent.action.SCREEN_ON"));
        assertEquals(CameraRuntimeHost.POWER_QUICKBOOT,
                CameraRuntimeClient.powerEvent("android.intent.action.QUICKBOOT_POWERON"));
        for (String name : new String[]{"BlindSpotOverlayController", "RearviewMirrorController"})
            assertTrue(CameraRuntimeContinuityTest.source(name).contains("CameraRuntimeContext.reportDisplayPower(context, displayId)"));
        String context = CameraRuntimeContinuityTest.source("CameraRuntimeContext");
        assertTrue(context.contains("if (runtime.lastDisplayPower == state) return"));
        assertTrue(context.contains("displayId != android.view.Display.DEFAULT_DISPLAY"));
    }

    @Test public void onlyCurrentFirstFrameFailureRetiresSharedSource() throws Exception {
        for (String reason : new String[]{"first_frame_timeout", "first_frame_timeout_rear_left",
                "production_first_frame_timeout", "production_first_frame_timeout_final"}) {
            assertTrue(CameraHelperMain.HelperBinder.shouldRetireSourceForTimeout(7, 7, reason));
            assertFalse(CameraHelperMain.HelperBinder.shouldRetireSourceForTimeout(8, 7, reason));
            assertFalse(CameraHelperMain.HelperBinder.shouldRetireSourceForTimeout(0, 0, reason));
        }
        assertFalse(CameraHelperMain.HelperBinder.shouldRetireSourceForTimeout(7, 7, "tab_changed"));
        assertFalse(CameraHelperMain.HelperBinder.shouldRetireSourceForTimeout(7, 7, null));
        String helper = CameraRuntimeContinuityTest.source("CameraHelperMain");
        String timeoutClose = helper.substring(helper.indexOf("if (shouldRetireSourceForTimeout("),
                helper.indexOf("return closePersistentGroup(group, reason, expectedRequestId)"));
        assertTrue(timeoutClose.contains("emit(\"camera_source_retired\""));
        assertTrue(timeoutClose.contains("tearDownPersistentProducer(NATIVE_SOURCE_RECOVERY, null)"));
    }

    @Test public void sourceDeathCodesAreDistinctFromFramesAndConsumerErrors() throws Exception {
        for (int event : new int[]{8, 1002, 1010}) assertTrue(CameraHelperMain.nativeSourceDied(event));
        for (int event : new int[]{0, 1000, 1001, 1003, 1005, 1006, 1008, 1009})
            assertFalse(CameraHelperMain.nativeSourceDied(event));
        String helper = CameraRuntimeContinuityTest.source("CameraHelperMain");
        assertTrue(helper.contains("if (source != camera) return"));
        assertTrue(helper.contains("eventHandler(opened)"));
        assertTrue(CameraRuntimeContinuityTest.source("ParkingCameraController")
                .contains("resetAfterAttachFailure(\"first_frame_timeout\")"));
    }
}
