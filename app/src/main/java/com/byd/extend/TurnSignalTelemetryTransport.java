package com.byd.extend;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;

/** Fixed four-signal guard telemetry transport; it exposes no caller-selected FIDs. */
final class TurnSignalTelemetryTransport implements TurnSignalTelemetryController.Transport {
    private static final FixedBydTelemetryManager.Request[] REQUESTS = {
            new FixedBydTelemetryManager.Request(
                    TurnSignalTelemetryController.STALK_DEVICE,
                    TurnSignalTelemetryController.STALK_FID,
                    TurnSignalTelemetryController.BLINK_FID),
            new FixedBydTelemetryManager.Request(TurnSignalTelemetryController.STEERING_DEVICE,
                    FixedBydTelemetryManager.ValueType.FLOAT,
                    TurnSignalTelemetryController.STEERING_FID),
            new FixedBydTelemetryManager.Request(TurnSignalTelemetryController.SPEED_DEVICE,
                    FixedBydTelemetryManager.ValueType.FLOAT,
                    TurnSignalTelemetryController.SPEED_FID)
    };

    private final Context context;
    private IBinder service;

    TurnSignalTelemetryTransport(Context context) { this.context = context; }

    @Override public TurnSignalTelemetryController.Subscription subscribe(
            TurnSignalTelemetryController.Listener listener) throws Exception {
        return subscribe(FixedBydTelemetryManager.get(context), listener);
    }

    static TurnSignalTelemetryController.Subscription subscribe(
            FixedBydTelemetryManager manager, TurnSignalTelemetryController.Listener listener)
            throws Exception {
        FixedBydTelemetryManager.Subscription subscription =
                manager.subscribe(REQUESTS,
                        new FixedBydTelemetryManager.Listener() {
                            @Override public void onValue(
                                    int device, int fid, int value, long receivedMs) {
                                listener.onValue(device, fid, value, receivedMs);
                            }
                            @Override public void onError(String reason, long receivedMs) {
                                listener.onError(reason, receivedMs);
                            }
                        });
        return subscription::close;
    }

    @Override public TurnSignalTelemetryController.Snapshot read() {
        return new TurnSignalTelemetryController.Snapshot(
                readInt(TurnSignalTelemetryController.STALK_DEVICE,
                        TurnSignalTelemetryController.STALK_FID),
                readInt(TurnSignalTelemetryController.STEERING_DEVICE,
                        TurnSignalTelemetryController.STEERING_FID),
                readInt(TurnSignalTelemetryController.BLINK_DEVICE,
                        TurnSignalTelemetryController.BLINK_FID),
                readInt(TurnSignalTelemetryController.SPEED_DEVICE,
                        TurnSignalTelemetryController.SPEED_FID));
    }

    private int readInt(int device, int fid) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            IBinder binder = service();
            data.writeInterfaceToken(binder.getInterfaceDescriptor());
            data.writeInt(device);
            data.writeInt(fid);
            if (!binder.transact(device == TurnSignalTelemetryController.STEERING_DEVICE
                            || device == TurnSignalTelemetryController.SPEED_DEVICE ? 7 : 5,
                    data, reply, 0) || reply.dataAvail() < 8) {
                return TurnSignalTelemetryController.MISSING;
            }
            int status = reply.readInt();
            int value = reply.readInt();
            return status == 0 ? value : TurnSignalTelemetryController.MISSING;
        } catch (Exception ignored) {
            synchronized (this) { service = null; }
            return TurnSignalTelemetryController.MISSING;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private synchronized IBinder service() throws Exception {
        if (service == null || !service.isBinderAlive()) {
            service = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "autoservice");
        }
        if (service == null) throw new IllegalStateException("autoservice unavailable");
        return service;
    }
}
