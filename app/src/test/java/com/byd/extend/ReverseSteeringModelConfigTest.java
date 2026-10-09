package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.Assume;
import org.junit.Test;

public final class ReverseSteeringModelConfigTest {
    @Test public void shellSnapshotValidatesBoundsAndIdentityWithoutOemLookup() {
        ReverseSteeringModelConfig snapshot = new ReverseSteeringModelConfig(-543.163f, 543.163f,
                "286/ocean/uked");
        org.junit.Assert.assertTrue(snapshot.sameModel(
                new ReverseSteeringModelConfig(-543.163f, 543.163f, "286/ocean/uked")));
        org.junit.Assert.assertFalse(snapshot.sameModel(null));
        org.junit.Assert.assertThrows(IllegalArgumentException.class,
                () -> new ReverseSteeringModelConfig(Float.NaN, 543f, "model"));
        org.junit.Assert.assertThrows(IllegalArgumentException.class,
                () -> new ReverseSteeringModelConfig(-543f, 543f, ""));
    }

    @Test public void readsOnlyExplicitActiveModelBoundsInMillidegrees() throws Exception {
        ReverseSteeringModelConfig config = ReverseSteeringModelConfig.fromJson(
                "series/body/trim",
                "{\"PROJECT_CONFIG\":{\"AngleMin\":-543163,\"AngleMax\":543163}} ");

        assertEquals(-543.163f, config.minimumDegrees, .0001f);
        assertEquals(543.163f, config.maximumDegrees, .0001f);
        assertEquals("series/body/trim", config.model);
    }

    @Test public void missingOrInvalidBoundsAreUnavailableInsteadOfDefaulted() throws Exception {
        assertInvalid("{\"PROJECT_CONFIG\":{\"AngleMax\":543163}}");
        assertInvalid("{\"PROJECT_CONFIG\":{\"AngleMin\":-10,\"AngleMax\":10.5}}");
        assertInvalid("{\"PROJECT_CONFIG\":{\"AngleMin\":0,\"AngleMax\":10}}");
        assertInvalid("{\"PROJECT_CONFIG\":{\"AngleMin\":-10,\"AngleMax\":0}}");
    }

    @Test public void normalizesOemCommentsAndMissingCommas() throws Exception {
        ReverseSteeringModelConfig config = ReverseSteeringModelConfig.fromJson(
                "ukea/ukea", "{\n\"PROJECT_CONFIG\":{\n# maximum\n"
                        + "\"AngleMax\":543163\n# minimum\n"
                        + "\"AngleMin\":-543163\n}\n}");

        assertEquals(-543.163f, config.minimumDegrees, .0001f);
        assertEquals(543.163f, config.maximumDegrees, .0001f);
    }

    @Test public void readsBydBodyParametersAsProvisionalDegreesWithoutApplyingRatio()
            throws Exception {
        String yaml = "vehicle_body_param:\n"
                + "  steering_angle_normalize_ratio: 0.8023\n"
                + "  steering_angle_max: 512\n"
                + "camera_param:\n"
                + "  steering_angle_max: 999\n";
        ReverseSteeringModelConfig.Selection selection = selection(
                "BYD", "", "resource_byd/shh/CarModel/configs/vehicle_param.yaml",
                "yaml", 7);

        ReverseSteeringModelConfig.Capture capture = selection.capture(yaml);

        assertTrue(capture.boundsAvailable);
        assertTrue(capture.provisionalBounds);
        assertEquals(-512.0f, capture.minimumDegrees, 0.0f);
        assertEquals(512.0f, capture.maximumDegrees, 0.0f);
        assertEquals("512", capture.rawSteeringParameters.get("steering_angle_max"));
        assertEquals("0.8023",
                capture.rawSteeringParameters.get("steering_angle_normalize_ratio"));
        assertEquals("resource_byd/shh/CarModel/configs/vehicle_param.yaml",
                capture.configurationPath);
        assertEquals("/collect2/autovideo/resource_byd/shh/CarModel/resource/configs/vehicle_param.yaml",
                capture.externalConfigurationPath);
        assertEquals(-512.0f, capture.toConfig().minimumDegrees, 0.0f);
        ReverseSteeringModelConfig fourSpaceConfig = ReverseSteeringModelConfig.fromYaml(
                "ocean/shh/shh", "vehicle_body_param:\n"
                        + "    steering_angle_max: 512\n");
        assertEquals(-512.0f, fourSpaceConfig.minimumDegrees, 0.0f);
        org.junit.Assert.assertThrows(UnsupportedOperationException.class,
                () -> capture.rawSteeringParameters.put("other", "value"));
    }

