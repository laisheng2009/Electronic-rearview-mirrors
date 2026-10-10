package com.byd.extend;

import static org.junit.Assert.assertFalse;

import java.util.ArrayDeque;
import java.util.Deque;

import org.junit.Test;

public final class TurnSignalTelemetryControllerTest {

    @Test public void stalledGetCannotRefreshCallbackOrFallbackFreshness() {
        Rig callback = new Rig();
        callback.transport.reads.add(snapshot(1, 0, 1, 0));
        callback.controller.start();
        callback.transport.reads.add(snapshot(1, 0, 1, 0));
        callback.transport.duringRead = () -> callback.clock.now = 2_251;
        callback.clock.now = 1_000;
        callback.controller.tick();
        assertFalse(callback.controller.subscriptionHealthy());
        assertFalse(callback.controller.dataFresh(2_251));

        Rig fallback = new Rig();
        fallback.transport.failSubscriptions = 1;
        fallback.transport.reads.add(snapshot(1, 0, 1, 0));
        fallback.controller.start();
        fallback.transport.reads.add(snapshot(1, 0, 1, 0));
        fallback.transport.duringRead = () -> fallback.clock.now = 301;
        fallback.clock.now = 50;
        fallback.controller.tick();
        assertFalse(fallback.controller.dataFresh(301));
    }

    private static TurnSignalTelemetryController.Snapshot snapshot(
            int stalk, float steering, int blink, float speed) {
        return new TurnSignalTelemetryController.Snapshot(stalk,
                Float.floatToIntBits(steering), blink, Float.floatToIntBits(speed));
    }

    private static final class Rig {
        final FakeClock clock = new FakeClock();
        final FakeTransport transport = new FakeTransport();
        final FakeSink sink = new FakeSink();
        final TurnSignalTelemetryController controller;
        Rig() {
            controller = new TurnSignalTelemetryController(clock, Runnable::run, transport, sink);
        }
    }

    private static final class FakeClock implements TurnSignalTelemetryController.Clock {
        long now;
        @Override public long now() { return now; }
    }

    private static final class FakeTransport implements TurnSignalTelemetryController.Transport {
        final Deque<TurnSignalTelemetryController.Snapshot> reads = new ArrayDeque<>();
        int failSubscriptions;
        Runnable duringRead;
        @Override public TurnSignalTelemetryController.Subscription subscribe(
                TurnSignalTelemetryController.Listener listener) throws Exception {
            if (failSubscriptions-- > 0) throw new IllegalStateException("rejected");
            return () -> {};
        }
        @Override public TurnSignalTelemetryController.Snapshot read() {
            TurnSignalTelemetryController.Snapshot result = reads.removeFirst();
            if (duringRead != null) {
                Runnable action = duringRead;
                duringRead = null;
                action.run();
            }
            return result;
        }
    }

    private static final class FakeSink implements TurnSignalTelemetryController.Sink {
        @Override public void onSnapshot(TurnSignalTelemetryController.Snapshot snapshot,
                TurnSignalTelemetryController.Source source, int liveMask,
                boolean conflict, long observedMs) { }
        @Override public void onMode(boolean subscribed, boolean seeded, String reason) {}
    }
}
