/*
 * VDO.Ninja SDK for Android (Kotlin / Java) on Google's libwebrtc.
 * Copyright (c) 2025-2026 Steve Seguin
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package ninja.vdo.sdk

import android.content.Context
import ninja.vdo.sdk.core.Crypto
import ninja.vdo.sdk.core.CandidateBatch
import ninja.vdo.sdk.core.IceCandidate
import ninja.vdo.sdk.core.IceServer
import ninja.vdo.sdk.core.ListingEntry
import ninja.vdo.sdk.core.PasswordContext
import ninja.vdo.sdk.core.Protocol
import ninja.vdo.sdk.core.Sanitize
import ninja.vdo.sdk.core.SessionDescription
import ninja.vdo.sdk.core.SignalingClient
import ninja.vdo.sdk.core.Turn
import ninja.vdo.sdk.core.TurnMode
import ninja.vdo.sdk.core.VdoEvent
import ninja.vdo.sdk.core.VdoEventListener
import ninja.vdo.sdk.core.VdoEvents
import ninja.vdo.sdk.core.VdoNinjaOptions
import ninja.vdo.sdk.core.ViewOptions
import ninja.vdo.sdk.core.parseListing
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.RTCStats
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RTCStatsReport
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription as RtcSessionDescription
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** One WebRTC connection in one direction: `publisher` (we send) or `viewer` (we receive). */
class VdoConnection internal constructor(
    val uuid: String,
    val type: String,
    val pc: PeerConnection,
    /** Runs the native dispose off libwebrtc's threads (dispose blocks on the signaling thread). */
    private val disposer: Executor,
    private val onClose: () -> Unit,
) {
    var session: String = Crypto.generateSession()
        internal set
    var streamId: String? = null
        internal set
    internal var control: DataChannel? = null
    internal val reservedChannels = CopyOnWriteArrayList<DataChannel>()
    @Volatile internal var controlOpen = false
    @Volatile internal var closed = false
    @Volatile internal var authenticated = false
    @Volatile internal var authenticating = false
    internal var admissionCommitInProgress = false
    internal var trackSetInvalidated = false
    @Volatile internal var remoteAnswerApplied = false
    private var disposed = false
    @Volatile internal var connectedOnce = false
    @Volatile internal var connectedEventEmitted = false
    @Volatile internal var disconnectedEventEmitted = false
    internal var remoteSet = false
    internal var recovering = false
    internal var statsTask: ScheduledFuture<*>? = null
    internal val statsRequestInFlight = AtomicBoolean(false)
    internal var firstSenderFrameEmitted = false
    internal var lastIceState: PeerConnection.IceConnectionState? = null
    val info = JSONObject()
    val obsState = JSONObject()
    internal var viewOptions = ViewOptions()
    internal var allowAudio = true
    internal var allowVideo = true
    internal val iceBundle = ArrayList<IceCandidate>()
    internal var deferLocalIceCandidates = false
    internal val deferredLocalIceCandidates = ArrayList<IceCandidate>()
    internal var iceTask: ScheduledFuture<*>? = null
    internal var iceDelayMs = 70L
    internal var connectTask: ScheduledFuture<*>? = null
    internal var graceTask: ScheduledFuture<*>? = null
    internal var pingTask: ScheduledFuture<*>? = null
    internal var pendingPing = 0L
    internal var missedPings = 0
    internal val senders = ArrayList<Pair<RtpSender, String>>() // sender, kind
    val remoteTracks = CopyOnWriteArrayList<MediaStreamTrack>()

    /** Sends a JSON object on the control channel. */
    fun sendControl(msg: JSONObject): Boolean {
        val dc = control ?: return false
        if (!controlOpen || dc.state() != DataChannel.State.OPEN) return false
        return try {
            dc.send(DataChannel.Buffer(ByteBuffer.wrap(msg.toString().toByteArray(StandardCharsets.UTF_8)), false))
        } catch (_: Exception) {
            false
        }
    }

    internal fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        iceTask?.cancel(false)
        connectTask?.cancel(false)
        graceTask?.cancel(false)
        pingTask?.cancel(false)
        statsTask?.cancel(false)
        controlOpen = false
        try { control?.close() } catch (_: Exception) {}
        try { pc.close() } catch (_: Exception) {}
        runCatching(onClose)
        // close() is often called from a libwebrtc observer callback, where dispose() would deadlock,
        // so the native release runs on the worker. After VdoNinja.dispose() the worker is gone;
        // a straggler then stays undisposed rather than risk blocking a libwebrtc thread.
        try { disposer.execute(::dispose) } catch (_: RejectedExecutionException) {}
    }

    /** Releases the native peer connection and data channels. Never call on a libwebrtc thread. */
    private fun dispose() {
        synchronized(this) {
            if (disposed) return
            disposed = true
        }
        for (dc in reservedChannels + listOfNotNull(control)) {
            try { dc.unregisterObserver() } catch (_: Exception) {}
            try { dc.dispose() } catch (_: Exception) {}
        }
        reservedChannels.clear()
        control = null
        try { pc.dispose() } catch (_: Exception) {}
    }
}

/** A stream seen in listings or room notifications. */
class StreamInfo(val streamId: String, var uuid: String?, var state: String = "available")

/** Admission and release callbacks for SDK users that gate publisher media on an authenticated peer. */
interface PublisherPeerAdmission {
    fun tryReserve(uuid: String, sessionId: String): Boolean
    /** Called only after a password-verified answer is applied and the peer is connected. */
    fun authenticate(sessionId: String): Boolean
    fun release(sessionId: String)
}

/**
 * VDO.Ninja SDK for Android.
 *
 * Create one instance per signaling connection. Media comes from your own
 * `PeerConnectionFactory` tracks (camera via `Camera2Enumerator`, microphone via
 * `createAudioSource`); pass [PeerConnectionFactory] in or let the SDK create one.
 * Events are delivered on libwebrtc/OkHttp threads; hop to the main thread for UI.
 */
