package com.byd.extend;

import android.Manifest;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Surface-input H.264 recorder with bounded keyframe-aware pre-roll and event post-roll. */
public final class MotionVideoRecorder implements AutoCloseable {
    private static final String TAG = "MotionVideoRecorder";
    private static final int VIDEO_TRACK = 0;
    private static final int AUDIO_TRACK = 1;
    private static final long MAX_RING_AGE_MS = 32_000L;
    private static final long MAX_RING_BYTES = 40L * 1024L * 1024L;
    private static final long DETECTION_MERGE_GAP_MS = 1_000L;
    private static final long MARKER_PERSIST_INTERVAL_MS = 1_000L;
    private static final int MAX_PENDING_DETECTIONS = 64;
    private static final long AUDIO_FORMAT_WAIT_MS = 1_500L;
    private static final int AUDIO_SAMPLE_RATE = 16_000;
    private static final int AUDIO_CHANNEL_COUNT = 1;

    private final Context context;
    private final Consumer<String> stateCallback;
    private final MotionMediaLibrary library;
    private final BlockingQueue<Detection> detections = new ArrayBlockingQueue<>(256);
    private final BlockingQueue<EncodedPacket> audioPackets = new ArrayBlockingQueue<>(512);
    private final ArrayList<EncodedPacket> ring = new ArrayList<>(); // Only encoded samples; hard byte/time bounded.
    private final ArrayDeque<Detection> pendingDetections = new ArrayDeque<>();
    private final ArrayList<TimelineItem> iteration = new ArrayList<>();

    private volatile boolean buffering;
    private volatile boolean recording;
    private volatile boolean stopRequested;
    private volatile boolean audioRunning;
    private volatile boolean audioFailed;
    private volatile AudioRecord audioRecord;
    private volatile MediaFormat audioFormat;
    private volatile Thread workerThread;
    private volatile Thread audioThread;
    private volatile MediaCodec videoEncoder;
    private volatile Surface inputSurface;
    private volatile long requestedFinishDeadlineMs = Long.MIN_VALUE;
    private volatile String stopReason;

    private MotionConfig config;
    private MotionMediaIndex index;
    private MotionStorage storage;
    private MediaFormat videoFormat;
    private Clip activeClip;
    private long ringBytes;
    private long latestRingElapsedUs = Long.MIN_VALUE;
    private long finishDeadlineMs = Long.MIN_VALUE;
    private long outputRestartUntilElapsedMs = Long.MIN_VALUE;
    private String outputRestartVolumeId;
    private long wallAnchorMs;
    private long elapsedAnchorMs;
    private long videoPtsOffsetUs = Long.MIN_VALUE;
    private long audioPtsOffsetUs = Long.MIN_VALUE;
    private long lastVideoElapsedUs = Long.MIN_VALUE;
    private long lastAudioElapsedUs = Long.MIN_VALUE;
    private final HashMap<Integer, Episode> episodesByLevel = new HashMap<>();
    private boolean videoEos;
    private boolean closed;

    public MotionVideoRecorder(Context context, Consumer<String> stateCallback) {
        this.context = context.getApplicationContext();
        this.stateCallback = stateCallback == null ? ignored -> { } : stateCallback;
        this.library = new MotionMediaLibrary(this.context);
        this.index = library.index();
        this.storage = library.storage();
    }

