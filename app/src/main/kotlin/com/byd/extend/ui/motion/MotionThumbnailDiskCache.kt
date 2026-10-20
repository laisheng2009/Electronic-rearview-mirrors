package com.byd.extend.ui.motion

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import com.byd.extend.MotionThumbnailKey

class MotionThumbnailDiskCache(private val directory: File) {
    private val lock = Any()
    private var temporaryFilesRemoved = false

    fun <T : Any> read(key: String, decode: (File) -> T?): T? {
        val file = file(key) ?: return null
        val value = try { decode(file) } catch (_: Exception) { null }
        if (value == null) synchronized(lock) { file.delete() }
        return value
    }

    fun file(key: String): File? = synchronized(lock) {
        cacheFile(key)?.takeIf(File::isFile)
    }

    fun write(key: String, encode: (OutputStream) -> Boolean): Boolean = synchronized(lock) {
        val target = cacheFile(key) ?: return@synchronized false
        if (!ensureDirectory()) return@synchronized false
        removeTemporaryFiles()
        val temporary = try { File.createTempFile("thumb-", ".tmp", directory) }
        catch (_: Exception) { return@synchronized false }
        try {
            val encoded = FileOutputStream(temporary).use { output ->
                val success = encode(output)
                output.flush()
                success
            }
            if (!encoded || temporary.length() == 0L) return@synchronized false
            try {
                Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), REPLACE_EXISTING)
            } catch (_: FileAlreadyExistsException) {
                Files.move(temporary.toPath(), target.toPath(), REPLACE_EXISTING)
            }
            removeOtherRevisions(key)
            target.isFile
        } catch (_: Exception) {
            false
        } finally {
            temporary.delete()
        }
    }

    fun remove(key: String) = synchronized(lock) { cacheFile(key)?.delete() }

    fun removeIdentity(key: String) = synchronized(lock) {
        val identityPrefix = identityPrefix(key) ?: return@synchronized
        cacheFiles().filter { it.name.startsWith(identityPrefix) }.forEach { it.delete() }
    }

    private fun removeOtherRevisions(key: String) {
        val identityPrefix = identityPrefix(key) ?: return
        val keep = "$key.png"
        cacheFiles().filter { it.name.startsWith(identityPrefix) && it.name != keep }
            .forEach { it.delete() }
    }

    private fun ensureDirectory() = directory.isDirectory || (!directory.exists() && directory.mkdirs())

    private fun cacheFile(key: String): File? =
        if (KEY.matches(key)) File(directory, "$key.png") else null

    private fun cacheFiles() = directory.listFiles { file ->
        file.isFile && file.name.endsWith(".png") && KEY.matches(file.name.removeSuffix(".png"))
    }?.toList().orEmpty()

    private fun identityPrefix(key: String) =
        if (KEY.matches(key)) "${key.substringBefore('-')}-" else null

    private fun removeTemporaryFiles() {
        if (temporaryFilesRemoved) return
        directory.listFiles { file -> file.isFile && file.name.startsWith("thumb-") && file.name.endsWith(".tmp") }
            ?.forEach { it.delete() }
        temporaryFilesRemoved = true
    }

    companion object {
        private val KEY = Regex("[0-9a-f]{64}-[0-9a-f]{64}")

        @JvmStatic
        fun key(identity: String, revision: String): String = MotionThumbnailKey.key(identity, revision)
    }
}
