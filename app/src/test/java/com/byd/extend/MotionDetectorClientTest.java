package com.byd.extend;

import static org.junit.Assert.assertThrows;
import org.junit.Test;

public class MotionDetectorClientTest {
    @Test public void replacementRejectsProcessGroupPids() {
        assertThrows(IllegalArgumentException.class,
                () -> MotionDetectorClient.stopObsoleteWorkerCommand(0));
        assertThrows(IllegalArgumentException.class,
                () -> MotionDetectorClient.stopObsoleteWorkerCommand(-1));
    }
}
