package com.byd.extend;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ReverseSteeringShiftTest {

    @Test
    public void renderedSteeringDirectionIsMirrorInvariantForCorrectedAndRawCrops() {
        ReverseCameraLayout.Rect correctedCrop =
                ReverseCameraLayout.sourceCrop(.25f, .2f, .5f, .6f);
        ReverseCameraLayout.Rect rawFallbackCrop =
                ReverseCameraLayout.sourceCrop(.2f, .1f, .6f, .8f);
        for (ReverseCameraLayout.Rect saved :
                new ReverseCameraLayout.Rect[]{correctedCrop, rawFallbackCrop}) {
            for (int angle : new int[]{-50, 50}) {
                for (boolean mirror : new boolean[]{false, true}) {
                    ReverseCameraLayout.Rect shifted = ReverseSteeringShift.apply(
                            saved, angle, -100.0f, 100.0f, mirror);
                    float[] before = cropTransform(saved, 0, mirror);
                    float[] after = cropTransform(shifted, 0, mirror);
                    float[] beforePoint = map(before,
                            .5f * ReverseCameraCompositionView.SOURCE_WIDTH,
                            .5f * ReverseCameraCompositionView.SOURCE_HEIGHT);
                    float[] afterPoint = map(after,
                            .5f * ReverseCameraCompositionView.SOURCE_WIDTH,
                            .5f * ReverseCameraCompositionView.SOURCE_HEIGHT);
                    assertEquals(Math.signum(angle),
                            Math.signum(afterPoint[0] - beforePoint[0]), 0.0f);
                }
            }
        }
    }

    private static float[] cropTransform(
            ReverseCameraLayout.Rect crop, int rotation, boolean mirror) {
        return CameraRotation.sourceAwareProportionalTransformValues(
                crop.left, crop.top, crop.width, crop.height,
                0, 0, 800, 400, rotation, CameraRotation.MODE_FIT,
                ReverseCameraCompositionView.SOURCE_WIDTH,
                ReverseCameraCompositionView.SOURCE_HEIGHT,
                ReverseCameraCompositionView.SOURCE_WIDTH,
                ReverseCameraCompositionView.SOURCE_HEIGHT, mirror);
    }

    private static float[] map(float[] transform, float x, float y) {
        return new float[]{
                transform[0] * x + transform[1] * y + transform[2],
                transform[3] * x + transform[4] * y + transform[5]
        };
    }

}
