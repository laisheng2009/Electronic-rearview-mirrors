package com.byd.extend.ui.motion

import android.content.Context
import android.graphics.Bitmap
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
import com.byd.extend.ui.icons.Videocam
import com.byd.extend.ui.UiPalette
import com.byd.extend.ui.UiStrings
import java.io.Closeable
import java.util.LinkedHashMap
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
    private val failures = object : LinkedHashMap<String, Unit>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > 64
    }
    // Main-thread waiters belong only to currently composed lazy cards, not the whole archive.
    private val waiting = LinkedHashSet<FutureTask<Unit>>()
    private val executor = object : ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(8)) {
        override fun afterExecute(task: Runnable?, failure: Throwable?) {
            super.afterExecute(task, failure)
            mainHandler.post { submitWaiting() }
        }
    }
    @Volatile private var closed = false

    internal fun cacheKey(entry: MotionMediaLibrary.Entry) =
        "${entry.contentUri}|${entry.sizeBytes}|${entry.durationMs}|${entry.status}|${entry.available}"

    internal fun cached(key: String): Bitmap? = synchronized(lock) { bitmaps.get(key) }

    internal fun request(entry: MotionMediaLibrary.Entry, onReady: (Bitmap?) -> Unit): () -> Unit {
        val key = cacheKey(entry)
        val cached = synchronized(lock) {
            if (closed) return {}
            bitmaps.get(key)
        }
        if (cached != null) {
            mainHandler.post { if (!closed) onReady(cached) }
            return {}
        }
        if (synchronized(lock) { closed || failures.containsKey(key) }) {
            mainHandler.post { if (!closed) onReady(null) }
            return {}
        }

        val cancelled = AtomicBoolean(false)
        val task = FutureTask<Unit> {
                val bitmap = decode(entry)
                synchronized(lock) {
                    if (!closed && !cancelled.get()) {
                        if (bitmap == null) failures[key] = Unit else bitmaps.put(key, bitmap)
                    }
                }
                mainHandler.post { if (!closed && !cancelled.get()) onReady(bitmap) }
            }
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            if (!closed) waiting.add(task)
        }
        return {
            cancelled.set(true)
            task.cancel(true)
            waiting.remove(task)
            executor.remove(task)
            executor.purge()
            submitWaiting()
        }
    }

    private fun submitWaiting() {
        if (closed) return
        val iterator = waiting.iterator()
        while (iterator.hasNext()) {
            val task = iterator.next()
            if (task.isCancelled) {
                iterator.remove()
                continue
            }
            try {
                executor.execute(task)
                iterator.remove()
            } catch (_: RejectedExecutionException) {
                return
            }
        }
    }

    private fun decode(entry: MotionMediaLibrary.Entry): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
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
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            bitmaps.evictAll()
            failures.clear()
        }
        waiting.forEach { it.cancel(true) }
        waiting.clear()
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
    val key = remember(loader, entry.contentUri, entry.sizeBytes, entry.durationMs, entry.status, entry.available) {
        loader.cacheKey(entry)
    }
    var bitmap by remember(loader, key) { mutableStateOf(loader.cached(key)) }
    DisposableEffect(loader, key, entry.available) {
        var active = true
        val cancel = if (entry.available && bitmap == null) loader.request(entry) {
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
