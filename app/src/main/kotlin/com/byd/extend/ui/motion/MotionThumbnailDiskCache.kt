package com.byd.extend.ui.motion

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

internal class MotionThumbnailDiskCache(
    private val directory: File,
    private val maxBytes: Long = 64L * 1024 * 1024,
) {
    private val lock = Any()

    init {
        require(maxBytes >= 0)
        synchronized(lock) { if (ensureDirectory()) removeTemporaryFiles() }
    }

    fun <T : Any> read(key: String, decode: (File) -> T?): T? {
        val file = synchronized(lock) {
            cacheFile(key)?.takeIf(File::isFile)?.also(::touch)
        } ?: return null
        val value = try { decode(file) } catch (_: Exception) { null }
        if (value == null) synchronized(lock) { file.delete() }
        return value
    }

    fun write(key: String, encode: (OutputStream) -> Boolean): Boolean = synchronized(lock) {
        val target = cacheFile(key) ?: return@synchronized false
        if (!ensureDirectory()) return@synchronized false
        val temporary = try { File.createTempFile("thumb-", ".tmp", directory) }
        catch (_: Exception) { return@synchronized false }
        try {
            var encoded = false
            FileOutputStream(temporary).use { output ->
                encoded = encode(output)
                output.flush()
            }
            if (!encoded || temporary.length() !in 1..maxBytes) return@synchronized false
            try {
                Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), REPLACE_EXISTING)
            } catch (_: FileAlreadyExistsException) {
                Files.move(temporary.toPath(), target.toPath(), REPLACE_EXISTING)
            }
            touch(target)
            trim()
            target.isFile
        } catch (_: Exception) {
            false
        } finally {
            temporary.delete()
        }
    }

    fun remove(key: String) = synchronized(lock) { cacheFile(key)?.delete() }

    fun retainKeys(keys: Set<String>) = synchronized(lock) {
        if (!ensureDirectory()) return@synchronized
        val keep = keys.mapNotNullTo(HashSet()) { cacheFile(it)?.name }
        cacheFiles().filter { it.name !in keep }.forEach { it.delete() }
        removeTemporaryFiles()
    }

    private fun ensureDirectory() = directory.isDirectory || (!directory.exists() && directory.mkdirs())

    private fun cacheFile(key: String): File? =
        if (KEY.matches(key)) File(directory, "$key.png") else null

    private fun cacheFiles() = directory.listFiles { file ->
        file.isFile && file.name.endsWith(".png") && KEY.matches(file.name.removeSuffix(".png"))
    }?.toList().orEmpty()

    private fun trim() {
        val files = cacheFiles()
        var bytes = files.sumOf(File::length)
        for (file in files.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })) {
            if (bytes <= maxBytes) break
            val size = file.length()
            if (file.delete()) bytes -= size
        }
    }

    private fun touch(file: File) {
        file.setLastModified(maxOf(System.currentTimeMillis(), file.lastModified() + 1))
    }

    private fun removeTemporaryFiles() {
        directory.listFiles { file -> file.isFile && file.name.startsWith("thumb-") && file.name.endsWith(".tmp") }
            ?.forEach { it.delete() }
    }

    companion object {
        private val KEY = Regex("[0-9a-f]{64}")

        fun key(identity: String, revision: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(identity.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            return digest.digest(revision.toByteArray(Charsets.UTF_8)).joinToString("") {
                (it.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        }
    }
}
