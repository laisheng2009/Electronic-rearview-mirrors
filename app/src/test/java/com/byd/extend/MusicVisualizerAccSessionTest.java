package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public final class MusicVisualizerAccSessionTest {
    @Test
    public void initialReadGatesStartThenUnknownFallsBackButFailedReadKeepsKnownOff() {
        Harness harness = new Harness();
        harness.session.beginRead();
        harness.playbackChanged();
        assertEquals(0, harness.oem.startRequests);

        harness.session.readFailed("unsupported_get");
        harness.playbackChanged();
        assertEquals(1, harness.oem.startRequests);

        Harness knownOff = new Harness();
        knownOff.session.update(MusicVisualizerAccSession.OFF, true, "initial");
        knownOff.session.beginRead();
        knownOff.session.readFailed("recheck_failed");
        knownOff.playbackChanged();
        assertEquals(MusicVisualizerAccSession.OFF, knownOff.session.accState());
        assertEquals(0, knownOff.oem.startRequests);
    }

    @Test
    public void getSnapshotIsDiscardedWhenNewerCallbackArrives() {
        assertTrue(MusicVisualizerAccSession.isReadSnapshotCurrent(0, 0, 0));
        assertFalse(MusicVisualizerAccSession.isReadSnapshotCurrent(0, 1, 0));
        assertFalse(MusicVisualizerAccSession.isReadSnapshotCurrent(1, 1, 0));
        assertTrue(MusicVisualizerAccSession.isReadSnapshotCurrent(1, 1, 1));
    }

    @Test
    public void offThenOnWithContinuousPlaybackStartsOnceAfterOemOrderingDelay() {
        Harness harness = new Harness();
        harness.session.update(MusicVisualizerAccSession.OFF, true, "initial");
        harness.playbackChanged();
        assertEquals(0, harness.oem.startRequests);

        harness.session.beginRead();
        harness.session.update(MusicVisualizerAccSession.ON, true, "wake_recheck");
        harness.playbackChanged();
        harness.scheduler.advanceBy(MusicVisualizerAccSession.ON_REEVALUATE_MS - 1);
        assertEquals(0, harness.oem.startRequests);

        harness.scheduler.advanceBy(1);
        assertEquals(1, harness.oem.startRequests);
        assertEquals(1, harness.oem.references);

        harness.session.update(MusicVisualizerAccSession.ON, false, "duplicate_callback");
        harness.playbackChanged();
        harness.playbackChanged();
        assertEquals(1, harness.oem.startRequests);
        assertEquals(1, harness.oem.references);
        assertEquals(1, harness.reevaluations);
    }

    @Test
    public void offClearsAnAcceptedClaimAndOnRestartsWithoutAnOemStopRequest() {
        Harness harness = new Harness();
        harness.session.readFailed("unsupported_get");
        harness.playbackChanged();
        assertEquals(1, harness.oem.startRequests);
        assertEquals(1, harness.oem.references);

        harness.session.update(MusicVisualizerAccSession.OFF, false, "off_callback");
        assertEquals(0, harness.oem.references);
        assertEquals(0, harness.oem.stopRequests);
        assertFalse(harness.session.outputClaimed());
        harness.playbackChanged();
        assertEquals(1, harness.oem.startRequests);

        harness.session.update(MusicVisualizerAccSession.ON, false, "on_callback");
        harness.playbackChanged();
        harness.scheduler.advanceBy(MusicVisualizerAccSession.ON_REEVALUATE_MS - 1);
        assertEquals(1, harness.oem.startRequests);
        harness.scheduler.advanceBy(1);
        assertEquals(2, harness.oem.startRequests);
        assertEquals(1, harness.oem.references);
        assertEquals(0, harness.oem.stopRequests);

        harness.session.update(MusicVisualizerAccSession.ON, false, "duplicate_on");
        harness.playbackChanged();
        assertEquals(2, harness.oem.startRequests);
        assertEquals(1, harness.oem.references);
    }

    @Test
    public void offDisableAndShutdownCancelPendingOnReevaluation() {
        Harness off = new Harness();
        off.session.update(MusicVisualizerAccSession.ON, true, "on");
        off.session.update(MusicVisualizerAccSession.OFF, false, "off");
        off.scheduler.advanceBy(MusicVisualizerAccSession.ON_REEVALUATE_MS);
        assertEquals(0, off.oem.startRequests);

        Harness disabled = new Harness();
        disabled.session.update(MusicVisualizerAccSession.ON, true, "on");
        disabled.enabled = false;
        disabled.session.cancelPendingStart();
        disabled.scheduler.advanceBy(MusicVisualizerAccSession.ON_REEVALUATE_MS);
        assertEquals(0, disabled.oem.startRequests);

        Harness shutdown = new Harness();
        shutdown.session.update(MusicVisualizerAccSession.ON, true, "on");
        shutdown.enabled = false;
        shutdown.session.close();
        shutdown.scheduler.advanceBy(MusicVisualizerAccSession.ON_REEVALUATE_MS);
        assertEquals(0, shutdown.oem.startRequests);
    }

    @Test
    public void stopRequestUsesSameClaimAndOnlyRunsOnce() throws Throwable {
        Harness harness = new Harness();
        harness.session.readFailed("unsupported_get");
        harness.playbackChanged();

        assertTrue(harness.session.requestStop(false, harness.oem::stop));
        assertFalse(harness.session.requestStop(false, harness.oem::stop));
        assertEquals(1, harness.oem.stopRequests);
        assertEquals(0, harness.oem.references);
        assertFalse(harness.session.outputClaimed());
    }

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
        assertEquals(0, harness.oem.startRequests);

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
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeOem oem = new FakeOem();
        final MusicVisualizerAccSession session = new MusicVisualizerAccSession(scheduler,
                new MusicVisualizerAccSession.Listener() {
                    @Override public void onAccOff(String reason) { oem.accOff(); }
                    @Override public void onAccOnReady(String reason) {
                        reevaluations++;
                        playbackChanged();
                    }
                    @Override public void onUnavailable(String reason) { }
                });
        boolean enabled = true;
        boolean awake = true;
        boolean eligiblePlayback = true;
        int reevaluations;

        void playbackChanged() {
            try {
                session.requestStart(enabled && awake && eligiblePlayback, oem::start);
            } catch (Throwable failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static final class FakeOem {
        int references;
        int startRequests;
        int stopRequests;

        void start() {
            references++;
            startRequests++;
        }

        void failStart() { throw new IllegalStateException("start rejected"); }

        void stop() {
            if (references > 0) references--;
            stopRequests++;
        }

        void failStopOnce() {
            stopRequests++;
            throw new IllegalStateException("stop failed");
        }

        void accOff() { references = 0; }
    }

    private static final class FakeScheduler implements MusicVisualizerAccSession.Scheduler {
        private final List<Scheduled> tasks = new ArrayList<>();
        private long now;

        @Override public void postDelayed(Runnable task, long delayMs) {
            tasks.add(new Scheduled(task, now + delayMs));
        }

        @Override public void removeCallbacks(Runnable task) {
            tasks.removeIf(scheduled -> scheduled.task == task);
        }

        void advanceBy(long durationMs) {
            now += durationMs;
            while (true) {
                Scheduled ready = null;
                for (Scheduled scheduled : tasks) {
                    if (scheduled.when <= now) {
                        ready = scheduled;
                        break;
                    }
                }
                if (ready == null) return;
                tasks.remove(ready);
                ready.task.run();
            }
        }
    }

    private static final class Scheduled {
        final Runnable task;
        final long when;
        Scheduled(Runnable task, long when) { this.task = task; this.when = when; }
    }
}
