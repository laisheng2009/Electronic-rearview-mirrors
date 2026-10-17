package com.byd.extend;

import android.os.IBinder;

/** Fixed Binder contract between the app, shell detector, and existing camera owner. */
final class MotionDetectorProtocol {
    static final String SERVICE_NAME = "byd_extend_motion";
    static final String PROCESS_NAME = "bydextend_motion";
    static final String MAIN_CLASS = "com.byd.extend.MotionDetectorShellMain";
    static final String DESCRIPTOR = "com.byd.extend.IMotionDetector";
    static final String CALLBACK_DESCRIPTOR = "com.byd.extend.IMotionDetectorCallback";
    static final String LOCK_PATH = "/data/local/tmp/bydextend_motion.lock";
    static final String LOG_PATH = "/data/local/tmp/bydextend_motion.log";
    static final int VERSION = 1;

    static final int TX_PING = IBinder.FIRST_CALL_TRANSACTION;
    static final int TX_START = IBinder.FIRST_CALL_TRANSACTION + 1;
    static final int TX_SET_ELIGIBLE = IBinder.FIRST_CALL_TRANSACTION + 2;
    static final int TX_STOP = IBinder.FIRST_CALL_TRANSACTION + 3;
    static final int TX_STATUS = IBinder.FIRST_CALL_TRANSACTION + 4;
    static final int TX_WAKE_FOR_WARNING = IBinder.FIRST_CALL_TRANSACTION + 5;
    static final int TX_FINISH_WARNING = IBinder.FIRST_CALL_TRANSACTION + 6;
    static final int TX_USER_TAKEOVER = IBinder.FIRST_CALL_TRANSACTION + 7;
    static final int TX_WARNING_WINDOW_READY = IBinder.FIRST_CALL_TRANSACTION + 8;

    static final int CB_PREPARED = IBinder.FIRST_CALL_TRANSACTION;
    static final int CB_STATUS = IBinder.FIRST_CALL_TRANSACTION + 1;
    static final int CB_DETECTION = IBinder.FIRST_CALL_TRANSACTION + 2;
    static final int CB_ERROR = IBinder.FIRST_CALL_TRANSACTION + 3;
    static final int CB_SCREEN = IBinder.FIRST_CALL_TRANSACTION + 4;

    // CameraRuntimeHost operations on byd_extend_guard/TX_CAMERA_RUNTIME.
    static final int CAMERA_ATTACH = 13;
    static final int CAMERA_DETACH = 14;
    static final int CAMERA_AUTHORIZE = 15;
    static final int SURFACE_INDEX = 4123;

    static final int INPUT_WIDTH = 3840;
    static final int INPUT_HEIGHT = 2600;
    static final int INPUT_BYTES = INPUT_WIDTH * INPUT_HEIGHT * 3 / 2;
    static final int PREVIEW_WIDTH = 1920;
    static final int PREVIEW_HEIGHT = 1080;
    static final long WARMUP_MS = 2_500L;
    static final long WARNING_MS = 10_000L;
    static final String OEM_PACKAGE = "com.byd.sentrymode";
    static final String OEM_MODEL_ASSET = "SM_V8s_L_V08_19.dlc";
    static final long OEM_MODEL_BYTES = 39_536_897L;
    static final String OEM_APK_SHA256 =
            "02EBF1C2290145063CC477D24C06DD286C80FE02EBB732D9A93B7A1D7903C296";
    static final String OEM_LIBRARY_SHA256 =
            "EE4CCBC9CA3111895FA8A825E312D5A8CD129BD12FAFF7BE915B05A45059539C";
    static final String OEM_MODEL_SHA256 =
            "036A57356B1B47A34419A0CFBF5DEED78827CC5BFCF8A3524273653936D08F76";
    static final String IDC_CONFIG =
            "supplier:idc;resolution:7680x1300;fps:30.0;datatype:yuv422;";
    static final int MODEL_VALUE = 286;

    private MotionDetectorProtocol() {}

    static boolean validSession(String sessionId) {
        return sessionId != null && sessionId.matches("[0-9a-f]{32}");
    }
}
