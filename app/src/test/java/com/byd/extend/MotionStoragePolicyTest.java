package com.byd.extend;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class MotionStoragePolicyTest {
    @Test public void twoGbArchiveLimitStillCapsGrowthWithOneGbKeptFree() {
        MotionStoragePolicy.Parts parts = MotionStoragePolicy.calculate(
                6_200_000_000L, 4_500_000_000L, 0, 2 * MotionStoragePolicy.GB, true,
                MotionStoragePolicy.GB, 0, null);
        assertEquals(2 * MotionStoragePolicy.GB, parts.allowance);
        assertEquals(2_500_000_000L, parts.unallocated);
        assertEquals(6_200_000_000L, parts.total());
    }

    @Test public void splitsWritableSpaceByUnmetOwnAndRecorderLimits() {
        MotionStoragePolicy.Parts parts = MotionStoragePolicy.calculate(
                100 * MotionStoragePolicy.GB, 20 * MotionStoragePolicy.GB,
                4 * MotionStoragePolicy.GB, 20 * MotionStoragePolicy.GB, true,
                5 * MotionStoragePolicy.GB, 4 * MotionStoragePolicy.GB,
                20 * MotionStoragePolicy.GB);

        assertEquals(72 * MotionStoragePolicy.GB, parts.other);
        assertEquals(4 * MotionStoragePolicy.GB, parts.own);
        assertEquals(4 * MotionStoragePolicy.GB, parts.oem);
        assertEquals(7_500_000_000L, parts.allowance);
        assertEquals(7_500_000_000L, parts.oemAllowance);
        assertEquals(5 * MotionStoragePolicy.GB, parts.unallocated);
        assertEquals(100 * MotionStoragePolicy.GB, parts.total());
    }

    @Test public void keepFreeFloorWinsAndDisabledOemLimitDoesNotReserveGrowth() {
        MotionStoragePolicy.Parts parts = MotionStoragePolicy.calculate(
                64 * MotionStoragePolicy.GB, 6 * MotionStoragePolicy.GB,
                2 * MotionStoragePolicy.GB, 10 * MotionStoragePolicy.GB, true,
                8 * MotionStoragePolicy.GB, 5 * MotionStoragePolicy.GB, null);

        assertEquals(0, parts.allowance);
        assertEquals(0, parts.oemAllowance);
        assertEquals(6 * MotionStoragePolicy.GB, parts.unallocated);
        assertEquals(51 * MotionStoragePolicy.GB, parts.other);
        assertEquals(64 * MotionStoragePolicy.GB, parts.total());
    }

    @Test public void inactiveVolumeGetsNoOwnAllowanceAndClampsInvalidAccounting() {
        MotionStoragePolicy.Parts parts = MotionStoragePolicy.calculate(
                10, 100, 100, 100, false, -1L, 100, null);

        assertEquals(10, parts.free());
        assertEquals(0, parts.allowance);
        assertEquals(0, parts.own);
        assertEquals(0, parts.oem);
        assertEquals(0, parts.other);
        assertEquals(10, parts.total());
    }
}
