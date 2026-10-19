package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

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
        long deadline = MotionRecorderTimeline.finishDeadline(30_000L, 10);
        assertFalse(MotionRecorderTimeline.postRollRecorded(30_000L, deadline));
        assertFalse(MotionRecorderTimeline.postRollRecorded(39_999L, deadline));
        assertTrue(MotionRecorderTimeline.postRollRecorded(40_000L, deadline));
        assertFalse(MotionRecorderTimeline.postRollRecorded(40_000L, Long.MIN_VALUE));
    }

    @Test public void eventWindowsMergeAcrossTheCurrentUnionAndWaitOnlyWithinRingHorizon() {
        long start = MotionRecorderTimeline.eventWindowStart(10_000L, 5);
        long end = MotionRecorderTimeline.finishDeadline(10_000L, 5);
        assertTrue(MotionRecorderTimeline.windowsOverlap(start, end,
                MotionRecorderTimeline.eventWindowStart(18_000L, 5),
                MotionRecorderTimeline.finishDeadline(18_000L, 5)));

        end = Math.max(end, MotionRecorderTimeline.finishDeadline(18_000L, 5));
        assertTrue(MotionRecorderTimeline.windowsOverlap(start, end,
                MotionRecorderTimeline.eventWindowStart(26_000L, 5),
                MotionRecorderTimeline.finishDeadline(26_000L, 5)));
        end = Math.max(end, MotionRecorderTimeline.finishDeadline(26_000L, 5));
        assertFalse(MotionRecorderTimeline.windowsOverlap(start, end,
                MotionRecorderTimeline.eventWindowStart(37_000L, 5),
                MotionRecorderTimeline.finishDeadline(37_000L, 5)));

        assertEquals(17_000L, MotionRecorderTimeline.mergeCloseDeadline(10_000L, 5));
        assertEquals(42_000L, MotionRecorderTimeline.mergeCloseDeadline(10_000L, 30));
        assertEquals(0L, MotionRecorderTimeline.eventWindowStart(10_000L, 30));
    }

    @Test public void precedingKeyframeCanMakeNominallySeparatedWindowsJoin() {
        long currentCoverageEndMs = 15_000L;
        long eventMs = 20_200L;
        long requestedStartMs = MotionRecorderTimeline.eventWindowStart(eventMs, 5);
        assertEquals(15_200L, requestedStartMs);

        long alignedStartMs = MotionRecorderTimeline.chooseKeyframeStart(
                Arrays.asList(14_800L, 15_800L), requestedStartMs, eventMs);
        assertEquals(14_800L, alignedStartMs);
        assertTrue(MotionRecorderTimeline.windowsOverlap(5_000L, currentCoverageEndMs,
                alignedStartMs, MotionRecorderTimeline.finishDeadline(eventMs, 5)));

        long followingOnlyStartMs = MotionRecorderTimeline.chooseKeyframeStart(
                Arrays.asList(15_800L), requestedStartMs, eventMs);
        assertEquals(15_800L, followingOnlyStartMs);
        assertFalse(MotionRecorderTimeline.windowsOverlap(5_000L, currentCoverageEndMs,
                followingOnlyStartMs, MotionRecorderTimeline.finishDeadline(eventMs, 5)));
    }

    @Test public void joinedCoverageAppendsOnlyUnwrittenAudioAndVideoThroughNextFrameBoundary() {
        List<TestSample> samples = Arrays.asList(
                new TestSample(0, 9_900L, "v-before"),
                new TestSample(1, 9_990L, "a-before"),
                new TestSample(1, 10_010L, "a-tail-1"),
                new TestSample(1, 10_030L, "a-end"),
                new TestSample(0, 10_040L, "v-first-end"),
                new TestSample(0, 10_080L, "v-tail-1"),
                new TestSample(1, 11_000L, "a-tail-2"),
                new TestSample(0, 16_040L, "v-next-end"),
                new TestSample(0, 16_080L, "v-after-next-end"),
                new TestSample(1, 16_050L, "a-after-end"));

        long firstBoundary = MotionRecorderTimeline.firstVideoAtOrAfter(samples, 0, 10_000L);
        assertEquals(10_040L, firstBoundary);
        long videoLast = 10_040L;
        long audioLast = 10_030L;
        ArrayList<String> appended = new ArrayList<>();
        long nextBoundary = MotionRecorderTimeline.firstVideoAtOrAfter(samples, 0, 16_000L);
        assertEquals(16_040L, nextBoundary);
        for (TestSample sample : samples) {
            if (!MotionRecorderTimeline.shouldAppendSample(sample.track, sample.elapsedUs,
                    0, 1, videoLast, audioLast, nextBoundary)) continue;
            appended.add(sample.name);
            if (sample.track == 0) videoLast = sample.elapsedUs;
            else audioLast = sample.elapsedUs;
        }

        assertEquals(Arrays.asList("v-tail-1", "a-tail-2", "v-next-end"), appended);
        assertEquals(16_040L, videoLast);
        assertEquals(11_000L, audioLast);
        assertFalse(MotionRecorderTimeline.shouldAppendSample(1, 16_050L,
                0, 1, videoLast, audioLast, nextBoundary));
        assertFalse(MotionRecorderTimeline.shouldAppendSample(0, 16_080L,
                0, 1, videoLast, audioLast, nextBoundary));
    }

    private static final class TestSample implements MotionRecorderTimeline.Sample {
        final int track;
        final long elapsedUs;
        final String name;

        TestSample(int track, long elapsedUs, String name) {
            this.track = track;
            this.elapsedUs = elapsedUs;
            this.name = name;
        }

        @Override public int track() { return track; }
        @Override public long elapsedUs() { return elapsedUs; }
    }
}