    @Test public void invalidModelCaptureKeepsSelectedSourceAndRawFields() {
        String yaml = "vehicle_body_param:\n"
                + "  steering_angle_normalize_ratio: 0.8023\n"
                + "  steering_angle_max: -512\n";
        ReverseSteeringModelConfig.Selection selection = selection(
                "BYD", "", "resource_byd/shh/CarModel/configs/vehicle_param.yaml",
                "yaml", 7);

        ReverseSteeringModelConfig.Capture capture = selection.capture(yaml);

        assertFalse(capture.boundsAvailable);
        assertTrue(capture.provisionalBounds);
        assertEquals(yaml, capture.rawConfiguration);
        assertEquals("-512", capture.rawSteeringParameters.get("steering_angle_max"));
        assertEquals("0.8023",
                capture.rawSteeringParameters.get("steering_angle_normalize_ratio"));
        assertEquals("resource_byd/shh/CarModel/configs/vehicle_param.yaml",
                capture.configurationPath);
        assertEquals("steering_angle_max is not positive", capture.parseError);
        org.junit.Assert.assertThrows(IllegalStateException.class, capture::toConfig);
    }

    @Test public void selectionIdentityIncludesPackageRendererModelAndResourcePath() {
        ReverseSteeringModelConfig.Selection selected = selection(
                "TS", "/ukea", "resource_ts/ukea/CarModel/ukea/Vehicle_Configuration.json",
                "json", 3);

        assertTrue(selected.sameIdentity(selection(
                "TS", "/ukea", "resource_ts/ukea/CarModel/ukea/Vehicle_Configuration.json",
                "json", 3)));
        assertFalse(selected.sameIdentity(selection(
                "TS", "/ukeb", "resource_ts/ukeb/CarModel/ukeb/Vehicle_Configuration.json",
                "json", 3)));
        assertFalse(selected.sameIdentity(selection(
                "TS", "/ukea", "resource_ts/ukea/CarModel/ukea/Vehicle_Configuration.json",
                "json", 4)));
    }

    @Test public void deterministicFailureSuppressesOnlyRepeatedLoadOfSameSelection() {
        ReverseSteeringModelConfig.Selection selected = selection(
                "TS", "/ukea", "resource_ts/ukea/CarModel/ukea/Vehicle_Configuration.json",
                "json", 3);
        int loadAttempts = 0;
        ReverseSteeringModelConfig.Selection cachedFailure = null;

        if (ReverseSteeringModelConfig.shouldLoadAfterCachedFailure(selected, cachedFailure)) {
            loadAttempts++;
            cachedFailure = selected;
        }
        if (ReverseSteeringModelConfig.shouldLoadAfterCachedFailure(selected, cachedFailure)) {
            loadAttempts++;
        }
        assertEquals("same deterministic failure should not be reparsed", 1, loadAttempts);

        ReverseSteeringModelConfig.Selection changedModel = selection(
                "TS", "/ukea", "resource_ts/ukea/CarModel/ukea/Vehicle_Configuration.json",
                "json", 3, "com.byd.avc", "ocean/ukea/ukea-new");
        if (ReverseSteeringModelConfig.shouldLoadAfterCachedFailure(changedModel, cachedFailure)) {
            loadAttempts++;
            cachedFailure = changedModel;
        }
        ReverseSteeringModelConfig.Selection changedPackage = selection(
                "TS", "/ukea", "resource_ts/ukea/CarModel/ukea/Vehicle_Configuration.json",
                "json", 4, "com.byd.avc.updated", "ocean/ukea/ukea");
        if (ReverseSteeringModelConfig.shouldLoadAfterCachedFailure(changedPackage, cachedFailure)) {
            loadAttempts++;
            cachedFailure = changedPackage;
        }

        // Transient read errors are not installed in the deterministic-failure cache.
        if (ReverseSteeringModelConfig.shouldLoadAfterCachedFailure(selected, null)) {
            loadAttempts++;
        }
        assertEquals("model, package, and transient failures should retry", 4, loadAttempts);
    }

