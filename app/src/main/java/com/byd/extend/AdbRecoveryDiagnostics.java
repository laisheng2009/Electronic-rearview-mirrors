package com.byd.extend;

import com.byd.extend.remote.CameraViewingDiagnostics;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/** App-owned recovery records; never include key material or transport payloads. */
final class AdbRecoveryDiagnostics {
    private static final Pattern PEM = Pattern.compile(
            "(?s)-----BEGIN [^-]+-----.*?-----END [^-]+-----");
    private static final Pattern KEY_MATERIAL = Pattern.compile(
            "(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{128,}={0,2}");
    private static final Pattern PERSONAL = Pattern.compile(
            "(?i)(\\b(?:ssid|bssid|serial|public_key|private_key|certificate)\\s*[:=]\\s*)"
                    + "(?:\"[^\"]*\"|'[^']*'|[^\\s,;}]+)");
    private static final List<String> TRANSPORT_FIELDS = Arrays.asList(
            "stage", "operation", "reason", "error", "error_class", "category", "endpoint",
            "token_bytes", "payload_bytes", "public_key_sent", "mode",
            "automatic_attempt_consumed", "exit_code");

    private AdbRecoveryDiagnostics() { }

    static String safeText(String text) {
        String safe = CameraViewingDiagnostics.safeText(text);
        safe = PEM.matcher(safe).replaceAll("[key/certificate redacted]");
        safe = KEY_MATERIAL.matcher(safe).replaceAll("[key material redacted]");
        return PERSONAL.matcher(safe).replaceAll("$1[redacted]");
    }

    static void failure(BiConsumer<String, Object[]> sink, String event,
            String operation, Throwable error, Object... context) {
        List<Object> fields = new ArrayList<>(Arrays.asList(context));
        fields.addAll(Arrays.asList("operation", operation,
                "error_class", error.getClass().getName(),
                "error", safeText(CameraViewingDiagnostics.describe(error))));
        sink.accept(event, fields.toArray());
    }

    static Object[] transportFields(String event, Object[] input) {
        List<Object> fields = new ArrayList<>(Arrays.asList("event", event));
        for (int i = 0; input != null && i + 1 < input.length; i += 2) {
            String key = String.valueOf(input[i]);
            if (!TRANSPORT_FIELDS.contains(key)) continue;
            Object value = input[i + 1];
            fields.add(key);
            fields.add(value instanceof String ? safeText((String) value) : value);
        }
        return fields.toArray();
    }
}
