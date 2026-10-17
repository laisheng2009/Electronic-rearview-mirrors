package com.byd.extend;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaExtractor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Persistent archive index and real MediaStore-backed archive/storage queries. */
public final class MotionMediaLibrary {
    private final Context context;
    private final MotionStorage storage;
    private final MotionMediaIndex index;

    public MotionMediaLibrary(Context context) {
        this.context = context.getApplicationContext();
        this.storage = new MotionStorage(this.context);
        try {
            this.index = MotionMediaIndex.shared(new File(this.context.getFilesDir(), "motion/media-index.json"));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot open Motion media index", e);
        }
    }

    public enum Sort { NEWEST_FIRST, OLDEST_FIRST }
    public enum Status { RECORDING, COMPLETE, INTERRUPTED, UNKNOWN, UNAVAILABLE }

    public static final class Query {
        public final Long startInclusiveMs;
        public final Long endExclusiveMs;
        public final String volumeId;
        public final Sort sort;

        public Query(Long startInclusiveMs, Long endExclusiveMs, String volumeId, Sort sort) {
            this.startInclusiveMs = startInclusiveMs;
            this.endExclusiveMs = endExclusiveMs;
            this.volumeId = volumeId;
            this.sort = sort == null ? Sort.NEWEST_FIRST : sort;
        }

        public static Query recent() { return new Query(null, null, null, Sort.NEWEST_FIRST); }
    }

    public static final class Marker {
        public final String eventId;
        public final long recordedAtMillis;
        public final long elapsedRealtimeMs;
        public final long endRecordedAtMillis;
        public final long endElapsedRealtimeMs;
        public final long positionMs;
        public final long durationMs;
        public final int level;

        private Marker(String eventId, long recordedAtMillis, long elapsedRealtimeMs,
                long endRecordedAtMillis, long endElapsedRealtimeMs, long positionMs, int level) {
            this.eventId = eventId;
            this.recordedAtMillis = recordedAtMillis;
            this.elapsedRealtimeMs = elapsedRealtimeMs;
            this.endRecordedAtMillis = endRecordedAtMillis;
            this.endElapsedRealtimeMs = endElapsedRealtimeMs;
            this.positionMs = Math.max(0, positionMs);
            this.durationMs = Math.max(0, endElapsedRealtimeMs - elapsedRealtimeMs);
            this.level = level;
        }
    }

    public static final class Entry {
        public final Uri contentUri;
        public final String title;
        public final String volumeId;
        public final String segmentId;
        public final long recordedAtMillis;
        public final long durationMs;
        public final long sizeBytes;
        public final Status status;
        public final List<Marker> markers;
        public final boolean available;
        public final boolean protectedFile;

        private Entry(Uri contentUri, String title, String volumeId, String segmentId,
                long recordedAtMillis, long durationMs, long sizeBytes, Status status,
                List<Marker> markers, boolean available, boolean protectedFile) {
            this.contentUri = contentUri;
            this.title = title == null ? "" : title;
            this.volumeId = volumeId;
            this.segmentId = segmentId;
            this.recordedAtMillis = recordedAtMillis;
            this.durationMs = Math.max(0, durationMs);
            this.sizeBytes = Math.max(0, sizeBytes);
            this.status = status;
            this.markers = Collections.unmodifiableList(markers);
            this.available = available;
            this.protectedFile = protectedFile;
        }
    }

    public static final class Day {
        public final LocalDate date;
        public final int entryCount;
        public final long durationMs;
        public final long sizeBytes;
        public final int yellowEvents;
        public final int redEvents;

        private Day(LocalDate date, int entryCount, long durationMs, long sizeBytes,
                int yellowEvents, int redEvents) {
            this.date = date;
            this.entryCount = entryCount;
            this.durationMs = durationMs;
            this.sizeBytes = sizeBytes;
            this.yellowEvents = yellowEvents;
            this.redEvents = redEvents;
        }
    }

    public static final class VolumeStats {
        public final String id;
        public final String label;
        public final boolean primary;
        public final boolean removable;
        public final boolean mounted;
        public final boolean readOnly;
        public final long capacityBytes;
        public final long freeBytes;
        public final long otherBytes;
        public final long ownBytes;
        public final long oemRecorderBytes;
        public final long ownAllowanceBytes;
        public final long oemAllowanceBytes;
        public final long unallocatedBytes;

