package com.byd.extend;

import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.io.File;
import java.io.IOException;

/** App facade for the shell-owned native detector and fixed camera/screen controls. */
public final class MotionDetectorClient implements AutoCloseable, MotionMediaLibrary.Access {
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
        final String sessionId;
        final String configJson;
        final boolean warning;
        final boolean warningWindowDrawn;

        Status(String state, boolean prepared, boolean eligible, String lastError, long elapsedMs,
                String sessionId, String configJson, boolean warning, boolean warningWindowDrawn) {
            this.state = state;
            this.prepared = prepared;
            this.eligible = eligible;
            this.lastError = lastError;
            this.elapsedMs = elapsedMs;
            this.sessionId = sessionId;
            this.configJson = configJson;
            this.warning = warning;
            this.warningWindowDrawn = warningWindowDrawn;
        }
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService commands = Executors.newSingleThreadExecutor(r ->
            new Thread(r, "motion-detector-client"));
    private final Object lock = new Object();
    private final Callback callback = new Callback();
    private volatile String sessionId;
    private volatile String configJson;
    private volatile Listener listener;
    private volatile IBinder worker;
    private volatile boolean closed;
    private int requestGeneration;
    private boolean legacyIndexImported;
    private boolean legacyIndexAttempted;

    public MotionDetectorClient(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override public int refresh() {
        ensureLegacyIndexImported();
        IBinder target = ensureMediaWorker();
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
            if (!target.transact(MotionDetectorProtocol.TX_REFRESH_MEDIA, data, reply, 0))
                throw new IllegalStateException("Motion media refresh unavailable");
            reply.readException();
            int refreshed = reply.readInt();
            if (reply.dataAvail() != 0 || refreshed < 0)
                throw new IllegalStateException("Invalid Motion media refresh reply");
            return refreshed;
        } catch (RuntimeException error) { throw error; }
        catch (Throwable error) { throw new IllegalStateException("Motion media refresh failed", error); }
        finally { data.recycle(); reply.recycle(); }
    }

