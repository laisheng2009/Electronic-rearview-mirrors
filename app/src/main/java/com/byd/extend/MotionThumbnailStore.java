package com.byd.extend;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;

import com.byd.extend.ui.motion.MotionThumbnailDiskCache;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Worker-owned, serial preparation and private persistent storage for archive covers. */
final class MotionThumbnailStore implements AutoCloseable {
    private static final int MAX_WIDTH = 480;
    private static final int MAX_HEIGHT = 270;
    private static final long MAX_PNG_BYTES = 2L * 1024L * 1024L;

    private final MotionStorage storage;
    private final MotionMediaIndex index;
    private final File leaseDirectory;
    private final File directory;
    private final MotionThumbnailDiskCache diskCache;
    private final Object lock = new Object();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            runnable.run();
        }, "motion-thumbnail-prepare");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Integer> volumeGenerations = new HashMap<>();
    private final Map<String, Boolean> previousMounted = new HashMap<>();
    private final Set<String> attempted = new HashSet<>();
    private final Set<String> validKeys = new HashSet<>();
    private final Set<String> queuedUris = new HashSet<>();
    private boolean scanQueued;
    private boolean scanRequested;
    private boolean closed;

    MotionThumbnailStore(MotionStorage storage, MotionMediaIndex index,
            File leaseDirectory, File privateDirectory) {
        if (storage == null || index == null || leaseDirectory == null || privateDirectory == null)
            throw new IllegalArgumentException("Motion thumbnail store dependencies are required");
        this.storage = storage;
        this.index = index;
        this.leaseDirectory = leaseDirectory;
        this.directory = new File(privateDirectory, "thumbnails-v1");
        this.diskCache = new MotionThumbnailDiskCache(directory);
    }

    /** Queues a full owner-index backfill; no extraction runs on the caller's thread. */
    void scanAvailable() {
        synchronized (lock) {
            if (closed) return;
            scanRequested = true;
            if (scanQueued) return;
            scanQueued = true;
            worker.execute(this::drainScans);
        }
    }

    /** Queues a freshly published recording without making finalization wait for extraction. */
    void prepare(String uri) {
        if (uri == null || uri.isEmpty()) return;
        synchronized (lock) {
            if (closed || !queuedUris.add(uri)) return;
            worker.execute(() -> {
                try { prepareUri(uri); }
                finally { synchronized (lock) { queuedUris.remove(uri); } }
            });
        }
    }

    ParcelFileDescriptor open(String volumeId, long mediaStoreId, String expectedKey,
            MotionMediaIndex.Record currentRecord) {
        if (!MotionThumbnailKey.isValid(expectedKey) || currentRecord == null
                || !volumeId.equals(currentRecord.volumeId) || !isFinalized(currentRecord.status)
                || !expectedKey.equals(MotionThumbnailKey.forRecord(currentRecord, mediaStoreId))) return null;
        synchronized (lock) {
            File file = diskCache.file(expectedKey);
            if (file == null || !file.isFile() || file.length() <= 0 || file.length() > MAX_PNG_BYTES) {
                if (validKeys.remove(expectedKey))
                    attempted.removeIf(value -> value.endsWith("|" + expectedKey));
                return null;
            }
            try { return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY); }
            catch (IOException | RuntimeException unavailable) { return null; }
        }
    }

    void removeFor(MotionMediaLibrary.Entry entry) {
        if (entry == null) return;
        removeIdentity(MotionThumbnailKey.identity(entry.volumeId, entry.contentUri.toString(),
                entry.mediaStoreId, entry.segmentId));
    }

    void removeForRecord(MotionMediaIndex.Record record, long mediaStoreId) {
        if (record == null || mediaStoreId <= 0) return;
        removeIdentity(MotionThumbnailKey.identity(record.volumeId, record.uri, mediaStoreId,
                record.segmentId));
    }

    private void removeIdentity(String identity) {
        String identityKey = MotionThumbnailKey.key(identity, "");
        String prefix = identityKey.substring(0, 64) + "-";
        synchronized (lock) {
            diskCache.removeIdentity(identityKey);
            validKeys.removeIf(key -> key.startsWith(prefix));
            attempted.removeIf(value -> {
                int lastSeparator = value.lastIndexOf('|');
                return lastSeparator >= 0 && value.substring(lastSeparator + 1).startsWith(prefix);
            });
        }
    }

    private void drainScans() {
        while (true) {
            synchronized (lock) { scanRequested = false; }
            try { scanMissing(); }
            catch (RuntimeException unavailable) {
                // A temporary provider failure must not strand subsequent mount or index scans.
            }
            synchronized (lock) {
                if (!scanRequested || closed) {
                    scanQueued = false;
                    return;
                }
            }
        }
    }

    private void scanMissing() {
        final List<MotionStorage.Volume> volumes;
        try { volumes = storage.volumes(); }
        catch (RuntimeException unavailable) { return; }
        HashMap<String, MotionStorage.Volume> byId = new HashMap<>();
        for (MotionStorage.Volume volume : volumes) byId.put(volume.id, volume);
        refreshVolumeGenerations(volumes);

        for (MotionMediaIndex.Record record : index.records()) {
            if (!isFinalized(record.status)) continue;
            MotionStorage.Volume volume = byId.get(record.volumeId);
            if (volume == null || !volume.mounted) continue;
            Uri uri = Uri.parse(record.uri);
            long mediaStoreId = storage.mediaStoreId(volume, uri);
            if (mediaStoreId <= 0) continue;
            String key = MotionThumbnailKey.forRecord(record, mediaStoreId);
            String attemptKey = attemptKey(volume.id, key);
            synchronized (lock) { if (closed || attempted.contains(attemptKey)) continue; }
            if (hasValid(key) || !storage.isOwnedMedia(volume, uri)) continue;
            prepareRecord(record.uri, key, volume.id, mediaStoreId);
        }
    }

    private void refreshVolumeGenerations(List<MotionStorage.Volume> volumes) {
        synchronized (lock) {
            for (MotionStorage.Volume volume : volumes) {
                Boolean old = previousMounted.put(volume.id, volume.mounted);
                if (old != null && old != volume.mounted)
                    volumeGenerations.put(volume.id, volumeGenerations.getOrDefault(volume.id, 0) + 1);
                else volumeGenerations.putIfAbsent(volume.id, 0);
            }
            // Missing volumes are remembered as absent so their return permits another attempt.
            for (String id : new HashSet<>(previousMounted.keySet())) {
                if (byVolumeId(volumes, id)) continue;
                Boolean old = previousMounted.put(id, false);
                if (old != null && old) volumeGenerations.put(id, volumeGenerations.getOrDefault(id, 0) + 1);
            }
        }
    }

    private static boolean byVolumeId(List<MotionStorage.Volume> volumes, String id) {
        for (MotionStorage.Volume volume : volumes) if (volume.id.equals(id)) return true;
        return false;
    }

    private void prepareUri(String uriValue) {
        MotionMediaIndex.Record record = index.find(uriValue);
        if (record == null || !isFinalized(record.status)) return;
        MotionStorage.Volume volume = storage.findVolume(record.volumeId);
        if (volume == null || !volume.mounted) return;
        Uri uri = Uri.parse(record.uri);
        long mediaStoreId = storage.mediaStoreId(volume, uri);
        if (mediaStoreId <= 0) return;
        String key = MotionThumbnailKey.forRecord(record, mediaStoreId);
        if (hasValid(key) || !storage.isOwnedMedia(volume, uri)) return;
        synchronized (lock) {
            if (closed || attempted.contains(attemptKey(volume.id, key))) return;
        }
        prepareRecord(uriValue, key, volume.id, mediaStoreId);
    }

    private void prepareRecord(String uriValue, String key, String volumeId, long mediaStoreId) {
        MotionMediaLease lease = null;
        ParcelFileDescriptor video = null;
        MediaMetadataRetriever retriever = null;
        Bitmap bitmap = null;
        try {
            MotionMediaIndex.Record initial = index.find(uriValue);
            if (initial == null || !isFinalized(initial.status)) return;
            MotionStorage.Volume volume = storage.findVolume(volumeId);
            if (volume == null || !volume.mounted || storage.mediaStoreId(volume, Uri.parse(uriValue)) != mediaStoreId
                    || !storage.isOwnedMedia(volume, Uri.parse(uriValue))) return;
            if (!key.equals(MotionThumbnailKey.forRecord(initial, mediaStoreId))) return;

            lease = MotionMediaLease.acquire(leaseDirectory, uriValue);
            MotionMediaIndex.Record current = index.find(uriValue);
            volume = storage.findVolume(volumeId);
            if (!sameRevision(initial, current, mediaStoreId) || volume == null || !volume.mounted
                    || !storage.isOwnedMedia(volume, Uri.parse(uriValue))) return;
            String attempt = attemptKey(volumeId, key);
            synchronized (lock) {
                if (closed || !attempted.add(attempt)) return;
            }

            video = storage.openMediaFileDescriptor(Uri.parse(uriValue), "r");
            if (video == null) return;
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(video.getFileDescriptor());
            bitmap = extractFirstFrame(retriever);
            if (bitmap == null || !fits(bitmap)) return;

            current = index.find(uriValue);
            volume = storage.findVolume(volumeId);
            if (!sameRevision(initial, current, mediaStoreId) || volume == null || !volume.mounted
                    || !storage.isOwnedMedia(volume, Uri.parse(uriValue))) return;
            if (!write(key, bitmap)) return;

            // Do not retain a cover if the indexed media revision changed during publication.
            current = index.find(uriValue);
            if (!sameRevision(initial, current, mediaStoreId)) remove(key);
        } catch (IOException | RuntimeException unavailable) {
            // Mount ejection, codec failures, or permission loss are not evidence of deletion.
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            if (retriever != null) try { retriever.release(); } catch (IOException | RuntimeException ignored) { }
            if (video != null) try { video.close(); } catch (IOException ignored) { }
            if (lease != null) try { lease.close(); } catch (IOException ignored) { }
        }
    }

    private static Bitmap extractFirstFrame(MediaMetadataRetriever retriever) {
        Bitmap frame;
        if (Build.VERSION.SDK_INT >= 27) {
            frame = retriever.getScaledFrameAtTime(0L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC, MAX_WIDTH, MAX_HEIGHT);
        } else {
            frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) return null;
            float scale = Math.min(1f, Math.min(MAX_WIDTH / (float) frame.getWidth(),
                    MAX_HEIGHT / (float) frame.getHeight()));
            if (scale < 1f) {
                Bitmap scaled = Bitmap.createScaledBitmap(frame,
                        Math.max(1, Math.round(frame.getWidth() * scale)),
                        Math.max(1, Math.round(frame.getHeight() * scale)), true);
                if (scaled != frame) frame.recycle();
                frame = scaled;
            }
        }
        return frame;
    }

    private boolean hasValid(String key) {
        synchronized (lock) {
            if (validKeys.contains(key)) return true;
            Boolean valid = diskCache.read(key, file -> {
                if (file.length() <= 0 || file.length() > MAX_PNG_BYTES) return null;
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
                return bounds.outWidth > 0 && bounds.outWidth <= MAX_WIDTH
                        && bounds.outHeight > 0 && bounds.outHeight <= MAX_HEIGHT ? Boolean.TRUE : null;
            });
            if (Boolean.TRUE.equals(valid)) validKeys.add(key);
            else validKeys.remove(key);
            return Boolean.TRUE.equals(valid);
        }
    }

    private boolean write(String key, Bitmap bitmap) {
        if (!MotionThumbnailKey.isValid(key) || !fits(bitmap)) return false;
        synchronized (lock) {
            if (closed || !ensurePrivateDirectory()) return false;
            boolean written = diskCache.write(key, output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            if (!written) return false;
            File target = diskCache.file(key);
            if (target == null) return false;
            try {
                Os.chmod(target.getAbsolutePath(), OsConstants.S_IRUSR | OsConstants.S_IWUSR);
                forgetOtherRevisions(key);
                validKeys.add(key);
                return target.isFile();
            } catch (android.system.ErrnoException | RuntimeException failure) {
                diskCache.remove(key);
                return false;
            }
        }
    }

    private boolean ensurePrivateDirectory() {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) return false;
        try {
            Os.chmod(directory.getAbsolutePath(), OsConstants.S_IRWXU);
            return true;
        } catch (android.system.ErrnoException failure) {
            return false;
        }
    }

    private void forgetOtherRevisions(String key) {
        String prefix = key.substring(0, 64) + "-";
        validKeys.removeIf(cached -> cached.startsWith(prefix) && !cached.equals(key));
    }

    private void remove(String key) {
        synchronized (lock) {
            diskCache.remove(key);
            validKeys.remove(key);
        }
    }

    private String attemptKey(String volumeId, String key) {
        synchronized (lock) {
            return volumeId + "|" + volumeGenerations.getOrDefault(volumeId, 0) + "|" + key;
        }
    }

    private static boolean sameRevision(MotionMediaIndex.Record expected,
            MotionMediaIndex.Record current, long mediaStoreId) {
        return current != null && isFinalized(current.status)
                && MotionThumbnailKey.forRecord(expected, mediaStoreId)
                .equals(MotionThumbnailKey.forRecord(current, mediaStoreId));
    }

    private static boolean isFinalized(String status) {
        return MotionMediaIndex.COMPLETE.equals(status) || MotionMediaIndex.INTERRUPTED.equals(status);
    }

    private static boolean fits(Bitmap bitmap) {
        return bitmap != null && !bitmap.isRecycled()
                && bitmap.getWidth() > 0 && bitmap.getWidth() <= MAX_WIDTH
                && bitmap.getHeight() > 0 && bitmap.getHeight() <= MAX_HEIGHT;
    }

    @Override public void close() {
        synchronized (lock) { closed = true; scanRequested = false; }
        worker.shutdownNow();
    }
}
