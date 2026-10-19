package com.byd.extend;

import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Maps recorded-frame elapsed time to local wall time from the recorder's shared clock anchor. */
final class MotionFrameTimestamp {
    private static final long MILLIS_PER_MINUTE = 60_000L;
    private final long wallAnchorMs;
    private final long elapsedAnchorMs;

    MotionFrameTimestamp(long wallAnchorMs, long elapsedAnchorMs) {
        this.wallAnchorMs = wallAnchorMs;
        this.elapsedAnchorMs = elapsedAnchorMs;
    }

    static MotionFrameTimestamp capture() {
        long wall = System.currentTimeMillis();
        return new MotionFrameTimestamp(wall, SystemClock.elapsedRealtime());
    }

    long wallTimeMillis(long elapsedMs) {
        return wallAnchorMs + elapsedMs - elapsedAnchorMs;
    }

    String format(long elapsedMs, TimeZone timeZone) {
        long wallTimeMs = wallTimeMillis(elapsedMs);
        TimeZone displayZone = timeZone == null ? TimeZone.getDefault() : timeZone;
        SimpleDateFormat formatter = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US);
        formatter.setTimeZone(displayZone);
        String localTime = formatter.format(new Date(wallTimeMs));
        if (timeZone == null) return localTime;

        int offsetMs = timeZone.getOffset(wallTimeMs);
        if (offsetMs % MILLIS_PER_MINUTE != 0) return localTime;
        int offsetMinutes = (int) (Math.abs((long) offsetMs) / MILLIS_PER_MINUTE);
        int hours = offsetMinutes / 60;
        int minutes = offsetMinutes % 60;
        String sign = offsetMs < 0 ? "-" : "+";
        return localTime + sign + twoDigits(hours)
                + (minutes == 0 ? "" : ":" + twoDigits(minutes));
    }

    private static String twoDigits(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }
}
