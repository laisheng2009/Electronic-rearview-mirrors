package com.byd.extend;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Real files/ZIPs, not a simulation of Android settings, ADB, or the document picker. */
public class AdbRecoveryDiagnosticsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void ordinaryLogsKeepCauseThroughRotationArchiveAndDocumentCopy() throws Exception {
        File directory = temporary.newFolder();
        IOException primary = new IOException("recovery identity read",
                missingFile(directory, "identity-missing"));
        primary.addSuppressed(missingFile(directory, "cleanup-missing"));
        List<JSONObject> events = new ArrayList<>();
        AdbRecoveryDiagnostics.failure((kind, fields) -> events.add(record(kind, fields)),
                "adb_setting_failed", "read", primary, "key", "adb_enabled");
        JSONObject event = events.get(0);
        String trace = event.getString("error");
        assertTrue(trace.contains("Caused by:"));
        assertTrue(trace.contains("Suppressed:"));
        assertTrue(trace.contains("NoSuchFileException"));
        assertTrue(trace.contains("identity-missing"));
        assertTrue(trace.contains("cleanup-missing"));
        assertTrue(trace.contains("AdbRecoveryDiagnosticsTest.missingFile"));

        File service = new File(directory, "helper-service-recovery.jsonl");
        boolean extended = DiagnosticLogPolicy.extended();
        DiagnosticLogPolicy.configure(false);
        AsyncServiceLog log = new AsyncServiceLog(() -> service, 0);
        try {
            log.appendRaw(event.toString());
            log.appendRaw(event.toString());
            AdbRecoveryDiagnostics.failure((kind, fields) -> log.appendRaw(record(kind, fields).toString()),
                    "adb_setting_failed", "read", missingFile(directory, "changed-cause"));
            CountDownLatch flushed = new CountDownLatch(1);
            log.flush(flushed::countDown);
            assertTrue("real log flush", flushed.await(5, TimeUnit.SECONDS));
        } finally {
            log.close();
            DiagnosticLogPolicy.configure(extended);
        }
        String ordinary = new String(Files.readAllBytes(service.toPath()), StandardCharsets.UTF_8);
        assertTrue(ordinary.contains("identity-missing"));
        assertTrue(ordinary.contains("changed-cause"));
        assertTrue(ordinary.contains("log_error_repeated"));

        AvasRecoveryJournal.writeRecord(directory, AvasRecoveryJournal.FILE_NAME, event);
        AvasRecoveryJournal.writeRecord(directory, AvasRecoveryJournal.FILE_NAME,
                new JSONObject().put("note", "x".repeat((int) AvasRecoveryJournal.MAX_BYTES)));
        AvasRecoveryJournal.writeRecord(directory, AvasRecoveryJournal.FILE_NAME, event);
        DiagnosticLogExporter.Snapshot snapshot = DiagnosticLogExporter.snapshot(directory);
        File archive = temporary.newFile("share.zip");
        List<DiagnosticLogExporter.SourceRecord> records = new ArrayList<>();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            DiagnosticLogExporter.writeAppLogs(zip, snapshot, records,
                    new DiagnosticLogExporter.ProgressTracker(null));
        }
        assertEquals(3, records.size());
        for (DiagnosticLogExporter.SourceRecord source : records) assertEquals("included", source.status);
        File document = temporary.newFile("saved-as.zip");
        try (FileOutputStream output = new FileOutputStream(document)) {
            assertEquals(archive.length(), ArchiveDocumentWriter.copy(archive, output));
        }
        assertArrayEquals(Files.readAllBytes(archive.toPath()), Files.readAllBytes(document.toPath()));
        for (File saved : new File[]{archive, document}) {
            try (ZipFile zip = new ZipFile(saved)) {
                for (String name : new String[]{service.getName(), AvasRecoveryJournal.FILE_NAME,
                        AvasRecoveryJournal.PREVIOUS_FILE_NAME}) {
                    String contents = new String(zip.getInputStream(zip.getEntry("logs/" + name))
                            .readAllBytes(), StandardCharsets.UTF_8);
                    JSONObject first = new JSONObject(contents.substring(0, contents.indexOf('\n')));
                    assertEquals(trace, first.getString("error"));
                    assertEquals("read", first.getString("operation"));
                    assertEquals("adb_enabled", first.getString("key"));
                }
            }
        }
    }

    @Test public void transportRetainsDiagnosticFieldsWithoutKeysPayloadsOrPersonalLabels() throws Exception {
        String material = "A".repeat(160);
        IOException diagnostic = new IOException("denied password=secret \"ssid\":\"private network\" "
                + "bssid=01:23:45:67:89:ab 'serial':'personal-serial' "
                + "-----BEGIN PRIVATE KEY-----\n" + material + "\n-----END PRIVATE KEY----- " + material);
        List<JSONObject> events = new ArrayList<>();
        AdbRecoveryDiagnostics.failure((kind, fields) -> events.add(record(kind, fields)),
                "adb_tls_failed", "handshake", diagnostic);
        Object[] input = {"stage", "signature_sent", "reason", "prompt_forbidden",
                "error", events.get(0).getString("error"), "token_bytes", 20,
                "public_key_sent", false, "fingerprint", "private-fingerprint",
                "payload", "private-payload", "private_key", material};
        JSONObject transported = record("adb_transport",
                AdbRecoveryDiagnostics.transportFields("adb_handshake", input));
        assertEquals("signature_sent", transported.getString("stage"));
        assertEquals("prompt_forbidden", transported.getString("reason"));
        assertEquals(20, transported.getInt("token_bytes"));
        assertFalse(transported.getBoolean("public_key_sent"));
        assertTrue(transported.getString("error").contains("java.io.IOException: denied"));
        for (String secret : new String[]{"secret", "private network", "01:23:45:67:89:ab",
                "personal-serial", material, "private-fingerprint", "private-payload"})
            assertFalse(secret, transported.toString().contains(secret));
        assertFalse(transported.has("fingerprint"));
        assertFalse(transported.has("payload"));
        assertFalse(transported.has("private_key"));
    }

    @Test public void differentSettingsAndPlatformCodesAreNotDeduplicatedAsOneFailure() {
        DiagnosticLogPolicy policy = new DiagnosticLogPolicy();
        String kind = "adb_setting_failed";
        policy.before(record(kind, "operation", "write", "reason", "putInt_returned_false",
                "key", "adb_enabled").toString(), kind, 1);
        policy.before(record(kind, "operation", "write", "reason", "putInt_returned_false",
                "key", "adb_wifi_enabled").toString(), kind, 2);
        assertFalse(policy.suppressed());
        kind = "adb_tls_mdns_resolve_failed";
        policy.before(record(kind, "error_code", 0).toString(), kind, 3);
        policy.before(record(kind, "error_code", 3).toString(), kind, 4);
        assertFalse(policy.suppressed());
        policy.before(record(kind, "error_code", 3).toString(), kind, 5);
        assertTrue(policy.suppressed());
    }

    private static IOException missingFile(File directory, String name) throws IOException {
        try { Files.readAllBytes(new File(directory, name).toPath()); }
        catch (IOException actual) { return actual; }
        throw new AssertionError("Expected a real missing file");
    }

    private static JSONObject record(String kind, Object... fields) {
        try {
            JSONObject event = new JSONObject().put("kind", kind);
            for (int i = 0; i + 1 < fields.length; i += 2) event.put(String.valueOf(fields[i]), fields[i + 1]);
            return event;
        } catch (Exception error) { throw new AssertionError(error); }
    }
}
