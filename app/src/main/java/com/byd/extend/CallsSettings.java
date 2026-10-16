package com.byd.extend;

import android.content.SharedPreferences;

/** Independent SOS and HELP destinations used by the Calls integration. */
public final class CallsSettings {
    public enum Profile {
        Sos("calls_sos"),
        Help("calls_help");

        private final String key;

        Profile(String key) {
            this.key = key;
        }
    }

    public static final class Destination {
        public final boolean enabled;
        public final String name;
        public final String number;

        private Destination(boolean enabled, String name, String number) {
            this.enabled = enabled;
            this.name = name;
            this.number = number;
        }
    }

    private CallsSettings() { }

    public static Destination read(SharedPreferences preferences, Profile profile) {
        String prefix = profile.key;
        String number = normalizeNumber(preferences.getString(prefix + "_number", ""));
        return new Destination(
                preferences.getBoolean(prefix + "_enabled", false),
                preferences.getString(prefix + "_name", ""),
                number == null ? "" : number);
    }

    public static void setEnabled(SharedPreferences preferences, Profile profile, boolean enabled) {
        preferences.edit().putBoolean(profile.key + "_enabled", enabled).apply();
    }

    /** Invalid input leaves the previously saved destination intact. */
    public static boolean saveDestination(
            SharedPreferences preferences, Profile profile, String name, String number) {
        String normalized = normalizeNumber(number);
        if (normalized == null) return false;
        String prefix = profile.key;
        preferences.edit()
                .putString(prefix + "_name", name == null ? "" : name.trim())
                .putString(prefix + "_number", normalized)
                .apply();
        return true;
    }

    /** Keeps the Preview phone-number contract: only formatting whitespace is removed. */
    public static String normalizeNumber(String input) {
        if (input == null) return null;
        String number = input.replaceAll("[\\s\\p{Zs}()\\-]", "");
        return number.matches("\\+?[0-9]{6,15}") ? number : null;
    }
}
