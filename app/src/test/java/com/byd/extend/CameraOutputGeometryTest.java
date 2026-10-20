package com.byd.extend;

import static org.junit.Assert.*;
import org.junit.Test;

public final class CameraOutputGeometryTest {
    @Test public void identityMirrorAndQuarterTurnHaveKnownClipCoordinates() {
        float[] identity = CameraRotation.gpuTransformValues(
                new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1}, 815, 504);
        assertArrayEquals(CameraGpuOutput.IDENTITY, identity, 0f);
        float[] mirror = CameraRotation.gpuTransformValues(
                new float[]{-1, 0, 815, 0, 1, 0, 0, 0, 1}, 815, 504);
        assertArrayEquals(new float[]{1, 1}, project(mirror, -1, 1), .00001f);
        float[] clockwise = CameraRotation.gpuTransformValues(
                new float[]{0, -1, 200, 1, 0, 0, 0, 0, 1}, 200, 200);
        assertArrayEquals(new float[]{1, 1}, project(clockwise, -1, 1), .00001f);
        assertArrayEquals(new float[]{-1, -1}, project(clockwise, 1, -1), .00001f);
    }

    @Test public void gpuPreservesExistingCropRotationFitFillAndMirrorAcrossPaneSizes() {
        for (int[] size : new int[][]{{815, 504}, {486, 220}, {576, 356}, {900, 700}}) {
            int w = size[0], h = size[1];
            for (int angle : new int[]{-180, -35, 0, 35, 90}) {
                for (boolean mirror : new boolean[]{false, true}) {
                    for (int mode : new int[]{CameraRotation.MODE_FIT, CameraRotation.MODE_FILL}) {
                        float[] view = CameraRotation.sourceAwareProportionalTransformValues(
                                .12f, .23f, .7f, .5f, 0, 0, w, h,
                                angle, mode, 1920, 1300, w, h, mirror);
                        float[] gpu = CameraRotation.gpuTransformValues(view, w, h);
                        // Compare the actual legacy pixel transform with the new clip-space output.
                        for (float[] point : new float[][]{{0, 0}, {w, h},
                                {.12f * w, .23f * h}, {.82f * w, .73f * h},
                                {.47f * w, .48f * h}}) {
                            float[] result = project(gpu, 2f * point[0] / w - 1f,
                                    1f - 2f * point[1] / h);
                            assertEquals(view[0] * point[0] + view[1] * point[1] + view[2],
                                    (result[0] + 1f) * w / 2f, .002f);
                            assertEquals(view[3] * point[0] + view[4] * point[1] + view[5],
                                    (1f - result[1]) * h / 2f, .002f);
                        }
                        assertArrayEquals(new float[]{0, 0},
                                project(gpu, -.06f, .04f), .00001f);
                    }
                }
            }
        }
    }

    @Test public void stretchHomogeneousComponentAndIndependentOutputsArePreserved() {
        float[] first = CameraRotation.gpuTransformValues(
                new float[]{1, .2f, 20, .3f, 1, 10, .001f, .002f, 1}, 200, 100);
        float[] saved = first.clone();
        float[] other = CameraRotation.gpuTransformValues(
                new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1}, 486, 220);
        // Input pixel (100,50) => (130,90)/1.2 => clip (1/12,-1/2).
        assertArrayEquals(new float[]{1f / 12f, -.5f}, project(first, 0, 0), .00001f);
        assertArrayEquals(saved, first, 0f);
        assertArrayEquals(CameraGpuOutput.IDENTITY, other, 0f);
    }

    @Test public void invalidViewGeometryIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CameraRotation.gpuTransformValues(new float[9], 0, 100));
        assertThrows(IllegalArgumentException.class,
                () -> CameraRotation.gpuTransformValues(new float[8], 100, 100));
        assertThrows(IllegalArgumentException.class, () -> CameraRotation.gpuTransformValues(
                new float[]{1, 0, Float.NaN, 0, 1, 0, 0, 0, 1}, 100, 100));
    }

    private static float[] project(float[] m, float x, float y) {
        float w = m[3] * x + m[7] * y + m[15];
        return new float[]{(m[0] * x + m[4] * y + m[12]) / w,
                (m[1] * x + m[5] * y + m[13]) / w};
    }
}
