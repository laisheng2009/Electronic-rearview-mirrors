package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AvasPlaybackPlanTest {
    @Test public void exteriorVolumeMapsToPlayerAndLoudnessEnhancer() {
        int[] volumes = {0, 25, 50, 60, 80, 100};
        float[] playerVolumes = {0f, 1f, 1f, 1f, 1f, 1f};
        int[] gainsMb = {0, 2796, 3398, 3556, 3806, 4000};
        for (int i = 0; i < volumes.length; i++) {
            assertEquals(playerVolumes[i], AvasPlaybackPlan.exteriorPlayerVolume(volumes[i], true), 0f);
            assertEquals(gainsMb[i], AvasPlaybackPlan.exteriorTargetGainMb(volumes[i]));
        }
        assertEquals(0f, AvasPlaybackPlan.exteriorPlayerVolume(-1, true), 0f);
        assertEquals(0, AvasPlaybackPlan.exteriorTargetGainMb(-1));
        assertEquals(1f, AvasPlaybackPlan.exteriorPlayerVolume(101, true), 0f);
        assertEquals(4000, AvasPlaybackPlan.exteriorTargetGainMb(101));
    }

    @Test public void exteriorSignalAppliesPercentageOnceAndKeepsZeroSilent() {
        for (int percent = 0; percent <= 100; percent++) {
            double signal = AvasPlaybackPlan.exteriorPlayerVolume(percent, true)
                    * Math.pow(10, AvasPlaybackPlan.exteriorTargetGainMb(percent) / 2000.0);
            assertEquals(percent, signal, Math.max(.001, percent * .0006));
            assertEquals(percent / 100f,
                    AvasPlaybackPlan.exteriorPlayerVolume(percent, false), 0f);
        }
        assertEquals(0f, AvasPlaybackPlan.interiorPcmGain(0), 0f);
        assertEquals(1f, AvasPlaybackPlan.interiorPcmGain(50), 0f);
        assertEquals(2f, AvasPlaybackPlan.interiorPcmGain(100), 0f);
    }

    @Test public void noRequestHasArtificialSilence() {
        for (AvasPlaybackQueue.Kind kind : AvasPlaybackQueue.Kind.values()) {
            assertEquals(0, AvasPlaybackPlan.silenceMillis(kind));
        }
    }

    @Test public void monoAndStereoSilenceUsesWavRateAndWholeFrames() {
        assertEquals(24_000, AvasPlaybackPlan.silenceFrames(48_000, 500));
        assertEquals(48_000, AvasPlaybackPlan.silenceBytes(48_000, 2, 500));
        assertEquals(96_000, AvasPlaybackPlan.silenceBytes(48_000, 4, 500));
        assertEquals(22_050, AvasPlaybackPlan.silenceFrames(44_100, 500));
        assertEquals(88_200, AvasPlaybackPlan.silenceBytes(44_100, 4, 500));
    }

    @Test public void cancellationOrPartialZerosPreventFileSubmission() {
        assertFalse(AvasPlaybackPlan.maySubmitFile(24_000, 12_000, true));
        assertFalse(AvasPlaybackPlan.maySubmitFile(24_000, 12_000, false));
        assertFalse(AvasPlaybackPlan.maySubmitFile(24_000, 24_000, true));
        assertTrue(AvasPlaybackPlan.maySubmitFile(24_000, 24_000, false));
        assertTrue(AvasPlaybackPlan.maySubmitFile(0, 0, false));
    }

    @Test public void productionSilenceWriterStopsOnCancellationAndUsesZeroPcm() throws Exception {
        byte[] zeroPcm = AvasPlaybackPlan.zeroPcm(16);
        for (byte value : zeroPcm) assertEquals(0, value);

        boolean[] cancelled = {false};
        int[] calls = {0};
        long written = AvasPlaybackPlan.writeSilence(12, 4, () -> cancelled[0], frames -> {
            calls[0]++;
            cancelled[0] = true;
            return frames;
        });
        assertEquals(4, written);
        assertEquals(1, calls[0]);
        assertFalse(AvasPlaybackPlan.maySubmitFile(12, written, cancelled[0]));

        long partial = AvasPlaybackPlan.writeSilence(12, 4, () -> false, frames -> 2);
        assertEquals(2, partial);
        assertFalse(AvasPlaybackPlan.maySubmitFile(12, partial, false));
    }

    @Test public void shortFileGetsOnlyNecessaryTailPadding() {
        assertEquals(0, AvasPlaybackPlan.tailPaddingFrames(1024, 1024));
        assertEquals(0, AvasPlaybackPlan.tailPaddingFrames(2048, 1024));
        assertEquals(768, AvasPlaybackPlan.tailPaddingFrames(256, 1024));
        assertEquals(0, AvasPlaybackPlan.tailPaddingFrames(1024 + 256, 1024));
    }
}
