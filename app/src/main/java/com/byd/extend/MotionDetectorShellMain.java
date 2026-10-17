package com.byd.extend;

import android.content.Context;
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
import java.security.MessageDigest;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shell-UID worker. It consumes a Surface from the existing camera owner; it never opens AVMCamera. */
public final class MotionDetectorShellMain {
    private static final long CONTROL_TIMEOUT_MS = 5_000L;
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
    private final MotionEventPolicy policy = new MotionEventPolicy();
    private final AtomicBoolean frameDrainQueued = new AtomicBoolean();
    private final ShellBinder binder = new ShellBinder();
    private final MotionScreenControl screen;

    private volatile String sessionId;
    private volatile IBinder appCallback;
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

    private MotionDetectorShellMain(int appUid, String extendIdentity, Context systemContext) {
        this.appUid = appUid;
        this.extendIdentity = extendIdentity;
        this.systemContext = systemContext;
        controlThread.start();
        control = new Handler(controlThread.getLooper());
        screen = new MotionScreenControl((screenState, detail) -> {
            String active = sessionId;
            if (active != null) sendScreen(active, screenState, detail);
        });
    }

    private void publish() throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        serviceManager.getMethod("addService", String.class, IBinder.class)
                .invoke(null, MotionDetectorProtocol.SERVICE_NAME, binder);
        System.out.println("motion_worker_ready uid=" + Process.myUid() + " pid=" + Process.myPid()
                + " protocol=" + MotionDetectorProtocol.VERSION);
        System.out.flush();
    }

    private final class ShellBinder extends Binder {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(MotionDetectorProtocol.DESCRIPTOR);
                return true;
            }
            if (Binder.getCallingUid() != appUid) {
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
                    case MotionDetectorProtocol.TX_START: {
                        int version = data.readInt();
                        int build = data.readInt();
                        String id = data.readString();
                        IBinder callback = data.readStrongBinder();
                        if (version != MotionDetectorProtocol.VERSION || build != BuildConfig.VERSION_CODE
                                || !MotionDetectorProtocol.validSession(id) || callback == null
                                || data.dataAvail() != 0)
                            throw new IllegalArgumentException("invalid motion start request");
                        int accepted = callControl(() -> startOnControl(id, callback));
                        reply.writeNoException();
                        reply.writeInt(accepted);
                        reply.writeStrongBinder(binder);
                        return true;
                    }
                    case MotionDetectorProtocol.TX_SET_ELIGIBLE: {
                        String id = data.readString();
                        boolean next = readFlag(data);
                        if (data.dataAvail() != 0) throw new IllegalArgumentException("eligibility payload trailing data");
                        int accepted = callControl(() -> setEligibleOnControl(id, next));
                        reply.writeNoException();
                        reply.writeInt(accepted);
                        return true;
                    }
                    case MotionDetectorProtocol.TX_STOP: {
                        String id = data.readString();
                        String reason = data.readString();
                        if (!validReason(reason) || data.dataAvail() != 0)
                            throw new IllegalArgumentException("invalid stop request");
                        int accepted = callControl(() -> stopOnControl(id, reason));
                        reply.writeNoException();
                        reply.writeInt(accepted);
                        return true;
                    }
                    case MotionDetectorProtocol.TX_STATUS:
                        if (data.dataAvail() != 0) throw new IllegalArgumentException("status payload not empty");
                        reply.writeNoException();
                        reply.writeString(state);
                        reply.writeInt(prepared ? 1 : 0);
                        reply.writeInt(eligible ? 1 : 0);
                        reply.writeString(lastError);
                        reply.writeLong(SystemClock.elapsedRealtime());
                        return true;
                    case MotionDetectorProtocol.TX_WAKE_FOR_WARNING:
                    case MotionDetectorProtocol.TX_WARNING_WINDOW_READY:
                    case MotionDetectorProtocol.TX_FINISH_WARNING:
                    case MotionDetectorProtocol.TX_USER_TAKEOVER: {
                        String id = data.readString();
                        boolean restore = code == MotionDetectorProtocol.TX_FINISH_WARNING
                                && readFlag(data);
                        if (data.dataAvail() != 0) throw new IllegalArgumentException("screen payload trailing data");
                        if (code == MotionDetectorProtocol.TX_WAKE_FOR_WARNING) {
                            callControl(() -> {
                                requireSession(id);
                                if (!eligible || !policy.warningActive(SystemClock.elapsedRealtime()))
                                    throw new IllegalStateException("no eligible active detector warning");
                                screen.wakeForWarning(id);
                                return 1;
                            });
                        }
                        else if (!sameSession(id)) throw new IllegalStateException("motion session expired");
                        else if (code == MotionDetectorProtocol.TX_WARNING_WINDOW_READY)
                            screen.warningWindowReady(id);
                        else if (code == MotionDetectorProtocol.TX_FINISH_WARNING)
                            screen.finishWarning(id, restore);
                        else screen.markUserTakeover(id);
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

    private int startOnControl(String id, IBinder callback) throws Exception {
        if (sessionId != null) {
            if (sessionId.equals(id) && appCallback == callback && prepared) return 1;
            throw new IllegalStateException("another motion session is active");
        }
        if (!callback.isBinderAlive()) throw new IllegalStateException("motion callback is dead");
        sessionId = id;
        appCallback = callback;
        callback.linkToDeath(() -> control.post(() -> stopOnControl(id, "app_callback_died")), 0);
        state = "preparing";
        prepared = false;
        eligible = false;
        attached = false;
        lastError = "";
        frameNumber = 0L;
        screen.beginSession(id);
        sendStatus(id, "preparing", "inspecting installed OEM detector artifacts");
        control.post(() -> prepareSession(id));
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
            sendStatus(id, "prepared", "OEM artifacts and native init accepted; camera surface not attached");
        } catch (Throwable error) {
            if (assets == null && setup.assets != null) {
                try { setup.assets.close(); } catch (Throwable ignored) { }
            }
            fail(id, "prepare", error);
        }
    }

    private int setEligibleOnControl(String id, boolean next) throws Exception {
        requireSession(id);
        if (!prepared) throw new IllegalStateException("detector is not prepared");
        if (eligible == next) return 1;
        if (!next) {
            policy.setEligible(false, SystemClock.elapsedRealtime());
            eligible = false;
            screen.setEligible(id, false);
            detachAndCloseReader();
            state = "prepared";
            sendStatus(id, "prepared", "camera eligibility disabled");
            return 1;
        }
        attachReader(id);
        eligible = true;
        // The app owns the OFF/lock eligibility window and calls us only after its 2500ms gate.
        // Use that same boundary rather than adding a second 2500ms delay after Surface attach.
        policy.setEligible(true, SystemClock.elapsedRealtime() - MotionDetectorProtocol.WARMUP_MS);
        screen.setEligible(id, true);
        state = "observing";
        lastReportedDetectorState = "observing";
        sendStatus(id, "observing", "camera Surface attached after the app eligibility gate");
        return 1;
    }

    private void attachReader(String id) throws Exception {
        if (attached || reader != null) throw new IllegalStateException("motion Surface already attached");
        reader = ImageReader.newInstance(MotionDetectorProtocol.INPUT_WIDTH,
                MotionDetectorProtocol.INPUT_HEIGHT, ImageFormat.YUV_420_888, 2);
        surface = reader.getSurface();
        reader.setOnImageAvailableListener(source -> {
            if (frameDrainQueued.compareAndSet(false, true)) control.post(this::drainLatestFrame);
        }, control);
        if (!cameraOperation(MotionDetectorProtocol.CAMERA_ATTACH, id, surface)) {
            detachAndCloseReader();
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
            policy.observe(raw, now);
            fail(sessionId, "native_detect", new IllegalStateException(
                    "native detect returned -2; getState was not called"));
            return;
        }
        int nativeState = nativeApi.getState();
        MotionEventPolicy.Decision decision = policy.observe(raw, now);
        state = policy.warningActive(now) ? "warning_active" : "observing";
        if (!state.equals(lastReportedDetectorState)) {
            lastReportedDetectorState = state;
            sendStatus(sessionId, state, "detector policy state changed");
        }
        if (decision.level != MotionEventPolicy.LEVEL_NONE)
            sendDetection(sessionId, decision.level, decision.elapsedMs, raw,
                    nativeState, frameNumber, frameTimeNs, decision.warningUntilMs);
    }

    private static MotionYuvPacker.Plane plane(Image.Plane plane) {
        ByteBuffer view = plane.getBuffer();
        return new MotionYuvPacker.Plane(view, plane.getRowStride(), plane.getPixelStride());
    }

    private boolean cameraOperation(int operation, String id, Surface value) throws Exception {
        if (Process.myUid() != 2000) throw new SecurityException("camera Surface routing requires shell UID");
        IBinder cameraHost = serviceBinder(TurnSignalShellProtocol.SERVICE_NAME);
        if (cameraHost == null) throw new IllegalStateException("existing camera owner service is unavailable");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TurnSignalShellProtocol.DESCRIPTOR);
            data.writeInt(operation);
            data.writeString(id);
            data.writeStrongBinder(binder);
            if (operation == MotionDetectorProtocol.CAMERA_ATTACH) data.writeTypedObject(value, 0);
            if (!cameraHost.transact(TurnSignalShellProtocol.TX_CAMERA_RUNTIME, data, reply, 0))
                throw new IllegalStateException("camera runtime did not accept auxiliary operation " + operation);
            reply.readException();
            return reply.readInt() == 1 && reply.dataAvail() == 0;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private int stopOnControl(String id, String reason) {
        if (!sameSession(id)) return 0;
        state = "stopping";
        eligible = false;
        policy.stop(SystemClock.elapsedRealtime());
        screen.setEligible(id, false);
        try { detachAndCloseReader(); }
        catch (Throwable error) { lastError = "detach: " + summary(error); }
        deleteNative();
        IBinder callback = appCallback;
        appCallback = null;
        sessionId = null;
        prepared = false;
        state = "idle";
        sendStatus(callback, id, "stopped", reason);
        return 1;
    }

    private void detachAndCloseReader() throws Exception {
        Exception detachFailure = null;
        if (attached) {
            attached = false; // Never retry an uncertain one-shot Binder detach.
            try {
                if (!cameraOperation(MotionDetectorProtocol.CAMERA_DETACH, sessionId, null))
                    throw new IllegalStateException("existing camera owner did not confirm Surface detach");
            } catch (Exception error) { detachFailure = error; }
        }
        ImageReader oldReader = reader;
        Surface oldSurface = surface;
        reader = null;
        surface = null;
        try {
            if (oldReader != null) oldReader.close();
        } catch (Exception closeError) {
            if (detachFailure == null) detachFailure = closeError;
        }
        try {
            if (oldSurface != null) oldSurface.release();
        } catch (Exception closeError) {
            if (detachFailure == null) detachFailure = closeError;
        }
        frameDrainQueued.set(false);
        if (detachFailure != null) throw detachFailure;
    }

    private void fail(String id, String stage, Throwable error) {
        if (!sameSession(id)) return;
        lastError = stage + ": " + summary(error);
        state = "error";
        eligible = false;
        policy.stop(SystemClock.elapsedRealtime());
        screen.setEligible(id, false);
        sendError(id, stage, summary(error));
        try { detachAndCloseReader(); }
        catch (Throwable detachError) {
            lastError += "; detach: " + summary(detachError);
            sendError(id, "camera_detach", summary(detachError));
        }
        deleteNative();
        prepared = false;
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

    private int callControl(ControlCall<Integer> call) throws Exception {
        FutureTask<Integer> task = new FutureTask<>(call::run);
        if (Looper.myLooper() == control.getLooper()) return call.run();
        if (!control.post(task)) throw new IllegalStateException("motion control thread unavailable");
        try { return task.get(CONTROL_TIMEOUT_MS, TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.TimeoutException timeout) {
            task.cancel(false);
            throw new IllegalStateException("motion control result uncertain after timeout", timeout);
        }
    }

    private void requireSession(String id) {
        if (!sameSession(id) || appCallback == null || !appCallback.isBinderAlive())
            throw new IllegalStateException("motion session expired");
    }

    private boolean sameSession(String id) { return id != null && id.equals(sessionId); }

    private void sendPrepared(String id) {
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
            if (!target.transact(MotionDetectorProtocol.CB_PREPARED, data, null, IBinder.FLAG_ONEWAY))
                stopOnControl(id, "callback_unavailable");
        } catch (RemoteException error) { stopOnControl(id, "callback_unavailable"); }
        finally { data.recycle(); }
    }

    private void sendStatus(String id, String next, String detail) {
        sendStatus(appCallback, id, next, detail);
    }

    private void sendStatus(IBinder target, String id, String next, String detail) {
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
            // Callback death is handled by its linked death recipient.
        } finally { data.recycle(); }
    }

    private void sendDetection(String id, int level, long elapsedMs, int raw, int nativeState,
            long frame, long frameTimeNs, long warningUntil) {
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
            // Callback death is handled by its linked death recipient.
        } finally { data.recycle(); }
    }

    private void sendError(String id, String stage, String detail) {
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
            // Callback death is handled by its linked death recipient.
        } finally { data.recycle(); }
    }

    private void sendScreen(String id, String screenState, String detail) {
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
            // Callback death is handled by its linked death recipient.
        } finally { data.recycle(); }
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
            if (version != 10_503_004L) throw new IOException("uninspected SentryMode version: " + version);
            info = context.getPackageManager().getApplicationInfo(MotionDetectorProtocol.OEM_PACKAGE, 0);
            if (!info.sourceDir.startsWith("/system/") || !info.nativeLibraryDir.startsWith("/system/"))
                throw new IOException("SentryMode artifacts are not from the inspected system image");
            requireHash(new File(info.sourceDir), MotionDetectorProtocol.OEM_APK_SHA256);
            File nativeLibrary = new File(info.nativeLibraryDir, "libnative-lib-motion.so");
            requireHash(nativeLibrary, MotionDetectorProtocol.OEM_LIBRARY_SHA256);

            modelValue = parseModel(runCommand("/system/bin/content", "query", "--user", "0",
                    "--uri", "content://carsettings/config", "--projection", "value",
                    "--where", "key='model_value'"));
            if (modelValue != MotionDetectorProtocol.MODEL_VALUE)
                throw new IOException("unsupported model_value=" + modelValue);
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
            if (Build.VERSION.SDK_INT != 32 || !"12".equals(Build.VERSION.RELEASE)
                    || !"Di5.0_USER_SIGN_SV162_202603202318_Q0311".equals(Build.DISPLAY)
                    || !"eng.build.20260320.231831".equals(Build.VERSION.INCREMENTAL))
                throw new IOException("unsupported firmware build/profile");
            String configured = property("vehicle.config.camInfo.avm");
            String active = property("persist.vendor.camera.autostudy.avm");
            if (!MotionDetectorProtocol.IDC_CONFIG.equals(configured) || !configured.equals(active))
                throw new IOException("unsupported or inconsistent IDC camera configuration");
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
            if (bytes != expectedBytes || !expectedHash.equals(hex(digest.digest())))
                throw new IOException("uninspected or truncated model asset");
        }

        private static void requireHash(File file, String expected) throws Exception {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[65_536];
                for (int n; (n = input.read(buffer)) != -1;) digest.update(buffer, 0, n);
            }
            if (!expected.equals(hex(digest.digest())))
                throw new IOException("uninspected firmware artifact: " + file.getName());
        }

        private static String hex(byte[] bytes) {
            StringBuilder value = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02X", b & 0xff));
            return value.toString();
        }
    }
}
