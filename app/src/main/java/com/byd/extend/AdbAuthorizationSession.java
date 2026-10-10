package com.byd.extend;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-local RSA attempt budget and priority over our own recovery I/O. */
final class AdbAuthorizationSession {
    private final Set<String> attemptedKeys = new HashSet<>();
    private final Map<String, Integer> reservedPrompts = new HashMap<>();
    private final Set<RecoveryOperation> recovery = new HashSet<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Runnable> foregroundListeners = new CopyOnWriteArrayList<>();
    private final ExecutorService foregroundExecutor = Executors.newSingleThreadExecutor(action -> {
        Thread thread = new Thread(action, "adb-foreground-authorization");
        thread.setDaemon(true);
        return thread;
    });
    private int requests;
    private long nextRequestId;
    private long permissionsCompletedRequestId;
    private Request foregroundRequest;
    private RequestSnapshot foregroundSnapshot;

    Attempt begin() {
        Attempt attempt;
        synchronized (this) {
            attempt = beginLocked();
        }
        changed();
        return attempt;
    }

    private Attempt beginLocked() {
        requests++;
        for (RecoveryOperation operation : recovery) operation.cancel();
        return new Attempt();
    }

    synchronized boolean pending() { return requests > 0; }

    synchronized PromptReservation reservePrompt(String fingerprint, boolean force) {
        if (!force && (attemptedKeys.contains(fingerprint)
                || reservedPrompts.containsKey(fingerprint))) return null;
        Integer reserved = reservedPrompts.get(fingerprint);
        reservedPrompts.put(fingerprint, reserved == null ? 1 : reserved + 1);
        return new PromptReservation(fingerprint);
    }

    private void releasePromptReservationLocked(String fingerprint) {
        Integer count = reservedPrompts.get(fingerprint);
        if (count == null || count <= 1) reservedPrompts.remove(fingerprint);
        else reservedPrompts.put(fingerprint, count - 1);
    }

    synchronized RecoveryOperation beginRecovery() {
        if (pending()) return null;
        RecoveryOperation operation = new RecoveryOperation();
        recovery.add(operation);
        return operation;
    }

    void addListener(Runnable listener) { listeners.addIfAbsent(listener); }
    void removeListener(Runnable listener) { listeners.remove(listener); }
    void addForegroundListener(Runnable listener) { foregroundListeners.addIfAbsent(listener); }
    void removeForegroundListener(Runnable listener) { foregroundListeners.remove(listener); }

    synchronized RequestSnapshot foregroundSnapshot() { return foregroundSnapshot; }

    synchronized boolean foregroundPermissionsPending() {
        RequestSnapshot snapshot = foregroundSnapshot;
        return snapshot != null && !snapshot.isActive()
                && snapshot.result != null && snapshot.result.ok
                && permissionsCompletedRequestId != snapshot.requestId;
    }

    void finishForegroundPermissions(long requestId) {
        synchronized (this) {
            if (foregroundSnapshot == null || foregroundSnapshot.requestId != requestId
                    || !foregroundPermissionsPending()) return;
            permissionsCompletedRequestId = requestId;
        }
        notifyForegroundListeners();
    }

    // A new foreground visit may retry only a failure that never started sending a key.
    synchronized boolean rearmUnsentAutomaticRequest() {
        RequestSnapshot snapshot = foregroundSnapshot;
        if (snapshot == null || snapshot.isActive()
                || snapshot.mode != LocalAdbClient.PromptMode.AUTO_ONCE) return false;
        LocalAdbClient.Result result = snapshot.result;
        if (result == null || result.ok || result.superseded || result.authorizationRequired
                || result.publicKeyWriteStarted || attemptedKeys.contains(result.fingerprint)) return false;
        foregroundSnapshot = null;
        return true;
    }