    @Override public String query(MotionMediaLibrary.Query query, MotionConfig config, String pageToken) {
        ensureLegacyIndexImported();
        IBinder target = ensureMediaWorker();
        MotionMediaLibrary.Query actual = query == null ? MotionMediaLibrary.Query.recent() : query;
        MotionConfig actualConfig = config == null ? MotionConfig.read(context) : config;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
            data.writeInt(actual.startInclusiveMs == null ? 0 : 1);
            if (actual.startInclusiveMs != null) data.writeLong(actual.startInclusiveMs);
            data.writeInt(actual.endExclusiveMs == null ? 0 : 1);
            if (actual.endExclusiveMs != null) data.writeLong(actual.endExclusiveMs);
            data.writeString(actual.volumeId);
            data.writeInt(actual.sort == MotionMediaLibrary.Sort.NEWEST_FIRST ? 0 : 1);
            data.writeString(actualConfig.toJson());
            data.writeString(pageToken);
            if (!target.transact(MotionDetectorProtocol.TX_QUERY_MEDIA, data, reply, 0))
                throw new IllegalStateException("Motion media query unavailable");
            reply.readException();
            String encoded = reply.readString();
            if (encoded == null || encoded.length() > 256 * 1024 || reply.dataAvail() != 0)
                throw new IllegalStateException("Invalid Motion media query reply");
            return encoded;
        } catch (RuntimeException error) { throw error; }
        catch (Throwable error) { throw new IllegalStateException("Motion media query failed", error); }
        finally { data.recycle(); reply.recycle(); }
    }

    @Override public String read(String volumeId, long mediaStoreId) {
        ensureLegacyIndexImported();
        return mediaRecordTransaction(ensureMediaWorker(), MotionDetectorProtocol.TX_READ_MEDIA,
                volumeId, mediaStoreId);
    }

    @Override public boolean delete(String volumeId, long mediaStoreId) {
        ensureLegacyIndexImported();
        IBinder target = ensureMediaWorker();
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
            data.writeString(volumeId);
            data.writeLong(mediaStoreId);
            if (!target.transact(MotionDetectorProtocol.TX_DELETE_MEDIA, data, reply, 0))
                throw new IllegalStateException("Motion media delete unavailable");
            reply.readException();
            int deleted = reply.readInt();
            if ((deleted != 0 && deleted != 1) || reply.dataAvail() != 0)
                throw new IllegalStateException("Invalid Motion media delete reply");
            return deleted == 1;
        } catch (RuntimeException error) { throw error; }
        catch (Throwable error) { throw new IllegalStateException("Motion media delete failed", error); }
        finally { data.recycle(); reply.recycle(); }
    }

    @Override public boolean importLegacyIndex(ParcelFileDescriptor source) throws IOException {
        if (source == null) throw new IOException("Legacy Motion index descriptor is missing");
        IBinder target;
        try { target = ensureMediaWorker(); }
        catch (RuntimeException error) { throw new IOException("Motion worker is unavailable", error); }
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
            data.writeTypedObject(source, 0);
            if (!target.transact(MotionDetectorProtocol.TX_IMPORT_MEDIA_INDEX, data, reply, 0))
                throw new IOException("Motion legacy-index import unavailable");
            reply.readException();
            int complete = reply.readInt();
            if ((complete != 0 && complete != 1) || reply.dataAvail() != 0)
                throw new IOException("Motion legacy-index import refused");
            return complete == 1;
        } catch (IOException error) { throw error; }
        catch (Throwable error) { throw new IOException("Motion legacy-index import failed", error); }
        finally { data.recycle(); reply.recycle(); }
    }

    private String mediaRecordTransaction(IBinder target, int operation, String volumeId, long mediaStoreId) {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
            data.writeString(volumeId);
            data.writeLong(mediaStoreId);
            if (!target.transact(operation, data, reply, 0))
                throw new IllegalStateException("Motion media record operation unavailable");
            reply.readException();
            String encoded = reply.readString();
            if (encoded == null || encoded.length() > 64 * 1024 || reply.dataAvail() != 0)
                throw new IllegalStateException("Invalid Motion media record reply");
            return encoded;
        } catch (RuntimeException error) { throw error; }
        catch (Throwable error) { throw new IllegalStateException("Motion media record operation failed", error); }
        finally { data.recycle(); reply.recycle(); }
    }

    private void ensureLegacyIndexImported() {
        synchronized (lock) {
            if (legacyIndexImported || legacyIndexAttempted) return;
            legacyIndexAttempted = true;
        }
        File legacy = new File(new File(context.getFilesDir(), "motion"), "media-index.json");
        if (!legacy.isFile()) return;
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(legacy,
                ParcelFileDescriptor.MODE_READ_ONLY)) {
            boolean complete = importLegacyIndex(descriptor);
            synchronized (lock) { legacyIndexImported = complete; }
        } catch (IOException error) {
            throw new IllegalStateException("Could not import the existing Motion media index", error);
        }
    }

    /** Opens the worker for fixed media operations; never configures monitoring. */
    private IBinder ensureMediaWorker() { return ensureWorker(true); }

    IBinder ensureCheckWorker() { return ensureWorker(false); }

    private IBinder ensureWorker(boolean replaceObsolete) {
        try {
            IBinder candidate = resolveWorker();
            WorkerIdentity identity = readIdentity(candidate);
            if (matchesCurrentApk(identity)) {
                worker = candidate;
                return candidate;
            }
            if (candidate != null && candidate.isBinderAlive()) {
                if (!replaceObsolete)
                    throw new IllegalStateException("obsolete Motion worker is left running; "
                            + "check-up cannot interrupt it; reopen the application to complete normal worker recovery");
                if (identity == null)
                    throw new IllegalStateException("cannot identify existing Motion worker");
                retireObsoleteWorker(requestGeneration, candidate, identity);
            }
            LocalAdbClient.Result launched = LocalAdbClient.executeAuthorized(context,
                    launchCommand(context), (kind, fields) -> { });
            if (launched.authorizationRequired || launched.superseded)
                throw new IllegalStateException("authenticated Motion worker launch failed: " + launched.error);
            candidate = awaitWorker(6_000L);
            worker = candidate;
            return candidate;
        } catch (RuntimeException error) { throw error; }
        catch (Throwable error) { throw new IllegalStateException("Motion worker unavailable", error); }
    }

    /** Persist settings in the shell worker, then attach this process as an optional UI observer. */
    void configure(String nextConfigJson, Listener nextListener) {
        if (nextConfigJson == null || nextConfigJson.length() > 8192)
            throw new IllegalArgumentException("valid Motion config is required");
        if (nextListener == null) throw new IllegalArgumentException("listener required");
        final int generation;
        synchronized (lock) {
            if (closed) throw new IllegalStateException("motion client is closed");
            if (nextConfigJson.equals(configJson) && sessionId != null && worker != null
                    && worker.isBinderAlive()) {
                listener = nextListener;
                return;
            }
            configJson = nextConfigJson;
            listener = nextListener;
            sessionId = null;
            generation = ++requestGeneration;
        }
        try { commands.execute(() -> configureOnWorker(generation, nextConfigJson)); }
        catch (java.util.concurrent.RejectedExecutionException ignored) {
            // close() may win between publishing the session and queuing this task.
        }
    }

    private void configureOnWorker(int generation, String requestedConfig) {
        String id = "";
        try {
            if (!isCurrentRequest(generation)) return;
            IBinder candidate = resolveWorker();
            WorkerIdentity identity = readIdentity(candidate);
            if (!matchesCurrentApk(identity)) {
                retireObsoleteWorker(generation, candidate, identity);
                if (!isCurrentRequest(generation)) return;
                LocalAdbClient.Result launched = LocalAdbClient.executeAuthorized(context,
                        launchCommand(context),
                        (kind, fields) -> { });
                if (launched.authorizationRequired || launched.superseded)
                    throw new IllegalStateException("authenticated shell launch failed: " + launched.error);
                candidate = awaitWorker(6_000L);
            }
            if (!isCurrentRequest(generation)) return;
            if (!healthy(candidate)) throw new IllegalStateException("motion shell worker readiness/protocol mismatch");
            worker = candidate;
            if (!isCurrentRequest(generation)) {
                if (worker == candidate) worker = null;
                return;
            }
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
                data.writeInt(MotionDetectorProtocol.VERSION);
                data.writeInt(BuildConfig.VERSION_CODE);
                data.writeString(requestedConfig);
                if (!candidate.transact(MotionDetectorProtocol.TX_CONFIGURE, data, reply, 0))
                    throw new IllegalStateException("motion worker rejected config transaction");
                reply.readException();
                if (reply.readInt() != 1) throw new IllegalStateException("motion worker refused configuration");
                id = reply.readString();
                IBinder returned = reply.readStrongBinder();
                if (returned == null || !returned.isBinderAlive())
                    throw new IllegalStateException("motion worker Binder unavailable");
                worker = returned;
            } finally {
                data.recycle();
                reply.recycle();
            }
            if (!isCurrentRequest(generation)) return;
            if (id == null || id.isEmpty()) {
                synchronized (lock) { sessionId = null; }
                boolean enabled = MotionConfig.parse(requestedConfig).anyEnabled();
                reportRequestStatus(generation, enabled ? "configured_pending" : "configured_disabled",
                        enabled ? "latest Motion settings are persisted and will start after the previous session drains"
                                : "monitoring disabled in shell; media-only worker remains available",
                        SystemClock.elapsedRealtime());
                return;
            }
            if (!MotionDetectorProtocol.validSession(id))
                throw new IllegalStateException("motion worker returned an invalid session ID");
            synchronized (lock) { sessionId = id; }
            attachUi(candidate, id);
        } catch (Throwable error) {
            reportRequestError(generation, id, "configure", summary(error));
        }
    }

    private void attachUi(IBinder target, String id) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
            data.writeString(id);
            data.writeStrongBinder(callback);
            if (!target.transact(MotionDetectorProtocol.TX_ATTACH_UI, data, reply, 0))
                throw new IllegalStateException("motion UI observer attach unavailable");
            reply.readException();
            if (reply.readInt() != 1 || reply.dataAvail() != 0)
                throw new IllegalStateException("motion worker refused UI observer attachment");
        } finally { data.recycle(); reply.recycle(); }
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
                        Status status = readStatusReply(reply);
                        if (result != null) main.post(() -> result.accept(status));
                    } finally { data.recycle(); reply.recycle(); }
                } catch (Throwable error) { reportError(id, "status", summary(error)); }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Closing won before this read-only status request could be queued.
        }
    }

    static Status readStatus(Context context) throws Exception {
        Context app = context.getApplicationContext();
        IBinder candidate = resolveWorker();
        WorkerIdentity identity = readIdentity(candidate);
        if (candidate == null || identity == null || identity.version != MotionDetectorProtocol.VERSION
                || identity.build != BuildConfig.VERSION_CODE
                || !currentApkIdentity(app).equals(identity.apk))
            throw new IllegalStateException("current Motion worker is unavailable or obsolete");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
            if (!candidate.transact(MotionDetectorProtocol.TX_STATUS, data, reply, 0))
                throw new IllegalStateException("Motion status unavailable");
            reply.readException();
            return readStatusReply(reply);
        } finally { data.recycle(); reply.recycle(); }
    }

    public static boolean isLiveWarningSession(Context context, String id) {
        if (!MotionDetectorProtocol.validSession(id)) return false;
        try {
            Status status = readStatus(context);
            return id.equals(status.sessionId) && status.warning && !status.lastError.equals("host_shutdown");
        } catch (Throwable ignored) { return false; }
    }

    private static Status readStatusReply(Parcel reply) {
        String state = reply.readString();
        boolean prepared = reply.readInt() == 1;
        boolean eligible = reply.readInt() == 1;
        String error = reply.readString();
        long elapsed = reply.readLong();
        String id = reply.readString();
        String config = reply.readString();
        boolean warning = reply.readInt() == 1;
        boolean drawn = reply.readInt() == 1;
        if (reply.dataAvail() != 0) throw new IllegalStateException("Motion status reply has trailing data");
        return new Status(state, prepared, eligible, error, elapsed, id, config, warning, drawn);
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
        return matchesCurrentApk(readIdentity(candidate));
    }

    private boolean matchesCurrentApk(WorkerIdentity identity) {
        return identity != null && identity.version == MotionDetectorProtocol.VERSION
                && identity.build == BuildConfig.VERSION_CODE
                && currentApkIdentity(context).equals(identity.apk);
    }

    private static WorkerIdentity readIdentity(IBinder candidate) {
        if (candidate == null || !candidate.isBinderAlive()) return null;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
            if (!candidate.transact(MotionDetectorProtocol.TX_PING, data, reply, 0)) return null;
            reply.readException();
            int version = reply.readInt();
            int build = reply.readInt();
            int uid = reply.readInt();
            int pid = reply.readInt();
            String identity = reply.readString();
            if (uid != 2000 || pid <= 0 || identity == null || identity.isEmpty()
                    || reply.dataAvail() != 0) return null;
            return new WorkerIdentity(version, build, pid, identity);
        } catch (Throwable ignored) {
            return null;
        } finally { data.recycle(); reply.recycle(); }
    }

    private void retireObsoleteWorker(int generation, IBinder candidate, WorkerIdentity old) throws Exception {
        if (candidate == null || !candidate.isBinderAlive()) return;
        if (old == null) {
            throw new IllegalStateException("cannot identify existing Motion worker for replacement");
        }
        // The first released worker has no shutdown transaction; use the existing authorized shell.
        reportRequestStatus(generation, "worker_replacing", "pid=" + old.pid + " protocol=" + old.version
                + " build=" + old.build + " old_apk=" + old.apk
                + " new_apk=" + currentApkIdentity(context), SystemClock.elapsedRealtime());
        LocalAdbClient.Result stopped = LocalAdbClient.executeAuthorized(context,
                stopObsoleteWorkerCommand(old.pid), (kind, fields) -> { });
        if (!stopped.ok) throw new IllegalStateException("obsolete Motion worker stop failed: "
                + stopped.error + "; " + stopped.output);
        reportRequestStatus(generation, "obsolete_worker_stopped", "old_pid=" + old.pid,
                SystemClock.elapsedRealtime());
    }

    static String stopObsoleteWorkerCommand(int pid) {
        if (pid <= 0) throw new IllegalArgumentException("positive worker PID required");
        String proc = "/proc/" + pid;
        return "if [ ! -d " + proc + " ]; then exit 0; fi; "
                + "if [ \"$(tr '\\000' '\\n' <" + proc + "/cmdline | head -n 1)\" != '"
                + MotionDetectorProtocol.PROCESS_NAME + "' ] || ! grep -Eq "
                + "'^Uid:[[:space:]]+2000[[:space:]]+2000[[:space:]]+2000[[:space:]]+2000$' "
                + proc + "/status; then echo motion_worker_identity_changed; exit 1; fi; "
                + "kill -TERM " + pid + " || exit 1; "
                + "wait_count=0; while [ -d " + proc + " ] && [ \"$wait_count\" -lt 30 ]; "
                + "do sleep 0.1; wait_count=$((wait_count + 1)); done; "
                + "if [ -d " + proc + " ]; then echo motion_worker_stop_timeout; exit 1; fi";
    }

    private static final class WorkerIdentity {
        final int version, build, pid;
        final String apk;
        WorkerIdentity(int version, int build, int pid, String apk) {
            this.version = version;
            this.build = build;
            this.pid = pid;
            this.apk = apk;
        }
    }

    private static IBinder resolveWorker() throws Exception {
        return service(MotionDetectorProtocol.SERVICE_NAME);
    }

    private static IBinder service(String name) throws Exception {
        return (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, name);
    }

    private boolean isCurrent(String id) {
        return !closed && id != null && id.equals(sessionId);
    }

    private boolean isCurrentRequest(int generation) {
        synchronized (lock) { return !closed && generation == requestGeneration; }
    }

    private void reportRequestStatus(int generation, String state, String detail, long elapsedMs) {
        Listener target = listener;
        if (!isCurrentRequest(generation) || target == null) return;
        main.post(() -> {
            if (isCurrentRequest(generation)) target.onStatus(state, detail, elapsedMs);
        });
    }

    private void reportRequestError(int generation, String id, String stage, String detail) {
        if (id != null && !id.isEmpty() && isCurrent(id)) {
            reportError(id, stage, detail);
            return;
        }
        Listener target = listener;
        if (!isCurrentRequest(generation) || target == null) return;
        long now = SystemClock.elapsedRealtime();
        main.post(() -> { if (isCurrentRequest(generation)) target.onError(stage, detail, now); });
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

    private static String launchCommand(Context context) throws Exception {
        String nativeDir = null;
        try {
            nativeDir = context.getPackageManager().getApplicationInfo(
                    MotionDetectorProtocol.OEM_PACKAGE, 0).nativeLibraryDir;
        } catch (android.content.pm.PackageManager.NameNotFoundException absent) {
            // Media-only access does not require Sentry; monitoring reports its missing package.
        }
        return launchCommand(context.getApplicationInfo().sourceDir, android.os.Process.myUid(),
                currentApkIdentity(context), nativeDir, android.os.Process.is64Bit());
    }

    static String launchCommand(String apkPath, int appUid, String identity,
            String nativeDir, boolean is64Bit) {
        String apk = shellQuote(apkPath);
        String quotedIdentity = shellQuote(identity);
        String lib = is64Bit ? "lib64" : "lib";
        String libraryPath = (nativeDir == null || nativeDir.isEmpty() ? "" : nativeDir + ":")
                + "/system/" + lib + ":/vendor/" + lib;
        return "trap '' HUP; LD_LIBRARY_PATH=" + shellQuote(libraryPath)
                + " CLASSPATH=" + apk + " setsid app_process /system/bin --nice-name="
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
        }
        try {
            commands.execute(() -> {
                try {
                    if (id != null && target != null && target.isBinderAlive())
                        transactDetachUi(target, id, callback);
                } catch (Throwable ignored) { /* Observer detach is best-effort; worker owns the session. */ }
                finally {
                    worker = null;
                    commands.shutdown();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // A concurrent shutdown won before the observer detach could be queued.
            commands.shutdown();
        }
    }

    private static void transactDetachUi(IBinder target, String id, IBinder callback) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
            data.writeString(id);
            data.writeStrongBinder(callback);
            if (!target.transact(MotionDetectorProtocol.TX_DETACH_UI, data, reply, 0))
                throw new IllegalStateException("motion observer detach unavailable");
            reply.readException();
            if (reply.readInt() != 1) throw new IllegalStateException("motion observer detach refused");
        } finally { data.recycle(); reply.recycle(); }
    }

}
