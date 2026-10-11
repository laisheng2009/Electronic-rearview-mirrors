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
        // The enhancer cannot attenuate: the track handles the sub-unity end of the curve.
        return loudnessEnabled ? (float) Math.min(1.0,
                Math.pow(10.0, 34.0 / 20.0) * percent / 100.0) : percent / 100f;
    }

    static int exteriorTargetGainMb(int volume) {
        int percent = Math.max(0, Math.min(100, volume));
        // Linear amplitude, capped at +34 dB; zero is muted by the track.
        return percent == 0 ? 0 : Math.max(0,
                (int) Math.round(3400.0 + 2000.0 * Math.log10(percent / 100.0)));
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
