package com.byd.extend.remote.tunnel;

import com.byd.extend.remote.security.TunnelSecretStore;
import java.io.File;

/** Narrow JNI boundary for the pinned userspace tunnel implementations. */
public final class NativeTunnelBridge {
    private static String loadedLibraryPath;

    private NativeTunnelBridge() {}

    public static synchronized void loadFromPath(String path) {
        if (path == null || path.isBlank() || !new File(path).isAbsolute()) {
            throw new IllegalArgumentException("The native tunnel library path must be absolute.");
        }
        String absolutePath = new File(path).getAbsolutePath();
        if (loadedLibraryPath != null) {
            if (!loadedLibraryPath.equals(absolutePath)) {
                throw new IllegalStateException("The native tunnel library is already loaded from another path.");
            }
            return;
        }
        System.load(absolutePath);
        loadedLibraryPath = absolutePath;
    }

    public static native boolean nativeInitialize(TunnelSecretStore secretStore, String runtimeDirectory);

    public static native void nativeNetworkChanged();

    public static native String nativeCall(String operation, String requestJson);
}
