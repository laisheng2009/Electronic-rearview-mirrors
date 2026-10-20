package com.byd.extend;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.view.Surface;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Per-window mapping endpoint; only configuration/acks cross Binder, never camera frames. */
final class CameraGpuOutput extends Binder {
    private static final String DESCRIPTOR = "com.byd.extend.CameraGpuOutput";
    private static final int BIND = FIRST_CALL_TRANSACTION;
    private static final int APPLIED = BIND + 1;
    private static final int DETACHED = BIND + 2;
    private static final int UPDATE = BIND + 3;
    private static final int RELEASE = BIND + 4;
    private static final int STOCK_CANVAS = BIND + 5;
    private static final int FRAME = BIND + 6;
    private static final Map<Surface, Endpoint> OUTPUTS = new WeakHashMap<>();
    private static final class Endpoint {
        final IBinder binder;
        final int uid;
        Endpoint(IBinder binder, int uid) { this.binder = binder; this.uid = uid; }
    }

    private final int width;
    private final int height;
    private final boolean lifecycleOnly;
    private final Consumer<CameraDewarpRenderer.Event> events;
    private CameraDewarpRenderer.MappingRequest request;
    private IBinder control;
    private Runnable stopped;
    private boolean released;
    private java.util.function.BiConsumer<Integer, Integer> stockCanvas;
    private java.util.function.LongConsumer frameListener;
    private final DeathRecipient death = this::disconnected;

    CameraGpuOutput(int width, int height, CameraDewarpConfig config, long token,
            Consumer<CameraDewarpRenderer.Event> events) {
        this(width, height, config, token, events, false);
    }

    CameraGpuOutput(int width, int height, CameraDewarpConfig config, long token,
            Consumer<CameraDewarpRenderer.Event> events, boolean lifecycleOnly) {
        this.width = width;
        this.height = height;
        this.events = events;
        this.lifecycleOnly = lifecycleOnly;
        request = new CameraDewarpRenderer.MappingRequest(config, token);
    }

    static void register(Surface surface, CameraGpuOutput output) {
        synchronized (OUTPUTS) { OUTPUTS.put(surface, new Endpoint(output, Process.myUid())); }
    }

    static void unregister(Surface surface) {
        synchronized (OUTPUTS) { OUTPUTS.remove(surface); }
    }

    static void writeSurface(Parcel data, Surface surface) {
        surface.writeToParcel(data, 0);
        synchronized (OUTPUTS) {
            Endpoint endpoint = OUTPUTS.get(surface);
            data.writeStrongBinder(endpoint == null ? null : endpoint.binder);
        }
    }

    static Surface readSurface(Parcel data) {
        return readSurface(data, Binder.getCallingUid());
    }

    static Surface readSurface(Parcel data, int ownerUid) {
        Surface surface = Surface.CREATOR.createFromParcel(data);
        try {
            IBinder endpoint = data.readStrongBinder();
            if (endpoint != null) synchronized (OUTPUTS) {
                OUTPUTS.put(surface, new Endpoint(endpoint, ownerUid));
            }
            return surface;
        } catch (RuntimeException error) {
            surface.release();
            throw error;
        }
    }

    static Binding bind(Surface surface, Runnable releaseTarget) throws RemoteException {
        Endpoint endpoint;
        synchronized (OUTPUTS) { endpoint = OUTPUTS.get(surface); }
        return endpoint == null ? null : new Binding(endpoint.binder, endpoint.uid, releaseTarget);
    }

    void setStockCanvasHandler(java.util.function.BiConsumer<Integer, Integer> handler) {
        stockCanvas = handler;
    }

    void setFrameListener(java.util.function.LongConsumer listener) { frameListener = listener; }

