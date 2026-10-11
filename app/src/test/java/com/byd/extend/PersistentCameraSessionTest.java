package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.view.Surface;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PersistentCameraSessionTest {
    @Test public void slowCameraOutputReceivesLatestSnapshotWithoutOverwritingGpuOwnedBuffers() {
        CameraFrameMailbox queue = new CameraFrameMailbox();
        int[] pixels = new int[CameraFrameMailbox.SLOT_COUNT];
        boolean[] gpuReady = {true, true};
        int first = queue.reserve(slot -> gpuReady[slot]);
        pixels[first] = 1;
        queue.publish(first);
        int held = queue.acquire();
        assertEquals(1, pixels[held]);

        // The output is stuck in swap; acquisition keeps publishing and only the newest survives.
        for (int frame = 2; frame <= 100; frame++) {
            int slot = queue.reserve(id -> gpuReady[id]);
            assertTrue(slot >= 0 && slot != held);
            pixels[slot] = frame;
            queue.publish(slot);
            assertEquals(1, pixels[held]);
        }
        gpuReady[held] = false;
        queue.release(held);
        int latest = queue.acquire();
        assertEquals(100, pixels[latest]);
        // A CPU-released slot is still unavailable until the output GPU has finished with it.
        assertEquals(-1, queue.reserve(id -> gpuReady[id]));
        gpuReady[held] = true;
        assertEquals(held, queue.reserve(id -> gpuReady[id]));
        queue.publish(held);
        assertEquals(99, queue.dropped());
        queue.close();
        queue.release(latest);
        assertEquals(-1, queue.acquire());
        assertEquals(-1, queue.reserve(id -> true));
    }

    @Test public void pendingCameraUploadCanBeSkippedWithoutWaitingOrLosingNewerFrame() {
        CameraFrameMailbox queue = new CameraFrameMailbox();
        boolean[] ready = {true, true};
        int old = queue.reserve(id -> ready[id]);
        queue.publish(old);
        ready[old] = false;
        int fresh = queue.reserve(id -> ready[id]);
        assertTrue(fresh >= 0 && fresh != old);
        queue.publish(fresh);
        assertEquals(fresh, queue.acquire());
        assertEquals(1, queue.dropped());
        assertEquals(-1, queue.reserve(id -> ready[id]));
        ready[old] = true;
        assertEquals(old, queue.reserve(id -> ready[id]));
    }

    @Test public void nativeOpenRejectsUnsafeIdsBeforeOemCallsAndPreservesSafeLegacyOpen()
            throws Exception {
        CameraOpenProbe.staticOpens = CameraOpenProbe.constructors = 0;
        for (int id : new int[]{1000, 5000, 10000}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> CameraHelperMain.openAvmCamera(CameraOpenProbe.class, id));
            assertTrue(error.getMessage().contains(String.valueOf(id)));
            assertEquals(0, CameraOpenProbe.staticOpens);
            assertEquals(0, CameraOpenProbe.constructors);
        }
        int opens = 0;
        for (int id : new int[]{0, 999, 10001}) {
            CameraOpenProbe camera = (CameraOpenProbe)
                    CameraHelperMain.openAvmCamera(CameraOpenProbe.class, id);
            assertEquals(id, camera.id);
            assertEquals(++opens, CameraOpenProbe.staticOpens);
            assertEquals(opens, CameraOpenProbe.constructors);
        }
    }

    @Test public void wakeTeardownReleasesAllConsumersAndReopensFreshProducerWithStandbyDemand()
            throws Exception {
        assertRecovery(CameraHelperMain.VEHICLE_WAKE_RECOVERY);
    }

    private void assertRecovery(String reason) throws Exception {
        Trace trace = new Trace();
        FakeCameraPort camera = new FakeCameraPort(trace);
        FakeFanout fanout = new FakeFanout(trace);
        CameraHelperMain.HelperBinder.PersistentSession session = session();
        FakeEventSink events = new FakeEventSink(trace);
        session.blindStandbyDemandMask = (1 << 2) | (1 << 3);
        session.startProducer(camera, fanout, session.overlayGroup,
                surfaces(1), new int[]{2}, 101, "overlay", false, false);
        session.attach(camera, session.parkingGroup, surfaces(1), new int[]{3}, 102,
                "parking", false, false, events, new FakeShellClose(trace), 7, 1);
        session.attach(camera, session.mirrorGroup, surfaces(1), new int[]{1}, 103,
                "mirror", false, false, events, new FakeShellClose(trace), 7, 1);

        CameraHelperMain.HelperBinder.TeardownOutcome outcome = session.tearDown(camera, reason, null,
                false, 0, false, new FakeShellClose(trace), events, 1);

        assertEquals(null, outcome.failure);
        assertEquals(1, count(trace.values, "stop"));
        assertEquals(1, count(trace.values, "close"));
        assertEquals(1, count(trace.values, "fanout-close"));
        assertFalse(session.producerOpen);
        assertFalse(session.overlayGroup.has());
        assertFalse(session.parkingGroup.has());
        assertFalse(session.mirrorGroup.has());
        for (int request = 101; request <= 103; request++) {
            assertEquals(1, events.count("camera_error", request));
            assertEquals(reason,
                    events.byKindAndRequest("camera_error", request).field("stage"));
        }
        assertEquals((1 << 2) | (1 << 3), session.blindStandbyDemandMask);
        Trace fresh = new Trace();
        session.startProducer(new FakeCameraPort(fresh), new FakeFanout(fresh),
                session.overlayGroup, surfaces(1), new int[]{2}, 104, "overlay", false, false);
        assertTrue(session.producerOpen);
        assertEquals(1, count(fresh.values, "start"));
        assertTrue(fresh.values.contains("add:3"));
        assertEquals(104, session.overlayGroup.requestId);
    }

    @Test
    public void startupAttachFailureStopsProducerBeforeFanoutRelease() throws Exception {
        Trace trace = new Trace();
        FakeCameraPort camera = new FakeCameraPort(trace);
        camera.removeResult = false;
        FakeFanout fanout = new FakeFanout(trace);
        fanout.failAttachCall = 1;
        CameraHelperMain.HelperBinder.PersistentSession session = session();

        try {
            session.startProducer(camera, fanout, session.activityGroup,
                    surfaces(1), new int[]{2}, 3,
                    "calibration", false, false);
            fail("logical attach should fail");
        } catch (IllegalStateException expected) {
            assertEquals("attach failed", expected.getMessage());
        }
        CameraHelperMain.HelperBinder.closeFailedPersistentProducer(camera, fanout, session);

        assertTrue(trace.values.indexOf("start")
                < trace.values.indexOf("stop"));
        assertTrue(trace.values.indexOf("stop")
                < trace.values.indexOf("remove:2"));
        assertTrue(trace.values.indexOf("remove:2")
                < trace.values.indexOf("close"));
        assertTrue(trace.values.indexOf("close")
                < trace.values.indexOf("fanout-close"));
    }

    @Test
    public void detachedConsumerFailureDoesNotTearDownTheProducer() throws Exception {
        Trace trace = new Trace();
        FakeCameraPort camera = new FakeCameraPort(trace);
        FakeFanout fanout = new FakeFanout(trace);
        CameraHelperMain.HelperBinder.PersistentSession session = session();
        FakeEventSink events = new FakeEventSink(trace);
        Surface detachedSurface = new Surface((android.graphics.SurfaceTexture) null);
        session.startProducer(camera, fanout, session.overlayGroup,
                new Surface[]{detachedSurface}, new int[]{2}, 77,
                "rear_left", false, false);
        CameraHelperMain.HelperBinder.ConsumerGroup.Snapshot detached =
                session.overlayGroup.snapshot();
        fanout.detach(detached.surfaces);
        session.rememberDetached(detached, 7);
        session.overlayGroup.clear();

        assertFalse(session.failConsumer(camera, detachedSurface, 2,
                new IllegalStateException("late swap failed"), events,
                new FakeShellClose(trace), 7, 7));

        CameraHelperMain.HelperBinder.PersistentSession.DetachedConsumerIdentity identity =
                session.detachedConsumerIdentity(detachedSurface, 7);
        assertEquals(CameraHelperMain.CAMERA_OWNER_OVERLAY, identity.owner);
        assertEquals(77, identity.requestId);
        assertEquals(7, identity.producerEpoch);
        assertTrue(session.producerOpen);
        assertEquals(0, count(trace.values, "stop"));
        assertEquals(0, count(trace.values, "close"));
        assertEquals(0, count(trace.values, "event:camera_error"));
    }

    private static CameraHelperMain.HelperBinder.PersistentSession session() {
        return new CameraHelperMain.HelperBinder.PersistentSession();
    }

    private static Surface[] surfaces(int count) {
        return new Surface[count];
    }

    private static int count(List<String> values, String expected) {
        int count = 0;
        for (String value : values) if (expected.equals(value)) count++;
        return count;
    }

    public static final class CameraOpenProbe {
        static int staticOpens, constructors;
        final int id;
        public static Object open(int id) { staticOpens++; return null; }
        public CameraOpenProbe(int id) { this.id = id; constructors++; }
        public boolean open() { return true; }
    }

    private static final class Trace {
        final List<String> values = new ArrayList<>();
    }

    private static final class FakeCameraPort implements CameraHelperMain.HelperBinder.PersistentCameraPort {
        final Trace trace;
        boolean removeResult = true;
        FakeCameraPort(Trace trace) { this.trace = trace; }
        @Override public boolean add(Surface value, int index) { trace.values.add("add:" + index); return true; }
        @Override public boolean remove(Surface value, int index) { trace.values.add("remove:" + index); return removeResult; }
        @Override public boolean start() { trace.values.add("start"); return true; }
        @Override public boolean stop() { trace.values.add("stop"); return true; }
        @Override public void close() { trace.values.add("close"); }
    }

    private static final class FakeFanout implements CameraHelperMain.HelperBinder.PersistentSurfaceFanout {
        final Trace trace;
        int attachCalls, failAttachCall;
        FakeFanout(Trace trace) { this.trace = trace; }
        @Override public Surface source(int index) { return null; }
        @Override public void attach(Surface[] values, int[] indexes, int requestId, int producerEpoch) {
            if (++attachCalls == failAttachCall) throw new IllegalStateException("attach failed");
            for (int index : indexes) trace.values.add("target-add:" + index);
        }
        @Override public void detach(Surface[] values) { trace.values.add("target-remove:" + values.length); }
        @Override public void setActive(Surface value, boolean active) { }
        @Override public void releaseSource(int index) { trace.values.add("source-release:" + index); }
        @Override public void close() { trace.values.add("fanout-close"); }
    }

    private static final class FakeShellClose implements CameraHelperMain.HelperBinder.PersistentShellCloseSink {
        final Trace trace;
        FakeShellClose(Trace trace) { this.trace = trace; }
        @Override public boolean close(String reason, int requestId) {
            trace.values.add("shell:" + reason + ":" + requestId);
            return true;
        }
    }

    private static final class FakeEventSink implements CameraHelperMain.HelperBinder.PersistentEventSink {
        final Trace trace;
        final List<Event> values = new ArrayList<>();
        FakeEventSink(Trace trace) { this.trace = trace; }
        @Override public void emit(String kind, Object... fields) {
            trace.values.add("event:" + kind);
            values.add(new Event(kind, fields));
        }
        Event byKindAndRequest(String kind, int requestId) {
            return values.stream().filter(e -> e.kind.equals(kind) && Integer.valueOf(requestId).equals(e.field("request_id")))
                    .findFirst().orElseThrow(() -> new AssertionError("Missing " + kind + " for " + requestId));
        }
        int count(String kind, int requestId) {
            return (int) values.stream().filter(e -> e.kind.equals(kind) && Integer.valueOf(requestId).equals(e.field("request_id"))).count();
        }
    }

    private static final class Event {
        final String kind;
        final Map<String, Object> fields = new LinkedHashMap<>();
        Event(String kind, Object... values) {
            this.kind = kind;
            for (int i = 0; i + 1 < values.length; i += 2) fields.put(String.valueOf(values[i]), values[i + 1]);
        }
        Object field(String name) { return fields.get(name); }
    }
}
