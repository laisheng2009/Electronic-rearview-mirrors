package com.byd.extend;

import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** App facade for the shell-owned native detector and fixed camera/screen controls. */
final class MotionDetectorClient implements AutoCloseable {
    interface Listener {
        default void onPrepared(IBinder workerBinder) {}
        default void onStatus(String state, String detail, long elapsedMs) {}
        default void onDetection(int level, long elapsedMs, int rawResult, int nativeState,
                long frameNumber, long frameTimestampNs, long warningUntilMs) {}
        default void onError(String stage, String detail, long elapsedMs) {}
        default void onScreenState(String state, String detail, long elapsedMs) {}
    }

    static final class Status {
        final String state;
        final boolean prepared;
        final boolean eligible;
        final String lastError;
        final long elapsedMs;

        Status(String state, boolean prepared, boolean eligible, String lastError, long elapsedMs) {
            this.state = state;
            this.prepared = prepared;
            this.eligible = eligible;
            this.lastError = lastError;
            this.elapsedMs = elapsedMs;
        }
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService commands = Executors.newSingleThreadExecutor(r ->
            new Thread(r, "motion-detector-client"));
    private final Object lock = new Object();
    private final Callback callback = new Callback();
    private volatile String sessionId;
    private volatile Listener listener;
    private volatile IBinder worker;
    private volatile IBinder authorizedWorker;
    private volatile boolean closed;

