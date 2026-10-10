package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.Assume;
import org.junit.Test;

public final class ReverseSteeringModelConfigTest {
    @Test public void ownerUked286UsesActiveRendererAndExactStockTrimFixtures() throws Exception {
        String tsPath = ReverseSteeringModelConfig.selectConfigurationPath(
                "uked", "uked", 286, marker("TS"));
        String bydPath = ReverseSteeringModelConfig.selectConfigurationPath(
                "uked", "uked", 286, marker("BYD"));
        assertEquals("resource_ts/uked/CarModel/uked/Vehicle_Configuration.json", tsPath);
        assertEquals("resource_byd/uked/CarModel/configs/vehicle_param.yaml", bydPath);

        Path tsAsset = checkedInAsset(tsPath);
        Path bydAsset = checkedInAsset(bydPath);
        Assume.assumeTrue("owner TS fixture is outside this checkout", tsAsset != null);
        Assume.assumeTrue("owner BYD fixture is outside this checkout", bydAsset != null);
        ReverseSteeringModelConfig ts = ReverseSteeringModelConfig.fromJson(
                "ocean/uked/uked", new String(Files.readAllBytes(tsAsset), StandardCharsets.UTF_8));
        ReverseSteeringModelConfig byd = ReverseSteeringModelConfig.fromYaml(
                "ocean/uked/uked", new String(Files.readAllBytes(bydAsset), StandardCharsets.UTF_8));
        assertEquals(-543.163f, ts.minimumDegrees, .0001f);
        assertEquals(543.163f, ts.maximumDegrees, .0001f);
        assertEquals(-512.0f, byd.minimumDegrees, 0.0f);
        assertEquals(512.0f, byd.maximumDegrees, 0.0f);
    }

    @Test public void markerFailuresKeepMissingDeniedAmbiguousAndUnsupportedDistinct() {
        FileNotFoundException missing = org.junit.Assert.assertThrows(FileNotFoundException.class,
                () -> ReverseSteeringModelConfig.selectConfigurationPath(
                        "unmapped", "trim", 999, new ReverseSteeringModelConfig.RendererMarker(
                                "missing", "", "/collect2/autovideo/sdk/pano_sdk.txt", "")));
        assertTrue(missing.getMessage().contains("marker missing"));
        assertTrue(missing.getMessage().contains("model_value=999"));

        SecurityException denied = org.junit.Assert.assertThrows(SecurityException.class,
                () -> ReverseSteeringModelConfig.selectConfigurationPath(
                        "unmapped", "trim", 999, new ReverseSteeringModelConfig.RendererMarker(
                                "denied", "", "/collect2/autovideo/sdk/pano_sdk.txt", "EACCES")));
        assertTrue(denied.getMessage().contains("permission denied"));
        assertTrue(denied.getMessage().contains("EACCES"));

        IOException readError = org.junit.Assert.assertThrows(IOException.class,
                () -> ReverseSteeringModelConfig.selectConfigurationPath(
                        "unmapped", "trim", 999, new ReverseSteeringModelConfig.RendererMarker(
                                "error", "", "/collect2/autovideo/sdk/pano_sdk.txt", "EIO")));
        assertTrue(readError.getMessage().contains("marker read failed"));
        assertTrue(readError.getMessage().contains("EIO"));

        IllegalArgumentException ambiguous = org.junit.Assert.assertThrows(
                IllegalArgumentException.class,
                () -> ReverseSteeringModelConfig.selectConfigurationPath(
                        "uked", "uked", 286, marker("TS,BYD")));
        assertTrue(ambiguous.getMessage().contains("ambiguous OEM renderer marker"));

        IllegalArgumentException unsupported = org.junit.Assert.assertThrows(
                IllegalArgumentException.class,
                () -> ReverseSteeringModelConfig.selectConfigurationPath(
                        "uked", "uked", 286, marker("APA")));
        assertTrue(unsupported.getMessage().contains("unsupported OEM renderer marker"));
    }