    Submission requestForeground(LocalAdbClient.PromptMode mode,
            Runnable cancelAuthorization, ForegroundWork work) {
        if (mode == null || mode == LocalAdbClient.PromptMode.NEVER) {
            throw new IllegalArgumentException("Foreground authorization needs AUTO_ONCE or FORCE");
        }
        if (work == null) throw new NullPointerException("work");

        Request replaced = null;
        Request request;
        RequestSnapshot queued;
        RequestSnapshot previousSnapshot;
        Disposition disposition;
        RuntimeException admissionError = null;
        synchronized (this) {
            if (foregroundRequest != null) {
                if (mode == LocalAdbClient.PromptMode.AUTO_ONCE) {
                    return new Submission(foregroundRequest.snapshot, Disposition.COALESCED);
                }
                replaced = foregroundRequest;
                if (replaced.cancelAuthorization != null) {
                    try { replaced.cancelAuthorization.run(); }
                    catch (RuntimeException ignored) { }
                }
                replaced.snapshot = new RequestSnapshot(replaced.id, replaced.mode,
                        RequestState.TERMINAL, LocalAdbClient.Result.superseded(), null);
                previousSnapshot = replaced.snapshot;
                disposition = replaced.mode == LocalAdbClient.PromptMode.AUTO_ONCE
                        ? Disposition.REPLACED_AUTO : Disposition.REPLACED_FORCE;
            } else {
                previousSnapshot = foregroundSnapshot;
                disposition = Disposition.ACCEPTED;
            }

            Attempt attempt = beginLocked();
            long requestId = ++nextRequestId;
            queued = new RequestSnapshot(requestId, mode, RequestState.QUEUED, null, null);
            request = new Request(requestId, mode, cancelAuthorization, work, attempt,
                    LocalAdbClient.cancellationToken(), queued);
            foregroundRequest = request;
            foregroundSnapshot = queued;
            try {
                foregroundExecutor.execute(() -> runForeground(request));
            } catch (RuntimeException error) {
                foregroundRequest = null;
                foregroundSnapshot = previousSnapshot;
                closeAttemptLocked(attempt);
                admissionError = error;
            }
        }

        changed();
        if (admissionError != null) {
            notifyForegroundListeners();
            throw admissionError;
        }
        notifyForegroundListeners();
        return new Submission(queued, disposition);
    }

    void cancelForeground() {
        synchronized (this) {
            Request request = foregroundRequest;
            if (request == null) return;
            LocalAdbClient.cancelPendingAuthorization();
            foregroundSnapshot = null;
            foregroundRequest = null;
        }
        notifyForegroundListeners();
    }

    private void runForeground(Request request) {
        boolean stale;
        boolean pendingChanged = false;
        synchronized (this) {
            stale = foregroundRequest != request;
            if (stale) {
                pendingChanged = closeAttemptLocked(request.attempt);
            } else {
                request.snapshot = new RequestSnapshot(request.id, request.mode,
                        RequestState.RUNNING, null, null);
                foregroundSnapshot = request.snapshot;
            }
        }
        if (stale) {
            if (pendingChanged) changed();
            return;
        }
        notifyForegroundListeners();

        LocalAdbClient.Result result = null;
        Throwable error = null;
        try {
            result = request.work.run(request.id, request.mode, request.cancellationToken);
        } catch (Throwable failure) {
            error = failure;
        }
        finishForeground(request, result, error);
        if (error instanceof Error) throw (Error) error;
    }

    private void finishForeground(Request request, LocalAdbClient.Result result, Throwable error) {
        boolean pendingChanged;
        boolean current;
        synchronized (this) {
            current = foregroundRequest == request;
            if (current) {
                request.snapshot = new RequestSnapshot(request.id, request.mode,
                        RequestState.TERMINAL, result, error);
                foregroundSnapshot = request.snapshot;
                foregroundRequest = null;
            }
            pendingChanged = closeAttemptLocked(request.attempt);
        }
        if (pendingChanged) changed();
        if (current) notifyForegroundListeners();
    }

    private boolean closeAttemptLocked(Attempt attempt) {
        if (attempt.closed) return false;
        attempt.closed = true;
        requests--;
        return true;
    }

    private void changed() {
        for (Runnable listener : listeners) {
            try { listener.run(); } catch (RuntimeException ignored) { }
        }
    }

    private void notifyForegroundListeners() {
        for (Runnable listener : foregroundListeners) {
            try { listener.run(); } catch (RuntimeException ignored) { }
        }
    }

