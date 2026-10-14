package com.byd.extend;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class CameraPresetFilesTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void cachedSnapshotSurvivesRestoreAndDocumentCopyUnchanged() throws Exception {
        String json = "{\"settings\":{\"label\":\"Камери 中文\",\"payload\":\""
                + "0123456789".repeat(9000) + "\"}}";
        File cache = temporary.newFolder();
        File snapshot = CameraPresetFiles.write(cache, json);
        File restored = CameraPresetFiles.restore(cache, snapshot.getName());
        assertEquals(snapshot, restored);
        File destination = temporary.newFile("saved-preset.json");
        try (FileOutputStream output = new FileOutputStream(destination)) {
            assertEquals(snapshot.length(), ArchiveDocumentWriter.copy(restored, output));
        }
        assertArrayEquals(json.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(destination.toPath()));
        try (FileInputStream input = new FileInputStream(destination)) {
            assertEquals(json, CameraPresetFiles.read(input));
        }
    }

    @Test public void restoreRejectsPathsAndMissingSnapshotsAndWriteKeepsSizeLimit() throws Exception {
        File cache = temporary.newFolder();
        File snapshot = CameraPresetFiles.write(cache, "{}");
        assertNull(CameraPresetFiles.restore(cache, snapshot.getAbsolutePath()));
        assertNull(CameraPresetFiles.restore(cache, "../" + snapshot.getName()));
        assertNull(CameraPresetFiles.restore(cache, "byd-extend-camera-preset-missing.json"));
        assertNull(CameraPresetFiles.restore(cache, null));
        assertThrows(IOException.class, () -> CameraPresetFiles.write(cache,
                "x".repeat(CameraSettingsTransfer.MAX_INPUT_BYTES + 1)));
    }
}
