package com.byd.extend.remote;

import org.json.JSONObject;

/** Only bounded browser codes, never messages, media URLs or credentials. */
public final class ViewerStopReason {
    private ViewerStopReason() {}

    public static String describe(JSONObject value) {
        String origin = value.optString("origin");
        if (!origin.equals("manual") && !origin.equals("pagehide") && !origin.equals("playback_error")) origin = "unspecified";
        StringBuilder result = new StringBuilder("origin=").append(origin);
        if (!origin.equals("playback_error")) return result.toString();
        for (String key : new String[] {"category", "exception", "hlsType", "hlsDetails"}) {
            String code = value.optString(key);
            if (code.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) result.append(' ').append(key).append('=').append(code);
        }
        number(result, value, "mediaCode", 1, 4);
        number(result, value, "fragment", 0, Integer.MAX_VALUE);
        number(result, value, "httpStatus", 100, 599);
        return result.toString();
    }

    private static void number(StringBuilder result, JSONObject value, String key, long min, long max) {
        Object raw = value.opt(key);
        if (!(raw instanceof Number)) return;
        double number = ((Number) raw).doubleValue();
        if (number >= min && number <= max && number == Math.floor(number))
            result.append(' ').append(key).append('=').append((long) number);
    }
}
