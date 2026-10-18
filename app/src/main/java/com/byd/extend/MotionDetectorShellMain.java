package com.byd.extend;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.graphics.Rect;
import android.graphics.ImageFormat;
import android.media.ImageReader;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.PowerManager;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.Surface;
import android.media.Image;
import com.byd.sentrymode.monitor.library.MotionNativeLibrary;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileLock;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shell-UID worker. It consumes a Surface from the existing camera owner; it never opens AVMCamera. */
public final class MotionDetectorShellMain {
    private static final long CONTROL_TIMEOUT_MS = 5_000L;
    private static final long FIRST_INFERENCE_TIMEOUT_MS = 10_000L;
    private static final String EXTEND_LOCK = MotionDetectorProtocol.LOCK_PATH;

    public static void main(String[] args) {
        try {
            if (args.length != 2 || !args[0].matches("[0-9]{1,10}") || args[1].isEmpty())
                throw new IllegalArgumentException("Expected app UID and Extend APK identity");
            if (Process.myUid() != 2000) throw new SecurityException("Motion worker must run as shell UID 2000");
            int appUid = Integer.parseInt(args[0]);
            String identity = args[1];
            if (Looper.myLooper() == null) Looper.prepareMainLooper();
            try (RandomAccessFile file = new RandomAccessFile(EXTEND_LOCK, "rw")) {
                FileLock acquired = file.getChannel().tryLock();
                if (acquired == null) throw new IllegalStateException("Motion worker already running");
                try (FileLock lock = acquired) {
                Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
                Object activityThread = activityThreadClass.getMethod("systemMain").invoke(null);
                Context system = (Context) activityThreadClass.getMethod("getSystemContext")
                        .invoke(activityThread);
                if (system == null) throw new IllegalStateException("system context unavailable");
                MotionDetectorShellMain daemon = new MotionDetectorShellMain(appUid, identity, system);
                daemon.publish();
                synchronized (daemon) { daemon.wait(); }
                }
            }
        } catch (Throwable error) {
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private final int appUid;
    private final String extendIdentity;
    private final Context systemContext;
    private final HandlerThread controlThread = new HandlerThread("motion-detector-control");
    private final Handler control;
    private final ExecutorService hostOperations = Executors.newSingleThreadExecutor(r ->
            new Thread(r, "motion-camera-host-ops"));
    private final MotionEventPolicy policy = new MotionEventPolicy();
    private final AtomicBoolean frameDrainQueued = new AtomicBoolean();
    private final ShellBinder binder = new ShellBinder();
    private final MotionScreenControl screen;
    private final MotionSessionConfigStore configStore;
    private final MotionSessionRuntime sessionRuntime = new MotionSessionRuntime();
    private final Runnable armCheck = this::advanceEligibility;
    private final Runnable firstInferenceTimeout = this::onFirstInferenceTimeout;
    private final Runnable warningWindowTimeout = this::onWarningWindowTimeout;
    private final Runnable warningTimeout = this::finishWarningAfterPolicy;
    private final Runnable recordingTailFinished = this::finishRecordingTail;
    private volatile MotionConfig config;
    private MotionSessionConfigStore.Snapshot pendingConfig;
    private MotionShellMediaContext mediaContext;
    private MotionMediaLibrary mediaLibrary;
    private MotionMediaIndexBridge mediaBridge;
    private MotionVideoRecorder recorder;
    private MotionGpuComposer composer;
    private PowerManager.WakeLock wakeLock;
    private int recordingGeneration;
    private int recordingRequestId;
    private boolean recordingAttached;
    private boolean recordingStopRequested;
    private boolean teardownPending;
    private boolean teardownNotifyHost;
    private boolean teardownHostShutdown;
    private boolean teardownSurfacesDrained;
    private boolean teardownHostUnbound;
    private boolean teardownUnbindAttempted;
    private boolean teardownDemandCleared;
    private String teardownFinalState = "idle";
    private String teardownReason = "";
    private IBinder teardownCallback;
    private volatile boolean warning;
    private boolean warningWindowPending;
    private volatile boolean warningWindowDrawn;
    private boolean shuttingDown;
    private volatile boolean workerBound;
    private volatile boolean workerBinding;
    private long warningDeadlineElapsedMs;
    private String deliberatelyDetachedProducerReason = "";
    private String warningWindowFailure = "";
    private volatile String expectedSourceEpoch = "";
    private MotionFirmwareProfile.Mismatch firmwareMismatch;

    private volatile String sessionId;
    private volatile IBinder appCallback;
    private IBinder.DeathRecipient appCallbackDeath;
    private volatile String state = "idle";
    private volatile String lastError = "";
    private volatile boolean prepared;
    private volatile boolean eligible;
    private boolean attached;
    private boolean nativeInitialized;
    private int sensitivity;
    private AssetManager assets;
    private MotionNativeLibrary nativeApi;
    private byte[] nativeInput;
    private ImageReader reader;
    private Surface surface;
    private long frameNumber;
    private String lastReportedDetectorState = "";
    private boolean firstInferenceReceived;

    private MotionDetectorShellMain(int appUid, String extendIdentity, Context systemContext) {
        this.appUid = appUid;
        this.extendIdentity = extendIdentity;
        this.systemContext = systemContext;
        this.configStore = new MotionSessionConfigStore(appUid);
        controlThread.start();
        control = new Handler(controlThread.getLooper());
        screen = new MotionScreenControl((screenState, detail) -> {
            String active = sessionId;
            if (active == null) return;
            if ("wake_requested".equals(screenState)) launchWarningWindow(active);
            sendScreen(active, screenState, detail);
            if ("cover_release_requested".equals(screenState)) {
                control.post(() -> {
                    if (!sameSession(active) || !warning || !warningWindowDrawn) return;
                    warningDeadlineElapsedMs = SystemClock.elapsedRealtime() + MotionDetectorProtocol.WARNING_MS;
                    control.removeCallbacks(warningTimeout);
                    control.postDelayed(warningTimeout, MotionDetectorProtocol.WARNING_MS);
                    sendStatus(active, "warning_visible", "warning interval begins after owned cover release was confirmed");
                });
            }
            if (isWarningCycleFailure(screenState)) {
                control.post(() -> {
                    if (!sameSession(active)) return;
                    if (eligible) sessionRuntime.blockWarningRetry();
                    warningWindowFailure = screenState + (detail.isEmpty() ? "" : ": " + detail);
                    warningWindowPending = false;
                    control.removeCallbacks(warningWindowTimeout);
                    sendError(active, "warning_preflight", warningWindowFailure);
                    if (warning) finishWarning(active, false);
                    else endOwnedWake(active);
                });
            }
            if ("wake_skipped".equals(screenState) || "wake_uncertain".equals(screenState)
                    || "cover_release_skipped".equals(screenState)
                    || "cover_release_uncertain".equals(screenState)
                    || "sleep_skipped".equals(screenState) || "sleep_uncertain".equals(screenState)
                    || "black_restore_observed".equals(screenState)
                    || "black_restore_uncertain".equals(screenState)
                    || "finish_uncertain".equals(screenState)
                    || "off_session_lost".equals(screenState)
                    || "user_dismissed_no_sleep".equals(screenState)) {
                control.post(() -> endOwnedWake(active));
            }
        });
    }

    private void publish() throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        serviceManager.getMethod("addService", String.class, IBinder.class)
                .invoke(null, MotionDetectorProtocol.SERVICE_NAME, binder);
        System.out.println("motion_worker_ready uid=" + Process.myUid() + " pid=" + Process.myPid()
                + " protocol=" + MotionDetectorProtocol.VERSION);
        System.out.flush();
        control.post(this::resumePersistedConfig);
    }

    private final class ShellBinder extends Binder {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(MotionDetectorProtocol.DESCRIPTOR);
                return true;
            }
            int callerUid = Binder.getCallingUid();
            if (code == MotionDetectorProtocol.TX_TELEMETRY) {
                if (callerUid != 2000) throw new SecurityException("Motion telemetry requires shell UID");
                data.enforceInterface(MotionDetectorProtocol.DESCRIPTOR);
                String id = data.readString();
                String line = data.readString();
                if (!MotionDetectorProtocol.validSession(id) || line == null || line.length() > 8192
                        || data.dataAvail() != 0)
                    throw new IllegalArgumentException("invalid Motion telemetry event");
                control.post(() -> acceptTelemetry(id, line));
                return true;
            }
            if (callerUid != appUid) {
                if (reply != null) reply.writeException(new SecurityException("motion caller denied"));
                return true;
            }
            try {
                data.enforceInterface(MotionDetectorProtocol.DESCRIPTOR);
                switch (code) {
                    case MotionDetectorProtocol.TX_PING:
                        if (data.dataAvail() != 0) throw new IllegalArgumentException("ping payload not empty");
                        reply.writeNoException();
                        reply.writeInt(MotionDetectorProtocol.VERSION);
                        reply.writeInt(BuildConfig.VERSION_CODE);
                        reply.writeInt(Process.myUid());
                        reply.writeInt(Process.myPid());
                        reply.writeString(extendIdentity);
                        return true;
                    case MotionDetectorProtocol.TX_CONFIGURE: {
                        int version = data.readInt();
                        int build = data.readInt();
                        String configJson = data.readString();
                        if (version != MotionDetectorProtocol.VERSION || build != BuildConfig.VERSION_CODE
                                || configJson == null || configJson.length() > 8192 || data.dataAvail() != 0)
                            throw new IllegalArgumentException("invalid Motion config request");
                        MotionSessionConfigStore.Snapshot snapshot = callControl(
                                () -> configureOnControl(configJson));
                        reply.writeNoException();
                        reply.writeInt(1);
                        reply.writeString(snapshot == null ? "" : snapshot.sessionId);
                        reply.writeStrongBinder(binder);
                        return true;
                    }
                    case MotionDetectorProtocol.TX_ATTACH_UI: {
                        String id = data.readString();
                        IBinder callback = data.readStrongBinder();
                        if (!MotionDetectorProtocol.validSession(id) || callback == null || data.dataAvail() != 0)
                            throw new IllegalArgumentException("invalid Motion UI attachment");
                        int accepted = callControl(() -> attachUiOnControl(id, callback));
                        reply.writeNoException();
                        reply.writeInt(accepted);
                        return true;
                    }
                    case MotionDetectorProtocol.TX_DETACH_UI: {
                        String id = data.readString();
                        IBinder callback = data.readStrongBinder();
                        if (!MotionDetectorProtocol.validSession(id) || callback == null || data.dataAvail() != 0)
                            throw new IllegalArgumentException("invalid Motion UI detachment");
                        int accepted = callControl(() -> detachUiOnControl(id, callback));
                        reply.writeNoException();
                        reply.writeInt(accepted);
                        return true;
                    }
                    case MotionDetectorProtocol.TX_STATUS: {
                        if (data.dataAvail() != 0) throw new IllegalArgumentException("status payload not empty");
                        reply.writeNoException();
                        reply.writeString(state);
                        reply.writeInt(prepared ? 1 : 0);
                        reply.writeInt(eligible ? 1 : 0);
                        reply.writeString(lastError);
                        reply.writeLong(SystemClock.elapsedRealtime());
                        reply.writeString(sessionId == null ? "" : sessionId);
                        reply.writeString(config == null ? "" : config.toJson());
                        reply.writeInt(warning ? 1 : 0);
                        reply.writeInt(warningWindowDrawn ? 1 : 0);
                        return true;
                    }
                    case MotionDetectorProtocol.TX_EXPLICIT_SHUTDOWN: {
                        String id = data.readString();
                        String reason = data.readString();
                        if (!MotionDetectorProtocol.validSession(id) || !validReason(reason)
                                || data.dataAvail() != 0)
                            throw new IllegalArgumentException("invalid Motion shutdown request");
                        int accepted = callControl(() -> suspendForExplicitShutdown(id, reason));
                        reply.writeNoException();
                        reply.writeInt(accepted);
                        return true;
                    }
                    case MotionDetectorProtocol.TX_IMPORT_MEDIA_INDEX: {
                        ParcelFileDescriptor source = data.readTypedObject(ParcelFileDescriptor.CREATOR);
                        if (source == null || data.dataAvail() != 0)
                            throw new IllegalArgumentException("legacy Motion index file descriptor required");
                        try (ParcelFileDescriptor descriptor = source;
                                InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                            ensureMediaBridge();
                            boolean complete = mediaBridge.importLegacyIndex(input);
                            reply.writeNoException();
                            reply.writeInt(complete ? 1 : 0);
                        }
                        return true;
                    }
                    case MotionDetectorProtocol.TX_REFRESH_MEDIA: {
                        if (data.dataAvail() != 0) throw new IllegalArgumentException("refresh payload not empty");
                        ensureMediaBridge();
                        int changed = mediaLibrary.refresh();
                        reply.writeNoException();
                        reply.writeInt(changed);
                        return true;
                    }
                    case MotionDetectorProtocol.TX_QUERY_MEDIA: {
                        MotionMediaLibrary.Query query = readMediaQuery(data);
                        String configJson = data.readString();
                        String pageToken = data.readString();
                        if (configJson == null || configJson.length() > 8192
                                || (pageToken != null && (pageToken.length() > 20
                                || !pageToken.matches("[0-9]+"))) || data.dataAvail() != 0)
                            throw new IllegalArgumentException("invalid Motion media query");
                        MotionConfig requested = MotionConfig.parse(configJson);
                        ensureMediaBridge();
                        String result = mediaBridge.queryPage(query, requested, pageToken);
                        reply.writeNoException();
                        reply.writeString(result);
                        return true;
                    }
                    case MotionDetectorProtocol.TX_READ_MEDIA: {
                        String volumeId = data.readString();
                        long mediaStoreId = data.readLong();
                        if (!validVolumeId(volumeId) || mediaStoreId <= 0 || data.dataAvail() != 0)
                            throw new IllegalArgumentException("invalid Motion media selector");
                        ensureMediaBridge();
                        String result = mediaBridge.read(volumeId, mediaStoreId);
                        reply.writeNoException();
                        reply.writeString(result);
                        return true;
                    }
                    case MotionDetectorProtocol.TX_DELETE_MEDIA: {
                        String volumeId = data.readString();
                        long mediaStoreId = data.readLong();
                        if (!validVolumeId(volumeId) || mediaStoreId <= 0 || data.dataAvail() != 0)
                            throw new IllegalArgumentException("invalid Motion media selector");
                        ensureMediaBridge();
                        boolean deleted = mediaBridge.delete(volumeId, mediaStoreId);
                        reply.writeNoException();
                        reply.writeInt(deleted ? 1 : 0);
                        return true;
                    }
                    case MotionDetectorProtocol.TX_START:
                    case MotionDetectorProtocol.TX_SET_ELIGIBLE:
                    case MotionDetectorProtocol.TX_STOP:
                        throw new IllegalArgumentException("obsolete Motion control transaction");
                    case MotionDetectorProtocol.TX_WAKE_FOR_WARNING:
                    case MotionDetectorProtocol.TX_WARNING_WINDOW_READY:
                    case MotionDetectorProtocol.TX_FINISH_WARNING:
                    case MotionDetectorProtocol.TX_USER_TAKEOVER: {
                        if (code != MotionDetectorProtocol.TX_WARNING_WINDOW_READY
                                && code != MotionDetectorProtocol.TX_USER_TAKEOVER)
                            throw new IllegalArgumentException("obsolete app-owned Motion screen control");
                        String id = data.readString();
                        if (data.dataAvail() != 0) throw new IllegalArgumentException("screen payload trailing data");
                        if (!sameSession(id)) throw new IllegalStateException("motion session expired");
                        if (code == MotionDetectorProtocol.TX_WARNING_WINDOW_READY) {
                            callControl(() -> acknowledgeWarningDraw(id));
                        } else callControl(() -> userDismissWarning(id));
                        reply.writeNoException();
                        reply.writeInt(1);
                        return true;
                    }
                    default:
                        return super.onTransact(code, data, reply, flags);
                }
            } catch (Throwable error) {
                if (reply != null) reply.writeException(error instanceof Exception
                        ? (Exception) error : new RuntimeException(error));
                return true;
            }
        }
    }

    private MotionSessionConfigStore.Snapshot configureOnControl(String configJson) throws Exception {
        int installedUid = systemContext.getPackageManager().getPackageUid(
                MotionShellMediaContext.OWNER_PACKAGE, 0);
        if (installedUid != appUid) throw new SecurityException("installed app UID changed");
        MotionSessionConfigStore.Snapshot next = configStore.write(configJson);
        if (next == null) {
            pendingConfig = null;
            if (sessionId != null) stopOnControl(sessionId, "settings_disabled");
            config = null;
            eligible = false;
            return null;
        }
        if (next.sessionId.equals(sessionId) && !sessionRuntime.suspended()) {
            if (teardownPending) {
                pendingConfig = next;
                return null;
            }
            return next;
        }
        return startConfiguredSession(next) ? next : null;
    }

    private void resumePersistedConfig() {
        try {
            MotionSessionConfigStore.Snapshot saved = configStore.read();
            if (saved != null && sessionId == null) startConfiguredSession(saved);
        } catch (Throwable error) {
            state = "error";
            lastError = "persisted_config: " + summary(error);
            System.err.println("motion_config_restore_failed " + lastError);
        }
    }

    private boolean startConfiguredSession(MotionSessionConfigStore.Snapshot snapshot) throws Exception {
        if (sessionId != null) {
            if (teardownPending) {
                pendingConfig = snapshot;
                sendStatus(sessionId, "settings_pending_teardown",
                        "latest settings snapshot will start after confirmed encoder and Surface drain");
                return false;
            }
            stopOnControl(sessionId, "settings_changed");
            if (sessionId != null) {
                pendingConfig = snapshot;
                sendStatus(sessionId, "settings_pending_teardown",
                        "latest settings snapshot will start after confirmed encoder and Surface drain");
                return false;
            }
        }
        String id = snapshot.sessionId;
        config = snapshot.config;
        sessionRuntime.configure(id, config);
        shuttingDown = false;
        sessionId = id;
        appCallback = null;
        state = "preparing";
        prepared = false;
        eligible = false;
        attached = false;
        warning = false;
        warningWindowPending = false;
        warningWindowDrawn = false;
        warningWindowFailure = "";
        deliberatelyDetachedProducerReason = "";
        lastError = "";
        lastReportedDetectorState = "";
        frameNumber = 0L;
        firstInferenceReceived = false;
        workerBound = false;
        expectedSourceEpoch = "";
        screen.beginSession(id);
        if (firmwareMismatch != null) {
            state = "error";
            lastError = MotionFirmwareProfile.ERROR_STAGE + ": " + summary(firmwareMismatch);
            sendError(id, MotionFirmwareProfile.ERROR_STAGE, summary(firmwareMismatch));
            sendStatus(id, "error", lastError);
            return true;
        }
        sendStatus(id, "preparing", "shell-owned Motion session; inspecting installed OEM detector artifacts");
        workerBinding = true;
        hostOperations.execute(() -> {
            Throwable failure = null;
            try {
                if (!cameraOperation(MotionDetectorProtocol.CAMERA_BIND_WORKER, id, null, null, 0))
                    throw new IllegalStateException("camera runtime refused Motion worker binding");
                setMotionTelemetryDemand(id, true);
            } catch (Throwable error) { failure = error; }
            Throwable result = failure;
            control.post(() -> onHostBindingComplete(id, result));
        });
        return true;
    }

    private void onHostBindingComplete(String id, Throwable failure) {
        if (failure != null) {
            workerBinding = false;
            if (sameSession(id)) fail(id, "camera_bind", failure);
            else if (workerBound) hostOperations.execute(() -> cleanupHostBinding(id));
            return;
        }
        if (!sameSession(id) || shuttingDown) {
            workerBinding = false;
            hostOperations.execute(() -> cleanupHostBinding(id));
            return;
        }
        workerBinding = false;
        workerBound = true;
        prepareSession(id);
    }

    private void cleanupHostBinding(String id) {
        try { setMotionTelemetryDemand(id, false); } catch (Throwable ignored) { }
        try { cameraOperation(MotionDetectorProtocol.CAMERA_END_WAKE, id, null, null, 0); }
        catch (Throwable ignored) { }
        try { cameraOperation(MotionDetectorProtocol.CAMERA_UNBIND_WORKER, id, null, null, 0); }
        catch (Throwable ignored) { }
    }

    private int attachUiOnControl(String id, IBinder callback) throws Exception {
        if (!sameSession(id) || shuttingDown || sessionRuntime.suspended())
            throw new IllegalStateException("Motion session is not active");
        if (!callback.isBinderAlive()) throw new IllegalStateException("Motion UI callback is dead");
        detachUiOnControl(id, appCallback);
        appCallback = callback;
        appCallbackDeath = () -> control.post(() -> detachUiOnControl(id, callback));
        callback.linkToDeath(appCallbackDeath, 0);
        if (prepared) sendPrepared(id);
        String detail = "UI observer attached; shell session continues independently";
        if ("error".equals(state) && !lastError.isEmpty()) detail += "; " + lastError;
        sendStatus(id, state, detail);
        return 1;
    }

    private int detachUiOnControl(String id, IBinder callback) {
        if (!sameSession(id) || callback == null || appCallback != callback) return 1;
        if (appCallbackDeath != null) {
            try { callback.unlinkToDeath(appCallbackDeath, 0); } catch (Throwable ignored) { }
        }
        appCallback = null;
        appCallbackDeath = null;
        return 1;
    }

    private void setMotionTelemetryDemand(String id, boolean enabled) throws Exception {
        IBinder guard = serviceBinder(TurnSignalShellProtocol.SERVICE_NAME);
        if (guard == null) throw new IllegalStateException("Motion telemetry helper is unavailable");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TurnSignalShellProtocol.DESCRIPTOR);
            data.writeString(id);
            data.writeStrongBinder(binder);
            data.writeInt(enabled ? 1 : 0);
            if (!guard.transact(TurnSignalShellProtocol.TX_SET_MOTION_TELEMETRY, data, reply, 0))
                throw new IllegalStateException("Motion telemetry subscription was not accepted");
            reply.readException();
            if (reply.dataAvail() != 0) throw new IllegalStateException("ambiguous Motion telemetry reply");
        } finally { data.recycle(); reply.recycle(); }
    }

    private int suspendForExplicitShutdown(String id, String reason) {
        if (!sameSession(id)) return 1;
        pendingConfig = null;
        shuttingDown = true;
        sessionRuntime.suspendForHostShutdown();
        stopOnControl(id, "explicit_shutdown_" + reason);
        return 1;
    }

    private void prepareSession(String id) {
        if (!sameSession(id)) return;
        MotionNativeSetup setup = new MotionNativeSetup(systemContext);
        try {
            setup.prepare();
            assets = setup.assets;
            sensitivity = setup.sensitivity;
            nativeApi = new MotionNativeLibrary();
            nativeInput = new byte[MotionDetectorProtocol.INPUT_BYTES];
            int init = nativeApi.init(assets, MotionDetectorProtocol.PREVIEW_WIDTH,
                    MotionDetectorProtocol.PREVIEW_HEIGHT, setup.mirrorSetting == 0, setup.modelValue);
            if (init != 0 && init != 1) throw new IOException("native init refused: " + init);
            nativeInitialized = true;
            prepared = true;
            state = "prepared";
            sendPrepared(id);
            sendStatus(id, "prepared", "OEM artifacts and native init accepted; awaiting fresh shell telemetry");
            if (sessionRuntime.armed()) setEligibleOnControl(id, true);
            else if (!sessionRuntime.arming()) holdCpu(false);
        } catch (Throwable error) {
            if (assets == null && setup.assets != null) {
                try { setup.assets.close(); } catch (Throwable ignored) { }
            }
            if (error instanceof MotionFirmwareProfile.Mismatch)
                firmwareMismatch = (MotionFirmwareProfile.Mismatch) error;
            fail(id, error instanceof MotionFirmwareProfile.Mismatch
                    ? MotionFirmwareProfile.ERROR_STAGE : "prepare", error);
        }
    }

    private int setEligibleOnControl(String id, boolean next) throws Exception {
        requireSession(id);
        if (!prepared) throw new IllegalStateException("detector is not prepared");
        if (eligible == next) return 1;
        if (!next) {
            policy.setEligible(false, SystemClock.elapsedRealtime());
            eligible = false;
            control.removeCallbacks(firstInferenceTimeout);
            screen.setEligible(id, false);
            boolean readerDetached = detachAndCloseReader(false);
            if (!readerDetached) sendError(id, "camera_detach", "detector Surface remains owned until a confirmed drain");
            if (warning) finishWarning(id, false);
            if (recorder != null && recorder.isRecording()) {
                long deadline = SystemClock.elapsedRealtime() + config.afterSeconds * 1000L;
                recorder.finishAfter(deadline);
                control.removeCallbacks(recordingTailFinished);
                control.postDelayed(recordingTailFinished, config.afterSeconds * 1000L);
                state = "recording_tail";
            } else {
                state = stopRecording("eligibility_ended", false) ? "prepared" : "recording_detach_pending";
            }
            sendStatus(id, state, "fresh Motion telemetry no longer meets the configured activation mode");
            return 1;
        }
        attachReader(id);
        eligible = true;
        policy.setEligible(true, SystemClock.elapsedRealtime() - MotionDetectorProtocol.WARMUP_MS);
        screen.setEligible(id, true);
        state = "waiting_for_frames";
        lastReportedDetectorState = "";
        firstInferenceReceived = false;
        control.removeCallbacks(firstInferenceTimeout);
        control.postDelayed(firstInferenceTimeout, FIRST_INFERENCE_TIMEOUT_MS);
        if (config.recordingEnabled) startRecording(id);
        sendStatus(id, "waiting_for_frames", "camera Surface attached; status remains pending until first successful native inference");
        return 1;
    }

    private void attachReader(String id) throws Exception {
        if (attached && reader != null && surface != null) return;
        if (attached || reader != null || surface != null)
            throw new IllegalStateException("motion Surface attachment is in an uncertain state");
        reader = ImageReader.newInstance(MotionDetectorProtocol.INPUT_WIDTH,
                MotionDetectorProtocol.INPUT_HEIGHT, ImageFormat.YUV_420_888, 2);
        surface = reader.getSurface();
        reader.setOnImageAvailableListener(source -> {
            if (frameDrainQueued.compareAndSet(false, true)) control.post(this::drainLatestFrame);
        }, control);
        boolean accepted;
        try { accepted = cameraOperation(MotionDetectorProtocol.CAMERA_ATTACH, id, surface); }
        catch (Throwable uncertain) {
            attached = true;
            throw uncertain;
        }
        if (!accepted) {
            detachAndCloseReader(false);
            throw new IllegalStateException("existing camera owner refused Surface index 4123");
        }
        attached = true;
    }

    private void drainLatestFrame() {
        try {
            if (!eligible || reader == null) return;
            Image frame = reader.acquireLatestImage();
            if (frame == null) return;
            try (Image owned = frame) { processFrame(owned); }
        } catch (Throwable error) {
            String id = sessionId;
            if (id != null) fail(id, "frame", error);
        } finally {
            frameDrainQueued.set(false);
        }
    }

    private void processFrame(Image frame) throws Exception {
        if (frame.getFormat() != ImageFormat.YUV_420_888
                || frame.getWidth() != MotionDetectorProtocol.INPUT_WIDTH
                || frame.getHeight() != MotionDetectorProtocol.INPUT_HEIGHT)
            throw new IllegalStateException("unexpected composed camera Surface format/dimensions");
        Rect crop = frame.getCropRect();
        if (crop.left != 0 || crop.top != 0 || crop.right != frame.getWidth()
                || crop.bottom != frame.getHeight())
            throw new IllegalStateException("motion frame crop is not the inspected full composition");
        Image.Plane[] planes = frame.getPlanes();
        if (planes == null || planes.length != 3)
            throw new IllegalStateException("YUV_420_888 did not provide exactly three planes");
        MotionYuvPacker.pack(
                plane(planes[0]), plane(planes[1]), plane(planes[2]),
                frame.getWidth(), frame.getHeight(), nativeInput);
        long frameTimeNs = frame.getTimestamp();
        long now = SystemClock.elapsedRealtime();
        int raw = nativeApi.detect(nativeInput, MotionDetectorProtocol.INPUT_WIDTH,
                MotionDetectorProtocol.INPUT_HEIGHT, sensitivity);
        frameNumber++;
        if (raw == MotionEventPolicy.ERROR_RESULT) {
            noteFirstInference(raw, "not_read_after_error", frameTimeNs);
            policy.observe(raw, now);
            fail(sessionId, "native_detect", new IllegalStateException(
                    "native detect returned -2; getState was not called"));
            return;
        }
        int nativeState = nativeApi.getState();
        noteFirstInference(raw, nativeState, frameTimeNs);
        MotionEventPolicy.Decision decision = policy.observe(raw, now);
        state = policy.warningActive(now) ? "warning_active" : "observing";
        if (!state.equals(lastReportedDetectorState)) {
            lastReportedDetectorState = state;
            sendStatus(sessionId, state, "detector policy state changed");
        }
        if (decision.level != MotionEventPolicy.LEVEL_NONE) {
            sendDetection(sessionId, decision.level, decision.elapsedMs, raw,
                    nativeState, frameNumber, frameTimeNs, decision.warningUntilMs);
            if (recorder != null) recorder.onDetection(decision.level, decision.elapsedMs);
            if (config != null && config.warningEnabled && !warning)
                requestWarningWake(sessionId);
        }
    }

    private void noteFirstInference(int raw, Object nativeState, long frameTimestampNs) {
        if (firstInferenceReceived) return;
        firstInferenceReceived = true;
        control.removeCallbacks(firstInferenceTimeout);
        logEvent("motion_first_inference", sessionId, "frame", frameNumber,
                "raw_result", raw, "native_state", nativeState,
                "frame_timestamp_ns", frameTimestampNs);
    }

    private void onFirstInferenceTimeout() {
        String id = sessionId;
        if (id == null || !sameSession(id) || !eligible || firstInferenceReceived) return;
        fail(id, "first_inference_timeout", new IOException(
                "camera Surface produced no detector inference within 10 seconds"));
    }

    private static MotionYuvPacker.Plane plane(Image.Plane plane) {
        ByteBuffer view = plane.getBuffer();
        return new MotionYuvPacker.Plane(view, plane.getRowStride(), plane.getPixelStride());
    }

    private boolean cameraOperation(int operation, String id, Surface detector,
            Surface[] recording, int requestId) throws Exception {
        if (Process.myUid() != 2000) throw new SecurityException("camera Surface routing requires shell UID");
        IBinder cameraHost = serviceBinder(TurnSignalShellProtocol.SERVICE_NAME);
        if (cameraHost == null) throw new IllegalStateException("existing camera owner service is unavailable");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TurnSignalShellProtocol.DESCRIPTOR);
            data.writeInt(operation);
            data.writeString(id);
            data.writeStrongBinder(binder);
            if (operation == MotionDetectorProtocol.CAMERA_ATTACH) data.writeTypedObject(detector, 0);
            if (operation == MotionDetectorProtocol.CAMERA_RECORD_ATTACH) {
                data.writeTypedArray(recording, 0);
                data.writeInt(requestId);
            }
            if (!cameraHost.transact(TurnSignalShellProtocol.TX_CAMERA_RUNTIME, data, reply, 0))
                throw new IllegalStateException("camera runtime did not accept auxiliary operation " + operation);
            reply.readException();
            boolean accepted = reply.readInt() == 1;
            if (operation == MotionDetectorProtocol.CAMERA_BIND_WORKER) {
                String epoch = reply.readString();
                if (!accepted || epoch == null || epoch.isEmpty() || epoch.length() > 128
                        || reply.dataAvail() != 0)
                    throw new IllegalStateException("camera runtime returned an invalid Motion source epoch");
                expectedSourceEpoch = epoch;
                workerBound = true;
                return true;
            }
            if (operation == MotionDetectorProtocol.CAMERA_UNBIND_WORKER
                    && accepted && reply.dataAvail() == 0) workerBound = false;
            return accepted && reply.dataAvail() == 0;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean cameraOperation(int operation, String id, Surface detector) throws Exception {
        return cameraOperation(operation, id, detector, null, 0);
    }

    private void requestWarningWake(String id) {
        if (!sameSession(id) || !eligible || warning || sessionRuntime.warningRetryBlocked()
                || config == null || !config.warningEnabled) return;
        warning = true;
        warningWindowPending = false;
        warningWindowDrawn = false;
        warningDeadlineElapsedMs = 0L;
        warningWindowFailure = "";
        control.removeCallbacks(warningTimeout);
        holdCpu(true);
        try {
            if (!cameraOperation(MotionDetectorProtocol.CAMERA_OWN_WAKE, id, null, null, 0))
                throw new IllegalStateException("camera host refused Motion-owned wake token");
            screen.wakeForWarning(id);
            sendStatus(id, "warning_waking", "shell registered the current OFF-state camera producer before wake reason 13");
        } catch (Throwable error) {
            sessionRuntime.blockWarningRetry();
            warningWindowFailure = summary(error);
            sendError(id, "wake_registration", warningWindowFailure);
            sendStatus(id, "warning_error", warningWindowFailure);
            finishWarning(id, false);
        }
    }

    private void launchWarningWindow(String id) {
        control.post(() -> {
            if (!sameSession(id) || !warning || !eligible || warningWindowPending || warningWindowDrawn) return;
            warningWindowPending = true;
            control.removeCallbacks(warningWindowTimeout);
            control.postDelayed(warningWindowTimeout, MotionDetectorProtocol.WARNING_MS);
            try {
                Intent intent = new Intent().setClassName(MotionShellMediaContext.OWNER_PACKAGE,
                                "com.byd.extend.ui.motion.MotionFullscreenActivity")
                        .setAction(MotionDetectorProtocol.ACTION_SHOW_WARNING)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        .putExtra(MotionDetectorProtocol.EXTRA_SESSION, id);
                startActivityFromShell(intent);
                sendStatus(id, "warning_window_pending", "shell launched warning Activity; first draw must be acknowledged within 10 seconds");
            } catch (Throwable error) {
                warningWindowPending = false;
                if (eligible) sessionRuntime.blockWarningRetry();
                warningWindowFailure = summary(error);
                sendError(id, "warning_activity_launch", warningWindowFailure);
                finishWarning(id, false);
            }
        });
    }

    private void onWarningWindowTimeout() {
        if (!warning || warningWindowDrawn || sessionId == null) return;
        warningWindowPending = false;
        if (eligible) sessionRuntime.blockWarningRetry();
        warningWindowFailure = "first_draw_timeout";
        sendError(sessionId, "warning_first_draw", warningWindowFailure);
        sendStatus(sessionId, "warning_error", "first-draw acknowledgement was not received within 10 seconds");
        finishWarning(sessionId, false);
    }

    private int acknowledgeWarningDraw(String id) throws Exception {
        requireSession(id);
        if (!warning || !warningWindowPending || warningWindowDrawn)
            throw new IllegalStateException("warning Activity is not awaiting its first draw");
        warningWindowPending = false;
        warningWindowDrawn = true;
        control.removeCallbacks(warningWindowTimeout);
        screen.warningWindowReady(id);
        sendStatus(id, "warning_visible", "authenticated Motion session reported first drawn frame");
        return 1;
    }

    private int userDismissWarning(String id) {
        if (!sameSession(id)) throw new IllegalStateException("Motion session expired");
        if (!warning) return 1;
        control.removeCallbacks(warningWindowTimeout);
        if (eligible) sessionRuntime.blockWarningRetry();
        screen.markUserTakeover(id);
        warning = false;
        warningWindowPending = false;
        warningWindowDrawn = false;
        warningDeadlineElapsedMs = 0L;
        screen.finishWarning(id, false);
        dismissWarningActivity(id);
        sendStatus(id, "warning_user_dismissed", "explicit user dismissal; no automated sleep will be attempted");
        return 1;
    }

    private int finishWarning(String id, boolean restoreAllowed) {
        if (!sameSession(id)) return 0;
        control.removeCallbacks(warningWindowTimeout);
        control.removeCallbacks(warningTimeout);
        boolean canRestore = restoreAllowed && eligible && warningWindowDrawn
                && sessionRuntime.power() == 0 && !sessionRuntime.suspended();
        if (warning) screen.finishWarning(id, canRestore);
        warning = false;
        warningWindowPending = false;
        warningWindowDrawn = false;
        warningDeadlineElapsedMs = 0L;
        dismissWarningActivity(id);
        sendStatus(id, canRestore ? "warning_finished" : "warning_restore_skipped",
                canRestore ? "shell will restore only its confirmed owned wake" : "warning ended without an authenticated drawable owned wake");
        return 1;
    }

    private void finishWarningAfterPolicy() {
        String id = sessionId;
        if (id != null && warning && warningDeadlineElapsedMs > 0L
                && SystemClock.elapsedRealtime() >= warningDeadlineElapsedMs)
            finishWarning(id, true);
    }

    private void dismissWarningActivity(String id) {
        try {
            Intent intent = new Intent().setClassName(MotionShellMediaContext.OWNER_PACKAGE,
                            "com.byd.extend.ui.motion.MotionFullscreenActivity")
                    .setAction(MotionDetectorProtocol.ACTION_DISMISS_WARNING)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra(MotionDetectorProtocol.EXTRA_SESSION, id);
            startActivityFromShell(intent);
        } catch (Throwable ignored) { }
    }

    private static int startActivityFromShell(Intent intent) throws Exception {
        Class<?> taskManager = Class.forName("android.app.IActivityTaskManager");
        IBinder taskBinder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "activity_task");
        if (taskBinder == null) throw new IllegalStateException("activity_task service unavailable");
        Object tasks = Class.forName("android.app.IActivityTaskManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, taskBinder);
        Method start = taskManager.getMethod("startActivity",
                Class.forName("android.app.IApplicationThread"), String.class, String.class,
                Intent.class, String.class, IBinder.class, String.class, int.class, int.class,
                Class.forName("android.app.ProfilerInfo"), Class.forName("android.os.Bundle"));
        int result = (Integer) start.invoke(tasks, null, "com.android.shell", null, intent,
                null, null, null, 0, 0, null, null);
        if (result < 0) throw new IllegalStateException("shell Activity launch rejected: " + result);
        return result;
    }

    private static boolean isWarningCycleFailure(String state) {
        return "wake_skipped".equals(state) || "wake_uncertain".equals(state)
                || "cover_release_skipped".equals(state) || "cover_release_uncertain".equals(state)
                || "sleep_skipped".equals(state) || "sleep_uncertain".equals(state)
                || "black_restore_uncertain".equals(state) || "finish_uncertain".equals(state)
                || "off_session_lost".equals(state);
    }

    private void endOwnedWake(String id) {
        try { cameraOperation(MotionDetectorProtocol.CAMERA_END_WAKE, id, null, null, 0); }
        catch (Throwable error) { sendError(id, "wake_release", summary(error)); }
    }

    private void holdCpu(boolean required) {
        try {
            if (required && wakeLock == null) {
                PowerManager manager = (PowerManager) systemContext.getSystemService(Context.POWER_SERVICE);
                if (manager == null) throw new IllegalStateException("PowerManager unavailable");
                wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "com.byd.extend:motion-shell");
                wakeLock.setReferenceCounted(false);
            }
            if (required && !wakeLock.isHeld()) wakeLock.acquire();
            else if (!required && wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (RuntimeException error) {
            if (sessionId != null) sendError(sessionId, "wake_lock", summary(error));
        }
    }

    private void acceptTelemetry(String id, String line) {
        if (!sameSession(id) || line == null) return;
        try {
            org.json.JSONObject event = new org.json.JSONObject(line);
            String kind = event.optString("kind", "");
            if ("motion_host_shutdown".equals(kind)) {
                shutdownFromHost(id, event.optString("reason", "host_shutdown"),
                        event.optBoolean("surfaces_drained", false));
                return;
            }
            if ("camera_producer_closed".equals(kind) || "camera_error".equals(kind)) {
                handleProducerLoss(id, kind, event.optString("reason", kind),
                        event.optString("error", ""));
                return;
            }
            if (teardownPending || shuttingDown || "error".equals(state)) return;
            if ("helper_death".equals(kind) || "helper_ping_failed".equals(kind)) {
                sessionRuntime.invalidateTelemetry();
                control.removeCallbacks(armCheck);
                if (eligible) setEligibleOnControl(id, false);
                eligible = false;
                holdCpu(false);
                sendStatus(id, "telemetry_disconnected", event.optString("error", kind));
                return;
            }
            if (!"motion_vehicle_state".equals(kind) && !"shell_power_state".equals(kind)) return;
            String signal = event.optString("signal", "");
            if (!"power".equals(signal) && !"lock".equals(signal)) return;
            String epoch = event.optString("source_epoch", "");
            if (epoch.isEmpty() || !epoch.equals(expectedSourceEpoch)) return;
            int value = event.optInt("value", Integer.MIN_VALUE);
            boolean baseline = event.optBoolean("baseline", false);
            long now = SystemClock.elapsedRealtime();
            MotionSessionRuntime.Change change = sessionRuntime.telemetry(epoch, signal,
                    value, baseline, now);
            if (change == MotionSessionRuntime.Change.EPOCH_CHANGED) {
                if (eligible) setEligibleOnControl(id, false);
                eligible = false;
                control.removeCallbacks(armCheck);
                sendStatus(id, "telemetry_epoch_changed", "old Power/Lock baselines discarded");
            }
            if (sessionRuntime.arming()) {
                holdCpu(true);
                control.removeCallbacks(armCheck);
                control.postDelayed(armCheck, MotionDetectorProtocol.WARMUP_MS);
                sendStatus(id, "arming", "fresh mode-eligible telemetry must remain valid for 2.5 seconds");
            }
            if (change == MotionSessionRuntime.Change.DISARMED) {
                control.removeCallbacks(armCheck);
                if (eligible) setEligibleOnControl(id, false);
                eligible = false;
                if (recorder == null || !recorder.isRecording()) holdCpu(false);
                sendStatus(id, "disarmed", "Power/Lock telemetry no longer meets Motion mode");
            }
        } catch (Throwable error) {
            System.err.println("motion_telemetry_rejected " + summary(error));
        }
    }

    private void advanceEligibility() {
        if (sessionId == null || shuttingDown || sessionRuntime.suspended()) return;
        MotionSessionRuntime.Change change = sessionRuntime.advance(SystemClock.elapsedRealtime());
        if (change == MotionSessionRuntime.Change.ARMED) {
            holdCpu(true);
            sendStatus(sessionId, "armed", "2.5-second shell telemetry warm-up completed");
            try {
                if (prepared) setEligibleOnControl(sessionId, true);
            } catch (Throwable error) {
                fail(sessionId, "camera_attach", error);
            }
        } else if (change == MotionSessionRuntime.Change.DISARMED) {
            control.removeCallbacks(armCheck);
            if (eligible) {
                try { setEligibleOnControl(sessionId, false); }
                catch (Throwable error) { fail(sessionId, "camera_detach", error); }
            }
            eligible = false;
            if (recorder == null || !recorder.isRecording()) holdCpu(false);
        }
    }

    private void handleProducerLoss(String id, String kind, String reason, String detail) {
        boolean producerClosed = "camera_producer_closed".equals(kind);
        if (teardownPending) {
            if (producerClosed) {
                teardownSurfacesDrained = true;
                if (teardownHostShutdown) teardownHostUnbound = true;
                continuePendingTeardown();
            }
            return;
        }
        boolean deliberate = !eligible && reason.equals(deliberatelyDetachedProducerReason)
                && ("motion_detector_idle".equals(reason) || "motion_recording_stop".equals(reason)
                    || "source_demand_idle".equals(reason));
        if (deliberate || !sameSession(id) || shuttingDown) return;
        sendStatus(id, "camera_recovering", kind + ":" + reason
                + (detail.isEmpty() ? "" : ":" + detail));
        sessionRuntime.invalidateTelemetry();
        control.removeCallbacks(armCheck);
        try {
            if (eligible) {
                policy.setEligible(false, SystemClock.elapsedRealtime());
                screen.setEligible(id, false);
                control.removeCallbacks(firstInferenceTimeout);
            }
            eligible = false;
            if (warning) finishWarning(id, false);
            boolean recordingStopped = stopRecording("camera_source_lost", producerClosed);
            boolean readerDetached = detachAndCloseReader(producerClosed);
            if (!recordingStopped || !readerDetached)
                sendStatus(id, "camera_recovering", "camera consumer retained until host detach or producer closure is confirmed");
            holdCpu(false);
        } catch (Throwable error) {
            fail(id, "camera_source_lost", error);
        }
    }

    private void startRecording(String id) {
        if (!sameSession(id) || !eligible || config == null || !config.recordingEnabled
                || recorder != null || shuttingDown) return;
        try {
            int generation = ++recordingGeneration;
            ensureMediaBridge();
            final MotionVideoRecorder[] owner = new MotionVideoRecorder[1];
            MotionVideoRecorder created = new MotionVideoRecorder(mediaContext, mediaLibrary, id,
                    detail -> control.post(() -> onRecorderEvent(id, owner[0], generation, detail)));
            owner[0] = created;
            recorder = created;
            recorder.startBuffering(config);
            if (!recorder.isBuffering() || recorder.inputSurface() == null)
                throw new IllegalStateException("hardware video encoder did not start");
            Surface encoderSurface = recorder.retainInputSurface();
            composer = new MotionGpuComposer(mediaContext,
                    recorder.width(), recorder.height(), config.cameraOrder, id,
                    detail -> control.post(() -> {
                        if (!sameSession(id) || generation != recordingGeneration) return;
                        sendError(id, "video_composer", detail);
                        stopRecording("composer_failed");
                    }));
            // Retain cleanup ownership even when initialization times out on the GL thread.
            composer.initialize(encoderSurface);
            recordingRequestId = recordingRequestId == Integer.MAX_VALUE ? 1 : recordingRequestId + 1;
            deliberatelyDetachedProducerReason = "";
            boolean accepted;
            try {
                accepted = cameraOperation(MotionDetectorProtocol.CAMERA_RECORD_ATTACH, id, null,
                        composer.inputs(), recordingRequestId);
            } catch (Throwable uncertain) {
                recordingAttached = true;
                throw uncertain;
            }
            if (!accepted)
                throw new IllegalStateException("camera owner refused Motion encoder surfaces");
            recordingAttached = true;
            sendStatus(id, "recording_buffering", "hardware encoder and four camera inputs are shell-owned");
        } catch (Throwable error) {
            sendError(id, "recording_start", summary(error));
            stopRecording("start_failed");
        }
    }

    private void onRecorderEvent(String id, MotionVideoRecorder owner, int generation, String detail) {
        if (owner == null || recorder != owner) return;
        if (detail != null && detail.startsWith("input_release_required:")) {
            if (teardownPending) continuePendingTeardown();
            else stopRecording(detail.substring("input_release_required:".length()));
            return;
        }
        if (detail != null && detail.startsWith("stopped:")) {
            if (owner.isBuffering()) return;
            if (recordingAttached || composer != null) {
                logEvent("motion_teardown_pending", id, "resource", "recorder",
                        "reason", "video worker ended before camera detach was confirmed");
                return;
            }
            try { owner.close(); }
            catch (Throwable error) { sendError(id, "recorder_close", summary(error)); return; }
            recorder = null;
            recordingStopRequested = false;
            if (teardownPending) continuePendingTeardown();
            else if (sameSession(id) && eligible && config != null && config.recordingEnabled)
                startRecording(id);
            return;
        }
        if (!sameSession(id) || generation != recordingGeneration) return;
        if (detail != null && (detail.startsWith("video_encoder_unavailable:")
                || detail.startsWith("video_encoder_failed:"))) {
            sendError(id, "video_encoder", detail);
            stopRecording("encoder_failed");
        }
    }

    private boolean stopRecording(String reason) { return stopRecording(reason, false); }

    private void finishRecordingTail() {
        if (teardownPending) {
            continuePendingTeardown();
            return;
        }
        String id = sessionId;
        boolean stopped = stopRecording("postroll_finished", false);
        if (id == null || !sameSession(id)) return;
        if (stopped) {
            state = "prepared";
            sendStatus(id, state, "recording post-roll completed and encoder detached");
        } else {
            state = "recording_teardown_pending";
            sendStatus(id, state, "camera host has not confirmed encoder drain; consumer resources retained");
        }
    }

    private boolean stopRecording(String reason, boolean surfacesDrained) {
        control.removeCallbacks(recordingTailFinished);
        recordingGeneration++;
        String id = sessionId;
        if (recordingAttached && id != null && !surfacesDrained) {
            deliberatelyDetachedProducerReason = "motion_recording_stop";
            try {
                if (!cameraOperation(MotionDetectorProtocol.CAMERA_RECORD_DETACH, id, null, null, 0))
                    throw new IllegalStateException("camera owner did not confirm Motion encoder detach");
                recordingAttached = false;
            } catch (Throwable error) {
                if (sameSession(id)) sendError(id, "recording_detach", summary(error));
                logEvent("motion_teardown_pending", id, "resource", "encoder_surface",
                        "reason", summary(error));
                return false;
            }
        } else if (surfacesDrained) recordingAttached = false;

        MotionGpuComposer oldComposer = composer;
        if (oldComposer != null) {
            try {
                oldComposer.close(() -> control.post(() -> {
                    if (composer != oldComposer || !sameSession(id)) return;
                    if (teardownPending) continuePendingTeardown();
                    else stopRecording(reason);
                }));
                composer = null;
            } catch (Throwable error) {
                if (sameSession(id)) sendError(id, "composer_close", summary(error));
                logEvent("motion_teardown_pending", id, "resource", "composer",
                        "reason", summary(error));
                return false;
            }
        }

        MotionVideoRecorder oldRecorder = recorder;
        if (oldRecorder != null) {
            try {
                // A self-ended encoder must keep its Surface until camera and GL owners release it.
                oldRecorder.releaseInputSurface();
                if (!recordingStopRequested) {
                    recordingStopRequested = true;
                    oldRecorder.stop(reason);
                }
                if (oldRecorder.isBuffering()) {
                    if (sameSession(id)) sendStatus(id, "recording_teardown_pending",
                            "encoder drain is still active; shared media provider retained");
                    logEvent("motion_teardown_pending", id, "resource", "recorder",
                            "reason", reason, "buffering", true);
                    return false;
                }
                oldRecorder.close();
                recorder = null;
                recordingStopRequested = false;
            } catch (Throwable error) {
                if (sameSession(id)) sendError(id, "recorder_close", summary(error));
                logEvent("motion_teardown_pending", id, "resource", "recorder",
                        "reason", summary(error));
                return false;
            }
        }
        if (!eligible && !warning) holdCpu(false);
        return !recordingAttached && composer == null && recorder == null;
    }

    private void shutdownFromHost(String id, String reason, boolean surfacesDrained) {
        if (!sameSession(id)) return;
        pendingConfig = null;
        shuttingDown = true;
        sessionRuntime.suspendForHostShutdown();
        control.removeCallbacks(armCheck);
        if (teardownPending) {
            teardownHostShutdown = true;
            teardownSurfacesDrained |= surfacesDrained;
            teardownHostUnbound |= surfacesDrained;
            continuePendingTeardown();
            return;
        }
        beginTeardown(id, "host_shutdown_" + reason, false, true, surfacesDrained, null, "suspended");
    }

    private int stopOnControl(String id, String reason) {
        if (!sameSession(id)) return 0;
        beginTeardown(id, reason, true, false, false, appCallback, "idle");
        return 1;
    }

    private void beginTeardown(String id, String reason, boolean notifyHost,
            boolean hostShutdown, boolean surfacesDrained, IBinder callback, String finalState) {
        if (teardownPending && sameSession(id)) return;
        teardownPending = true;
        teardownNotifyHost = notifyHost;
        teardownHostShutdown = hostShutdown;
        teardownSurfacesDrained = surfacesDrained;
        teardownHostUnbound = hostShutdown && surfacesDrained;
        teardownUnbindAttempted = false;
        teardownDemandCleared = false;
        teardownReason = reason;
        teardownCallback = callback;
        teardownFinalState = finalState;
        state = "stopping";
        continuePendingTeardown();
    }

    private void continuePendingTeardown() {
        String id = sessionId;
        if (!teardownPending || id == null) return;
        control.removeCallbacks(armCheck);
        control.removeCallbacks(firstInferenceTimeout);
        control.removeCallbacks(warningWindowTimeout);
        control.removeCallbacks(warningTimeout);
        control.removeCallbacks(recordingTailFinished);
        eligible = false;
        policy.stop(SystemClock.elapsedRealtime());
        screen.setEligible(id, false);
        if (warning) finishWarning(id, false);
        deliberatelyDetachedProducerReason = "motion_detector_idle";
        boolean recorderStopped = stopRecording(teardownReason, teardownSurfacesDrained);
        boolean readerDetached = detachAndCloseReader(teardownSurfacesDrained);

        if (teardownNotifyHost && workerBound && !teardownDemandCleared) {
            teardownDemandCleared = true;
            try { setMotionTelemetryDemand(id, false); }
            catch (Throwable error) { sendError(id, "telemetry_unsubscribe", summary(error)); }
            try { cameraOperation(MotionDetectorProtocol.CAMERA_END_WAKE, id, null, null, 0); }
            catch (Throwable ignored) { }
        }

        boolean surfacesNeedHostDrain = !recorderStopped && recordingAttached || !readerDetached && attached;
        if (workerBound && !teardownHostUnbound && !teardownUnbindAttempted
                && (teardownNotifyHost || surfacesNeedHostDrain)) {
            teardownUnbindAttempted = true;
            try {
                if (!cameraOperation(MotionDetectorProtocol.CAMERA_UNBIND_WORKER, id, null, null, 0))
                    throw new IllegalStateException("camera runtime did not confirm Motion worker unbind");
                teardownHostUnbound = true;
                teardownSurfacesDrained = true;
                recorderStopped = stopRecording(teardownReason, true);
                readerDetached = detachAndCloseReader(true);
            } catch (Throwable error) {
                sendError(id, "worker_unbind", summary(error));
                logEvent("motion_teardown_pending", id, "resource", "camera_host",
                        "reason", summary(error));
            }
        }

        boolean resourcesSafe = recorderStopped && readerDetached
                && (!recordingAttached && !attached);
        boolean hostReleased = !workerBound || !teardownNotifyHost || teardownHostUnbound
                || teardownHostShutdown && teardownSurfacesDrained;
        if (!resourcesSafe || !hostReleased) {
            state = "teardown_pending";
            if (!lastError.isEmpty()) sendStatus(id, state, lastError);
            else sendStatus(id, state, "Motion surfaces or encoder drain are awaiting a confirmed host release");
            return;
        }

        deleteNative();
        holdCpu(false);
        warning = false;
        warningWindowPending = false;
        warningWindowDrawn = false;
        prepared = false;
        eligible = false;
        IBinder callback = teardownCallback;
        if (callback != null) detachUiOnControl(id, callback);
        String finishedState = teardownFinalState;
        String finishedReason = teardownReason;
        if (teardownHostShutdown && teardownSurfacesDrained) workerBound = false;
        teardownPending = false;
        teardownCallback = null;
        sessionId = null;
        state = finishedState;
        if ("suspended".equals(finishedState))
            sendStatus(callback, id, "suspended", "explicit host shutdown; saved Motion config retained until authenticated configure");
        else if ("error".equals(finishedState))
            sendStatus(callback, id, "error", lastError);
        else sendStatus(callback, id, "stopped", finishedReason);

        MotionSessionConfigStore.Snapshot next = pendingConfig;
        pendingConfig = null;
        if (next != null) {
            try { startConfiguredSession(next); }
            catch (Throwable error) {
                state = "error";
                lastError = "pending_config: " + summary(error);
                System.err.println("motion_pending_config_failed " + lastError);
                System.err.flush();
            }
        }
    }

    private boolean detachAndCloseReader(boolean surfacesDrained) {
        if (attached && !surfacesDrained) {
            deliberatelyDetachedProducerReason = "motion_detector_idle";
            try {
                if (!cameraOperation(MotionDetectorProtocol.CAMERA_DETACH, sessionId, null))
                    throw new IllegalStateException("existing camera owner did not confirm Surface detach");
                attached = false;
            } catch (Throwable error) {
                String id = sessionId;
                if (id != null) sendError(id, "camera_detach", summary(error));
                return false;
            }
        } else if (surfacesDrained) attached = false;
        if (attached) return false;

        ImageReader oldReader = reader;
        Surface oldSurface = surface;
        reader = null;
        surface = null;
        try { if (oldReader != null) oldReader.close(); }
        catch (Throwable error) {
            String id = sessionId;
            if (id != null) sendError(id, "camera_reader_close", summary(error));
        }
        try { if (oldSurface != null) oldSurface.release(); }
        catch (Throwable error) {
            String id = sessionId;
            if (id != null) sendError(id, "camera_surface_release", summary(error));
        }
        frameDrainQueued.set(false);
        return true;
    }

    private void fail(String id, String stage, Throwable error) {
        if (!sameSession(id)) return;
        lastError = stage + ": " + summary(error);
        sendError(id, stage, summary(error));
        beginTeardown(id, "runtime_error", true, false, false, appCallback, "error");
    }

    private void deleteNative() {
        if (nativeInitialized) {
            nativeInitialized = false;
            try { nativeApi.delete(); }
            catch (Throwable error) { lastError += "; native_delete: " + summary(error); }
        }
        nativeApi = null;
        nativeInput = null;
        if (assets != null) {
            try { assets.close(); }
            catch (Throwable error) { lastError += "; asset_close: " + summary(error); }
            assets = null;
        }
    }

    private <T> T callControl(ControlCall<T> call) throws Exception {
        FutureTask<T> task = new FutureTask<>(call::run);
        if (Looper.myLooper() == control.getLooper()) return call.run();
        if (!control.post(task)) throw new IllegalStateException("motion control thread unavailable");
        try { return task.get(CONTROL_TIMEOUT_MS, TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.TimeoutException timeout) {
            task.cancel(false);
            throw new IllegalStateException("motion control result uncertain after timeout", timeout);
        }
    }

    private synchronized void ensureMediaBridge() throws Exception {
        if (mediaBridge != null) return;
        MotionShellMediaContext nextContext = new MotionShellMediaContext(systemContext, appUid,
                MotionShellMediaContext.OWNER_PACKAGE, new File(MotionDetectorProtocol.STATE_ROOT));
        try {
            MotionMediaLibrary nextLibrary = new MotionMediaLibrary(nextContext);
            MotionMediaIndexBridge nextBridge = new MotionMediaIndexBridge(nextLibrary);
            mediaContext = nextContext;
            mediaLibrary = nextLibrary;
            mediaBridge = nextBridge;
        } catch (Throwable failure) {
            nextContext.close();
            if (failure instanceof Exception) throw (Exception) failure;
            throw new RuntimeException(failure);
        }
    }

    private static MotionMediaLibrary.Query readMediaQuery(Parcel data) {
        boolean hasStart = readFlag(data);
        long start = hasStart ? data.readLong() : 0L;
        boolean hasEnd = readFlag(data);
        long end = hasEnd ? data.readLong() : 0L;
        String volumeId = data.readString();
        int sort = data.readInt();
        if ((hasStart && start < 0) || (hasEnd && end < 0)
                || hasStart && hasEnd && start > end
                || volumeId != null && !volumeId.isEmpty() && !validVolumeId(volumeId)
                || sort < 0 || sort > 1)
            throw new IllegalArgumentException("invalid Motion media query fields");
        return new MotionMediaLibrary.Query(hasStart ? start : null, hasEnd ? end : null,
                volumeId == null || volumeId.isEmpty() ? null : volumeId,
                sort == 0 ? MotionMediaLibrary.Sort.NEWEST_FIRST : MotionMediaLibrary.Sort.OLDEST_FIRST);
    }

    private static boolean validVolumeId(String volumeId) {
        return volumeId != null && volumeId.length() <= 128
                && volumeId.matches("[A-Za-z0-9_-]{1,128}");
    }

    private void requireSession(String id) {
        if (!sameSession(id) || shuttingDown || sessionRuntime.suspended())
            throw new IllegalStateException("motion session expired");
    }

    private boolean sameSession(String id) { return id != null && id.equals(sessionId); }

    private void sendPrepared(String id) {
        logEvent("motion_worker_prepared", id, "process_id", Process.myPid());
        IBinder target = appCallback;
        if (target == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.CALLBACK_DESCRIPTOR);
            data.writeString(id);
            data.writeStrongBinder(binder);
            data.writeInt(MotionDetectorProtocol.VERSION);
            data.writeInt(BuildConfig.VERSION_CODE);
            data.writeInt(Process.myPid());
            target.transact(MotionDetectorProtocol.CB_PREPARED, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException ignored) { detachUiOnControl(id, target); }
        finally { data.recycle(); }
    }

    private void sendStatus(String id, String next, String detail) {
        sendStatus(appCallback, id, next, detail);
    }

    private void sendStatus(IBinder target, String id, String next, String detail) {
        logEvent("motion_status", id, "state", next, "detail", detail);
        if (target == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.CALLBACK_DESCRIPTOR);
            data.writeString(id);
            data.writeString(next);
            data.writeString(detail);
            data.writeLong(SystemClock.elapsedRealtime());
            target.transact(MotionDetectorProtocol.CB_STATUS, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException ignored) {
            detachUiOnControl(id, target);
        } finally { data.recycle(); }
    }

    private void sendDetection(String id, int level, long elapsedMs, int raw, int nativeState,
            long frame, long frameTimeNs, long warningUntil) {
        logEvent("motion_detection", id, "level", level, "elapsed_ms", elapsedMs,
                "raw_result", raw, "native_state", nativeState, "frame", frame,
                "frame_timestamp_ns", frameTimeNs, "warning_until_ms", warningUntil);
        IBinder target = appCallback;
        if (target == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.CALLBACK_DESCRIPTOR);
            data.writeString(id);
            data.writeInt(level);
            data.writeLong(elapsedMs);
            data.writeInt(raw);
            data.writeInt(nativeState);
            data.writeLong(frame);
            data.writeLong(frameTimeNs);
            data.writeLong(warningUntil);
            target.transact(MotionDetectorProtocol.CB_DETECTION, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException ignored) {
            detachUiOnControl(id, target);
        } finally { data.recycle(); }
    }

    private void sendError(String id, String stage, String detail) {
        logEvent("motion_error", id, "stage", stage, "detail", detail);
        IBinder target = appCallback;
        if (target == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.CALLBACK_DESCRIPTOR);
            data.writeString(id);
            data.writeString(stage);
            data.writeString(detail);
            data.writeLong(SystemClock.elapsedRealtime());
            target.transact(MotionDetectorProtocol.CB_ERROR, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException ignored) {
            detachUiOnControl(id, target);
        } finally { data.recycle(); }
    }

    private void sendScreen(String id, String screenState, String detail) {
        logEvent("motion_screen", id, "state", screenState, "detail", detail);
        IBinder target = appCallback;
        if (target == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.CALLBACK_DESCRIPTOR);
            data.writeString(id);
            data.writeString(screenState);
            data.writeString(detail);
            data.writeLong(SystemClock.elapsedRealtime());
            target.transact(MotionDetectorProtocol.CB_SCREEN, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException ignored) {
            detachUiOnControl(id, target);
        } finally { data.recycle(); }
    }

    private void logEvent(String kind, String id, Object... fields) {
        try {
            org.json.JSONObject event = new org.json.JSONObject().put("kind", kind)
                    .put("session_id", id == null ? "" : id)
                    .put("process_id", Process.myPid())
                    .put("elapsed_ms", SystemClock.elapsedRealtime())
                    .put("wall_ms", System.currentTimeMillis());
            for (int i = 0; i + 1 < fields.length; i += 2)
                event.put(String.valueOf(fields[i]), fields[i + 1]);
            System.out.println(event.toString());
            System.out.flush();
        } catch (Throwable error) {
            System.err.println("motion_log_failure kind=" + kind + " error=" + summary(error));
        }
    }

    private static boolean readFlag(Parcel data) {
        int value = data.readInt();
        if (value != 0 && value != 1) throw new IllegalArgumentException("flag must be 0 or 1");
        return value == 1;
    }

    private static boolean validReason(String reason) {
        return reason != null && reason.length() <= 64
                && reason.matches("[A-Za-z0-9_.-]*");
    }

    private static IBinder serviceBinder(String name) throws Exception {
        return (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, name);
    }

    private static String summary(Throwable error) {
        Throwable cause = error;
        while (cause instanceof java.lang.reflect.InvocationTargetException && cause.getCause() != null)
            cause = cause.getCause();
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    private interface ControlCall<T> { T run() throws Exception; }

    private static final class MotionNativeSetup {
        final Context context;
        ApplicationInfo info;
        AssetManager assets;
        int modelValue;
        int mirrorSetting;
        int sensitivity;

        MotionNativeSetup(Context context) { this.context = context; }

        void prepare() throws Exception {
            verifyFirmware();
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(
                    MotionDetectorProtocol.OEM_PACKAGE, 0);
            long version = packageInfo.getLongVersionCode();
            if (version != 10_503_004L) throw new MotionFirmwareProfile.Mismatch(
                    "SentryMode.versionCode", "10503004", Long.toString(version));
            info = context.getPackageManager().getApplicationInfo(MotionDetectorProtocol.OEM_PACKAGE, 0);
            if (!info.sourceDir.startsWith("/system/") || !info.nativeLibraryDir.startsWith("/system/"))
                throw new MotionFirmwareProfile.Mismatch("SentryMode.paths", "/system/*",
                        "sourceDir=" + info.sourceDir + "; nativeLibraryDir=" + info.nativeLibraryDir);
            requireHash(new File(info.sourceDir), MotionDetectorProtocol.OEM_APK_SHA256);
            File nativeLibrary = new File(info.nativeLibraryDir, "libnative-lib-motion.so");
            requireHash(nativeLibrary, MotionDetectorProtocol.OEM_LIBRARY_SHA256);

            modelValue = parseModel(runCommand("/system/bin/content", "query", "--user", "0",
                    "--uri", "content://carsettings/config", "--projection", "value",
                    "--where", "key='model_value'"));
            if (modelValue != MotionDetectorProtocol.MODEL_VALUE)
                throw new MotionFirmwareProfile.Mismatch("model_value",
                        Integer.toString(MotionDetectorProtocol.MODEL_VALUE), Integer.toString(modelValue));
            mirrorSetting = parseSetting(runCommand("/system/bin/settings", "--user", "0",
                    "get", "global", "sentrymode_rearview_status"), 0, 1);
            int sensitivitySetting = parseSetting(runCommand("/system/bin/settings", "--user", "0",
                    "get", "global", "sentrymode_sensitivity_level"), 2, 2);
            sensitivity = 3 - sensitivitySetting;

            assets = AssetManager.class.getDeclaredConstructor().newInstance();
            int assetPath = (Integer) AssetManager.class.getMethod("addAssetPath", String.class)
                    .invoke(assets, info.sourceDir);
            if (assetPath == 0) throw new IOException("stock model APK asset path unavailable");
            requireAssetHash(assets, MotionDetectorProtocol.OEM_MODEL_ASSET,
                    MotionDetectorProtocol.OEM_MODEL_BYTES, MotionDetectorProtocol.OEM_MODEL_SHA256);

            System.load(new File(info.nativeLibraryDir, "libc++_shared.so").getPath());
            System.load(new File(info.nativeLibraryDir, "libSNPE.so").getPath());
            System.load(nativeLibrary.getPath());
        }

        private static void verifyFirmware() throws Exception {
            MotionFirmwareProfile.verify(Build.VERSION.SDK_INT, Build.VERSION.RELEASE,
                    Build.DISPLAY, Build.VERSION.INCREMENTAL);
            String configured = property("vehicle.config.camInfo.avm");
            String active = property("persist.vendor.camera.autostudy.avm");
            if (!MotionDetectorProtocol.IDC_CONFIG.equals(configured) || !configured.equals(active))
                throw new IOException("IDC camera configuration mismatch: expected=["
                        + MotionDetectorProtocol.IDC_CONFIG + "] vehicle.config.camInfo.avm=["
                        + configured + "] persist.vendor.camera.autostudy.avm=[" + active + "]");
        }

        private static String property(String key) throws Exception {
            return (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class).invoke(null, key);
        }

        private static String runCommand(String... command) throws Exception {
            java.lang.Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS))
                    throw new IOException("configuration read timed out: " + command[0]);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (InputStream input = process.getInputStream()) {
                    byte[] buffer = new byte[1024];
                    for (int n; (n = input.read(buffer)) != -1;) {
                        if (bytes.size() + n > 8192) throw new IOException("configuration response too large");
                        bytes.write(buffer, 0, n);
                    }
                }
                if (process.exitValue() != 0)
                    throw new IOException("configuration command failed: " + command[0]);
                return bytes.toString("UTF-8").trim();
            } finally {
                if (process.isAlive()) process.destroyForcibly();
                process.getInputStream().close();
                process.getOutputStream().close();
                process.getErrorStream().close();
            }
        }

        private static int parseModel(String text) throws IOException {
            Matcher row = Pattern.compile("Row: 0 value=([0-9]+)").matcher(text.trim());
            if (!row.matches()) throw new IOException("model_value missing or ambiguous");
            return parseBounded(row.group(1), 1, 65_535);
        }

        private static int parseSetting(String text, int absentDefault, int max) throws IOException {
            String value = text.trim();
            return "null".equals(value) ? absentDefault : parseBounded(value, 0, max);
        }

        private static int parseBounded(String value, int min, int max) throws IOException {
            if (!value.matches("[0-9]+")) throw new IOException("invalid OEM setting value");
            int result;
            try { result = Integer.parseInt(value); }
            catch (NumberFormatException error) { throw new IOException("invalid OEM setting value", error); }
            if (result < min || result > max) throw new IOException("OEM setting out of range");
            return result;
        }

        private static void requireAssetHash(AssetManager manager, String path, long expectedBytes,
                String expectedHash) throws Exception {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long bytes = 0L;
            try (InputStream input = manager.open(path)) {
                byte[] buffer = new byte[65_536];
                for (int n; (n = input.read(buffer)) != -1;) {
                    bytes += n;
                    digest.update(buffer, 0, n);
                }
            }
            String actualHash = hex(digest.digest());
            if (bytes != expectedBytes || !expectedHash.equals(actualHash))
                throw new MotionFirmwareProfile.Mismatch("asset:" + path,
                        "bytes=" + expectedBytes + "; sha256=" + expectedHash,
                        "bytes=" + bytes + "; sha256=" + actualHash);
        }

        private static void requireHash(File file, String expected) throws Exception {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[65_536];
                for (int n; (n = input.read(buffer)) != -1;) digest.update(buffer, 0, n);
            }
            String actual = hex(digest.digest());
            if (!expected.equals(actual))
                throw new MotionFirmwareProfile.Mismatch("sha256:" + file.getPath(), expected, actual);
        }

        private static String hex(byte[] bytes) {
            StringBuilder value = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02X", b & 0xff));
            return value.toString();
        }
    }
}
