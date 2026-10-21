package com.byd.extend;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.IOException;

/** Shell services plus owner-APK resources for the remote-viewing app_process. */
public final class CameraViewingShellContext implements AutoCloseable {
    private final Context systemContext;
    private final Context ownerResourcesContext;
    private final Context runtimeContext;
    private final ApplicationInfo ownerApplication;
    private final int ownerUid;
    private final String apkIdentity;
    private final File privateDirectory;

    CameraViewingShellContext(Context system, int expectedOwnerUid, String expectedApkIdentity)
            throws Exception {
        if (system == null || expectedOwnerUid <= 0 || expectedApkIdentity == null
                || expectedApkIdentity.isEmpty())
            throw new IllegalArgumentException("invalid camera viewing owner identity");
        systemContext = TurnSignalShellMain.shellContext(system);
        ownerApplication = system.getPackageManager().getApplicationInfo(
                CameraViewingShellProtocol.OWNER_PACKAGE, 0);
        if (ownerApplication.uid != expectedOwnerUid)
            throw new SecurityException("camera viewing owner UID does not match package");
        ownerUid = expectedOwnerUid;
        apkIdentity = CameraViewingShellProtocol.currentApkIdentity(system);
        if (!apkIdentity.equals(expectedApkIdentity))
            throw new SecurityException("camera viewing owner APK changed during launch");
        ownerResourcesContext = system.createPackageContext(
                CameraViewingShellProtocol.OWNER_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
        privateDirectory = validatePrivateDirectory(new File(CameraViewingShellProtocol.STATE_ROOT));
        runtimeContext = new ViewingRuntimeContext(systemContext, ownerResourcesContext,
                ownerApplication, privateDirectory);
    }

    /** Shell-attributed platform context; this never impersonates the app UID. */
    public Context systemContext() { return systemContext; }

    /** Owner context is for APK resources/package metadata; shell-owned state uses privateDirectory. */
    public Context ownerResourcesContext() { return ownerResourcesContext; }
    /** Shell-attributed platform context with only owner APK resources/code metadata overlaid. */
    public Context runtimeContext() { return runtimeContext; }

    public int ownerUid() { return ownerUid; }
    public String apkIdentity() { return apkIdentity; }
    public String ownerApkPath() { return ownerApplication.sourceDir; }
    public String ownerNativeLibraryDir() { return ownerApplication.nativeLibraryDir; }
    public File privateDirectory() { return privateDirectory; }

    private static File validatePrivateDirectory(File directory) throws IOException {
        File expected = new File(CameraViewingShellProtocol.STATE_ROOT).getCanonicalFile();
        File actual = directory.getCanonicalFile();
        if (!expected.equals(actual)) throw new SecurityException("unexpected viewing state directory");
        if (!actual.isDirectory() && !actual.mkdirs() && !actual.isDirectory())
            throw new IOException("camera viewing state directory unavailable");
        try {
            if (Os.stat(actual.getAbsolutePath()).st_uid != android.os.Process.myUid())
                throw new SecurityException("camera viewing state directory is not shell-owned");
            Os.chmod(actual.getAbsolutePath(), OsConstants.S_IRWXU);
        } catch (android.system.ErrnoException failure) {
            throw new IOException("cannot validate camera viewing state directory", failure);
        }
        return actual;
    }

    private static final class ViewingRuntimeContext extends ContextWrapper {
        private final Context ownerResources;
        private final ApplicationInfo ownerApplication;
        private final File privateDirectory;

        ViewingRuntimeContext(Context shell, Context ownerResources,
                ApplicationInfo ownerApplication, File privateDirectory) {
            super(shell);
            this.ownerResources = ownerResources;
            this.ownerApplication = ownerApplication;
            this.privateDirectory = privateDirectory;
        }

        @Override public Context getApplicationContext() { return this; }
        @Override public android.content.res.Resources getResources() { return ownerResources.getResources(); }
        @Override public android.content.res.AssetManager getAssets() { return ownerResources.getAssets(); }
        @Override public ClassLoader getClassLoader() { return ownerResources.getClassLoader(); }
        @Override public ApplicationInfo getApplicationInfo() { return ownerApplication; }
        @Override public String getPackageCodePath() { return ownerApplication.sourceDir; }
        @Override public String getPackageResourcePath() { return ownerApplication.publicSourceDir; }
        @Override public File getDataDir() { return privateDirectory; }
        @Override public File getFilesDir() { return child("runtime-files"); }
        @Override public File getCacheDir() { return child("runtime-cache"); }
        @Override public File getCodeCacheDir() { return child("runtime-code-cache"); }
        @Override public File getNoBackupFilesDir() { return child("runtime-no-backup"); }

        private File child(String name) {
            File target = new File(privateDirectory, name);
            try {
                if (!target.isDirectory() && !target.mkdirs() && !target.isDirectory())
                    throw new IOException("cannot create viewing runtime directory");
                File canonicalRoot = privateDirectory.getCanonicalFile();
                File canonicalTarget = target.getCanonicalFile();
                if (!canonicalRoot.equals(canonicalTarget.getParentFile()))
                    throw new SecurityException("viewing runtime directory escaped its private root");
                Os.chmod(canonicalTarget.getAbsolutePath(), OsConstants.S_IRWXU);
                return canonicalTarget;
            } catch (Exception failure) {
                throw new IllegalStateException("viewing runtime directory unavailable", failure);
            }
        }
    }

    @Override public void close() {}
}
