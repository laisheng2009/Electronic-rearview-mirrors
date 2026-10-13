package com.byd.extend;

/** Pure per-request audio policy shared by playback and deterministic JVM tests. */
final class AvasPlaybackPlan {
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

    static byte[] zeroPcm(int alignedBytes) {
        if (alignedBytes < 0) throw new IllegalArgumentException("invalid PCM byte count");
        return new byte[alignedBytes];
    }

    static long tailPaddingFrames(long submittedFrames, long startupFrames) {
        if (submittedFrames < 0 || startupFrames < 0) {
            throw new IllegalArgumentException("invalid frames");
        }
        return Math.max(0, startupFrames - submittedFrames);
    }
}
