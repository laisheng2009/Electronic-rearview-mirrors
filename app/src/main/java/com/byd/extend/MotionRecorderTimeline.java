package com.byd.extend;

import java.util.List;

/** Pure timestamp policy shared by the real encoder loop and JVM tests. */
final class MotionRecorderTimeline {
    static final long SEGMENT_MILLIS = 120_000L;
    static final long MERGE_HEADROOM_MILLIS = 2_000L;

    interface Sample {
        int track();
        long elapsedUs();
    }

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

    static long eventWindowStart(long eventElapsedMs, int beforeSeconds) {
        return Math.max(0L, eventElapsedMs - Math.max(0, Math.min(30, beforeSeconds)) * 1000L);
    }

    static boolean windowsOverlap(long firstStartMs, long firstEndMs,
            long secondStartMs, long secondEndMs) {
        return firstStartMs <= secondEndMs && secondStartMs <= firstEndMs;
    }

    static long mergeCloseDeadline(long contentEndMs, int beforeSeconds) {
        long preRollMs = Math.max(0, Math.min(30, beforeSeconds)) * 1000L;
        long waitMs = preRollMs + MERGE_HEADROOM_MILLIS;
        return contentEndMs > Long.MAX_VALUE - waitMs ? Long.MAX_VALUE : contentEndMs + waitMs;
    }

    static long firstVideoAtOrAfter(List<? extends Sample> samples, int videoTrack, long deadlineUs) {
        long boundary = Long.MAX_VALUE;
        for (Sample sample : samples) {
            if (sample.track() == videoTrack && sample.elapsedUs() >= deadlineUs)
                boundary = Math.min(boundary, sample.elapsedUs());
        }
        return boundary;
    }

    static boolean shouldAppendSample(int track, long elapsedUs, int videoTrack, int audioTrack,
            long lastVideoUs, long lastAudioUs, long throughUs) {
        if (elapsedUs > throughUs) return false;
        if (track == videoTrack) return elapsedUs > lastVideoUs;
        return track == audioTrack && elapsedUs > lastAudioUs;
    }

    static boolean postRollRecorded(long lastVideoMs, long deadlineMs) {
        return deadlineMs != Long.MIN_VALUE && lastVideoMs >= deadlineMs;
    }

    static boolean startsSegmentAt(long segmentStartMs, long frameElapsedMs, boolean keyframe) {
        return keyframe && frameElapsedMs - segmentStartMs >= SEGMENT_MILLIS;
    }
}
