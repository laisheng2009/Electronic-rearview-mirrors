package com.byd.extend;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class MotionNativeSetupTest {
    @Test public void passesActualModelIdsWithoutAnAllowlist() throws Exception {
        assertEquals(286, MotionDetectorShellMain.MotionNativeSetup.parseModel("Row: 0 value=286"));
        assertEquals(136, MotionDetectorShellMain.MotionNativeSetup.parseModel("Row: 0 value=136"));
        assertEquals(70000, MotionDetectorShellMain.MotionNativeSetup.parseModel("Row: 0 value=70000"));
    }

    @Test public void missingAmbiguousAndInvalidIdsAreNotReplacedByAGuessedModel() {
        for (String text : new String[] { "No result found.", "Row: 0 value=null",
                "Row: 0 value=286\nRow: 1 value=136", "Row: 0 value=0",
                "Row: 0 value=-1", "Row: 0 value=2147483648" }) {
            IOException error = assertThrows(IOException.class,
                    () -> MotionDetectorShellMain.MotionNativeSetup.parseModel(text));
            assertFalse(error.getMessage().isEmpty());
        }
    }
}
