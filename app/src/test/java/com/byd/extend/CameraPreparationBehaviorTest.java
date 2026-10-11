package com.byd.extend;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.SurfaceTexture;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.provider.Settings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowDisplayManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = 29, application = Application.class)
public class CameraPreparationBehaviorTest {
    private Context context;
    private SharedPreferences settings;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 7);
        settings = context.getSharedPreferences("cluster-test", Context.MODE_PRIVATE);
        settings.edit().putBoolean(RearviewMirrorSettings.PREF_ENABLED, true)
                .putString(RearviewMirrorSettings.PREF_TARGET, "Cluster").commit();
        ShadowDisplayManager.addDisplay("w1920dp-h720dp", "XDJAScreenProjection_1");
        ProtocolBoundary.calls.set(0);
        ProtocolBoundary.results.clear();
        ProtocolBoundary.block = null;
    }

    @Test @Config(shadows = ProtocolBoundary.class)
    public void failedBindRetriesAfterCompletionAndSuccessSurvivesControllerRestart() throws Exception {
        ProtocolBoundary.results.add(bindFailure());
        ProtocolBoundary.results.add(new ClusterFullscreenProtocol.Result("", false, false));
        // A prior build recorded a failed attempt as if preparation were complete.
        settings.edit().putLong("cluster_fullscreen_attempted_session", 42).commit();
        ClusterFullscreenController controller = controller();
        try {
            power(controller, 42, true);
            awaitAttempts(controller, 1);
            controller.settingsChanged();
            advance(999);
            assertEquals(1, ProtocolBoundary.calls.get());
            advance(1);
            awaitAttempts(controller, 2);
            advance(60_000);
            controller.settingsChanged();
            assertEquals(2, ProtocolBoundary.calls.get());
            controller.shutdown();
            controller = controller();
            power(controller, 42, true);
            advance(60_000);
            assertEquals("successful preparation must not replay after app reconnect", 2,
                    ProtocolBoundary.calls.get());
        } finally { controller.shutdown(); }
    }

    @Test @Config(shadows = ProtocolBoundary.class)
    public void retriesAreBoundedAndSleepCancelsThemWithoutAcceptingAnOldSuccess() throws Exception {
        for (int i = 0; i < 5; i++) ProtocolBoundary.results.add(bindFailure());
        ClusterFullscreenController controller = controller();
        try {
            power(controller, 1, true);
            awaitAttempts(controller, 1);
            for (int i = 0; i < 3; i++) {
                advance(new long[]{1_000, 3_000, 10_000}[i]);
                awaitAttempts(controller, i + 2);
            }
            controller.settingsChanged();
            advance(300_000);
            assertEquals(4, ProtocolBoundary.calls.get());
            power(controller, 2, true);
            awaitAttempts(controller, 5);
            power(controller, 2, false);
            advance(300_000);
            assertEquals(5, ProtocolBoundary.calls.get());

            CountDownLatch blocked = new CountDownLatch(1);
            ProtocolBoundary.block = blocked;
            ProtocolBoundary.results.add(new ClusterFullscreenProtocol.Result("", false, false));
            power(controller, 3, true);
            AndroidBehaviorHarness.await("third wake starts preparation", () -> ProtocolBoundary.calls.get() == 6);
            settings.edit().putBoolean(RearviewMirrorSettings.PREF_ENABLED, false).commit();
            controller.settingsChanged();
            blocked.countDown();
            awaitAttempts(controller, 6);
            assertEquals(-1, settings.getLong("cluster_fullscreen_succeeded_session", -1));

            ProtocolBoundary.block = null;
            ProtocolBoundary.results.add(new ClusterFullscreenProtocol.Result("transaction timeout", false, true));
            settings.edit().putBoolean(RearviewMirrorSettings.PREF_ENABLED, true).commit();
            controller.settingsChanged();
            awaitAttempts(controller, 7);
            advance(300_000);
            assertEquals("an indeterminate submitted command must not be replayed", 7,
                    ProtocolBoundary.calls.get());
        } finally {
            if (ProtocolBoundary.block != null) ProtocolBoundary.block.countDown();
            controller.shutdown();
        }
    }

    @Test public void protocolDistinguishesUnsentBindFailureFromAnOutstandingTransaction() throws Exception {
        AtomicInteger commands = new AtomicInteger();
        AtomicInteger unbinds = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        Context noService = new ContextWrapper(context) {
            @Override public boolean bindService(Intent intent, ServiceConnection connection, int flags) {
                return false;
            }
        };
        assertTrue(ClusterFullscreenProtocol.dispatch(noService, () -> true).retryable);
        Context blockedService = new ContextWrapper(context) {
            @Override public boolean bindService(Intent intent, ServiceConnection connection, int flags) {
                connection.onServiceConnected(intent.getComponent(), new Binder() {
                    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                        commands.incrementAndGet();
                        try {
                            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("transaction not released");
                            reply.writeNoException();
                            return true;
                        } catch (InterruptedException error) { throw new AssertionError(error); }
                        finally { finished.countDown(); }
                    }
                });
                return true;
            }
            @Override public void unbindService(ServiceConnection connection) { unbinds.incrementAndGet(); }
        };
        try {
            ClusterFullscreenProtocol.Result result = ClusterFullscreenProtocol.dispatch(blockedService, () -> true);
            assertTrue(result.indeterminate);
            assertFalse(result.retryable);
            assertTrue(ClusterFullscreenProtocol.dispatch(blockedService, () -> true).indeterminate);
            assertEquals(1, commands.get());
            assertEquals(1, unbinds.get());
        } finally { release.countDown(); }
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        AndroidBehaviorHarness.await("finished transaction releases protocol ownership",
                () -> !((java.util.concurrent.atomic.AtomicBoolean) AndroidBehaviorHarness.field(
                        ClusterFullscreenProtocol.class, "TRANSACTION_IN_FLIGHT")).get());
    }

    @Test public void inputBuffersReturnBeforePublicationIncludingHiddenSkippedAndFailedFrames() {
        List<String> events = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        TestTexture input = new TestTexture(events);
        CameraFramePump pump = new CameraFramePump(null, null, null, 2, 2,
                new CameraFramePump.Listener() {
                    public void available() { events.add("publish"); }
                    public void failed(Throwable error) { errors.add(error); events.add("failed"); }
                    public void acquired(long start, long timestamp, long update, long copy, int count) {
                        events.add("acquired:" + timestamp);
                    }
                });
        AndroidBehaviorHarness.field(pump, "input", input);
        AndroidBehaviorHarness.field(pump, "positions", DirectCameraSourceHub.buffer(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}));
        AndroidBehaviorHarness.field(pump, "coords", DirectCameraSourceHub.buffer(new float[]{0, 0, 1, 0, 0, 1, 1, 1}));
        capture(pump, input, 10); // Hidden, no GPU copy.
        assertEquals(List.of("update", "release", "acquired:10"), events);
        events.clear();
        capture(pump, input, 0); // Queued callback sees the released placeholder.
        capture(pump, input, 10); // Repeated timestamp is not another frame.
        assertEquals(List.of("update", "release", "update", "release"), events);
        assertEquals(-1, pump.mailbox.acquire());

        // Exercise the real publication path; Android GL calls are host stubs, not a GPU test.
        for (CameraFramePump.Frame frame : pump.frames) frame.width = frame.height = 1;
        pump.setOutputSize(1, 1);
        events.clear();
        capture(pump, input, 20);
        assertEquals(errors.toString(), List.of("update", "release", "publish", "acquired:20"), events);
        int held = pump.mailbox.acquire();
        assertEquals(20, pump.frames[held].producerNs);
        int writing = pump.mailbox.reserve(slot -> true);
        events.clear();
        capture(pump, input, 30); // Both snapshot slots are busy: still return OEM input.
        assertEquals(List.of("update", "release", "acquired:30"), events);
        assertEquals(20, pump.frames[held].producerNs);
        assertTrue(writing >= 0);
        input.failTransform = true;
        events.clear();
        capture(pump, input, 40);
        assertEquals(List.of("update", "release", "failed"), events);
        input.release();
    }

    private ClusterFullscreenController controller() {
        return new ClusterFullscreenController(context, settings, new Handler(Looper.getMainLooper()), (k, v) -> {});
    }
    private static void power(ClusterFullscreenController controller, long session, boolean awake) {
        controller.acceptEvent("{\"kind\":\"shell_power_state\",\"awake_session_id\":" + session
                + ",\"interactive\":" + awake + "}");
    }
    private static void advance(long ms) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }
    private static void awaitAttempts(ClusterFullscreenController controller, int count) throws Exception {
        AndroidBehaviorHarness.await("preparation attempt completes", () -> {
            shadowOf(Looper.getMainLooper()).idle();
            return ProtocolBoundary.calls.get() == count && !(boolean) AndroidBehaviorHarness.field(controller, "inFlight");
        });
    }
    private static ClusterFullscreenProtocol.Result bindFailure() {
        return new ClusterFullscreenProtocol.Result("bind timeout after 3000ms", true, false);
    }
    private static void capture(CameraFramePump pump, TestTexture input, long timestamp) {
        input.timestamp = timestamp;
        AndroidBehaviorHarness.call(pump, "capture");
    }

    @Implements(ClusterFullscreenProtocol.class)
    public static class ProtocolBoundary {
        static final AtomicInteger calls = new AtomicInteger();
        static final ConcurrentLinkedQueue<ClusterFullscreenProtocol.Result> results = new ConcurrentLinkedQueue<>();
        static volatile CountDownLatch block;
        @Implementation protected static ClusterFullscreenProtocol.Result dispatch(Context context, BooleanSupplier current) {
            calls.incrementAndGet();
            ClusterFullscreenProtocol.Result result = results.remove();
            try { if (block != null) block.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            return result;
        }
    }
    private static final class TestTexture extends SurfaceTexture {
        final List<String> events;
        long timestamp;
        boolean failTransform;
        TestTexture(List<String> events) { super(0); this.events = events; }
        @Override public void updateTexImage() { events.add("update"); }
        @Override public long getTimestamp() { return timestamp; }
        @Override public void getTransformMatrix(float[] matrix) {
            if (failTransform) throw new IllegalStateException("input lost");
            android.opengl.Matrix.setIdentityM(matrix, 0);
        }
        @Override public void releaseTexImage() { events.add("release"); timestamp = 0; }
    }
}
