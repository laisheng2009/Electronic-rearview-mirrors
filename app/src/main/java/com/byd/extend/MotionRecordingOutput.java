package com.byd.extend;

import android.content.Context;
import android.opengl.EGLContext;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.Surface;
import java.util.IdentityHashMap;
import java.util.function.Consumer;

/** Encoder ownership for local shared-YUV recording and legacy helper consumers. */
final class MotionRecordingOutput {
    private static final String DESCRIPTOR = "com.byd.extend.MotionRecordingOutput";
    private static final IdentityHashMap<Surface, MotionRecordingOutput> OUTPUTS = new IdentityHashMap<>();
    final int width, height;
    final int[] order;
    final String session;
    final MotionFrameTimestamp clock;
    private final IBinder callback;
    private Surface[] surfaces;
    private MotionGpuComposer composer;
    private int attached;
    private MotionSharedSource localSource;

    MotionRecordingOutput(int width, int height, int[] order, String session,
            MotionFrameTimestamp clock, Consumer<String> errors) {
        this(width, height, order, session, clock, new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
                if (Binder.getCallingUid() != 2000) throw new SecurityException("Motion composer caller denied");
                data.enforceInterface(DESCRIPTOR);
                String error = data.readString();
                if (data.dataAvail() != 0) throw new IllegalArgumentException("invalid composer event");
                errors.accept(error);
                return true;
            }
        });
    }

    private MotionRecordingOutput(int width, int height, int[] order, String session,
            MotionFrameTimestamp clock, IBinder callback) {
        if (width < 2 || height < 2 || width > 4096 || height > 4096
                || order == null || order.length != 4 || callback == null
                || !MotionDetectorProtocol.validSession(session))
            throw new IllegalArgumentException("invalid Motion encoder configuration");
        int mask = 0;
        for (int direction : order) {
            if (direction < 0 || direction > 3 || (mask & (1 << direction)) != 0)
                throw new IllegalArgumentException("invalid Motion camera order");
            mask |= 1 << direction;
        }
        this.width = width; this.height = height; this.order = order.clone();
        this.session = session; this.clock = clock; this.callback = callback;
    }

    void initialize(Surface encoder) {
        // Four logical consumers share one encoder queue, not four intermediate buffer queues.
        surfaces = new Surface[]{encoder, encoder, encoder, encoder};
    }

    Surface[] inputs() { return surfaces.clone(); }

    void writeToParcel(Parcel data) {
        data.writeInt(width); data.writeInt(height); data.writeIntArray(order);
        data.writeLong(clock.wallTimeMillis(0)); data.writeStrongBinder(callback);
    }

    static MotionRecordingOutput readFromParcel(Parcel data, String session, Surface[] surfaces) {
        MotionRecordingOutput output = new MotionRecordingOutput(
                data.readInt(), data.readInt(), data.createIntArray(), session,
                new MotionFrameTimestamp(data.readLong(), 0), data.readStrongBinder());
        if (surfaces == null || surfaces.length != 4)
            throw new IllegalArgumentException("four Motion consumers required");
        output.surfaces = surfaces;
        return output;
    }

    void register() {
        synchronized (OUTPUTS) { for (Surface surface : surfaces) OUTPUTS.put(surface, this); }
    }

    void unregister() {
        synchronized (OUTPUTS) { for (Surface surface : surfaces) OUTPUTS.remove(surface); }
    }

    static MotionRecordingOutput find(Surface surface) {
        synchronized (OUTPUTS) { return OUTPUTS.get(surface); }
    }

    synchronized void prepare(Context context, EGLContext sharedContext, int glVersion) throws Exception {
        if (composer != null) return;
        composer = new MotionGpuComposer(context, width, height, order, session, clock,
                sharedContext, glVersion, this::failed);
        composer.initialize(surfaces[0]);
    }

    synchronized void retain() { attached++; }

    synchronized void detach(int sourceIndex) {
        if (composer == null) return;
        composer.detachSource(sourceIndex);
        if (--attached == 0) {
            composer.close();
            composer = null;
        }
    }

    synchronized boolean offer(int sourceIndex, CameraFramePump pump, int slot) {
        return composer != null && composer.offer(sourceIndex, pump, slot);
    }

    synchronized void closeUnused() {
        if (attached == 0 && composer != null) { composer.close(); composer = null; }
    }

    void startLocal(Context context, MotionSharedSource source) throws Exception {
        localSource = source;
        source.startRecording(context, surfaces[0], width, height, order, session, clock, this::failed);
    }

    void close(Runnable onClosed) {
        if (localSource != null) {
            localSource.stopRecording(onClosed);
            localSource = null;
        } else if (onClosed != null) onClosed.run();
    }

    void failed(String detail) {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR); data.writeString(detail);
            callback.transact(IBinder.FIRST_CALL_TRANSACTION, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException ignored) {
            // Host death/session cleanup owns encoder teardown if the Motion worker has gone away.
        } finally { data.recycle(); }
    }
}
