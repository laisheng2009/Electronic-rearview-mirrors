package com.byd.extend;

import android.content.Context;
import android.os.Handler;

import java.util.UUID;
import java.util.function.BiConsumer;

/** Live rescue notifications are gestures, never a state to poll or replay. */
final class RescueButtonTelemetry {
    static final int DEVICE = 1040;
    static final int FID = 1070596116;
    private final Context context;
    private final Handler handler;
    private final BiConsumer<String, Object[]> events;
    private FixedBydTelemetryManager.Subscription subscription;
    private String session = "";
    private long sequence;
    private long generation;

    RescueButtonTelemetry(Context context, Handler handler,
            BiConsumer<String, Object[]> events) {
        this.context = context;
        this.handler = handler;
        this.events = events;
    }

    void configure(boolean enabled) {
        handler.post(() -> configureOnHandler(enabled));
    }

    private void configureOnHandler(boolean enabled) {
        if (!enabled) {
            close("disabled");
            return;
        }
        if (subscription != null) {
            reportConnected();
            return;
        }
        final long epoch = ++generation;
        session = UUID.randomUUID().toString();
        sequence = 0;
        try {
            subscription = FixedBydTelemetryManager.get(context).subscribe(
                    new FixedBydTelemetryManager.Request[]{
                            new FixedBydTelemetryManager.Request(DEVICE, FID)},
                    new FixedBydTelemetryManager.Listener() {
                        @Override public void onValue(int device, int fid, int value, long receivedMs) {
                            handler.post(() -> {
                                if (generation != epoch || subscription == null) return;
                                events.accept("rescue_button", new Object[]{
                                        "value", value, "received_ms", receivedMs,
                                        "session_id", session, "sequence", ++sequence});
                            });
                        }
                        @Override public void onError(String reason, long receivedMs) {
                            handler.post(() -> {
                                if (generation == epoch) close("subscription_error");
                            });
                        }
                    });
            reportConnected();
        } catch (Exception failure) {
            close(failure.getClass().getSimpleName());
        }
    }

    private void reportConnected() {
        // A rebound Accessibility service needs the session, never a replayed press.
        events.accept("rescue_transport", new Object[]{
                "connected", true, "session_id", session, "reason", "subscribed"});
    }

    private void close(String reason) {
        ++generation;
        FixedBydTelemetryManager.Subscription previous = subscription;
        subscription = null;
        if (previous != null) previous.close();
        if (!session.isEmpty()) {
            events.accept("rescue_transport", new Object[]{
                    "connected", false, "session_id", session, "reason", reason});
            session = "";
        }
    }
}
