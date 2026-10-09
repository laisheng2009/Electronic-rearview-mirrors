package com.byd.extend;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;

/** Fixed ACC telemetry subscription and read-only transaction-5 GET. */
final class MusicVisualizerAccTransport {
    static final int DEVICE = 1005;
    static final int FID = -1728053193;

    interface Listener {
        void onValue(int value);
        void onError(String reason);
    }

    interface Subscription { void close(); }

    private final Context context;
    private IBinder service;

    MusicVisualizerAccTransport(Context context) { this.context = context; }

    Subscription subscribe(Listener callback) throws Exception {
        FixedBydTelemetryManager.Subscription subscription =
                FixedBydTelemetryManager.get(context).subscribe(
                        new FixedBydTelemetryManager.Request[]{
                                new FixedBydTelemetryManager.Request(DEVICE,
                                        FixedBydTelemetryManager.ValueType.INTEGER, FID)},
                        new FixedBydTelemetryManager.Listener() {
                            @Override public void onValue(
                                    int device, int fid, int value, long receivedMs) {
                                if (device == DEVICE && fid == FID) callback.onValue(value);
                            }
                            @Override public void onError(String reason, long receivedMs) {
                                callback.onError(reason);
                            }
                        });
        return subscription::close;
    }

    Integer read() {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            IBinder autoservice = service();
            data.writeInterfaceToken(autoservice.getInterfaceDescriptor());
            data.writeInt(DEVICE);
            data.writeInt(FID);
            if (!autoservice.transact(5, data, reply, 0) || reply.dataAvail() < 8) return null;
            int status = reply.readInt();
            int value = reply.readInt();
            return status == 0 ? value : null;
        } catch (Exception ignored) {
            synchronized (this) { service = null; }
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private synchronized IBinder service() throws Exception {
        return service = AvasAutoService.resolve(service);
    }
}
