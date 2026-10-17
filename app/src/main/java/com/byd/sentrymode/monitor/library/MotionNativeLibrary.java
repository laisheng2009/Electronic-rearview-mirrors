package com.byd.sentrymode.monitor.library;

import android.content.res.AssetManager;

/** Exact OEM JNI symbol owner. It does not launch or impersonate the Sentry application. */
public final class MotionNativeLibrary {
    public native int init(AssetManager assets, int width, int height, boolean mirrorsOpen, int carId);
    public native int detect(byte[] nv12, int width, int height, int sensitivity);
    public native int getState();
    public native void delete();
}
