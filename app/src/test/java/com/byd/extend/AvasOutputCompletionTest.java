package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AvasOutputCompletionTest {
    @Test public void frameGapIsCorrectedByItsTimestampAge() {
        long now = 5_000_000_000L;
        assertEquals(109_000_000L, AvasOutputCompletion.remainingNs(
                48_000, 48_000, 42_768, now, now, 960));
        assertEquals(0, AvasOutputCompletion.remainingNs(
                48_000, 48_000, 42_768, now - 200_000_000L, now, 960));
        assertEquals(0, AvasOutputCompletion.remainingNs(
                48_000, 48_000, 48_000, now, now, 960));
    }

    @Test public void missingOrStaleTimestampUsesActualBufferNotLeadingSilence() {
        assertEquals(20_000_000L, AvasOutputCompletion.remainingNs(
                48_000, 48_000, -1, -1, 5_000_000_000L, 960));
        assertEquals(20_000_000L, AvasOutputCompletion.remainingNs(
                48_000, 48_000, 0, 1_000_000_000L, 5_000_000_000L, 960));
        assertEquals(0, AvasOutputCompletion.remainingNs(0, 48_000, -1, -1, 5, 960));
        assertThrows(IllegalArgumentException.class,
                () -> AvasOutputCompletion.remainingNs(1, 0, 0, 1, 2, 0));
    }

    @Test public void outputIsQuiescedBeforeGainAndRouteAreReleased() throws Exception {
        String source = CameraRuntimeContinuityTest.source("AvasAudioPlayer");
        String event = source.substring(source.indexOf("session.finalSample(output)"),
                source.indexOf("void playMicrophone("));
        assertTrue(event.indexOf("release(output)") < event.indexOf("exteriorGain.close()"));
        assertTrue(event.indexOf("exteriorGain.close()") < event.indexOf("releaseExteriorSession(diagnostics)"));
        String release = source.substring(source.indexOf("private static void release(AudioTrack output)"),
                source.indexOf("private static void release(AudioTrack output)") + 850);
        assertTrue(release.indexOf("output.setVolume(0f)") < release.indexOf("output.pause()"));
        assertTrue(release.indexOf("output.flush()") < release.indexOf("output.release()"));
        assertFalse(release.contains("output.stop()"));
        String engine = source.substring(source.indexOf("void drain() throws Exception"),
                source.indexOf("long submittedFrames()"));
        assertTrue(engine.contains("awaitPresentation(track"));
        assertTrue(source.contains("\"timestamp_nano\", timestampNano"));
    }
}
