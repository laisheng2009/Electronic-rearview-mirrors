package com.byd.extend;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

final class MotionMediaIndex {
    static final String RECORDING = "recording";
    static final String COMPLETE = "complete";
    static final String INTERRUPTED = "interrupted";

    static final class Marker {
        final String eventId;
        final long elapsedRealtimeMs;
        final long wallTimeMs;
        final long endElapsedRealtimeMs;
        final long endWallTimeMs;
        final int level;

        Marker(long elapsedRealtimeMs, long wallTimeMs, int level) {
            this("event-" + level + "-" + elapsedRealtimeMs, elapsedRealtimeMs, wallTimeMs,
                    elapsedRealtimeMs, wallTimeMs, level);
        }

        Marker(String eventId, long elapsedRealtimeMs, long wallTimeMs,
                long endElapsedRealtimeMs, long endWallTimeMs, int level) {
            if (level != 1 && level != 2) throw new IllegalArgumentException("level must be 1 or 2");
            this.eventId = eventId == null ? "" : eventId;
            this.elapsedRealtimeMs = elapsedRealtimeMs;
            this.wallTimeMs = wallTimeMs;
            this.endElapsedRealtimeMs = Math.max(elapsedRealtimeMs, endElapsedRealtimeMs);
            this.endWallTimeMs = Math.max(wallTimeMs, endWallTimeMs);
            this.level = level;
        }

        Marker merge(Marker newer) {
            long start = Math.min(elapsedRealtimeMs, newer.elapsedRealtimeMs);
            long startWall = elapsedRealtimeMs <= newer.elapsedRealtimeMs ? wallTimeMs : newer.wallTimeMs;
            long end = Math.max(endElapsedRealtimeMs, newer.endElapsedRealtimeMs);
            long endWall = endElapsedRealtimeMs >= newer.endElapsedRealtimeMs ? endWallTimeMs : newer.endWallTimeMs;
            return new Marker(eventId, start, startWall, end, endWall, level);
        }
    }

    static final class Record {
        final String uri;
        final String stagingUri;
        final String volumeId;
        final String name;
        final String segmentId;
        final long recordedAtMs;
        final long startElapsedRealtimeMs;
        final int width;
        final int height;
        long durationMs;
        long sizeBytes;
        String status;
        boolean protectedFile;
        final ArrayList<Marker> markers = new ArrayList<>();

        Record(String uri, String volumeId, String name, String segmentId, long recordedAtMs,
                long startElapsedRealtimeMs, int width, int height, String status) {
            this(uri, null, volumeId, name, segmentId, recordedAtMs, startElapsedRealtimeMs,
                    width, height, status);
        }

        Record(String uri, String stagingUri, String volumeId, String name, String segmentId, long recordedAtMs,
                long startElapsedRealtimeMs, int width, int height, String status) {
            this.uri = uri;
            this.stagingUri = stagingUri;
            this.volumeId = volumeId;
            this.name = name;
            this.segmentId = segmentId;
            this.recordedAtMs = recordedAtMs;
            this.startElapsedRealtimeMs = startElapsedRealtimeMs;
            this.width = width;
            this.height = height;
            this.status = status;
        }

        Record copy() {
            Record copy = new Record(uri, stagingUri, volumeId, name, segmentId, recordedAtMs,
                    startElapsedRealtimeMs, width, height, status);
            copy.durationMs = durationMs;
            copy.sizeBytes = sizeBytes;
            copy.protectedFile = protectedFile;
            copy.markers.addAll(markers);
            return copy;
        }
    }

    private static final int VERSION = 1;
    private static final Map<String, MotionMediaIndex> SHARED = new HashMap<>();
    private final File file;
    private final ArrayList<Record> records = new ArrayList<>();
    private String legacyImportDigest;

    MotionMediaIndex(File file) throws IOException {
        this.file = file;
        load();
    }

    static synchronized MotionMediaIndex shared(File file) throws IOException {
        String key = file.getAbsolutePath();
        MotionMediaIndex index = SHARED.get(key);
        if (index == null) {
            index = new MotionMediaIndex(file);
            SHARED.put(key, index);
        }
        return index;
    }

    synchronized List<Record> records() {
        ArrayList<Record> copy = new ArrayList<>(records.size());
        for (Record record : records) copy.add(record.copy());
        return Collections.unmodifiableList(copy);
    }

    synchronized Record find(String uri) {
        int index = indexOf(uri);
        return index < 0 ? null : records.get(index).copy();
    }

    synchronized void put(Record record) throws IOException {
        int index = indexOf(record.uri);
        if (index < 0) records.add(record.copy());
        else records.set(index, record.copy());
        save();
    }

