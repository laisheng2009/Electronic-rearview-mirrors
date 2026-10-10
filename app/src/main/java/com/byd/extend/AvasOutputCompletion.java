package com.byd.extend;

/** Presentation time is a frame/time pair, not just the Java playback-head counter. */
final class AvasOutputCompletion {
    static long remainingNs(long frames, int sampleRate, long stampFrame, long stampNano,
            long nowNano, int bufferFrames) {
        if (frames <= 0) return 0;
        if (sampleRate <= 0 || bufferFrames < 0) throw new IllegalArgumentException("Invalid PCM format");
        long bufferNs = (long) bufferFrames * 1_000_000_000L / sampleRate;
        // shortcut: if OEM timestamps are absent/stale, retain one actual track-buffer duration.
        if (stampFrame < 0 || stampNano <= 0 || Math.abs(nowNano - stampNano) > 1_000_000_000L)
            return bufferNs;
        long remaining = stampNano - nowNano
                + Math.max(0, frames - stampFrame) * 1_000_000_000L / sampleRate;
        return Math.max(0, Math.min(remaining, Math.max(bufferNs, 1_000_000_000L)));
    }
    private AvasOutputCompletion() { }
}
