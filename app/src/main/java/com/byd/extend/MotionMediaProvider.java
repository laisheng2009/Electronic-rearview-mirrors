package com.byd.extend;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Narrow MediaStore operations used by Motion; shell mode calls IContentProvider directly. */
interface MotionMediaProvider extends AutoCloseable {
    Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder);
    Uri insert(Uri uri, ContentValues values);
    int update(Uri uri, ContentValues values, String selection, String[] selectionArgs);
    int delete(Uri uri, String selection, String[] selectionArgs);
    ParcelFileDescriptor openFileDescriptor(Uri uri, String mode) throws IOException;
    Set<String> volumeNames();
    @Override void close();

    final class Resolver implements MotionMediaProvider {
        private final Context context;
        private final ContentResolver resolver;

        Resolver(Context context) {
            this.context = context;
            resolver = context.getContentResolver();
        }

        @Override public Cursor query(Uri uri, String[] projection, String selection,
                String[] selectionArgs, String sortOrder) {
            return resolver.query(uri, projection, selection, selectionArgs, sortOrder);
        }
        @Override public Uri insert(Uri uri, ContentValues values) { return resolver.insert(uri, values); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
            return resolver.update(uri, values, selection, args);
        }
        @Override public int delete(Uri uri, String selection, String[] args) {
            return resolver.delete(uri, selection, args);
        }
        @Override public ParcelFileDescriptor openFileDescriptor(Uri uri, String mode) throws IOException {
            return resolver.openFileDescriptor(uri, mode);
        }
        @Override public Set<String> volumeNames() {
            return android.os.Build.VERSION.SDK_INT >= 29
                    ? android.provider.MediaStore.getExternalVolumeNames(context) : Collections.emptySet();
        }
        @Override public void close() { }
    }

    /** Android 12 shell external-provider handle, using the shell Context's real AttributionSource. */
    final class External implements MotionMediaProvider {
        private static final String TAG = "MotionMediaProvider";
        private static final String AUTHORITY = "media";
        private final Object provider;
        private final Object attribution;
        private final Context shellContext;
        private final Method query;
        private final Method insert;
        private final Method update;
        private final Method delete;
        private final Method openFile;
        private final Object activityManager;
        private final Method removeProvider;
        private final IBinder token;
        private boolean closed;

        External(Context shellContext) throws Exception {
            this.shellContext = shellContext;
            Class<?> attributionType = Class.forName("android.content.AttributionSource");
            attribution = invoke(Context.class.getMethod("getAttributionSource"), shellContext);
            if (attribution == null) throw new IllegalStateException("Shell AttributionSource unavailable");
            int attributedUid = (Integer) invoke(attributionType.getMethod("getUid"), attribution);
            if (attributedUid != Process.myUid())
                throw new SecurityException("MediaStore AttributionSource UID does not match shell process");

            Class<?> providerType = Class.forName("android.content.IContentProvider");
            query = providerType.getMethod("query", attributionType, Uri.class, String[].class,
                    Bundle.class, Class.forName("android.os.ICancellationSignal"));
            insert = providerType.getMethod("insert", attributionType, Uri.class,
                    ContentValues.class, Bundle.class);
            update = providerType.getMethod("update", attributionType, Uri.class,
                    ContentValues.class, Bundle.class);
            delete = providerType.getMethod("delete", attributionType, Uri.class,
                    Bundle.class);
            openFile = providerType.getMethod("openFile", attributionType, Uri.class,
                    String.class, Class.forName("android.os.ICancellationSignal"));

            token = new android.os.Binder();
            Class<?> activityManagerType = Class.forName("android.app.IActivityManager");
            activityManager = invoke(Class.forName("android.app.ActivityManager")
                    .getMethod("getService"), null);
            Method acquire = activityManagerType.getMethod("getContentProviderExternal",
                    String.class, int.class, IBinder.class, String.class);
            removeProvider = activityManagerType.getMethod("removeContentProviderExternal",
                    String.class, IBinder.class);
            Object holder = invoke(acquire, activityManager, AUTHORITY, 0, token,
                    "byd-extend-motion");
            if (holder == null) throw new IllegalStateException("MediaProvider unavailable");
            try {
                provider = holder.getClass().getField("provider").get(holder);
                if (provider == null) throw new IllegalStateException("MediaProvider unavailable");
            } catch (Exception failure) {
                try { invoke(removeProvider, activityManager, AUTHORITY, token); }
                catch (Exception cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                throw failure;
            }
        }

        @Override public Cursor query(Uri uri, String[] projection, String selection,
                String[] selectionArgs, String sortOrder) {
            Bundle args = new Bundle();
            if (selection != null) args.putString("android:query-arg-sql-selection", selection);
            if (selectionArgs != null) args.putStringArray("android:query-arg-sql-selection-args", selectionArgs);
            if (sortOrder != null) args.putString("android:query-arg-sql-sort-order", sortOrder);
            try {
                return (Cursor) call(query, attribution, uri, projection, args, null);
            } catch (Exception failure) {
                throw providerFailure("query", failure);
            }
        }

        @Override public Uri insert(Uri uri, ContentValues values) {
            try { return (Uri) call(insert, attribution, uri, values, null); }
            catch (Exception failure) { throw providerFailure("insert", failure); }
        }

        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
            Bundle queryArgs = selectionArgs(selection, args);
            try { return (Integer) call(update, attribution, uri, values, queryArgs); }
            catch (Exception failure) { throw providerFailure("update", failure); }
        }

        @Override public int delete(Uri uri, String selection, String[] args) {
            Bundle queryArgs = selectionArgs(selection, args);
            try { return (Integer) call(delete, attribution, uri, queryArgs); }
            catch (Exception failure) { throw providerFailure("delete", failure); }
        }

        @Override public ParcelFileDescriptor openFileDescriptor(Uri uri, String mode) throws IOException {
            try {
                return (ParcelFileDescriptor) call(openFile, attribution, uri, mode, null);
            } catch (Exception failure) {
                throw new IOException("MediaStore openFile failed", failure);
            }
        }

        @Override public Set<String> volumeNames() {
            // StorageVolume exposes the MediaStore volume for each mounted filesystem. Avoid an
            // implicit ContentResolver call here so every MediaStore provider operation remains
            // attributed to the shell external-provider handle above.
            HashSet<String> names = new HashSet<>();
            android.os.storage.StorageManager manager = shellContext.getSystemService(
                    android.os.storage.StorageManager.class);
            if (manager == null) return names;
            for (android.os.storage.StorageVolume volume : manager.getStorageVolumes()) {
                String name = android.os.Build.VERSION.SDK_INT >= 30
                        ? volume.getMediaStoreVolumeName()
                        : MotionVolumeMapping.legacyName(volume.isPrimary(), volume.getUuid());
                if (name != null) names.add(name);
            }
            return names;
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            try { invoke(removeProvider, activityManager, AUTHORITY, token); }
            catch (Exception failure) { Log.w(TAG, "external_provider_release_failed", failure); }
        }

        private synchronized Object call(Method method, Object... args) throws Exception {
            if (closed) throw new IllegalStateException("MediaProvider handle is closed");
            return invoke(method, provider, args);
        }

        private static RuntimeException providerFailure(String operation, Exception failure) {
            Throwable cause = failure instanceof InvocationTargetException
                    && ((InvocationTargetException) failure).getCause() != null
                    ? ((InvocationTargetException) failure).getCause() : failure;
            if (cause instanceof RuntimeException) return (RuntimeException) cause;
            return new IllegalStateException("MediaStore " + operation + " failed", cause);
        }

        private static Bundle selectionArgs(String selection, String[] selectionArgs) {
            Bundle args = new Bundle();
            if (selection != null) args.putString("android:query-arg-sql-selection", selection);
            if (selectionArgs != null)
                args.putStringArray("android:query-arg-sql-selection-args", selectionArgs);
            return args;
        }
    }

    /** Fixed BYD normal-recorder table surface; never accepts a caller-supplied authority or URI. */
    interface OemRecorder extends AutoCloseable {
        Cursor queryNormal(String selection, String[] selectionArgs);
        int deleteNormal(String selection, String[] selectionArgs);
        @Override void close();

        final class Unavailable implements OemRecorder {
            private final Exception failure;
            Unavailable(Exception failure) { this.failure = failure; }
            @Override public Cursor queryNormal(String selection, String[] selectionArgs) {
                throw new IllegalStateException("CDR normal-recorder provider is unavailable", failure);
            }
            @Override public int deleteNormal(String selection, String[] selectionArgs) {
                throw new IllegalStateException("CDR normal-recorder provider is unavailable", failure);
            }
            @Override public void close() { }
        }

        final class Resolver implements OemRecorder {
            private static final Uri URI = Uri.parse("content://cdr/cdr_video_normal");
            private final ContentResolver resolver;

            Resolver(Context context) { resolver = context.getContentResolver(); }
            @Override public Cursor queryNormal(String selection, String[] selectionArgs) {
                return resolver.query(URI, new String[]{"file_path", "name"}, selection,
                        selectionArgs, null);
            }
            @Override public int deleteNormal(String selection, String[] selectionArgs) {
                return resolver.delete(URI, selection, selectionArgs);
            }
            @Override public void close() { }
        }

        /** Shell CDR handle uses a real shell AttributionSource and the external provider API. */
        final class External implements OemRecorder {
            private static final String TAG = "MotionMediaProvider";
            private static final String AUTHORITY = "cdr";
            private static final Uri URI = Uri.parse("content://cdr/cdr_video_normal");
            private final Object provider;
            private final Object attribution;
            private final Object activityManager;
            private final Method removeProvider;
            private final IBinder token;
            private final Method query;
            private final Method delete;
            private boolean closed;

            External(Context shellContext) throws Exception {
                Class<?> attributionType = Class.forName("android.content.AttributionSource");
                attribution = invoke(Context.class.getMethod("getAttributionSource"), shellContext);
                if (attribution == null) throw new IllegalStateException("Shell AttributionSource unavailable");
                int attributedUid = (Integer) invoke(attributionType.getMethod("getUid"), attribution);
                if (attributedUid != Process.myUid())
                    throw new SecurityException("CDR AttributionSource UID does not match shell process");
                Class<?> providerType = Class.forName("android.content.IContentProvider");
                query = providerType.getMethod("query", attributionType, Uri.class, String[].class,
                        Bundle.class, Class.forName("android.os.ICancellationSignal"));
                delete = providerType.getMethod("delete", attributionType, Uri.class, Bundle.class);
                token = new android.os.Binder();
                Class<?> activityManagerType = Class.forName("android.app.IActivityManager");
                activityManager = invoke(Class.forName("android.app.ActivityManager")
                        .getMethod("getService"), null);
                Method acquire = activityManagerType.getMethod("getContentProviderExternal",
                        String.class, int.class, IBinder.class, String.class);
                removeProvider = activityManagerType.getMethod("removeContentProviderExternal",
                        String.class, IBinder.class);
                Object holder = invoke(acquire, activityManager, AUTHORITY, 0, token,
                        "byd-extend-motion-cdr-cleanup");
                if (holder == null) throw new IllegalStateException("CDR provider unavailable");
                try {
                    provider = holder.getClass().getField("provider").get(holder);
                    if (provider == null) throw new IllegalStateException("CDR provider unavailable");
                } catch (Exception failure) {
                    try { invoke(removeProvider, activityManager, AUTHORITY, token); }
                    catch (Exception cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                    throw failure;
                }
            }

            @Override public Cursor queryNormal(String selection, String[] selectionArgs) {
                Bundle args = selectionArgs(selection, selectionArgs);
                try { return (Cursor) call(query, attribution, URI,
                        new String[]{"file_path", "name"}, args, null); }
                catch (Exception failure) { throw providerFailure("cdr_query", failure); }
            }

            @Override public int deleteNormal(String selection, String[] selectionArgs) {
                Bundle args = selectionArgs(selection, selectionArgs);
                try { return (Integer) call(delete, attribution, URI, args); }
                catch (Exception failure) { throw providerFailure("cdr_delete", failure); }
            }

            @Override public synchronized void close() {
                if (closed) return;
                closed = true;
                try { invoke(removeProvider, activityManager, AUTHORITY, token); }
                catch (Exception failure) { Log.w(TAG, "external_cdr_provider_release_failed", failure); }
            }

            private synchronized Object call(Method method, Object... args) throws Exception {
                if (closed) throw new IllegalStateException("CDR provider handle is closed");
                return invoke(method, provider, args);
            }
        }
    }

    private static RuntimeException providerFailure(String operation, Exception failure) {
        Throwable cause = failure instanceof InvocationTargetException
                && ((InvocationTargetException) failure).getCause() != null
                ? ((InvocationTargetException) failure).getCause() : failure;
        if (cause instanceof RuntimeException) return (RuntimeException) cause;
        return new IllegalStateException(operation + " failed", cause);
    }

    private static Bundle selectionArgs(String selection, String[] selectionArgs) {
        Bundle args = new Bundle();
        if (selection != null) args.putString("android:query-arg-sql-selection", selection);
        if (selectionArgs != null) args.putStringArray("android:query-arg-sql-selection-args", selectionArgs);
        return args;
    }

    static Object invoke(Method method, Object target, Object... args) throws Exception {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw failure;
        }
    }
}