    MotionDetectorClient(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Prepares only. Authorize the prepared Binder, then enable eligibility after the app's OFF/lock gate. */
    void start(String id, Listener nextListener) {
        if (!MotionDetectorProtocol.validSession(id)) throw new IllegalArgumentException("invalid motion session ID");
        if (nextListener == null) throw new IllegalArgumentException("listener required");
        synchronized (lock) {
            if (closed) throw new IllegalStateException("motion client is closed");
            if (sessionId != null) {
                if (!sessionId.equals(id)) throw new IllegalStateException("a different motion session is already active");
                listener = nextListener;
                return;
            }
            sessionId = id;
            listener = nextListener;
        }
        try { commands.execute(() -> startOnWorker(id)); }
        catch (java.util.concurrent.RejectedExecutionException ignored) {
            // close() may win between publishing the session and queuing this task.
        }
    }

    private void startOnWorker(String id) {
        try {
            if (!isCurrent(id)) return;
            IBinder candidate = resolveWorker();
            if (!healthy(candidate)) {
                LocalAdbClient.Result launched = LocalAdbClient.executeAuthorized(context,
                        launchCommand(context.getApplicationInfo().sourceDir,
                                android.os.Process.myUid(), currentApkIdentity(context)),
                        (kind, fields) -> { });
                if (launched.authorizationRequired || launched.superseded)
                    throw new IllegalStateException("authenticated shell launch failed: " + launched.error);
                candidate = awaitWorker(6_000L);
            }
            if (!isCurrent(id)) return;
            if (!healthy(candidate)) throw new IllegalStateException("motion shell worker readiness/protocol mismatch");
            worker = candidate;
            // close() may race after the previous check while launch/probe was in flight.
            // If it won, do not create an orphaned shell session; otherwise close() now sees
            // this Binder and queues TX_STOP behind the in-progress start transaction.
            if (!isCurrent(id)) {
                if (worker == candidate) worker = null;
                return;
            }
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
                data.writeInt(MotionDetectorProtocol.VERSION);
                data.writeInt(BuildConfig.VERSION_CODE);
                data.writeString(id);
                data.writeStrongBinder(callback);
                if (!candidate.transact(MotionDetectorProtocol.TX_START, data, reply, 0))
                    throw new IllegalStateException("motion worker rejected start transaction");
                reply.readException();
                if (reply.readInt() != 1) throw new IllegalStateException("motion worker refused session");
                IBinder returned = reply.readStrongBinder();
                if (returned == null || !returned.isBinderAlive())
                    throw new IllegalStateException("motion worker Binder unavailable");
                worker = returned;
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Throwable error) {
            reportError(id, "start", summary(error));
        }
    }

    /** App-only CameraRuntimeHost operation 15; call from the prepared callback. */
    boolean authorizeCameraSession(String id, IBinder workerBinder) {
        if (!isCurrent(id) || workerBinder == null || !workerBinder.isBinderAlive()
                || worker == null || worker != workerBinder && !worker.equals(workerBinder)) return false;
        IBinder host;
        try { host = resolveGuard(); }
        catch (Exception error) { reportError(id, "camera_authorize", summary(error)); return false; }
        if (host == null) {
            reportError(id, "camera_authorize", "existing camera runtime service unavailable");
            return false;
        }
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TurnSignalShellProtocol.DESCRIPTOR);
            data.writeInt(MotionDetectorProtocol.CAMERA_AUTHORIZE);
            data.writeString(id);
            data.writeStrongBinder(workerBinder);
            if (!host.transact(TurnSignalShellProtocol.TX_CAMERA_RUNTIME, data, reply, 0))
                throw new IllegalStateException("camera runtime did not accept authorization");
            reply.readException();
            boolean accepted = reply.readInt() == 1 && reply.dataAvail() == 0;
            if (accepted) authorizedWorker = workerBinder;
            return accepted;
        } catch (Throwable error) {
            reportError(id, "camera_authorize", summary(error));
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    void setEligible(String id, boolean eligible) {
        if (eligible && (authorizedWorker == null || worker == null
                || authorizedWorker != worker && !authorizedWorker.equals(worker))) {
            reportError(id, "eligibility", "worker Binder must be authorized by camera runtime first");
            return;
        }
        submit(id, MotionDetectorProtocol.TX_SET_ELIGIBLE, data -> data.writeInt(eligible ? 1 : 0));
    }

    void stop(String id, String reason) {
        if (reason == null || !reason.matches("[A-Za-z0-9_.-]{0,64}"))
            throw new IllegalArgumentException("invalid stop reason");
        submit(id, MotionDetectorProtocol.TX_STOP, data -> data.writeString(reason));
    }

    void status(String id, Consumer<Status> result) {
        if (!isCurrent(id)) return;
        try {
            commands.execute(() -> {
                try {
                    IBinder target = requireWorker(id);
                    Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                    try {
                        data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
                        if (!target.transact(MotionDetectorProtocol.TX_STATUS, data, reply, 0))
                            throw new IllegalStateException("motion status transaction unavailable");
                        reply.readException();
                        Status status = new Status(reply.readString(), reply.readInt() == 1,
                                reply.readInt() == 1, reply.readString(), reply.readLong());
                        if (result != null) main.post(() -> result.accept(status));
                    } finally { data.recycle(); reply.recycle(); }
                } catch (Throwable error) { reportError(id, "status", summary(error)); }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Closing won before this read-only status request could be queued.
        }
    }

    void wakeForWarning(String id) {
        submit(id, MotionDetectorProtocol.TX_WAKE_FOR_WARNING, ignored -> { });
    }

    /** Call after the app-owned fullscreen warning reports its first ready/drawn state. */
    void warningWindowReady(String id) {
        submit(id, MotionDetectorProtocol.TX_WARNING_WINDOW_READY, ignored -> { });
    }

    void finishWarning(String id, boolean restoreAllowed) {
        submit(id, MotionDetectorProtocol.TX_FINISH_WARNING,
                data -> data.writeInt(restoreAllowed ? 1 : 0));
    }

    void markUserTakeover(String id) {
        submit(id, MotionDetectorProtocol.TX_USER_TAKEOVER, ignored -> { });
    }

    IBinder workerBinder() { return worker; }

    private void submit(String id, int transaction, ParcelWriter extra) {
        if (!isCurrent(id)) return;
        try {
            commands.execute(() -> {
                try {
                    IBinder target = requireWorker(id);
                    Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                    try {
                        data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
                        data.writeString(id);
                        extra.write(data);
                        if (!target.transact(transaction, data, reply, 0))
                            throw new IllegalStateException("motion command unavailable: " + transaction);
                        reply.readException();
                        if (reply.readInt() != 1) throw new IllegalStateException("motion command refused: " + transaction);
                    } finally { data.recycle(); reply.recycle(); }
                } catch (Throwable error) { reportError(id, "command_" + transaction, summary(error)); }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Closing won before this command could be queued.
        }
    }

    private IBinder requireWorker(String id) throws Exception {
        if (!isCurrent(id)) throw new IllegalStateException("motion session expired");
        IBinder target = worker;
        if (!healthy(target)) throw new IllegalStateException("motion shell worker disconnected");
        return target;
    }

    private IBinder awaitWorker(long timeoutMs) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        do {
            IBinder candidate = resolveWorker();
            if (healthy(candidate)) return candidate;
            SystemClock.sleep(100L);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new IllegalStateException("motion worker did not become ready before timeout");
    }

    private boolean healthy(IBinder candidate) {
        if (candidate == null || !candidate.isBinderAlive()) return false;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
            if (!candidate.transact(MotionDetectorProtocol.TX_PING, data, reply, 0)) return false;
            reply.readException();
            int version = reply.readInt();
            int build = reply.readInt();
            int uid = reply.readInt();
            int pid = reply.readInt();
            String identity = reply.readString();
            return version == MotionDetectorProtocol.VERSION && build == BuildConfig.VERSION_CODE
                    && uid == 2000 && pid > 0 && currentApkIdentity(context).equals(identity);
        } catch (Throwable ignored) {
            return false;
        } finally { data.recycle(); reply.recycle(); }
    }

    private static IBinder resolveWorker() throws Exception {
        return service(MotionDetectorProtocol.SERVICE_NAME);
    }

    private static IBinder resolveGuard() throws Exception {
        return service(TurnSignalShellProtocol.SERVICE_NAME);
    }

    private static IBinder service(String name) throws Exception {
        return (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, name);
    }

    private boolean isCurrent(String id) {
        return !closed && id != null && id.equals(sessionId);
    }

    private void reportPrepared(String id, IBinder binder) {
        Listener target = listener;
        if (!isCurrent(id) || target == null) return;
        main.post(() -> { if (isCurrent(id)) target.onPrepared(binder); });
    }

    private void reportStatus(String id, String state, String detail, long elapsedMs) {
        Listener target = listener;
        if (!isCurrent(id) || target == null) return;
        main.post(() -> {
            if (!isCurrent(id)) return;
            target.onStatus(state, detail, elapsedMs);
            if ("stopped".equals(state)) {
                synchronized (lock) {
                    if (id.equals(sessionId)) {
                        sessionId = null;
                        worker = null;
                        authorizedWorker = null;
                        listener = null;
                    }
                }
            }
        });
    }

    private void reportDetection(String id, int level, long elapsedMs, int rawResult,
            int nativeState, long frameNumber, long frameTimestampNs, long warningUntilMs) {
        Listener target = listener;
        if (!isCurrent(id) || target == null) return;
        main.post(() -> { if (isCurrent(id)) target.onDetection(level, elapsedMs, rawResult,
                nativeState, frameNumber, frameTimestampNs, warningUntilMs); });
    }

    private void reportError(String id, String stage, String detail) {
        Listener target = listener;
        if (!isCurrent(id) || target == null) return;
        long now = SystemClock.elapsedRealtime();
        main.post(() -> { if (isCurrent(id)) target.onError(stage, detail, now); });
    }

    private void reportScreen(String id, String state, String detail, long elapsedMs) {
        Listener target = listener;
        if (!isCurrent(id) || target == null) return;
        main.post(() -> { if (isCurrent(id)) target.onScreenState(state, detail, elapsedMs); });
    }

    private final class Callback extends Binder {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(MotionDetectorProtocol.CALLBACK_DESCRIPTOR);
                return true;
            }
            if (Binder.getCallingUid() != 2000) throw new SecurityException("motion callback sender denied");
            data.enforceInterface(MotionDetectorProtocol.CALLBACK_DESCRIPTOR);
            String id = data.readString();
            if (!isCurrent(id)) return true;
            switch (code) {
                case MotionDetectorProtocol.CB_PREPARED: {
                    IBinder preparedBinder = data.readStrongBinder();
                    int protocol = data.readInt();
                    int build = data.readInt();
                    int pid = data.readInt();
                    if (preparedBinder == null || !preparedBinder.isBinderAlive()
                            || protocol != MotionDetectorProtocol.VERSION
                            || build != BuildConfig.VERSION_CODE || pid <= 0)
                        throw new SecurityException("invalid motion prepared callback");
                    worker = preparedBinder;
                    reportPrepared(id, preparedBinder);
                    break;
                }
                case MotionDetectorProtocol.CB_STATUS:
                    reportStatus(id, data.readString(), data.readString(), data.readLong());
                    break;
                case MotionDetectorProtocol.CB_DETECTION: {
                    int level = data.readInt();
                    long elapsedMs = data.readLong();
                    int raw = data.readInt();
                    int nativeState = data.readInt();
                    long frame = data.readLong();
                    long frameTimeNs = data.readLong();
                    long warningUntil = data.readLong();
                    int expected = raw < 0 ? MotionEventPolicy.LEVEL_ONE : MotionEventPolicy.LEVEL_TWO;
                    if (raw == 0 || raw == -2 || level != expected || elapsedMs < 0 || frame <= 0
                            || frameTimeNs < 0 || warningUntil <= elapsedMs)
                        throw new SecurityException("invalid motion detection callback");
                    reportDetection(id, level, elapsedMs, raw, nativeState, frame, frameTimeNs, warningUntil);
                    break;
                }
                case MotionDetectorProtocol.CB_ERROR:
                    reportError(id, data.readString(), data.readString());
                    data.readLong();
                    break;
                case MotionDetectorProtocol.CB_SCREEN:
                    reportScreen(id, data.readString(), data.readString(), data.readLong());
                    break;
                default:
                    return false;
            }
            return true;
        }
    }

    static String launchCommand(String apkPath, int appUid, String identity) {
        String apk = shellQuote(apkPath);
        String quotedIdentity = shellQuote(identity);
        return "trap '' HUP; CLASSPATH=" + apk + " setsid app_process /system/bin --nice-name="
                + MotionDetectorProtocol.PROCESS_NAME + " " + MotionDetectorProtocol.MAIN_CLASS
                + " " + appUid + " " + quotedIdentity + " </dev/null >>"
                + MotionDetectorProtocol.LOG_PATH + " 2>&1 & for i in 1 2 3 4 5 6; do service list 2>/dev/null | grep -q '"
                + MotionDetectorProtocol.SERVICE_NAME + "' && break; sleep 0.5; done; service list 2>/dev/null | grep -q '"
                + MotionDetectorProtocol.SERVICE_NAME + "'";
    }

    static String currentApkIdentity(Context context) {
        try {
            long update = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
            return context.getApplicationInfo().sourceDir + ":" + update;
        } catch (Throwable ignored) {
            return context.getApplicationInfo().sourceDir + ":unknown";
        }
    }

    private static String shellQuote(String value) {
        return "'" + String.valueOf(value).replace("'", "'\\''") + "'";
    }

    private static String summary(Throwable error) {
        Throwable cause = error;
        while (cause instanceof java.lang.reflect.InvocationTargetException && cause.getCause() != null)
            cause = cause.getCause();
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    @Override public void close() {
        String id;
        IBinder target;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            listener = null;
            id = sessionId;
            target = worker;
            worker = null;
            authorizedWorker = null;
        }
        if (id == null || target == null || !target.isBinderAlive()) {
            commands.shutdown();
            return;
        }
        try {
            commands.execute(() -> {
                try { transactStop(target, id, "client_closed"); }
                catch (Throwable ignored) { /* Closing is final; no callback is delivered. */ }
                finally { commands.shutdown(); }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // A concurrent shutdown won before the stop could be queued.
            commands.shutdown();
        }
    }

    private static void transactStop(IBinder target, String id, String reason) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
            data.writeString(id);
            data.writeString(reason);
            if (!target.transact(MotionDetectorProtocol.TX_STOP, data, reply, 0))
                throw new IllegalStateException("motion stop transaction unavailable");
            reply.readException();
            if (reply.readInt() != 1) throw new IllegalStateException("motion stop refused");
        } finally { data.recycle(); reply.recycle(); }
    }

    private interface ParcelWriter { void write(Parcel parcel); }
}
