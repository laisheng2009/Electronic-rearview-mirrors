package com.byd.extend;

import org.json.JSONException;
import org.json.JSONObject;

/** Formats the essential lifecycle and foreground-transition records. */
final class ForegroundServiceDiagnostics {
    private ForegroundServiceDiagnostics() { }

    static String event(
            String kind, int pid, String serviceInstance, String thread, long threadId,
            long elapsedMs, int requestStartId, int latestStartId,
            boolean foregroundBefore, boolean foregroundAfter, Object... fields) {
        JSONObject event = new JSONObject();
        try {
            event.put("kind", kind)
                    .put("source", "helper_service")
                    .put("t_ms", elapsedMs)
                    .put("pid", pid)
                    .put("service_instance", serviceInstance)
                    .put("thread", thread)
                    .put("thread_id", threadId)
                    .put("request_start_id", requestStartId)
                    .put("latest_start_id", latestStartId)
                    .put("foreground_before", foregroundBefore)
                    .put("foreground_after", foregroundAfter);
            for (int i = 0; fields != null && i + 1 < fields.length; i += 2) {
                event.put(String.valueOf(fields[i]), fields[i + 1]);
            }
            return event.toString();
        } catch (JSONException ignored) {
            return null;
        }
    }
}
