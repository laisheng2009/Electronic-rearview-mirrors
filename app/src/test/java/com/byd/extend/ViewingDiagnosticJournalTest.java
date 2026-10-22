package com.byd.extend;

import com.byd.extend.remote.CameraViewingDiagnostics;
import java.io.IOException;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ViewingDiagnosticJournalTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void preservesRedactedPrimaryAndCleanupAcrossJournalRotation() throws Exception {
        var directory = temporary.newFolder();
        String name = "viewing.jsonl";
        IOException failure = new IOException("store write failed: password=secret",
                new IOException("keystore unavailable"));
        failure.addSuppressed(new IOException("listener cleanup failed"));
        JSONObject event = new JSONObject().put("stage", "start.up").put("generation", "run-1")
                .put("details", CameraViewingDiagnostics.describe(failure, "secret"));
        AvasRecoveryJournal.writeRecord(directory, name, event);
        String saved = new String(Files.readAllBytes(directory.toPath().resolve(name)), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(saved.contains("keystore unavailable"));
        assertTrue(saved.contains("listener cleanup failed"));
        assertTrue(saved.contains("start.up"));
        assertFalse(saved.contains("password=secret"));
        JSONObject padding = new JSONObject().put("details", "x".repeat(4096));
        while (directory.toPath().resolve(name).toFile().length() < AvasRecoveryJournal.MAX_BYTES)
            AvasRecoveryJournal.writeRecord(directory, name, padding);
        AvasRecoveryJournal.writeRecord(directory, name, event);
        assertTrue(new String(Files.readAllBytes(directory.toPath().resolve(name + ".1")),
                java.nio.charset.StandardCharsets.UTF_8).startsWith(saved));
        assertEquals(saved, new String(Files.readAllBytes(directory.toPath().resolve(name)),
                java.nio.charset.StandardCharsets.UTF_8));
    }
}
