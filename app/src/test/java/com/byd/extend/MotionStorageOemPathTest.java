package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class MotionStorageOemPathTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void lexicalAliasKeepsDatabasePathAndResolvesCanonicalNormalFile() throws Exception {
        File storage = temporaryFolder.newFolder("storage");
        File volumeRoot = new File(storage, "4A21-0000");
        File normalDirectory = new File(volumeRoot, "Recorder/Normal");
        assertTrue(normalDirectory.mkdirs());
        File file = new File(normalDirectory, "2026-10-19-12-13-14.mp4");
        assertTrue(file.createNewFile());

        String databasePath = new File(normalDirectory, "../Normal/" + file.getName()).getAbsolutePath();
        MotionStorage.CdrNormalRow row = MotionStorage.cdrNormalRow(normalDirectory, databasePath);

        assertNotNull(row);
        assertEquals(databasePath, row.path);
        assertEquals(file.getCanonicalFile(), row.file);

        File outsideFile = new File(temporaryFolder.newFolder("outside"), file.getName());
        assertTrue(outsideFile.createNewFile());
        assertNull(MotionStorage.cdrNormalRow(normalDirectory, outsideFile.getAbsolutePath()));
    }

    @Test public void symbolicLinkAliasKeepsDatabasePathAndResolvesCanonicalNormalFile() throws Exception {
        File storage = temporaryFolder.newFolder("symlink-storage");
        File volumeRoot = new File(storage, "4A21-0000");
        File normalDirectory = new File(volumeRoot, "Recorder/Normal");
        assertTrue(normalDirectory.mkdirs());
        File file = new File(normalDirectory, "2026-10-19-12-13-14.mp4");
        assertTrue(file.createNewFile());

        File aliasRoot = new File(storage, "sdcard");
        try {
            Files.createSymbolicLink(aliasRoot.toPath(), volumeRoot.toPath());
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            Assume.assumeNoException(unavailable);
        }

        String databasePath = new File(new File(new File(aliasRoot, "Recorder"), "Normal"),
                file.getName()).getAbsolutePath();
        MotionStorage.CdrNormalRow row = MotionStorage.cdrNormalRow(normalDirectory, databasePath);

        assertNotNull(row);
        assertEquals(databasePath, row.path);
        assertEquals(file.getCanonicalFile(), row.file);
    }
}