    @Test public void onlySourceConfirmedModelsUseOemDefinedMissingMarkerFallback() throws Exception {
        ReverseSteeringModelConfig.RendererMarker missing = new ReverseSteeringModelConfig.RendererMarker(
                "missing", "", "/collect2/autovideo/sdk/pano_sdk.txt", "");
        assertEquals("resource_byd/uked/CarModel/configs/vehicle_param.yaml",
                ReverseSteeringModelConfig.selectConfigurationPath("uked", "uked", 286, missing));
        assertEquals("resource_byd/shh/CarModel/configs/vehicle_param.yaml",
                ReverseSteeringModelConfig.selectConfigurationPath("shh", "shh", 235, missing));

        ReverseSteeringModelConfig.RendererResolution ownerFallback =
                ReverseSteeringModelConfig.resolveRenderer(
                        missing, "uked", "uked", "ocean/uked/uked", 286);
        assertEquals("BYD", ownerFallback.renderer);
        assertEquals("oem_model_fallback", ownerFallback.source);
        assertEquals("missing", ownerFallback.markerStatus);
        ReverseSteeringModelConfig.RendererResolution ownerDeniedFallback =
                ReverseSteeringModelConfig.resolveRenderer(
                        new ReverseSteeringModelConfig.RendererMarker(
                                "denied", "", "/collect2/autovideo/sdk/pano_sdk.txt", "EACCES"),
                        "uked", "uked", "ocean/uked/uked", 286);
        assertEquals("BYD", ownerDeniedFallback.renderer);
        assertEquals("denied", ownerDeniedFallback.markerStatus);
        org.junit.Assert.assertThrows(FileNotFoundException.class,
                () -> ReverseSteeringModelConfig.selectConfigurationPath(
                        "uked", "uked", 285, missing));
    }

    @Test public void mirrorsOemBydTrimRulesWithoutAdasAndRejectsUnknownRequiredValue()
            throws Exception {
        assertEquals("resource_byd/mars/CarModel/bev/configs/vehicle_param.yaml",
                ReverseSteeringModelConfig.selectConfigurationPath(
                        "mars", "trim", 126, marker("BYD")));
        assertEquals("resource_byd/mars/CarModel/phev/configs/vehicle_param.yaml",
                ReverseSteeringModelConfig.selectConfigurationPath(
                        "mars", "trim", 125, marker("BYD")));
        assertEquals("resource_byd/uked/CarModel/configs/vehicle_param.yaml",
                ReverseSteeringModelConfig.selectConfigurationPath(
                        "uked", "uked", 286, marker("BYD")));

        IllegalArgumentException unknownValue = org.junit.Assert.assertThrows(
                IllegalArgumentException.class,
                () -> ReverseSteeringModelConfig.selectConfigurationPath(
                        "mars", "trim", -1, marker("BYD")));
        assertTrue(unknownValue.getMessage().contains("ambiguous active BYD trim"));
        IllegalArgumentException adasDependent = org.junit.Assert.assertThrows(
                IllegalArgumentException.class,
                () -> ReverseSteeringModelConfig.selectConfigurationPath(
                        "stf", "trim", 136, marker("BYD")));
        assertTrue(adasDependent.getMessage().contains("requires ADAS-backed is4Drive"));
    }

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
        assertEquals(235, capture.modelValue);
        assertEquals("marker", capture.rendererSelectionSource);
        assertEquals("ok", capture.rendererMarkerStatus);
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
        assertFalse(selected.sameIdentity(selection(
                "TS", "/ukea", "resource_ts/ukea/CarModel/ukea/Vehicle_Configuration.json",
                "json", 3, "com.byd.avc", "ocean/ukea/ukea", 286)));
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
        String selectedPath = ReverseSteeringModelConfig.selectConfigurationPath(
                "shh", "shh", 235, marker("BYD"));
        assertEquals("resource_byd/shh/CarModel/configs/vehicle_param.yaml", selectedPath);
        Path apk = retainedBydApk();
        Assume.assumeTrue("SL06 research APK is outside this checkout", apk != null);
        String path = "assets/" + selectedPath;
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
        return checkedInAsset("resource_ts/ukea/CarModel/ukea/Vehicle_Configuration.json");
    }

    private static Path checkedInAsset(String resourcePath) {
        Path directory = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; directory != null && depth < 8; depth++, directory = directory.getParent()) {
            Path candidate = directory.resolve("research/AutoVideo-jadx/resources/assets/"
                    + resourcePath);
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
                "com.byd.avc", "ocean/shh/shh", 235);
    }

    private static ReverseSteeringModelConfig.Selection selection(
            String renderer, String suffix, String path, String format, int packageVersionCode,
            String packageName, String model) {
        return selection(renderer, suffix, path, format, packageVersionCode,
                packageName, model, 235);
    }

    private static ReverseSteeringModelConfig.Selection selection(
            String renderer, String suffix, String path, String format, int packageVersionCode,
            String packageName, String model, int modelValue) {
        String external = "BYD".equals(renderer)
                ? "/collect2/autovideo/resource_byd/shh/CarModel" + suffix
                        + "/resource/configs/vehicle_param.yaml"
                : "";
        return new ReverseSteeringModelConfig.Selection(
                null, packageName, "1.0", packageVersionCode, 10L,
                "/system/app/AutoVideo.apk", model, modelValue, renderer,
                "marker", "ok", "/collect2/autovideo/sdk/pano_sdk.txt", suffix,
                path, external, format);
    }

    private static ReverseSteeringModelConfig.RendererMarker marker(String renderer) {
        return new ReverseSteeringModelConfig.RendererMarker(
                "ok", renderer, "/collect2/autovideo/sdk/pano_sdk.txt", "");
    }
}
