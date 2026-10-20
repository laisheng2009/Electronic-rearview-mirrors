package com.byd.extend.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionSeekQueueTest {
    @Test
    fun keepsOnlyLatestTargetAndFinishesAtReleasePosition() {
        val queue = MotionSeekQueue()
        val issued = mutableListOf<Int>()

        queue.request(100) { issued += it }
        queue.request(250) { issued += it }
        queue.request(400) { issued += it }
        queue.request(325) { issued += it }

        assertEquals(listOf(100), issued)
        assertFalse(queue.complete { issued += it })
        assertEquals(listOf(100, 325), issued)
        assertTrue(queue.complete { issued += it })
        assertFalse(queue.isActive)
    }
}
