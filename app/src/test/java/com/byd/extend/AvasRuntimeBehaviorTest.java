package com.byd.extend;

import android.app.Application;
import android.content.Context;
import android.media.AudioFocusRequest;
import android.media.AudioTrack;
import android.media.AudioManager;
import android.os.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAudioTrack;
import org.robolectric.shadows.ShadowAudioManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = 29, application = Application.class, shadows = {
        AvasRuntimeBehaviorTest.ShellSettingsBoundary.class, AvasRuntimeBehaviorTest.RouteBoundary.class,
        AvasRuntimeBehaviorTest.NavBoundary.class, AvasRuntimeBehaviorTest.TrackBoundary.class,
        AvasRuntimeBehaviorTest.AutoServiceBoundary.class, AvasRuntimeBehaviorTest.AudioBoundary.class})
public class AvasRuntimeBehaviorTest {
    @Test public void concurrentOwnersReleaseRouteOnlyAfterLastClose() throws Exception {
        List<JSONObject> events = new CopyOnWriteArrayList<>();
        AtomicInteger firstVolume = new AtomicInteger(20);
        AtomicInteger secondVolume = new AtomicInteger(70);
        try (AvasAudioPlayer player = new AvasAudioPlayer(RuntimeEnvironment.getApplication(), events::add,
                new AvasEngineCueGate())) {
            try (var first = player.openEngineOutput(true, () -> false, firstVolume::get, 20);
                 var second = player.openEngineOutput(true, () -> false, secondVolume::get, 70)) {
                assertEquals(1, RouteBoundary.acquired);
                assertEquals(0, RouteBoundary.released);
                assertEquals(20, first.trackVolumePercent());
                assertEquals(70, second.trackVolumePercent());
                firstVolume.set(45);
                first.write(new short[16], 16);
                assertEquals(45, first.trackVolumePercent());
                assertEquals("changing one source must not change another", 70, second.trackVolumePercent());
                first.close();
                assertEquals("the remaining owner still needs NAV", 0, RouteBoundary.released);
                second.write(new short[16], 16);
                assertEquals(70, second.trackVolumePercent());
            }
            assertEquals(1, RouteBoundary.released);
        }
        assertEquals("repeated cleanup must not release somebody else's route", 1, RouteBoundary.released);
        assertEquals(0, TrackBoundary.live.get());
    }

