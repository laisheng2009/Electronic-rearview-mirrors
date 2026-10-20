package com.byd.extend.ui.motion

import android.content.Context
import android.graphics.Matrix
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import com.byd.extend.MotionCompositionLayout

/** One media pipeline for composition and all four camera crops. Changing the crop never seeks or reloads. */
internal class MotionTexturePlayer(
    context: Context,
    uri: Uri,
    private val cameraOrder: IntArray?,
    private val onPrepared: (durationMs: Int) -> Unit,
    private val onSeekSettled: (positionMs: Int) -> Unit,
    private val onCompletion: () -> Unit,
    private val onError: (what: Int, extra: Int) -> Unit,
) : FrameLayout(context), TextureView.SurfaceTextureListener {
    private val texture = TextureView(context)
    private val seeks = MotionSeekQueue()
    private var mediaPlayer: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false
    private var preparing = false
    private var released = false
    private var failed = false
    private var cropDirection = -1
    private var videoWidth = 0
    private var videoHeight = 0
    private var resumeOnSurfaceAvailable = false
    private var playbackAllowed = true
    private var scrubbing = false
    private var scrubWasPlaying = false
    private var resumeAfterSeek = false
    private var startAfterSeek = false

    val isReady: Boolean get() = prepared && !released && !failed
    val isSeeking: Boolean get() = seeks.isActive

    init {
        setBackgroundColor(android.graphics.Color.BLACK)
        clipChildren = true
        clipToPadding = true
        texture.surfaceTextureListener = this
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        try {
            val player = MediaPlayer()
            mediaPlayer = player
            player.setAudioStreamType(AudioManager.STREAM_MUSIC)
            player.setOnPreparedListener {
                if (!released && !failed) {
                    prepared = true
                    preparing = false
                    videoWidth = it.videoWidth
                    videoHeight = it.videoHeight
                    updateViewport()
                    onPrepared(it.duration.coerceAtLeast(0))
                    if (surface != null && playbackAllowed) it.start()
                    else resumeOnSurfaceAvailable = surface == null && playbackAllowed
                }
            }
            player.setOnVideoSizeChangedListener { _, width, height ->
                if (!released && !failed) {
                    videoWidth = width
                    videoHeight = height
                    updateViewport()
                }
            }
            player.setOnSeekCompleteListener {
                if (!released && !failed && seeks.complete(::issueSeek)) finishSeekSequence()
            }
            player.setOnCompletionListener {
                if (!released && !failed) {
                    startAfterSeek = false
                    resumeAfterSeek = false
                    onCompletion()
                }
            }
            player.setOnErrorListener { _, what, extra ->
                fail(what, extra)
                true
            }
            player.setDataSource(context, uri)
            prepareWhenReady()
        } catch (_: Exception) {
            fail(0, 0)
        }
    }

    fun showCrop(direction: Int) {
        cropDirection = direction
        updateViewport()
    }

    fun currentPositionOrZero(): Int = currentPosition

    fun setPlaybackAllowed(allowed: Boolean) {
        playbackAllowed = allowed
        if (!allowed) {
            resumeOnSurfaceAvailable = false
            resumeAfterSeek = false
            startAfterSeek = false
            scrubWasPlaying = false
            scrubbing = false
            pauseMedia()
        }
    }

    fun play() {
        if (!isReady || !playbackAllowed) return
        if (seeks.isActive) {
            startAfterSeek = true
            return
        }
        if (surface == null) {
            resumeOnSurfaceAvailable = true
            return
        }
        try { mediaPlayer?.start() } catch (_: IllegalStateException) { }
    }

    fun pause() {
        startAfterSeek = false
        resumeAfterSeek = false
        pauseMedia()
    }

    fun togglePlayback(): Boolean {
        if (isPlaying || startAfterSeek || resumeAfterSeek) {
            pause()
            return false
        }
        if (currentPosition >= duration) restart() else play()
        return true
    }

    fun restart(): Boolean {
        if (!isReady) return false
        startAfterSeek = true
        requestSeek(0)
        if (!seeks.isActive) finishSeekSequence()
        return true
    }

    fun setPlaybackSpeed(speed: Float): Boolean {
        if (!isReady || !speed.isFinite() || speed <= 0f) return false
        val player = mediaPlayer ?: return false
        val wasPlaying = isPlaying
        return try {
            player.playbackParams = player.playbackParams.setSpeed(speed)
            if (!wasPlaying) {
                if (safeBoolean { player.isPlaying }) player.pause()
                if (safeBoolean { player.isPlaying }) return false
            }
            true
        } catch (_: RuntimeException) {
            if (!wasPlaying) pauseMedia()
            false
        }
    }

    fun beginScrubbing() {
        if (!isReady || scrubbing) return
        scrubWasPlaying = isPlaying
        scrubbing = true
        if (scrubWasPlaying) pauseMedia()
    }

    fun seekTo(positionMs: Int) {
        requestSeek(positionMs)
    }

    fun finishScrubbing(positionMs: Int) {
        if (!scrubbing) {
            requestSeek(positionMs)
            return
        }
        requestSeek(positionMs)
        resumeAfterSeek = scrubWasPlaying && playbackAllowed
        scrubWasPlaying = false
        scrubbing = false
        if (!seeks.isActive) finishSeekSequence()
    }

    fun release() {
        if (released) return
        released = true
        texture.surfaceTextureListener = null
        seeks.clear()
        releasePlayerAndSurface()
        prepared = false
        preparing = false
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {
        if (released || failed) return
        surface?.release()
        surface = Surface(surfaceTexture)
        try {
            mediaPlayer?.setSurface(surface)
            if (prepared && resumeOnSurfaceAvailable && playbackAllowed) mediaPlayer?.start()
            else prepareWhenReady()
            resumeOnSurfaceAvailable = false
        } catch (_: IllegalStateException) {
            fail(0, 0)
        }
        updateViewport()
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {
        updateViewport()
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: android.graphics.SurfaceTexture): Boolean {
        resumeOnSurfaceAvailable = isPlaying
        if (resumeOnSurfaceAvailable) pauseMedia()
        try { mediaPlayer?.setSurface(null) } catch (_: IllegalStateException) { }
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: android.graphics.SurfaceTexture) = Unit

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateViewport()
    }

    private fun prepareWhenReady() {
        val player = mediaPlayer ?: return
        if (released || surface == null || preparing || prepared) return
        try {
            preparing = true
            player.prepareAsync()
        } catch (_: IllegalStateException) {
            fail(0, 0)
        }
    }

    private fun requestSeek(positionMs: Int) {
        if (!isReady) return
        seeks.request(positionMs.coerceIn(0, duration), ::issueSeek)
    }

    private fun issueSeek(positionMs: Int) {
        try {
            mediaPlayer?.seekTo(positionMs.toLong(), MediaPlayer.SEEK_CLOSEST)
        } catch (_: RuntimeException) {
            fail(0, 0)
        }
    }

    private fun finishSeekSequence() {
        if (released || failed || scrubbing) return
        val shouldPlay = startAfterSeek || resumeAfterSeek
        startAfterSeek = false
        resumeAfterSeek = false
        if (shouldPlay) play()
        onSeekSettled(currentPosition)
    }

    private fun fail(what: Int, extra: Int) {
        if (released || failed) return
        failed = true
        prepared = false
        preparing = false
        seeks.clear()
        releasePlayerAndSurface()
        onError(what, extra)
    }

    private fun pauseMedia() {
        if (isPlaying) try { mediaPlayer?.pause() } catch (_: IllegalStateException) { }
    }

    private fun releasePlayerAndSurface() {
        val player = mediaPlayer
        mediaPlayer = null
        try { player?.setSurface(null) } catch (_: IllegalStateException) { }
        try { player?.release() } catch (_: RuntimeException) { }
        surface?.release()
        surface = null
    }

    /** Fit the encoded source/camera rectangle in the black parent; crop within TextureView coordinates. */
    private fun updateViewport() {
        if (videoWidth <= 0 || videoHeight <= 0 || width <= 0 || height <= 0) return
        val crop = if (cropDirection < 0) null else MotionCompositionLayout.cameraRectForDirection(
            cropDirection, cameraOrder, videoWidth, videoHeight) ?: return
        val sourceLeft = crop?.left ?: 0
        val sourceTop = crop?.top ?: 0
        val sourceWidth = crop?.width() ?: videoWidth
        val sourceHeight = crop?.height() ?: videoHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) return
        val aspect = sourceWidth.toFloat() / sourceHeight
        val viewportWidth = minOf(width.toFloat(), height * aspect).toInt().coerceAtLeast(1)
        val viewportHeight = minOf(height.toFloat(), width / aspect).toInt().coerceAtLeast(1)
        if (texture.layoutParams.width != viewportWidth || texture.layoutParams.height != viewportHeight) {
            texture.layoutParams = LayoutParams(viewportWidth, viewportHeight, Gravity.CENTER)
        }
        if (texture.width <= 0 || texture.height <= 0) return
        val transform = Matrix()
        if (cropDirection >= 0) {
            val scaleX = videoWidth.toFloat() / sourceWidth
            val scaleY = videoHeight.toFloat() / sourceHeight
            val dx = -sourceLeft.toFloat() * texture.width / sourceWidth
            val dy = -sourceTop.toFloat() * texture.height / sourceHeight
            transform.setScale(scaleX, scaleY)
            transform.postTranslate(dx, dy)
        }
        texture.setTransform(transform)
    }

    private val duration: Int get() = if (prepared) safeInt { mediaPlayer?.duration ?: 0 } else 0
    private val currentPosition: Int get() = if (prepared) safeInt { mediaPlayer?.currentPosition ?: 0 } else 0
    val isPlaying: Boolean get() = prepared && safeBoolean { mediaPlayer?.isPlaying == true }

    private inline fun safeInt(read: () -> Int): Int = try { read().coerceAtLeast(0) } catch (_: IllegalStateException) { 0 }
    private inline fun safeBoolean(read: () -> Boolean): Boolean = try { read() } catch (_: IllegalStateException) { false }
}

/** Keeps one decoder seek active and replaces its queued target with the latest pointer position. */
internal class MotionSeekQueue {
    private var pendingTarget: Int? = null
    var isActive: Boolean = false
        private set

    fun request(target: Int, issue: (Int) -> Unit) {
        if (isActive) pendingTarget = target
        else {
            isActive = true
            issue(target)
        }
    }

    /** Returns true when the current seek was the last queued target. */
    fun complete(issue: (Int) -> Unit): Boolean {
        val next = pendingTarget
        if (next == null) {
            isActive = false
            return true
        }
        pendingTarget = null
        issue(next)
        return false
    }

    fun clear() {
        pendingTarget = null
        isActive = false
    }
}
