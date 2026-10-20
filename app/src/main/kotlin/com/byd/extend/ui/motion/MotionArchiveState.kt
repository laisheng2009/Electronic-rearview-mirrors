package com.byd.extend.ui.motion

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
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

internal class MotionArchiveState(context: Context) : Closeable {
    private val appContext = context.applicationContext
    val library = MotionMediaLibrary(appContext, MotionDetectorClient(appContext))
    val thumbnailLoader = MotionThumbnailLoader(appContext)
    private val refreshMutex = Mutex()
    private var closed = false

    var entries by mutableStateOf(emptyList<MotionMediaLibrary.Entry>())
        private set
    var stats by mutableStateOf<MotionMediaLibrary.Stats?>(null)
        private set
    var hasSnapshot by mutableStateOf(false)
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
    val gridPositions = mutableStateMapOf<MotionArchiveGridKey, MotionArchiveGridPosition>()

    suspend fun refreshSnapshot(): Boolean = refreshMutex.withLock { refreshSnapshotLocked() }

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
        return try {
            val snapshot = withContext(Dispatchers.IO) {
                library.refresh()
                library.loadSnapshot(MotionConfig.read(appContext)).also {
                    thumbnailLoader.reconcileSnapshot(it.entries)
                }
            }
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
        thumbnailLoader.close()
        library.close()
    }
}