    synchronized boolean addMarker(String uri, Marker marker) throws IOException {
        return upsertMarker(uri, marker);
    }

    synchronized boolean upsertMarker(String uri, Marker marker) throws IOException {
        int index = indexOf(uri);
        if (index < 0) return false;
        Record record = records.get(index);
        for (int i = 0; i < record.markers.size(); i++) {
            Marker existing = record.markers.get(i);
            if (existing.eventId.equals(marker.eventId) && existing.level == marker.level) {
                Marker merged = existing.merge(marker);
                if (merged.endElapsedRealtimeMs == existing.endElapsedRealtimeMs
                        && merged.elapsedRealtimeMs == existing.elapsedRealtimeMs) return true;
                record.markers.set(i, merged);
                save();
                return true;
            }
        }
        record.markers.add(marker);
        record.markers.sort(java.util.Comparator.comparingLong(item -> item.elapsedRealtimeMs));
        save();
        return true;
    }

    synchronized boolean update(String uri, String status, long durationMs, long sizeBytes) throws IOException {
        int index = indexOf(uri);
        if (index < 0) return false;
        Record record = records.get(index);
        record.status = status;
        record.durationMs = Math.max(0, durationMs);
        record.sizeBytes = Math.max(0, sizeBytes);
        save();
        return true;
    }

    synchronized boolean finishRecording(String uri, String status, long durationMs, long sizeBytes) throws IOException {
        int index = indexOf(uri);
        if (index < 0) return false;
        Record record = records.get(index);
        long start = record.startElapsedRealtimeMs;
        long end = start + Math.max(0, durationMs);
        ArrayList<Marker> captured = new ArrayList<>();
        for (Marker marker : record.markers) {
            if (marker.elapsedRealtimeMs >= end || marker.endElapsedRealtimeMs < start) continue;
            long boundedStart = Math.max(start, marker.elapsedRealtimeMs);
            long boundedEnd = Math.min(end, marker.endElapsedRealtimeMs);
            captured.add(new Marker(marker.eventId, boundedStart,
                    marker.wallTimeMs + boundedStart - marker.elapsedRealtimeMs, boundedEnd,
                    marker.endWallTimeMs - (marker.endElapsedRealtimeMs - boundedEnd), marker.level));
        }
        record.markers.clear();
        record.markers.addAll(captured);
        return update(uri, status, durationMs, sizeBytes);
    }

    synchronized boolean remove(String uri) throws IOException {
        int index = indexOf(uri);
        if (index < 0) return false;
        records.remove(index);
        save();
        return true;
    }

    synchronized boolean hasImportedLegacyDigest(String digest) {
        return digest != null && digest.equals(legacyImportDigest);
    }

    /** Merges validated legacy rows without replacing worker-owned state or losing markers. */
    synchronized void mergeLegacyRecords(List<Record> imported, String digest,
            boolean fullyResolved) throws IOException {
        boolean changed = false;
        for (Record incoming : imported) {
            if (incoming == null || incoming.uri == null || incoming.uri.isEmpty()) continue;
            int index = indexOf(incoming.uri);
            if (index < 0) {
                records.add(incoming.copy());
                changed = true;
                continue;
            }
            Record existing = records.get(index);
            if (incoming.protectedFile && !existing.protectedFile) {
                existing.protectedFile = true;
                changed = true;
            }
            for (Marker marker : incoming.markers) {
                int markerIndex = markerIndex(existing.markers, marker.eventId, marker.level);
                if (markerIndex < 0) {
                    existing.markers.add(marker);
                    changed = true;
                } else {
                    Marker current = existing.markers.get(markerIndex);
                    Marker merged = current.merge(marker);
                    if (!sameMarker(current, merged)) {
                        existing.markers.set(markerIndex, merged);
                        changed = true;
                    }
                }
            }
            existing.markers.sort(java.util.Comparator.comparingLong(item -> item.elapsedRealtimeMs));
        }
        if (fullyResolved && digest != null && !digest.equals(legacyImportDigest)) {
            legacyImportDigest = digest;
            changed = true;
        }
        if (changed) save();
    }

    static List<Record> parseLegacySnapshot(InputStream input, int maxBytes) throws IOException {
        byte[] bytes = readBounded(input, maxBytes);
        try {
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (root.getInt("version") != VERSION) throw new IOException("Unsupported Motion index version");
            JSONArray rows = root.getJSONArray("records");
            ArrayList<Record> parsed = new ArrayList<>(rows.length());
            for (int i = 0; i < rows.length(); i++) parsed.add(readRecord(rows.getJSONObject(i)));
            return Collections.unmodifiableList(parsed);
        } catch (JSONException e) {
            throw new IOException("Invalid legacy Motion index", e);
        }
    }

