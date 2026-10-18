package com.byd.extend;

import java.io.IOException;

/** Android build fields captured from the inspected vehicle, not BYD's platform label. */
final class MotionFirmwareProfile {
    static final String ERROR_STAGE = "firmware_compatibility";

    static final class Mismatch extends IOException {
        Mismatch(String field, String expected, String actual) {
            super("firmware mismatch: field=" + field + " expected=[" + expected
                    + "] actual=[" + actual + "]");
        }
    }

    static void verify(int sdk, String release, String display, String incremental) throws Mismatch {
        require("SDK_INT", "32", Integer.toString(sdk));
        require("RELEASE", "12", release);
        require("DISPLAY", "SKQ1.230128.001 release-keys", display);
        require("INCREMENTAL", "eng.build.20260320.231831", incremental);
    }

    private static void require(String field, String expected, String actual) throws Mismatch {
        if (!expected.equals(actual)) throw new Mismatch(field, expected, actual);
    }

    private MotionFirmwareProfile() {}
}
