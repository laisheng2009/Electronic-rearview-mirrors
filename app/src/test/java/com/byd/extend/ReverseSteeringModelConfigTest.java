package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.FileNotFoundException;
import org.junit.Test;

public final class ReverseSteeringModelConfigTest {

    @Test
    public void missingOrDeniedMarkerUsesBydFallbackForExactUge201Model() throws Exception {
        ReverseSteeringModelConfig.RendererResolution result = resolve(
                "denied", "", "uge", "uge", 201);

        assertEquals("BYD", result.renderer);
        assertEquals("oem_model_fallback", result.source);
        assertEquals("denied", result.markerStatus);

        ReverseSteeringModelConfig.RendererResolution missing = resolve(
                "missing", "", "uge", "uge", 201);
        assertEquals("BYD", missing.renderer);
        assertEquals("oem_model_fallback", missing.source);
        assertEquals("missing", missing.markerStatus);
    }

    @Test
    public void explicitSupportedMarkerPrecedesKnownModelFallback() throws Exception {
        for (String renderer : new String[]{"TS", "BYD"}) {
            ReverseSteeringModelConfig.RendererResolution result = resolve(
                    "ok", renderer, "uge", "uge", 201);
            assertEquals(renderer, result.renderer);
            assertEquals("marker", result.source);
        }
    }

    @Test
    public void existingKnownModelFallbacksRemainAvailable() throws Exception {
        assertFallback("shh", "shh", 235);
        assertFallback("uked", "uked", 286);
    }

    @Test
    public void ambiguousAndUnsupportedMarkersRemainRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> resolve("ambiguous", "", "uge", "uge", 201));
        assertThrows(IllegalArgumentException.class,
                () -> resolve("ok", "TS BYD", "uge", "uge", 201));
        assertThrows(IllegalArgumentException.class,
                () -> resolve("ok", "PI", "uge", "uge", 201));
        assertThrows(IllegalArgumentException.class,
                () -> resolve("unknown-status", "", "uge", "uge", 201));
    }

    @Test
    public void unknownAndNearMatchModelsKeepTheirMarkerFailures() {
        assertThrows(SecurityException.class,
                () -> resolve("denied", "", "other", "other", 999));
        assertThrows(FileNotFoundException.class,
                () -> resolve("missing", "", "other", "other", 999));
        assertThrows(SecurityException.class,
                () -> resolve("denied", "", "uge", "other", 201));
        assertThrows(SecurityException.class,
                () -> resolve("denied", "", "uge", "uge", 200));
        assertThrows(SecurityException.class,
                () -> resolve("denied", "", "other", "uge", 201));
    }

    private static void assertFallback(String carType, String subCarType, int modelValue)
            throws Exception {
        ReverseSteeringModelConfig.RendererResolution result = resolve(
                "missing", "", carType, subCarType, modelValue);
        assertEquals("BYD", result.renderer);
        assertEquals("oem_model_fallback", result.source);
    }

    private static ReverseSteeringModelConfig.RendererResolution resolve(
            String status, String renderer, String carType, String subCarType, int modelValue)
            throws Exception {
        return ReverseSteeringModelConfig.resolveRenderer(
                new ReverseSteeringModelConfig.RendererMarker(status, renderer, "/marker", "test"),
                carType, subCarType, carType + "/" + subCarType, modelValue);
    }
}
