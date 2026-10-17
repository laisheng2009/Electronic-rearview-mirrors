package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class MotionRecorderTimelineTest {
    @Test public void eventStartUsesDecodableKeyframeAtOrBeforeRequestedPreRoll() {
        assertEquals(8_000L, MotionRecorderTimeline.chooseKeyframeStart(
                Arrays.asList(5_000L, 8_000L, 10_000L, 12_000L), 9_000L, 12_000L));
        assertEquals(10_000L, MotionRecorderTimeline.chooseKeyframeStart(
                Arrays.asList(10_000L, 12_000L), 8_000L, 12_000L));
        assertEquals(13_000L, MotionRecorderTimeline.chooseKeyframeStart(
                Arrays.asList(13_000L), 8_000L, 12_000L));
        assertEquals(-1L, MotionRecorderTimeline.chooseKeyframeStart(
                Arrays.asList(14_000L), 8_000L, 12_000L));
    }

    @Test public void postRollIsBoundedAndLongSegmentsRollOnlyAtKeyframes() {
        assertEquals(31_000L, MotionRecorderTimeline.finishDeadline(30_000L, 1));
        assertEquals(60_000L, MotionRecorderTimeline.finishDeadline(30_000L, 30));
        assertEquals(60_000L, MotionRecorderTimeline.finishDeadline(30_000L, 120));
        assertFalse(MotionRecorderTimeline.startsSegmentAt(1_000L, 120_000L, false));
        assertTrue(MotionRecorderTimeline.startsSegmentAt(1_000L, 121_000L, true));
        assertFalse(MotionRecorderTimeline.startsSegmentAt(1_000L, 120_999L, true));
    }
}
