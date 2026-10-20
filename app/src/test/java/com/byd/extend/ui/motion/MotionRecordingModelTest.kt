package com.byd.extend.ui.motion

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionRecordingModelTest {
    @Test fun recordingDisplayNameUsesCalendarYearAndArchiveTimeZone() {
        val instant = Instant.parse("2021-01-01T00:30:04Z").toEpochMilli()
        assertEquals("Motion_20210101_003004", motionRecordingDisplayName(instant, ZoneId.of("UTC")))
        assertEquals("Motion_20201231_193004", motionRecordingDisplayName(instant, ZoneId.of("America/New_York")))
    }

    @Test fun clipEventCountsKeepOverlappingLevelsAndIgnoreIntervalsOutsideTheClip() {
        val intervals = listOf(
            MotionArchiveInterval(100, 400, 1),
            MotionArchiveInterval(200, 200, 2),
            MotionArchiveInterval(800, 500, 1),
            MotionArchiveInterval(900, 0, 2),
            MotionArchiveInterval(-100, 200, 1),
            MotionArchiveInterval(-200, 50, 2),
            MotionArchiveInterval(1_000, 100, 2),
            MotionArchiveInterval(500, -1, 1),
            MotionArchiveInterval(400, 100, 9),
        )
        assertEquals(MotionArchiveEventCounts(3, 2), motionArchiveEventCounts(intervals, 1_000))
        assertEquals(MotionArchiveEventCounts(0, 0), motionArchiveEventCounts(intervals, 0))
        assertEquals(MotionArchiveEventCounts(0, 0), motionArchiveEventCounts(emptyList(), 1_000))
    }

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

    @Test fun firstArchiveDateUsesLatestAvailableEntryInDeviceZone() {
        val zone = ZoneId.of("Europe/Kyiv")
        val records = listOf(
            MotionArchiveDateRecord(Instant.parse("2026-10-18T20:00:00Z").toEpochMilli(), true),
            MotionArchiveDateRecord(Instant.parse("2026-10-19T22:30:00Z").toEpochMilli(), false),
            MotionArchiveDateRecord(Instant.parse("2026-10-19T20:30:00Z").toEpochMilli(), true),
        )

        assertEquals(LocalDate.of(2026, 10, 19), motionArchiveLatestAvailableDate(records, zone))
        assertNull(motionArchiveLatestAvailableDate(records.filterNot { it.available }, zone))
    }

    @Test fun archiveStorageSegmentsUseTheTabletIdAndAllRemovableIds() {
        val removable = setOf("sd-a", "sd-b")
        assertTrue(motionArchiveVolumeMatches(MotionArchiveStorageFilter.ALL, "unknown", "tablet", removable))
        assertTrue(motionArchiveVolumeMatches(MotionArchiveStorageFilter.TABLET, "tablet", "tablet", removable))
        assertFalse(motionArchiveVolumeMatches(MotionArchiveStorageFilter.TABLET, "sd-a", "tablet", removable))
        assertTrue(motionArchiveVolumeMatches(MotionArchiveStorageFilter.SD, "sd-b", "tablet", removable))
        assertFalse(motionArchiveVolumeMatches(MotionArchiveStorageFilter.SD, "unknown", "tablet", removable))
    }

    @Test fun archiveDateFilterUsesHalfOpenBoundaries() {
        val bounds = MotionArchiveBounds(100L, 200L)
        assertFalse(motionArchiveContains(bounds, 99L))
        assertTrue(motionArchiveContains(bounds, 100L))
        assertTrue(motionArchiveContains(bounds, 199L))
        assertFalse(motionArchiveContains(bounds, 200L))
    }

    @Test fun archiveProjectionFiltersAndSortsSnapshotMetadataWithoutLosingSelectionBounds() {
        val bounds = MotionArchiveBounds(100L, 200L)
        val snapshot = listOf(
            MotionArchiveRecordMetadata("sd-later", 180L, "sd-a"),
            MotionArchiveRecordMetadata("sd-earlier", 110L, "sd-a"),
            MotionArchiveRecordMetadata("tablet", 190L, "tablet"),
            MotionArchiveRecordMetadata("outside", 200L, "sd-a"),
        )

        assertEquals(listOf("sd-later", "sd-earlier"), motionArchiveProject(
            snapshot, MotionArchiveStorageFilter.SD, "tablet", setOf("sd-a"), bounds, oldestFirst = false,
        ))
        assertEquals(listOf("sd-earlier", "sd-later"), motionArchiveProject(
            snapshot, MotionArchiveStorageFilter.SD, "tablet", setOf("sd-a"), bounds, oldestFirst = true,
        ))
        assertEquals(listOf("tablet"), motionArchiveProject(
            snapshot, MotionArchiveStorageFilter.TABLET, "tablet", setOf("sd-a"), bounds, oldestFirst = false,
        ))

        val afterSnapshotAdd = snapshot + MotionArchiveRecordMetadata("sd-new", 195L, "sd-a")
        assertEquals(listOf("sd-new", "sd-later", "sd-earlier"), motionArchiveProject(
            afterSnapshotAdd, MotionArchiveStorageFilter.SD, "tablet", setOf("sd-a"), bounds, oldestFirst = false,
        ))
        val afterSnapshotDelete = afterSnapshotAdd.filterNot { it.key == "sd-later" }
        assertEquals(listOf("sd-new", "sd-earlier"), motionArchiveProject(
            afterSnapshotDelete, MotionArchiveStorageFilter.SD, "tablet", setOf("sd-a"), bounds, oldestFirst = false,
        ))
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
