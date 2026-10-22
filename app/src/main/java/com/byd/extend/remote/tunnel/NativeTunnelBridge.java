package com.byd.extend.remote.tunnel;

import com.byd.extend.remote.security.TunnelSecretStore;
import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import com.byd.extend.remote.CameraViewingDiagnostics;

/** Narrow JNI boundary for the pinned userspace tunnel implementations. */
public final class NativeTunnelBridge {
    private static String loadedLibraryPath;
    private static final ConcurrentHashMap<String, Consumer<String>> listeners = new ConcurrentHashMap<>();

    public static void observe(String id, Consumer<String> listener) { listeners.put(id, listener); }
    public static void unobserve(String id) { listeners.remove(id); }
    public static void onNativeEvent(String id, String result) {
        Consumer<String> listener = listeners.get(id);
        if (listener != null) listener.accept(result);
    }
    public static void onNativeDiagnostic(String provider, String stage, String detail) {
        CameraViewingDiagnostics.detail(stage, provider, detail);
    }
    public static void onStoreFailure(String operation, Throwable failure) {
        CameraViewingDiagnostics.failure("native_store_" + operation, "private_store", failure);
    }

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

    public static native void nativeCancel(String provider, String instanceId);

    public static native String nativeCall(String operation, String requestJson);
}
