package com.byd.extend;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

public final class CompatibilityBundleExporterTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private static final String MAPS = "com.huawei.maps.car.app";
    private static final String ADS = "com.huawei.hibaic.adshmiic";
    private static final String MAPS_DIR = "/data/app/~~token/" + MAPS + "-token";
    private static final String ADS_DIR = "/system/app/HuaweiAds";

    @Test public void standardExportCopiesHuaweiSplitsNativeFilesAndFirmwareIntoZip() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String path : new String[] {
                MAPS_DIR + "/base.apk", MAPS_DIR + "/split_config.arm64_v8a.apk",
                ADS_DIR + "/HuaweiAds.apk", MAPS_DIR + "/lib/arm64/libvideo.so",
                ADS_DIR + "/lib/arm64/librender.so", "/system/framework/panosdk.jar",
                "/system/bin/bmmcameraserver", "/system/lib64/libbmmcamera_jni.so",
                "/system/lib64/libbmmcamsrv_device.so",
                "/system/etc/selinux/plat_property_contexts",
                "/vendor/etc/selinux/vendor_property_contexts",
                "/system_ext/etc/selinux/system_ext_property_contexts",
                "/product/etc/selinux/product_property_contexts",
                "/vendor/etc/selinux/precompiled_sepolicy", "/sys/fs/selinux/policy",
                "/vendor/etc/selinux/vendor_sepolicy.cil"
        }) files.put(path, ("binary:" + path).getBytes(StandardCharsets.UTF_8));

        List<String> reads = new ArrayList<>();
        List<String> commands = new ArrayList<>();
        File archive = CompatibilityBundleExporter.export(temporary.newFolder(), null, identity(),
                (command, limit) -> {
                    commands.add(command);
                    if (command.startsWith("pm path " + MAPS)) return ok(
                            "package:" + MAPS_DIR + "/base.apk\npackage:" + MAPS_DIR
                                    + "/split_config.arm64_v8a.apk\npackage:/system/etc/unrelated.apk\n"
                                    + "package:/data/user/0/private/base.apk\n");
                    if (command.startsWith("pm path " + ADS)) return ok("package:" + ADS_DIR + "/HuaweiAds.apk\n");
                    if (command.equals("dumpsys package " + MAPS)) return ok(
                            "  legacyNativeLibraryDir=" + MAPS_DIR + "/lib\n"
                                    + "  nativeLibraryDir=" + MAPS_DIR + "/lib\n"
                                    + "  secondaryNativeLibraryDir=/data/user/0/private\n");
                    if (command.equals("dumpsys package " + ADS)) return ok(
                            "  nativeLibraryDir=" + ADS_DIR + "/lib\n");
                    if (command.startsWith("find -L '" + MAPS_DIR)) return ok(
                            MAPS_DIR + "/lib/arm64/libvideo.so\n"
                                    + MAPS_DIR + "/lib/../secret.so\n"
                                    + MAPS_DIR + "/lib-elsewhere/not-ours.so\n"
                                    + ADS_DIR + "/lib/arm64/not-in-maps.so\n"
                                    + MAPS_DIR + "/lib/readme.txt\n");
                    if (command.startsWith("find -L '" + ADS_DIR)) return ok(ADS_DIR + "/lib/arm64/librender.so\n");
                    if (command.startsWith("find /system/framework")) return ok("/system/framework/panosdk.jar\n");
                    return ok("");
                }, (command, output, limit) -> {
                    reads.add(command);
                    for (Map.Entry<String, byte[]> item : files.entrySet()) {
                        if (!command.equals(CompatibilityBundleExporter.catCommandForTest(item.getKey()))) continue;
                        assertTrue(item.getValue().length <= limit);
                        try { output.write(item.getValue()); }
                        catch (IOException error) { throw new AssertionError(error); }
                        return CompatibilityBundleExporter.StreamResult.success(0, item.getValue().length);
                    }
                    return missing();
                }, System.currentTimeMillis());

        try (ZipFile zip = new ZipFile(archive)) {
            JSONObject manifest = new JSONObject(text(zip, "manifest.json"));
            for (Map.Entry<String, byte[]> item : files.entrySet()) {
                assertArrayEquals(item.getKey(), item.getValue(),
                        zip.getInputStream(zip.getEntry("remote" + item.getKey())).readAllBytes());
                assertEquals("included", source(manifest, item.getKey()).getString("status"));
                assertEquals(item.getValue().length, source(manifest, item.getKey()).getLong("size_bytes"));
            }
            assertNotNull(zip.getEntry("packages/" + MAPS + "/package.txt"));
            assertNotNull(zip.getEntry("packages/" + ADS + "/paths.txt"));
            assertFalse(reads.toString().contains("unrelated.apk"));
            assertFalse(reads.toString().contains("private"));
            assertFalse(reads.toString().contains("secret.so"));
            assertFalse(reads.toString().contains("not-ours.so"));
            assertFalse(reads.toString().contains("not-in-maps.so"));
            assertFalse(reads.toString().contains("readme.txt"));
            assertEquals(1L, commands.stream().filter(c -> c.startsWith("find -L '" + MAPS_DIR)).count());
        }
    }

    @Test public void unavailablePackageDeniedPolicyAndOversizeFileDoNotLoseOtherEvidence() throws Exception {
        String device = "/system/lib64/libbmmcamsrv_device.so";
        String policy = "/sys/fs/selinux/policy";
        String contexts = "/product/etc/selinux/product_property_contexts";
        File archive = CompatibilityBundleExporter.export(temporary.newFolder(), null, identity(),
                (command, limit) -> command.startsWith("pm path ")
                        ? CompatibilityBundleExporter.CommandResult.failure("shell_exit_1", 1) : ok(""),
                (command, output, limit) -> {
                    if (command.equals(CompatibilityBundleExporter.catCommandForTest(policy)))
                        return CompatibilityBundleExporter.StreamResult.failure("shell_exit_13", 13, 0);
                    if (command.equals(CompatibilityBundleExporter.catCommandForTest(device)))
                        return CompatibilityBundleExporter.StreamResult.failure("too_large", -1, 0);
                    if (command.equals(CompatibilityBundleExporter.catCommandForTest(contexts))) {
                        try { output.write("contexts".getBytes(StandardCharsets.UTF_8)); }
                        catch (IOException error) { throw new AssertionError(error); }
                        return CompatibilityBundleExporter.StreamResult.success(0, 8);
                    }
                    return missing();
                }, System.currentTimeMillis());
        try (ZipFile zip = new ZipFile(archive)) {
            JSONObject manifest = new JSONObject(text(zip, "manifest.json"));
            assertEquals("permission_denied", source(manifest, policy).getString("status"));
            assertEquals("too_large", source(manifest, device).getString("status"));
            assertEquals("missing", source(manifest, "/system/bin/bmmcameraserver").getString("status"));
            assertEquals("error", source(manifest, "pm path " + MAPS + " 2>/dev/null").getString("status"));
            assertEquals("contexts", text(zip, "remote" + contexts));
            assertNull(zip.getEntry("remote" + policy));
            assertNull(zip.getEntry("remote" + device));
        }
    }

    @Test public void cancellationWhileCopyingCameraServerRemovesIncompleteExport() throws Exception {
        File cache = temporary.newFolder();
        CompatibilityBundleExporter.ExportControl control = new CompatibilityBundleExporter.ExportControl();
        try {
            CompatibilityBundleExporter.export(cache, null, identity(), (command, limit) -> ok(""),
                    (command, output, limit) -> {
                        if (command.equals(CompatibilityBundleExporter.catCommandForTest("/system/bin/bmmcameraserver"))) {
                            try { output.write(1); }
                            catch (IOException error) { throw new AssertionError(error); }
                            control.cancel();
                            return CompatibilityBundleExporter.StreamResult.success(0, 1);
                        }
                        return missing();
                    }, System.currentTimeMillis(), control);
            fail("Canceled export must not hand off a completed archive");
        } catch (CompatibilityBundleExporter.CancellationException expected) { }
        assertArrayEquals(new String[0], new File(cache, "shared_logs").list());
    }

    private static CompatibilityBundleExporter.CommandResult ok(String value) {
        return CompatibilityBundleExporter.CommandResult.success(value);
    }

    private static CompatibilityBundleExporter.StreamResult missing() {
        return CompatibilityBundleExporter.StreamResult.failure("shell_exit_44", 44, 0);
    }

    private static CompatibilityBundleExporter.Identity identity() {
        return new CompatibilityBundleExporter.Identity("com.byd.extend", "1.6.2", 115,
                "BYD", "Leo5", "IVI", "IVI", "13", 33);
    }

    private static String text(ZipFile zip, String entry) throws IOException {
        return new String(zip.getInputStream(zip.getEntry(entry)).readAllBytes(), StandardCharsets.UTF_8);
    }

    private static JSONObject source(JSONObject manifest, String source) throws org.json.JSONException {
        JSONArray sources = manifest.getJSONArray("sources");
        for (int i = 0; i < sources.length(); i++) {
            JSONObject item = sources.getJSONObject(i);
            if (source.equals(item.getString("source"))) return item;
        }
        throw new AssertionError("Missing manifest source: " + source);
    }
}
