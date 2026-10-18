package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public class MotionFirmwareProfileTest {
    @Test public void acceptsCapturedAndroidBuildFields() throws Exception {
        MotionFirmwareProfile.verify(32, "12", "SKQ1.230128.001 release-keys",
                "eng.build.20260320.231831");
    }

    @Test public void bydPlatformLabelIsNotTheAndroidDisplayId() throws Exception {
        try {
            MotionFirmwareProfile.verify(32, "12", "Di5.0_USER_SIGN_SV162_202603202318_Q0311",
                    "eng.build.20260320.231831");
            fail("a different display identifier must be rejected");
        } catch (MotionFirmwareProfile.Mismatch error) {
            assertEquals("firmware mismatch: field=DISPLAY expected=[SKQ1.230128.001 release-keys]"
                    + " actual=[Di5.0_USER_SIGN_SV162_202603202318_Q0311]", error.getMessage());
        }
    }

    @Test public void otherBuildMismatchesIdentifyTheirFieldAndValues() throws Exception {
        assertMismatch("SDK_INT", "32", "31", 31, "12", "SKQ1.230128.001 release-keys",
                "eng.build.20260320.231831");
        assertMismatch("RELEASE", "12", "13", 32, "13", "SKQ1.230128.001 release-keys",
                "eng.build.20260320.231831");
        assertMismatch("INCREMENTAL", "eng.build.20260320.231831", "other", 32, "12",
                "SKQ1.230128.001 release-keys", "other");
        assertMismatch("DISPLAY", "SKQ1.230128.001 release-keys", "null", 32, "12", null,
                "eng.build.20260320.231831");
    }

    private static void assertMismatch(String field, String expected, String actual,
            int sdk, String release, String display, String incremental) throws Exception {
        try {
            MotionFirmwareProfile.verify(sdk, release, display, incremental);
            fail("mismatched build field must be rejected");
        } catch (MotionFirmwareProfile.Mismatch error) {
            assertEquals("firmware mismatch: field=" + field + " expected=[" + expected
                    + "] actual=[" + actual + "]", error.getMessage());
        }
    }
}
