package com.byd.extend;

import android.net.Uri;
import android.os.Process;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Fixed worker-side media/index operations; all record access is by volume and MediaStore ID. */
public final class MotionMediaIndexBridge {
    private static final int MAX_LEGACY_INDEX_BYTES = 16 * 1024 * 1024;
    private final MotionMediaLibrary library;
    private final MotionMediaPages pages = new MotionMediaPages();

    MotionMediaIndexBridge(MotionMediaLibrary library) {
        if (library == null) throw new IllegalArgumentException("Motion media library is required");
        // These accessors reject the app's remote facade; the bridge must own the worker index.
        library.index();
        library.storage();
        this.library = library;
        library.scanAvailableThumbnails();
    }

    /** Consumes one authenticated legacy index snapshot and never modifies its source. */
    public synchronized boolean importLegacyIndex(InputStream source) throws IOException {
        byte[] bytes = readBounded(source, MAX_LEGACY_INDEX_BYTES);
        String digest = sha256(bytes);
        MotionMediaIndex index = library.index();
        if (index.hasImportedLegacyDigest(digest)) return true;

        final List<MotionMediaIndex.Record> sourceRecords = MotionMediaIndex.parseLegacySnapshot(
                new ByteArrayInputStream(bytes), MAX_LEGACY_INDEX_BYTES);
        MotionStorage storage = library.storage();
        List<MotionStorage.Volume> volumes = storage.volumes();
        ArrayList<MotionMediaIndex.Record> imported = new ArrayList<>();
        boolean fullyResolved = true;
        int unresolved = 0;
        int missing = 0;
        for (MotionMediaIndex.Record old : sourceRecords) {
            Uri oldUri = old.uri == null ? null : Uri.parse(old.uri);
            if (!isMediaStoreItem(oldUri)) {
                fullyResolved = false;
                unresolved++;
                continue;
            }
            long id = positiveId(oldUri);
            if (id <= 0) {
                fullyResolved = false;
                unresolved++;
                continue;
            }
            String uriVolumeName = oldUri.getPathSegments().get(0);
            MotionStorage.Volume volume = matchVolume(volumes, old.volumeId, uriVolumeName);
            if (volume == null || !volume.mounted || volume.mediaStoreName == null) {
                fullyResolved = false;
                unresolved++;
                continue;
            }
            Uri canonical = storage.uriForOutput(volume.mediaStoreName, id);
            final MotionStorage.MediaStoreOwnership ownership;
            try {
                ownership = storage.inspectMediaStoreOwnership(volume, canonical);
            } catch (IOException unavailable) {
                fullyResolved = false;
                unresolved++;
                continue;
            }
            if (ownership == MotionStorage.MediaStoreOwnership.ABSENT) {
                // The app-side snapshot may still be the only copy of this record's markers.
                // Do not declare the snapshot migrated while any indexed item is unresolved.
                fullyResolved = false;
                unresolved++;
                missing++;
                continue;
            }
            if (ownership != MotionStorage.MediaStoreOwnership.OWNED) {
                fullyResolved = false;
                unresolved++;
                continue;
            }

            MotionMediaIndex.Record copy = new MotionMediaIndex.Record(canonical.toString(),
                    volume.id, old.name, old.segmentId, old.recordedAtMs,
                    old.startElapsedRealtimeMs, old.width, old.height, old.status, old.cameraOrder);
            copy.durationMs = old.durationMs;
            copy.sizeBytes = old.sizeBytes;
            copy.protectedFile = old.protectedFile;
            copy.markers.addAll(old.markers);
            imported.add(copy);
        }
        index.mergeLegacyRecords(imported, digest, fullyResolved);
        library.scanAvailableThumbnails();
        if (!fullyResolved) {
            System.out.println("MOTION session=media pid=" + Process.myPid()
                    + " elapsed_ms=" + SystemClock.elapsedRealtime()
                    + " event=legacy_index_import_incomplete imported=" + imported.size()
                    + " unresolved=" + unresolved + " missing=" + missing
                    + " source_preserved=true digest_committed=false");
            System.out.flush();
        }
        return fullyResolved;
    }