        private VolumeStats(String id, String label, boolean primary, boolean removable,
                boolean mounted, boolean readOnly, long capacityBytes, long freeBytes,
                long otherBytes, long ownBytes, long oemRecorderBytes, long ownAllowanceBytes,
                long oemAllowanceBytes, long unallocatedBytes) {
            this.id = id; this.label = label; this.primary = primary; this.removable = removable;
            this.mounted = mounted; this.readOnly = readOnly; this.capacityBytes = capacityBytes;
            this.freeBytes = freeBytes; this.otherBytes = otherBytes; this.ownBytes = ownBytes;
            this.oemRecorderBytes = oemRecorderBytes; this.ownAllowanceBytes = ownAllowanceBytes;
            this.oemAllowanceBytes = oemAllowanceBytes; this.unallocatedBytes = unallocatedBytes;
        }
    }

    public static final class Stats {
        public final List<VolumeStats> volumes;
        public final VolumeStats primary;
        public final VolumeStats secondary;

        private Stats(List<VolumeStats> volumes, VolumeStats primary, VolumeStats secondary) {
            this.volumes = Collections.unmodifiableList(volumes);
            this.primary = primary;
            this.secondary = secondary;
        }
    }

    public static final class DeleteResult {
        public final int deletedCount;
        public final int failedCount;
        public final int skippedCount;

        private DeleteResult(int deletedCount, int failedCount, int skippedCount) {
            this.deletedCount = deletedCount; this.failedCount = failedCount; this.skippedCount = skippedCount;
        }
    }

    static final class StorageDecision {
        final boolean allowed;
        final long ownBytes;
        final long allowanceBytes;
        final String reason;

        private StorageDecision(boolean allowed, long ownBytes, long allowanceBytes, String reason) {
            this.allowed = allowed;
            this.ownBytes = ownBytes;
            this.allowanceBytes = allowanceBytes;
            this.reason = reason;
        }
    }

    /** Reconciles app-owned public media and finishes valid interrupted segments after a crash. */
    public synchronized int refresh() {
        int changed = 0;
        List<MotionStorage.Volume> volumes = storage.volumes();
        Map<String, MotionStorage.Volume> byId = new HashMap<>();
        for (MotionStorage.Volume volume : volumes) byId.put(volume.id, volume);

        for (MotionMediaIndex.Record row : index.records()) {
            if (!MotionMediaIndex.RECORDING.equals(row.status)) continue;
            MotionStorage.Volume volume = byId.get(row.volumeId);
            if (volume == null || !volume.mounted || MotionMediaLease.isHeld(context, row.uri)) continue;
            Uri finalUri = Uri.parse(row.uri);
            String inspectionUri = row.uri;
            if (row.stagingUri != null && "file".equals(finalUri.getScheme())
                    && !new File(finalUri.getPath()).isFile()) inspectionUri = row.stagingUri;
            MediaFacts facts = inspect(inspectionUri);
            try {
                if (facts != null && facts.hasVideo
                        && storage.recoverInterrupted(volume, Uri.parse(row.uri), row.stagingUri)) {
                    long bytes = sizeOf(Uri.parse(row.uri));
                    index.update(row.uri, MotionMediaIndex.INTERRUPTED, facts.durationMs, Math.max(0, bytes));
                    changed++;
                } else if (storage.discardUnplayableInterrupted(volume, Uri.parse(row.uri), row.stagingUri)) {
                    index.remove(row.uri);
                    changed++;
                }
            } catch (IOException | RuntimeException ignored) {
                // Keep the index row for a later retry; volume ejection and permission loss are transient.
            }
        }

        for (MotionStorage.Volume volume : volumes) {
            if (!volume.mounted) continue;
            List<MotionStorage.OwnedMedia> media;
            try { media = storage.ownedMedia(volume); }
            catch (IOException unavailable) { continue; }
            for (MotionStorage.OwnedMedia item : media) {
                String uri = item.uri.toString();
                if (index.find(uri) != null) continue;
                if (item.pending) {
                    if (System.currentTimeMillis() - item.recordedAtMs < 60_000L
                            || MotionMediaLease.isHeld(context, uri)) continue;
                    MediaFacts facts = inspect(uri);
                    try {
                        if (facts != null && facts.hasVideo && storage.recoverInterrupted(volume, item.uri, null)) {
                            MotionMediaIndex.Record recovered = new MotionMediaIndex.Record(uri, volume.id,
                                    item.name, item.name == null ? uri : item.name, item.recordedAtMs,
                                    0, item.width, item.height, MotionMediaIndex.INTERRUPTED);
                            recovered.durationMs = facts.durationMs;
                            recovered.sizeBytes = sizeOf(item.uri);
                            index.put(recovered);
                            changed++;
                        } else if (storage.discardUnplayableInterrupted(volume, item.uri, null)) changed++;
                    } catch (IOException | RuntimeException ignored) {
                        // Retain uncertain pending media for a later scan rather than deleting it.
                    }
                    continue;
                }
                String segmentId = item.name == null ? uri : item.name;
                MotionMediaIndex.Record record = new MotionMediaIndex.Record(uri, volume.id, item.name,
                        segmentId, item.recordedAtMs, 0, item.width, item.height, MotionMediaIndex.COMPLETE);
                record.durationMs = item.durationMs;
                record.sizeBytes = item.sizeBytes;
                try { index.put(record); changed++; }
                catch (IOException ignored) { }
            }
        }
        return changed;
    }

