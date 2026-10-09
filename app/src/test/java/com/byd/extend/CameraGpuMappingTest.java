package com.byd.extend;

import static org.junit.Assert.*;

import java.nio.FloatBuffer;
import org.junit.Test;

public final class CameraGpuMappingTest {
    @Test public void fusedMeshUsesTheSameProjectionRoiAndStrengthAsTheSeparateRenderer() {
        for (int projection = 0; projection <= 1; projection++) {
            for (int lens = 1; lens <= 4; lens++) {
                CameraDewarpConfig config = CameraDewarpConfig.of(lens, true, 130, projection,
                        70, 130.5f).withRoiCenter(.55f, .45f);
                CameraFisheyeMapping.Mesh expected = CameraFisheyeMapping.buildMesh(config, 960, 650);
                DirectCameraSourceHub.MappingMesh actual = new DirectCameraSourceHub.MappingMesh();
                CameraDewarpRenderer.MappingRequest request = request(config, 1);
                assertSame(request, actual.prepare(request, 960, 650));
                float[] vertices = new float[actual.vertices.capacity()];
                short[] indices = new short[actual.indices.capacity()];
                actual.vertices.duplicate().get(vertices);
                actual.indices.duplicate().get(indices);
                assertArrayEquals(expected.vertices, vertices, 0);
                assertArrayEquals(expected.indices, indices);
                assertFalse(actual.rawFallback);
            }
        }
    }

    @Test public void separateTargetsRetainIndependentMappingsAndOnlyEditsRebuildMesh() {
        CameraDewarpConfig left = CameraDewarpConfig.of(1, true, 120);
        CameraDewarpConfig right = CameraDewarpConfig.of(2, true, 160, 1);
        DirectCameraSourceHub.MappingMesh first = new DirectCameraSourceHub.MappingMesh();
        DirectCameraSourceHub.MappingMesh second = new DirectCameraSourceHub.MappingMesh();
        CameraDewarpRenderer.MappingRequest initial = request(left, 1);
        first.prepare(initial, 960, 650);
        FloatBuffer vertices = first.vertices;
        first.prepare(initial, 960, 650);
        assertSame(vertices, first.vertices);
        CameraDewarpRenderer.MappingRequest repeated = request(left, 2);
        assertSame(repeated, first.prepare(repeated, 960, 650));
        assertSame(vertices, first.vertices);
        assertEquals(0, first.generationNs);
        second.prepare(request(right, 1), 960, 650);
        assertSame(repeated, first.applied);
        first.prepare(request(left.withRoiCenter(.6f, .5f), 3), 960, 650);
        assertNotSame(vertices, first.vertices);
        assertSame(right, second.applied.config);
    }

    @Test public void disablingCorrectionReplacesTheMeshWithRawGeometry() {
        DirectCameraSourceHub.MappingMesh output = new DirectCameraSourceHub.MappingMesh();
        output.prepare(request(CameraDewarpConfig.of(3, true, 100), 1), 960, 650);
        assertTrue(output.indices.capacity() > 6);
        output.prepare(request(CameraDewarpConfig.disabled(3), 2), 960, 650);
        assertEquals(6, output.indices.capacity());
        assertFalse(output.rawFallback);
    }

    private static CameraDewarpRenderer.MappingRequest request(CameraDewarpConfig config, long token) {
        return new CameraDewarpRenderer.MappingRequest(config, token);
    }
}
