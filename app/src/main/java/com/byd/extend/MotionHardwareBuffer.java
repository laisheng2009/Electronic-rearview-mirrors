package com.byd.extend;

import android.hardware.HardwareBuffer;

/** Used only while the source Image and the current EGL context are owned by the caller. */
final class MotionHardwareBuffer {
    static native long bind(HardwareBuffer buffer, int texture);
    static native void release(long image);
}
