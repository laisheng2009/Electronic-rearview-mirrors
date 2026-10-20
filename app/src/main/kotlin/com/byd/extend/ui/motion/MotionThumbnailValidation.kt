package com.byd.extend.ui.motion

internal object MotionThumbnailValidation {
    const val MAX_BYTES = 2 * 1024 * 1024

    private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

    fun isValidPng(bytes: ByteArray): Boolean {
        if (bytes.size !in 33..MAX_BYTES) return false
        if (pngSignature.indices.any { bytes[it] != pngSignature[it] }) return false
        if (readInt(bytes, 8) != 13 || bytes[12] != 0x49.toByte() || bytes[13] != 0x48.toByte()
            || bytes[14] != 0x44.toByte() || bytes[15] != 0x52.toByte()) return false
        return readInt(bytes, 16) in 1..480 && readInt(bytes, 20) in 1..270
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
}