    /** Returns a fixed-schema page; decimal continuation tokens retain one coherent snapshot. */
    public synchronized String queryPage(MotionMediaLibrary.Query query, MotionConfig config,
            String pageToken) {
        MotionMediaLibrary.Query actual = query == null
                ? MotionMediaLibrary.Query.recent() : query;
        try {
            String queryKey = new JSONArray().put(actual.startInclusiveMs)
                    .put(actual.endExclusiveMs).put(actual.volumeId).put(actual.sort.name())
                    .put(config == null ? JSONObject.NULL : config.toJson()).toString();
            if (pageToken != null && !pageToken.isEmpty()) return pages.next(queryKey, pageToken);
            ArrayList<JSONObject> entries = new ArrayList<>();
            for (MotionMediaLibrary.Entry entry : library.query(actual)) entries.add(encodeEntry(entry));
            return pages.start(queryKey, entries, encodeStats(library.queryStats(config)));
        } catch (JSONException failure) {
            throw new IllegalStateException("Cannot encode worker Motion media page", failure);
        }
    }

    /** Read authorization is rechecked against the worker's current index and owner-filtered row. */
    public synchronized String read(String volumeId, long mediaStoreId) {
        MotionMediaLibrary.Entry entry = find(volumeId, mediaStoreId);
        JSONObject root = new JSONObject();
        try {
            root.put("version", 1);
            root.put("entry", entry == null ? JSONObject.NULL : encodeEntry(entry));
            return root.toString();
        } catch (JSONException failure) {
            throw new IllegalStateException("Cannot encode worker Motion media record", failure);
        }
    }

    /** Deletes only the exact current indexed ID through existing protected/lease/ownership rules. */
    public synchronized boolean delete(String volumeId, long mediaStoreId) {
        MotionMediaLibrary.Entry entry = find(volumeId, mediaStoreId);
        if (entry == null) return false;
        MotionMediaLibrary.DeleteResult result = library.deleteSelected(Collections.singletonList(entry));
        return result.deletedCount == 1;
    }

    /** Returns only a private read-only cover FD after revalidating its indexed revision. */
    public synchronized android.os.ParcelFileDescriptor openThumbnail(String volumeId,
            long mediaStoreId, String expectedKey) {
        if (!MotionThumbnailKey.isValid(expectedKey)) return null;
        return library.openPreparedThumbnail(volumeId, mediaStoreId, expectedKey);
    }

    private MotionMediaLibrary.Entry find(String volumeId, long mediaStoreId) {
        if (volumeId == null || volumeId.isEmpty() || mediaStoreId <= 0) return null;
        MotionMediaLibrary.Query query = new MotionMediaLibrary.Query(null, null, volumeId,
                MotionMediaLibrary.Sort.NEWEST_FIRST);
        for (MotionMediaLibrary.Entry entry : library.query(query))
            if (entry.mediaStoreId == mediaStoreId) return entry;
        return null;
    }

