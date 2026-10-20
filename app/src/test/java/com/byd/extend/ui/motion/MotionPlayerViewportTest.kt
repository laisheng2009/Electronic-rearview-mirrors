package com.byd.extend.ui.motion

import com.byd.extend.MotionCompositionLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPlayerViewportTest {
    @Test
    fun compositionFitsInsideMeasuredVideoRegion() {
        val normal = motionPlayerViewport(1920, 1080, 704, 416)
        val fullscreenAboveControls = motionPlayerViewport(1920, 1080, 1280, 560)

        assertViewport(normal, 704, 416, 16.0 / 9.0)
        assertViewport(fullscreenAboveControls, 1280, 560, 16.0 / 9.0)
    }

    @Test
    fun cameraCropFitsNormalAndFullscreenRegionsAtItsRecordedAspect() {
        val crop = MotionCompositionLayout.cameraRectForDirection(0, intArrayOf(2, 0, 3, 1))
        val normal = motionPlayerViewport(crop.width(), crop.height(), 540, 420)
        val fullscreenAboveControls = motionPlayerViewport(crop.width(), crop.height(), 1120, 500)
        val cameraAspect = crop.width().toDouble() / crop.height()

        assertViewport(normal, 540, 420, cameraAspect)
        assertViewport(fullscreenAboveControls, 1120, 500, cameraAspect)
    }

    @Test
    fun invalidDimensionsProduceAnEmptyViewport() {
        assertEquals(MotionPlayerViewport(0, 0), motionPlayerViewport(1920, 1080, 0, 400))
        assertEquals(MotionPlayerViewport(0, 0), motionPlayerViewport(0, 1080, 400, 300))
    }

    private fun assertViewport(
        viewport: MotionPlayerViewport,
        boundsWidth: Int,
        boundsHeight: Int,
        aspect: Double,
    ) {
        assertTrue(viewport.width in 1..boundsWidth)
        assertTrue(viewport.height in 1..boundsHeight)
        assertEquals(aspect, viewport.width.toDouble() / viewport.height, 0.01)
    }
}
