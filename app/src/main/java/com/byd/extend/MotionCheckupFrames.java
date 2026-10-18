package com.byd.extend;

/** Evidence from the current manual run, not a cached first-inference flag. */
final class MotionCheckupFrames {
    private long lastTimestamp;
    private int freshFrames;
    private boolean motion;

    boolean observe(long timestampNs, int raw) {
        if (raw == MotionEventPolicy.ERROR_RESULT)
            throw new IllegalStateException("native detect returned -2; getState was not called");
        if (timestampNs <= 0 || timestampNs <= lastTimestamp) return false;
        lastTimestamp = timestampNs;
        freshFrames++;
        motion |= raw != 0;
        return freshFrames >= 2;
    }

    boolean motionObserved() { return motion; }
}
