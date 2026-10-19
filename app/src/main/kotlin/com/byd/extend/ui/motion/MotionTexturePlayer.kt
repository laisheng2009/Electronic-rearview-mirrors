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
import android.widget.MediaController
import android.widget.MediaController.MediaPlayerControl
import com.byd.extend.MotionCompositionLayout

/** One media pipeline for composition and all four camera crops. Changing the crop never seeks or reloads. */
internal class MotionTexturePlayer(
    context: Context,
    uri: Uri,
    private val cameraOrder: IntArray?,
    private val onPrepared: (durationMs: Int) -> Unit,
    private val onError: (what: Int, extra: Int) -> Unit,
) : FrameLayout(context), TextureView.SurfaceTextureListener, MediaPlayerControl {
    private val texture = TextureView(context)
    private val controller = MediaController(context)
    private var mediaPlayer: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false
    private var preparing = false
    private var released = false
    private var cropDirection = -1
    private var videoWidth = 0
    private var videoHeight = 0
    private var resumeOnSurfaceAvailable = false
    private var playbackAllowed = true
    private var failed = false

    init {
        setBackgroundColor(android.graphics.Color.BLACK)
        clipChildren = true
        clipToPadding = true
        texture.surfaceTextureListener = this
        texture.isClickable = true
        texture.setOnClickListener { controller.show(3_000) }
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        controller.setMediaPlayer(this)
        controller.setAnchorView(this)
        controller.isEnabled = false
        try {
            // Assign first so even a failing setDataSource path releases this exact instance.
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
                    controller.isEnabled = true
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
            pause()
        }
    }

    fun release() {
        if (released) return
        released = true
        controller.hide()
        controller.isEnabled = false
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
            if (prepared && resumeOnSurfaceAvailable && playbackAllowed) {
                mediaPlayer?.start()
                resumeOnSurfaceAvailable = false
            } else prepareWhenReady()
        } catch (_: IllegalStateException) {
            fail(0, 0)
        }
        updateViewport()
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {
        updateViewport()
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: android.graphics.SurfaceTexture): Boolean {
        if (isPlaying) {
            try { mediaPlayer?.pause() } catch (_: IllegalStateException) { }
            resumeOnSurfaceAvailable = true
        }
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

    private fun fail(what: Int, extra: Int) {
        if (released || failed) return
        failed = true
        prepared = false
        preparing = false
        controller.isEnabled = false
        releasePlayerAndSurface()
        onError(what, extra)
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
        val aspect = sourceWidth.toFloat() / sourceHeight.toFloat()
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

    override fun start() {
        if (!released && prepared && surface != null && playbackAllowed)
            try { mediaPlayer?.start() } catch (_: IllegalStateException) { }
    }

    override fun pause() {
        if (isPlaying) try { mediaPlayer?.pause() } catch (_: IllegalStateException) { }
    }

    override fun getDuration(): Int = if (prepared) safeInt { mediaPlayer?.duration ?: 0 } else 0

    override fun getCurrentPosition(): Int = if (prepared) safeInt { mediaPlayer?.currentPosition ?: 0 } else 0

    override fun seekTo(pos: Int) {
        if (prepared) try { mediaPlayer?.seekTo(pos.coerceAtLeast(0)) } catch (_: IllegalStateException) { }
    }

    override fun isPlaying(): Boolean = prepared && safeBoolean { mediaPlayer?.isPlaying == true }

    override fun getBufferPercentage(): Int = 0
    override fun canPause(): Boolean = true
    override fun canSeekBackward(): Boolean = prepared
    override fun canSeekForward(): Boolean = prepared
    override fun getAudioSessionId(): Int = try { mediaPlayer?.audioSessionId ?: 0 } catch (_: IllegalStateException) { 0 }

    private inline fun safeInt(read: () -> Int): Int = try { read().coerceAtLeast(0) } catch (_: IllegalStateException) { 0 }
    private inline fun safeBoolean(read: () -> Boolean): Boolean = try { read() } catch (_: IllegalStateException) { false }
}
