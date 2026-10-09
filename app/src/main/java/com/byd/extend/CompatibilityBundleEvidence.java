package com.byd.extend;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Narrow path policy for the extra evidence used by the compatibility bundle. */
final class CompatibilityBundleEvidence {
    private static final String LIBRARY_NAME = "libavmapi.so";
    private static final String[] APP_NATIVE_ROOTS = {
            "/data/app/", "/system/app/", "/system/priv-app/",
            "/system_ext/app/", "/system_ext/priv-app/", "/vendor/app/",
            "/vendor/priv-app/", "/odm/app/", "/odm/priv-app/",
            "/product/app/", "/product/priv-app/"
    };
    private static final String[] SYSTEM_LIB64_DIRS = {
            "/vendor/lib64", "/odm/lib64", "/system/lib64",
            "/system_ext/lib64", "/product/lib64"
    };
    private static final String[] SYSTEM_LIB32_DIRS = {
            "/vendor/lib", "/odm/lib", "/system/lib",
            "/system_ext/lib", "/product/lib"
    };
    private static final String EXTERNAL_CONFIG_PREFIX =
            "/collect2/autovideo/resource_byd/";
    private static final String EXTERNAL_CONFIG_SUFFIX =
            "/resource/configs/vehicle_param.yaml";

    private CompatibilityBundleEvidence() {}

    static PackageAbi resolvePackageAbi(String nativeLibraryDir) {
        String abi = abiFromPath(nativeLibraryDir);
        if (!abi.isEmpty()) {
            return new PackageAbi(abi, "native_library_dir_abi_segment");
        }
        abi = abiFromBundledLayout(nativeLibraryDir);
        if (!abi.isEmpty()) {
            return new PackageAbi(abi, "native_library_dir_bundled_layout");
        }
        return new PackageAbi("", "unavailable");
    }

    static List<NativeLibraryCandidate> nativeLibraryCandidates(String nativeLibraryDir) {
        List<NativeLibraryCandidate> result = new ArrayList<>();
        PackageAbi packageAbi = resolvePackageAbi(nativeLibraryDir);

        if (isSafeApplicationNativeDir(nativeLibraryDir)) {
            addCandidate(result, nativeLibraryDir + "/" + LIBRARY_NAME,
                    "autovideo_native_library_candidate", packageAbi.abi);
        }
        String[] systemDirs = is64BitAbi(packageAbi.abi) ? SYSTEM_LIB64_DIRS
                : is32BitAbi(packageAbi.abi) ? SYSTEM_LIB32_DIRS : new String[0];
        String systemType = "system_native_library_candidate";
        for (String directory : systemDirs) {
            addCandidate(result, directory + "/" + LIBRARY_NAME,
                    systemType, packageAbi.abi);
        }
        return Collections.unmodifiableList(result);
    }

    static boolean isAllowedNativeLibraryCandidate(String path, String nativeLibraryDir) {
        if (path == null) return false;
        for (NativeLibraryCandidate candidate : nativeLibraryCandidates(nativeLibraryDir)) {
            if (candidate.path.equals(path)) return true;
        }
        return false;
    }

    static boolean isAllowedSelectedAssetPath(String path) {
        return isSafeResourcePath(path)
                && (path.startsWith("resource_ts/") || path.startsWith("resource_byd/"))
                && (path.endsWith(".json") || path.endsWith(".yaml") || path.endsWith(".yml"));
    }

    static boolean isAllowedExternalConfigPath(String path) {
        if (path == null || !path.startsWith(EXTERNAL_CONFIG_PREFIX)
                || !path.endsWith(EXTERNAL_CONFIG_SUFFIX) || !isSafeAbsolutePath(path)) {
            return false;
        }
        String modelPath = path.substring(EXTERNAL_CONFIG_PREFIX.length(),
                path.length() - EXTERNAL_CONFIG_SUFFIX.length());
        if (modelPath.isEmpty()) return false;
        for (String part : modelPath.split("/", -1)) {
            if (!part.matches("[A-Za-z0-9_-]+")) return false;
        }
        String[] parts = modelPath.split("/", -1);
        return parts.length >= 2 && "CarModel".equals(parts[1]);
    }

    private static boolean isSafeResourcePath(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/")
                || path.contains("\\") || path.contains("..") || path.contains("//")) {
            return false;
        }
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || !part.matches("[A-Za-z0-9._-]+")) return false;
        }
        return true;
    }

    private static boolean isSafeApplicationNativeDir(String path) {
        if (!isSafeAbsolutePath(path) || path.endsWith("/")) return false;
        for (String root : APP_NATIVE_ROOTS) {
            if (path.startsWith(root) && path.length() > root.length()) return true;
        }
        return false;
    }

    private static boolean isSafeAbsolutePath(String path) {
        return path != null && path.startsWith("/") && !path.contains("..")
                && path.indexOf('\0') < 0 && !path.contains("//");
    }

    private static String abiFromPath(String path) {
        if (!isSafeAbsolutePath(path)) return "";
        String lower = path.toLowerCase(Locale.US);
        if (lower.endsWith("/arm64") || lower.endsWith("/arm64-v8a")
                || lower.endsWith("/x86_64") || lower.endsWith("/riscv64")) return "64";
        if (lower.endsWith("/arm") || lower.endsWith("/armeabi-v7a")
                || lower.endsWith("/armeabi") || lower.endsWith("/x86")) return "32";
        return "";
    }

    private static String abiFromBundledLayout(String path) {
        if (!isSafeAbsolutePath(path)) return "";
        String lower = path.toLowerCase(Locale.US);
        if (lower.matches("^/(system|system_ext|vendor|odm|product)/(app|priv-app)"
                + "/lib64/[a-z0-9._-]+$")) return "64";
        if (lower.matches("^/(system|system_ext|vendor|odm|product)/(app|priv-app)"
                + "/lib/[a-z0-9._-]+$")) return "32";
        return "";
    }

    private static boolean is64BitAbi(String abi) { return "64".equals(abi); }
    private static boolean is32BitAbi(String abi) { return "32".equals(abi); }

    private static void addCandidate(
            List<NativeLibraryCandidate> result, String path, String origin, String abi) {
        for (NativeLibraryCandidate candidate : result) {
            if (candidate.path.equals(path)) return;
        }
        result.add(new NativeLibraryCandidate(path, origin, abi));
    }

    static final class NativeLibraryCandidate {
        final String path;
        final String origin;
        final String abi;

        NativeLibraryCandidate(String path, String origin, String abi) {
            this.path = path;
            this.origin = origin;
            this.abi = abi;
        }
    }

    static final class PackageAbi {
        final String abi;
        final String source;

        PackageAbi(String abi, String source) {
            this.abi = abi;
            this.source = source;
        }
    }
}
