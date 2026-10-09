package com.byd.extend;

/** ACC gate and local claim for the OEM music visualizer output request. */
final class MusicVisualizerAccSession {
    static final int UNKNOWN = -1;
    static final int OFF = 0;
    static final int ON = 1;
    static final long ON_REEVALUATE_MS = 1_000;

    interface Scheduler {
        void postDelayed(Runnable task, long delayMs);
        void removeCallbacks(Runnable task);
    }

    interface Listener {
        void onAccOff(String reason);
        void onAccOnReady(String reason);
        void onUnavailable(String reason);
    }

    interface Request { void run() throws Throwable; }

    private final Scheduler scheduler;
    private final Listener listener;
    private final Runnable onReadyTask = this::onReady;
    private int accState = UNKNOWN;
    private boolean readPending;
    private boolean outputClaimed;
    private boolean onReadyPending;
    private boolean closed;
    private String onReadyReason = "";

    MusicVisualizerAccSession(Scheduler scheduler, Listener listener) {
        this.scheduler = scheduler;
        this.listener = listener;
    }

    void beginRead() {
        if (!closed) readPending = true;
    }

    void update(int value, boolean reconcile, String reason) {
        if (value != OFF && value != ON) {
            readFailed(reason);
            return;
        }
        readPending = false;
        if (value == OFF) {
            boolean invalidate = accState != OFF || outputClaimed || onReadyPending;
            accState = OFF;
            outputClaimed = false;
            cancelOnReady();
            if (invalidate) listener.onAccOff(reason);
            return;
        }
        boolean changed = accState != ON;
        accState = ON;
        if (changed || reconcile) scheduleOnReady(reason);
    }

    void readFailed(String reason) {
        readPending = false;
        if (accState == UNKNOWN) listener.onUnavailable(reason);
    }

    boolean canStart() {
        return !closed && accState != OFF && !readPending && !onReadyPending;
    }

    boolean requestStart(boolean eligible, Request request) throws Throwable {
        if (!eligible || !canStart() || outputClaimed) return false;
        outputClaimed = true;
        request.run();
        return true;
    }

    boolean requestStop(boolean retryExhausted, Request request) throws Throwable {
        if (!outputClaimed || retryExhausted) return false;
        request.run();
        outputClaimed = false;
        return true;
    }

    boolean outputClaimed() { return outputClaimed; }
    int accState() { return accState; }
    boolean readPending() { return readPending; }

    static boolean isReadSnapshotCurrent(
            long sequenceAtRead, long sequenceNow, long sequenceApplied) {
        return sequenceAtRead == sequenceNow && sequenceAtRead <= sequenceApplied;
    }

    void cancelPendingStart() {
        readPending = false;
        cancelOnReady();
    }

    void close() {
        cancelPendingStart();
        closed = true;
    }

    private void scheduleOnReady(String reason) {
        if (closed || onReadyPending) return;
        onReadyReason = reason;
        onReadyPending = true;
        scheduler.postDelayed(onReadyTask, ON_REEVALUATE_MS);
    }

    private void cancelOnReady() {
        if (!onReadyPending) return;
        onReadyPending = false;
        scheduler.removeCallbacks(onReadyTask);
    }

    private void onReady() {
        onReadyPending = false;
        if (!closed && accState == ON) listener.onAccOnReady(onReadyReason);
    }
}
