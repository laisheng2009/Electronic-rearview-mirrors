package com.byd.extend.ui.motion

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionRecordingModelTest {
    @Test fun calendarUsesFullWeeksForShortAndSixWeekMonths() {
        val shortMonth = motionCalendarWeeks(YearMonth.of(2021, 2), DayOfWeek.MONDAY)
        assertEquals(4, shortMonth.size)
        assertTrue(shortMonth.all { it.size == 7 })
        assertEquals((1..28).map { LocalDate.of(2021, 2, it) }, shortMonth.flatten().filterNotNull())

        val sixWeekMonth = motionCalendarWeeks(YearMonth.of(2026, 8), DayOfWeek.MONDAY)
        assertEquals(6, sixWeekMonth.size)
        assertTrue(sixWeekMonth.all { it.size == 7 })
    }

    @Test fun archiveBoundsUseHalfOpenDayMonthAndTimeRanges() {
        val zone = ZoneOffset.UTC
        val day = LocalDate.of(2026, 10, 18)
        val dayBounds = motionArchiveBounds(YearMonth.from(day), day, null, null, zone)!!
        assertEquals(day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), dayBounds.startMillis)
        assertEquals(day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), dayBounds.endMillis)

        val month = motionArchiveBounds(YearMonth.of(2026, 2), null, null, null, zone)!!
        assertEquals(LocalDate.of(2026, 2, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), month.startMillis)
        assertEquals(LocalDate.of(2026, 3, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), month.endMillis)

        val range = motionArchiveBounds(YearMonth.of(2026, 2), day,
            LocalDateTime.of(2026, 2, 3, 10, 15), LocalDateTime.of(2026, 2, 4, 11, 30), zone)!!
        assertEquals(LocalDateTime.of(2026, 2, 3, 10, 15).toInstant(ZoneOffset.UTC).toEpochMilli(), range.startMillis)
        assertEquals(LocalDateTime.of(2026, 2, 4, 11, 30).toInstant(ZoneOffset.UTC).toEpochMilli(), range.endMillis)
    }

    @Test fun incompleteOrReversedArchiveRangesDoNotFallBackToMonthOrDay() {
        val month = YearMonth.of(2026, 2)
        val day = LocalDate.of(2026, 2, 8)
        assertNull(motionArchiveBounds(month, day, LocalDateTime.of(2026, 2, 8, 10, 0), null, ZoneOffset.UTC))
        assertNull(motionArchiveBounds(month, day,
            LocalDateTime.of(2026, 2, 8, 12, 0), LocalDateTime.of(2026, 2, 8, 11, 0), ZoneOffset.UTC))
        assertNull(motionArchiveBounds(month, day,
            LocalDateTime.of(2026, 2, 8, 12, 0), LocalDateTime.of(2026, 2, 8, 12, 0), ZoneOffset.UTC))
    }

    @Test fun recordingStoragePermissionsMatchApiRequirements() {
        assertEquals(MotionStoragePermissionNeeds(false, false), motionStoragePermissionNeeds(22))
        assertEquals(MotionStoragePermissionNeeds(true, true), motionStoragePermissionNeeds(28))
        assertEquals(MotionStoragePermissionNeeds(true, false), motionStoragePermissionNeeds(29))
        assertEquals(MotionStoragePermissionNeeds(true, false), motionStoragePermissionNeeds(32))
        assertEquals(MotionStoragePermissionNeeds(false, false), motionStoragePermissionNeeds(33))

        val legacy = motionStoragePermissionNeeds(28)
        assertFalse(motionStoragePermissionsGranted(legacy, readGranted = true, writeGranted = false))
        assertTrue(motionStoragePermissionsGranted(legacy, readGranted = true, writeGranted = true))
        assertTrue(motionStoragePermissionsGranted(motionStoragePermissionNeeds(33), false, false))
    }
}
