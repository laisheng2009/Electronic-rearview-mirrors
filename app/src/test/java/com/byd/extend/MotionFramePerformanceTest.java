package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.*;

public class MotionFramePerformanceTest {
    @Test public void separatesPackingDetectionAndWaitingTimeAtThirtySecondBoundary() {
        MotionDetectorShellMain.FramePerformance stats = new MotionDetectorShellMain.FramePerformance();
        stats.record(1_000_000_000L, 1_010_000_000L, 1_015_000_000L, 1_035_000_000L);
        stats.record(30_900_000_000L, 30_905_000_000L, 30_910_000_000L, 30_950_000_000L);
        assertFalse(stats.due());
        assertEquals(2, stats.frames);
        assertEquals(15_000_000L, stats.packTotalNs);
        assertEquals(10_000_000L, stats.packMaxNs);
        assertEquals(60_000_000L, stats.detectTotalNs);
        assertEquals(40_000_000L, stats.detectMaxNs);
        assertEquals(29_950_000_000L, stats.windowNs());

        stats.record(30_970_000_000L, 30_980_000_000L, 30_985_000_000L, 31_000_000_000L);
        assertTrue(stats.due());
        assertEquals(30_000_000_000L, stats.windowNs());
    }

    @Test public void resetDropsPreviousWindowAndIdleTime() {
        MotionDetectorShellMain.FramePerformance stats = new MotionDetectorShellMain.FramePerformance();
        assertEquals(0, stats.frames);
        assertFalse(stats.due());
        stats.record(1, 11, 16, 36);
        assertEquals(35, stats.windowNs());
        stats.reset();
        assertEquals(0, stats.frames);
        assertEquals(0, stats.windowNs());
        assertEquals(0, stats.packTotalNs);
        assertEquals(0, stats.detectTotalNs);
        assertFalse(stats.due());

        stats.record(60_000_000_000L, 60_000_000_003L, 60_000_000_009L, 60_000_000_013L);
        assertEquals(1, stats.frames);
        assertEquals(3, stats.packMaxNs);
        assertEquals(4, stats.detectMaxNs);
        assertEquals(13, stats.windowNs());
        assertFalse(stats.due());
    }
}
