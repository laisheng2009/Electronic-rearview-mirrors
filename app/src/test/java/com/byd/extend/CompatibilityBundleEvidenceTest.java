package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public final class CompatibilityBundleEvidenceTest {
    @Test
    public void nativeLibraryCandidatesUsePackageOriginAndMatchingAbiDirectories() {
        List<CompatibilityBundleEvidence.NativeLibraryCandidate> arm64 =
                CompatibilityBundleEvidence.nativeLibraryCandidates(
                        "/system/app/AutoVideo/lib/arm64");

        assertEquals(6, arm64.size());
        assertEquals("/system/app/AutoVideo/lib/arm64/libavmapi.so", arm64.get(0).path);
        assertEquals("autovideo_native_library_candidate", arm64.get(0).origin);
        assertEquals("64", arm64.get(0).abi);
        assertEquals("/vendor/lib64/libavmapi.so", arm64.get(1).path);
        assertEquals("system_native_library_candidate", arm64.get(1).origin);
        assertTrue(arm64.stream().allMatch(candidate -> candidate.abi.equals("64")));
        assertFalse(arm64.stream().anyMatch(
                candidate -> candidate.path.endsWith("/lib/libavmapi.so")));

        List<CompatibilityBundleEvidence.NativeLibraryCandidate> arm32 =
                CompatibilityBundleEvidence.nativeLibraryCandidates(
                        "/vendor/app/AutoVideo/lib/arm");
        assertEquals("/vendor/app/AutoVideo/lib/arm/libavmapi.so", arm32.get(0).path);
        assertEquals("/vendor/lib/libavmapi.so", arm32.get(1).path);
        assertTrue(arm32.stream().allMatch(candidate -> candidate.abi.equals("32")));
    }

    @Test
    public void bundled32BitPackageDoesNotInherit64BitDeviceAbi() {
        String nativeLibraryDir = "/system/app/lib/AutoVideo";
        CompatibilityBundleEvidence.PackageAbi packageAbi =
                CompatibilityBundleEvidence.resolvePackageAbi(nativeLibraryDir);
        List<CompatibilityBundleEvidence.NativeLibraryCandidate> candidates =
                CompatibilityBundleEvidence.nativeLibraryCandidates(nativeLibraryDir);

        assertEquals("32", packageAbi.abi);
        assertEquals("native_library_dir_bundled_layout", packageAbi.source);
        assertEquals("/system/app/lib/AutoVideo/libavmapi.so", candidates.get(0).path);
        assertEquals("autovideo_native_library_candidate", candidates.get(0).origin);
        assertEquals("/vendor/lib/libavmapi.so", candidates.get(1).path);
        assertTrue(candidates.stream().allMatch(candidate -> candidate.abi.equals("32")));
        assertFalse(candidates.stream().anyMatch(
                candidate -> candidate.path.endsWith("/lib64/libavmapi.so")));
    }

    @Test
    public void unsupportedPackageAbiKeepsExactPackageCandidateWithoutGuessingSystemAbi() {
        String nativeLibraryDir = "/system/app/AutoVideo/lib/universal";
        CompatibilityBundleEvidence.PackageAbi packageAbi =
                CompatibilityBundleEvidence.resolvePackageAbi(nativeLibraryDir);
        List<CompatibilityBundleEvidence.NativeLibraryCandidate> candidates =
                CompatibilityBundleEvidence.nativeLibraryCandidates(nativeLibraryDir);

        assertEquals("", packageAbi.abi);
        assertEquals("unavailable", packageAbi.source);
        assertEquals(1, candidates.size());
        assertEquals("/system/app/AutoVideo/lib/universal/libavmapi.so", candidates.get(0).path);
        assertEquals("", candidates.get(0).abi);
    }

    @Test
    public void rejectsArbitraryLibraryAndConfigurationPaths() {
        assertFalse(CompatibilityBundleEvidence.isAllowedNativeLibraryCandidate(
                "/sdcard/libavmapi.so", "/sdcard"));
        assertFalse(CompatibilityBundleEvidence.isAllowedNativeLibraryCandidate(
                "/vendor/lib64/other.so", "/system/app/AutoVideo/lib/arm64"));
        assertTrue(CompatibilityBundleEvidence.isAllowedSelectedAssetPath(
                "resource_byd/shh/CarModel/configs/vehicle_param.yaml"));
        assertTrue(CompatibilityBundleEvidence.isAllowedSelectedAssetPath(
                "resource_ts/shh/CarModel/sedan/Vehicle_Configuration.json"));
        assertFalse(CompatibilityBundleEvidence.isAllowedSelectedAssetPath(
                "resource_byd/../private/vehicle_param.yaml"));
        assertTrue(CompatibilityBundleEvidence.isAllowedExternalConfigPath(
                "/collect2/autovideo/resource_byd/shh/CarModel/configs"
                        + "/resource/configs/vehicle_param.yaml"));
        assertFalse(CompatibilityBundleEvidence.isAllowedExternalConfigPath(
                "/collect2/autovideo/resource_byd/shh/CarModelish/configs"
                        + "/resource/configs/vehicle_param.yaml"));
        assertFalse(CompatibilityBundleEvidence.isAllowedExternalConfigPath(
                "/collect2/autovideo/resource_byd/../private/resource/configs/vehicle_param.yaml"));
        assertFalse(CompatibilityBundleEvidence.isAllowedExternalConfigPath(
                "/vendor/etc/vehicle_param.yaml"));
    }
}