    /** Starts the video encoder and returns its GPU-fed input through inputSurface(). */
    public void startBuffering(MotionConfig requestedConfig) {
        synchronized (this) {
            if (closed) throw new IllegalStateException("MotionVideoRecorder is closed");
            if (buffering) return;
            MotionConfig next = requestedConfig == null ? MotionConfig.read(context) : requestedConfig;
            if (!next.recordingEnabled) {
                notifyState("recording_disabled");
                return;
            }
            config = next;
            storage = library.storage();
            index = library.index();
            ring.clear();
            pendingDetections.clear();
            detections.clear();
            audioPackets.clear();
            ringBytes = 0;
            latestRingElapsedUs = Long.MIN_VALUE;
            finishDeadlineMs = Long.MIN_VALUE;
            outputRestartUntilElapsedMs = Long.MIN_VALUE;
            outputRestartVolumeId = null;
            requestedFinishDeadlineMs = Long.MIN_VALUE;
            videoPtsOffsetUs = Long.MIN_VALUE;
            audioPtsOffsetUs = Long.MIN_VALUE;
            lastVideoElapsedUs = Long.MIN_VALUE;
            lastAudioElapsedUs = Long.MIN_VALUE;
            episodesByLevel.clear();
            videoFormat = null;
            audioFormat = null;
            audioFailed = false;
            videoEos = false;
            stopRequested = false;
            stopReason = null;
            wallAnchorMs = System.currentTimeMillis();
            elapsedAnchorMs = SystemClock.elapsedRealtime();
            MediaCodec encoder = null;
            Surface surface = null;
            try {
                encoder = createHardwareAvcEncoder(next.width(), next.height(), next.bitrate());
                MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                        next.width(), next.height());
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                format.setInteger(MediaFormat.KEY_BIT_RATE, next.bitrate());
                format.setInteger(MediaFormat.KEY_FRAME_RATE, 25);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                surface = encoder.createInputSurface();
                encoder.start();
                videoEncoder = encoder;
                inputSurface = surface;
                buffering = true;
                Thread worker = new Thread(this::runVideoLoop, "motion-video-recorder");
                workerThread = worker;
                worker.start();
                if (next.audioEnabled) startAudioCapture();
            } catch (IOException | RuntimeException failure) {
                if (surface != null) try { surface.release(); } catch (RuntimeException ignored) { }
                if (encoder != null) {
                    try { encoder.stop(); } catch (RuntimeException ignored) { }
                    try { encoder.release(); } catch (RuntimeException ignored) { }
                }
                inputSurface = null;
                videoEncoder = null;
                buffering = false;
                String detail = failure.getMessage();
                notifyState("video_encoder_unavailable:" + (detail == null || detail.isEmpty()
                        ? failure.getClass().getSimpleName() : detail));
            }
        }
    }

    public Surface inputSurface() { return inputSurface; }
    public int width() { return config == null ? MotionConfig.read(context).width() : config.width(); }
    public int height() { return config == null ? MotionConfig.read(context).height() : config.height(); }

    /** Records an event level 1 (yellow) or 2 (red), timestamped on elapsedRealtime. */
    public void onDetection(int level, long elapsedMs) {
        if (!buffering || (level != 1 && level != 2) || elapsedMs < 0) return;
        if (!detections.offer(new Detection(level, elapsedMs))) notifyState("motion_detection_queue_full");
    }

    /** Extends or sets the current absolute elapsedRealtime deadline; event post-roll is never shortened. */
    public void finishAfter(long deadlineMs) {
        if (!buffering || deadlineMs < 0) return;
        requestedFinishDeadlineMs = Math.max(requestedFinishDeadlineMs, deadlineMs);
    }

    /** Stops gracefully, finalizing and publishing any complete muxed segments. */
    public void stop(String reason) {
        Thread worker;
        synchronized (this) {
            if (!buffering) return;
            stopReason = reason == null ? "stopped" : reason;
            stopRequested = true;
            audioRunning = false;
            AudioRecord recorder = audioRecord;
            if (recorder != null) {
                try { recorder.stop(); } catch (RuntimeException ignored) { }
            }
            worker = workerThread;
        }
        if (worker != null && worker != Thread.currentThread()) {
            try { worker.join(5_000L); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    public boolean isBuffering() { return buffering; }
    public boolean isRecording() { return recording; }

    private static MediaCodec createHardwareAvcEncoder(int width, int height, int bitrate) throws IOException {
        if (Build.VERSION.SDK_INT < 29) {
            throw new IOException("hardware_encoder_classification_unavailable");
        }
        MediaCodecList codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        String lastFailure = "no_hardware_avc_encoder_supports_requested_size_rate";
        for (MediaCodecInfo info : codecs.getCodecInfos()) {
            if (!info.isEncoder() || !info.isHardwareAccelerated() || info.isSoftwareOnly()) continue;
            boolean supportsAvc = false;
            for (String type : info.getSupportedTypes()) {
                if (MediaFormat.MIMETYPE_VIDEO_AVC.equalsIgnoreCase(type)) {
                    supportsAvc = true;
                    break;
                }
            }
            if (!supportsAvc) continue;
            try {
                MediaCodecInfo.CodecCapabilities capabilities = info.getCapabilitiesForType(
                        MediaFormat.MIMETYPE_VIDEO_AVC);
                MediaCodecInfo.VideoCapabilities video = capabilities.getVideoCapabilities();
                if (video == null || !video.isSizeSupported(width, height)
                        || !video.areSizeAndRateSupported(width, height, 25.0)) continue;
                return MediaCodec.createByCodecName(info.getName());
            } catch (IOException | RuntimeException failure) {
                // Keep scanning other hardware encoders; none are accepted without actual format support.
                lastFailure = "hardware_avc_encoder_unavailable:" + failure.getClass().getSimpleName();
            }
        }
        throw new IOException(lastFailure);
    }

    @Override public void close() {
        synchronized (this) { closed = true; }
        stop("closed");
    }

    private void startAudioCapture() {
        if (Build.VERSION.SDK_INT >= 23
                && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioFailed = true;
            notifyState("audio_unavailable:permission_denied");
            return;
        }
        audioRunning = true;
        Thread thread = new Thread(this::runAudioCapture, "motion-audio-capture");
        audioThread = thread;
        thread.start();
    }

    private void runAudioCapture() {
        AudioRecord recorder = null;
        MediaCodec encoder = null;
        boolean started = false;
        long basePtsUs = 0;
        long sampleCount = 0;
        try {
            assertAudioCaptureAvailable(-1);
            int minimum = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IOException("audio_format_unavailable");
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minimum * 2, 6_400));
            audioRecord = recorder;
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IOException("audio_record_not_initialized");
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
                    AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 32_000);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4_096);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            assertAudioCaptureAvailable(-1);
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IOException("audio_record_start_failed");
            started = true;
            assertAudioCaptureAvailable(recorder.getAudioSessionId());
            basePtsUs = SystemClock.elapsedRealtimeNanos() / 1_000L;
            byte[] pcm = new byte[2_048];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (audioRunning) {
                assertAudioCaptureAvailable(recorder.getAudioSessionId());
                int count = recorder.read(pcm, 0, pcm.length, AudioRecord.READ_BLOCKING);
                if (count < 0) throw new IOException("audio_capture_read_failed_" + count);
                if (count == 0) continue;
                if ((count & 1) != 0) count--;
                if (count <= 0) continue;
                int inputIndex;
                do {
                    inputIndex = encoder.dequeueInputBuffer(10_000);
                    drainAudio(encoder, info);
                } while (inputIndex < 0 && audioRunning);
                if (inputIndex < 0) break;
                ByteBuffer input = encoder.getInputBuffer(inputIndex);
                if (input == null) throw new IOException("audio_encoder_input_unavailable");
                input.clear();
                input.put(pcm, 0, count);
                long ptsUs = basePtsUs + sampleCount * 1_000_000L / AUDIO_SAMPLE_RATE;
                encoder.queueInputBuffer(inputIndex, 0, count, ptsUs, 0);
                sampleCount += count / 2L;
                drainAudio(encoder, info);
            }
            if (started) {
                int inputIndex = encoder.dequeueInputBuffer(50_000);
                if (inputIndex >= 0) {
                    encoder.queueInputBuffer(inputIndex, 0, 0,
                            basePtsUs + sampleCount * 1_000_000L / AUDIO_SAMPLE_RATE,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
                long deadline = SystemClock.elapsedRealtime() + 750L;
                while (SystemClock.elapsedRealtime() < deadline && drainAudio(encoder, info)) {
                    if (!audioRunning && audioPackets.isEmpty()) break;
                }
            }
        } catch (IOException | RuntimeException failure) {
            if (audioRunning) {
                audioFailed = true;
                String detail = failure.getMessage();
                notifyState("audio_unavailable:" + (detail == null || detail.isEmpty()
                        ? failure.getClass().getSimpleName() : detail));
            }
        } finally {
            audioRunning = false;
            audioRecord = null;
            if (recorder != null) {
                try { recorder.stop(); } catch (RuntimeException ignored) { }
                try { recorder.release(); } catch (RuntimeException ignored) { }
            }
            if (encoder != null) {
                try { encoder.stop(); } catch (RuntimeException ignored) { }
                try { encoder.release(); } catch (RuntimeException ignored) { }
            }
        }
    }

    /** Refuse or stop optional microphone capture when calls, AVAS, or another client owns input. */
    private void assertAudioCaptureAvailable(int ownAudioSessionId) throws IOException {
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) throw new IOException("audio_manager_unavailable");
        try {
            if (audio.getMode() != AudioManager.MODE_NORMAL)
                throw new IOException("audio_in_use_by_call_or_communication");
            List<AudioRecordingConfiguration> active = audio.getActiveRecordingConfigurations();
            if (active == null) return;
            for (AudioRecordingConfiguration configuration : active) {
                if (ownAudioSessionId <= 0
                        || configuration.getClientAudioSessionId() != ownAudioSessionId) {
                    throw new IOException("audio_in_use_by_other_client");
                }
            }
        } catch (SecurityException | IllegalStateException unavailable) {
            throw new IOException("audio_state_unavailable", unavailable);
        }
    }

    private boolean drainAudio(MediaCodec encoder, MediaCodec.BufferInfo info) {
        boolean any = false;
        while (true) {
            int outputIndex = encoder.dequeueOutputBuffer(info, 0);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) return any;
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                audioFormat = encoder.getOutputFormat();
                continue;
            }
            if (outputIndex < 0) continue;
            any = true;
            ByteBuffer output = encoder.getOutputBuffer(outputIndex);
            try {
                if (output != null && info.size > 0
                        && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    long elapsedUs = mapAudioPts(info.presentationTimeUs);
                    if (elapsedUs > lastAudioElapsedUs) {
                        byte[] bytes = copyBytes(output, info.offset, info.size);
                        audioPackets.offer(new EncodedPacket(AUDIO_TRACK, elapsedUs, info.flags, bytes));
                        lastAudioElapsedUs = elapsedUs;
                    }
                }
            } finally {
                encoder.releaseOutputBuffer(outputIndex, false);
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return any;
        }
    }

    private void runVideoLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (!stopRequested) {
                collectTimelineItems(info);
                processTimelineItems();
                applyFinishRequest();
                tryOpenPendingDetection();
                trimRing();
                if (activeClip != null && finishDeadlineMs != Long.MIN_VALUE
                        && SystemClock.elapsedRealtime() >= finishDeadlineMs) closeActiveClip();
                expireOutputRestart(SystemClock.elapsedRealtime());
                Thread.sleep(5L);
            }
            stopAudioCapture();
            signalVideoEndOfStream();
            long drainUntil = SystemClock.elapsedRealtime() + 1_500L;
            while (!videoEos && SystemClock.elapsedRealtime() < drainUntil) {
                collectTimelineItems(info);
                processTimelineItems();
                tryOpenPendingDetection();
                Thread.sleep(5L);
            }
            Thread audio = audioThread;
            if (audio != null && audio != Thread.currentThread()) {
                try { audio.join(500L); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            collectTimelineItems(info);
            processTimelineItems();
            if (activeClip != null) closeActiveClip();
        } catch (InterruptedException interrupted) {
            if (!stopRequested) Thread.currentThread().interrupt();
            if (activeClip != null) closeActiveClip();
        } catch (RuntimeException failure) {
            Log.e(TAG, "video_encoder_loop_failed", failure);
            notifyState("video_encoder_failed:" + failure.getClass().getSimpleName());
            if (activeClip != null) closeActiveClip();
        } finally {
            stopAudioCapture();
            Thread audio = audioThread;
            if (audio != null && audio != Thread.currentThread()) {
                try { audio.join(300L); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            MediaCodec encoder = videoEncoder;
            videoEncoder = null;
            if (encoder != null) {
                try { encoder.stop(); } catch (RuntimeException ignored) { }
                try { encoder.release(); } catch (RuntimeException ignored) { }
            }
            Surface surface = inputSurface;
            inputSurface = null;
            if (surface != null) try { surface.release(); } catch (RuntimeException ignored) { }
            buffering = false;
            recording = false;
            workerThread = null;
            notifyState("stopped:" + (stopReason == null ? "encoder_ended" : stopReason));
        }
    }

    private void collectTimelineItems(MediaCodec.BufferInfo info) {
        iteration.clear();
        MediaCodec encoder = videoEncoder;
        if (encoder != null) {
            while (true) {
                int outputIndex;
                try { outputIndex = encoder.dequeueOutputBuffer(info, 0); }
                catch (RuntimeException failure) {
                    stopReason = "video_encoder_read_failed";
                    stopRequested = true;
                    notifyState(stopReason);
                    break;
                }
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoFormat = encoder.getOutputFormat();
                    continue;
                }
                if (outputIndex < 0) continue;
                ByteBuffer output = encoder.getOutputBuffer(outputIndex);
                try {
                    if (output != null && info.size > 0
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        long elapsedUs = mapVideoPts(info.presentationTimeUs);
                        if (elapsedUs > lastVideoElapsedUs) {
                            byte[] bytes = copyBytes(output, info.offset, info.size);
                            iteration.add(TimelineItem.packet(new EncodedPacket(VIDEO_TRACK, elapsedUs,
                                    info.flags, bytes)));
                            lastVideoElapsedUs = elapsedUs;
                        }
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) videoEos = true;
                } finally {
                    encoder.releaseOutputBuffer(outputIndex, false);
                }
            }
        }
        EncodedPacket audio;
        while ((audio = audioPackets.poll()) != null) iteration.add(TimelineItem.packet(audio));
        Detection detection;
        while ((detection = detections.poll()) != null) iteration.add(TimelineItem.detection(detection));
    }

    private void processTimelineItems() {
        iteration.sort(Comparator.comparingLong(TimelineItem::elapsedUs)
                .thenComparingInt(item -> item.detection == null ? 0 : 1));
        for (TimelineItem item : iteration) {
            if (item.detection != null) processDetection(item.detection);
            else handlePacket(item.packet);
        }
    }

    private void processDetection(Detection detection) {
        updateEpisode(detection);
        if (activeClip != null && detection.elapsedMs > finishDeadlineMs) closeActiveClip();
        if (activeClip != null) {
            if (detection.elapsedMs >= activeClip.startElapsedMs) persistEpisode(activeClip, detection);
            else persistMarkerInFinishedClip(detection);
            extendFinishDeadline(MotionRecorderTimeline.finishDeadline(detection.elapsedMs, config.afterSeconds));
            return;
        }
        if (persistMarkerInFinishedClip(detection)) return;
        extendFinishDeadline(MotionRecorderTimeline.finishDeadline(detection.elapsedMs, config.afterSeconds));
        Detection pending = pendingDetections.peekLast();
        if (pending != null && pending.eventId.equals(detection.eventId)) pending.mergeLatest(detection);
        else {
            if (pendingDetections.size() >= MAX_PENDING_DETECTIONS) {
                pendingDetections.removeFirst();
                notifyState("motion_pending_event_queue_full");
            }
            pendingDetections.addLast(detection);
        }
    }

    private void tryOpenPendingDetection() {
        if (activeClip != null || pendingDetections.isEmpty() || videoFormat == null) return;
        Detection first = pendingDetections.peekFirst();
        long nowMs = SystemClock.elapsedRealtime();
        boolean audioRequested = config.audioEnabled && !audioFailed;
        if (audioRequested && audioFormat == null && nowMs - first.elapsedMs < AUDIO_FORMAT_WAIT_MS) return;
        long targetMs = first.elapsedMs - config.beforeSeconds * 1_000L;
        ArrayList<Long> keyframes = new ArrayList<>();
        for (EncodedPacket packet : ring) {
            if (packet.track == VIDEO_TRACK && packet.isKeyFrame()) keyframes.add(packet.elapsedUs / 1_000L);
        }
        long startMs = MotionRecorderTimeline.chooseKeyframeStart(keyframes, targetMs, first.elapsedMs);
        if (startMs < 0) {
            if (nowMs - first.elapsedMs < 2_000L) return;
            pendingDetections.removeFirst();
            notifyState("motion_no_keyframe_before_event");
            return;
        }
        List<EncodedPacket> initialPackets = ringPacketsFrom(startMs);
        long finishAt = MotionRecorderTimeline.finishDeadline(first.latestElapsedMs, config.afterSeconds);
        extendFinishDeadline(finishAt);
        long futureMs = Math.max(0, finishAt - nowMs);
        long requiredBytes = estimateSegmentBytes(initialPackets, futureMs,
                config.audioEnabled && !audioFailed && audioFormat != null);
        String firstAttemptVolumeId = outputRestartActive(nowMs) ? outputRestartVolumeId : null;
        if (!openSegment(startMs, initialPackets, requiredBytes, firstAttemptVolumeId)) {
            pendingDetections.removeFirst();
            return;
        }
        pendingDetections.removeFirst();
        finishDeadlineMs = Math.max(finishDeadlineMs, finishAt);
        persistInitialEpisode(activeClip, first);
        while (!pendingDetections.isEmpty() && activeClip != null) {
            Detection queued = pendingDetections.removeFirst();
            extendFinishDeadline(MotionRecorderTimeline.finishDeadline(queued.latestElapsedMs, config.afterSeconds));
            persistEpisode(activeClip, queued);
        }
    }

    private void handlePacket(EncodedPacket packet) {
        remember(packet);
        long packetElapsedMs = packet.elapsedUs / 1_000L;
        long nowMs = SystemClock.elapsedRealtime();
        expireOutputRestart(nowMs);
        if (activeClip == null && outputRestartActive(nowMs)
                && packet.track == VIDEO_TRACK && packet.isKeyFrame()) {
            long durationMs = Math.max(0, Math.min(MotionRecorderTimeline.SEGMENT_MILLIS,
                    outputRestartUntilElapsedMs - packetElapsedMs));
            long requiredBytes = estimateSegmentBytes(Collections.emptyList(), durationMs,
                    config.audioEnabled && !audioFailed && audioFormat != null);
            openSegment(packetElapsedMs, Collections.emptyList(), requiredBytes, outputRestartVolumeId);
        }
        if (activeClip == null || packet.elapsedUs < activeClip.startElapsedMs * 1_000L) return;
        if (packet.track == VIDEO_TRACK && MotionRecorderTimeline.startsSegmentAt(
                activeClip.startElapsedMs, packet.elapsedUs / 1_000L, packet.isKeyFrame())) {
            String endedVolumeId = activeClip.output.volumeId;
            closeActiveClip();
            long startMs = packet.elapsedUs / 1_000L;
            long durationMs = Math.max(0, Math.min(MotionRecorderTimeline.SEGMENT_MILLIS,
                    finishDeadlineMs - startMs));
            long requiredBytes = estimateSegmentBytes(Collections.emptyList(), durationMs,
                    config.audioEnabled && !audioFailed && audioFormat != null);
            if (!openSegment(startMs, Collections.emptyList(), requiredBytes, null)
                    && !outputRestartActive(SystemClock.elapsedRealtime()))
                scheduleOutputContinuation(endedVolumeId);
        }
        if (activeClip != null) {
            Clip clip = activeClip;
            if (!hasStorageForPacket(clip, packet.data.length)) {
                scheduleOutputContinuation(clip.output.volumeId);
                closeActiveClip();
            } else writePacket(clip, packet);
        }
    }

    private List<EncodedPacket> ringPacketsFrom(long startMs) {
        ArrayList<EncodedPacket> result = new ArrayList<>();
        long startUs = startMs * 1_000L;
        for (EncodedPacket packet : ring) if (packet.elapsedUs >= startUs) result.add(packet);
        result.sort(PACKET_ORDER);
        return result;
    }

    private long estimateSegmentBytes(List<EncodedPacket> initialPackets, long futureMs, boolean includeAudio) {
        long bytes = 0;
        for (EncodedPacket packet : initialPackets) bytes = saturatedAdd(bytes, packet.data.length);
        long bitsPerSecond = config.bitrate() + (includeAudio ? 32_000L : 0L);
        long futureBytes = (bitsPerSecond * Math.max(0, futureMs) + 7_999L) / 8_000L;
        return saturatedAdd(bytes, futureBytes);
    }

    private boolean hasStorageForPacket(Clip clip, int packetBytes) {
        long now = SystemClock.elapsedRealtime();
        long currentSize = Math.max(clip.segmentSizeBytes, clip.sampleBytes);
        long actualSize = storage.outputSizeBytes(clip.output);
        if (actualSize >= 0) currentSize = Math.max(currentSize, actualSize);
        long nextSize = saturatedAdd(currentSize, packetBytes);
        if (now - clip.lastStorageCheckMs >= 1_000L || nextSize > clip.storageWriteLimitBytes) {
            MotionMediaLibrary.StorageDecision decision = library.prepareOwnSpace(clip.output.volumeId,
                    config, packetBytes, clip.output.baseOwnBytes, currentSize);
            if (!decision.allowed) {
                notifyState(decision.reason == null ? "motion_storage_allowance_unavailable" : decision.reason);
                return false;
            }
            clip.storageWriteLimitBytes = saturatedAdd(currentSize, decision.allowanceBytes);
            clip.lastStorageCheckMs = now;
        }
        if (nextSize <= clip.storageWriteLimitBytes) return true;
        notifyState("motion_storage_packet_exceeds_allowance");
        return false;
    }

    private boolean openSegment(long startElapsedMs, List<EncodedPacket> initialPackets, long requiredBytes,
            String firstAttemptVolumeId) {
        if (videoFormat == null) return false;
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                .format(new Date(wallFromElapsed(startElapsedMs)));
        String name = "Motion_" + timestamp + "_" + UUID.randomUUID().toString().substring(0, 8) + ".mp4";
        MotionStorage.Output output = null;
        MediaMuxer muxer = null;
        MotionMediaLease lease = null;
        String segmentId = UUID.randomUUID().toString().replace("-", "");
        String uri = null;
        String preferred = library.selectedVolumeId(config.primarySd);
        try {
            output = storage.createOutput(preferred, config.fallbackEnabled, name, config,
                    library, requiredBytes, firstAttemptVolumeId);
            uri = output.uri.toString();
            lease = MotionMediaLease.acquire(context, uri);
            MotionMediaIndex.Record record = new MotionMediaIndex.Record(uri,
                    output.recoveryUri == null ? null : output.recoveryUri.toString(),
                    output.volumeId, output.displayName, segmentId, wallFromElapsed(startElapsedMs),
                    startElapsedMs, config.width(), config.height(), MotionMediaIndex.RECORDING);
            index.put(record);
            if (output.descriptor != null) {
                muxer = new MediaMuxer(output.descriptor.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } else if (output.temporaryFile != null) {
                muxer = new MediaMuxer(output.temporaryFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } else {
                throw new IOException("Motion storage returned no muxer target");
            }
            int videoTrack = muxer.addTrack(videoFormat);
            int audioTrack = -1;
            MediaFormat selectedAudioFormat = config.audioEnabled && !audioFailed ? audioFormat : null;
            if (selectedAudioFormat != null) audioTrack = muxer.addTrack(selectedAudioFormat);
            muxer.start();
            Clip clip = new Clip(output, muxer, lease, record, videoTrack, audioTrack,
                    selectedAudioFormat != null, startElapsedMs);
            activeClip = clip;
            recording = true;
            if (firstAttemptVolumeId != null) clearOutputRestart();
            clip.storageWriteLimitBytes = output.initialOwnAllowanceBytes;
            clip.segmentSizeBytes = Math.max(0, storage.outputSizeBytes(output));
            persistCurrentEpisode(clip, false);
            for (EncodedPacket packet : initialPackets) {
                if (packet.elapsedUs >= startElapsedMs * 1_000L) {
                    if (!hasStorageForPacket(clip, packet.data.length)) {
                        scheduleOutputContinuation(clip.output.volumeId);
                        closeActiveClip();
                        return false;
                    }
                    writePacket(clip, packet);
                }
            }
            return true;
        } catch (IOException | RuntimeException failure) {
            Log.e(TAG, "motion_segment_open_failed", failure);
            notifyState("motion_storage_unavailable:" + failure.getClass().getSimpleName());
            if (output != null) scheduleOutputContinuation(output.volumeId);
            else if (firstAttemptVolumeId != null) scheduleOutputContinuationTo(firstAttemptVolumeId);
            else scheduleOutputContinuationTo(storage.oppositeVolumeId(preferred));
            if (muxer != null) {
                try { muxer.stop(); } catch (RuntimeException ignored) { }
                try { muxer.release(); } catch (RuntimeException ignored) { }
            }
            closeDescriptor(output);
            if (output != null) storage.discard(output);
            if (lease != null) try { lease.close(); } catch (IOException ignored) { }
            if (uri != null) {
                try { index.remove(uri); } catch (IOException ignored) { }
            }
            activeClip = null;
            recording = false;
            return false;
        }
    }

    private void writePacket(Clip clip, EncodedPacket packet) {
        int track = packet.track == VIDEO_TRACK ? clip.videoTrack : clip.audioTrack;
        if (track < 0 || (packet.track == AUDIO_TRACK && !clip.audioIncluded)) return;
        long presentation = packet.elapsedUs - clip.startElapsedMs * 1_000L;
        if (presentation < 0 || packet.data.length == 0) return;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        info.set(0, packet.data.length, presentation, packet.flags);
        try {
            clip.muxer.writeSampleData(track, ByteBuffer.wrap(packet.data), info);
            clip.segmentSizeBytes = saturatedAdd(clip.segmentSizeBytes, packet.data.length);
            clip.sampleBytes = saturatedAdd(clip.sampleBytes, packet.data.length);
            if (packet.track == VIDEO_TRACK) {
                clip.videoSamples++;
                clip.lastVideoElapsedMs = packet.elapsedUs / 1_000L;
            } else clip.audioSamples++;
        } catch (RuntimeException failure) {
            Log.e(TAG, "motion_muxer_write_failed", failure);
            notifyState("motion_muxer_write_failed");
            scheduleOutputContinuation(clip.output.volumeId);
            closeActiveClip();
        }
    }

    private void closeActiveClip() {
        Clip clip = activeClip;
        if (clip == null) return;
        activeClip = null;
        recording = false;
        persistCurrentEpisode(clip, true);
        boolean stopped = false;
        try {
            clip.muxer.stop();
            stopped = true;
        } catch (RuntimeException failure) {
            Log.w(TAG, "motion_muxer_stop_failed", failure);
        } finally {
            try { clip.muxer.release(); } catch (RuntimeException ignored) { }
            closeDescriptor(clip.output);
        }
        try {
            if (stopped && clip.videoSamples > 0) {
                long size = clip.output.temporaryFile == null
                        ? sizeOf(clip.output.uri) : Math.max(0, clip.output.temporaryFile.length());
                storage.publish(clip.output);
                long durationMs = Math.max(1L, clip.lastVideoElapsedMs - clip.startElapsedMs + 34L);
                index.update(clip.record.uri, MotionMediaIndex.COMPLETE, durationMs, size);
            } else if (clip.videoSamples == 0) {
                storage.discard(clip.output);
                index.remove(clip.record.uri);
            }
        } catch (IOException | RuntimeException failure) {
            Log.e(TAG, "motion_segment_publish_failed", failure);
            notifyState("motion_publish_failed:" + failure.getClass().getSimpleName());
            scheduleOutputContinuation(clip.output.volumeId);
        } finally {
            try { clip.lease.close(); } catch (IOException ignored) { }
        }
    }

    private void updateEpisode(Detection detection) {
        Episode episode = episodesByLevel.get(detection.level);
        if (episode != null && detection.elapsedMs >= episode.lastElapsedMs
                && detection.elapsedMs - episode.lastElapsedMs <= DETECTION_MERGE_GAP_MS) {
            episode.lastElapsedMs = detection.elapsedMs;
        } else {
            episode = new Episode(UUID.randomUUID().toString(), detection.level,
                    detection.elapsedMs, detection.elapsedMs);
            episodesByLevel.put(detection.level, episode);
        }
        detection.eventId = episode.id;
        detection.episodeStartMs = episode.startElapsedMs;
        detection.episodeEndMs = episode.lastElapsedMs;
        detection.latestElapsedMs = detection.elapsedMs;
    }

    private void persistEpisode(Clip clip, Detection detection) {
        if (clip == null || detection.eventId == null || detection.episodeEndMs < clip.startElapsedMs) return;
        persistEpisode(clip, detection.eventId, detection.level,
                Math.max(detection.episodeStartMs, clip.startElapsedMs), detection.episodeEndMs, false);
    }

    private void persistInitialEpisode(Clip clip, Detection detection) {
        if (clip == null || detection.eventId == null) return;
        long start = Math.max(detection.episodeStartMs, clip.startElapsedMs);
        long end = Math.max(start, detection.episodeEndMs);
        persistEpisode(clip, detection.eventId, detection.level, start, end, false);
    }

    private void persistCurrentEpisode(Clip clip, boolean force) {
        if (clip == null) return;
        for (Episode episode : episodesByLevel.values()) {
            if (episode.lastElapsedMs < clip.startElapsedMs) continue;
            persistEpisode(clip, episode.id, episode.level,
                    Math.max(episode.startElapsedMs, clip.startElapsedMs), episode.lastElapsedMs, force);
        }
    }

    private void persistEpisode(Clip clip, String eventId, int level, long startMs, long endMs, boolean force) {
        long boundedEnd = Math.max(startMs, endMs);
        long previous = clip.persistedEpisodeEnds.getOrDefault(eventId, Long.MIN_VALUE);
        if (!force && previous != Long.MIN_VALUE && boundedEnd - previous < MARKER_PERSIST_INTERVAL_MS) return;
        long wallStart = wallFromElapsed(startMs);
        long wallEnd = wallFromElapsed(boundedEnd);
        try {
            index.upsertMarker(clip.record.uri,
                    new MotionMediaIndex.Marker(eventId, startMs, wallStart, boundedEnd, wallEnd, level));
            clip.persistedEpisodeEnds.put(eventId, boundedEnd);
        } catch (IOException failure) {
            notifyState("motion_marker_persist_failed");
        }
    }

    private boolean persistMarkerInFinishedClip(Detection detection) {
        if (detection.eventId == null) return false;
        for (MotionMediaIndex.Record record : index.records()) {
            if (!MotionMediaIndex.COMPLETE.equals(record.status) && !MotionMediaIndex.INTERRUPTED.equals(record.status))
                continue;
            long end = record.startElapsedRealtimeMs + record.durationMs;
            if (detection.elapsedMs < record.startElapsedRealtimeMs || detection.elapsedMs > end) continue;
            long start = Math.max(detection.episodeStartMs, record.startElapsedRealtimeMs);
            long endInClip = Math.min(detection.episodeEndMs, end);
            if (endInClip < start) continue;
            for (MotionMediaIndex.Marker marker : record.markers) {
                if (marker.eventId.equals(detection.eventId)
                        && endInClip - marker.endElapsedRealtimeMs < MARKER_PERSIST_INTERVAL_MS) return true;
            }
            try {
                index.upsertMarker(record.uri, new MotionMediaIndex.Marker(detection.eventId, start,
                        wallFromElapsed(start), endInClip, wallFromElapsed(endInClip), detection.level));
                return true;
            } catch (IOException failure) {
                notifyState("motion_marker_persist_failed");
                return false;
            }
        }
        return false;
    }

    private void remember(EncodedPacket packet) {
        int position = Collections.binarySearch(ring, packet, PACKET_ORDER);
        if (position < 0) position = -position - 1;
        else while (position < ring.size() && PACKET_ORDER.compare(ring.get(position), packet) <= 0) position++;
        ring.add(position, packet);
        ringBytes += packet.data.length;
        latestRingElapsedUs = Math.max(latestRingElapsedUs, packet.elapsedUs);
    }

    private void trimRing() {
        if (ring.isEmpty()) return;
        long newestMs = latestRingElapsedUs / 1_000L;
        long requestedAge = Math.min(30, config.beforeSeconds) * 1_000L + 2_000L;
        long cutoffMs = newestMs - Math.min(MAX_RING_AGE_MS, requestedAge);
        long precedingKeyframe = Long.MIN_VALUE;
        long followingKeyframe = Long.MAX_VALUE;
        for (EncodedPacket packet : ring) {
            if (packet.track != VIDEO_TRACK || !packet.isKeyFrame()) continue;
            long time = packet.elapsedUs / 1_000L;
            if (time <= cutoffMs) precedingKeyframe = Math.max(precedingKeyframe, time);
            else followingKeyframe = Math.min(followingKeyframe, time);
        }
        long keepFrom = precedingKeyframe != Long.MIN_VALUE ? precedingKeyframe
                : followingKeyframe != Long.MAX_VALUE ? followingKeyframe : cutoffMs;
        removeRingBefore(keepFrom * 1_000L);
        while (ringBytes > MAX_RING_BYTES && ring.size() > 1) {
            long oldestKeyframe = Long.MAX_VALUE;
            for (EncodedPacket packet : ring) {
                if (packet.track == VIDEO_TRACK && packet.isKeyFrame() && packet.elapsedUs > ring.get(0).elapsedUs)
                    oldestKeyframe = Math.min(oldestKeyframe, packet.elapsedUs);
            }
            if (oldestKeyframe != Long.MAX_VALUE) removeRingBefore(oldestKeyframe);
            else {
                EncodedPacket removed = ring.remove(0);
                ringBytes -= removed.data.length;
            }
        }
    }

    private void removeRingBefore(long elapsedUs) {
        Iterator<EncodedPacket> iterator = ring.iterator();
        while (iterator.hasNext()) {
            EncodedPacket packet = iterator.next();
            if (packet.elapsedUs < elapsedUs) {
                ringBytes -= packet.data.length;
                iterator.remove();
            }
        }
    }

    private void applyFinishRequest() {
        long requested = requestedFinishDeadlineMs;
        if (requested != Long.MIN_VALUE) extendFinishDeadline(requested);
    }

    private void extendFinishDeadline(long deadlineMs) {
        finishDeadlineMs = Math.max(finishDeadlineMs, deadlineMs);
        if (outputRestartUntilElapsedMs != Long.MIN_VALUE)
            outputRestartUntilElapsedMs = Math.max(outputRestartUntilElapsedMs, finishDeadlineMs);
    }

    private void scheduleOutputContinuation(String failedVolumeId) {
        if (config == null || !config.fallbackEnabled || finishDeadlineMs == Long.MIN_VALUE) return;
        String inverseVolumeId = storage.oppositeVolumeId(failedVolumeId);
        scheduleOutputContinuationTo(inverseVolumeId);
    }

    private void scheduleOutputContinuationTo(String firstAttemptVolumeId) {
        if (config == null || !config.fallbackEnabled || firstAttemptVolumeId == null
                || finishDeadlineMs == Long.MIN_VALUE) return;
        if (finishDeadlineMs <= SystemClock.elapsedRealtime()) return;
        outputRestartVolumeId = firstAttemptVolumeId;
        outputRestartUntilElapsedMs = Math.max(outputRestartUntilElapsedMs, finishDeadlineMs);
    }

    private boolean outputRestartActive(long elapsedMs) {
        return outputRestartVolumeId != null && outputRestartUntilElapsedMs != Long.MIN_VALUE
                && elapsedMs < outputRestartUntilElapsedMs;
    }

    private void expireOutputRestart(long elapsedMs) {
        if (outputRestartUntilElapsedMs != Long.MIN_VALUE && elapsedMs >= outputRestartUntilElapsedMs)
            clearOutputRestart();
    }

    private void clearOutputRestart() {
        outputRestartVolumeId = null;
        outputRestartUntilElapsedMs = Long.MIN_VALUE;
    }

    private void signalVideoEndOfStream() {
        MediaCodec encoder = videoEncoder;
        if (encoder == null) return;
        try { encoder.signalEndOfInputStream(); }
        catch (RuntimeException failure) { Log.w(TAG, "video_eos_signal_failed", failure); }
    }

    private void stopAudioCapture() {
        audioRunning = false;
        AudioRecord recorder = audioRecord;
        if (recorder != null) {
            try { recorder.stop(); } catch (RuntimeException ignored) { }
        }
    }

    private long mapVideoPts(long ptsUs) {
        if (videoPtsOffsetUs == Long.MIN_VALUE) videoPtsOffsetUs = ptsOffset(ptsUs);
        return ptsUs + videoPtsOffsetUs;
    }

    private long mapAudioPts(long ptsUs) {
        if (audioPtsOffsetUs == Long.MIN_VALUE) audioPtsOffsetUs = ptsOffset(ptsUs);
        return ptsUs + audioPtsOffsetUs;
    }

    private static long ptsOffset(long ptsUs) {
        long nowUs = SystemClock.elapsedRealtimeNanos() / 1_000L;
        return ptsUs > 0 && Math.abs(ptsUs - nowUs) <= 5_000_000L ? 0 : nowUs - ptsUs;
    }

    private long wallFromElapsed(long elapsedMs) { return wallAnchorMs + elapsedMs - elapsedAnchorMs; }

    private long sizeOf(android.net.Uri uri) {
        try (android.content.res.AssetFileDescriptor descriptor = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            return descriptor == null ? 0 : Math.max(0, descriptor.getLength());
        } catch (IOException | RuntimeException unavailable) { return 0; }
    }

    private void notifyState(String value) {
        try { stateCallback.accept(value); }
        catch (RuntimeException failure) { Log.w(TAG, "state_callback_failed", failure); }
    }

    private static byte[] copyBytes(ByteBuffer source, int offset, int size) {
        ByteBuffer copy = source.duplicate();
        copy.position(offset);
        copy.limit(offset + size);
        byte[] result = new byte[size];
        copy.get(result);
        return result;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private static void closeDescriptor(MotionStorage.Output output) {
        if (output != null && output.descriptor != null) {
            try { output.descriptor.close(); } catch (IOException ignored) { }
        }
    }

    private static final Comparator<EncodedPacket> PACKET_ORDER = Comparator
            .comparingLong((EncodedPacket packet) -> packet.elapsedUs)
            .thenComparingInt(packet -> packet.track);

    private static final class Detection {
        final int level;
        final long elapsedMs;
        String eventId;
        long episodeStartMs;
        long episodeEndMs;
        long latestElapsedMs;
        Detection(int level, long elapsedMs) { this.level = level; this.elapsedMs = elapsedMs; }
        void mergeLatest(Detection newer) {
            episodeEndMs = newer.episodeEndMs;
            latestElapsedMs = newer.latestElapsedMs;
        }
    }

    private static final class Episode {
        final String id;
        final int level;
        final long startElapsedMs;
        long lastElapsedMs;
        Episode(String id, int level, long startElapsedMs, long lastElapsedMs) {
            this.id = id; this.level = level; this.startElapsedMs = startElapsedMs;
            this.lastElapsedMs = lastElapsedMs;
        }
    }

    private static final class EncodedPacket {
        final int track;
        final long elapsedUs;
        final int flags;
        final byte[] data;
        EncodedPacket(int track, long elapsedUs, int flags, byte[] data) {
            this.track = track; this.elapsedUs = elapsedUs; this.flags = flags; this.data = data;
        }
        boolean isKeyFrame() { return (flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0; }
    }

    private static final class TimelineItem {
        final EncodedPacket packet;
        final Detection detection;
        private TimelineItem(EncodedPacket packet, Detection detection) { this.packet = packet; this.detection = detection; }
        static TimelineItem packet(EncodedPacket value) { return new TimelineItem(value, null); }
        static TimelineItem detection(Detection value) { return new TimelineItem(null, value); }
        long elapsedUs() { return packet == null ? detection.elapsedMs * 1_000L : packet.elapsedUs; }
    }

    private static final class Clip {
        final MotionStorage.Output output;
        final MediaMuxer muxer;
        final MotionMediaLease lease;
        final MotionMediaIndex.Record record;
        final int videoTrack;
        final int audioTrack;
        final boolean audioIncluded;
        final long startElapsedMs;
        long lastVideoElapsedMs;
        long videoSamples;
        long audioSamples;
        long sampleBytes;
        long segmentSizeBytes;
        long storageWriteLimitBytes = Long.MAX_VALUE;
        long lastStorageCheckMs;
        final HashMap<String, Long> persistedEpisodeEnds = new HashMap<>();

        Clip(MotionStorage.Output output, MediaMuxer muxer, MotionMediaLease lease,
                MotionMediaIndex.Record record, int videoTrack, int audioTrack,
                boolean audioIncluded, long startElapsedMs) {
            this.output = output; this.muxer = muxer; this.lease = lease; this.record = record;
            this.videoTrack = videoTrack; this.audioTrack = audioTrack;
            this.audioIncluded = audioIncluded; this.startElapsedMs = startElapsedMs;
            this.lastVideoElapsedMs = startElapsedMs;
        }
    }
}
