package com.byd.extend;

import android.content.Context;
import android.util.Log;
import java.io.File;

/** Process-local filtering; never changes system log properties or functional events. */
final class OemLogPolicy {
    private static boolean installed;
    static synchronized void initialize(Context context) {
        if (installed) return;
        try {
            String directory = context.getPackageManager()
                    .getApplicationInfo("com.byd.extend", 0).nativeLibraryDir;
            MotionYuvPacker.loadNative(new File(directory, "libmotion_yuv.so").getPath());
            installed = install();
            configure(DiagnosticLogPolicy.extended());
            Log.i("OemLogPolicy", "process_local_filter=" + installed);
        } catch (Exception | LinkageError error) {
            Log.w("OemLogPolicy", "OEM filtering unavailable; retaining logs", error);
        }
    }
    static synchronized void configure(boolean extended) {
        if (installed) setVerbose(extended);
    }
    private static native boolean install();
    private static native void setVerbose(boolean enabled);
}
