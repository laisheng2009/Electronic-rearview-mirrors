package com.byd.extend;

import org.junit.Test;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;

public class MotionFrameTimestampTest {
    @Test public void frameTimeUsesTheSharedElapsedToWallAnchorAcrossDst() throws Exception {
        MotionFrameTimestamp timestamp = new MotionFrameTimestamp(
                utcMillis("2026-10-25 00:59:59"), 10_000L);
        TimeZone kyiv = TimeZone.getTimeZone("Europe/Kyiv");

        assertEquals("25.10.2026 03:59:59+03", timestamp.format(10_000L, kyiv));
        assertEquals("25.10.2026 03:00:00+02", timestamp.format(11_000L, kyiv));
        assertEquals(utcMillis("2026-10-25 01:00:00"), timestamp.wallTimeMillis(11_000L));
    }

    @Test public void formatsNegativeAndHalfHourOffsetsWithoutRounding() throws Exception {
        MotionFrameTimestamp winter = new MotionFrameTimestamp(
                utcMillis("2026-01-15 12:00:00"), 0L);
        MotionFrameTimestamp summer = new MotionFrameTimestamp(
                utcMillis("2026-07-01 12:00:00"), 0L);

        assertEquals("15.01.2026 07:00:00-05",
                winter.format(0L, TimeZone.getTimeZone("America/New_York")));
        assertEquals("01.07.2026 09:30:00-02:30",
                summer.format(0L, TimeZone.getTimeZone("America/St_Johns")));
    }

    @Test public void rendersTheOwnerSpecifiedDateTimeAndSuffixFormat() throws Exception {
        MotionFrameTimestamp timestamp = new MotionFrameTimestamp(
                utcMillis("2026-10-19 16:23:04"), 0L);

        assertEquals("19.10.2026 19:23:04+03",
                timestamp.format(0L, TimeZone.getTimeZone("Europe/Kyiv")));
    }

    @Test public void unavailableZoneOmitsOnlyTheOffsetSuffix() throws Exception {
        MotionFrameTimestamp timestamp = new MotionFrameTimestamp(
                utcMillis("2026-10-19 16:23:04"), 1_000L);
        SimpleDateFormat local = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US);
        local.setTimeZone(TimeZone.getDefault());

        String value = timestamp.format(1_000L, null);
        assertEquals(local.format(utcMillis("2026-10-19 16:23:04")), value);
        assertEquals(19, value.length());
    }

    private static long utcMillis(String value) throws ParseException {
        SimpleDateFormat utc = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        return utc.parse(value).getTime();
    }
}
