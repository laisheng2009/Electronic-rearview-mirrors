package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

    @Test public void motionDirectoryQueryDistinguishesPresentAbsentAndNonDirectoryPaths() throws Exception {
        File publicRoot = temporaryFolder.newFolder("motion-public-root");
        assertNull(MotionStorage.queryMotionDirectory(publicRoot, "Movies"));

        File movies = new File(publicRoot, "Movies");
        assertTrue(movies.mkdir());
        assertNull(MotionStorage.queryMotionDirectory(publicRoot, "Movies"));

        File bydExtend = new File(movies, "BYD Extend");
        assertTrue(bydExtend.mkdir());
        assertNull(MotionStorage.queryMotionDirectory(publicRoot, "Movies"));

        File motion = new File(bydExtend, "Motion");
        assertTrue(motion.mkdir());
        assertEquals(motion.getCanonicalFile(),
                MotionStorage.queryMotionDirectory(publicRoot, "Movies").getCanonicalFile());

        File nonDirectoryRoot = temporaryFolder.newFolder("motion-nondirectory-root");
        assertTrue(new File(nonDirectoryRoot, "Movies").createNewFile());
        try {
            MotionStorage.queryMotionDirectory(nonDirectoryRoot, "Movies");
            fail("Expected a non-directory path to be treated as an unqueryable volume");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Cannot query Motion media directory"));
        }
    }
}
