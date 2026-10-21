package com.byd.extend;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** App-facing lease for the shell worker's existing shared camera preparation owner. */
public final class RemoteCameraPreparation {
    private RemoteCameraPreparation() {}

    /** Returns whether the shared preparation is ready for the active, battery-safe viewing session. */
    public static boolean prepare(Context context, IBinder owner, String sessionId) throws Exception {
        return MotionDetectorClient.prepareRemoteView(context, owner, sessionId);
    }

    /** Releases this owner's preparation lease; closing the owner Binder also releases it. */
    public static void release(Context context, IBinder owner, String sessionId) throws Exception {
        MotionDetectorClient.releaseRemoteView(context, owner, sessionId);
    }

    /** App-UID registration binds only this APK-verified shell helper PID to fixed viewing RPCs. */
    public static void registerViewingHelper(Context context, IBinder helper, int helperPid)
            throws Exception {
        if (context == null || helper == null || helperPid <= 0 || Process.myUid() == 2000)
            throw new SecurityException("viewing helper registration requires the app process");
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        int ownerUid = app.getPackageManager().getApplicationInfo(
                CameraViewingShellProtocol.OWNER_PACKAGE, 0).uid;
        if (ownerUid != Process.myUid()) throw new SecurityException("Extend package UID changed");
        String apkIdentity = CameraViewingShellProtocol.currentApkIdentity(app);
        CameraViewingShellProtocol.Identity identity = CameraViewingShellProtocol.readIdentity(helper);
        if (identity == null || !identity.matches(helperPid, apkIdentity))
            throw new SecurityException("viewing helper APK/PID identity mismatch");

        MotionDetectorClient bootstrap = new MotionDetectorClient(app);
        try {
            IBinder worker = awaitCurrentMotionWorker(bootstrap);
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
                data.writeStrongBinder(helper);
                data.writeInt(helperPid);
                data.writeString(apkIdentity);
                if (!worker.transact(MotionDetectorProtocol.TX_REGISTER_VIEW_HELPER, data, reply, 0))
                    throw new IllegalStateException("Motion viewing registration unavailable");
                reply.readException();
                if (reply.readInt() != 1 || reply.dataAvail() != 0)
                    throw new IllegalStateException("Motion viewing registration refused");
            } finally { data.recycle(); reply.recycle(); }
        } finally { bootstrap.close(); }
    }

    private static IBinder awaitCurrentMotionWorker(MotionDetectorClient bootstrap) {
        long deadline = android.os.SystemClock.elapsedRealtime() + 8_000L;
        IllegalStateException lastObsolete = null;
        while (true) {
            try { return bootstrap.ensureCheckWorker(); }
            catch (IllegalStateException unavailable) {
                String message = unavailable.getMessage();
                if (message == null || !message.startsWith("obsolete Motion worker")) throw unavailable;
                lastObsolete = unavailable;
                if (android.os.SystemClock.elapsedRealtime() >= deadline) throw lastObsolete;
                android.os.SystemClock.sleep(100L);
            }
        }
    }

    /** Fresh owner read used before each viewer camera prepare; no app preference mirroring. */
    public static CameraChargePolicy.State readChargeState(Context context) throws Exception {
        IBinder worker = viewingWorker(context);
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
            if (!worker.transact(MotionDetectorProtocol.TX_READ_CHARGE, data, reply, 0))
                throw new IllegalStateException("shared camera charge read unavailable");
            reply.readException();
            CameraChargePolicy.State state = MotionDetectorProtocol.readChargeState(reply);
            if (reply.dataAvail() != 0) throw new IllegalStateException("invalid charge reply");
            return state;
        } finally { data.recycle(); reply.recycle(); }
    }

    /** Registers with the existing Motion charge owner; it adds no polling loop. */
    public static AutoCloseable observeCharge(Context context,
            Consumer<CameraChargePolicy.State> listener) throws Exception {
        if (listener == null) throw new IllegalArgumentException("charge listener required");
        String apkIdentity = CameraViewingShellProtocol.currentApkIdentity(context);
        IBinder worker = viewingWorker(context);
        int workerPid = MotionDetectorClient.verifiedWorkerPid(worker, apkIdentity);
        ChargeObserver callback = new ChargeObserver(worker, workerPid, listener);
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
            data.writeStrongBinder(callback);
            if (!worker.transact(MotionDetectorProtocol.TX_VIEW_OBSERVE_CHARGE, data, reply, 0))
                throw new IllegalStateException("shared camera charge observation unavailable");
            reply.readException();
            if (reply.readInt() != 1 || reply.dataAvail() != 0)
                throw new IllegalStateException("shared camera charge observation refused");
        } catch (Throwable failure) {
            callback.detach();
            if (failure instanceof Exception) throw (Exception) failure;
            throw new IllegalStateException("shared camera charge observation failed", failure);
        } finally { data.recycle(); reply.recycle(); }
        return () -> {
            if (!callback.detach()) return;
            if (!worker.isBinderAlive()) return;
            Parcel detach = Parcel.obtain(), result = Parcel.obtain();
            try {
                detach.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
                detach.writeStrongBinder(callback);
                if (!worker.transact(MotionDetectorProtocol.TX_VIEW_DETACH_CHARGE, detach, result, 0)) return;
                result.readException();
            } catch (Throwable ignored) {
            } finally { detach.recycle(); result.recycle(); }
        };
    }

    private static IBinder viewingWorker(Context context) throws Exception {
        if (context == null) throw new IllegalArgumentException("context required");
        return MotionDetectorClient.verifiedWorker(context,
                CameraViewingShellProtocol.currentApkIdentity(context));
    }

    private static final class ChargeObserver extends Binder {
        private final IBinder worker;
        private final Consumer<CameraChargePolicy.State> listener;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final IBinder.DeathRecipient workerDeath = this::detach;

        ChargeObserver(IBinder worker, int workerPid, Consumer<CameraChargePolicy.State> listener)
                throws RemoteException {
            if (worker == null || !worker.isBinderAlive() || workerPid <= 0)
                throw new IllegalArgumentException("verified Motion worker capability required");
            this.worker = worker;
            this.listener = listener;
            worker.linkToDeath(workerDeath, 0);
        }

        boolean detach() {
            if (!active.getAndSet(false)) return false;
            try { worker.unlinkToDeath(workerDeath, 0); } catch (Throwable ignored) { }
            return true;
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(MotionDetectorProtocol.VIEW_CHARGE_CALLBACK_DESCRIPTOR);
                return true;
            }
            if (code != MotionDetectorProtocol.CB_VIEW_CHARGE) return false;
            // One-way callbacks have no usable caller PID. This endpoint was passed only to the
            // APK-verified worker Binder above and is revoked with that exact Binder's lifetime.
            if (!active.get() || Binder.getCallingUid() != 2000 || !worker.isBinderAlive())
                throw new SecurityException("Motion charge callback sender denied");
            data.enforceInterface(MotionDetectorProtocol.VIEW_CHARGE_CALLBACK_DESCRIPTOR);
            CameraChargePolicy.State state = MotionDetectorProtocol.readChargeState(data);
            if (data.dataAvail() != 0) throw new IllegalArgumentException("charge callback has trailing data");
            try { listener.accept(state); } catch (RuntimeException ignored) { }
            return true;
        }
    }
}
