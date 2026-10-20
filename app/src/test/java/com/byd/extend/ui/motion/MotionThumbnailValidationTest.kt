package com.byd.extend.ui.motion

import java.util.Base64
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MotionThumbnailValidationTest {
    @Rule @JvmField val temporaryFolder = TemporaryFolder()

    @Test fun acceptsPreparedPngFromFile() {
        val cover = temporaryFolder.newFile("cover.png")
        cover.writeBytes(png1x1())

        assertTrue(MotionThumbnailValidation.isValidPng(cover.readBytes()))
    }

    @Test fun rejectsOversizedAndOutOfBoundsPngs() {
        assertFalse(MotionThumbnailValidation.isValidPng(ByteArray(MotionThumbnailValidation.MAX_BYTES + 1)))

        val tooWide = png1x1().apply {
            this[18] = 0x01
            this[19] = 0xe1.toByte()
        }
        assertFalse(MotionThumbnailValidation.isValidPng(tooWide))
    }

    private fun png1x1(): ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4//8/AAX+Av4N70a4AAAAAElFTkSuQmCC",
    )
}