    public List<Entry> query(Query query) {
        Query actual = query == null ? Query.recent() : query;
        ArrayList<Entry> result = new ArrayList<>();
        Map<String, MotionStorage.Volume> volumes = volumeMap();
        for (MotionMediaIndex.Record record : index.records()) {
            if (!matches(actual, record)) continue;
            MotionStorage.Volume volume = volumes.get(record.volumeId);
            boolean mounted = volume != null && volume.mounted;
            Uri uri = Uri.parse(record.uri);
            boolean available = mounted && storage.isOwnedMedia(volume, uri);
            Status status = status(record.status);
            if (!mounted) status = Status.UNAVAILABLE;
            ArrayList<Marker> markers = new ArrayList<>();
            for (MotionMediaIndex.Marker marker : record.markers) {
                markers.add(new Marker(marker.eventId, marker.wallTimeMs, marker.elapsedRealtimeMs,
                        marker.endWallTimeMs, marker.endElapsedRealtimeMs,
                        marker.elapsedRealtimeMs - record.startElapsedRealtimeMs, marker.level));
            }
            result.add(new Entry(uri, record.name, record.volumeId, record.segmentId,
                    record.recordedAtMs, record.durationMs, record.sizeBytes, status, markers,
                    available, record.protectedFile));
        }
        Comparator<Entry> order = Comparator.comparingLong(entry -> entry.recordedAtMillis);
        if (actual.sort == Sort.NEWEST_FIRST) order = order.reversed();
        result.sort(order.thenComparing(entry -> entry.contentUri.toString()));
        return result;
    }

    /** Aggregates only entries in the supplied interval/volume, using the device-local calendar date. */
    public List<Day> queryDays(Query query) {
        Query actual = query == null ? Query.recent() : query;
        TreeMap<LocalDate, MutableDay> days = new TreeMap<>();
        ZoneId zone = ZoneId.systemDefault();
        for (Entry entry : query(actual)) {
            LocalDate date = Instant.ofEpochMilli(entry.recordedAtMillis).atZone(zone).toLocalDate();
            MutableDay day = days.computeIfAbsent(date, MutableDay::new);
            day.entryCount++;
            day.durationMs += entry.durationMs;
            day.sizeBytes += entry.sizeBytes;
            for (Marker marker : entry.markers) {
                String key = marker.eventId.isEmpty()
                        ? entry.contentUri + "|" + marker.elapsedRealtimeMs + "|" + marker.level
                        : marker.eventId;
                if (day.events.add(key)) {
                    if (marker.level == 1) day.yellow++;
                    else if (marker.level == 2) day.red++;
                }
            }
        }
        ArrayList<Day> result = new ArrayList<>();
        for (MutableDay day : days.values()) result.add(day.toDay());
        if (actual.sort == Sort.NEWEST_FIRST) Collections.reverse(result);
        return result;
    }

    public Stats queryStats() { return queryStats(MotionConfig.read(context)); }

