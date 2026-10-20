package com.byd.extend.ui.motion

import kotlin.math.roundToInt

internal data class MotionPlayerViewport(val width: Int, val height: Int)

internal fun motionPlayerViewport(
    sourceWidth: Int,
    sourceHeight: Int,
    boundsWidth: Int,
    boundsHeight: Int,
): MotionPlayerViewport {
    if (sourceWidth <= 0 || sourceHeight <= 0 || boundsWidth <= 0 || boundsHeight <= 0) {
        return MotionPlayerViewport(0, 0)
    }
    val scale = minOf(boundsWidth.toDouble() / sourceWidth, boundsHeight.toDouble() / sourceHeight)
    return MotionPlayerViewport(
        (sourceWidth * scale).roundToInt().coerceIn(1, boundsWidth),
        (sourceHeight * scale).roundToInt().coerceIn(1, boundsHeight),
    )
}
