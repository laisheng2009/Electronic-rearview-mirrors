package com.byd.extend.ui.motion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.byd.extend.MotionMediaLibrary

internal enum class MotionArchiveStorageFilter { ALL, TABLET, SD }

internal data class MotionArchiveDateRecord(val recordedAtMillis: Long, val available: Boolean)

internal data class MotionArchiveGridKey(
    val month: YearMonth,
    val selectedDate: LocalDate?,
    val rangeFrom: LocalDate?,
    val rangeTo: LocalDate?,
    val storageFilter: MotionArchiveStorageFilter,
    val sort: Int,
)

internal data class MotionArchiveGridPosition(val index: Int, val offset: Int)

internal data class MotionArchiveBounds(val startMillis: Long, val endMillis: Long)

internal data class MotionArchiveRecordMetadata(
    val key: String,
    val recordedAtMillis: Long,
    val volumeId: String,
)

internal fun motionRecordingDisplayName(recordedAtMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    "Motion_" + DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)
        .withZone(zone).format(Instant.ofEpochMilli(recordedAtMillis))

internal data class MotionArchiveInterval(val positionMs: Long, val durationMs: Long, val level: Int)

internal data class MotionArchiveEventCounts(val yellow: Int, val red: Int)

internal fun motionArchiveEventCounts(
    intervals: Iterable<MotionArchiveInterval>,
    durationMs: Long,
): MotionArchiveEventCounts {
    var yellow = 0
    var red = 0
    if (durationMs > 0) intervals.forEach { interval ->
        // Zero-length markers are still visible point events on the player's timeline.
        if (interval.durationMs < 0 || interval.positionMs >= durationMs ||
            interval.positionMs < 0 && interval.positionMs < -interval.durationMs) return@forEach
        when (interval.level) {
            1 -> yellow++
            2 -> red++
        }
    }
    return MotionArchiveEventCounts(yellow, red)
}

internal fun motionArchiveProject(
    records: Iterable<MotionArchiveRecordMetadata>,
    filter: MotionArchiveStorageFilter,
    tabletVolumeId: String?,
    removableVolumeIds: Set<String>,
    bounds: MotionArchiveBounds?,
    oldestFirst: Boolean,
): List<String> {
    if (bounds == null) return emptyList()
    val matched = records.filter { record ->
        motionArchiveVolumeMatches(filter, record.volumeId, tabletVolumeId, removableVolumeIds) &&
            motionArchiveContains(bounds, record.recordedAtMillis)
    }
    val ordered = if (oldestFirst) matched.sortedWith(
        compareBy<MotionArchiveRecordMetadata> { it.recordedAtMillis }.thenBy { it.key },
    ) else matched.sortedWith(
        compareByDescending<MotionArchiveRecordMetadata> { it.recordedAtMillis }.thenBy { it.key },
    )
    return ordered.map { it.key }
}

internal data class MotionStoragePermissionNeeds(val read: Boolean, val write: Boolean)

internal fun motionStoragePermissionNeeds(apiLevel: Int) = MotionStoragePermissionNeeds(
    read = apiLevel in 23..32,
    write = apiLevel in 23..28,
)

internal fun motionStoragePermissionsGranted(
    needs: MotionStoragePermissionNeeds,
    readGranted: Boolean,
    writeGranted: Boolean,
) = (!needs.read || readGranted) && (!needs.write || writeGranted)

