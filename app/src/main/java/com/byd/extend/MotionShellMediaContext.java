package com.byd.extend;

import android.Manifest;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/** Shell-worker environment for Motion media access; package Context is used for resources only. */
final class MotionShellMediaContext implements AutoCloseable {
    private static final String TAG = "MotionShellMedia";
    static final String OWNER_PACKAGE = "com.byd.extend";
    static final File WORKER_ROOT = new File("/data/local/tmp/bydextend_motion");

    private final Context shellContext;
    private final Context ownerResourcesContext;
    private final int appUid;
    private final File privateDirectory;
    private final MotionMediaProvider.External mediaProvider;
    private final MotionMediaProvider.OemRecorder oemRecorderProvider;

    MotionShellMediaContext(Context systemContext, int appUid, String ownerPackage,
            File privateDirectory) throws Exception {
        if (Build.VERSION.SDK_INT < 31)
            throw new IOException("Shell MediaStore external-provider access requires Android 12+");
        if (systemContext == null || appUid <= 0 || !OWNER_PACKAGE.equals(ownerPackage))
            throw new IllegalArgumentException("Invalid Motion shell owner identity");
        this.shellContext = TurnSignalShellMain.shellContext(systemContext);
        this.appUid = appUid;
        this.privateDirectory = validatePrivateDirectory(privateDirectory);
        ApplicationInfo app = systemContext.getPackageManager().getApplicationInfo(ownerPackage, 0);
        if (app.uid != appUid) throw new SecurityException("Motion owner UID does not match package");
        mediaProvider = new MotionMediaProvider.External(shellContext);
        MotionMediaProvider.OemRecorder.External cdr;
        Exception cdrFailure = null;
        try {
            cdr = new MotionMediaProvider.OemRecorder.External(shellContext);
        } catch (Exception failure) {
            Log.w(TAG, "optional_cdr_provider_unavailable", failure);
            cdr = null;
            cdrFailure = failure;
        }
        oemRecorderProvider = cdr == null
                ? new MotionMediaProvider.OemRecorder.Unavailable(cdrFailure)
                : cdr;
        try {
            ownerResourcesContext = systemContext.createPackageContext(ownerPackage,
                    Context.CONTEXT_IGNORE_SECURITY);
        } catch (PackageManager.NameNotFoundException failure) {
            mediaProvider.close();
            oemRecorderProvider.close();
            throw new IOException("Motion owner resources are unavailable", failure);
        }
    }

    /** Shell-attributed context for platform services. It does not impersonate the app UID. */
    Context systemContext() { return shellContext; }
    Context ownerResourcesContext() { return ownerResourcesContext; }
    int appUid() { return appUid; }
    File privateDirectory() { return privateDirectory; }
    MotionMediaProvider mediaProvider() { return mediaProvider; }
    MotionMediaProvider.OemRecorder oemRecorderProvider() { return oemRecorderProvider; }

    boolean hasShellPermission(String permission) {
        return shellContext.checkPermission(permission, Process.myPid(), Process.myUid())
                == PackageManager.PERMISSION_GRANTED;
    }

    boolean hasAppPermission(String permission) {
        // Package identity was UID-verified in the constructor. Query the package grant directly;
        // never borrow the shell process's own permission result for the app's mic/storage gate.
        return shellContext.getPackageManager().checkPermission(permission, OWNER_PACKAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    boolean hasRecordAudioPermission() {
        return hasShellPermission(Manifest.permission.RECORD_AUDIO)
                && hasAppPermission(Manifest.permission.RECORD_AUDIO);
    }

    boolean hasAppMotionReadPermission() {
        return Build.VERSION.SDK_INT < 23 || Build.VERSION.SDK_INT > 32
                || hasAppPermission(Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    boolean hasAppMotionWritePermission() {
        return Build.VERSION.SDK_INT < 23 || Build.VERSION.SDK_INT > 28
                || hasAppPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }

    boolean hasAppOemCleanupPermissions() {
        return Build.VERSION.SDK_INT < 23 || (hasAppPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                && hasAppPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE));
    }

    private static File validatePrivateDirectory(File directory) throws IOException {
        if (directory == null) throw new IOException("Motion worker directory is missing");
        File expected = WORKER_ROOT.getCanonicalFile();
        File actual = directory.getCanonicalFile();
        if (!expected.equals(actual)) throw new SecurityException("Unexpected Motion worker directory");
        if (!actual.isDirectory() && !actual.mkdirs() && !actual.isDirectory())
            throw new IOException("Cannot create Motion worker directory");
        try {
            android.system.StructStat stat = Os.stat(actual.getAbsolutePath());
            if (stat.st_uid != Process.myUid())
                throw new SecurityException("Motion worker directory is not shell-owned");
            Os.chmod(actual.getAbsolutePath(), OsConstants.S_IRWXU);
        } catch (android.system.ErrnoException failure) {
            throw new IOException("Cannot validate Motion worker directory", failure);
        }
        return actual;
    }

    @Override public void close() {
        mediaProvider.close();
        oemRecorderProvider.close();
    }
}
