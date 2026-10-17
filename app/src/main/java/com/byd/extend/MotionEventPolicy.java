package com.byd.extend;

/** Deterministic time policy for detector warm-up and renewable warning leases. */
final class MotionEventPolicy {
    static final int ERROR_RESULT = -2;
    static final int LEVEL_NONE = 0;
    static final int LEVEL_ONE = 1;
    static final int LEVEL_TWO = 2;

    private boolean eligible;
    private long eligibleAtMs;
    private long warningUntilMs;

    void setEligible(boolean next, long nowMs) {
        if (nowMs < 0) throw new IllegalArgumentException("negative elapsed time");
        if (next == eligible) return;
        eligible = next;
        eligibleAtMs = next ? nowMs : 0L;
        warningUntilMs = 0L;
    }

    Decision observe(int nativeResult, long nowMs) {
        if (nowMs < 0) throw new IllegalArgumentException("negative elapsed time");
        expire(nowMs);
        if (nativeResult == ERROR_RESULT) return Decision.error(nowMs);
        if (!eligible || nowMs - eligibleAtMs < MotionDetectorProtocol.WARMUP_MS)
            return Decision.suppressed(nowMs, warningUntilMs);
        if (nativeResult == 0) return Decision.none(nowMs, warningUntilMs);
        int level = nativeResult < 0 ? LEVEL_ONE : LEVEL_TWO;
        warningUntilMs = nowMs + MotionDetectorProtocol.WARNING_MS;
        return Decision.detection(level, nowMs, warningUntilMs, nativeResult);
    }

    void stop(long nowMs) {
        if (nowMs < 0) throw new IllegalArgumentException("negative elapsed time");
        eligible = false;
        eligibleAtMs = 0L;
        warningUntilMs = 0L;
    }

    boolean warningActive(long nowMs) {
        if (nowMs < 0) throw new IllegalArgumentException("negative elapsed time");
        expire(nowMs);
        return eligible && warningUntilMs > nowMs;
    }

    long warningUntilMs() { return warningUntilMs; }
    boolean eligible() { return eligible; }

    private void expire(long nowMs) {
        if (warningUntilMs != 0L && nowMs >= warningUntilMs) warningUntilMs = 0L;
    }

    static final class Decision {
        final int level;
        final long elapsedMs;
        final long warningUntilMs;
        final int nativeResult;
        final boolean error;
        final boolean suppressed;

        private Decision(int level, long elapsedMs, long warningUntilMs, int nativeResult,
                boolean error, boolean suppressed) {
            this.level = level;
            this.elapsedMs = elapsedMs;
            this.warningUntilMs = warningUntilMs;
            this.nativeResult = nativeResult;
            this.error = error;
            this.suppressed = suppressed;
        }

        static Decision error(long nowMs) {
            return new Decision(LEVEL_NONE, nowMs, 0L, ERROR_RESULT, true, false);
        }

        static Decision suppressed(long nowMs, long untilMs) {
            return new Decision(LEVEL_NONE, nowMs, untilMs, 0, false, true);
        }

        static Decision none(long nowMs, long untilMs) {
            return new Decision(LEVEL_NONE, nowMs, untilMs, 0, false, false);
        }

        static Decision detection(int level, long nowMs, long untilMs, int raw) {
            return new Decision(level, nowMs, untilMs, raw, false, false);
        }
    }
}
