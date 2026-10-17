package com.byd.extend.ui.motion

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.viewinterop.AndroidView
import com.byd.extend.ui.CameraHostKind
import com.byd.extend.ui.CameraHostSlot

internal val LocalMotionCameraHost = staticCompositionLocalOf<@Composable (CameraHostSlot) -> Unit> {
    error("Motion camera host is not installed")
}

@Composable
internal fun MotionCameraTile(direction: Int, modifier: Modifier = Modifier) {
    require(direction in 0..3) { "Motion direction must be in range 0..3" }
    val cameraHost = LocalMotionCameraHost.current
    val kind = when (direction) {
        0 -> CameraHostKind.MotionFrontPreview
        1 -> CameraHostKind.MotionRearPreview
        2 -> CameraHostKind.MotionLeftPreview
        else -> CameraHostKind.MotionRightPreview
    }
    Box(modifier.clipToBounds()) {
        cameraHost(CameraHostSlot(kind = kind, sourceIndex = direction))
    }
}

/** One app-owned raw pano_h output surface; CameraProbeActivity owns its camera lease. */
class MotionCompositionPreviewView(
    context: Context,
    val direction: Int,
    unavailableText: String,
    private val listener: Listener,
) : FrameLayout(context), TextureView.SurfaceTextureListener {
    interface Listener {
        fun onMotionPreviewSurfaceAvailable(view: MotionCompositionPreviewView, surface: Surface)
        fun onMotionPreviewSurfaceDestroyed(view: MotionCompositionPreviewView)
    }

    private val preview = TextureView(context).apply {
        surfaceTextureListener = this@MotionCompositionPreviewView
    }
    private val unavailable = TextView(context).apply {
        text = unavailableText
        gravity = Gravity.CENTER
        setTextColor(0xffffffff.toInt())
        textSize = 12f
        setPadding(8, 5, 8, 5)
        setBackgroundColor(0x99000000.toInt())
    }

    init {
        setBackgroundColor(0xff000000.toInt())
        addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(unavailable, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            Gravity.CENTER))
    }

    fun showUnavailable() {
        unavailable.visibility = VISIBLE
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width <= 0 || height <= 0) return
        val ratio = SOURCE_ASPECT
        val previewWidth: Int
        val previewHeight: Int
        if (width.toFloat() / height > ratio) {
            previewWidth = (height * ratio).toInt()
            previewHeight = height
        } else {
            previewWidth = width
            previewHeight = (width / ratio).toInt()
        }
        val params = preview.layoutParams as LayoutParams
        if (params.width != previewWidth || params.height != previewHeight) {
            params.width = previewWidth
            params.height = previewHeight
            params.gravity = Gravity.CENTER
            preview.layoutParams = params
        }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        texture.setDefaultBufferSize(SOURCE_WIDTH, SOURCE_HEIGHT)
        showUnavailable()
        listener.onMotionPreviewSurfaceAvailable(this, Surface(texture))
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        listener.onMotionPreviewSurfaceDestroyed(this)
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        unavailable.visibility = GONE
    }

    private companion object {
        const val SOURCE_WIDTH = 1920
        const val SOURCE_HEIGHT = 1300
        val SOURCE_ASPECT = SOURCE_WIDTH.toFloat() / SOURCE_HEIGHT
    }
}
