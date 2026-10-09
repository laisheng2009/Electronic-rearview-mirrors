package com.byd.extend;

import android.content.SharedPreferences;

/** OEM source request, not a software render limiter or a measured frame rate. */
final class CameraFrameRate {
    static final String PREF = "camera_frame_rate";
    static final int DEFAULT = 25;

    private CameraFrameRate() {}

    static boolean isValid(int fps) { return fps == 20 || fps == 25 || fps == 30; }

    static int load(SharedPreferences preferences) {
        try {
            int fps = preferences.getInt(PREF, DEFAULT);
            return isValid(fps) ? fps : DEFAULT;
        } catch (ClassCastException error) {
            return DEFAULT;
        }
    }

    static int fromSelection(int index) {
        if (index < 0 || index > 2) throw new IllegalArgumentException("invalid FPS selection");
        return 20 + index * 5;
    }

    static boolean request(Object camera, int fps) throws ReflectiveOperationException {
        if (!isValid(fps)) throw new IllegalArgumentException("invalid camera FPS");
        return Boolean.TRUE.equals(camera.getClass().getMethod("setCameraFps", int.class)
                .invoke(camera, fps));
    }
}
