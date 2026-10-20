package com.byd.extend;

import android.content.Context;
import android.os.Handler;

import java.lang.reflect.InvocationTargetException;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Live rescue notifications are gestures, never a state to poll or replay. */
final class RescueButtonTelemetry {
    static final int DEVICE = 1040;
    static final int FID = 1070596116;
    private static final long RETRY_DELAY_MS = 1_000L;
    private final Context context;
    private final Handler handler;
    private final BiConsumer<String, Object[]> events;
    private FixedBydTelemetryManager.Subscription subscription;
    private String session = "";
    private long sequence;
    private long generation;
    private boolean enabled;
    private boolean retryPending;
    private String lastFailure;
    private final Runnable retrySubscription = () -> {
        retryPending = false;
        if (enabled && subscription == null) subscribe();
    };

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
            this.enabled = false;
            cancelRetry();
            lastFailure = null;
            close("disabled");
            return;
        }
        this.enabled = true;
        if (subscription != null) {
            reportConnected();
            return;
        }
        if (retryPending) return;
        subscribe();
    }

    private void subscribe() {
        if (!enabled || subscription != null) return;
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
                                if (generation == epoch && subscription != null)
                                    fail(reason == null || reason.isEmpty() ? "listener_error" : reason);
                            });
                        }
                    });
            lastFailure = null;
            reportConnected();
        } catch (Exception failure) {
            fail(failureReason(failure));
        }
    }

    private void fail(String cause) {
        close("subscription_error", cause);
        scheduleRetry();
    }

    private void scheduleRetry() {
        if (!enabled || subscription != null || retryPending) return;
        retryPending = handler.postDelayed(retrySubscription, RETRY_DELAY_MS);
    }

    private void cancelRetry() {
        if (!retryPending) return;
        handler.removeCallbacks(retrySubscription);
        retryPending = false;
    }

    private void reportConnected() {
        // A rebound Accessibility service needs the session, never a replayed press.
        events.accept("rescue_transport", new Object[]{
                "connected", true, "session_id", session, "reason", "subscribed"});
    }

    private void close(String reason) {
        close(reason, null);
    }

    private void close(String reason, String failure) {
        ++generation;
        FixedBydTelemetryManager.Subscription previous = subscription;
        subscription = null;
        if (previous != null) previous.close();
        String closedSession = session;
        session = "";
        if (!closedSession.isEmpty() && (failure == null || !failure.equals(lastFailure))) {
            if (failure != null) lastFailure = failure;
            if (failure == null) {
                events.accept("rescue_transport", new Object[]{
                        "connected", false, "session_id", closedSession, "reason", reason});
            } else {
                events.accept("rescue_transport", new Object[]{
                        "connected", false, "session_id", closedSession, "reason", reason,
                        "error", failure});
            }
        }
    }

    private static String failureReason(Throwable failure) {
        while (failure instanceof InvocationTargetException && failure.getCause() != null)
            failure = failure.getCause();
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
