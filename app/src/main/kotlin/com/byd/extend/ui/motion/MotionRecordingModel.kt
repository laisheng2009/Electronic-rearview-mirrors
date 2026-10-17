package com.byd.extend.ui.motion

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

internal data class MotionArchiveBounds(val startMillis: Long, val endMillis: Long)

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

internal fun motionArchiveTimeRange(from: LocalDateTime, to: LocalDateTime, zone: ZoneId): MotionArchiveBounds? {
    val start = from.atZone(zone).toInstant()
    val end = to.atZone(zone).toInstant()
    return if (start.isBefore(end)) MotionArchiveBounds(start.toEpochMilli(), end.toEpochMilli()) else null
}

internal fun motionArchiveBounds(
    month: YearMonth,
    selectedDate: LocalDate?,
    from: LocalDateTime?,
    to: LocalDateTime?,
    zone: ZoneId,
): MotionArchiveBounds? = when {
    (from == null) != (to == null) -> null
    from != null && to != null -> motionArchiveTimeRange(from, to, zone)
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
