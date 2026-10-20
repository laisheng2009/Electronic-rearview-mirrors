package com.byd.extend.ui.motion

import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MotionThumbnailDiskCacheTest {
    @Rule @JvmField val temporary = TemporaryFolder()

    @Test fun writesAndReadsThePngPayloadFromDisk() {
        val folder = temporary.newFolder("cache")
        val cache = MotionThumbnailDiskCache(folder, 32)
        val key = MotionThumbnailDiskCache.key("volume|content-uri", "size|duration")
        val bytes = byteArrayOf(1, 2, 3, 4)

        assertTrue(cache.write(key) { it.write(bytes); true })
        assertEquals(bytes.toList(), cache.read(key) { it.readBytes().toList() })
        assertEquals(bytes.toList(), MotionThumbnailDiskCache(folder, 32).read(key) { it.readBytes().toList() })
        assertEquals(bytes.size.toLong(), File(folder, "$key.png").length())
    }

    @Test fun aCorruptPngIsRemovedAndCanBeReplaced() {
        val cache = MotionThumbnailDiskCache(temporary.newFolder("cache"), 1024)
        val key = MotionThumbnailDiskCache.key("volume|content-uri", "size|duration")
        val file = File(temporary.root, "cache/$key.png")
        val png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADUlEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC",
        )
        assertTrue(cache.write(key) { it.write(png); true })
        assertNotNull(cache.read(key, ::decodePng))

        assertTrue(cache.write(key) { it.write(png.copyOf(12)); true })
        assertNull(cache.read(key, ::decodePng))
        assertFalse(file.exists())
        assertTrue(cache.write(key) { it.write(png); true })
        assertNotNull(cache.read(key, ::decodePng))
    }

    @Test fun evictsLeastRecentlyUsedFilesToStayWithinTheByteLimit() {
        val cache = MotionThumbnailDiskCache(temporary.newFolder("cache"), 8)
        val first = MotionThumbnailDiskCache.key("volume|first", "revision")
        val second = MotionThumbnailDiskCache.key("volume|second", "revision")
        val newest = MotionThumbnailDiskCache.key("volume|newest", "revision")
        val folder = File(temporary.root, "cache")

        assertTrue(cache.write(first) { it.write(byteArrayOf(1, 2, 3, 4)); true })
        assertTrue(cache.write(second) { it.write(byteArrayOf(5, 6, 7, 8)); true })
        File(folder, "$first.png").setLastModified(1)
        File(folder, "$second.png").setLastModified(2)
        assertNotNull(cache.read(first) { it.readBytes() })
        assertTrue(cache.write(newest) { it.write(byteArrayOf(9, 10, 11, 12)); true })

        assertTrue(File(folder, "$first.png").isFile)
        assertFalse(File(folder, "$second.png").exists())
        assertTrue(File(folder, "$newest.png").isFile)
        assertTrue(folder.listFiles()!!.filter { it.extension == "png" }.sumOf(File::length) <= 8)
    }

    @Test fun snapshotReconciliationRemovesDeletedAndChangedKeys() {
        val cache = MotionThumbnailDiskCache(temporary.newFolder("cache"), 32)
        val retained = MotionThumbnailDiskCache.key("volume|kept", "revision-1")
        val changedOld = MotionThumbnailDiskCache.key("volume|changed", "revision-1")
        val changedNew = MotionThumbnailDiskCache.key("volume|changed", "revision-2")
        val deleted = MotionThumbnailDiskCache.key("volume|deleted", "revision-1")
        listOf(retained, changedOld, deleted).forEach { key ->
            assertTrue(cache.write(key) { it.write(byteArrayOf(1)); true })
        }

        cache.retainKeys(setOf(retained, changedNew))

        assertTrue(File(temporary.root, "cache/$retained.png").isFile)
        assertFalse(File(temporary.root, "cache/$changedOld.png").exists())
        assertFalse(File(temporary.root, "cache/$deleted.png").exists())
        assertNotEquals(retained, changedNew)
    }

    private fun decodePng(file: File): Any? = Class.forName("javax.imageio.ImageIO")
        .getMethod("read", File::class.java)
        .invoke(null, file)
}
