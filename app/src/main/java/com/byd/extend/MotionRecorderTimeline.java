package com.byd.extend;

import java.util.List;

/** Pure timestamp policy shared by the real encoder loop and JVM tests. */
final class MotionRecorderTimeline {
    static final long SEGMENT_MILLIS = 120_000L;

    private MotionRecorderTimeline() { }

    static long chooseKeyframeStart(List<Long> keyframes, long desiredStartMs, long eventMs) {
        long preceding = -1;
        long following = -1;
        long firstAfterEvent = -1;
        for (long keyframe : keyframes) {
            if (keyframe <= eventMs && keyframe <= desiredStartMs) preceding = keyframe;
            if (following < 0 && keyframe >= desiredStartMs && keyframe <= eventMs) following = keyframe;
            if (firstAfterEvent < 0 && keyframe > eventMs && keyframe - eventMs <= 1_500L)
                firstAfterEvent = keyframe;
        }
        return preceding >= 0 ? preceding : following >= 0 ? following : firstAfterEvent;
    }

    static long finishDeadline(long eventElapsedMs, int afterSeconds) {
        int bounded = Math.max(1, Math.min(30, afterSeconds));
        return eventElapsedMs + bounded * 1000L;
    }

    static boolean postRollRecorded(long lastVideoMs, long deadlineMs) {
        return deadlineMs != Long.MIN_VALUE && lastVideoMs >= deadlineMs;
    }

    static boolean startsSegmentAt(long segmentStartMs, long frameElapsedMs, boolean keyframe) {
        return keyframe && frameElapsedMs - segmentStartMs >= SEGMENT_MILLIS;
    }
}
