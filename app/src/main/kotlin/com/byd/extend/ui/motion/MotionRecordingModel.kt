package com.byd.extend.ui.motion

internal fun motionCameraOrder(encoded: String): List<Int> {
    val fields = encoded.split(',')
    val order = fields.mapNotNull { it.toIntOrNull() }
    return if (fields.size == 4 && order.size == 4 && order.toSet() == setOf(0, 1, 2, 3)) order
    else listOf(0, 1, 2, 3)
}

internal fun swapMotionCameras(order: List<Int>, from: Int, to: Int): List<Int> =
    if (from !in order.indices || to !in order.indices) order else order.toMutableList().apply {
        this[from] = order[to]
        this[to] = order[from]
    }

internal fun motionTileAt(x: Float, y: Float): Int? =
    if (x >= 0f && x < 1f && y >= 0f && y < 1f)
        (if (y < .5f) 0 else 2) + (if (x < .5f) 0 else 1) else null
