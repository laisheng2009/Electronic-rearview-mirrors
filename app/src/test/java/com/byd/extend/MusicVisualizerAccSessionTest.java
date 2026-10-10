package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;


import org.junit.Test;

public final class MusicVisualizerAccSessionTest {

    @Test
    public void failedStartKeepsClaimUntilCleanupStopRetrySucceeds() throws Throwable {
        Harness harness = new Harness();
        harness.session.readFailed("unsupported_get");

        try {
            harness.session.requestStart(true, harness.oem::failStart);
        } catch (IllegalStateException expected) {
            // Runtime attempts stop cleanup after a failed start request.
        }
        assertTrue(harness.session.outputClaimed());


        try {
            harness.session.requestStop(false, harness.oem::failStopOnce);
        } catch (IllegalStateException expected) {
            // A failed stop keeps the claim live for the runtime's scheduled retry.
        }
        assertTrue(harness.session.outputClaimed());
        assertTrue(harness.session.requestStop(false, harness.oem::stop));
        assertFalse(harness.session.outputClaimed());
        assertEquals(2, harness.oem.stopRequests);
    }

    private static final class Harness {
        final FakeOem oem = new FakeOem();
        final MusicVisualizerAccSession session = new MusicVisualizerAccSession(
                new MusicVisualizerAccSession.Scheduler() {
                    @Override public void postDelayed(Runnable task, long delayMs) { }
                    @Override public void removeCallbacks(Runnable task) { }
                }, new MusicVisualizerAccSession.Listener() {
                    @Override public void onAccOff(String reason) { }
                    @Override public void onAccOnReady(String reason) { }
                    @Override public void onUnavailable(String reason) { }
                });
    }

    private static final class FakeOem {
        int stopRequests;
        void failStart() { throw new IllegalStateException("start rejected"); }
        void stop() { stopRequests++; }
        void failStopOnce() {
            stopRequests++;
            throw new IllegalStateException("stop failed");
        }
    }
}