    @Test public void readsTheCheckedInUkeaAssetWhenResearchCheckoutIsPresent()
            throws Exception {
        Path asset = checkedInUkeaAsset();
        Assume.assumeTrue("UKEA research asset is outside this checkout", asset != null);
        String source = new String(Files.readAllBytes(asset), StandardCharsets.UTF_8);

        ReverseSteeringModelConfig config = ReverseSteeringModelConfig.fromJson(
                "ukea/ukea", source);

        assertEquals(-543.163f, config.minimumDegrees, .0001f);
        assertEquals(543.163f, config.maximumDegrees, .0001f);
    }

    @Test public void readsTheRetainedSl06BydYamlFromResearchApkWhenPresent() throws Exception {
        Path apk = retainedBydApk();
        Assume.assumeTrue("SL06 research APK is outside this checkout", apk != null);
        String path = "assets/resource_byd/shh/CarModel/configs/vehicle_param.yaml";
        String yaml;
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            ZipEntry entry = zip.getEntry(path);
            Assume.assumeTrue("retained OEM YAML is absent", entry != null);
            try (java.io.InputStream input = zip.getInputStream(entry)) {
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                yaml = new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        }

        ReverseSteeringModelConfig config = ReverseSteeringModelConfig.fromYaml(
                "ocean/shh/shh", yaml);

        assertEquals(-512.0f, config.minimumDegrees, 0.0f);
        assertEquals(512.0f, config.maximumDegrees, 0.0f);
    }

    private static void assertInvalid(String json) throws Exception {
        try {
            ReverseSteeringModelConfig.fromJson("body/trim", json);
            fail("expected missing or invalid explicit steering bounds");
        } catch (IllegalArgumentException expected) {
            // Missing, fractional, or non-signed model bounds must stay unavailable.
        }
    }

    private static Path checkedInUkeaAsset() {
        Path directory = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; directory != null && depth < 8; depth++, directory = directory.getParent()) {
            Path candidate = directory.resolve("research/AutoVideo-jadx/resources/assets/"
                    + "resource_ts/ukea/CarModel/ukea/Vehicle_Configuration.json");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private static Path retainedBydApk() {
        Path directory = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; directory != null && depth < 8; depth++, directory = directory.getParent()) {
            Path candidate = directory.resolve(
                    "research/SL06-DMi/remote/system/app/AutoVideo/AutoVideo.apk");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private static ReverseSteeringModelConfig.Selection selection(
            String renderer, String suffix, String path, String format, int packageVersionCode) {
        return selection(renderer, suffix, path, format, packageVersionCode,
                "com.byd.avc", "ocean/shh/shh");
    }

    private static ReverseSteeringModelConfig.Selection selection(
            String renderer, String suffix, String path, String format, int packageVersionCode,
            String packageName, String model) {
        String external = "BYD".equals(renderer)
                ? "/collect2/autovideo/resource_byd/shh/CarModel" + suffix
                        + "/resource/configs/vehicle_param.yaml"
                : "";
        return new ReverseSteeringModelConfig.Selection(
                null, packageName, "1.0", packageVersionCode, 10L,
                "/system/app/AutoVideo.apk", model, renderer, suffix,
                path, external, format);
    }
}
