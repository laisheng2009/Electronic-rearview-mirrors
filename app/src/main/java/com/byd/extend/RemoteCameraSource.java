package com.byd.extend;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.PowerManager;
import android.view.Surface;
import java.util.UUID;
import java.util.function.Consumer;

/** Viewing-helper-owned streaming consumer of the existing shell camera host. */
public final class RemoteCameraSource implements AutoCloseable {
    private final Context context;
    private final IBinder owner = new Binder();
    private final String session = UUID.randomUUID().toString().replace("-", "");
    private final Consumer<String> failure;
    private IBinder host;
    private IBinder.DeathRecipient death;
    private boolean attached;
    private boolean preparationRequested;
    private long outputGeneration;
    private final PowerManager.WakeLock wakeLock;

    public RemoteCameraSource(Context context, Consumer<String> failure) {
        this.context = context.getApplicationContext();
        this.failure = failure;
        PowerManager power = (PowerManager) this.context.getSystemService(Context.POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bydextend:camera-viewer");
        wakeLock.setReferenceCounted(false);
    }

    public boolean prepare() throws Exception {
        if (!wakeLock.isHeld()) wakeLock.acquire();
        preparationRequested = true;
        return RemoteCameraPreparation.prepare(context, owner, session);
    }

    public void attach(Surface encoder, int width, int height) throws Exception {
        if (attached) throw new IllegalStateException("viewer already attached");
        if (!preparationRequested) throw new IllegalStateException("viewer not prepared");
        IBinder candidate = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, TurnSignalShellProtocol.SERVICE_NAME);
        if (!TurnSignalController.ping(candidate).healthy())
            throw new IllegalStateException("camera helper unavailable");
        MotionRecordingOutput output = new MotionRecordingOutput(width, height,
                new int[]{0, 1, 2, 3}, session, MotionFrameTimestamp.capture(),
                ignored -> failure.accept("camera_source_failed"));
        output.initialize(encoder);
        host = candidate;
        death = () -> failure.accept("camera_helper_unavailable");
        candidate.linkToDeath(death, 0);
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TurnSignalShellProtocol.DESCRIPTOR);
            data.writeInt(CameraRuntimeHost.VIEW_ATTACH);
            data.writeStrongBinder(owner);
            data.writeString(session);
            data.writeTypedArray(output.inputs(), 0);
            data.writeInt(1);
            output.writeToParcel(data);
            if (!candidate.transact(TurnSignalShellProtocol.TX_CAMERA_RUNTIME, data, reply, 0))
                throw new IllegalStateException("camera viewer operation unavailable");
            reply.readException();
            if (reply.readInt() != 1) throw new IllegalStateException("camera viewer not attached");
            attached = true;
        } finally { data.recycle(); reply.recycle(); }
    }

    public void updateOutput(Surface encoder, int direction) throws Exception {
        if (!attached || host == null) throw new IllegalStateException("viewer not attached");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TurnSignalShellProtocol.DESCRIPTOR);
            data.writeInt(CameraRuntimeHost.VIEW_UPDATE_OUTPUT);
            data.writeStrongBinder(owner); data.writeString(session);
            data.writeLong(++outputGeneration); data.writeInt(direction);
            data.writeInt(encoder == null ? 0 : 1);
            if (encoder != null) encoder.writeToParcel(data, 0);
            if (!host.transact(TurnSignalShellProtocol.TX_CAMERA_RUNTIME, data, reply, 0))
                throw new IllegalStateException("viewer output update unavailable");
            reply.readException();
            if (reply.readInt() != 1) throw new IllegalStateException("viewer output update incomplete");
        } finally { data.recycle(); reply.recycle(); }
    }

    @Override public void close() throws Exception {
        IBinder target = host;
        if (target != null && target.isBinderAlive()) {
            // Also cancel an attachment whose synchronous reply was lost.
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(TurnSignalShellProtocol.DESCRIPTOR);
                data.writeInt(CameraRuntimeHost.VIEW_DETACH);
                data.writeStrongBinder(owner);
                data.writeString(session);
                if (!target.transact(TurnSignalShellProtocol.TX_CAMERA_RUNTIME, data, reply, 0))
                    throw new IllegalStateException("camera viewer detach unavailable");
                reply.readException();
                if (reply.readInt() != 1) throw new IllegalStateException("camera viewer detach incomplete");
            } finally { data.recycle(); reply.recycle(); }
        }
        attached = false;
        if (target != null && death != null) target.unlinkToDeath(death, 0);
        host = null;
        death = null;
        try {
            if (preparationRequested) {
                RemoteCameraPreparation.release(context, owner, session);
                preparationRequested = false;
            }
        } finally {
            // A disappearing worker must not keep the app awake after confirmed video detach.
            if (wakeLock.isHeld()) wakeLock.release();
        }
    }

    public boolean isVideoDetached() { return host == null; }
}
