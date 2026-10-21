package com.byd.extend.remote;

import android.content.Context;
import android.os.ParcelFileDescriptor;

import com.byd.extend.MotionMediaLibrary;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Read-only Motion archive access from the separate shell-UID viewing helper. */
public final class CameraViewerArchive implements Closeable {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US);
    private static final DateTimeFormatter TITLE = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US);

    private final MotionMediaLibrary library;
    private final Set<Playback> playbacks = new HashSet<>();
    private boolean closed;

    private CameraViewerArchive(MotionMediaLibrary library) {
        this.library = library;
    }

    /** Uses the viewing helper's shell-attributed context and the writer helper's shared state root. */
    public static CameraViewerArchive create(Context shellRuntimeContext, int verifiedOwnerUid)
            throws IOException {
        try {
            return new CameraViewerArchive(
                    MotionMediaLibrary.openShellArchive(shellRuntimeContext, verifiedOwnerUid));
        } catch (Exception failure) {
            throw new IOException("Cannot open helper-owned Motion archive", failure);
        }
    }

    /** Returns all currently owned indexed recordings so the browser can switch months locally. */
    public synchronized String catalog() throws IOException {
        ensureOpen();
        List<MotionMediaLibrary.ArchiveItem> items = library.queryArchiveIndex();
        ZoneId zone = ZoneId.systemDefault();
        JSONArray recordings = new JSONArray();
        String latestDay = null;
        try {
            for (MotionMediaLibrary.ArchiveItem item : items) {
                MotionMediaLibrary.Entry entry = item.entry;
                java.time.ZonedDateTime local = Instant.ofEpochMilli(entry.recordedAtMillis).atZone(zone);
                String day = DAY.format(local);
                if (latestDay == null) latestDay = day;
                JSONObject recording = new JSONObject();
                recording.put("id", item.id);
                recording.put("name", "Motion_" + TITLE.format(local));
                recording.put("startEpochMs", entry.recordedAtMillis);
                recording.put("day", day);
                recording.put("time", TIME.format(local));
                recording.put("durationMs", entry.durationMs);
                recording.put("storage", item.storage);
                recording.put("cameraOrder", encodeCameraOrder(entry.cameraOrder));
                recording.put("events", encodeEvents(entry.markers));
                int[] counts = countEvents(item.id, entry.markers);
                recording.put("yellowCount", counts[0]);
                recording.put("redCount", counts[1]);
                recording.put("thumbnailUrl", "/archive/thumbnail/" + item.id);
                recording.put("videoUrl", "/archive/video/" + item.id);
                recordings.put(recording);
            }
            JSONObject result = new JSONObject();
            result.put("version", 1);
            result.put("latestDay", latestDay == null ? JSONObject.NULL : latestDay);
            result.put("recordings", recordings);
            return result.toString();
        } catch (JSONException failure) {
            throw new IOException("Cannot encode Motion archive catalog", failure);
        }
    }

    /** Opens only a verified private thumbnail already prepared by the Motion helper. */
    public synchronized ParcelFileDescriptor openThumbnail(String id) throws IOException {
        ensureOpen();
        return library.openArchiveThumbnail(id);
    }

    /** Starts a playback lease that remains held across all subsequent HTTP Range requests. */
    public synchronized Playback openVideo(String id) throws IOException {
        ensureOpen();
        MotionMediaLibrary.ArchivePlayback media = library.openArchiveVideo(id);
        Playback playback = new Playback(this, media);
        playbacks.add(playback);
        return playback;
    }

    private void release(Playback playback) {
        synchronized (this) { playbacks.remove(playback); }
    }

    private synchronized void ensureOpen() throws IOException {
        if (closed) throw new IOException("Motion archive is closed");
    }

    @Override public void close() throws IOException {
        ArrayList<Playback> active;
        synchronized (this) {
            if (closed) return;
            closed = true;
            active = new ArrayList<>(playbacks);
            playbacks.clear();
        }
        IOException failure = null;
        for (Playback playback : active) {
            try { playback.closeFromArchive(); }
            catch (IOException cleanupFailure) {
                if (failure == null) failure = cleanupFailure;
                else failure.addSuppressed(cleanupFailure);
            }
        }
        library.close();
        if (failure != null) throw failure;
    }

    private static JSONArray encodeCameraOrder(int[] order) {
        JSONArray encoded = new JSONArray();
        if (order != null) for (int camera : order) encoded.put(camera);
        return encoded;
    }

    private static JSONArray encodeEvents(List<MotionMediaLibrary.Marker> markers)
            throws JSONException {
        JSONArray events = new JSONArray();
        for (MotionMediaLibrary.Marker marker : markers) {
            JSONObject event = new JSONObject();
            event.put("startMs", marker.positionMs);
            event.put("endMs", marker.positionMs + marker.durationMs);
            event.put("level", marker.level == 2 ? "red" : "yellow");
            events.put(event);
        }
        return events;
    }

    private static int[] countEvents(String id, List<MotionMediaLibrary.Marker> markers) {
        Set<String> counted = new HashSet<>();
        int[] counts = new int[2];
        for (MotionMediaLibrary.Marker marker : markers) {
            String identity = marker.eventId.isEmpty()
                    ? id + "|" + marker.positionMs + "|" + marker.durationMs + "|" + marker.level
                    : marker.eventId + "|" + marker.level;
            if (!counted.add(identity)) continue;
            if (marker.level == 1) counts[0]++;
            else if (marker.level == 2) counts[1]++;
        }
        return counts;
    }

    public static final class Playback implements Closeable {
        private final CameraViewerArchive archive;
        private final MotionMediaLibrary.ArchivePlayback media;
        private final Set<RangeFile> files = new HashSet<>();
        private boolean closed;

        private Playback(CameraViewerArchive archive, MotionMediaLibrary.ArchivePlayback media) {
            this.archive = archive;
            this.media = media;
        }

        public RangeFile openDescriptor() throws IOException {
            synchronized (this) {
                if (closed) throw new IOException("Motion archive playback is closed");
            }
            MotionMediaLibrary.ArchiveDescriptor opened = media.openDescriptor();
            RangeFile file = new RangeFile(this, opened);
            synchronized (this) {
                if (!closed) {
                    files.add(file);
                    return file;
                }
            }
            try { file.close(); }
            catch (IOException cleanupFailure) {
                throw new IOException("Motion archive playback closed during range open", cleanupFailure);
            }
            throw new IOException("Motion archive playback is closed");
        }

        private void release(RangeFile file) {
            synchronized (this) { files.remove(file); }
        }

        @Override public void close() throws IOException {
            closeInternal(true);
        }

        private void closeFromArchive() throws IOException {
            closeInternal(false);
        }

        private void closeInternal(boolean releaseFromArchive) throws IOException {
            ArrayList<RangeFile> active;
            synchronized (this) {
                if (closed) return;
                closed = true;
                active = new ArrayList<>(files);
                files.clear();
            }
            IOException failure = null;
            for (RangeFile file : active) {
                try { file.closeFromPlayback(); }
                catch (IOException cleanupFailure) {
                    if (failure == null) failure = cleanupFailure;
                    else failure.addSuppressed(cleanupFailure);
                }
            }
            try { media.close(); }
            catch (IOException cleanupFailure) {
                if (failure == null) failure = cleanupFailure;
                else failure.addSuppressed(cleanupFailure);
            }
            if (releaseFromArchive) archive.release(this);
            if (failure != null) throw failure;
        }
    }

    public static final class RangeFile implements Closeable {
        public final ParcelFileDescriptor descriptor;
        public final long contentLength;
        private final Playback playback;
        private final MotionMediaLibrary.ArchiveDescriptor media;
        private boolean closed;

        private RangeFile(Playback playback, MotionMediaLibrary.ArchiveDescriptor media) {
            this.playback = playback;
            this.media = media;
            this.descriptor = media.descriptor;
            this.contentLength = media.contentLength;
        }

        @Override public void close() throws IOException {
            synchronized (this) {
                if (closed) return;
                closed = true;
                playback.release(this);
                media.close();
            }
        }

        private void closeFromPlayback() throws IOException {
            synchronized (this) {
                if (closed) return;
                closed = true;
                media.close();
            }
        }
    }
}
