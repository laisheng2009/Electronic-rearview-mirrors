package com.byd.extend;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ForegroundServiceDiagnosticsTest {
    @Test public void persistsRepeatedForegroundTransitionsWhenExtendedLoggingIsOff()
            throws Exception {
        boolean extendedBefore = DiagnosticLogPolicy.extended();
        DiagnosticLogPolicy.configure(false);
        Path directory = Files.createTempDirectory("foreground-service-log-");
        Path file = directory.resolve("service.jsonl");
        AsyncServiceLog log = new AsyncServiceLog(() -> file.toFile(), 0L);
        try {
            long now = System.nanoTime() / 1_000_000L;
            log.appendRaw(event(now, "start", "entry", "startForeground"));
            log.appendRaw(event(now + 1, "start", "exception", "startForeground"));
            log.appendRaw(event(now + 2, "start", "exception", "startForeground"));
            log.appendRaw(event(now + 3, "stop", "posting", "stop_runtime"));
            log.appendRaw(ForegroundServiceDiagnostics.event(
                    "foreground_transition", 1234, "1234-1", "test", 7L, now + 4,
                    9, 10, true, true,
                    "operation_id", "1234-1-fg-4", "operation", "stop_runtime",
                    "transition", "stop", "action", "test_action", "reason", "test_reason",
                    "phase", "execute", "outcome", "entry", "queue_delay_ms", 12L));

            CountDownLatch flushed = new CountDownLatch(1);
            log.flush(flushed::countDown);
            assertTrue("log flush timed out", flushed.await(5, TimeUnit.SECONDS));

            List<String> records = Files.readAllLines(file);
            assertEquals(5, records.size());
            JSONObject repeatedException = new JSONObject(records.get(2));
            assertEquals("foreground_transition", repeatedException.getString("kind"));
            assertEquals("exception", repeatedException.getString("outcome"));
            assertEquals("1234-1", repeatedException.getString("service_instance"));
            assertEquals("startForeground", repeatedException.getString("operation"));

            JSONObject queuedStop = new JSONObject(records.get(4));
            assertEquals(9, queuedStop.getInt("request_start_id"));
            assertEquals(10, queuedStop.getInt("latest_start_id"));
            assertEquals(12L, queuedStop.getLong("queue_delay_ms"));
            assertTrue(queuedStop.has("pid"));
            assertTrue(queuedStop.has("thread"));
            assertTrue(queuedStop.has("t_ms"));
            assertTrue(queuedStop.has("foreground_before"));
            assertTrue(queuedStop.has("foreground_after"));
        } finally {
            log.close();
            CountDownLatch closed = new CountDownLatch(1);
            log.flush(closed::countDown);
            closed.await(5, TimeUnit.SECONDS);
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
            DiagnosticLogPolicy.configure(extendedBefore);
        }
    }

    private static String event(long elapsedMs, String transition, String outcome, String operation) {
        return ForegroundServiceDiagnostics.event(
                "foreground_transition", 1234, "1234-1", "test", 7L, elapsedMs,
                9, 10, transition.equals("stop"), transition.equals("stop"),
                "operation_id", "1234-1-fg-" + elapsedMs,
                "operation", operation, "transition", transition, "action", "test_action",
                "reason", "test_reason", "phase", "call", "outcome", outcome);
    }
}
