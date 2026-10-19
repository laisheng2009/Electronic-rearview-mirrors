package com.byd.extend;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ResultReceiver;
import android.util.Log;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** A dialog-owned diagnostic lease; never configures or stops the autonomous session. */
public final class MotionCheckupClient implements AutoCloseable {
    public static final int ACCESS = 0, DETECTOR = 1, FRAMES = 2, RECORDING = 3;
    public static final int NOT_CHECKED = 0, RUNNING = 1, OK = 2, ERROR = 3;
    static final long TIMEOUT_MS = 45_000L;

    public static final class Update {
        public final int stage, state;
        public final String reason, detail;
        Update(int stage, int state, String reason, String detail) {
            this.stage = stage; this.state = state; this.reason = reason; this.detail = detail;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService commands = Executors.newSingleThreadExecutor();
    private final MotionDetectorClient launcher;
    private final Context context;
    private final String id = UUID.randomUUID().toString().replace("-", "");
    private final IBinder owner = new Binder();
    private final Consumer<Update> listener;
    private volatile IBinder worker;
    private volatile boolean closed;
    private boolean started;
    private volatile boolean finished;
    private int stage = ACCESS;
    private final Runnable timeout = () -> finishError("timeout", "Motion check-up timed out");
    private final IBinder.DeathRecipient death = () -> main.post(() ->
            finishError("worker_unavailable", "Motion worker Binder died during check-up"));
    private final ResultReceiver results = new ResultReceiver(main) {
        @Override protected void onReceiveResult(int code, Bundle data) {
            if (closed || finished || data == null) return;
            stage = data.getInt("stage", ACCESS);
            if (stage < ACCESS || stage > RECORDING) return;
            listener.accept(new Update(stage, code, data.getString("reason", ""),
                    data.getString("detail", "")));
            if (data.getBoolean("terminal")) {
                finished = true;
                main.removeCallbacks(timeout);
            }
        }
    };

    public MotionCheckupClient(Context context, Consumer<Update> listener) {
        this.context = context.getApplicationContext();
        launcher = new MotionDetectorClient(this.context);
        this.listener = listener;
    }

    public void start() {
        if (started || closed) return;
        started = true;
        listener.accept(new Update(ACCESS, RUNNING, "", ""));
        main.postDelayed(timeout, TIMEOUT_MS);
        commands.execute(() -> {
            try {
                IBinder target = launcher.ensureCheckWorker();
                worker = target;
                if (closed || finished) return;
                target.linkToDeath(death, 0);
                Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
                    data.writeString(id);
                    data.writeStrongBinder(owner);
                    data.writeTypedObject(results, 0);
                    data.writeString(MotionConfig.read(context).toJson());
                    if (!target.transact(MotionDetectorProtocol.TX_CHECK_START, data, reply, 0))
                        throw new IllegalStateException("Motion check-up endpoint unavailable");
                    reply.readException();
                } finally { data.recycle(); reply.recycle(); }
            } catch (Exception error) {
                Log.e("MotionCheckup", "Worker access failed", error);
                Throwable cause = error;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                String detail = error + (cause == error ? "" : "; cause: " + cause);
                main.post(() -> finishError("worker_unavailable", detail));
            }
        });
    }

    private void finishError(String reason, String detail) {
        if (closed || finished) return;
        finished = true;
        main.removeCallbacks(timeout);
        Log.e("MotionCheckup", reason + ": " + detail);
        listener.accept(new Update(stage, ERROR, reason, detail));
        commands.execute(this::cancelRemote);
    }

    private void cancelRemote() {
        IBinder target = worker;
        if (target == null) return;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
            data.writeString(id);
            if (!target.transact(MotionDetectorProtocol.TX_CHECK_CANCEL, data, reply, 0))
                throw new IllegalStateException("Motion check-up cancellation unavailable");
            reply.readException();
        } catch (Exception error) { Log.e("MotionCheckup", "cancel: " + error); }
        finally {
            data.recycle(); reply.recycle();
            try { target.unlinkToDeath(death, 0); } catch (java.util.NoSuchElementException ignored) { }
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        main.removeCallbacks(timeout);
        commands.execute(() -> {
            cancelRemote();
            launcher.close();
        });
        commands.shutdown();
    }
}
