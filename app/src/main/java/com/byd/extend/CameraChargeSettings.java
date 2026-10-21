package com.byd.extend;

import android.content.Context;
import android.content.SharedPreferences;

/** Persisted shared camera charge thresholds. Defaults are read without rewriting user settings. */
public final class CameraChargeSettings {
    public static final String PREFS = "camera_charge";
    public static final String KEY_SOC_THRESHOLD = "soc_threshold_percent";
    public static final String KEY_VOLTAGE_THRESHOLD_TENTHS = "voltage_threshold_tenths";
    public static final String KEY_REMOTE_VIEWING_ENABLED = "remote_viewing_enabled";

    public static final int DEFAULT_SOC_THRESHOLD_PERCENT = 20;
    public static final int MIN_SOC_THRESHOLD_PERCENT = 10;
    public static final int MAX_SOC_THRESHOLD_PERCENT = 90;
    public static final float DEFAULT_VOLTAGE_THRESHOLD_VOLTS = 12.4f;
    public static final int MIN_VOLTAGE_THRESHOLD_TENTHS = 120;
    public static final int MAX_VOLTAGE_THRESHOLD_TENTHS = 130;

    private CameraChargeSettings() {}

    public static int getSocThresholdPercent(Context context) {
        return preferences(context).getInt(KEY_SOC_THRESHOLD, DEFAULT_SOC_THRESHOLD_PERCENT);
    }

    public static void setSocThresholdPercent(Context context, int value) {
        if (!isValidSocThreshold(value)) throw new IllegalArgumentException("SOC threshold must be 10–90% in 1% steps");
        SharedPreferences preferences = preferences(context);
        if (preferences.getInt(KEY_SOC_THRESHOLD, DEFAULT_SOC_THRESHOLD_PERCENT) == value) return;
        preferences.edit().putInt(KEY_SOC_THRESHOLD, value).apply();
        notifyRuntimeIfEnabled(context);
    }

    public static float getVoltageThresholdVolts(Context context) {
        return getVoltageThresholdTenths(context) / 10.0f;
    }

    public static void setVoltageThresholdVolts(Context context, float value) {
        int tenths = voltageTenths(value);
        SharedPreferences preferences = preferences(context);
        if (preferences.getInt(KEY_VOLTAGE_THRESHOLD_TENTHS,
                Math.round(DEFAULT_VOLTAGE_THRESHOLD_VOLTS * 10.0f)) == tenths) return;
        preferences.edit().putInt(KEY_VOLTAGE_THRESHOLD_TENTHS, tenths).apply();
        notifyRuntimeIfEnabled(context);
    }

    public static boolean isRemoteViewingEnabled(Context context) {
        return preferences(context).getBoolean(KEY_REMOTE_VIEWING_ENABLED, false);
    }

    static int getVoltageThresholdTenths(Context context) {
        return preferences(context).getInt(KEY_VOLTAGE_THRESHOLD_TENTHS,
                Math.round(DEFAULT_VOLTAGE_THRESHOLD_VOLTS * 10.0f));
    }

    static boolean isValidSocThreshold(int value) {
        return value >= MIN_SOC_THRESHOLD_PERCENT && value <= MAX_SOC_THRESHOLD_PERCENT;
    }

    static int voltageTenths(float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("12V threshold must be finite");
        int tenths = Math.round(value * 10.0f);
        if (tenths < MIN_VOLTAGE_THRESHOLD_TENTHS || tenths > MAX_VOLTAGE_THRESHOLD_TENTHS
                || Math.abs(value * 10.0f - tenths) > 0.0001f)
            throw new IllegalArgumentException("12V threshold must be 12.0–13.0V in 0.1V steps");
        return tenths;
    }

    private static SharedPreferences preferences(Context context) {
        if (context == null) throw new IllegalArgumentException("context required");
        Context application = context.getApplicationContext();
        return (application == null ? context : application)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void notifyRuntimeIfEnabled(Context context) {
        if (MotionConfig.read(context).anyEnabled()) MotionRuntime.settingsChanged(context);
    }
}