    public Stats queryStats(MotionConfig config) {
        MotionConfig actual = config == null ? MotionConfig.read(context) : config;
        List<MotionStorage.Volume> volumes = storage.volumes();
        ArrayList<VolumeStats> result = new ArrayList<>();
        VolumeStats primary = null;
        VolumeStats secondary = null;
        String selectedId = selectedVolumeId(actual.primarySd);
        for (MotionStorage.Volume volume : volumes) {
            long own = storage.queryOwnedBytes(volume);
            long oem = storage.queryRecorderBytes(volume);
            long total = volume.totalBytes;
            long free = volume.freeBytes;
            long other = -1;
            long ownAllowance = 0;
            long oemAllowance = 0;
            long unallocated = 0;
            if (total >= 0 && free >= 0 && own >= 0 && oem >= 0) {
                MotionStoragePolicy.Parts parts = MotionStoragePolicy.calculate(total, free, own,
                        limitBytes(volume, actual), canRecordHere(volume, actual, selectedId),
                        keepFreeBytes(volume, actual), oem,
                        storage.oemCleanupEnabled(actual) ? actual.oemLimitGb * MotionStoragePolicy.GB : null);
                other = parts.other;
                ownAllowance = parts.allowance;
                oemAllowance = parts.oemAllowance;
                unallocated = parts.unallocated;
            } else if (total >= 0 && free >= 0 && own >= 0 && oem < 0
                    && !storage.oemCleanupEnabled(actual)) {
                MotionStoragePolicy.Parts parts = MotionStoragePolicy.calculate(total, free, own,
                        limitBytes(volume, actual), canRecordHere(volume, actual, selectedId),
                        keepFreeBytes(volume, actual), 0, null);
                ownAllowance = parts.allowance;
                unallocated = parts.unallocated;
            } else if (total >= 0 && free >= 0 && own >= 0 && oem < 0) {
                other = -1; // Without permission, Recorder and non-OEM bytes cannot honestly be separated.
            }
            VolumeStats stats = new VolumeStats(volume.id, volume.label, volume.primary, volume.removable,
                    volume.mounted, volume.readOnly, total, free, other, own, oem,
                    ownAllowance, oemAllowance, unallocated);
            result.add(stats);
            if (volume.primary) primary = stats;
            else if (secondary == null && volume.removable) secondary = stats;
        }
        return new Stats(result, primary, secondary);
    }

    public DeleteResult deleteSelected(Collection<Entry> selected) {
        if (selected == null || selected.isEmpty()) return new DeleteResult(0, 0, 0);
        int deleted = 0, failed = 0, skipped = 0;
        Map<String, MotionStorage.Volume> volumes = volumeMap();
        for (Entry entry : selected) {
            if (entry == null || entry.contentUri == null || entry.protectedFile
                    || (entry.status != Status.COMPLETE && entry.status != Status.INTERRUPTED
                    && entry.status != Status.UNKNOWN)) {
                skipped++;
                continue;
            }
            MotionMediaIndex.Record current = index.find(entry.contentUri.toString());
            if (current == null || MotionMediaIndex.RECORDING.equals(current.status)
                    || current.protectedFile || !current.segmentId.equals(entry.segmentId)) {
                skipped++;
                continue;
            }
            if (MotionMediaLease.isHeld(context, current.uri)) {
                skipped++;
                continue;
            }
            MotionStorage.Volume volume = volumes.get(current.volumeId);
            if (volume == null || !volume.mounted || volume.readOnly
                    || !storage.isOwnedMedia(volume, entry.contentUri)) {
                failed++;
                continue;
            }
            try {
                if (storage.deleteOwned(volume, entry.contentUri) == 1) {
                    index.remove(current.uri);
                    deleted++;
                } else failed++;
            } catch (IOException | RuntimeException e) {
                failed++;
            }
        }
        return new DeleteResult(deleted, failed, skipped);
    }

    String selectedVolumeId(boolean sd) {
        List<MotionStorage.Volume> volumes = storage.volumes();
        if (!sd) {
            for (MotionStorage.Volume volume : volumes) if (volume.primary) return volume.id;
        } else {
            for (MotionStorage.Volume volume : volumes) if (volume.removable && volume.mounted) return volume.id;
        }
        return null;
    }

    MotionMediaIndex index() { return index; }
    MotionStorage storage() { return storage; }

    /** Deletes only oldest indexed, complete, unprotected own clips until this segment's byte budget fits. */
    StorageDecision prepareOwnSpace(String volumeId, MotionConfig config, long requiredBytes) {
        return prepareOwnSpace(volumeId, config, requiredBytes, -1, 0);
    }

