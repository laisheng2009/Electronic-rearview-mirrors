package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RuntimeSettingsReplayTest {
    @Test
    public void storedConfigurationReplaysExactlyWithoutWritingPreferences() {
        TestSharedPreferences settings = new TestSharedPreferences();
        settings.putBoolean("guard_enabled", true)
                .putFloat("outward_deg", -37.5f)
                .putFloat("center_deg", 181.25f)
                .putInt("correction_delay_ms", -400)
                .putInt("max_speed_kph", 173)
                .putBoolean("music_visualizer_enabled", true);
        int transactionsBefore = settings.transactions;
        RecordingSink sink = new RecordingSink();

        CameraHelperService.RuntimeSettingsSnapshot
                .read(settings, true)
                .replay(sink);

        assertEquals(transactionsBefore, settings.transactions);
        assertTrue(settings.getBoolean("guard_enabled", false));
        assertEquals(-37.5f, sink.outward, 0f);
        assertEquals(181.25f, sink.center, 0f);
        assertEquals(-400, sink.delayMs);
        assertEquals(173, sink.maxSpeedKph);
        assertTrue(sink.guardEnabled);
        assertTrue(sink.musicEnabled);
        assertTrue(sink.parkingEnabled);
        assertEquals(3, sink.calls);
    }

    private static final class RecordingSink
            implements CameraHelperService.RuntimeSettingsSink {
        boolean guardEnabled;
        float outward;
        float center;
        int delayMs;
        int maxSpeedKph;
        boolean musicEnabled;
        boolean parkingEnabled;
        int calls;

        @Override
        public void applyGuard(boolean enabled, float outward, float center,
                int delayMs, int maxSpeedKph) {
            guardEnabled = enabled;
            this.outward = outward;
            this.center = center;
            this.delayMs = delayMs;
            this.maxSpeedKph = maxSpeedKph;
            calls++;
        }

        @Override
        public void applyMusic(boolean enabled) {
            musicEnabled = enabled;
            calls++;
        }

        @Override
        public void applyParkingRadar(boolean enabled) {
            parkingEnabled = enabled;
            calls++;
        }
    }
}
