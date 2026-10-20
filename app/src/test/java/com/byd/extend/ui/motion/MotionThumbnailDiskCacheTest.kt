package com.byd.extend.ui.motion

import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MotionThumbnailDiskCacheTest {
    @Rule @JvmField val temporary = TemporaryFolder()

    @Test fun actualPngSurvivesCacheRecreationAndDecodesFromThePersistentFile() {
        val folder = temporary.newFolder("cache")
        val firstCache = MotionThumbnailDiskCache(folder)
        val secondCache = MotionThumbnailDiskCache(folder)
        val key = MotionThumbnailDiskCache.key("volume|content-uri|42|segment", "size|duration|time")

        assertTrue(firstCache.write(key) { it.write(validPng); true })
        val decoded = secondCache.read(key, ::decodePng)

        assertNotNull(decoded)
        assertEquals(validPng.toList(), File(folder, "$key.png").readBytes().toList())
        assertTrue(File(folder, "$key.png").isFile)
    }

    @Test fun corruptImageIsRemovedAndAValidImageCanReplaceIt() {
        val folder = temporary.newFolder("cache")
        val cache = MotionThumbnailDiskCache(folder)
        val key = MotionThumbnailDiskCache.key("volume|content-uri", "size|duration")
        val file = File(folder, "$key.png")

        assertTrue(cache.write(key) { it.write(validPng.copyOf(12)); true })
        assertNull(cache.read(key, ::decodePng))
        assertFalse(file.exists())

        assertTrue(cache.write(key) { it.write(validPng); true })
        assertNotNull(cache.read(key, ::decodePng))
    }

    @Test fun successfulRevisionReplacementRemovesOnlyThatIdentitysOlderCovers() {
        val folder = temporary.newFolder("cache")
        val cache = MotionThumbnailDiskCache(folder)
        val old = MotionThumbnailDiskCache.key("volume|same-uri|7|segment", "revision-1")
        val current = MotionThumbnailDiskCache.key("volume|same-uri|7|segment", "revision-2")
        val other = MotionThumbnailDiskCache.key("volume|other-uri|8|segment", "revision-1")

        assertTrue(cache.write(old) { it.write(validPng); true })
        assertTrue(cache.write(other) { it.write(validPng); true })
        assertTrue(cache.write(current) { it.write(validPng); true })

        assertFalse(File(folder, "$old.png").exists())
        assertTrue(File(folder, "$current.png").isFile)
        assertTrue(File(folder, "$other.png").isFile)
    }

    @Test fun confirmedIdentityRemovalDeletesAllRevisionsButNoOtherIdentity() {
        val folder = temporary.newFolder("cache")
        val cache = MotionThumbnailDiskCache(folder)
        val old = MotionThumbnailDiskCache.key("volume|deleted-uri|7|segment", "revision-1")
        val newer = MotionThumbnailDiskCache.key("volume|deleted-uri|7|segment", "revision-2")
        val retained = MotionThumbnailDiskCache.key("volume|kept-uri|8|segment", "revision-1")
        listOf(old, newer, retained).forEach { key ->
            assertTrue(cache.write(key) { it.write(validPng); true })
        }

        cache.removeIdentity(newer)

        assertFalse(File(folder, "$old.png").exists())
        assertFalse(File(folder, "$newer.png").exists())
        assertTrue(File(folder, "$retained.png").isFile)
    }

    // Android's compile classpath omits ImageIO; the host JVM still performs the real PNG decode.
    private fun decodePng(file: File): Any? = Class.forName("javax.imageio.ImageIO")
        .getMethod("read", File::class.java).invoke(null, file)

    private companion object {
        val validPng = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADUlEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC",
        )
    }
}
