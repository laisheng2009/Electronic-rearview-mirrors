package com.byd.extend;

/** Keeps Motion ineligible until fresh valid Power and Lock values form a baseline. */
final class MotionTelemetryState {
    private int power = -1;
    private int lock = -1;
    private boolean powerBaseline;
    private boolean lockBaseline;

    void power(int raw, boolean baseline) {
        int normalized = AvasEventPolicy.normalizedPower(raw);
        if (normalized < 0) {
            invalidate();
            return;
        }
        power = normalized;
        powerBaseline |= baseline;
    }

    void lock(int value, boolean baseline) {
        if (value != 1 && value != 2) {
            invalidate();
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
        return ready() && config != null && config.eligible(power, lock);
    }

    int power() { return power; }
    int lock() { return lock; }
}
