package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public class MotionCameraEventsTest {
    @Test public void onlyCurrentRecordingFailureOrSharedProducerClosureAffectsMotion() {
        assertFalse(MotionDetectorProtocol.isMotionCameraLoss("camera_error", "activity", 12, 12));
        assertFalse(MotionDetectorProtocol.isMotionCameraLoss("camera_error", "overlay", 12, 12));
        assertFalse(MotionDetectorProtocol.isMotionCameraLoss("camera_error", "motion_preview_rear", 12, 12));
        assertFalse(MotionDetectorProtocol.isMotionCameraLoss("camera_error", "motion_recording", 11, 12));
        assertFalse(MotionDetectorProtocol.isMotionCameraLoss("camera_error", "motion_recording", 12, 0));
        assertTrue(MotionDetectorProtocol.isMotionCameraLoss("camera_error", "motion_recording", 12, 12));
        assertTrue(MotionDetectorProtocol.isMotionCameraLoss("camera_producer_closed", "", 0, 0));
        assertFalse(MotionDetectorProtocol.isMotionCameraLoss("camera_closed", "activity", 12, 12));
    }
}
