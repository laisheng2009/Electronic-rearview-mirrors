package com.byd.extend;

import android.content.Context;
import android.os.IBinder;

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
}