    private static JSONObject encodeEntry(MotionMediaLibrary.Entry entry) {
        JSONObject row = new JSONObject();
        JSONArray markers = new JSONArray();
        try {
            for (MotionMediaLibrary.Marker marker : entry.markers) {
                JSONObject item = new JSONObject();
                item.put("eventId", marker.eventId);
                item.put("recordedAtMillis", marker.recordedAtMillis);
                item.put("elapsedRealtimeMs", marker.elapsedRealtimeMs);
                item.put("endRecordedAtMillis", marker.endRecordedAtMillis);
                item.put("endElapsedRealtimeMs", marker.endElapsedRealtimeMs);
                item.put("positionMs", marker.positionMs);
                item.put("durationMs", marker.durationMs);
                item.put("level", marker.level);
                markers.put(item);
            }
            row.put("mediaStoreId", entry.mediaStoreId);
            row.put("contentUri", entry.contentUri.toString());
            row.put("title", entry.title);
            row.put("volumeId", entry.volumeId);
            row.put("segmentId", entry.segmentId);
            row.put("recordedAtMillis", entry.recordedAtMillis);
            row.put("durationMs", entry.durationMs);
            row.put("sizeBytes", entry.sizeBytes);
            row.put("status", entry.status.name().toLowerCase(java.util.Locale.ROOT));
            row.put("available", entry.available);
            row.put("protectedFile", entry.protectedFile);
            if (entry.cameraOrder != null)
                row.put("cameraOrder", MotionMediaIndex.encodeCameraOrder(entry.cameraOrder));
            row.put("markers", markers);
            return row;
        } catch (JSONException failure) {
            throw new IllegalStateException("Cannot encode worker Motion entry", failure);
        }
    }

    private static JSONObject encodeStats(MotionMediaLibrary.Stats stats) throws JSONException {
        JSONObject result = new JSONObject();
        JSONArray volumes = new JSONArray();
        for (MotionMediaLibrary.VolumeStats volume : stats.volumes) volumes.put(encodeVolume(volume));
        result.put("volumes", volumes);
        result.put("primary", stats.primary == null ? JSONObject.NULL : encodeVolume(stats.primary));
        result.put("secondary", stats.secondary == null ? JSONObject.NULL : encodeVolume(stats.secondary));
        return result;
    }

    private static JSONObject encodeVolume(MotionMediaLibrary.VolumeStats volume) throws JSONException {
        JSONObject row = new JSONObject();
        row.put("id", volume.id);
        row.put("label", volume.label);
        row.put("primary", volume.primary);
        row.put("removable", volume.removable);
        row.put("mounted", volume.mounted);
        row.put("readOnly", volume.readOnly);
        row.put("capacityBytes", volume.capacityBytes);
        row.put("freeBytes", volume.freeBytes);
        row.put("otherBytes", volume.otherBytes);
        row.put("ownBytes", volume.ownBytes);
        row.put("oemRecorderBytes", volume.oemRecorderBytes);
        row.put("ownAllowanceBytes", volume.ownAllowanceBytes);
        row.put("oemAllowanceBytes", volume.oemAllowanceBytes);
        row.put("unallocatedBytes", volume.unallocatedBytes);
        return row;
    }

    private static boolean isMediaStoreItem(Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme()) || !"media".equals(uri.getAuthority()))
            return false;
        List<String> segments = uri.getPathSegments();
        return segments.size() == 4 && "video".equals(segments.get(1))
                && "media".equals(segments.get(2));
    }

    private static long positiveId(Uri uri) {
        try {
            long id = Long.parseLong(uri.getLastPathSegment());
            return id > 0 ? id : -1;
        } catch (RuntimeException invalid) { return -1; }
    }

    private static MotionStorage.Volume matchVolume(List<MotionStorage.Volume> volumes,
            String legacyVolumeId, String uriVolumeName) {
        for (MotionStorage.Volume volume : volumes) {
            if (!volume.mounted || volume.mediaStoreName == null
                    || !volume.mediaStoreName.equals(uriVolumeName)) continue;
            if (volume.id.equals(legacyVolumeId) || volume.mediaStoreName.equals(legacyVolumeId)
                    || (volume.primary && ("primary".equals(legacyVolumeId)
                    || "external_primary".equals(legacyVolumeId)))) return volume;
        }
        return null;
    }

    private static byte[] readBounded(InputStream input, int maximumBytes) throws IOException {
        if (input == null) throw new IOException("Legacy Motion index is missing");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > maximumBytes - total) throw new IOException("Legacy Motion index exceeds size limit");
            output.write(buffer, 0, count);
            total += count;
        }
        return output.toByteArray();
    }

    private static String sha256(byte[] value) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }
}
