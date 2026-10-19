package com.byd.extend;

/** Keeps Motion ineligible until fresh valid telemetry baselines meet its activation mode. */
final class MotionTelemetryState {
    private int power = -1;
    private int lock = -1;
    private boolean powerBaseline;
    private boolean lockBaseline;

    void power(int raw, boolean baseline) {
        int normalized = AvasEventPolicy.normalizedPower(raw);
        if (normalized < 0) {
            power = -1;
            powerBaseline = false;
            return;
        }
        power = normalized;
        powerBaseline |= baseline;
    }

    void lock(int value, boolean baseline) {
        if (value != 1 && value != 2) {
            lock = -1;
            lockBaseline = false;
            return;
        }
        lock = value;
        lockBaseline |= baseline;
    }

    void invalidate() {
        power = lock = -1;
        powerBaseline = lockBaseline = false;
    }

    boolean ready() {
        return powerBaseline && lockBaseline && power >= 0 && (lock == 1 || lock == 2);
    }

    boolean eligible(MotionConfig config) {
        return readyFor(config) && config.eligible(power, lock);
    }

    boolean readyFor(MotionConfig config) {
        return config != null && powerBaseline && power >= 0
                && (config.lockCondition == 0 || lockBaseline && (lock == 1 || lock == 2));
    }

    int power() { return power; }
    int lock() { return lock; }
}
