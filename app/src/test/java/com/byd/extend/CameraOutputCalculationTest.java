package com.byd.extend;

import static org.junit.Assert.*;

import org.junit.Test;

public final class CameraOutputCalculationTest {
    @Test public void outputStatisticsExcludeHiddenTimeAndStartAtFirstLoggedFrame() {
        DirectCameraSourceHub.StatsWindow stats = new DirectCameraSourceHub.StatsWindow();
        assertNull(outputFrame(stats, 1_000));
        assertNull(outputFrame(stats, 1_050));
        stats.restart(); // Target hidden, then shown much later.
        assertNull(outputFrame(stats, 195_000));
        DirectCameraSourceHub.Stats report = null;
        for (long ms = 195_050; ms <= 200_000; ms += 50) report = outputFrame(stats, ms);
        assertNotNull(report);
        assertEquals(5_000_000_000L, report.intervalNs);
        assertEquals(101, report.renderedFrames);
        assertEquals(50_000_000L, report.callbackGapMaxNs);
        assertEquals(101, report.swaps);

        stats.restart(); // Logging disabled; no callbacks until it is enabled again.
        assertNull(outputFrame(stats, 400_000));
        for (long ms = 400_050; ms <= 405_000; ms += 50) report = outputFrame(stats, ms);
        assertNotNull(report);
        assertEquals(5_000_000_000L, report.intervalNs);
        assertEquals(50_000_000L, report.callbackGapMaxNs);
        assertEquals(0, report.producerTimestampRepeated);
    }

    private static DirectCameraSourceHub.Stats outputFrame(
            DirectCameraSourceHub.StatsWindow stats, long ms) {
        long ns = ms * 1_000_000L;
        return stats.record(ns, ns, 0, 1_000_000L, 1_000_000L,
                2_000_000L, 2_000_000L, 3_000_000L, 1, 3_000_000L,
                1, 527L * 357, 527, 357, new int[]{527}, new int[]{357}, 1, 1920, 1300);
    }

    @Test public void directOutputKeepsIndependentQualitySizedMappingsDuringLiveChanges() {
        DirectCameraSourceHub.MappingMesh left = new DirectCameraSourceHub.MappingMesh();
        DirectCameraSourceHub.MappingMesh right = new DirectCameraSourceHub.MappingMesh();
        CameraDewarpConfig original = CameraDewarpConfig.of(
                CameraDewarpConfig.LENS_LEFT, true, 100);
        left.prepare(new CameraDewarpRenderer.MappingRequest(original, 1), 527, 357);
        right.prepare(new CameraDewarpRenderer.MappingRequest(original, 2), 800, 357);
        float[] rightBefore = meshVertices(right);
        CameraDewarpConfig moved = original.withRoiCenter(0.6f, 0.45f);
        CameraDewarpRenderer.MappingRequest update = new CameraDewarpRenderer.MappingRequest(moved, 3);
        assertEquals(update, left.prepare(update, 527, 357));
        assertFalse(left.rawFallback);
        assertArrayEquals(CameraFisheyeMapping.buildMesh(moved, 527, 357).vertices,
                meshVertices(left), 0f);
        assertArrayEquals(rightBefore, meshVertices(right), 0f);
        java.nio.FloatBuffer retained = left.vertices;
        left.prepare(new CameraDewarpRenderer.MappingRequest(moved, 4), 527, 357);
        assertTrue(retained == left.vertices); // Acknowledgement-only change keeps the mesh.
        CameraDewarpConfig raw = CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_LEFT);
        left.prepare(new CameraDewarpRenderer.MappingRequest(raw, 5), 527, 357);
        assertArrayEquals(CameraFisheyeMapping.buildMesh(raw, 527, 357).vertices,
                meshVertices(left), 0f);
    }

    private static float[] meshVertices(DirectCameraSourceHub.MappingMesh mesh) {
        float[] result = new float[mesh.vertices.capacity()];
        java.nio.FloatBuffer copy = mesh.vertices.duplicate();
        copy.position(0);
        copy.get(result);
        return result;
    }
}
