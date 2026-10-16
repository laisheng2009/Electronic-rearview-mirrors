package com.byd.extend;

final class WeatherLocationSelection {
    static final long MAX_AGE_NANOS = 120_000_000_000L;

    private WeatherLocationSelection() {}

    static int selectIndex(long nowNanos, long... candidateNanos) {
        int selected = -1;
        long selectedNanos = 0L;
        for (int i = 0; i < candidateNanos.length; i++) {
            long candidateNanosValue = candidateNanos[i];
            if (rejectionReason(candidateNanosValue, nowNanos) == null
                    && (selected < 0 || candidateNanosValue > selectedNanos)) {
                selected = i;
                selectedNanos = candidateNanosValue;
            }
        }
        return selected;
    }

    static boolean shouldReplace(long candidateNanos, long currentNanos, long nowNanos) {
        return rejectionReason(candidateNanos, nowNanos) == null
                && (rejectionReason(currentNanos, nowNanos) != null
                || candidateNanos > currentNanos);
    }

    static String rejectionReason(long elapsedNanos, long nowNanos) {
        if (elapsedNanos <= 0L) return "invalid_elapsed";
        if (elapsedNanos > nowNanos) return "future_elapsed";
        return nowNanos - elapsedNanos > MAX_AGE_NANOS ? "stale" : null;
    }
}
