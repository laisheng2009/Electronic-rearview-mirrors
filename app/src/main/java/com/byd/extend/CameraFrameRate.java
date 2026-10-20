package com.byd.extend;

import android.content.SharedPreferences;

/** OEM source request, not a software render limiter or a measured frame rate. */
public final class CameraFrameRate {
    static final String PREF = "camera_frame_rate";
    private static final int LEGACY_FPS = 20;
    static final int DEFAULT = 25;

    private CameraFrameRate() {}

    static boolean isValid(int fps) { return fps == 15 || fps == 25 || fps == 30; }

    static int normalize(int fps) {
        if (fps == LEGACY_FPS) return 15;
        return isValid(fps) ? fps : DEFAULT;
    }

    static int load(SharedPreferences preferences) {
        try {
            return normalize(preferences.getInt(PREF, DEFAULT));
        } catch (ClassCastException error) {
            return DEFAULT;
        }
    }

    static void migrateLegacyPreference(SharedPreferences preferences) {
        if (!preferences.contains(PREF)) return;
        try {
            if (preferences.getInt(PREF, DEFAULT) == LEGACY_FPS) {
                preferences.edit().putInt(PREF, normalize(LEGACY_FPS)).commit();
            }
        } catch (ClassCastException ignored) {
            // Preserve an unexpected stored type; load() already falls back safely.
        }
    }

    public static int fromSelection(int index) {
        switch (index) {
            case 0: return 15;
            case 1: return 25;
            case 2: return 30;
            default: throw new IllegalArgumentException("invalid FPS selection");
        }
    }

    static boolean request(Object camera, int fps) throws ReflectiveOperationException {
        if (!isValid(fps)) throw new IllegalArgumentException("invalid camera FPS");
        return Boolean.TRUE.equals(camera.getClass().getMethod("setCameraFps", int.class)
                .invoke(camera, fps));
    }
}
