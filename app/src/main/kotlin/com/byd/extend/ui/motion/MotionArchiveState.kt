package com.byd.extend.ui.motion

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import coil3.ImageLoader
import coil3.memory.MemoryCache
import com.byd.extend.MotionConfig
import com.byd.extend.MotionDetectorClient
import com.byd.extend.MotionMediaLibrary
import java.io.Closeable
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class MotionArchiveState private constructor(context: Context) : Closeable {
    private val appContext = context.applicationContext
    val library = MotionMediaLibrary(appContext, MotionDetectorClient(appContext))
    val imageLoader = ImageLoader.Builder(appContext)
        .components {
            add(MotionThumbnailKeyer)
            add(MotionThumbnailFetcherFactory(library))
        }
        .memoryCache { MemoryCache.Builder().maxSizeBytes(32L * 1024 * 1024).build() }
        .diskCache(null)
        .build()
    private val refreshMutex = Mutex()
    private val pendingThumbnailRefreshes = mutableStateMapOf<String, Long>()
    private var closed = false

    var entries by mutableStateOf(emptyList<MotionMediaLibrary.Entry>())
        private set
    var stats by mutableStateOf<MotionMediaLibrary.Stats?>(null)
        private set
    var hasSnapshot by mutableStateOf(false)
        private set
    var thumbnailRefreshGeneration by mutableStateOf(0L)
        private set
    var loading by mutableStateOf(true)
        private set
    var loadFailed by mutableStateOf(false)
        private set
    var archiveOpen by mutableStateOf(false)
    var playerEntry by mutableStateOf<MotionMediaLibrary.Entry?>(null)
    var month by mutableStateOf(YearMonth.now())
    var selectedDate by mutableStateOf<LocalDate?>(null)
    var rangeFrom by mutableStateOf<LocalDate?>(null)
    var rangeTo by mutableStateOf<LocalDate?>(null)
    var storageFilter by mutableStateOf(MotionArchiveStorageFilter.ALL)
    var sort by mutableStateOf(0)
    var tabletVolumeId by mutableStateOf<String?>(null)
        private set
    var removableVolumeIds by mutableStateOf(emptySet<String>())
        private set
    val gridPositions = mutableMapOf<MotionArchiveGridKey, MotionArchiveGridPosition>()

    fun canRequestThumbnail(key: String): Boolean =
        pendingThumbnailRefreshes[key]?.let { thumbnailRefreshGeneration > it } ?: true

    fun pendingThumbnailGeneration(key: String): Long? = pendingThumbnailRefreshes[key]

    fun markThumbnailPending(key: String, requestSnapshotGeneration: Long) {
        pendingThumbnailRefreshes[key] = requestSnapshotGeneration
    }

    fun markThumbnailResolved(key: String) {
        pendingThumbnailRefreshes.remove(key)
    }

    suspend fun refreshSnapshot(backgroundOnly: Boolean = false): Boolean = refreshMutex.withLock {
        if (backgroundOnly && !withContext(Dispatchers.IO) { library.isWorkerAvailable }) return@withLock false
        refreshSnapshotLocked()
    }

    suspend fun deleteSelected(selected: List<MotionMediaLibrary.Entry>): MotionMediaLibrary.DeleteResult =
        refreshMutex.withLock {
            try {
                val result = withContext(Dispatchers.IO) { library.deleteSelected(selected) }
                refreshSnapshotLocked()
                result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                loadFailed = true
                throw error
            }
        }

    private suspend fun refreshSnapshotLocked(): Boolean {
        if (closed) return false
        if (!hasSnapshot) loading = true
        val previousEntries = entries
        val hadSnapshot = hasSnapshot
        return try {
            val snapshot = withContext(Dispatchers.IO) {
                library.refresh()
                library.loadSnapshot(MotionConfig.read(appContext))
            }
            if (hadSnapshot) evictRemovedThumbnails(previousEntries, snapshot)
            Snapshot.withMutableSnapshot {
                entries = snapshot.entries
                stats = snapshot.stats
                snapshot.stats?.let { latestStats ->
                    tabletVolumeId = latestStats.volumes.firstOrNull { it.primary }?.id ?: tabletVolumeId
                    removableVolumeIds = removableVolumeIds + latestStats.volumes.filter { it.removable }.map { it.id }
                }
                if (!hasSnapshot) {
                    val latestDate = motionArchiveLatestAvailableDate(
                        snapshot.entries.map { MotionArchiveDateRecord(it.recordedAtMillis, it.available) },
                        ZoneId.systemDefault(),
                    )
                    selectedDate = latestDate
                    month = latestDate?.let(YearMonth::from) ?: YearMonth.now()
                }
                hasSnapshot = true
                thumbnailRefreshGeneration++
                loading = false
                loadFailed = false
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loading = false
            loadFailed = true
            false
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        imageLoader.shutdown()
        library.close()
    }

    private fun evictRemovedThumbnails(
        previousEntries: List<MotionMediaLibrary.Entry>,
        snapshot: MotionMediaLibrary.Snapshot,
    ) {
        val mountedVolumes = snapshot.stats?.volumes?.asSequence()?.filter { it.mounted }?.map { it.id }
            ?.toHashSet().orEmpty()
        if (mountedVolumes.isEmpty()) return
        val retained = snapshot.entries.asSequence().map { it.thumbnailIdentity() }.toHashSet()
        previousEntries.asSequence()
            .filter { it.volumeId in mountedVolumes && it.thumbnailIdentity() !in retained }
            .forEach {
                val key = it.motionThumbnailCacheKey()
                imageLoader.memoryCache?.remove(MemoryCache.Key(key))
                pendingThumbnailRefreshes.remove(key)
            }
    }

    companion object {
        private var instance: MotionArchiveState? = null

        @Synchronized
        fun get(context: Context): MotionArchiveState =
            instance?.takeUnless { it.closed } ?: MotionArchiveState(context).also { instance = it }
    }
}

private data class MotionArchiveEntryIdentity(
    val volumeId: String,
    val contentUri: String,
    val mediaStoreId: Long,
    val segmentId: String,
)

private fun MotionMediaLibrary.Entry.thumbnailIdentity() = MotionArchiveEntryIdentity(
    volumeId, contentUri.toString(), mediaStoreId, segmentId,
)
