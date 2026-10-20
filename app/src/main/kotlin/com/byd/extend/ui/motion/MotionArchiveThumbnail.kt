package com.byd.extend.ui.motion

import android.os.ParcelFileDescriptor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.crossfade
import com.byd.extend.MotionMediaLibrary
import com.byd.extend.R
import com.byd.extend.ui.UiPalette
import com.byd.extend.ui.UiStrings
import com.byd.extend.ui.icons.Videocam
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem

internal class MotionThumbnailRequest(
    val entry: MotionMediaLibrary.Entry,
    val cacheKey: String,
    val retryGeneration: Long,
    val snapshotGeneration: Long,
) {
    override fun equals(other: Any?): Boolean = other is MotionThumbnailRequest &&
        cacheKey == other.cacheKey && retryGeneration == other.retryGeneration

    override fun hashCode(): Int = 31 * cacheKey.hashCode() + retryGeneration.hashCode()
}

internal fun MotionMediaLibrary.Entry.motionThumbnailCacheKey(): String = MotionThumbnailDiskCache.key(
    "$volumeId|$contentUri|$mediaStoreId|$segmentId",
    "$sizeBytes|$durationMs|$recordedAtMillis",
)

internal object MotionThumbnailKeyer : Keyer<MotionThumbnailRequest> {
    override fun key(data: MotionThumbnailRequest, options: Options): String = data.cacheKey
}

internal class MotionThumbnailFetcherFactory(
    private val library: MotionMediaLibrary,
) : Fetcher.Factory<MotionThumbnailRequest> {
    override fun create(data: MotionThumbnailRequest, options: Options, imageLoader: ImageLoader): Fetcher =
        MotionThumbnailFetcher(data, library)
}

private class MotionThumbnailFetcher(
    private val request: MotionThumbnailRequest,
    private val library: MotionMediaLibrary,
) : Fetcher {
    override suspend fun fetch(): SourceFetchResult {
        val bytes = readPreparedThumbnail()
        if (!MotionThumbnailValidation.isValidPng(bytes)) throw MotionThumbnailInvalidException()
        return SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), FileSystem.SYSTEM),
            mimeType = "image/png",
            dataSource = DataSource.DISK,
        )
    }

    private suspend fun readPreparedThumbnail(): ByteArray = withContext(Dispatchers.IO) {
        val descriptor = try {
            library.openThumbnail(request.entry, request.cacheKey)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw MotionThumbnailPendingException(error)
        } ?: throw MotionThumbnailPendingException()

        try {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() > MotionThumbnailValidation.MAX_BYTES - count) {
                        throw MotionThumbnailInvalidException()
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: MotionThumbnailInvalidException) {
            throw error
        } catch (error: IOException) {
            throw MotionThumbnailPendingException(error)
        } catch (error: Exception) {
            throw MotionThumbnailPendingException(error)
        }
    }
}

internal class MotionThumbnailPendingException(cause: Throwable? = null) : IOException(cause)

private class MotionThumbnailInvalidException : IOException()

internal enum class MotionThumbnailDisplayStatus { LOADING, PENDING, FAILED, SUCCESS }

internal data class MotionThumbnailDisplayState(
    val requestGeneration: Long,
    val status: MotionThumbnailDisplayStatus,
)

@Composable
internal fun MotionArchiveThumbnail(
    entry: MotionMediaLibrary.Entry,
    archiveState: MotionArchiveState,
    colors: UiPalette,
    strings: UiStrings,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val cacheKey = remember(entry.volumeId, entry.contentUri, entry.mediaStoreId, entry.segmentId,
        entry.sizeBytes, entry.durationMs, entry.recordedAtMillis) { entry.motionThumbnailCacheKey() }
    var displayState by remember(cacheKey) { mutableStateOf<MotionThumbnailDisplayState?>(null) }
    val pendingGeneration = archiveState.pendingThumbnailGeneration(cacheKey)
    val retryGeneration = maxOf(
        displayState?.requestGeneration ?: 0L,
        pendingGeneration?.takeIf { archiveState.thumbnailRefreshGeneration > it }?.plus(1L) ?: 0L,
    )
    val requestData = remember(cacheKey, retryGeneration) {
        MotionThumbnailRequest(entry, cacheKey, retryGeneration, archiveState.thumbnailRefreshGeneration)
    }
    val request = remember(context, requestData) {
        ImageRequest.Builder(context).data(requestData).crossfade(false).build()
    }
    val currentStatus = displayState?.takeIf { it.requestGeneration == retryGeneration }?.status
    val canRequestThumbnail = archiveState.canRequestThumbnail(cacheKey)
    val hasCachedThumbnail = archiveState.imageLoader.memoryCache?.get(MemoryCache.Key(cacheKey)) != null

    Box(modifier.background(colors.panelAlt), contentAlignment = Alignment.Center) {
        if (entry.available) {
            if (canRequestThumbnail || hasCachedThumbnail) {
                AsyncImage(
                    model = request,
                    imageLoader = archiveState.imageLoader,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onState = { state ->
                        when (state) {
                            is AsyncImagePainter.State.Success -> {
                                archiveState.markThumbnailResolved(cacheKey)
                                displayState = MotionThumbnailDisplayState(
                                    retryGeneration, MotionThumbnailDisplayStatus.SUCCESS,
                                )
                            }
                            is AsyncImagePainter.State.Error -> {
                                val pending = state.result.throwable.isThumbnailPending()
                                if (pending) archiveState.markThumbnailPending(cacheKey, requestData.snapshotGeneration)
                                else archiveState.markThumbnailResolved(cacheKey)
                                displayState = MotionThumbnailDisplayState(
                                    retryGeneration,
                                    if (pending) MotionThumbnailDisplayStatus.PENDING
                                    else MotionThumbnailDisplayStatus.FAILED,
                                )
                            }
                            is AsyncImagePainter.State.Loading, is AsyncImagePainter.State.Empty -> {
                                displayState = MotionThumbnailDisplayState(
                                    retryGeneration, MotionThumbnailDisplayStatus.LOADING,
                                )
                            }
                        }
                    },
                )
            }
            if (currentStatus == MotionThumbnailDisplayStatus.FAILED) {
                MotionThumbnailUnavailable(strings.resource(R.string.motion_archive_thumbnail_unavailable), colors)
            }
        } else {
            MotionThumbnailUnavailable(strings.resource(R.string.motion_archive_unavailable), colors)
        }
    }
}

private fun Throwable.isThumbnailPending(): Boolean =
    this is MotionThumbnailPendingException || cause?.isThumbnailPending() == true

@Composable
private fun MotionThumbnailUnavailable(text: String, colors: UiPalette) {
    Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Videocam, contentDescription = null, tint = colors.muted,
            modifier = Modifier.padding(bottom = 3.dp))
        Text(text, color = colors.muted, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2)
    }
}
