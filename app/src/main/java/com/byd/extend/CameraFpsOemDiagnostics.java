package com.byd.extend;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A short, bounded Logcat observation for one planned camera FPS reconnect. */
final class CameraFpsOemDiagnostics implements AutoCloseable {
    static final long MAX_WINDOW_MS = 30_000;
    static final int MAX_FRAGMENT_BYTES = 256 * 1024;
    private static final int MAX_LINE_CHARS = 4_096;
    private static final int MAX_BASELINE_CANDIDATES = 64;
    static String[] logcatCommand(long sinceWallMs) {
        // Include events emitted while the child process is starting.
        String since = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date(sinceWallMs));
        return new String[] {
                "/system/bin/logcat", "-v", "epoch", "-T", since, "-s",
                "BMMCamDevice:D", "Hal3CameraCore:D", "NormalHal3CamDevice:D",
                "NormalCamClient:D", "CHI:D", "CHIUSECASE:D", "CHIMEMCPY:D"
        };
    }

    private final String cycleId;
    private final int cameraId;
    private final String cameraTag;
    private final int sourceIndex;
    private final int requestedFps;
    private final int helperPid;
    private final BiConsumer<String, Object[]> eventSink;
    private final EvidenceWindow evidence;
    private final long startedAtNanos = System.nanoTime();
    private final long startedAtWallMs = System.currentTimeMillis();
    private volatile Process process;
    private volatile Thread readerThread;
    private volatile Thread deadlineThread;
    private volatile String observerStatus = "starting";
    private volatile String stopReason;
    private volatile boolean sinkFailed;
    private int fragmentBytes;
    private int droppedFragments;
    private int truncatedLines;
    private int readErrors;
    private boolean unexpectedExit;
    private boolean summarySent;

    static CameraFpsOemDiagnostics start(String cycleId, int cameraId, String cameraTag,
            int sourceIndex, int requestedFps, int helperPid,
            BiConsumer<String, Object[]> eventSink) {
        CameraFpsOemDiagnostics result = new CameraFpsOemDiagnostics(cycleId, cameraId,
                cameraTag, sourceIndex, requestedFps, helperPid, eventSink);
        result.startObserver();
        return result;
    }

    private CameraFpsOemDiagnostics(String cycleId, int cameraId, String cameraTag,
            int sourceIndex, int requestedFps, int helperPid,
            BiConsumer<String, Object[]> eventSink) {
        this.cycleId = cycleId;
        this.cameraId = cameraId;
        this.cameraTag = cameraTag;
        this.sourceIndex = sourceIndex;
        this.requestedFps = requestedFps;
        this.helperPid = helperPid;
        this.eventSink = eventSink;
        // No verified native physical/logical camera-ID mapping is available yet.
        evidence = new EvidenceWindow(-1, sourceIndex, requestedFps, startedAtWallMs);
    }

    private void startObserver() {
        try {
            process = new ProcessBuilder(logcatCommand(startedAtWallMs))
                    .redirectErrorStream(true).start();
            observerStatus = "running";
            emit("camera_fps_oem_observer", "status", observerStatus,
                    "window_limit_ms", MAX_WINDOW_MS);
            readerThread = new Thread(this::readLogcat, "camera-fps-oem-logcat");
            readerThread.setDaemon(true);
            readerThread.start();
            Thread deadline = new Thread(() -> {
                long remainingNanos = TimeUnit.MILLISECONDS.toNanos(MAX_WINDOW_MS)
                        - (System.nanoTime() - startedAtNanos);
                try {
                    if (remainingNanos > 0) TimeUnit.NANOSECONDS.sleep(remainingNanos);
                }
                catch (InterruptedException ignored) { return; }
                stop("window_limit_30s");
            }, "camera-fps-oem-deadline");
            deadlineThread = deadline;
            deadline.setDaemon(true);
            if (stopReason == null) deadline.start();
        } catch (IOException | RuntimeException error) {
            destroyProcess();
            observerStatus = "unavailable";
            stopReason = "start_failed";
            emit("camera_fps_oem_observer", "status", observerStatus,
                    "reason", error.getClass().getSimpleName());
            finishSummary();
        }
    }

    /** Emit the reconnect caller's lifecycle evidence with the same cycle identity. */
    void event(String stage, Object... fields) {
        long wallMs = System.currentTimeMillis();
        evidence.noteStage(stage, wallMs);
        Object[] all = new Object[fields.length + 4];
        all[0] = "stage"; all[1] = stage;
        all[2] = "event_wall_time_ms"; all[3] = wallMs;
        System.arraycopy(fields, 0, all, 4, fields.length);
        emit("camera_fps_oem_lifecycle", all);
    }

    /** Ends the observation at the caller's measurement boundary. */
    void finish() { stop("measurement_end"); }

    @Override public void close() { finish(); }

    private void stop(String reason) {
        synchronized (this) {
            if (stopReason != null) return;
            stopReason = reason;
            observerStatus = "stopped";
        }
        Thread deadline = deadlineThread;
        if (deadline != null && deadline != Thread.currentThread()) deadline.interrupt();
        destroyProcess();
        if (readerThread == null) finishSummary();
    }

    private void destroyProcess() {
        Process current = process;
        if (current == null) return;
        current.destroyForcibly();
        try { current.getInputStream().close(); }
        catch (IOException ignored) { }
    }

    private void readLogcat() {
        StringBuilder line = new StringBuilder(256);
        boolean lineTruncated = false;
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            int value;
            while ((value = input.read()) != -1) {
                if (value == '\n') {
                    consume(line.toString(), lineTruncated);
                    line.setLength(0);
                    lineTruncated = false;
                } else if (line.length() < MAX_LINE_CHARS) {
                    line.append((char) value);
                } else {
                    lineTruncated = true;
                }
            }
            if (line.length() != 0 || lineTruncated) consume(line.toString(), lineTruncated);
            if (stopReason == null) {
                observerStatus = "unavailable";
                stopReason = "logcat_exited";
                unexpectedExit = true;
            }
        } catch (IOException error) {
            if (stopReason == null) {
                readErrors++;
                observerStatus = "read_failed";
                stopReason = "read_error";
            }
        } finally {
            Thread deadline = deadlineThread;
            if (deadline != null && deadline != Thread.currentThread()) deadline.interrupt();
            Process current = process;
            if (current != null && current.isAlive()) destroyProcess();
            finishSummary();
        }
    }

    private void consume(String raw, boolean lineTruncated) {
        if (raw.endsWith("\r")) raw = raw.substring(0, raw.length() - 1);
        if (lineTruncated) truncatedLines++;
        NativeFragment fragment = NativeFragment.parse(raw, evidence.expectedNativeCameraId(), sourceIndex,
                startedAtWallMs, System.currentTimeMillis(), lineTruncated);
        if (fragment == null) return;
        evidence.accept(fragment);
        int bytes = raw.getBytes(StandardCharsets.UTF_8).length;
        if (sinkFailed || fragmentBytes + bytes > MAX_FRAGMENT_BYTES) {
            droppedFragments++;
            return;
        }
        if (emit("camera_fps_oem_fragment",
                "native_event", fragment.event,
                "native_pid", fragment.pid,
                "native_epoch_ms", fragment.epochMs,
                "native_tag", fragment.tag,
                "native_camera_id_field_candidate", fragment.cameraIdCandidate,
                "native_camera_id_candidate", fragment.nativeCameraIdCandidate,
                "native_identity_correlation", fragment.correlated
                        ? "matched" : "unknown_or_mismatched",
                "native_source_index", fragment.nativeSourceIndex,
                "native_stream_id", fragment.streamId,
                "native_camera_number", fragment.cameraNumber,
                "native_id_candidate", fragment.nativeId,
                "native_config_fps", fragment.configFps,
                "native_src_fps", fragment.srcFps,
                "native_sink_fps", fragment.sinkFps,
                "native_observed_fps", fragment.observedFps,
                "native_mfps", fragment.mFps,
                "native_client_count", fragment.clientCount,
                "native_drop_interval", fragment.dropInterval,
                "native_hal_stop_success", fragment.successfulStop,
                "native_hal_start_success", fragment.successfulStart,
                "native_stop_skipped", fragment.stopSkipped,
                "correlated", fragment.correlated,
                "fragment_truncated", lineTruncated,
                "fragment", raw)) {
            fragmentBytes += bytes;
        } else {
            droppedFragments++;
        }
    }

    private void finishSummary() {
        synchronized (this) {
            if (summarySent) return;
            summarySent = true;
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        boolean loss = sinkFailed || droppedFragments > 0 || truncatedLines > 0 || readErrors > 0
                || unexpectedExit || "unavailable".equals(observerStatus)
                || "read_failed".equals(observerStatus);
        emit("camera_fps_oem_summary",
                "outcome", evidence.outcome(loss).value,
                "observer_status", observerStatus,
                "stop_reason", stopReason == null ? "unknown" : stopReason,
                "window_ms", Math.min(elapsed, MAX_WINDOW_MS),
                "fragment_bytes", fragmentBytes,
                "dropped_fragments", droppedFragments,
                "truncated_lines", truncatedLines,
                "read_errors", readErrors,
                "capture_loss", loss,
                "baseline_ambiguous", evidence.baselineAmbiguous(),
                "native_identity_mapping", evidence.nativeIdentityMapping(),
                "native_expected_camera_id", evidence.expectedNativeCameraId(),
                "outcome_reason", evidence.identityMappingUnverified()
                        ? "camera_namespace_unverified" : "evidence_incomplete_or_ambiguous",
                "native_pid", evidence.nativePid(),
                "baseline_stream_id", evidence.baselineStreamId(),
                "stopped_stream_id", evidence.stoppedStreamId(),
                "skip_stop_stream_id", evidence.skippedStopStreamId(),
                "started_stream_id", evidence.startedStreamId(),
                "native_config_fps", evidence.configFps(),
                "native_src_fps", evidence.srcFps(),
                "native_sink_fps", evidence.sinkFps(),
                "native_drop_interval", evidence.dropInterval(),
                "native_observed_fps", evidence.observedFps(),
                "native_mfps", evidence.mFps(),
                "native_client_count", evidence.clientCount());
    }

    private boolean emit(String kind, Object... fields) {
        Object[] all = new Object[fields.length + 14];
        all[0] = "cycle_id"; all[1] = cycleId;
        all[2] = "camera_id"; all[3] = cameraId;
        all[4] = "helper_camera_id"; all[5] = cameraId;
        all[6] = "camera_tag"; all[7] = cameraTag;
        all[8] = "source_index"; all[9] = sourceIndex;
        all[10] = "requested_fps"; all[11] = requestedFps;
        all[12] = "helper_pid"; all[13] = helperPid;
        System.arraycopy(fields, 0, all, 14, fields.length);
        try {
            eventSink.accept(kind, all);
            return true;
        } catch (RuntimeException ignored) {
            sinkFailed = true;
            return false;
        }
    }

    enum Outcome {
        RESTART_CONFIRMED("restart_confirmed"),
        OLD_STREAM_CONTINUED("old_stream_continued"),
        INSUFFICIENT_EVIDENCE("insufficient_evidence");
        final String value;
        Outcome(String value) { this.value = value; }
    }

    /** Pure parser and correlator so the evidence rules can be checked without Logcat. */
    static final class EvidenceWindow {
        private static final Pattern LOGCAT = Pattern.compile(
                "^\\s*(\\d+(?:\\.\\d+)?)\\s+(\\d+)\\s+\\d+\\s+[VDIWEF]\\s+([^:]+):\\s*(.*)$");
        // An explicit camera_id field is only a candidate. It does not by itself prove the
        // physical/logical namespace; generic CameraId/Camera <n> forms remain unknown.
        private static final Pattern CAMERA_ID = Pattern.compile(
                "(?i)\\bcamera_id\\s*[:=]\\s*(\\d+)\\b");
        private static final Pattern NATIVE_CAMERA_ID_CANDIDATE = Pattern.compile(
                "(?i)\\bcamera\\s*id\\s*[:=]\\s*(\\d+)\\b");
        private static final Pattern CAMERA_NUMBER = Pattern.compile("(?i)\\bCamera\\s+(\\d+)\\b");
        private static final Pattern CAMERA_INSTANCE_ID = Pattern.compile(
                "(?i)\\bCamera\\s+\\d+\\s+id\\s*=\\s*(\\d+)\\b");
        private static final Pattern CAMERA_RATES = Pattern.compile(
                "(?i)\\bCamera\\s+\\d+(?:\\s+id\\s*=\\s*\\d+)?\\s*,\\s*fps\\s*=\\s*(\\d+)"
                + "(?:\\s+mFps\\s*=\\s*(\\d+))?");
        private static final Pattern SOURCE_INDEX = Pattern.compile(
                "(?i)\\b(?:source(?:_index|index)?|preview(?:_index|index))\\s*[:=]\\s*(\\d+)\\b");
        private static final Pattern STREAM_ID = Pattern.compile(
                "(?i)\\b(?:stream[ _-]?(?:id|handle)|streamhandle)\\s*[:=]\\s*(0x[0-9a-f]+|[a-z0-9_.-]+)");
        private static final Pattern CONFIG_FPS = Pattern.compile(
                "(?i)\\bconfigFps[\\\"']?\\s*[:=]\\s*(\\d{1,3})\\b");
        private static final Pattern SRC_FPS = Pattern.compile("(?i)\\bsrcFps\\s*[:=]\\s*(\\d{1,3})\\b");
        private static final Pattern SINK_FPS = Pattern.compile(
                "(?i)\\bsinkFps\\]?[\\\"']?\\s*[:=]\\s*\\[?\\s*(\\d{1,3})\\b");
        private static final Pattern DROP_INTERVAL = Pattern.compile(
                "(?i)\\b(?:drop[_ -]*interval|frame[_ -]*drop(?:[_ -]*interval)?)\\s*[:=]\\s*(\\d{1,6})\\b");
        private static final Pattern ACTIVE_CLIENTS = Pattern.compile(
                "(?i)\\b(?:active(?:\\s+client)?\\s*count|clientCount|"
                + "m(?:Start|Open)ClientNumber|clients)\\s*[:=]?\\s*(\\d+)\\b");
        private static final Pattern RESULT_ZERO = Pattern.compile(
                "(?i)\\b(?:ret|result|status)\\s*[:=]\\s*0\\b");
        private static final Pattern SUCCESS_WORD = Pattern.compile(
                "(?i)\\b(?:success|successful|succeeded|successfully)\\b");

        private final int expectedNativeCameraId;
        private final int expectedSourceIndex;
        private final int requestedFps;
        private final long captureStartWallMs;
        private final ArrayList<StreamCandidate> baselineCandidates = new ArrayList<>();
        private long reconnectWallMs;
        private long freshFrameWallMs;
        private long sequence;
        private int nativePid = -1;
        private boolean pidConflict;
        private String baselineStreamId;
        private String stoppedStreamId;
        private String skippedStopStreamId;
        private String startedStreamId;
        private long stoppedSequence = -1;
        private long startedSequence = -1;
        private long startedWallMs = -1;
        private long stoppedWallMs = -1;
        private long configWallMs = -1;
        private long configSequence = -1;
        private long skippedStopWallMs = -1;
        private long skippedStopSequence = -1;
        private int configFps = -1;
        private int srcFps = -1;
        private int sinkFps = -1;
        private int dropInterval = -1;
        private int observedFps = -1;
        private int mFps = -1;
        private int clientCount = -1;
        private String configStreamId;
        private boolean baselineCandidateOverflow;

        EvidenceWindow(int expectedNativeCameraId, int sourceIndex, int requestedFps,
                long captureStartWallMs) {
            this.expectedNativeCameraId = expectedNativeCameraId;
            expectedSourceIndex = sourceIndex;
            this.requestedFps = requestedFps;
            this.captureStartWallMs = captureStartWallMs;
        }

        synchronized void noteStage(String stage, long wallMs) {
            if ("reconnect_begin".equals(stage) && reconnectWallMs == 0) {
                reconnectWallMs = wallMs;
                selectBaselineCandidate();
            }
            if ("first_fresh_frame".equals(stage)) freshFrameWallMs = wallMs;
        }

        synchronized void accept(NativeFragment fragment) {
            if (!fragment.correlated) return;
            sequence++;
            if (nativePid < 0) nativePid = fragment.pid;
            else if (nativePid != fragment.pid) pidConflict = true;

            if (fragment.streamId != null && (reconnectWallMs == 0
                    || fragment.epochMs < reconnectWallMs)) {
                recordBaselineCandidate(fragment);
            }
            if (reconnectWallMs == 0) {
                return;
            }
            if (fragment.epochMs < reconnectWallMs) return;
            if (fragment.configFps >= 0) {
                configFps = fragment.configFps;
                configStreamId = fragment.streamId;
                configSequence = sequence;
                configWallMs = fragment.epochMs;
            }
            if (fragment.srcFps >= 0) srcFps = fragment.srcFps;
            if (fragment.sinkFps >= 0) sinkFps = fragment.sinkFps;
            if (fragment.dropInterval >= 0) dropInterval = fragment.dropInterval;
            if (fragment.observedFps >= 0) observedFps = fragment.observedFps;
            if (fragment.mFps >= 0) mFps = fragment.mFps;
            if (fragment.clientCount >= 0) clientCount = fragment.clientCount;
            if (fragment.successfulStop && fragment.streamId != null) {
                stoppedStreamId = fragment.streamId;
                stoppedSequence = sequence;
                stoppedWallMs = fragment.epochMs;
            }
            if (fragment.successfulStart && fragment.streamId != null) {
                startedStreamId = fragment.streamId;
                startedSequence = sequence;
                startedWallMs = fragment.epochMs;
            }
            if (fragment.stopSkipped && fragment.streamId != null) {
                skippedStopWallMs = fragment.epochMs;
                skippedStopSequence = sequence;
                skippedStopStreamId = fragment.streamId;
            }
        }

        synchronized Outcome outcome() { return outcome(false); }

        synchronized Outcome outcome(boolean captureLoss) {
            if (captureLoss || baselineCandidateOverflow || pidConflict || nativePid < 0 || baselineStreamId == null
                    || reconnectWallMs == 0 || freshFrameWallMs == 0) {
                return Outcome.INSUFFICIENT_EVIDENCE;
            }
            boolean stoppedOld = baselineStreamId.equals(stoppedStreamId)
                    && stoppedSequence > 0;
            boolean startedNew = startedStreamId != null
                    && !baselineStreamId.equals(startedStreamId)
                    && stoppedSequence > 0 && stoppedWallMs > reconnectWallMs
                    && startedWallMs > stoppedWallMs
                    && configFps == requestedFps
                    && configSequence > 0 && configWallMs >= reconnectWallMs
                    && configWallMs < startedWallMs
                    && startedStreamId.equals(configStreamId)
                    && freshFrameWallMs > Math.max(reconnectWallMs, startedWallMs);
            if (stoppedOld && startedNew && skippedStopSequence < 0)
                return Outcome.RESTART_CONFIRMED;
            if (skippedStopSequence > 0 && baselineStreamId.equals(skippedStopStreamId)
                    && stoppedWallMs < 0 && startedWallMs < 0
                    && freshFrameWallMs > skippedStopWallMs)
                return Outcome.OLD_STREAM_CONTINUED;
            return Outcome.INSUFFICIENT_EVIDENCE;
        }

        private void recordBaselineCandidate(NativeFragment fragment) {
            for (StreamCandidate candidate : baselineCandidates) {
                if (candidate.pid == fragment.pid && candidate.streamId.equals(fragment.streamId)) {
                    candidate.latestWallMs = Math.max(candidate.latestWallMs, fragment.epochMs);
                    if (reconnectWallMs != 0 && candidate.latestWallMs < reconnectWallMs)
                        selectBaselineCandidate();
                    return;
                }
            }
            if (baselineCandidates.size() >= MAX_BASELINE_CANDIDATES) {
                baselineCandidateOverflow = true;
                return;
            }
            baselineCandidates.add(new StreamCandidate(fragment.pid, fragment.streamId,
                    fragment.epochMs));
            if (reconnectWallMs != 0) selectBaselineCandidate();
        }

        private void selectBaselineCandidate() {
            StreamCandidate latest = null;
            for (StreamCandidate candidate : baselineCandidates) {
                if (candidate.latestWallMs < reconnectWallMs
                        && (latest == null || candidate.latestWallMs > latest.latestWallMs)) {
                    latest = candidate;
                }
            }
            baselineStreamId = latest == null ? null : latest.streamId;
        }

        private static final class StreamCandidate {
            final int pid;
            final String streamId;
            long latestWallMs;
            StreamCandidate(int pid, String streamId, long latestWallMs) {
                this.pid = pid;
                this.streamId = streamId;
                this.latestWallMs = latestWallMs;
            }
        }

        synchronized int nativePid() { return nativePid; }
        synchronized int expectedNativeCameraId() { return expectedNativeCameraId; }
        synchronized boolean identityMappingUnverified() { return expectedNativeCameraId < 0; }
        synchronized String nativeIdentityMapping() {
            return identityMappingUnverified() ? "unverified" : "expected_native_id_supplied";
        }
        synchronized boolean baselineAmbiguous() { return baselineCandidateOverflow; }
        synchronized String baselineStreamId() { return valueOrEmpty(baselineStreamId); }
        synchronized String stoppedStreamId() { return valueOrEmpty(stoppedStreamId); }
        synchronized String skippedStopStreamId() { return valueOrEmpty(skippedStopStreamId); }
        synchronized String startedStreamId() { return valueOrEmpty(startedStreamId); }
        synchronized int configFps() { return configFps; }
        synchronized int srcFps() { return srcFps; }
        synchronized int sinkFps() { return sinkFps; }
        synchronized int dropInterval() { return dropInterval; }
        synchronized int observedFps() { return observedFps; }
        synchronized int mFps() { return mFps; }
        synchronized int clientCount() { return clientCount; }

        private static String valueOrEmpty(String value) { return value == null ? "" : value; }

        static NativeFragment parseLine(String line, int expectedNativeCameraId, int sourceIndex,
                long captureStartWallMs, long observedWallMs) {
            return NativeFragment.parse(line, expectedNativeCameraId, sourceIndex,
                    captureStartWallMs, observedWallMs, false);
        }
    }

    static final class NativeFragment {
        final String tag, message, event, streamId, nativeId;
        final long epochMs;
        final int pid, cameraIdCandidate, nativeCameraIdCandidate, cameraNumber,
                nativeSourceIndex, configFps, srcFps,
                sinkFps, dropInterval, observedFps, mFps, clientCount;
        final boolean successfulStop, successfulStart, stopSkipped, correlated;

        private NativeFragment(String tag, String message, String event, String streamId,
                String nativeId, long epochMs, int pid, int cameraIdCandidate,
                int nativeCameraIdCandidate, int cameraNumber,
                int nativeSourceIndex, int configFps, int srcFps, int sinkFps,
                int dropInterval, int observedFps, int mFps, int clientCount,
                boolean successfulStop, boolean successfulStart,
                boolean stopSkipped, boolean correlated) {
            this.tag = tag;
            this.message = message;
            this.event = event;
            this.streamId = streamId;
            this.nativeId = nativeId;
            this.epochMs = epochMs;
            this.pid = pid;
            this.cameraIdCandidate = cameraIdCandidate;
            this.nativeCameraIdCandidate = nativeCameraIdCandidate;
            this.cameraNumber = cameraNumber;
            this.nativeSourceIndex = nativeSourceIndex;
            this.configFps = configFps;
            this.srcFps = srcFps;
            this.sinkFps = sinkFps;
            this.dropInterval = dropInterval;
            this.observedFps = observedFps;
            this.mFps = mFps;
            this.clientCount = clientCount;
            this.successfulStop = successfulStop;
            this.successfulStart = successfulStart;
            this.stopSkipped = stopSkipped;
            this.correlated = correlated;
        }

        static NativeFragment parse(String line, int expectedNativeCameraId,
                int expectedSourceIndex,
                long captureStartWallMs, long observedWallMs, boolean truncated) {
            Matcher prefix = EvidenceWindow.LOGCAT.matcher(line);
            long epochMs = -1;
            int pid = -1;
            String tag = "";
            String message = line;
            if (prefix.matches()) {
                try { epochMs = (long) (Double.parseDouble(prefix.group(1)) * 1_000); }
                catch (NumberFormatException ignored) { }
                try { pid = Integer.parseInt(prefix.group(2)); }
                catch (NumberFormatException ignored) { }
                tag = prefix.group(3).trim();
                message = prefix.group(4);
            }
            String lower = message.toLowerCase(Locale.ROOT);
            String lowerTag = tag.toLowerCase(Locale.ROOT);
            if (!(lowerTag.contains("bmm") || lowerTag.contains("hal3")
                    || lowerTag.contains("chi") || lowerTag.contains("normalcamclient"))) return null;
            boolean startCall = lower.contains("starthal3stream")
                    || lower.contains("normalcamclient::startpreview");
            boolean nativeStopCall = lower.contains("stopstreaming");
            boolean stopCall = nativeStopCall || lower.contains("normalcamclient::stoppreview");
            boolean deleteCall = lower.contains("deletestream");
            boolean clearCall = lower.contains("clearstreamingrequest");
            Integer config = number(EvidenceWindow.CONFIG_FPS, message);
            Integer src = number(EvidenceWindow.SRC_FPS, message);
            Integer sink = number(EvidenceWindow.SINK_FPS, message);
            Integer drop = number(EvidenceWindow.DROP_INTERVAL, message);
            Matcher cameraRates = EvidenceWindow.CAMERA_RATES.matcher(message);
            Integer observed = null;
            Integer mfps = null;
            if (cameraRates.find()) {
                observed = parseInt(cameraRates.group(1));
                mfps = parseInt(cameraRates.group(2));
            }
            String streamId = group(EvidenceWindow.STREAM_ID, message);
            Integer camera = number(EvidenceWindow.CAMERA_ID, message);
            Integer nativeCameraIdCandidate = number(
                    EvidenceWindow.NATIVE_CAMERA_ID_CANDIDATE, message);
            Integer cameraNumber = number(EvidenceWindow.CAMERA_NUMBER, message);
            String nativeId = group(EvidenceWindow.CAMERA_INSTANCE_ID, message);
            Integer source = number(EvidenceWindow.SOURCE_INDEX, message);
            Integer activeClients = number(EvidenceWindow.ACTIVE_CLIENTS, message);
            boolean operationSuccess = explicitSuccess(message);
            boolean halTag = lowerTag.contains("hal3");
            boolean successfulStop = halTag && nativeStopCall && operationSuccess;
            boolean successfulStart = halTag && lower.contains("starthal3stream") && operationSuccess;
            boolean stopSkipped = lowerTag.contains("bmm")
                    && (lower.contains("stopstreaming") || lower.contains("stoppreview"))
                    && (lower.contains("skip") || lower.contains("not stop")
                    || lower.contains("not call") || lower.contains("not invoke"))
                    && activeClients != null && activeClients > 0;
            boolean clientLifecycle = lower.contains("normalcamclient")
                    && (lower.contains("startpreview") || lower.contains("stoppreview"));
            boolean relevant = startCall || stopCall || deleteCall || clearCall || config != null
                    || src != null || sink != null || drop != null || observed != null
                    || mfps != null || activeClients != null || stopSkipped
                    || clientLifecycle || streamId != null;
            if (!relevant) return null;
            boolean timeValid = epochMs >= captureStartWallMs && epochMs <= observedWallMs + 1_000;
            boolean cameraMatches = expectedNativeCameraId >= 0 && camera != null
                    && camera == expectedNativeCameraId;
            boolean sourceMatches = source != null && source == expectedSourceIndex;
            boolean correlated = !truncated && pid > 0 && timeValid && cameraMatches && sourceMatches;
            String event = clientLifecycle ? "client_lifecycle"
                    : startCall ? "stream_start"
                    : stopSkipped ? "stop_skipped"
                    : stopCall ? "stream_stop"
                    : deleteCall ? "stream_delete"
                    : clearCall ? "stream_clear"
                    : config != null || src != null || sink != null || drop != null
                            || observed != null || mfps != null ? "fps_metadata"
                    : activeClients != null ? "client_count"
                    : "stream_identity";
            return new NativeFragment(tag, message, event, streamId, nativeId,
                    epochMs, pid, camera == null ? -1 : camera,
                    nativeCameraIdCandidate == null ? -1 : nativeCameraIdCandidate,
                    cameraNumber == null ? -1 : cameraNumber,
                    source == null ? -1 : source,
                    config == null ? -1 : config, src == null ? -1 : src,
                    sink == null ? -1 : sink, drop == null ? -1 : drop,
                    observed == null ? -1 : observed, mfps == null ? -1 : mfps,
                    activeClients == null ? -1 : activeClients,
                    successfulStop, successfulStart, stopSkipped, correlated);
        }

        private static boolean explicitSuccess(String message) {
            String lower = message.toLowerCase(Locale.ROOT);
            if (lower.contains("goto") || lower.contains("entry") || lower.contains("request")
                    || lower.contains("skip") || lower.contains("failed")
                    || lower.contains("failure") || lower.contains("error")
                    || lower.contains("unsuccessful") || lower.contains("not success")
                    || lower.contains("no success") || lower.contains("did not"))
                return false;
            return EvidenceWindow.SUCCESS_WORD.matcher(message).find()
                    || EvidenceWindow.RESULT_ZERO.matcher(message).find();
        }

        private static Integer parseInt(String value) {
            try { return value == null ? null : Integer.parseInt(value); }
            catch (NumberFormatException ignored) { return null; }
        }

        private static Integer number(Pattern pattern, String value) {
            String found = group(pattern, value);
            if (found == null) return null;
            try { return Integer.parseInt(found); }
            catch (NumberFormatException ignored) { return null; }
        }

        private static String group(Pattern pattern, String value) {
            Matcher match = pattern.matcher(value);
            return match.find() ? match.group(1).toLowerCase(Locale.ROOT) : null;
        }
    }
}
