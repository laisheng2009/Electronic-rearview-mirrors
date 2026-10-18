package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public class MotionCheckupFramesTest {
    @Test public void emptySceneIsSuccessfulAfterTwoFreshFrames() {
        MotionCheckupFrames check = new MotionCheckupFrames();
        assertFalse(check.observe(100, 0));
        assertTrue(check.observe(200, 0));
        assertFalse(check.motionObserved());
    }

    @Test public void duplicateInvalidAndOlderFramesDoNotProveFreshInput() {
        MotionCheckupFrames check = new MotionCheckupFrames();
        assertFalse(check.observe(0, 0));
        assertFalse(check.observe(-1, 1));
        assertFalse(check.observe(100, 0));
        assertFalse(check.observe(100, 1));
        assertFalse(check.observe(99, 1));
        assertTrue(check.observe(101, 0));
        assertFalse(check.motionObserved());
    }

    @Test public void positiveDetectionIsSeparateEvidenceForThisRun() {
        for (int raw : new int[]{-1, 1, 2}) {
            MotionCheckupFrames check = new MotionCheckupFrames();
            assertFalse(check.observe(1, raw));
            assertTrue(check.observe(2, 0));
            assertTrue(check.motionObserved());
        }
        assertFalse(new MotionCheckupFrames().motionObserved());
    }

    @Test public void errorIsNeverSuccessfulEvenForDuplicateTimestamp() {
        MotionCheckupFrames check = new MotionCheckupFrames();
        check.observe(100, 0);
        assertThrows(IllegalStateException.class, () -> check.observe(100, -2));
    }
}
