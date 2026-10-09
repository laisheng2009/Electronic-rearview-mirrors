package com.byd.extend;

import java.util.function.BooleanSupplier;

/** Pure per-request audio policy shared by playback and deterministic JVM tests. */
final class AvasPlaybackPlan {
    interface FrameWriter { long write(long frames) throws Exception; }
    static final int EXTERIOR_SILENCE_MILLIS = 0;
    static final int NAVIGATION_SILENCE_MILLIS = 0;
    static final int PCM_GAIN_MULTIPLIER = 2;

    private AvasPlaybackPlan() {}

    static float exteriorPlayerVolume(int volume, boolean loudnessEnabled) {
        int percent = Math.max(0, Math.min(100, volume));
        // The enhancer already applies the percentage; retain attenuation only without it.
        return percent == 0 ? 0f : loudnessEnabled ? 1f : percent / 100f;
    }

    static int exteriorTargetGainMb(int volume) {
        int percent = Math.max(0, Math.min(100, volume));
        // Linear amplitude: 1%=1x, 50%=50x, 100%=100x (+40 dB); zero is muted by the track.
        return percent == 0 ? 0 : (int) Math.round(2000.0 * Math.log10(percent));
    }

    static float interiorPcmGain(int volume) {
        return Math.max(0, Math.min(100, volume)) * PCM_GAIN_MULTIPLIER / 100f;
    }

    static int silenceMillis(AvasPlaybackQueue.Kind kind) {
        if (kind == AvasPlaybackQueue.Kind.AUDITION_NAV) return NAVIGATION_SILENCE_MILLIS;
        return EXTERIOR_SILENCE_MILLIS;
    }

    static long silenceFrames(int sampleRate, int millis) {
        if (sampleRate <= 0 || millis < 0) throw new IllegalArgumentException("invalid PCM timing");
        return (long) sampleRate * millis / 1000;
    }

    static long silenceBytes(int sampleRate, int frameSize, int millis) {
        if (frameSize <= 0) throw new IllegalArgumentException("invalid PCM frame size");
        return silenceFrames(sampleRate, millis) * frameSize;
    }

    static boolean maySubmitFile(long plannedSilenceFrames, long writtenSilenceFrames,
            boolean cancelled) {
        return !cancelled && writtenSilenceFrames == plannedSilenceFrames;
    }

    static byte[] zeroPcm(int alignedBytes) {
        if (alignedBytes < 0) throw new IllegalArgumentException("invalid PCM byte count");
        return new byte[alignedBytes];
    }

    static long writeSilence(long plannedFrames, int maximumChunkFrames,
            BooleanSupplier cancelled, FrameWriter writer) throws Exception {
        if (plannedFrames < 0 || maximumChunkFrames <= 0) {
            throw new IllegalArgumentException("invalid silence write plan");
        }
        long written = 0;
        while (written < plannedFrames && !cancelled.getAsBoolean()) {
            long wanted = Math.min(maximumChunkFrames, plannedFrames - written);
            long count = writer.write(wanted);
            if (count < 0 || count > wanted) throw new IllegalStateException("invalid frame write");
            written += count;
            if (count < wanted) break;
        }
        return written;
    }

    static long tailPaddingFrames(long submittedFrames, long startupFrames) {
        if (submittedFrames < 0 || startupFrames < 0) {
            throw new IllegalArgumentException("invalid frames");
        }
        return Math.max(0, startupFrames - submittedFrames);
    }
}
