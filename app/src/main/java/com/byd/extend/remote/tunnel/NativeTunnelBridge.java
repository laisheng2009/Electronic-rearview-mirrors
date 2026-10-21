package com.byd.extend.remote.tunnel;

import com.byd.extend.remote.security.KeystoreSecretStore;

/** Narrow JNI boundary for the pinned userspace tunnel implementations. */
public final class NativeTunnelBridge {
    static {
        System.loadLibrary("camera_tunnel");
    }

    private NativeTunnelBridge() {}

    public static native boolean nativeInitialize(KeystoreSecretStore secretStore, String runtimeDirectory);

    public static native String nativeCall(String operation, String requestJson);
}