    @Test public void readyAndTestModesControlRealOutputOnce() throws Exception {
        List<JSONObject> events = new CopyOnWriteArrayList<>();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        AvasEngineCueGate cues = new AvasEngineCueGate();
        try (AvasAudioPlayer player = new AvasAudioPlayer(RuntimeEnvironment.getApplication(), events::add, cues)) {
            AvasEngineRuntime runtime = new AvasEngineRuntime(RuntimeEnvironment.getApplication(), () -> player,
                    () -> false, executor, events::add, () -> {}, cues);
            try {
                AutoServiceBoundary.ok = 2;
                runtime.onPower(2, true);
                runtime.configure(new AvasConfig.Engine(true, AvasEngineSettings.DEFAULT_PACK_ID,
                        false, 25, true, 40));
                AndroidBehaviorHarness.await("OK=absent must reach the ready monitor", () ->
                        events.stream().anyMatch(e -> e.optString("kind").equals("avas_engine_ready")
                                && e.optInt("instrument_raw") == 2));
                assertEquals("ACC power=2 alone must not play", 0, TrackBoundary.opened.get());
                AutoServiceBoundary.ok = 1;
                AndroidBehaviorHarness.await("confirmed OK must open live audio without Start", () -> TrackBoundary.frames.get() > 0);
                assertEquals(1, TrackBoundary.opened.get());
                assertEquals("active", runtime.status().getString("state"));
                runtime.startManual();
                AndroidBehaviorHarness.await("test must replace live output", () -> TrackBoundary.opened.get() == 2);
                assertTrue(runtime.status().getBoolean("testActive"));
                runtime.stopManual();
                AndroidBehaviorHarness.await("stopping test must restore live output", () -> TrackBoundary.opened.get() == 3);
                assertFalse(runtime.status().getBoolean("testActive"));
                assertEquals("two render owners must never overlap", 1, TrackBoundary.maxLive.get());
                assertEquals("restoring live audio must not replay ignition", 2,
                        events.stream().filter(e -> e.optString("kind").equals("avas_engine_policy")
                                && e.optString("action").equals("START")).count());
                runtime.configure(new AvasConfig.Engine(false, AvasEngineSettings.DEFAULT_PACK_ID,
                        false, 25, true, 40));
                AndroidBehaviorHarness.await("disabled engine must release output", () -> TrackBoundary.live.get() == 0);
            } finally {
                runtime.close();
                AndroidBehaviorHarness.await("render worker must finish", () -> TrackBoundary.live.get() == 0);
            }
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Implements(AvasShellSettings.class)
    public static class ShellSettingsBoundary {
        private final Map<String, Integer> values = new HashMap<>();
        @Implementation protected void __constructor__(Context context) { }
        @Implementation protected int getInt(String key, int fallback) { return values.getOrDefault(key, fallback); }
        @Implementation protected void putInt(String key, int value) { values.put(key, value); }
        @Implementation protected void close() { }
    }

    // AOSP has no DiLink NAV stream 15; keep the actual cap/restore policy above this boundary.
    @Implements(AudioManager.class)
    public static class AudioBoundary extends ShadowAudioManager {
        private int navVolume = 7;
        @Implementation protected int getStreamVolume(int stream) {
            return stream == 15 ? navVolume : super.getStreamVolume(stream);
        }
        @Implementation protected void setStreamVolume(int stream, int value, int flags) {
            if (stream == 15) navVolume = value;
            else super.setStreamVolume(stream, value, flags);
        }
    }

    @Implements(AvasExteriorRoute.class)
    public static class RouteBoundary {
        static int acquired, released;
        @Implementation protected void __constructor__(Context context, Consumer<JSONObject> log) { }
        @Implementation protected void naviFocus(boolean on, AvasAudioDiagnostics.Context diagnostics) { }
        @Implementation protected void prepare(AudioFocusRequest focus, AvasAudioDiagnostics.Context diagnostics,
                AvasNavigationRecovery.Marker marker) throws Exception {
            acquired++;
            marker.write(AvasShellSettings.EXTERIOR_DEVICE3_DIRTY);
        }
        @Implementation protected void release(AudioFocusRequest focus, AvasAudioDiagnostics.Context diagnostics, int dirty) {
            released++;
        }
        @Implementation protected void mute(boolean on, AvasAudioDiagnostics.Context diagnostics) { }
    }

    @Implements(AvasNavSourceTransport.class)
    public static class NavBoundary {
        @Implementation protected AvasNavSourceGate.Subscription subscribe(AvasNavSourceGate.Listener listener) {
            listener.changed(0);
            return () -> {};
        }
        @Implementation protected AvasNavSourceGate.Snapshot read() { return new AvasNavSourceGate.Snapshot(0, 0); }
    }

    @Implements(AudioTrack.class)
    public static class TrackBoundary extends ShadowAudioTrack {
        static final AtomicInteger opened = new AtomicInteger(), live = new AtomicInteger(),
                maxLive = new AtomicInteger(), frames = new AtomicInteger();
        private boolean playing;
        private int head;
        @Implementation protected void play() {
            if (!playing) {
                playing = true;
                opened.incrementAndGet();
                maxLive.accumulateAndGet(live.incrementAndGet(), Math::max);
            }
        }
        @Implementation protected int write(short[] pcm, int offset, int count, int mode) {
            frames.addAndGet(count);
            head += count;
            // Back-pressure stands in for the real PCM consumer; avoid an unbounded host spin.
            try { Thread.sleep(1); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            return count;
        }
        @Implementation protected int getPlaybackHeadPosition() { return head; }
        @Implementation protected void release() { if (playing) { playing = false; live.decrementAndGet(); } }
    }

    @Implements(AvasAutoService.class)
    public static class AutoServiceBoundary {
        static volatile int ok = 2;
        private static final Binder service = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                data.enforceInterface("test.autoservice");
                int device = data.readInt(), fid = data.readInt();
                reply.writeInt(0);
                reply.writeInt(device == 1007 && fid == 738197524 ? ok : 0);
                return true;
            }
        };
        static { service.attachInterface(null, "test.autoservice"); }
        @Implementation protected static IBinder resolve(IBinder previous) { return service; }
    }
}