    static void configureStockCanvas(Surface surface, int width, int height) throws RemoteException {
        Endpoint endpoint;
        synchronized (OUTPUTS) { endpoint = OUTPUTS.get(surface); }
        if (endpoint == null) throw new IllegalStateException("Stock canvas endpoint missing");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(width);
            data.writeInt(height);
            if (!endpoint.binder.transact(STOCK_CANVAS, data, reply, 0))
                throw new IllegalStateException("Stock canvas configuration rejected");
            reply.readException();
        } finally { data.recycle(); reply.recycle(); }
    }

    synchronized void update(CameraDewarpConfig config, long token) {
        if (released || token <= request.token) return;
        request = new CameraDewarpRenderer.MappingRequest(config, token);
        if (control == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            writeMapping(data, request);
            if (!control.transact(UPDATE, data, null, FLAG_ONEWAY)) disconnected();
        } catch (RemoteException error) { disconnected(); }
        finally { data.recycle(); }
    }

    synchronized void release(Runnable onStopped) {
        if (released) return;
        released = true;
        stopped = onStopped;
        if (control == null) { disconnected(); return; }
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!control.transact(RELEASE, data, null, FLAG_ONEWAY)) disconnected();
        } catch (RemoteException error) { disconnected(); }
        finally { data.recycle(); }
    }

    private synchronized void disconnected() {
        if (control != null) control.unlinkToDeath(death, 0);
        control = null;
        Runnable completion = stopped;
        stopped = null;
        if (completion != null) completion.run();
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (!CameraShellProtocol.isCallerAllowed(Binder.getCallingUid(), Process.myUid())) {
            throw new SecurityException("GPU output sender denied");
        }
        data.enforceInterface(DESCRIPTOR);
        if (code == STOCK_CANVAS) {
            int w = data.readInt(), h = data.readInt();
            if (w < 240 || h < 240 || w > 4096 || h > 4096)
                throw new IllegalArgumentException("Invalid stock canvas dimensions");
            synchronized (this) {
                if (released || !lifecycleOnly || stockCanvas == null)
                    throw new IllegalStateException("Stock canvas unavailable");
            }
            // The main-thread resize must not hold the lease lock used by view retirement.
            stockCanvas.accept(w, h);
            reply.writeNoException();
            return true;
        }
        return transactLease(code, data, reply);
    }

    private synchronized boolean transactLease(int code, Parcel data, Parcel reply)
            throws RemoteException {
        if (code == BIND) {
            if (released || control != null) throw new IllegalStateException("GPU output not available");
            IBinder next = data.readStrongBinder();
            if (next == null) throw new IllegalArgumentException("GPU output control missing");
            next.linkToDeath(death, 0);
            control = next;
            reply.writeNoException();
            reply.writeInt(width);
            reply.writeInt(height);
            reply.writeInt(lifecycleOnly ? 1 : 0);
            reply.writeInt(frameListener != null ? 1 : 0);
            writeMapping(reply, request);
            return true;
        }
        IBinder sender = data.readStrongBinder();
        if (sender != control) {
            if (reply != null) reply.writeNoException();
            return true;
        }
        if (code == DETACHED) {
            disconnected();
            if (reply != null) reply.writeNoException();
            return true;
        }
        if (code == FRAME) {
            long timestamp = data.readLong();
            if (!released && frameListener != null && timestamp > 0) frameListener.accept(timestamp);
            return true;
        }
        if (code == APPLIED) {
            long token = data.readLong();
            boolean fallback = data.readInt() != 0;
            int vertices = data.readInt();
            long generationNs = data.readLong();
            if (!released && token == request.token) {
                events.accept(new CameraDewarpRenderer.Event(fallback
                        ? "dewarp_fallback_raw" : "dewarp_mesh_applied",
                        request, vertices, generationNs, null, width, height));
            }
            return true;
        }
        return false;
    }

    private static void writeMapping(Parcel parcel, CameraDewarpRenderer.MappingRequest value) {
        parcel.writeLong(value.token);
        parcel.writeIntArray(CameraShellProtocol.encodeDewarp(value.config));
        parcel.writeFloat(value.config.roiCenterX);
        parcel.writeFloat(value.config.roiCenterY);
    }

    private static CameraDewarpRenderer.MappingRequest readMapping(Parcel parcel) {
        long token = parcel.readLong();
        if (token < 0) throw new IllegalArgumentException("invalid GPU mapping token");
        CameraDewarpConfig config = CameraShellProtocol.decodeDewarp(parcel.createIntArray())
                .withRoiCenter(parcel.readFloat(), parcel.readFloat());
        return new CameraDewarpRenderer.MappingRequest(config, token);
    }

    /** Source-worker-owned lease. Destruction is acknowledged only after EGL disconnects. */
    static final class Binding extends Binder {
        final IBinder endpoint;
        final Runnable releaseTarget;
        final int ownerUid;
        final int width;
        final int height;
        final boolean lifecycleOnly;
        final boolean frameEvents;
        volatile CameraDewarpRenderer.MappingRequest request;
        private boolean closed;
        private final DeathRecipient ownerDeath;

        Binding(IBinder endpoint, int ownerUid, Runnable releaseTarget) throws RemoteException {
            this.endpoint = endpoint;
            this.ownerUid = ownerUid;
            this.releaseTarget = releaseTarget;
            ownerDeath = releaseTarget::run;
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                endpoint.linkToDeath(ownerDeath, 0);
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeStrongBinder(this);
                if (!endpoint.transact(BIND, data, reply, 0)) {
                    throw new IllegalStateException("GPU output bind rejected");
                }
                reply.readException();
                width = reply.readInt();
                height = reply.readInt();
                lifecycleOnly = reply.readInt() != 0;
                frameEvents = reply.readInt() != 0;
                if (width <= 0 || height <= 0 || width > 4096 || height > 4096) {
                    throw new IllegalArgumentException("invalid GPU output dimensions");
                }
                accept(readMapping(reply));
            } catch (RemoteException | RuntimeException error) {
                try { close(); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
                throw error;
            } finally { data.recycle(); reply.recycle(); }
        }

        private synchronized void accept(CameraDewarpRenderer.MappingRequest next) {
            if (!closed && (request == null || next.token > request.token)) request = next;
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (Binder.getCallingUid() != ownerUid) throw new SecurityException("GPU control sender denied");
            data.enforceInterface(DESCRIPTOR);
            if (code == UPDATE) { accept(readMapping(data)); return true; }
            if (code == RELEASE) { releaseTarget.run(); return true; }
            return false;
        }

        void applied(long token, boolean fallback, int vertices, long generationNs) throws RemoteException {
            Parcel data = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeStrongBinder(this);
                data.writeLong(token);
                data.writeInt(fallback ? 1 : 0);
                data.writeInt(vertices);
                data.writeLong(generationNs);
                if (!endpoint.transact(APPLIED, data, null, FLAG_ONEWAY)) {
                    throw new IllegalStateException("GPU output acknowledgement rejected");
                }
            } finally { data.recycle(); }
        }

        void frame(long timestamp) throws RemoteException {
            if (!frameEvents) return;
            Parcel data = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeStrongBinder(this);
                data.writeLong(timestamp);
                if (!endpoint.transact(FRAME, data, null, FLAG_ONEWAY))
                    throw new IllegalStateException("GPU frame acknowledgement rejected");
            } finally { data.recycle(); }
        }

        void close() {
            synchronized (this) { if (closed) return; closed = true; }
            endpoint.unlinkToDeath(ownerDeath, 0);
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeStrongBinder(this);
                if (endpoint.transact(DETACHED, data, reply, 0)) reply.readException();
            } catch (RemoteException ignored) {
                // A dead owner has no remaining TextureView to release.
            } finally { data.recycle(); reply.recycle(); }
        }
    }
}