    private static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        if (input == null || maxBytes <= 0) throw new IOException("Invalid Motion index input");
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > maxBytes - total) throw new IOException("Legacy Motion index exceeds size limit");
            output.write(buffer, 0, count);
            total += count;
        }
        return output.toByteArray();
    }

    private int indexOf(String uri) {
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).uri.equals(uri)) return i;
        }
        return -1;
    }

    private void load() throws IOException {
        if (!file.isFile()) return;
        try {
            JSONObject root = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            if (root.getInt("version") != VERSION) throw new IOException("Unsupported Motion index version");
            legacyImportDigest = root.optString("legacyImportDigest", null);
            JSONArray rows = root.getJSONArray("records");
            for (int i = 0; i < rows.length(); i++) records.add(readRecord(rows.getJSONObject(i)));
        } catch (JSONException e) {
            throw new IOException("Invalid Motion index", e);
        }
    }

    private void save() throws IOException {
        JSONObject root = new JSONObject();
        JSONArray rows = new JSONArray();
        try {
            for (Record record : records) rows.put(writeRecord(record));
            root.put("version", VERSION);
            root.put("records", rows);
            if (legacyImportDigest != null) root.put("legacyImportDigest", legacyImportDigest);
        } catch (JSONException e) {
            throw new IOException("Cannot encode Motion index", e);
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Cannot create Motion index directory");
        }
        File temp = new File(file.getPath() + ".tmp");
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(bytes);
            output.getFD().sync();
        }
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static JSONObject writeRecord(Record record) throws JSONException {
        JSONObject row = new JSONObject();
        JSONArray markers = new JSONArray();
        for (Marker marker : record.markers) {
            JSONObject item = new JSONObject();
            item.put("elapsedRealtimeMs", marker.elapsedRealtimeMs);
            item.put("wallTimeMs", marker.wallTimeMs);
            item.put("eventId", marker.eventId);
            item.put("endElapsedRealtimeMs", marker.endElapsedRealtimeMs);
            item.put("endWallTimeMs", marker.endWallTimeMs);
            item.put("level", marker.level);
            markers.put(item);
        }
        row.put("uri", record.uri);
        if (record.stagingUri != null) row.put("stagingUri", record.stagingUri);
        row.put("volumeId", record.volumeId);
        row.put("name", record.name);
        row.put("segmentId", record.segmentId);
        row.put("recordedAtMs", record.recordedAtMs);
        row.put("startElapsedRealtimeMs", record.startElapsedRealtimeMs);
        row.put("durationMs", record.durationMs);
        row.put("sizeBytes", record.sizeBytes);
        row.put("width", record.width);
        row.put("height", record.height);
        row.put("status", record.status);
        row.put("protected", record.protectedFile);
        row.put("markers", markers);
        return row;
    }

    private static Record readRecord(JSONObject row) throws JSONException, IOException {
        Record record = new Record(row.getString("uri"), row.optString("stagingUri", null),
                row.getString("volumeId"), row.getString("name"),
                row.getString("segmentId"), row.getLong("recordedAtMs"),
                row.getLong("startElapsedRealtimeMs"), row.getInt("width"), row.getInt("height"),
                row.getString("status"));
        record.durationMs = row.getLong("durationMs");
        record.sizeBytes = row.getLong("sizeBytes");
        record.protectedFile = row.getBoolean("protected");
        JSONArray markers = row.getJSONArray("markers");
        for (int i = 0; i < markers.length(); i++) {
            JSONObject item = markers.getJSONObject(i);
            long startElapsed = item.getLong("elapsedRealtimeMs");
            long startWall = item.getLong("wallTimeMs");
            int level = item.getInt("level");
            record.markers.add(new Marker(item.optString("eventId", "event-" + level + "-" + startElapsed),
                    startElapsed, startWall, item.optLong("endElapsedRealtimeMs", startElapsed),
                    item.optLong("endWallTimeMs", startWall), level));
        }
        return record;
    }

    private static int markerIndex(List<Marker> markers, String eventId, int level) {
        for (int i = 0; i < markers.size(); i++) {
            Marker marker = markers.get(i);
            if (marker.level == level && marker.eventId.equals(eventId)) return i;
        }
        return -1;
    }

    private static boolean sameMarker(Marker left, Marker right) {
        return left.elapsedRealtimeMs == right.elapsedRealtimeMs
                && left.wallTimeMs == right.wallTimeMs
                && left.endElapsedRealtimeMs == right.endElapsedRealtimeMs
                && left.endWallTimeMs == right.endWallTimeMs;
    }
}
