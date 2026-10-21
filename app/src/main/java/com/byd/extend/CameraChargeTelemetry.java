package com.byd.extend;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashSet;
import java.util.Set;

/** App-side read-only status facade plus the shell process's single telemetry owner. */
public final class CameraChargeTelemetry {
    interface StateSink { void onState(CameraChargePolicy.State state); }
    public interface Listener { void onState(CameraChargePolicy.State state); }

    private static final Object INSTANCE_LOCK = new Object();
    private static volatile CameraChargeTelemetry instance;
    private static volatile CameraChargePolicy.State lastPublished =
            new CameraChargePolicy.State(null, null,
                    CameraChargePolicy.MISSING_SOC | CameraChargePolicy.MISSING_VOLTAGE,
                    0, false, true);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Listener> listeners = new LinkedHashSet<>();
    private volatile CameraChargePolicy.State state;

    private CameraChargeTelemetry(Context context) {
        state = lastPublished;
        try {
            state = MotionDetectorClient.readChargeState(context);
            lastPublished = state;
        }
        catch (Throwable unavailable) { /* No worker yet: the cached state remains unavailable. */ }
    }

    public static CameraChargeTelemetry get(Context context) {
        if (context == null) throw new IllegalArgumentException("context required");
        CameraChargeTelemetry current = instance;
        if (current != null) return current;
        synchronized (INSTANCE_LOCK) {
            current = instance;
            if (current == null) {
                Context application = context.getApplicationContext();
                current = new CameraChargeTelemetry(application == null ? context : application);
                instance = current;
            }
            return current;
        }
    }

    /** Returns the latest worker-published sample without opening a vehicle subscription. */
    public CameraChargePolicy.State snapshot() { return state; }

    /** Registers a UI-only observer and immediately delivers the latest cached worker state. */
    public Observation observe(Listener listener) {
        if (listener == null) throw new IllegalArgumentException("listener required");
        CameraChargePolicy.State current;
        synchronized (listeners) {
            listeners.add(listener);
            current = state;
        }
        dispatch(listener, current);
        return new Observation(this, listener);
    }

    /** Forces a fresh worker-owned read before a viewing lease is acquired. */
    public static CameraChargePolicy.State checkBeforeViewing(Context context) throws Exception {
        CameraChargePolicy.State fresh = MotionDetectorClient.readChargeState(context, true);
        if (!fresh.isMonitoring())
            throw new IllegalStateException("shared camera charge telemetry is not active");
        publish(fresh);
        return fresh;
    }

    static void publish(CameraChargePolicy.State next) {
        if (next == null) return;
        lastPublished = next;
        CameraChargeTelemetry current = instance;
        if (current != null) current.accept(next);
    }

    private void accept(CameraChargePolicy.State next) {
        state = next;
        Listener[] targets;
        synchronized (listeners) { targets = listeners.toArray(new Listener[0]); }
        for (Listener listener : targets) dispatch(listener, next);
    }

    private void dispatch(Listener listener, CameraChargePolicy.State value) {
        if (Looper.myLooper() == Looper.getMainLooper()) listener.onState(value);
        else main.post(() -> {
            synchronized (listeners) { if (!listeners.contains(listener)) return; }
            listener.onState(value);
        });
    }

    private void remove(Listener listener) {
        synchronized (listeners) { listeners.remove(listener); }
    }

    /** Closing this handle removes only the observing screen; shell telemetry ownership is unchanged. */
    public static final class Observation implements AutoCloseable {
        private CameraChargeTelemetry owner;
        private final Listener listener;

        private Observation(CameraChargeTelemetry owner, Listener listener) {
            this.owner = owner;
            this.listener = listener;
        }

        @Override public void close() {
            CameraChargeTelemetry current = owner;
            owner = null;
            if (current != null) current.remove(listener);
        }
    }

    /** Shell-only BYDAutoManager owner. All callbacks and reads are serialized on its Handler. */
    static final class Owner implements AutoCloseable {
        static final int SOC_DEVICE = 1014;
        static final int SOC_FID = 1145045040;
        static final int VOLTAGE_DEVICE = 1032;
        static final int VOLTAGE_FID = 1150287896;
        static final long POLL_INTERVAL_MS = 5_000L;

        private static final FixedBydTelemetryManager.Request[] REQUESTS = {
                new FixedBydTelemetryManager.Request(SOC_DEVICE,
                        FixedBydTelemetryManager.ValueType.INTEGER, SOC_FID),
                new FixedBydTelemetryManager.Request(VOLTAGE_DEVICE,
                        FixedBydTelemetryManager.ValueType.FLOAT, VOLTAGE_FID)
        };