class VdoNinja @JvmOverloads constructor(
    context: Context,
    val options: VdoNinjaOptions = VdoNinjaOptions(),
    factory: PeerConnectionFactory? = null,
    val eglBase: EglBase = EglBase.create(),
    private val publisherAdmission: PublisherPeerAdmission? = null,
) {
    val factory: PeerConnectionFactory
    private val pw: PasswordContext = options.passwordContext()
    private val publisherInfo = JSONObject().apply {
        options.label?.let { Sanitize.label(it) }?.takeIf { it.isNotEmpty() }?.let { put("label", it) }
        options.metaJson?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { put("meta", it) }
    }
    private val signal: SignalingClient
    private val listeners = CopyOnWriteArrayList<VdoEventListener>()
    private val lock = Any()

    private var room: String? = null
    private var roomIntent: String? = null
    private var roomClaim = false
    private var roomWaiter: CompletableFuture<List<ListingEntry>>? = null

    @Volatile var streamId: String? = null
        private set
    @Volatile private var publishing = false
    private val localTracks = ArrayList<MediaStreamTrack>()

    private class Pair2 { var publisher: VdoConnection? = null; var viewer: VdoConnection? = null }
    private val connections = LinkedHashMap<String, Pair2>()
    private val publisherOffersInProgress = HashMap<String, String>()
    private val pendingViews = HashMap<String, ViewOptions>()
    private val viewIntent = HashMap<String, ViewOptions>()
    private val stoppedViews = HashSet<String>()
    private val viewWaiters = HashMap<String, MutableList<CompletableFuture<VdoConnection>>>()
    private val viewRetries = HashMap<String, ScheduledFuture<*>>()
    private val streams = LinkedHashMap<String, StreamInfo>()
    private val pendingIce = HashMap<String, MutableList<CandidateBatch>>()

    private val subscriptions = HashSet<String>()
    private val peerSubscriptions = HashMap<String, MutableSet<String>>()
    private val requestHandlers = ConcurrentHashMap<String, (Any?, String) -> Any?>()
    private class PendingRequest(val target: String?) { val future = CompletableFuture<Any?>() }
    private val pendingRequests = ConcurrentHashMap<String, PendingRequest>()

    @Volatile private var autoConnect = false
    private var autoFull = false
    private var iceServers: CompletableFuture<List<PeerConnection.IceServer>>? = null
    @Volatile private var intentional = false

    /** Blocking work (TURN fetch, native dispose, request handlers) stays off the timer and signaling threads. */
    private val worker: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "vdoninja-worker").apply { isDaemon = true }
    }

    init {
        this.factory = factory ?: run {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions(),
            )
            PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                .createPeerConnectionFactory()
        }
        signal = SignalingClient(options.host, options.maxReconnectAttempts, options.reconnectDelayMs, options.clientId, ::log).apply {
            onOpen = { reconnect ->
                emit(VdoEvent(if (reconnect) VdoEvents.RECONNECTED else VdoEvents.CONNECTED))
                if (reconnect) restoreIntent()
            }
            onClose = { intentional -> emit(VdoEvent(VdoEvents.DISCONNECTED, intentional = intentional)) }
            onReconnecting = { attempt -> emit(VdoEvent(VdoEvents.RECONNECTING, attempt = attempt)) }
            onReconnectFailed = { emit(VdoEvent(VdoEvents.RECONNECT_FAILED)) }
            onMessage = ::handleSignal
        }
    }

    private val scheduler get() = signal.scheduler

    // ------------------------------------------------------------- events

    fun addListener(listener: VdoEventListener) { listeners.add(listener) }
    fun removeListener(listener: VdoEventListener) { listeners.remove(listener) }

    /** Disconnects current peers for a UUID; stale connection objects are never signaled or removed. */
    fun disconnectPeer(uuid: String) {
        val peers = synchronized(lock) { allConnections().filter { it.uuid == uuid } }
        for (peer in peers) {
            val removed = synchronized(lock) {
                if (connection(peer.uuid, peer.type) !== peer) false
                else { removeConnection(peer); true }
            }
            if (!removed) continue
            sendBye(peer)
            peer.close()
            emitPeerDisconnected(peer)
        }
    }

    /** Closes one publisher generation without affecting a replacement peer or the published stream. */
    fun disconnectPublisherSession(sessionId: String): Boolean {
        return closePublisherSession(sessionId, authenticatedOnly = true)
    }

    /** Closes one provisional publisher generation when its publisher is being stopped. */
    fun cancelPublisherSession(sessionId: String): Boolean =
        closePublisherSession(sessionId, authenticatedOnly = false)

    private fun closePublisherSession(sessionId: String, authenticatedOnly: Boolean): Boolean {
        val peer = synchronized(lock) {
            allConnections().firstOrNull { it.type == "publisher" && it.session == sessionId }
        } ?: return false
        if (authenticatedOnly && !peer.authenticated) return false
        val removed = synchronized(lock) {
            if (connection(peer.uuid, "publisher") !== peer) false
            else { removeConnection(peer); true }
        }
        if (!removed) return false
        sendBye(peer)
        peer.close()
        emitPeerDisconnected(peer)
        return true
    }

    /** Extend's shell-safe network observer replaces WebRTC's app-only receiver. */
    fun networkChanged() {
        if (intentional) return
        try {
            worker.execute {
                if (intentional) return@execute
                val peers = synchronized(lock) { allConnections().filter { !it.closed } }
                peers.forEach { peer ->
                    if (!peer.recovering) recoverConnection(peer, "network_changed")
                }
            }
        } catch (stopped: java.util.concurrent.RejectedExecutionException) {
            if (!intentional) throw stopped
        }
    }

    /** Extend: stop video while leaving the authenticated data peer available for status. */
    fun removeTrack(track: MediaStreamTrack) {
        val (peers, stagedCandidates) = synchronized(lock) {
            val removed = localTracks.remove(track)
            val staged = if (removed) invalidateStagedPublisherCandidatesLocked() else emptyList()
            allConnections().filter { it.type == "publisher" && it.authenticated } to staged
        }
        cancelStagedPublisherCandidates(stagedCandidates)
        for (peer in peers) {
            val removed = synchronized(lock) { peer.senders.filter { it.first.track() == track } }
            for ((sender, _) in removed) peer.pc.removeTrack(sender)
            synchronized(lock) { peer.senders.removeAll(removed.toSet()) }
            if (removed.any { it.second == "video" }) stopPublisherStatsIfVideoDetached(peer)
            renegotiate(peer, false)
        }
    }

    /** Convenience: listen for one event type. Returns a runnable that removes the listener. */
    fun on(type: String, handler: (VdoEvent) -> Unit): () -> Unit {
        val l = VdoEventListener { if (it.type == type) handler(it) }
        listeners.add(l)
        return { listeners.remove(l) }
    }

    private fun emit(e: VdoEvent) {
        for (l in listeners) {
            try { l.onEvent(e) } catch (t: Throwable) { log("listener threw: $t") }
        }
    }

    private fun emitPeerDisconnected(c: VdoConnection) {
        val shouldEmit = synchronized(lock) {
            if (c.disconnectedEventEmitted) false
            else { c.disconnectedEventEmitted = true; true }
        }
        if (shouldEmit) {
            if (c.type == "publisher") emitPublisherDiagnostic(c.session, "session_ended", "authenticated=${c.authenticated}")
            emit(VdoEvent(
                VdoEvents.PEER_DISCONNECTED,
                uuid = c.uuid,
                sessionId = c.session,
                streamId = c.streamId,
                connectionType = c.type,
            ))
        }
    }

    private fun emitPeerConnectedIfReady(c: VdoConnection) {
        val shouldEmit = synchronized(lock) {
            if (connection(c.uuid, c.type) !== c || c.closed || !c.connectedOnce ||
                c.type == "publisher" && !c.authenticated || c.connectedEventEmitted) false
            else { c.connectedEventEmitted = true; true }
        }
        if (shouldEmit) {
            setStreamState(c.streamId, "connected")
            emit(VdoEvent(
                VdoEvents.PEER_CONNECTED,
                uuid = c.uuid,
                sessionId = c.session,
                streamId = c.streamId,
                connectionType = c.type,
            ))
        }
    }

    private fun emitPublisherSessionFailure(uuid: String, sessionId: String, stage: String, failure: Throwable) {
        emit(VdoEvent(
            VdoEvents.CONNECTION_FAILED,
            uuid = uuid,
            sessionId = sessionId,
            streamId = streamId,
            connectionType = "publisher",
            stage = stage,
            failure = failure,
        ))
    }

    private fun emitPublisherDiagnostic(sessionId: String, stage: String, detail: String? = null, failure: Throwable? = null) {
        emit(VdoEvent(
            VdoEvents.SESSION_DIAGNOSTIC,
            sessionId = sessionId,
            connectionType = "publisher",
            stage = stage,
            message = detail,
            failure = failure,
        ))
    }

    private fun isActiveVideoPublisher(c: VdoConnection): Boolean = synchronized(lock) {
        connection(c.uuid, "publisher") === c && !c.closed && c.authenticated &&
            c.senders.any { (sender, kind) -> kind == "video" && sender.track() != null }
    }

    private fun startPublisherStats(c: VdoConnection) {
        var failure: Throwable? = null
        synchronized(lock) {
            if (connection(c.uuid, "publisher") !== c || c.closed || !c.authenticated || c.statsTask != null ||
                c.senders.none { (sender, kind) -> kind == "video" && sender.track() != null }) return
            try {
                c.statsTask = scheduler.scheduleAtFixedRate({ collectPublisherStats(c) }, 0, 10, TimeUnit.SECONDS)
            } catch (caught: Throwable) {
                failure = caught
            }
        }
        failure?.let { emitPublisherDiagnostic(c.session, "stats_schedule_failed", failure = it) }
    }

    private fun stopPublisherStatsIfVideoDetached(c: VdoConnection) {
        val task = synchronized(lock) {
            if (c.senders.any { (sender, kind) -> kind == "video" && sender.track() != null }) null
            else c.statsTask.also { c.statsTask = null }
        }
        task?.cancel(false)
    }

    private fun collectPublisherStats(c: VdoConnection) {
        if (!c.statsRequestInFlight.compareAndSet(false, true)) return
        if (!isActiveVideoPublisher(c)) {
            c.statsRequestInFlight.set(false)
            return
        }
        try {
            c.pc.getStats(object : RTCStatsCollectorCallback {
                override fun onStatsDelivered(report: RTCStatsReport) {
                    try {
                        if (!isActiveVideoPublisher(c)) return
                        val stats = report.statsMap.values
                        val outboundVideo = stats.filter { stat ->
                            if (stat.type != "outbound-rtp") return@filter false
                            val mediaKind = stat.members["kind"] as? String ?: stat.members["mediaType"] as? String
                            mediaKind.equals("video", ignoreCase = true)
                        }
                        val framesSent = sumStat(outboundVideo, "framesSent")
                        val bytesSent = sumStat(outboundVideo, "bytesSent")
                        val iceState = c.pc.iceConnectionState().name.lowercase()
                        val route = selectedCandidateTypes(stats)
                        val detail = "ice=$iceState outbound_video_frames_total=${framesSent ?: "unavailable"} " +
                            "outbound_video_bytes_total=${bytesSent ?: "unavailable"} " +
                            "selected_local_candidate_type=${route.first} selected_remote_candidate_type=${route.second}"
                        emitPublisherDiagnostic(c.session, "rtc_stats", detail)
                        val firstFrame = synchronized(lock) {
                            if (framesSent != null && framesSent > 0 && !c.firstSenderFrameEmitted &&
                                connection(c.uuid, "publisher") === c && !c.closed && c.authenticated) {
                                c.firstSenderFrameEmitted = true
                                true
                            } else false
                        }
                        if (firstFrame) emitPublisherDiagnostic(c.session, "first_sender_frame",
                            "outbound_video_frames_total=$framesSent outbound_video_bytes_total=${bytesSent ?: "unavailable"}")
                    } catch (failure: Throwable) {
                        if (isActiveVideoPublisher(c)) emitPublisherDiagnostic(c.session, "stats_read_failed", failure = failure)
                    } finally {
                        c.statsRequestInFlight.set(false)
                    }
                }
            })
        } catch (failure: Throwable) {
            c.statsRequestInFlight.set(false)
            if (isActiveVideoPublisher(c)) emitPublisherDiagnostic(c.session, "stats_request_failed", failure = failure)
        }
    }

    private fun sumStat(stats: List<RTCStats>, key: String): Long? {
        val values = stats.mapNotNull { (it.members[key] as? Number)?.toLong() }
        return values.takeIf { it.isNotEmpty() }?.sum()
    }

    private fun selectedCandidateTypes(stats: Collection<RTCStats>): Pair<String, String> {
        val byId = stats.associateBy { it.id }
        val selectedId = stats.asSequence().filter { it.type == "transport" }
            .mapNotNull { it.members["selectedCandidatePairId"] as? String }.firstOrNull()
        val pair = selectedId?.let(byId::get) ?: stats.firstOrNull { stat ->
            stat.type == "candidate-pair" && (
                stat.members["selected"] == true ||
                    stat.members["nominated"] == true && stat.members["state"] == "succeeded"
                )
        }
        fun candidateType(idField: String): String {
            val id = pair?.members?.get(idField) as? String ?: return "unknown"
            val raw = byId[id]?.members?.get("candidateType") as? String ?: return "unknown"
            return raw.lowercase().takeIf { it in setOf("host", "srflx", "prflx", "relay") } ?: "unknown"
        }
        return candidateType("localCandidateId") to candidateType("remoteCandidateId")
    }

    private fun failPublisherConnection(
        c: VdoConnection,
        stage: String,
        failure: Throwable,
        skipIfAdmissionCommitInProgress: Boolean = false,
    ) {
        if (c.type != "publisher") return
        val removed = synchronized(lock) {
            if (connection(c.uuid, "publisher") !== c ||
                (skipIfAdmissionCommitInProgress && c.admissionCommitInProgress)) false
            else { removeConnection(c); true }
        }
        if (!removed) return
        emitPeerDisconnected(c)
        try { c.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
        emitPublisherSessionFailure(c.uuid, c.session, stage, failure)
    }

    private fun failPublisherSession(uuid: String, sessionId: String, stage: String, failure: Throwable) {
        val c = synchronized(lock) {
            connections[uuid]?.publisher?.takeIf { it.session == sessionId }
        }
        if (c != null) failPublisherConnection(c, stage, failure)
        else emitPublisherSessionFailure(uuid, sessionId, stage, failure)
    }

    /** Caller holds the SDK lock; mark before callbacks so queued admission cannot commit stale tracks. */
    private fun invalidateStagedPublisherCandidatesLocked(): List<VdoConnection> =
        allConnections().filter {
            it.type == "publisher" && it.authenticating && !it.authenticated && !it.trackSetInvalidated
        }.onEach { it.trackSetInvalidated = true }

    private fun cancelStagedPublisherCandidates(candidates: List<VdoConnection>) {
        for (candidate in candidates) {
            val failure = IllegalStateException("published track set changed during candidate admission")
            try { publisherAdmission?.release(candidate.session) }
            catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            failPublisherConnection(candidate, "published_tracks_changed", failure)
        }
    }

    private fun log(message: String) {
        if (options.debug) android.util.Log.d("vdoninja", message)
    }

    // ---------------------------------------------------------- lifecycle

    val connected: Boolean get() = signal.connected

    /** Opens the signaling connection (blocking, up to [timeoutMs]). */
    @Throws(java.io.IOException::class)
    @JvmOverloads
    fun connect(timeoutMs: Long = 15_000) {
        intentional = false
        signal.connect(timeoutMs)
        iceServerFuture() // prefetch the TURN list so the first offer does not block on HTTP
    }

    private fun restoreIntent() {
        roomIntent?.let { r -> runCatching { joinRoom(r, roomClaim, 0) } }
        val sid = streamId
        if (publishing && sid != null) signal.send(JSONObject().put("request", "seed").put("streamID", pw.hashStreamId(sid)))
        val views = synchronized(lock) { viewIntent.filterKeys { it !in stoppedViews }.toMap() }
        for ((sid2, opts) in views) requestView(sid2, opts)
    }

    /** Sends bye to every peer, closes all connections and the socket. */
    fun disconnect() {
        intentional = true
        autoConnect = false
        val conns: List<VdoConnection>
        val pendingPublishers: List<String>
        synchronized(lock) {
            viewRetries.values.forEach { it.cancel(false) }
            viewRetries.clear()
            publishing = false
            conns = allConnections()
            pendingPublishers = publisherOffersInProgress.values.toList()
            publisherOffersInProgress.clear()
        }
        pendingPublishers.forEach { publisherAdmission?.release(it) }
        conns.forEach { sendBye(it) }
        if (conns.isNotEmpty()) Thread.sleep(50)
        conns.forEach { it.close() }
        synchronized(lock) {
            connections.clear()
            pendingViews.clear()
            viewIntent.clear()
            stoppedViews.clear()
            pendingIce.clear()
            viewWaiters.values.flatten().forEach { it.completeExceptionally(IllegalStateException("disconnected")) }
            viewWaiters.clear()
            roomWaiter?.completeExceptionally(IllegalStateException("disconnected"))
            roomWaiter = null
            room = null
            roomIntent = null
            publishing = false
            streamId = null
            localTracks.clear()
        }
        pendingRequests.values.forEach { it.future.completeExceptionally(IllegalStateException("disconnected")) }
        pendingRequests.clear()
        signal.close()
    }

    /** Disconnects and releases the timer and worker threads. The factory and EGL context stay owned by the caller. */
    fun dispose() {
        disconnect()
        signal.shutdown()
        worker.shutdown() // queued native disposes still run
    }

    // -------------------------------------------------------------- rooms

    val currentRoom: String? get() = room

    /** Joins a room; blocks for the listing up to [timeoutMs] (0 = do not wait). */
    @JvmOverloads
    fun joinRoom(room: String? = null, claim: Boolean = false, timeoutMs: Long = 10_000): List<ListingEntry> {
        val clean = Sanitize.room(room ?: options.room)
        require(clean.isNotEmpty()) { "room is required" }
        check(connected) { "not connected" }
        val waiter = CompletableFuture<List<ListingEntry>>()
        val msg = JSONObject().put("request", "joinroom").put("roomid", pw.hashRoom(clean))
        synchronized(lock) {
            roomIntent = clean
            roomClaim = claim
            this.room = clean
            roomWaiter = waiter
            if (claim) msg.put("claim", true)
            val sid = streamId
            if (publishing && sid != null) msg.put("streamID", pw.hashStreamId(sid))
        }
        signal.send(msg)
        if (timeoutMs <= 0) return emptyList()
        return try {
            waiter.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            synchronized(lock) { if (roomWaiter === waiter) roomWaiter = null }
            throw TimeoutException("no listing received for room $clean")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    fun leaveRoom() {
        val left = synchronized(lock) { val r = room; room = null; roomIntent = null; r } ?: return
        signal.send(JSONObject().put("leave", true)) // the JavaScript SDK sends this too
        emit(VdoEvent(VdoEvents.ROOM_LEFT, data = left))
    }

    // --------------------------------------------------------- publishing

    /** Registers a data-only stream. Returns the sanitized stream ID. */
    @JvmOverloads
    fun announce(streamId: String? = null, room: String? = null): String = seed(streamId, room, emptyList())

    /** Publishes [tracks] (from your `PeerConnectionFactory`) to every viewer. */
    @JvmOverloads
    fun publish(tracks: List<MediaStreamTrack>, streamId: String? = null, room: String? = null): String = seed(streamId, room, tracks)

    private fun seed(streamId: String?, room: String?, tracks: List<MediaStreamTrack>): String {
        check(connected) { "not connected" }
        // One publish per signaling connection, like the JavaScript SDK.
        check(!publishing) { "already publishing on this connection; disconnect and reconnect to publish a different stream" }
        val sid = Sanitize.streamId(streamId)
        if (!room.isNullOrEmpty()) {
            try { joinRoom(room) } catch (_: TimeoutException) { log("joinroom did not answer; seeding anyway") }
        }
        synchronized(lock) {
            this.streamId = sid
            publishing = true
            localTracks.clear()
            localTracks.addAll(tracks)
        }
        signal.send(JSONObject().put("request", "seed").put("streamID", pw.hashStreamId(sid)))
        emit(VdoEvent(VdoEvents.PUBLISHING, streamId = sid, data = JSONObject().put("dataOnly", tracks.isEmpty())))
        return sid
    }

    /** Adds a track to the published stream and renegotiates every viewer. */
    fun addTrack(track: MediaStreamTrack) {
        check(publishing) { "not publishing" }
        val (conns, stagedCandidates) = synchronized(lock) {
            localTracks.add(track)
            val staged = invalidateStagedPublisherCandidatesLocked()
            allConnections().filter { it.type == "publisher" && it.authenticated } to staged
        }
        cancelStagedPublisherCandidates(stagedCandidates)
        for (c in conns) {
            val sender = c.pc.addTrack(track, listOf("vdoninja"))
            synchronized(lock) { c.senders.add(sender to track.kind()) }
            if (track.kind() == "video") startPublisherStats(c)
            renegotiate(c, false)
        }
    }

    /** Replaces the published track of the same kind on every viewer (camera/mic switching), no renegotiation. */
    fun replaceTrack(newTrack: MediaStreamTrack) {
        val (conns, stagedCandidates) = synchronized(lock) {
            localTracks.removeAll { it.kind() == newTrack.kind() }
            localTracks.add(newTrack)
            val staged = invalidateStagedPublisherCandidatesLocked()
            allConnections().filter { it.type == "publisher" && it.authenticated } to staged
        }
        cancelStagedPublisherCandidates(stagedCandidates)
        for (c in conns) {
            for ((sender, kind) in c.senders.toList()) {
                val allowed = if (kind == "video") c.allowVideo else c.allowAudio
                if (kind == newTrack.kind() && allowed) sender.setTrack(newTrack, false)
            }
        }
    }

    fun stopPublishing() {
        val conns: List<VdoConnection>
        val pendingPublishers: List<String>
        val sid: String?
        synchronized(lock) {
            conns = allConnections().filter { it.type == "publisher" }
            pendingPublishers = publisherOffersInProgress.values.toList()
            publisherOffersInProgress.clear()
            sid = streamId
            publishing = false
            streamId = null
            localTracks.clear()
        }
        pendingPublishers.forEach { publisherAdmission?.release(it) }
        conns.forEach { sendBye(it) }
        if (conns.isNotEmpty()) Thread.sleep(50)
        for (c in conns) {
            synchronized(lock) { removeConnection(c) }
            c.close()
        }
        emit(VdoEvent(VdoEvents.PUBLISHING_STOPPED, streamId = sid))
    }

    // ------------------------------------------------------------ viewing

    /**
     * Requests a stream and blocks until the publisher's offer arrives (up to
     * [timeoutMs]). Throws [TimeoutException] when the publisher is not online
     * yet; the request stays pending and connects when the stream appears.
     * `timeoutMs == 0` returns null immediately with a pending request.
     */
    @JvmOverloads
    fun view(streamId: String, options: ViewOptions = ViewOptions(), timeoutMs: Long = 15_000): VdoConnection? {
        check(connected) { "not connected" }
        val sid = requestView(Sanitize.streamId(streamId), options)
        if (timeoutMs <= 0) return null
        val waiter = CompletableFuture<VdoConnection>()
        synchronized(lock) { viewWaiters.getOrPut(sid) { ArrayList() }.add(waiter) }
        return try {
            waiter.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            synchronized(lock) { viewWaiters[sid]?.remove(waiter) }
            throw TimeoutException("publisher of $sid did not answer; the request stays pending")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    /**
     * Registers the view intent and sends the play request. Like the JavaScript SDK, a
     * play that gets no offer is re-sent every `viewRetryIntervalMs` until the stream
     * appears or [stopViewing] is called; the offer cancels the timer.
     */
    private fun requestView(sid: String, options: ViewOptions): String {
        synchronized(lock) {
            stoppedViews.remove(sid)
            pendingViews[sid] = options
            viewIntent[sid] = options
        }
        signal.send(JSONObject().put("request", "play").put("streamID", pw.hashStreamId(sid)))
        scheduleViewRetry(sid, this.options.viewRetryIntervalMs)
        return sid
    }

    /** (Re)arms the view retry for [sid]; a newer schedule replaces an older one. */
    private fun scheduleViewRetry(sid: String, delayMs: Long) {
        synchronized(lock) {
            if (intentional || sid in stoppedViews) return
            viewRetries.remove(sid)?.cancel(false)
            viewRetries[sid] = scheduler.schedule({
                val opts: ViewOptions?
                synchronized(lock) {
                    viewRetries.remove(sid)
                    opts = if (intentional || sid in stoppedViews || !connected || findViewerForStream(sid) != null) null
                    else viewIntent[sid] ?: ViewOptions()
                }
                if (opts != null) {
                    log("retrying view of $sid")
                    requestView(sid, opts)
                }
            }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    fun stopViewing(streamId: String) {
        val sid = Sanitize.streamId(streamId)
        val conns: List<VdoConnection>
        synchronized(lock) {
            stoppedViews.add(sid)
            pendingViews.remove(sid)
            viewIntent.remove(sid)
            viewRetries.remove(sid)?.cancel(false)
            viewWaiters.remove(sid)?.forEach { it.completeExceptionally(IllegalStateException("viewing stopped")) }
            conns = allConnections().filter { it.type == "viewer" && it.streamId == sid }
        }
        conns.forEach { sendBye(it) }
        if (conns.isNotEmpty()) Thread.sleep(50)
        for (c in conns) {
            synchronized(lock) { removeConnection(c) }
            c.close()
        }
        emit(VdoEvent(VdoEvents.VIEWING_STOPPED, streamId = sid))
    }

    /** Streams seen so far. */
    fun streams(): List<StreamInfo> = synchronized(lock) { streams.values.toList() }

    /** UUIDs of peers with at least one connection. */
    fun peers(): List<String> = synchronized(lock) { connections.keys.toList() }

    /** Finds a connection to [uuid]; null [type] prefers the publisher side. */
    @JvmOverloads
    fun connection(uuid: String, type: String? = null): VdoConnection? = synchronized(lock) {
        val pair = connections[uuid] ?: return null
        when (type) {
            "publisher" -> pair.publisher
            "viewer" -> pair.viewer
            else -> pair.publisher ?: pair.viewer
        }
    }

    // -------------------------------------------------------- autoConnect

    /** Connects, joins [room], announces and views every other member (half mesh by default). */
    @JvmOverloads
    fun autoConnect(room: String, streamId: String? = null, fullMode: Boolean = false): String {
        if (!connected) connect()
        val sid = Sanitize.streamId(streamId)
        synchronized(lock) {
            this.streamId = sid
            autoConnect = true
            autoFull = fullMode
        }
        joinRoom(room)
        announce(sid)
        streams().forEach { autoConsider(it.streamId) }
        return sid
    }

    fun stopAutoConnect() { autoConnect = false }

    private fun autoConsider(sid: String) {
        val go: Boolean
        synchronized(lock) {
            val mine = streamId
            go = autoConnect && mine != null && sid != mine && sid !in stoppedViews &&
                findViewerForStream(sid) == null && !pendingViews.containsKey(sid) && (autoFull || sid < mine)
        }
        if (go) requestView(sid, ViewOptions())
    }

    // --------------------------------------------------------------- data

    /**
     * Sends application data as `{"pipe": data}`. [data] may be a JSONObject,
     * JSONArray, String, Number or Boolean. [uuid] null = every peer;
     * [preference] any|publisher|viewer|all.
     */
    @JvmOverloads
    fun sendData(data: Any?, uuid: String? = null, type: String? = null, preference: String = "any", allowFallback: Boolean = false): Boolean {
        val text = JSONObject().put("pipe", data ?: JSONObject.NULL).toString()
        val buf = text.toByteArray(StandardCharsets.UTF_8)
        var sent = false
        val pairs = synchronized(lock) { connections.filterKeys { uuid == null || it == uuid }.values.map { it.publisher to it.viewer } }
        fun trySend(c: VdoConnection?): Boolean {
            if (c == null || (type != null && c.type != type) || !c.controlOpen) return false
            val dc = c.control ?: return false
            return try {
                val ok = dc.send(DataChannel.Buffer(ByteBuffer.wrap(buf), false))
                if (ok) log("control data-channel message sent (payload omitted)")
                ok
            } catch (_: Exception) { false }
        }
        for ((pub, view) in pairs) {
            sent = when (preference) {
                "publisher" -> trySend(pub)
                "viewer" -> trySend(view)
                "all" -> { val a = trySend(pub); val b = trySend(view); a || b }
                else -> trySend(pub) || trySend(view)
            } || sent
        }
        if (sent || !allowFallback || !connected) return sent
        val targets = if (uuid != null) listOf(uuid) else peers()
        for (t in targets) {
            if (signal.send(JSONObject().put("__fallback", true).put("UUID", t).put("pipe", data ?: JSONObject.NULL))) sent = true
        }
        return sent
    }

    /** Pings peers over the control channel; replies emit `peerLatency`. */
    @JvmOverloads
    fun sendPing(uuid: String? = null): Boolean {
        val now = System.currentTimeMillis()
        var sent = false
        for (c in synchronized(lock) { allConnections() }) {
            if (uuid != null && c.uuid != uuid) continue
            if (!c.controlOpen) continue
            c.pendingPing = now
            if (c.sendControl(JSONObject().put("ping", now))) sent = true
        }
        return sent
    }

    fun subscribe(vararg channels: String) {
        synchronized(lock) { subscriptions.addAll(channels) }
        sendData(JSONObject().put("type", "subscribe").put("channels", JSONArray(channels.toList())), preference = "all")
    }

    fun unsubscribe(vararg channels: String) {
        synchronized(lock) { subscriptions.removeAll(channels.toSet()) }
        sendData(JSONObject().put("type", "unsubscribe").put("channels", JSONArray(channels.toList())), preference = "all")
    }

    @JvmOverloads
    fun publishToChannel(channel: String, data: Any?, uuid: String? = null): Boolean = sendData(
        JSONObject().put("type", "channelMessage").put("channel", channel).put("data", data ?: JSONObject.NULL).put("timestamp", System.currentTimeMillis()),
        uuid = uuid,
    )

    /** Registers a request handler `(data, fromUuid) -> response`; null removes it. Handlers run on worker threads. */
    fun onRequest(requestType: String, handler: ((data: Any?, fromUuid: String) -> Any?)?) {
        if (handler == null) requestHandlers.remove(requestType) else requestHandlers[requestType] = handler
    }

    /** Sends a request and blocks for the response. */
    @JvmOverloads
    fun request(requestType: String, data: Any?, targetUuid: String, timeoutMs: Long = 5000): Any? {
        val id = Crypto.generateUuid()
        val p = PendingRequest(targetUuid)
        pendingRequests[id] = p
        val ok = sendData(
            JSONObject().put("type", "request").put("requestType", requestType).put("requestId", id).put("data", data ?: JSONObject.NULL),
            uuid = targetUuid,
        )
        if (!ok) {
            pendingRequests.remove(id)
            throw IllegalStateException("no open data channel to $targetUuid")
        }
        return try {
            p.future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            pendingRequests.remove(id)
            throw TimeoutException("request $requestType timed out")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    fun respond(requestId: String, data: Any?, targetUuid: String): Boolean =
        sendData(JSONObject().put("type", "response").put("requestId", requestId).put("data", data ?: JSONObject.NULL), uuid = targetUuid)

    // ------------------------------------------------------ connections

    private fun allConnections(): List<VdoConnection> = connections.values.flatMap { listOfNotNull(it.publisher, it.viewer) }

    private fun findViewerForStream(sid: String): VdoConnection? = allConnections().firstOrNull { it.type == "viewer" && it.streamId == sid }

    private fun removeConnection(c: VdoConnection) {
        val pair = connections[c.uuid] ?: return
        if (pair.publisher === c) pair.publisher = null
        if (pair.viewer === c) pair.viewer = null
        if (pair.publisher == null && pair.viewer == null) {
            connections.remove(c.uuid)
            peerSubscriptions.remove(c.uuid)
        }
    }

    /** Starts (once) the ICE server resolution on a worker thread; `Turn.fetch` blocks for up to 10 s. */
    private fun iceServerFuture(): CompletableFuture<List<PeerConnection.IceServer>> = synchronized(lock) {
        iceServers ?: CompletableFuture.supplyAsync({ buildIceServers() }, worker).also { iceServers = it }
    }

    /** Cached ICE servers; waits for the prefetch only when it has not finished yet. */
    private fun iceServerList(): List<PeerConnection.IceServer> = try {
        iceServerFuture().get()
    } catch (e: java.util.concurrent.ExecutionException) {
        synchronized(lock) { iceServers = null } // retry next time
        throw e.cause ?: e
    }

    private fun buildIceServers(): List<PeerConnection.IceServer> {
        val out = ArrayList<IceServer>()
        for (s in options.stunServers ?: Turn.DEFAULT_STUN) out.add(IceServer(listOf(s)))
        when (options.turnMode) {
            TurnMode.DISABLED -> {}
            TurnMode.CUSTOM -> out.addAll(options.turnServers)
            TurnMode.AUTO -> out.addAll(Turn.select(Turn.fetch(options.turnUrl), forceTcp = options.forceTcp))
        }
        return out.map { s ->
            PeerConnection.IceServer.builder(s.urls).apply {
                if (s.username != null) setUsername(s.username)
                if (s.credential != null) setPassword(s.credential)
            }.createIceServer()
        }
    }

    private fun createConnection(uuid: String, type: String, sessionId: String? = null): VdoConnection {
        if (type == "publisher" && !publishing) throw IllegalStateException("not publishing")
        val config = PeerConnection.RTCConfiguration(iceServerList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            if (options.forceTurn) iceTransportsType = PeerConnection.IceTransportsType.RELAY
        }
        lateinit var conn: VdoConnection
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) { handleIceConnectionState(conn, state) }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: org.webrtc.IceCandidate) { handleLocalCandidate(conn, candidate) }
            override fun onIceCandidatesRemoved(candidates: Array<out org.webrtc.IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dc: DataChannel) { handleIncomingChannel(conn, dc) }
            override fun onRenegotiationNeeded() {}
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) { handlePeerState(conn, newState) }
            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track() ?: return
                if (conn.closed) return
                conn.remoteTracks.add(track)
                emit(VdoEvent(VdoEvents.TRACK, uuid = uuid, streamId = conn.streamId, connectionType = type, track = track))
            }
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        }
        val pc = factory.createPeerConnection(config, observer) ?: throw IllegalStateException("createPeerConnection failed")
        conn = VdoConnection(uuid, type, pc, worker) {
            if (type == "publisher") publisherAdmission?.release(conn.session)
        }.apply { if (sessionId != null) session = sessionId }
        val old: VdoConnection?
        var publishingStopped = false
        synchronized(lock) {
            if (type == "publisher" && !publishing) {
                old = null
                publishingStopped = true
            } else {
                val pair = connections.getOrPut(uuid) { Pair2() }
                old = if (type == "publisher") pair.publisher else pair.viewer
                old?.let { removeConnection(it) }
                if (type == "publisher") pair.publisher = conn else pair.viewer = conn
            }
        }
        if (publishingStopped) {
            conn.close()
            throw IllegalStateException("not publishing")
        }
        old?.close()
        conn.connectTask = scheduler.schedule({
            if (conn.closed) return@schedule
            if (conn.type == "publisher" && conn.connectedOnce && !conn.authenticated) {
                failPublisherConnection(
                    conn,
                    "connection_timeout",
                    TimeoutException("publisher answer was not authenticated before connection timeout"),
                    skipIfAdmissionCommitInProgress = true,
                )
            } else if (!conn.connectedOnce) {
                recoverConnection(conn, "timeout")
            }
        }, options.connectionTimeoutMs, TimeUnit.MILLISECONDS)
        return conn
    }

    private fun addLocalTracks(c: VdoConnection, tracks: List<MediaStreamTrack>) {
        for (t in tracks) {
            val sender = c.pc.addTrack(t, listOf("vdoninja"))
            synchronized(lock) { c.senders.add(sender to t.kind()) }
        }
    }

    /**
     * Prepares existing tracks before admission, then commits only after protected SDP and ICE.
     * A data-only peer may connect before its answer callback runs, so both transitions retry here.
     */
    private fun completePublisherAuthentication(c: VdoConnection) {
        if (c.type != "publisher") return
        val shouldAdmit = synchronized(lock) {
            if (connection(c.uuid, "publisher") !== c || c.closed || c.authenticated || c.authenticating ||
                !c.remoteAnswerApplied || !c.connectedOnce) false
            else { c.authenticating = true; true }
        }
        if (!shouldAdmit) return

        val tracks = synchronized(lock) { localTracks.toList() }
        if (tracks.isEmpty()) {
            admitPublisherCandidate(c, null)
            return
        }

        synchronized(lock) {
            if (connection(c.uuid, "publisher") !== c || c.closed) return
            // Keep candidates from this replacement offer local until owner admission commits.
            c.deferLocalIceCandidates = true
        }
        try {
            addLocalTracks(c, tracks)
        } catch (failure: Throwable) {
            failPublisherConnection(c, "attach_local_tracks", failure)
            return
        }
        try {
            c.pc.createOffer(sdpObserver(createOffer@{ offer ->
                if (offer == null) {
                    failPublisherConnection(c, "create_offer", IllegalStateException("createOffer returned no description"))
                    return@createOffer
                }
                if (!isCurrentPublisherConnection(c)) return@createOffer
                emitPublisherDiagnostic(c.session, "local_offer_created")
                try {
                    c.pc.setLocalDescription(sdpObserver(setLocal@{
                        if (!isCurrentPublisherConnection(c)) return@setLocal
                        emitPublisherDiagnostic(c.session, "local_sdp_applied")
                        admitPublisherCandidate(c, offer)
                    }, { error ->
                        failPublisherConnection(c, "set_local_description", IllegalStateException(error))
                    }), offer)
                } catch (failure: Throwable) {
                    failPublisherConnection(c, "set_local_description", failure)
                }
            }, { error ->
                failPublisherConnection(c, "create_offer", IllegalStateException(error))
            }), MediaConstraints())
        } catch (failure: Throwable) {
            failPublisherConnection(c, "create_offer", failure)
        }
    }

    private fun admitPublisherCandidate(c: VdoConnection, preparedOffer: RtcSessionDescription?) {
        val commitStarted = synchronized(lock) {
            if (connection(c.uuid, "publisher") !== c || c.closed || c.authenticated || !c.authenticating ||
                c.trackSetInvalidated) false
            else { c.admissionCommitInProgress = true; true }
        }
        if (!commitStarted) return
        val admissionAccepted = try {
            publisherAdmission?.authenticate(c.session) != false
        } catch (failure: Throwable) {
            failPublisherConnection(c, "viewer_admission", failure)
            return
        }
        if (!admissionAccepted) {
            failPublisherConnection(c, "viewer_admission", SecurityException("authenticated viewer was not admitted"))
            return
        }
        val admitted = synchronized(lock) {
            if (connection(c.uuid, "publisher") !== c || c.closed || c.trackSetInvalidated) {
                c.authenticating = false
                c.admissionCommitInProgress = false
                false
            } else {
                c.authenticating = false
                c.authenticated = true
                c.admissionCommitInProgress = false
                true
            }
        }
        if (!admitted) {
            publisherAdmission?.release(c.session)
            return
        }
        c.connectTask?.cancel(false)
        emitPublisherDiagnostic(c.session, "viewer_admitted", "ice=connected password_protected_sdp=applied")
        if (preparedOffer != null) {
            try {
                sendDescription(c, preparedOffer)
                flushDeferredLocalIceCandidates(c)
            } catch (failure: Throwable) {
                failPublisherConnection(c, "send_local_description", failure)
                return
            }
        }
        startPublisherStats(c)
        emitPeerConnectedIfReady(c)
    }

    private fun isCurrentPublisherConnection(c: VdoConnection): Boolean = synchronized(lock) {
        connection(c.uuid, "publisher") === c && !c.closed
    }

    private fun sdpObserver(onSuccess: (RtcSessionDescription?) -> Unit, onFailure: (String) -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(desc: RtcSessionDescription?) = onSuccess(desc)
        override fun onSetSuccess() = onSuccess(null)
        override fun onCreateFailure(error: String?) = onFailure(error ?: "create failed")
        override fun onSetFailure(error: String?) = onFailure(error ?: "set failed")
    }

    private fun sendDescription(c: VdoConnection, desc: RtcSessionDescription, includeStreamId: Boolean = true) {
        val msg = JSONObject().put("UUID", c.uuid).put("session", c.session)
        val sid = c.streamId
        if (includeStreamId && sid != null) msg.put("streamID", pw.hashStreamId(sid))
        pw.encryptInto(msg, "description", SessionDescription(desc.type.canonicalForm(), desc.description).toJson())
        sendPreparedDescription(c, msg)
    }

    private fun sendPreparedDescription(c: VdoConnection, msg: JSONObject) {
        if (c.controlOpen) {
            val dcMsg = JSONObject().put("description", msg.get("description")).put("session", c.session)
            if (msg.has("vector")) dcMsg.put("vector", msg.get("vector"))
            if (c.sendControl(dcMsg)) {
                if (c.type == "publisher") emitPublisherDiagnostic(c.session, "local_sdp_send", "transport=data_channel queued=true")
                return
            }
        }
        val queued = signal.send(msg)
        if (c.type == "publisher") emitPublisherDiagnostic(c.session, "local_sdp_send", "transport=signaling queued=$queued")
    }

    private fun renegotiate(c: VdoConnection, iceRestart: Boolean) {
        if (c.type == "publisher" && c.authenticating && !c.authenticated) {
            failPublisherConnection(c, "renegotiate_during_admission",
                IllegalStateException("publisher renegotiation requested while candidate SDP is being prepared"))
            return
        }
        if (iceRestart) c.pc.restartIce()
        val constraints = MediaConstraints().apply {
            if (iceRestart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        c.pc.createOffer(sdpObserver({ offer ->
            if (offer == null) return@sdpObserver
            if (c.closed) return@sdpObserver
            c.pc.setLocalDescription(sdpObserver({
                if (!c.closed) sendDescription(c, offer)
            }, { error -> failNegotiation(c, "set_local_description", error) }), offer)
        }, { error -> failNegotiation(c, "create_offer", error) }), constraints)
    }

    private fun failNegotiation(c: VdoConnection, stage: String, error: String) {
        val failure = IllegalStateException(error)
        if (c.type == "publisher") failPublisherConnection(c, stage, failure)
        else log("$stage failed for ${c.uuid}/${c.type}: $error")
    }

    private fun handleLocalCandidate(c: VdoConnection, cand: org.webrtc.IceCandidate) {
        if (c.closed || cand.sdp.isNullOrEmpty()) return
        val ic = IceCandidate(cand.sdp, cand.sdpMid, cand.sdpMLineIndex)
        val sendNow = synchronized(lock) {
            if (connection(c.uuid, c.type) !== c || c.closed) return
            if (c.deferLocalIceCandidates) {
                c.deferredLocalIceCandidates.add(ic)
                false
            } else true
        }
        if (!sendNow) return
        sendLocalCandidate(c, ic)
    }

    private fun flushDeferredLocalIceCandidates(c: VdoConnection) {
        val candidates = synchronized(lock) {
            c.deferLocalIceCandidates = false
            c.deferredLocalIceCandidates.toList().also { c.deferredLocalIceCandidates.clear() }
        }
        candidates.forEach { sendLocalCandidate(c, it) }
    }

    private fun sendLocalCandidate(c: VdoConnection, ic: IceCandidate) {
        if (c.closed) return
        val msgType = Protocol.candidateTypeFor(c.type)
        if (c.controlOpen) {
            val msg = JSONObject().put("type", msgType).put("UUID", c.uuid).put("session", c.session)
            pw.encryptInto(msg, "candidates", JSONArray().put(ic.toJson()))
            if (c.sendControl(msg)) return
        }
        synchronized(lock) {
            c.iceBundle.add(ic)
            if (c.iceTask != null) return
            val delay = c.iceDelayMs
            c.iceDelayMs = minOf(c.iceDelayMs * 2, 1000L)
            c.iceTask = scheduler.schedule({
                val bundle: List<IceCandidate>
                synchronized(lock) {
                    c.iceTask = null
                    bundle = c.iceBundle.toList()
                    c.iceBundle.clear()
                }
                if (c.closed || bundle.isEmpty()) return@schedule
                val msg = JSONObject().put("UUID", c.uuid).put("type", msgType).put("session", c.session)
                pw.encryptInto(msg, "candidates", JSONArray().apply { bundle.forEach { put(it.toJson()) } })
                signal.send(msg)
            }, delay, TimeUnit.MILLISECONDS)
        }
    }

    private fun handlePeerState(c: VdoConnection, state: PeerConnection.PeerConnectionState) {
        if (c.closed) return
        log("peer state ${c.uuid}/${c.type}: $state")
        when (state) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                if (c.type != "publisher" || c.authenticated) c.connectTask?.cancel(false)
                c.graceTask?.cancel(false)
                c.graceTask = null
                c.recovering = false
                c.connectedOnce = true
                if (c.type == "publisher") completePublisherAuthentication(c)
                else emitPeerConnectedIfReady(c)
            }
            PeerConnection.PeerConnectionState.DISCONNECTED -> {
                setStreamState(c.streamId, "disconnected")
                if (c.graceTask == null) {
                    c.graceTask = scheduler.schedule({
                        c.graceTask = null
                        if (!c.closed && c.pc.connectionState() != PeerConnection.PeerConnectionState.CONNECTED) recoverConnection(c, "disconnected")
                    }, options.disconnectGracePeriodMs, TimeUnit.MILLISECONDS)
                }
            }
            PeerConnection.PeerConnectionState.FAILED -> {
                setStreamState(c.streamId, "failed")
                recoverConnection(c, "failed")
            }
            PeerConnection.PeerConnectionState.CLOSED -> {
                if (c.type == "publisher") {
                    failPublisherConnection(c, "peer_closed", IllegalStateException("publisher peer connection closed"))
                } else {
                    emitPeerDisconnected(c)
                }
            }
            else -> {}
        }
    }

    private fun handleIceConnectionState(c: VdoConnection, state: PeerConnection.IceConnectionState) {
        if (c.type != "publisher") return
        val changed = synchronized(lock) {
            if (connection(c.uuid, "publisher") !== c || c.closed || c.lastIceState == state) false
            else { c.lastIceState = state; true }
        }
        if (changed) emitPublisherDiagnostic(c.session, "ice_state", state.name.lowercase())
    }

    private fun setStreamState(sid: String?, state: String) {
        if (sid == null) return
        synchronized(lock) { streams[sid]?.state = state }
    }

    private fun recoverConnection(c: VdoConnection, reason: String) {
        if (c.closed) return
        if (!c.recovering && (c.type == "publisher" || c.controlOpen)) {
            c.recovering = true
            log("ICE restart for ${c.uuid}/${c.type} ($reason)")
            emit(VdoEvent(VdoEvents.ICE_RESTART, uuid = c.uuid, sessionId = c.session, streamId = c.streamId, connectionType = c.type, message = reason))
            if (c.type == "publisher") renegotiate(c, true) else c.sendControl(JSONObject().put("iceRestartRequest", true))
            c.connectTask?.cancel(false)
            c.connectTask = scheduler.schedule({ if (!c.closed) recoverConnection(c, "restart_timeout") }, options.connectionTimeoutMs, TimeUnit.MILLISECONDS)
            return
        }
        // Recovery is exhausted (or impossible): tear the connection down. `connectionFailed`
        // marks the final failure only, preceded by `peerDisconnected` like any other teardown.
        val sid = c.streamId
        if (c.type == "publisher") {
            failPublisherConnection(c, "ice_recovery", IllegalStateException("ICE recovery ended: $reason"))
        } else {
            val removed = synchronized(lock) {
                if (connection(c.uuid, c.type) !== c) false
                else { removeConnection(c); true }
            }
            if (!removed) return
            emitPeerDisconnected(c)
            c.close()
            emit(VdoEvent(VdoEvents.CONNECTION_FAILED, uuid = c.uuid, sessionId = c.session,
                streamId = sid, connectionType = c.type, stage = "ice_recovery",
                failure = IllegalStateException("ICE recovery ended: $reason"), message = reason))
            if (sid != null && synchronized(lock) { sid !in stoppedViews }) scheduleViewRetry(sid, 2000)
        }
    }

    private fun sendBye(c: VdoConnection) {
        if (!c.sendControl(JSONObject().put("bye", true).put("session", c.session))) {
            signal.send(JSONObject().put("bye", true).put("UUID", c.uuid).put("session", c.session))
        }
    }

    // ------------------------------------------------------ data channels

    private fun handleIncomingChannel(c: VdoConnection, dc: DataChannel) {
        val label = dc.label() ?: ""
        log("data channel received")
        if (label.isEmpty() || label == Protocol.CONTROL_CHANNEL) {
            setupControlChannel(c, dc)
            return
        }
        if (label == Protocol.BINARY_CHANNEL || label.startsWith(Protocol.RESERVED_PREFIX)) {
            c.reservedChannels.add(dc)
            dc.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) {}
                override fun onStateChange() {}
                override fun onMessage(buffer: DataChannel.Buffer) {
                    val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                    if (buffer.binary) {
                        emit(VdoEvent(VdoEvents.BINARY_RECEIVED, uuid = c.uuid, streamId = c.streamId, channel = label, bytes = bytes))
                    } else {
                        val text = String(bytes, StandardCharsets.UTF_8)
                        emit(VdoEvent(VdoEvents.CHANNEL_MESSAGE, uuid = c.uuid, streamId = c.streamId, channel = label, data = runCatching { PasswordContext.parseJson(text) }.getOrDefault(text)))
                    }
                }
            })
            return
        }
        // File transfers, resources and chunked channels are not part of this SDK.
        log("ignoring unsupported data channel")
        dc.close()
    }

    private fun setupControlChannel(c: VdoConnection, dc: DataChannel) {
        c.control = dc
        fun opened() {
            if (c.closed || c.controlOpen) return
            c.controlOpen = true
            log("control data channel opened")
            if (c.type == "viewer") {
                c.sendControl(c.viewOptions.toPrefs())
                if (options.autoPingViewer) startPingMonitor(c)
            } else if (publisherInfo.length() > 0) {
                c.sendControl(JSONObject().put("info", publisherInfo))
            }
            val subs = synchronized(lock) { subscriptions.toList() }
            if (subs.isNotEmpty()) c.sendControl(JSONObject().put("pipe", JSONObject().put("type", "subscribe").put("channels", JSONArray(subs))))
            emit(VdoEvent(VdoEvents.DATA_CHANNEL_OPEN, uuid = c.uuid, sessionId = c.session,
                streamId = c.streamId, connectionType = c.type))
        }
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                when (dc.state()) {
                    DataChannel.State.OPEN -> opened()
                    DataChannel.State.CLOSED -> if (c.controlOpen) {
                        c.controlOpen = false
                        c.pingTask?.cancel(false)
                        emit(VdoEvent(VdoEvents.DATA_CHANNEL_CLOSE, uuid = c.uuid, sessionId = c.session,
                            streamId = c.streamId, connectionType = c.type))
                    }
                    else -> {}
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return // VDO.Ninja renders binary control payloads as images; ignored here
                val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                val text = String(bytes, StandardCharsets.UTF_8)
                val msg = try { JSONObject(text) } catch (_: Exception) { return }
                log("control data-channel message received (payload omitted)")
                handleControlMessage(c, msg)
            }
        })
        if (dc.state() == DataChannel.State.OPEN) opened()
    }

    private fun startPingMonitor(c: VdoConnection) {
        c.pingTask?.cancel(false)
        val interval = maxOf(options.autoPingIntervalMs, 1000L)
        c.pingTask = scheduler.scheduleAtFixedRate({
            if (c.closed || !c.controlOpen) return@scheduleAtFixedRate
            if (c.pendingPing != 0L) {
                val elapsed = System.currentTimeMillis() - c.pendingPing
                if (elapsed < interval + 5000) return@scheduleAtFixedRate
                c.missedPings++
                c.pendingPing = 0
                if (c.missedPings >= 2) {
                    c.missedPings = 0
                    recoverConnection(c, "missed_pings")
                    return@scheduleAtFixedRate
                }
            }
            sendPing(c.uuid)
        }, interval, interval, TimeUnit.MILLISECONDS)
    }

    private fun handleControlMessage(c: VdoConnection, msg: JSONObject) {
        msg.optJSONObject("info")?.let { info ->
            synchronized(c.info) { info.keys().forEach { k -> c.info.put(k, info.get(k)) } }
            emit(VdoEvent(VdoEvents.PEER_INFO, uuid = c.uuid, streamId = c.streamId, data = JSONObject(c.info.toString())))
        }
        msg.optJSONObject("obsState")?.let { obs ->
            synchronized(c.obsState) { obs.keys().forEach { k -> c.obsState.put(k, obs.get(k)) } }
            emit(VdoEvent(VdoEvents.OBS_STATE, uuid = c.uuid, streamId = c.streamId, data = JSONObject(c.obsState.toString())))
        }
        when {
            msg.has("description") -> handleSdp(msg, c)
            msg.has("candidate") -> handleRemoteCandidates(msg, single = true, via = c)
            msg.has("candidates") -> handleRemoteCandidates(msg, via = c)
            msg.opt("audio") is Boolean || msg.opt("video") is Boolean -> {
                (msg.opt("audio") as? Boolean)?.let { c.allowAudio = it }
                (msg.opt("video") as? Boolean)?.let { c.allowVideo = it }
                updateTracks(c)
            }
            msg.has("ping") -> c.sendControl(JSONObject().put("pong", msg.get("ping")))
            msg.opt("pong") is Number -> {
                val ts = (msg.get("pong") as Number).toLong()
                if (c.pendingPing == ts) { c.pendingPing = 0; c.missedPings = 0 }
                emit(VdoEvent(VdoEvents.PEER_LATENCY, uuid = c.uuid, streamId = c.streamId, latencyMs = System.currentTimeMillis() - ts))
            }
            msg.has("bye") -> handleBye(c.uuid, VdoEvents.BYE, msg, c.session)
            msg.has("videoMuted") -> {
                emit(VdoEvent(VdoEvents.REMOTE_VIDEO_MUTE_STATE, uuid = c.uuid, streamId = c.streamId, connectionType = c.type, muted = msg.optBoolean("videoMuted"), raw = msg))
                emit(VdoEvent(VdoEvents.DATA_RECEIVED, uuid = c.uuid, streamId = c.streamId, data = msg, raw = msg))
            }
            msg.has("pipe") -> {
                val pipe = msg.opt("pipe")
                if (pipe is JSONObject && handlePipeSpecial(c, pipe)) return
                emit(VdoEvent(VdoEvents.DATA_RECEIVED, uuid = c.uuid, streamId = c.streamId, data = pipe, raw = msg))
            }
            msg.has("iceRestartRequest") -> if (c.type == "publisher") renegotiate(c, true)
        }
    }

    private fun handlePipeSpecial(c: VdoConnection, pipe: JSONObject): Boolean {
        when (pipe.optString("type", "")) {
            "subscribe", "unsubscribe" -> {
                val list = when (val raw = pipe.opt("channels")) {
                    is JSONArray -> (0 until raw.length()).mapNotNull { raw.optString(it, null) }
                    is String -> listOf(raw)
                    else -> emptyList()
                }
                val all: List<String>
                synchronized(lock) {
                    val set = peerSubscriptions.getOrPut(c.uuid) { LinkedHashSet() }
                    if (pipe.optString("type") == "subscribe") set.addAll(list) else set.removeAll(list.toSet())
                    all = set.toList()
                }
                emit(VdoEvent(if (pipe.optString("type") == "subscribe") VdoEvents.PEER_SUBSCRIBED else VdoEvents.PEER_UNSUBSCRIBED, uuid = c.uuid, data = JSONArray(all), raw = pipe))
                return true
            }
            "channelMessage" -> {
                val ch = pipe.optString("channel", "")
                if (ch.isNotEmpty() && synchronized(lock) { ch in subscriptions }) {
                    emit(VdoEvent(VdoEvents.CHANNEL_MESSAGE, uuid = c.uuid, channel = ch, data = pipe.opt("data"), raw = pipe))
                }
                return true
            }
            "request" -> {
                val rt = pipe.optString("requestType", "")
                val rid = pipe.optString("requestId", "")
                val h = requestHandlers[rt] ?: return true
                if (rid.isEmpty()) return true
                try {
                    worker.execute {
                        try {
                            respond(rid, h(pipe.opt("data"), c.uuid), c.uuid)
                        } catch (t: Throwable) {
                            respond(rid, JSONObject().put("error", t.message ?: t.toString()), c.uuid)
                        }
                    }
                } catch (_: RejectedExecutionException) {} // disposed
                return true
            }
            "response" -> {
                val rid = pipe.optString("requestId", "")
                val p = pendingRequests[rid] ?: return true
                if (p.target != null && p.target != c.uuid) return true
                pendingRequests.remove(rid)
                val data = pipe.opt("data")
                val err = (data as? JSONObject)?.optString("error", "")
                if (!err.isNullOrEmpty()) p.future.completeExceptionally(RuntimeException(err)) else p.future.complete(data)
                return true
            }
        }
        return false
    }

    private fun updateTracks(c: VdoConnection) {
        val local = synchronized(lock) { localTracks.toList() }
        for ((sender, kind) in c.senders.toList()) {
            val allowed = if (kind == "video") c.allowVideo else c.allowAudio
            val track = local.firstOrNull { it.kind() == kind }
            try {
                if (!allowed && sender.track() != null) sender.setTrack(null, false)
                else if (allowed && sender.track() == null && track != null) sender.setTrack(track, false)
            } catch (e: Exception) {
                log("setTrack failed: $e")
            }
        }
    }

    // ---------------------------------------------------------- signaling

    private fun handleSignal(msg: JSONObject) {
        val request = msg.optString("request", "")
        when {
            msg.has("description") -> handleSdp(msg, null)
            msg.has("candidate") -> handleRemoteCandidates(msg, single = true)
            msg.has("candidates") -> handleRemoteCandidates(msg)
            request.isNotEmpty() -> when (request) {
                "listing", "transferred" -> handleListing(msg, request == "transferred")
                "videoaddedtoroom" -> handleRoomEvent(msg, VdoEvents.VIDEO_ADDED_TO_ROOM)
                "someonejoined", "joinroom" -> handleRoomEvent(msg, VdoEvents.SOMEONE_JOINED)
                "play" -> {
                    val requested = pw.stripHash(msg.optString("streamID", ""))
                    if (publishing && requested == streamId) handleOfferRequest(msg.optString("UUID", null))
                    else log("ignoring play request for \"$requested\"")
                }
                "offerSDP" -> handleOfferRequest(msg.optString("UUID", null))
                "error" -> emit(VdoEvent(VdoEvents.ERROR, message = msg.optString("message", null), raw = msg))
                "alert" -> {
                    val text = msg.optString("message", "")
                    emit(VdoEvent(VdoEvents.ALERT, message = text, raw = msg))
                    if (text.contains("already in use")) publishing = false
                }
            }
            msg.has("bye") -> handleBye(msg.optString("UUID", null), VdoEvents.BYE, msg)
            msg.has("hangup") -> handleBye(msg.optString("UUID", null), VdoEvents.HANGUP, msg)
            msg.has("pipe") && msg.has("__fallback") ->
                emit(VdoEvent(VdoEvents.DATA_RECEIVED, uuid = msg.optString("UUID", null), data = msg.opt("pipe"), fallback = true, raw = msg))
            msg.has("rejected") || msg.has("approved") ->
                emit(VdoEvent(VdoEvents.ALERT, message = if (msg.has("rejected")) "connection rejected" else "connection approved", raw = msg))
            msg.has("iceRestartRequest") -> connection(msg.optString("UUID", ""), "publisher")?.let { renegotiate(it, true) }
        }
    }

    private fun handleListing(msg: JSONObject, transferred: Boolean) {
        val entries = parseListing(msg, pw)
        val waiter: CompletableFuture<List<ListingEntry>>?
        synchronized(lock) {
            for (e in entries) {
                if (e.streamId.isEmpty()) continue
                val info = streams.getOrPut(e.streamId) { StreamInfo(e.streamId, e.uuid) }
                if (e.uuid != null) info.uuid = e.uuid
                if (info.state == "disconnected" || info.state == "failed") info.state = "available"
            }
            waiter = roomWaiter
            roomWaiter = null
        }
        if (waiter != null && !waiter.isDone) {
            waiter.complete(entries)
            emit(VdoEvent(VdoEvents.ROOM_JOINED, data = JSONArray(entries.map { it.streamId }), raw = msg))
        }
        if (transferred) emit(VdoEvent(VdoEvents.TRANSFERRED, raw = msg))
        emit(VdoEvent(VdoEvents.LISTING, data = JSONArray(entries.map { e -> JSONObject().put("streamID", e.streamId).put("UUID", e.uuid ?: JSONObject.NULL).put("director", e.director) }), raw = msg))
        entries.filter { it.streamId.isNotEmpty() }.forEach { autoConsider(it.streamId) }
    }

    private fun handleRoomEvent(msg: JSONObject, type: String) {
        val raw = msg.optString("streamID", "")
        val sid = if (raw.isEmpty()) null else pw.stripHash(raw)
        val uuid = msg.optString("UUID", null)
        if (sid != null) synchronized(lock) {
            val info = streams.getOrPut(sid) { StreamInfo(sid, uuid) }
            if (uuid != null) info.uuid = uuid
            if (info.state == "disconnected" || info.state == "failed") info.state = "available"
        }
        emit(VdoEvent(type, streamId = sid, uuid = uuid, raw = msg))
        if (sid != null) {
            val replay = synchronized(lock) { pendingViews.containsKey(sid) && findViewerForStream(sid) == null }
            if (replay) signal.send(JSONObject().put("request", "play").put("streamID", pw.hashStreamId(sid)))
            autoConsider(sid)
        }
    }

    private fun handleOfferRequest(uuid: String?) {
        val peerId = uuid?.takeIf(String::isNotBlank) ?: return
        if (!publishing) return
        val sessionId = Crypto.generateSession()
        val reserved = synchronized(lock) {
            if (!publishing || connections[peerId]?.publisher?.closed == false ||
                publisherOffersInProgress.containsKey(peerId)) false
            else { publisherOffersInProgress[peerId] = sessionId; true }
        }
        if (!reserved) return
        var conn: VdoConnection? = null
        var stage = "reserve_candidate"
        try {
            if (publisherAdmission?.tryReserve(peerId, sessionId) == false) return
            emitPublisherDiagnostic(sessionId, "candidate_reserved")
            if (!publishing) {
                publisherAdmission?.release(sessionId)
                return
            }
            stage = "create_peer_connection"
            val c = createConnection(peerId, "publisher", sessionId)
            conn = c
            c.streamId = streamId
            stage = "create_control_channel"
            val dc = c.pc.createDataChannel(Protocol.CONTROL_CHANNEL, DataChannel.Init())
            setupControlChannel(c, dc)
            stage = "create_offer"
            c.pc.createOffer(sdpObserver({ offer ->
                if (offer == null) {
                    failPublisherConnection(c, "create_offer", IllegalStateException("createOffer returned no description"))
                    return@sdpObserver
                }
                if (c.closed) return@sdpObserver
                emitPublisherDiagnostic(c.session, "local_offer_created")
                c.pc.setLocalDescription(sdpObserver({
                    if (!c.closed) {
                        emitPublisherDiagnostic(c.session, "local_sdp_applied")
                        sendDescription(c, offer)
                    }
                }, { error ->
                    failPublisherConnection(c, "set_local_description", IllegalStateException(error))
                }), offer)
            }, { error -> failPublisherConnection(c, "create_offer", IllegalStateException(error)) }), MediaConstraints())
        } catch (failure: Throwable) {
            val failed = conn ?: connection(peerId, "publisher")?.takeIf { it.session == sessionId }
            if (failed != null) failPublisherConnection(failed, stage, failure)
            else {
                publisherAdmission?.release(sessionId)
                failPublisherSession(peerId, sessionId, stage, failure)
            }
        } finally {
            synchronized(lock) {
                if (publisherOffersInProgress[peerId] == sessionId) publisherOffersInProgress.remove(peerId)
            }
        }
    }

    private fun handleSdp(msg: JSONObject, via: VdoConnection?) {
        val uuid = via?.uuid ?: msg.optString("UUID", null) ?: return
        val session = msg.optString("session", "").ifEmpty { via?.session }
        val publisher = if (via == null || via.type == "publisher") connection(uuid, "publisher") else null
        val decoded = try {
            pw.decodeFieldOrThrow(msg, "description")
        } catch (failure: Throwable) {
            if (publisher != null && !session.isNullOrEmpty() && session == publisher.session) {
                failPublisherConnection(publisher, "decrypt_remote_description", failure)
            }
            return
        }
        val desc = SessionDescription.fromJson(decoded)
        if (desc == null) {
            if (publisher != null && !session.isNullOrEmpty() && session == publisher.session && msg.has("description")) {
                failPublisherConnection(publisher, "parse_remote_description",
                    IllegalArgumentException("remote description was missing or malformed"))
            }
            return
        }
        when (desc.type) {
            "offer" -> handleOffer(uuid, session, msg, desc, via)
            "answer" -> {
                val c = publisher ?: if (via?.type == "publisher") via else connection(uuid, "publisher")
                // Answers without the exact VDO session are not safe to associate with a peer generation.
                if (c == null || session.isNullOrEmpty() || c.session != session) return
                emitPublisherDiagnostic(c.session, "remote_sdp_decrypted")
                handleAnswer(c, desc)
            }
        }
    }

    private fun handleOffer(uuid: String, session: String?, msg: JSONObject, desc: SessionDescription, via: VdoConnection?) {
        val rawSid = msg.optString("streamID", null)
        val clean = rawSid?.let { pw.stripHash(it) }
        if (clean != null) synchronized(lock) { viewRetries.remove(clean)?.cancel(false) } // the publisher answered; stop re-sending play
        var c = if (via != null && via.type == "viewer") via else connection(uuid, "viewer")
        if (c != null && !session.isNullOrEmpty() && c.session != session) {
            log("session mismatch for $uuid; replacing viewer connection")
            synchronized(lock) { removeConnection(c!!) }
            c.close()
            c = null
        }
        val conn: VdoConnection
        try {
            if (c == null) {
                conn = createConnection(uuid, "viewer")
                if (!session.isNullOrEmpty()) conn.session = session
                conn.streamId = clean
                synchronized(lock) {
                    if (clean != null) pendingViews[clean]?.let { conn.viewOptions = it }
                    if (!clean.isNullOrEmpty()) streams.getOrPut(clean) { StreamInfo(clean, uuid) }.uuid = uuid
                }
            } else {
                conn = c
                if (!session.isNullOrEmpty()) conn.session = session
                if (conn.streamId == null) conn.streamId = clean
            }
        } catch (e: Exception) {
            emit(VdoEvent(VdoEvents.ERROR, uuid = uuid, message = "failed to create viewer connection: $e"))
            return
        }
        val fail = { what: String -> emit(VdoEvent(VdoEvents.ERROR, uuid = uuid, message = "failed to handle offer: $what")) }
        conn.pc.setRemoteDescription(sdpObserver({
            drainPendingIce(conn, "local")
            conn.pc.createAnswer(sdpObserver({ answer ->
                if (answer == null) return@sdpObserver
                conn.pc.setLocalDescription(sdpObserver({
                    val out = JSONObject().put("UUID", uuid).put("session", conn.session)
                    if (rawSid != null) out.put("streamID", rawSid)
                    pw.encryptInto(out, "description", SessionDescription(answer.type.canonicalForm(), answer.description).toJson())
                    if (conn.controlOpen) {
                        val dcMsg = JSONObject().put("description", out.get("description"))
                        if (out.has("vector")) dcMsg.put("vector", out.get("vector"))
                        conn.sendControl(dcMsg)
                    } else {
                        signal.send(out)
                    }
                    if (!clean.isNullOrEmpty()) {
                        val waiters: List<CompletableFuture<VdoConnection>>
                        synchronized(lock) {
                            pendingViews.remove(clean)
                            waiters = viewWaiters.remove(clean) ?: emptyList()
                        }
                        waiters.forEach { it.complete(conn) }
                    }
                }, fail), answer)
            }, fail), MediaConstraints())
        }, fail), RtcSessionDescription(RtcSessionDescription.Type.OFFER, desc.sdp))
    }

    private fun handleAnswer(c: VdoConnection, desc: SessionDescription) {
        if (c.type != "publisher" || c.closed || connection(c.uuid, "publisher") !== c) return
        c.pc.setRemoteDescription(sdpObserver({
            drainPendingIce(c, "remote")
            if (connection(c.uuid, "publisher") !== c || c.closed) return@sdpObserver
            c.remoteAnswerApplied = true
            emitPublisherDiagnostic(c.session, "remote_sdp_applied")
            completePublisherAuthentication(c)
        }, { error ->
            failPublisherConnection(c, "set_remote_description", IllegalStateException(error))
        }), RtcSessionDescription(RtcSessionDescription.Type.ANSWER, desc.sdp))
    }

    private fun handleRemoteCandidates(msg: JSONObject, single: Boolean = false, via: VdoConnection? = null) {
        val cands = IceCandidate.listFromJson(pw.decodeField(msg, if (single) "candidate" else "candidates"))
        val uuid = via?.uuid ?: msg.optString("UUID", null) ?: return
        if (cands.isEmpty()) return
        // "remote" candidates come from viewers (for our publisher pc), "local" from publishers.
        val want = via?.type ?: if (msg.optString("type") == "remote") "publisher" else "viewer"
        val batch = CandidateBatch(msg.opt("session") as? String ?: via?.session, cands)
        val c = synchronized(lock) {
            val current = via ?: connection(uuid, want)
            if (current == null || !current.remoteSet) {
                pendingIce.getOrPut("${if (want == "publisher") "remote" else "local"}:$uuid") { ArrayList() }.add(batch)
                return
            }
            current
        }
        addCandidates(c, batch.matching(c.session))
    }

    private fun addCandidates(c: VdoConnection, cands: List<IceCandidate>) {
        for (ic in cands) {
            try {
                val accepted = c.pc.addIceCandidate(
                    org.webrtc.IceCandidate(ic.sdpMid ?: "0", ic.sdpMLineIndex ?: 0, ic.candidate),
                )
                if (!accepted && c.type == "publisher") {
                    emitPublisherDiagnostic(
                        c.session,
                        "remote_ice_candidate_rejected",
                        failure = IllegalStateException("libwebrtc rejected a remote ICE candidate"),
                    )
                }
            } catch (failure: Exception) {
                if (c.type == "publisher") {
                    emitPublisherDiagnostic(c.session, "remote_ice_candidate_failed", failure = failure)
                } else {
                    log("remote ICE candidate processing failed for viewer")
                }
            }
        }
    }

    private fun drainPendingIce(c: VdoConnection, msgType: String) {
        val list = synchronized(lock) {
            // Queueing and the SDP-ready transition must be atomic: otherwise a
            // candidate can be enqueued just after the only drain has finished.
            if (connection(c.uuid, c.type) !== c) return
            c.remoteSet = true
            pendingIce.remove("$msgType:${c.uuid}")
        } ?: return
        addCandidates(c, list.flatMap { it.matching(c.session) })
    }

    private fun handleBye(uuid: String?, type: String, msg: JSONObject, expectedSession: String? = null) {
        if (uuid.isNullOrEmpty()) return
        val conns: List<VdoConnection>
        val session = expectedSession ?: msg.optString("session", "")
        var retry: String? = null
        synchronized(lock) {
            val pair = connections[uuid]
            conns = listOfNotNull(pair?.publisher, pair?.viewer).filter {
                session.isEmpty() || it.session == session
            }
            conns.forEach(::removeConnection)
            if (connections[uuid] == null) {
                pendingIce.remove("local:$uuid")
                pendingIce.remove("remote:$uuid")
                peerSubscriptions.remove(uuid)
            }
            for (c in conns) {
                val sid = c.streamId
                if (c.type == "viewer" && sid != null && !intentional && sid !in stoppedViews) {
                    // Only `bye` re-requests the stream (the JavaScript SDK never retries after `hangup`).
                    if (type == VdoEvents.BYE) retry = sid
                    streams[sid]?.state = "disconnected"
                }
            }
        }
        conns.forEach { peer -> peer.close(); emitPeerDisconnected(peer) }
        retry?.let { scheduleViewRetry(it, 2000) }
        emit(VdoEvent(type, uuid = uuid, sessionId = session.ifEmpty { null }, raw = msg))
    }
}