    /** Keep Activity instances out of work retained by the process-scoped executor. */
    @FunctionalInterface interface ForegroundWork {
        LocalAdbClient.Result run(long requestId, LocalAdbClient.PromptMode mode,
                long cancellationToken) throws Exception;
    }

    enum Disposition { ACCEPTED, REPLACED_AUTO, REPLACED_FORCE, COALESCED }

    enum RequestState { QUEUED, RUNNING, TERMINAL }

    static final class Submission {
        final RequestSnapshot snapshot;
        final Disposition disposition;

        private Submission(RequestSnapshot snapshot, Disposition disposition) {
            this.snapshot = snapshot;
            this.disposition = disposition;
        }
    }

    static final class RequestSnapshot {
        final long requestId;
        final LocalAdbClient.PromptMode mode;
        final RequestState state;
        final LocalAdbClient.Result result;
        final Throwable error;

        private RequestSnapshot(long requestId, LocalAdbClient.PromptMode mode,
                RequestState state, LocalAdbClient.Result result, Throwable error) {
            this.requestId = requestId;
            this.mode = mode;
            this.state = state;
            this.result = result;
            this.error = error;
        }

        boolean isActive() { return state != RequestState.TERMINAL; }
    }

    private static final class Request {
        final long id;
        final LocalAdbClient.PromptMode mode;
        final Runnable cancelAuthorization;
        final ForegroundWork work;
        final Attempt attempt;
        final long cancellationToken;
        RequestSnapshot snapshot;

        Request(long id, LocalAdbClient.PromptMode mode, Runnable cancelAuthorization,
                ForegroundWork work, Attempt attempt, long cancellationToken,
                RequestSnapshot snapshot) {
            this.id = id;
            this.mode = mode;
            this.cancelAuthorization = cancelAuthorization;
            this.work = work;
            this.attempt = attempt;
            this.cancellationToken = cancellationToken;
            this.snapshot = snapshot;
        }
    }

    final class PromptReservation implements AutoCloseable {
        private final String fingerprint;
        private boolean released;
        private boolean writeStarted;

        private PromptReservation(String fingerprint) { this.fingerprint = fingerprint; }

        boolean markWriteStarted() {
            synchronized (AdbAuthorizationSession.this) {
                if (released || writeStarted) return false;
                writeStarted = true;
                attemptedKeys.add(fingerprint);
                releasePromptReservationLocked(fingerprint);
                released = true;
                return true;
            }
        }

        @Override public void close() {
            synchronized (AdbAuthorizationSession.this) {
                if (released) return;
                releasePromptReservationLocked(fingerprint);
                released = true;
            }
        }
    }

    final class Attempt implements AutoCloseable {
        private boolean closed;
        @Override public void close() {
            boolean pendingChanged;
            synchronized (AdbAuthorizationSession.this) {
                pendingChanged = closeAttemptLocked(this);
            }
            if (pendingChanged) changed();
        }
    }

    @FunctionalInterface interface Write { void run() throws IOException; }

    final class RecoveryOperation implements AutoCloseable {
        private final Set<Closeable> connections = new HashSet<>();
        private volatile boolean cancelled;

        boolean cancelled() { return cancelled; }

        void register(Closeable connection) throws IOException {
            synchronized (AdbAuthorizationSession.this) {
                if (cancelled || pending()) {
                    closeQuietly(connection);
                    throw new IOException("recovery_yielded_to_authorization");
                }
                connections.add(connection);
            }
        }

        void unregister(Closeable connection) {
            synchronized (AdbAuthorizationSession.this) { connections.remove(connection); }
        }

        /** Only short writes/admissions, never a network read or connection wait. */
        void write(Write action) throws IOException {
            synchronized (AdbAuthorizationSession.this) {
                if (cancelled || pending()) {
                    throw new IOException("recovery_yielded_to_authorization");
                }
                action.run();
            }
        }

        private void cancel() {
            cancelled = true;
            for (Closeable connection : connections) closeQuietly(connection);
            connections.clear();
        }

        @Override public void close() {
            synchronized (AdbAuthorizationSession.this) {
                cancel();
                recovery.remove(this);
            }
        }
    }

    private static void closeQuietly(Closeable connection) {
        try { connection.close(); } catch (IOException ignored) { }
    }
}