    StorageDecision prepareOwnSpace(String volumeId, MotionConfig config, long requiredBytes,
            long baseOwnBytes, long activeBytes) {
        MotionConfig actual = config == null ? MotionConfig.read(context) : config;
        MotionStorage.Volume volume = storage.findVolume(volumeId);
        if (volume == null || !volume.mounted || volume.readOnly)
            return new StorageDecision(false, -1, -1, "motion_volume_not_writable");
        String selectedId = selectedVolumeId(actual.primarySd);
        if (!canRecordHere(volume, actual, selectedId))
            return new StorageDecision(false, -1, -1, "motion_volume_not_selected_or_fallback");
        long needed = Math.max(0, requiredBytes);
        java.util.HashSet<String> attempted = new java.util.HashSet<>();
        boolean deletionFailed = false;
        while (true) {
            MotionStorage.Volume currentVolume = storage.findVolume(volumeId);
            if (currentVolume == null || !currentVolume.mounted || currentVolume.readOnly)
                return new StorageDecision(false, -1, -1, "motion_volume_not_writable");
            long own = storage.queryOwnedBytes(currentVolume);
            if (own < 0) return new StorageDecision(false, -1, -1, "motion_archive_accounting_unavailable");
            if (baseOwnBytes >= 0 && activeBytes > 0)
                own = Math.max(own, Math.min(Math.max(0, currentVolume.totalBytes), baseOwnBytes + activeBytes));
            long oem = storage.queryRecorderBytes(currentVolume);
            if (oem < 0 && storage.oemCleanupEnabled(actual))
                return new StorageDecision(false, own, -1, "oem_recorder_accounting_unavailable");
            MotionStoragePolicy.Parts parts = MotionStoragePolicy.calculate(currentVolume.totalBytes,
                    currentVolume.freeBytes, own, limitBytes(currentVolume, actual), true,
                    keepFreeBytes(currentVolume, actual), Math.max(0, oem),
                    storage.oemCleanupEnabled(actual) ? actual.oemLimitGb * MotionStoragePolicy.GB : null);
            if (parts.allowance >= needed)
                return new StorageDecision(true, own, parts.allowance, null);

            MotionMediaIndex.Record candidate = null;
            for (MotionMediaIndex.Record record : index.records()) {
                if (!currentVolume.id.equals(record.volumeId) || !MotionMediaIndex.COMPLETE.equals(record.status)
                        || record.protectedFile || attempted.contains(record.uri)
                        || MotionMediaLease.isHeld(context, record.uri)) continue;
                Uri uri = Uri.parse(record.uri);
                if (!storage.isOwnedMedia(currentVolume, uri)) continue;
                if (candidate == null || record.recordedAtMs < candidate.recordedAtMs) candidate = record;
            }
            if (candidate == null) {
                if (deletionFailed)
                    return new StorageDecision(false, own, parts.allowance, "motion_archive_retention_delete_failed");
                return new StorageDecision(false, own, parts.allowance,
                        storageFailureReason(currentVolume, actual, own, parts.allowance, needed));
            }
            attempted.add(candidate.uri);
            if (storage.deleteOwned(currentVolume, Uri.parse(candidate.uri)) != 1) {
                deletionFailed = true;
                continue;
            }
            try {
                index.remove(candidate.uri);
            } catch (IOException failure) {
                return new StorageDecision(false, own, parts.allowance, "motion_archive_index_update_failed");
            }
        }
    }

    /** Remaining policy allowance for an active segment, including its bytes if MediaStore metadata lags. */
    long currentOwnGrowthAllowance(String volumeId, MotionConfig config, long baseOwnBytes, long activeBytes) {
        MotionConfig actual = config == null ? MotionConfig.read(context) : config;
        MotionStorage.Volume volume = storage.findVolume(volumeId);
        if (volume == null || !volume.mounted || volume.readOnly || volume.totalBytes < 0 || volume.freeBytes < 0)
            return -1;
        long own = storage.queryOwnedBytes(volume);
        if (own < 0) return -1;
        if (baseOwnBytes >= 0 && activeBytes > 0)
            own = Math.max(own, Math.min(volume.totalBytes, baseOwnBytes + activeBytes));
        long oem = storage.queryRecorderBytes(volume);
        if (oem < 0) {
            if (storage.oemCleanupEnabled(actual)) return -1;
            oem = 0;
        }
        String selectedId = selectedVolumeId(actual.primarySd);
        MotionStoragePolicy.Parts parts = MotionStoragePolicy.calculate(volume.totalBytes, volume.freeBytes,
                own, limitBytes(volume, actual), canRecordHere(volume, actual, selectedId),
                keepFreeBytes(volume, actual), Math.max(0, oem),
                storage.oemCleanupEnabled(actual) ? actual.oemLimitGb * MotionStoragePolicy.GB : null);
        return parts.allowance;
    }