/** Java bridge for the same permission range used by the recording UI before enabling capture. */
@JvmName("motionRecordingStoragePermissionGranted")
fun motionRecordingStoragePermissionGranted(context: Context): Boolean {
    val needs = motionStoragePermissionNeeds(Build.VERSION.SDK_INT)
    val read = !needs.read || ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    val write = !needs.write || ContextCompat.checkSelfPermission(
        context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    return motionStoragePermissionsGranted(needs, read, write)
}

internal fun motionArchiveDateRange(from: LocalDate, to: LocalDate, zone: ZoneId): MotionArchiveBounds? {
    if (from.isAfter(to)) return null
    val start = from.atStartOfDay(zone).toInstant()
    val end = to.plusDays(1).atStartOfDay(zone).toInstant()
    return if (start.isBefore(end)) MotionArchiveBounds(start.toEpochMilli(), end.toEpochMilli()) else null
}

internal fun motionArchiveLatestAvailableDate(records: Iterable<MotionArchiveDateRecord>, zone: ZoneId): LocalDate? =
    records.asSequence().filter { it.available }.maxByOrNull { it.recordedAtMillis }
        ?.let { Instant.ofEpochMilli(it.recordedAtMillis).atZone(zone).toLocalDate() }

internal fun motionArchiveVolumeMatches(
    filter: MotionArchiveStorageFilter,
    volumeId: String,
    tabletVolumeId: String?,
    removableVolumeIds: Set<String>,
): Boolean = when (filter) {
    MotionArchiveStorageFilter.ALL -> true
    MotionArchiveStorageFilter.TABLET -> volumeId == tabletVolumeId
    MotionArchiveStorageFilter.SD -> volumeId in removableVolumeIds
}

internal fun motionArchiveContains(bounds: MotionArchiveBounds, recordedAtMillis: Long) =
    recordedAtMillis >= bounds.startMillis && recordedAtMillis < bounds.endMillis

internal data class MotionArchiveDay(
    val date: LocalDate,
    val entryCount: Int,
    val yellowEvents: Int,
    val redEvents: Int,
)

internal fun motionArchiveDays(entries: Iterable<MotionMediaLibrary.Entry>, zone: ZoneId): List<MotionArchiveDay> {
    data class MutableDay(val date: LocalDate, val entries: MutableList<MotionMediaLibrary.Entry> = mutableListOf())
    val days = sortedMapOf<LocalDate, MutableDay>()
    entries.forEach { entry ->
        val date = Instant.ofEpochMilli(entry.recordedAtMillis).atZone(zone).toLocalDate()
        days.getOrPut(date) { MutableDay(date) }.entries += entry
    }
    return days.values.map { day ->
        val events = HashSet<String>()
        var yellow = 0
        var red = 0
        day.entries.forEach { entry -> entry.markers.forEach { marker ->
            val key = if (marker.eventId.isEmpty())
                "${entry.contentUri}|${marker.elapsedRealtimeMs}|${marker.level}" else marker.eventId
            if (events.add(key)) when (marker.level) {
                1 -> yellow++
                2 -> red++
            }
        } }
        MotionArchiveDay(day.date, day.entries.size, yellow, red)
    }
}

internal fun motionArchiveBounds(
    month: YearMonth,
    selectedDate: LocalDate?,
    from: LocalDate?,
    to: LocalDate?,
    zone: ZoneId,
): MotionArchiveBounds? = when {
    (from == null) != (to == null) -> null
    from != null && to != null -> motionArchiveDateRange(from, to, zone)
    selectedDate != null -> MotionArchiveBounds(
        selectedDate.atStartOfDay(zone).toInstant().toEpochMilli(),
        selectedDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
    )
    else -> MotionArchiveBounds(
        month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
    )
}

internal fun motionCalendarWeeks(month: YearMonth, firstDay: DayOfWeek): List<List<LocalDate?>> {
    val offset = (month.atDay(1).dayOfWeek.value - firstDay.value + 7) % 7
    val dates = List<LocalDate?>(offset) { null } + (1..month.lengthOfMonth()).map(month::atDay)
    return (dates + List((7 - dates.size % 7) % 7) { null }).chunked(7)
}

internal fun motionCameraOrder(encoded: String): List<Int> {
    val fields = encoded.split(',')
    val order = fields.mapNotNull { it.toIntOrNull() }
    return if (fields.size == 4 && order.size == 4 && order.toSet() == setOf(0, 1, 2, 3)) order
    else listOf(0, 1, 2, 3)
}

internal fun swapMotionCameras(order: List<Int>, from: Int, to: Int): List<Int> =
    if (from !in order.indices || to !in order.indices) order else order.toMutableList().apply {
        this[from] = order[to]
        this[to] = order[from]
    }

internal fun motionTileAt(x: Float, y: Float): Int? =
    if (x >= 0f && x < 1f && y >= 0f && y < 1f)
        (if (y < .5f) 0 else 2) + (if (x < .5f) 0 else 1) else null
