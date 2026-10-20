package com.byd.extend;

/** One bounded acquisition; callbacks and settings changes must not extend it. */
final class WeatherLocationAttempt {
    static final long TIMEOUT_MS = 60_000L;
    private long startedAt = -1L;

    void start(long elapsedMs) {
        if (startedAt < 0L) startedAt = elapsedMs;
    }

    long remaining(long elapsedMs) {
        return startedAt < 0L ? TIMEOUT_MS
                : Math.max(0L, TIMEOUT_MS - Math.max(0L, elapsedMs - startedAt));
    }

    long duration(long elapsedMs) {
        return startedAt < 0L ? 0L : Math.max(0L, elapsedMs - startedAt);
    }

    void clear() {
        startedAt = -1L;
    }
}
