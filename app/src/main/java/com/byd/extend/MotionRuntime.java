package com.byd.extend;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** App-side Motion settings publisher and optional observer; the shell worker owns the session. */
public final class MotionRuntime implements AutoCloseable {
    private static volatile MotionRuntime active;

    private final Context context;
    private final Consumer<String> events;
    private final HandlerThread thread = new HandlerThread("motion-runtime-observer");
    private final Handler handler;
    private final SharedPreferences screenPrefs;
    private final SharedPreferences recordingPrefs;
    private final SharedPreferences.OnSharedPreferenceChangeListener changed =
            (prefs, key) -> refresh();

    private MotionConfig config;
    private MotionDetectorClient detector;
    private String submittedConfig;
    private boolean closed;

    MotionRuntime(Context context, CameraRuntimeClient ignoredCameras, Consumer<String> events) {
        this.context = context.getApplicationContext();
        this.events = events == null ? ignored -> { } : events;
        screenPrefs = this.context.getSharedPreferences(MotionConfig.SCREEN_PREFS, Context.MODE_PRIVATE);
        recordingPrefs = this.context.getSharedPreferences(MotionConfig.RECORDING_PREFS, Context.MODE_PRIVATE);
        normalizeStoredLockMode(screenPrefs);
        thread.start();
        handler = new Handler(thread.getLooper());
        screenPrefs.registerOnSharedPreferenceChangeListener(changed);
        recordingPrefs.registerOnSharedPreferenceChangeListener(changed);
        active = this;
        refresh();
    }

    /** Called by settings UI after edits; enabling starts the existing authorized helper path. */
    public static void settingsChanged(Context context) {
        MotionConfig current = MotionConfig.read(context);
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putBoolean("motion_monitoring_enabled", current.anyEnabled()).apply();
        MotionRuntime runtime = active;
        if (runtime != null) {
            runtime.refresh();
        } else if (current.anyEnabled()) {
            CameraHelperService.startPersistent(context, "motion_bootstrap");
        } else {
            // A previously running shell worker may outlive the UI/service process. Publish the
            // disabled snapshot through the same authenticated client without enabling monitoring.
            MotionDetectorClient oneShot = new MotionDetectorClient(context);
            oneShot.configure(current.toJson(), new MotionDetectorClient.Listener() {
                @Override public void onStatus(String state, String detail, long elapsedMs) {
                    if ("configured_disabled".equals(state)) oneShot.close();
                }
                @Override public void onError(String stage, String detail, long elapsedMs) {
                    oneShot.close();
                }
            });
        }
    }

    void refresh() {
        if (!handler.post(() -> refreshOnObserver())) return;
    }

    private void refreshOnObserver() {
        if (closed) return;
        MotionConfig next = MotionConfig.read(context);
        String nextJson = next.toJson();
        boolean modified = !nextJson.equals(submittedConfig);
        config = next;
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putBoolean("motion_monitoring_enabled", next.anyEnabled()).apply();

        if (detector == null) detector = new MotionDetectorClient(context);
        if (!modified) return;
        submittedConfig = nextJson;
        event("motion_config_publish", "enabled", next.anyEnabled(), "config", nextJson);
        detector.configure(nextJson, new MotionDetectorClient.Listener() {
            @Override public void onPrepared(android.os.IBinder workerBinder) {
                event("motion_worker_prepared", "uid", 2000);
            }

            @Override public void onStatus(String state, String detail, long elapsedMs) {
                if ("configured_disabled".equals(state)) {
                    event("motion_worker_disabled", "detail", detail, "elapsed_ms", elapsedMs);
                    return;
                }
                event("motion_worker_state", "state", state, "detail", detail,
                        "elapsed_ms", elapsedMs);
            }

            @Override public void onDetection(int level, long elapsedMs, int rawResult,
                    int nativeState, long frameNumber, long frameTimestampNs, long warningUntilMs) {
                event("motion_detected", "level", level, "raw", rawResult,
                        "native_state", nativeState, "frame", frameNumber,
                        "frame_timestamp_ns", frameTimestampNs, "elapsed_ms", elapsedMs,
                        "warning_until_ms", warningUntilMs);
            }

            @Override public void onError(String stage, String detail, long elapsedMs) {
                event("motion_error", "stage", stage, "error", detail,
                        "automatic_retry", false, "elapsed_ms", elapsedMs);
            }

            @Override public void onScreenState(String state, String detail, long elapsedMs) {
                event("motion_screen_state", "state", state, "detail", detail,
                        "elapsed_ms", elapsedMs);
            }
        });
    }

    private static void normalizeStoredLockMode(SharedPreferences screen) {
        Map<String, ?> values = screen.getAll();
        Object stored = values.get("lock");
        int raw = stored instanceof Integer ? (Integer) stored : 1;
        int normalized = MotionConfig.normalizeLockMode(raw);
        if (!(stored instanceof Integer) || raw != normalized)
            screen.edit().putInt("lock", normalized).apply();
    }

    private void event(String kind, Object... fields) {
        try {
            JSONObject json = new JSONObject().put("kind", kind)
                    .put("elapsed_ms", SystemClock.elapsedRealtime())
                    .put("wall_ms", System.currentTimeMillis());
            for (int i = 0; i + 1 < fields.length; i += 2)
                json.put(String.valueOf(fields[i]), fields[i + 1]);
            events.accept(json.toString());
        } catch (Exception ignored) { android.util.Log.w("BydMotion", kind); }
    }

    @Override public void close() {
        screenPrefs.unregisterOnSharedPreferenceChangeListener(changed);
        recordingPrefs.unregisterOnSharedPreferenceChangeListener(changed);
        FutureTask<Void> task = new FutureTask<>(() -> {
            closed = true;
            MotionDetectorClient old = detector;
            detector = null;
            if (old != null) old.close(); // Detaches only this observer; worker session survives.
            if (active == this) active = null;
            return null;
        });
        handler.post(task);
        try { task.get(10, TimeUnit.SECONDS); }
        catch (Exception failure) { event("motion_observer_close_error", "error", failure.toString()); }
        thread.quitSafely();
    }
}
