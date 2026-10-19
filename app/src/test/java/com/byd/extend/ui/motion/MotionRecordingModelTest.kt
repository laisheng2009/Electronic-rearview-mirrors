package com.byd.extend.ui.motion

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
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

    @Test fun archiveBoundsUseInclusiveLocalDateRangesAndHalfOpenInstants() {
        val zone = ZoneId.of("America/New_York")
        val day = LocalDate.of(2026, 10, 18)
        val dayBounds = motionArchiveBounds(YearMonth.from(day), day, null, null, zone)!!
        assertEquals(day.atStartOfDay(zone).toInstant().toEpochMilli(), dayBounds.startMillis)
        assertEquals(day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), dayBounds.endMillis)

        val month = motionArchiveBounds(YearMonth.of(2026, 2), null, null, null, zone)!!
        assertEquals(LocalDate.of(2026, 2, 1).atStartOfDay(zone).toInstant().toEpochMilli(), month.startMillis)
        assertEquals(LocalDate.of(2026, 3, 1).atStartOfDay(zone).toInstant().toEpochMilli(), month.endMillis)

        val sameDay = LocalDate.of(2026, 3, 8)
        val sameDayBounds = motionArchiveBounds(YearMonth.from(sameDay), day, sameDay, sameDay, zone)!!
        assertEquals(sameDay.atStartOfDay(zone).toInstant().toEpochMilli(), sameDayBounds.startMillis)
        assertEquals(sameDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), sameDayBounds.endMillis)
        assertEquals(23 * 60 * 60 * 1000L, sameDayBounds.endMillis - sameDayBounds.startMillis)

        val range = motionArchiveBounds(YearMonth.from(sameDay), day,
            sameDay.minusDays(1), sameDay.plusDays(1), zone)!!
        assertEquals(sameDay.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), range.startMillis)
        assertEquals(sameDay.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli(), range.endMillis)
        assertEquals(71 * 60 * 60 * 1000L, range.endMillis - range.startMillis)
    }

    @Test fun incompleteOrReversedArchiveRangesDoNotFallBackToMonthOrDay() {
        val month = YearMonth.of(2026, 2)
        val day = LocalDate.of(2026, 2, 8)
        val zone = ZoneId.of("UTC")
        assertNull(motionArchiveBounds(month, day, day, null, zone))
        assertNull(motionArchiveBounds(month, day, day.plusDays(1), day, zone))
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
