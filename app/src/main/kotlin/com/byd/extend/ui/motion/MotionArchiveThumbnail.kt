package com.byd.extend.ui.motion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** App-side passive reader for covers already persisted by the Motion worker. */
internal class MotionThumbnailLoader(
    context: Context,
    private val library: MotionMediaLibrary,
) : Closeable {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val bitmaps = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val inFlight = LinkedHashMap<String, DecodeJob>()
    private val waiting = LinkedHashMap<String, DecodeJob>()
    private val subscriptions = LinkedHashMap<String, LinkedHashMap<Long, Subscription>>()
    private var nextSubscriptionId = 0L
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

    /** Reconciles active missing subscriptions on every refresh, including an unchanged snapshot. */
    internal fun reconcileSnapshot(entries: Collection<MotionMediaLibrary.Entry>) {
        synchronized(lock) {
            if (closed) return
            for (entry in entries) {
                val key = cacheKey(entry)
                if (bitmaps.get(key) != null || subscriptions[key].isNullOrEmpty() || key in inFlight) continue
                val job = DecodeJob(key, entry)
                inFlight[key] = job
                enqueueLocked(job)
            }
        }
    }

    internal fun request(entry: MotionMediaLibrary.Entry, onReady: (Bitmap?) -> Unit): () -> Unit {
        val key = cacheKey(entry)
        val subscription = Subscription(onReady)
        synchronized(lock) {
            if (closed) return {}
            bitmaps.get(key)?.let { bitmap ->
                mainHandler.post {
                    if (!closed && !subscription.cancelled.get()) onReady(bitmap)
                }
                return { subscription.cancelled.set(true) }
            }
            val id = nextSubscriptionId++
            subscriptions.getOrPut(key) { LinkedHashMap() }[id] = subscription
            if (key !in inFlight) {
                val job = DecodeJob(key, entry)
                inFlight[key] = job
                enqueueLocked(job)
            }
            return {
                subscription.cancelled.set(true)
                synchronized(lock) {
                    subscriptions[key]?.let { active ->
                        active.remove(id)
                        if (active.isEmpty()) subscriptions.remove(key)
                    }
                    val job = inFlight[key]
                    if (job != null && subscriptions[key].isNullOrEmpty() && !job.started) {
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
        var started = false
        val task = FutureTask<Unit> {
            synchronized(lock) { started = true }
            val bitmap = load(entry, key)
            val callbacks = synchronized(lock) {
                if (closed || inFlight[key] !== this) null
                else {
                    inFlight.remove(key)
                    subscriptions[key]?.values?.toList().orEmpty().also { active ->
                        if (bitmap != null && active.isNotEmpty()) bitmaps.put(key, bitmap)
                    }
                }
            }
            if (callbacks == null || callbacks.isEmpty()) {
                bitmap?.recycle()
            } else if (bitmap != null) {
                callbacks.forEach { subscription ->
                    mainHandler.post {
                        if (!closed && !subscription.cancelled.get()) subscription.callback(bitmap)
                    }
                }
            }
            // A missing worker asset leaves subscriptions active for the next same-key refresh.
        }
    }

    private data class Subscription(val callback: (Bitmap?) -> Unit, val cancelled: AtomicBoolean = AtomicBoolean())

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

    private fun load(entry: MotionMediaLibrary.Entry, key: String): Bitmap? {
        val descriptor = try { library.openThumbnail(entry, key) } catch (_: RuntimeException) { null } ?: return null
        val bytes = try {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() > MAX_THUMBNAIL_BYTES - count) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (_: Exception) {
            return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_WIDTH || bounds.outHeight !in 1..MAX_HEIGHT) return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (bitmap.width !in 1..MAX_WIDTH || bitmap.height !in 1..MAX_HEIGHT) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            bitmaps.evictAll()
            inFlight.values.forEach { it.task.cancel(true) }
            subscriptions.clear()
            waiting.clear()
            inFlight.clear()
        }
        executor.shutdownNow()
    }

    private companion object {
        const val MAX_WIDTH = 480
        const val MAX_HEIGHT = 270
        const val MAX_THUMBNAIL_BYTES = 2 * 1024 * 1024
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
