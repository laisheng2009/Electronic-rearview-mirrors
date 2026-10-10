package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DiagnosticLogExporterTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void cancellationDuringHistoryRemovesOnlyPartialArchiveAndNeverReportsComplete() throws Exception {
        List<CompatibilityBundleExporter.Progress> updates = new ArrayList<>();
        CompatibilityBundleExporter.ExportControl control = new CompatibilityBundleExporter.ExportControl(updates::add);
        File cache = temporary.newFolder();
        File exports = new File(cache, "shared_logs");
        assertTrue(exports.mkdir());
        File previous = new File(exports, "byd-extend-diagnostics-previous.zip");
        write(previous, "previous");
        try {
            DiagnosticLogExporter.export(cache, new DiagnosticLogExporter.Snapshot(Collections.emptyList()), identity(),
                    command -> missing(), (command, output) -> {
                        if (command.equals(ContinuousLogcatRecorder.SNAPSHOT_COMMAND)) {
                            output.write(new byte[128]);
                            control.cancel();
                            output.write(new byte[128]);
                        }
                        return missing();
                    }, 5400L, control);
            fail("Cancellation must escape the per-source partial-result handler");
        } catch (CompatibilityBundleExporter.CancellationException expected) { }
        assertTrue(previous.isFile());
        assertEquals(1, exports.list().length);
        assertFalse(updates.stream().anyMatch(p -> p.phase == CompatibilityBundleExporter.Progress.Phase.COMPLETE));
    }

    private static DiagnosticLogExporter.Identity identity() {
        return new DiagnosticLogExporter.Identity(
                "com.byd.turnsignalguard.capture",
                "0.47.0",
                62,
                "BYD",
                "SL06",
                "DiLink",
                "tablet",
                "10",
                29);
    }

    private static DiagnosticLogExporter.CommandResult missing() {
        return DiagnosticLogExporter.CommandResult.failure(
                "", "authorization_required", -1);
    }

    private static void write(File file, String value) throws IOException {
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }

}