    private static String storageFailureReason(MotionStorage.Volume volume, MotionConfig config,
            long ownBytes, long allowance, long requiredBytes) {
        long floor = keepFreeBytes(volume, config);
        if (volume.totalBytes < 0 || volume.freeBytes < 0) return "motion_free_space_unavailable";
        if (volume.freeBytes <= floor) return "motion_keep_free_floor_reached";
        if (ownBytes >= limitBytes(volume, config)) return "motion_archive_limit_reached";
        if (allowance < requiredBytes) return "motion_shared_growth_allowance_insufficient";
        return "motion_storage_unavailable";
    }

    private Map<String, MotionStorage.Volume> volumeMap() {
        HashMap<String, MotionStorage.Volume> result = new HashMap<>();
        for (MotionStorage.Volume volume : storage.volumes()) result.put(volume.id, volume);
        return result;
    }

    private static boolean matches(Query query, MotionMediaIndex.Record record) {
        return (query.volumeId == null || query.volumeId.equals(record.volumeId))
                && (query.startInclusiveMs == null || record.recordedAtMs >= query.startInclusiveMs)
                && (query.endExclusiveMs == null || record.recordedAtMs < query.endExclusiveMs);
    }

    private static Status status(String value) {
        if (MotionMediaIndex.RECORDING.equals(value)) return Status.RECORDING;
        if (MotionMediaIndex.COMPLETE.equals(value)) return Status.COMPLETE;
        if (MotionMediaIndex.INTERRUPTED.equals(value)) return Status.INTERRUPTED;
        return Status.UNKNOWN;
    }

    private static long limitBytes(MotionStorage.Volume volume, MotionConfig config) {
        long limitGb = volume.primary ? config.internalLimitGb : config.sdLimitGb;
        return limitGb * MotionStoragePolicy.GB;
    }

    private static Long keepFreeBytes(MotionStorage.Volume volume, MotionConfig config) {
        boolean enabled = volume.primary ? config.internalKeepFree : config.sdKeepFree;
        int freeGb = volume.primary ? config.internalFreeGb : config.sdFreeGb;
        return enabled ? freeGb * MotionStoragePolicy.GB : 0L;
    }

    private static boolean canRecordHere(MotionStorage.Volume volume, MotionConfig config, String selectedId) {
        if (!config.recordingEnabled || !volume.mounted || volume.readOnly) return false;
        if (volume.id.equals(selectedId)) return true;
        if (!config.fallbackEnabled) return false;
        if (selectedId != null) return volume.primary != "primary".equals(selectedId);
        return config.primarySd && volume.primary;
    }

    private MediaFacts inspect(String uriValue) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            Uri uri = Uri.parse(uriValue);
            if ("file".equals(uri.getScheme())) extractor.setDataSource(uri.getPath());
            else extractor.setDataSource(context, uri, null);
            boolean video = false;
            long duration = 0;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                android.media.MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) video = true;
                if (format.containsKey(android.media.MediaFormat.KEY_DURATION))
                    duration = Math.max(duration, format.getLong(android.media.MediaFormat.KEY_DURATION) / 1000L);
            }
            return new MediaFacts(video, duration);
        } catch (IOException | RuntimeException invalid) {
            return null;
        } finally {
            extractor.release();
        }
    }

    private long sizeOf(Uri uri) {
        if ("file".equals(uri.getScheme())) {
            File file = new File(uri.getPath());
            return file.isFile() ? file.length() : 0;
        }
        try (android.content.res.AssetFileDescriptor descriptor = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            return descriptor == null ? 0 : descriptor.getLength();
        } catch (IOException | RuntimeException unavailable) { return 0; }
    }

    private static final class MediaFacts {
        final boolean hasVideo;
        final long durationMs;
        MediaFacts(boolean hasVideo, long durationMs) { this.hasVideo = hasVideo; this.durationMs = durationMs; }
    }

    private static final class MutableDay {
        final LocalDate date;
        int entryCount, yellow, red;
        long durationMs, sizeBytes;
        final java.util.HashSet<String> events = new java.util.HashSet<>();
        MutableDay(LocalDate date) { this.date = date; }
        Day toDay() { return new Day(date, entryCount, durationMs, sizeBytes, yellow, red); }
    }
}