        private final Context context;
        private final Handler handler;
        private final StateSink sink;
        private final CameraChargePolicy policy;
        private final Runnable poll = this::poll;
        private FixedBydTelemetryManager manager;
        private FixedBydTelemetryManager.Subscription subscription;
        private boolean active;

        Owner(Context context, Handler handler, StateSink sink) throws IOException {
            if (context == null || handler == null || sink == null)
                throw new IllegalArgumentException("charge owner dependencies required");
            this.context = context;
            this.handler = handler;
            this.sink = sink;
            policy = new CameraChargePolicy(new File(MotionDetectorProtocol.STATE_ROOT),
                    CameraChargeSettings.DEFAULT_SOC_THRESHOLD_PERCENT,
                    Math.round(CameraChargeSettings.DEFAULT_VOLTAGE_THRESHOLD_VOLTS * 10.0f));
        }

        void start(MotionConfig config) {
            if (config == null || !config.anyEnabled()) throw new IllegalArgumentException("enabled config required");
            policy.setThresholds(config.chargeSocThresholdPercent, config.chargeVoltageThresholdTenths);
            active = true;
            policy.setMonitoring(true);
            trySubscribe();
            readNow(); // Initial values are evaluated before any Motion camera/native preparation.
            handler.removeCallbacks(poll);
            handler.postDelayed(poll, POLL_INTERVAL_MS);
        }

        boolean isMonitoring() { return active; }

        CameraChargePolicy.State readNow() {
            if (!active) throw new IllegalStateException("camera charge telemetry is not active");
            trySubscribe();
            FixedBydTelemetryManager source = manager;
            Integer soc = readSoc(source);
            Float voltage = readVoltage(source);
            policy.updateSoc(soc);
            policy.updateVoltage(voltage);
            CameraChargePolicy.State state = policy.snapshot();
            sink.onState(state);
            return state;
        }

        CameraChargePolicy.State snapshot() { return policy.snapshot(); }

        private void poll() {
            if (!active) return;
            readNow();
            handler.postDelayed(poll, POLL_INTERVAL_MS);
        }

        private Integer readSoc(FixedBydTelemetryManager source) {
            if (source == null) return null;
            try { return decodeSoc(source.readBuffer(SOC_DEVICE, SOC_FID)); }
            catch (Throwable unavailable) { return null; }
        }

        private Float readVoltage(FixedBydTelemetryManager source) {
            if (source == null) return null;
            try { return decodeVoltage(source.readBuffer(VOLTAGE_DEVICE, VOLTAGE_FID)); }
            catch (Throwable unavailable) { return null; }
        }

        private void trySubscribe() {
            if (!active || subscription != null) return;
            try {
                if (manager == null) manager = FixedBydTelemetryManager.get(context);
                subscription = manager.subscribe(REQUESTS, new FixedBydTelemetryManager.Listener() {
                    @Override public void onValue(int device, int fid, int value, long receivedMs) {
                        handler.post(() -> acceptCallback(device, fid, value));
                    }

                    @Override public void onError(String reason, long receivedMs) {
                        handler.post(() -> acceptError());
                    }
                });
            } catch (Throwable unavailable) {
                manager = null;
            }
        }

        private void acceptCallback(int device, int fid, int raw) {
            if (!active) return;
            if (device == SOC_DEVICE && fid == SOC_FID) policy.updateSoc(raw);
            else if (device == VOLTAGE_DEVICE && fid == VOLTAGE_FID)
                policy.updateVoltage(Float.intBitsToFloat(raw));
            else return;
            sink.onState(policy.snapshot());
        }

        private void acceptError() {
            if (!active) return;
            closeSubscription();
            manager = null;
            sink.onState(policy.markUnavailable(CameraChargePolicy.MISSING_SOC
                    | CameraChargePolicy.MISSING_VOLTAGE));
        }

        @Override public void close() {
            if (!active) return;
            active = false;
            handler.removeCallbacks(poll);
            closeSubscription();
            manager = null;
            policy.setMonitoring(false);
            sink.onState(policy.snapshot());
        }

        private void closeSubscription() {
            FixedBydTelemetryManager.Subscription old = subscription;
            subscription = null;
            if (old != null) {
                try { old.close(); } catch (RuntimeException ignored) { }
            }
        }

        static int decodeSoc(byte[] bytes) {
            if (bytes == null || bytes.length != 4)
                throw new IllegalArgumentException("SOC buffer must contain one 32-bit integer");
            return ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).getInt();
        }

        static float decodeVoltage(byte[] bytes) {
            if (bytes == null || bytes.length != 4)
                throw new IllegalArgumentException("12V buffer must contain one 32-bit float");
            int raw = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).getInt();
            return Float.intBitsToFloat(raw);
        }
    }
}
