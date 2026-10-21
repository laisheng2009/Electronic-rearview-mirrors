package com.byd.extend.remote

import android.content.Context
import android.util.Log
import android.view.Surface
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import ninja.vdo.sdk.PublisherPeerAdmission
import ninja.vdo.sdk.VdoNinja
import ninja.vdo.sdk.core.Protocol
import ninja.vdo.sdk.core.TurnMode
import ninja.vdo.sdk.core.VdoEvent
import ninja.vdo.sdk.core.VdoEventListener
import ninja.vdo.sdk.core.VdoEvents
import ninja.vdo.sdk.core.VdoNinjaOptions
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.HardwareVideoEncoderFactory
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/** One authenticated VDO.Ninja publisher. Video is attached only after the owner passes its safety gate. */
internal class VdoCameraPublisher(
    context: Context,
    private val streamId: String,
    private val password: String,
    private val listener: Listener,
) : AutoCloseable {
    interface Listener {
        fun onReady(viewerUrl: String)
        fun onViewerConnected()
        fun onViewerDisconnected()
        fun onError(encoderUnavailable: Boolean)
    }

    private data class Resources(
        val egl: EglBase,
        val factory: PeerConnectionFactory,
        val sdk: VdoNinja,
    )

    private data class VideoSession(
        val helper: SurfaceTextureHelper,
        val source: VideoSource,
        val track: VideoTrack,
        val surface: Surface,
    )

    private data class CloseResources(
        val sdk: VdoNinja?,
        val factory: PeerConnectionFactory?,
        val egl: EglBase?,
    )

    private val appContext = context.applicationContext
    private val lock = Any()
    private val opener = Executors.newSingleThreadExecutor { task ->
        Thread(task, "vdo-camera-publisher").apply { isDaemon = true }
    }
    private val eventExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "vdo-camera-events").apply { isDaemon = true }
    }

    @Volatile private var closed = false
    private var startRequested = false
    private var opening = false
    private var ready = false
    private var readyCallbackPending = false
    private var viewerNotified = false
    private var awaitingViewerCleanup = false
    private var candidatePeerId: String? = null
    private var acceptedPeerId: String? = null
    private var resources: Resources? = null
    private var video: VideoSession? = null
    private var sdkDisposedByClose = false

    private val publisherAdmission = object : PublisherPeerAdmission {
        override fun tryAdmit(uuid: String): Boolean = synchronized(lock) {
            if (closed) return@synchronized false
            if (candidatePeerId == null && acceptedPeerId == null) {
                candidatePeerId = uuid
                true
            } else false
        }

        override fun release(uuid: String) {
            synchronized(lock) {
                if (candidatePeerId == uuid) candidatePeerId = null
            }
        }
    }

    private val sdkListener = VdoEventListener(::handleEvent)

    fun start() {
        synchronized(lock) {
            if (closed || startRequested) return
            startRequested = true
            opening = true
        }
        try {
            opener.execute(::open)
        } catch (_: RejectedExecutionException) {
            synchronized(lock) { opening = false }
            notifyErrorUnlessClosed(encoderUnavailable = false)
        }
    }

    /** Returns the WebRTC input surface for the existing camera/GPU composer. */
    fun startVideo(): Surface = synchronized(lock) {
        check(!closed && ready) { "VDO publisher is not ready" }
        check(acceptedPeerId != null) { "No authenticated viewer" }
        check(video == null) { "VDO video is already running" }
        val active = checkNotNull(resources) { "VDO publisher is unavailable" }

        val helper = SurfaceTextureHelper.create("vdo-camera-capture", active.egl.eglBaseContext)
            ?: error("SurfaceTextureHelper unavailable")
        var source: VideoSource? = null
        var track: VideoTrack? = null
        var surface: Surface? = null
        var started = false
        try {
            val videoSource = active.factory.createVideoSource(false)
            source = videoSource
            videoSource.setIsScreencast(false)
            videoSource.adaptOutputFormat(WIDTH, HEIGHT, FPS)
            helper.setTextureSize(WIDTH, HEIGHT)
            val observer = videoSource.capturerObserver
            observer.onCapturerStarted(true)
            started = true
            helper.startListening(VideoSink(observer::onFrameCaptured))
            val videoTrack = active.factory.createVideoTrack("byd-camera-video", videoSource)
            track = videoTrack
            val captureSurface = Surface(helper.surfaceTexture)
            surface = captureSurface
            active.sdk.addTrack(videoTrack)
            VideoSession(helper, videoSource, videoTrack, captureSurface).also { video = it }
            captureSurface
        } catch (failure: Throwable) {
            if (started) runCatching { source?.capturerObserver?.onCapturerStopped() }
            runCatching { track?.let(active.sdk::removeTrack) }
            runCatching { helper.stopListening() }
            runCatching { surface?.release() }
            runCatching { track?.dispose() }
            runCatching { source?.dispose() }
            runCatching { helper.dispose() }
            throw failure
        }
    }

    /** Removes only the video track and capture resources; the authenticated data peer stays connected. */
    fun stopVideo() {
        synchronized(lock) { stopVideoLocked() }
    }

    private fun stopVideoLocked() {
        video?.let { current ->
            video = null
            resources?.sdk?.let { sdk -> runCatching { sdk.removeTrack(current.track) } }
            runCatching { current.source.capturerObserver.onCapturerStopped() }
            runCatching { current.helper.stopListening() }
            runCatching { current.surface.release() }
            runCatching { current.track.dispose() }
            runCatching { current.source.dispose() }
            runCatching { current.helper.dispose() }
        }
        // Keep the next peer out until the owner has stopped its camera source and removed the track.
        if (awaitingViewerCleanup) {
            acceptedPeerId = null
            awaitingViewerCleanup = false
        }
    }

    override fun close() {
        val toClose = synchronized(lock) {
            if (closed) return
            closed = true
            stopVideoLocked()
            candidatePeerId = null
            acceptedPeerId = null
            viewerNotified = false
            awaitingViewerCleanup = false
            ready = false
            readyCallbackPending = false

            val active = resources
            resources = null
            val sdkToClose = active?.sdk
            if (sdkToClose != null) {
                sdkToClose.removeListener(sdkListener)
                sdkDisposedByClose = true
            }
            if (opening) {
                // connect() is blocking; disposing its signaling client wakes it. The opener releases
                // the factory and EGL resources after that call unwinds.
                CloseResources(sdkToClose, null, null)
            } else {
                CloseResources(sdkToClose, active?.factory, active?.egl)
            }
        }
        opener.shutdownNow()
        eventExecutor.shutdownNow()
        runCatching { toClose.sdk?.dispose() }
        runCatching { toClose.factory?.dispose() }
        runCatching { toClose.egl?.release() }
    }

    private fun open() {
        var local: Resources? = null
        var eglOwned: EglBase? = null
        var factoryOwned: PeerConnectionFactory? = null
        var sdkOwned: VdoNinja? = null
        var published = false
        var completed = false
        try {
            check(!closed) { "Publisher closed" }
            val viewerUrl = CameraViewingEdits.vdoViewerUrl(streamId)
            require(password.isNotBlank()) { "Viewing password required" }

            ensureWebRtcInitialized(appContext)
            val egl = EglBase.create()
            eglOwned = egl
            val encoderFactory = HardwareH264EncoderFactory(egl.eglBaseContext) {
                notifyErrorUnlessClosed(encoderUnavailable = true)
            }
            if (encoderFactory.getSupportedCodecs().isEmpty()) throw HardwareEncoderUnavailable()
            val factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()
            factoryOwned = factory
            val sdk = VdoNinja(
                appContext,
                VdoNinjaOptions(
                    password = password,
                    noPassword = false,
                    salt = Protocol.DEFAULT_SALT,
                    turnMode = TurnMode.AUTO,
                ),
                factory,
                egl,
                publisherAdmission,
            )
            sdkOwned = sdk
            local = Resources(egl, factory, sdk)
            sdk.addListener(sdkListener)

            synchronized(lock) {
                check(!closed) { "Publisher closed" }
                resources = local
                published = true
            }

            sdk.connect()
            check(!closed) { "Publisher closed" }
            val publishedId = sdk.announce(streamId)
            check(publishedId == streamId) { "Invalid stream name" }

            synchronized(lock) {
                check(!closed) { "Publisher closed" }
                ready = true
                readyCallbackPending = true
            }
            notifyReadyUnlessClosed(viewerUrl)
            synchronized(lock) {
                readyCallbackPending = false
                if (!closed && acceptedPeerId != null && !viewerNotified) {
                    viewerNotified = true
                    // Enqueue connected before a concurrently received disconnect callback.
                    notifyViewerConnectedUnlessClosed()
                }
            }
            completed = true
        } catch (failure: HardwareEncoderUnavailable) {
            Log.w("CameraViewing", "vdo_open type=${failure.javaClass.simpleName}")
            notifyErrorUnlessClosed(encoderUnavailable = true)
        } catch (failure: Exception) {
            Log.w("CameraViewing", "vdo_open type=${failure.javaClass.simpleName}" +
                " cause=${failure.cause?.javaClass?.simpleName ?: "none"}")
            notifyErrorUnlessClosed(encoderUnavailable = false)
        } finally {
            val disposeSdk: VdoNinja?
            val disposeFactory: PeerConnectionFactory?
            val disposeEgl: EglBase?
            synchronized(lock) {
                opening = false
                if (completed && !closed) {
                    disposeSdk = null
                    disposeFactory = null
                    disposeEgl = null
                } else {
                    val current = local
                    val stillOwnedHere = current != null && resources === current
                    if (stillOwnedHere) resources = null
                    disposeSdk = if (stillOwnedHere || !published && !sdkDisposedByClose)
                        current?.sdk ?: sdkOwned else null
                    disposeFactory = current?.factory ?: factoryOwned
                    disposeEgl = current?.egl ?: eglOwned
                }
            }
            disposeSdk?.let { sdk ->
                sdk.removeListener(sdkListener)
                runCatching { sdk.dispose() }
            }
            runCatching { disposeFactory?.dispose() }
            runCatching { disposeEgl?.release() }
        }
    }

    private fun handleEvent(event: VdoEvent) {
        if (closed) return
        try {
            eventExecutor.execute { handleEventSerially(event) }
        } catch (_: RejectedExecutionException) {
            // Close can race with the SDK's final connection callback.
        }
    }

    private fun handleEventSerially(event: VdoEvent) {
        if (closed) return
        when (event.type) {
            VdoEvents.PEER_CONNECTED -> {
                if (event.connectionType != "publisher") return
                val peerId = event.uuid?.takeIf(String::isNotBlank) ?: return
                var rejectPeer = false
                val notifyViewer = synchronized(lock) {
                    if (closed) return
                    val accepted = acceptedPeerId
                    if (candidatePeerId != peerId || accepted != null && accepted != peerId) {
                        rejectPeer = true
                        false
                    } else {
                        acceptedPeerId = peerId
                        if (ready && !readyCallbackPending && !viewerNotified) {
                            viewerNotified = true
                            true
                        } else false
                    }
                }
                if (rejectPeer) resources?.sdk?.disconnectPeer(peerId)
                else if (notifyViewer) notifyViewerConnectedUnlessClosed()
            }
            VdoEvents.PEER_DISCONNECTED -> {
                val peerId = event.uuid ?: return
                val notifyViewer = synchronized(lock) {
                    if (closed || acceptedPeerId != peerId) false
                    else {
                        val wasNotified = viewerNotified
                        viewerNotified = false
                        // The controller's disconnect callback calls stopVideo after releasing its camera lease.
                        awaitingViewerCleanup = wasNotified
                        if (!wasNotified) acceptedPeerId = null
                        wasNotified
                    }
                }
                if (notifyViewer) notifyViewerDisconnectedUnlessClosed()
            }
            VdoEvents.RECONNECT_FAILED -> notifyErrorUnlessClosed(encoderUnavailable = false)
        }
    }

    private fun notifyReadyUnlessClosed(url: String) {
        if (!closed) runCatching { listener.onReady(url) }
    }

    private fun notifyViewerConnectedUnlessClosed() {
        if (!closed) runCatching { listener.onViewerConnected() }
    }

    private fun notifyViewerDisconnectedUnlessClosed() {
        if (!closed) runCatching { listener.onViewerDisconnected() }
    }

    private fun notifyErrorUnlessClosed(encoderUnavailable: Boolean) {
        if (!closed) runCatching { listener.onError(encoderUnavailable) }
    }

    private class HardwareH264EncoderFactory(
        sharedContext: EglBase.Context,
        private val onUnavailable: () -> Unit,
    ) : VideoEncoderFactory {
        private val hardware = try {
            HardwareVideoEncoderFactory(sharedContext, false, false)
        } catch (_: Exception) {
            onUnavailable()
            throw HardwareEncoderUnavailable()
        }
        private val h264Codecs = try {
            hardware.supportedCodecs
                .filter { it.name.equals("H264", ignoreCase = true) }
                .toTypedArray()
        } catch (_: Exception) {
            onUnavailable()
            throw HardwareEncoderUnavailable()
        }

        override fun getSupportedCodecs(): Array<VideoCodecInfo> = h264Codecs.copyOf()

        override fun createEncoder(codecInfo: VideoCodecInfo): VideoEncoder? {
            if (!codecInfo.name.equals("H264", ignoreCase = true) || h264Codecs.isEmpty()) return null
            val encoder = try { hardware.createEncoder(codecInfo) } catch (_: Exception) { null }
            if (encoder == null) {
                onUnavailable()
                return null
            }
            return HardwareEncoder(encoder, onUnavailable)
        }
    }

    private class HardwareEncoder(
        private val delegate: VideoEncoder,
        private val onUnavailable: () -> Unit,
    ) : VideoEncoder by delegate {
        override fun initEncode(
            settings: VideoEncoder.Settings,
            callback: VideoEncoder.Callback,
        ): VideoCodecStatus {
            val result = try { delegate.initEncode(settings, callback) } catch (_: Exception) {
                VideoCodecStatus.ERROR
            }
            if (result != VideoCodecStatus.OK) onUnavailable()
            return result
        }
    }

    private class HardwareEncoderUnavailable : Exception()

    companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val FPS = 15

        private val initializationLock = Any()
        @Volatile private var webRtcInitialized = false

        private fun ensureWebRtcInitialized(context: Context) {
            if (webRtcInitialized) return
            synchronized(initializationLock) {
                if (webRtcInitialized) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions(),
                )
                webRtcInitialized = true
            }
        }
    }
}
