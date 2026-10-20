package com.byd.extend.ui.motion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.extend.MotionMediaLibrary
import com.byd.extend.R
import com.byd.extend.ui.UiPalette
import com.byd.extend.ui.UiStrings
import com.byd.extend.ui.icons.Videocam
import java.io.Closeable
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class MotionThumbnailLoader(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val bitmaps = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val diskCache = MotionThumbnailDiskCache(File(appContext.cacheDir, "motion-thumbnails-v1"))
    private val inFlight = LinkedHashMap<String, DecodeJob>()
    private val waiting = LinkedHashMap<String, DecodeJob>()
    private var nextSubscriptionId = 0L
    private var knownSnapshotKeys: Set<String>? = null
    @Volatile private var closed = false

    private val executor = object : ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(8)) {
        override fun afterExecute(task: Runnable?, failure: Throwable?) {
            super.afterExecute(task, failure)
            submitWaiting()
        }
    }

    internal fun cacheKey(entry: MotionMediaLibrary.Entry): String = MotionThumbnailDiskCache.key(
        "${entry.volumeId}|${entry.contentUri}|${entry.mediaStoreId}|${entry.segmentId}",
        "${entry.sizeBytes}|${entry.durationMs}|${entry.recordedAtMillis}",
    )

    internal fun cached(key: String): Bitmap? = synchronized(lock) { bitmaps.get(key) }

    internal fun reconcileSnapshot(entries: Collection<MotionMediaLibrary.Entry>) {
        val keys = entries.mapTo(LinkedHashSet(), ::cacheKey)
        val staleJobs = synchronized(lock) {
            if (knownSnapshotKeys == keys) return
            knownSnapshotKeys = keys
            bitmaps.snapshot().keys.filter { it !in keys }.forEach { bitmaps.remove(it) }
            inFlight.values.filter { it.key !in keys }.also { jobs ->
                jobs.forEach { job ->
                    inFlight.remove(job.key)
                    waiting.remove(job.key)
                    executor.remove(job.task)
                    job.task.cancel(true)
                }
            }
        }
        diskCache.retainKeys(keys)
        synchronized(lock) {
            staleJobs.forEach { job -> job.subscriptions.values.forEach { it.cancelled.set(true) } }
            submitWaitingLocked()
        }
    }

    internal fun request(entry: MotionMediaLibrary.Entry, onReady: (Bitmap?) -> Unit): () -> Unit {
        val key = cacheKey(entry)
        val subscription = Subscription(onReady)
        synchronized(lock) {
            if (closed) return {}
            bitmaps.get(key)?.let { bitmap ->
                mainHandler.post {
                    val current = synchronized(lock) {
                        !closed && !subscription.cancelled.get() &&
                            (knownSnapshotKeys == null || key in knownSnapshotKeys!!)
                    }
                    if (current) onReady(bitmap)
                }
                return { subscription.cancelled.set(true) }
            }
            var isNew = false
            val job = inFlight[key] ?: DecodeJob(key, entry).also {
                inFlight[key] = it
                isNew = true
            }
            val id = nextSubscriptionId++
            job.subscriptions[id] = subscription
            if (isNew) enqueueLocked(job)
            return {
                subscription.cancelled.set(true)
                synchronized(lock) {
                    if (inFlight[key] !== job) return@synchronized
                    job.subscriptions.remove(id)
                    if (job.subscriptions.isEmpty() && !job.started) {
                        waiting.remove(key)
                        executor.remove(job.task)
                        job.task.cancel(false)
                        inFlight.remove(key)
                        submitWaitingLocked()
                    }
                }
            }
        }
    }

    private inner class DecodeJob(val key: String, val entry: MotionMediaLibrary.Entry) {
        val subscriptions = LinkedHashMap<Long, Subscription>()
        var started = false
        val task = FutureTask<Unit> {
            synchronized(lock) { started = true }
            val decoded = load(entry, key)
            val callbacks = synchronized(lock) {
                if (closed || inFlight[key] !== this) null
                else {
                    inFlight.remove(key)
                    if (decoded != null) bitmaps.put(key, decoded.bitmap)
                    subscriptions.values.toList()
                }
            }
            if (callbacks == null) {
                decoded?.bitmap?.recycle()
            } else {
                if (decoded != null && !decoded.fromDisk) {
                    diskCache.write(key) { output -> decoded.bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
                }
                val stillCurrent = synchronized(lock) {
                    !closed && (knownSnapshotKeys == null || key in knownSnapshotKeys!!)
                }
                if (!stillCurrent) {
                    synchronized(lock) { bitmaps.remove(key) }
                    diskCache.remove(key)
                    decoded?.bitmap?.recycle()
                } else {
                    callbacks.forEach { subscription ->
                        mainHandler.post {
                            val current = synchronized(lock) {
                                !closed && !subscription.cancelled.get() &&
                                    (knownSnapshotKeys == null || key in knownSnapshotKeys!!)
                            }
                            if (current) subscription.callback(decoded?.bitmap)
                        }
                    }
                }
            }
        }
    }

    private data class Subscription(val callback: (Bitmap?) -> Unit, val cancelled: AtomicBoolean = AtomicBoolean())
    private data class Decoded(val bitmap: Bitmap, val fromDisk: Boolean)

    private fun enqueueLocked(job: DecodeJob) {
        try {
            executor.execute(job.task)
        } catch (_: RejectedExecutionException) {
            waiting[job.key] = job
        }
    }

    private fun submitWaiting() = synchronized(lock) { submitWaitingLocked() }

    private fun submitWaitingLocked() {
        if (closed) return
        val iterator = waiting.values.iterator()
        while (iterator.hasNext()) {
            val job = iterator.next()
            if (job.task.isCancelled || inFlight[job.key] !== job) {
                iterator.remove()
                continue
            }
            try {
                executor.execute(job.task)
                iterator.remove()
            } catch (_: RejectedExecutionException) {
                return
            }
        }
    }

    private fun load(entry: MotionMediaLibrary.Entry, key: String): Decoded? {
        val cached = diskCache.read(key) { file ->
            BitmapFactory.decodeFile(file.absolutePath)?.let { bitmap ->
                if (fitsThumbnail(bitmap)) bitmap else { bitmap.recycle(); null }
            }
        }
        if (cached != null) return Decoded(cached, true)
        val retriever = MediaMetadataRetriever()
        val bitmap = try {
            retriever.setDataSource(appContext, entry.contentUri)
            if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270)
            } else {
                val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: return null
                val scale = minOf(480f / frame.width, 270f / frame.height, 1f)
                val scaled = Bitmap.createScaledBitmap(frame,
                    (frame.width * scale).toInt().coerceAtLeast(1),
                    (frame.height * scale).toInt().coerceAtLeast(1), true)
                if (scaled !== frame) frame.recycle()
                scaled
            }
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: RuntimeException) { }
        }
        return bitmap?.let { if (fitsThumbnail(it)) Decoded(it, false) else { it.recycle(); null } }
    }

    private fun fitsThumbnail(bitmap: Bitmap) = bitmap.width in 1..480 && bitmap.height in 1..270

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            bitmaps.evictAll()
            inFlight.values.forEach { it.task.cancel(true) }
            waiting.clear()
            inFlight.clear()
        }
        executor.shutdownNow()
    }
}

@Composable
internal fun MotionArchiveThumbnail(
    entry: MotionMediaLibrary.Entry,
    loader: MotionThumbnailLoader,
    colors: UiPalette,
    strings: UiStrings,
    modifier: Modifier = Modifier,
) {
    val key = remember(loader, entry.contentUri, entry.volumeId, entry.mediaStoreId, entry.segmentId,
        entry.sizeBytes, entry.durationMs, entry.recordedAtMillis) { loader.cacheKey(entry) }
    var bitmap by remember(loader, key) { mutableStateOf(loader.cached(key)) }
    DisposableEffect(loader, key, entry.available) {
        var active = true
        val cancel = if (bitmap == null) loader.request(entry) {
            if (active) bitmap = it
        } else null
        onDispose {
            active = false
            cancel?.invoke()
        }
    }

    Box(modifier.background(colors.panelAlt), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop)
        } else Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Videocam, contentDescription = null, tint = colors.muted,
                modifier = Modifier.padding(bottom = 3.dp))
            Text(strings.resource(if (entry.available) R.string.motion_archive_thumbnail_unavailable
                else R.string.motion_archive_unavailable), color = colors.muted, fontSize = 11.sp,
                textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}
