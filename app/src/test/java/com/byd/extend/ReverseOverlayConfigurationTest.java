package com.byd.extend;

import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = 29, application = Application.class)
public final class ReverseOverlayConfigurationTest {
    @Test public void reconfigurePublishesOnlyFinalOutputsAndReusesPanesWithoutStaleReveal() {
        ReverseCameraCompositionView view = new ReverseCameraCompositionView(
                RuntimeEnvironment.getApplication());
        view.setSideMode(ReverseSideSelectorView.MODE_FRONT);
        Object panes = AndroidBehaviorHarness.field(view, "panes");
        List<int[]> outputChanges = new ArrayList<>();
        view.setCallback(new ReverseCameraCompositionView.Callback() {
            public void onReverseSurfacesReady(int[] generations) { }
            public void onReverseFramesReady(int requestId, int[] generations) {
                fail("configuration must not reveal a frame from the previous request");
            }
            public void onReverseSurfaceLost(int index, int generation) { }
            public void onReverseOutputSetChanged(int target, int[] sources) {
                outputChanges.add(sources);
            }
        });
        ReverseCameraLayout layout = ReverseCameraLayout.withTarget(ReverseCameraLayout.defaults(),
                ReverseCameraLayout.REAR_CAMERA_INDEX, CameraDisplayTarget.CLUSTER);
        CameraShellProtocol.ReverseOverlaySpec spec = new CameraShellProtocol.ReverseOverlaySpec(
                17, layout, layout, 8,
                CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_REAR),
                CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_LEFT),
                CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_RIGHT),
                CameraBufferQuality.DEFAULT, ReverseCameraLayout.VISIBILITY_REAR_LEFT);
        view.applyOverlayConfiguration(spec, false);
        assertEquals(1, outputChanges.size());
        assertArrayEquals(new int[]{ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX}, outputChanges.get(0));
        assertEquals(ReverseSideSelectorView.MODE_REAR, view.sideMode());
        assertSame(panes, AndroidBehaviorHarness.field(view, "panes"));
        assertFalse(view.framesReady(17, new int[]{1}));
        view.applyOverlayConfiguration(spec, false);
        assertEquals("unchanged outputs must not trigger reattachment", 1, outputChanges.size());
    }
}
