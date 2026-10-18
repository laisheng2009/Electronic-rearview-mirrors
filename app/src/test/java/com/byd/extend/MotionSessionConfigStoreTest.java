package com.byd.extend;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class MotionSessionConfigStoreTest {
    @Test public void configSurvivesWorkerRecreationAndChangesSessionAtomically() throws Exception {
        File root = Files.createTempDirectory("motion-config-store").toFile();
        try {
            MotionSessionConfigStore first = new MotionSessionConfigStore(root, 10_123);
            MotionSessionConfigStore.Snapshot saved = first.write(
                    "{\"enabled\":true,\"lock\":2,\"before\":7}");
            assertNotNull(saved);
            assertTrue(MotionDetectorProtocol.validSession(saved.sessionId));
            assertEquals(1, saved.config.lockCondition);

            MotionSessionConfigStore afterRestart = new MotionSessionConfigStore(root, 10_123);
            MotionSessionConfigStore.Snapshot reopened = afterRestart.read();
            assertNotNull(reopened);
            assertEquals(saved.sessionId, reopened.sessionId);
            assertEquals(saved.config.toJson(), reopened.config.toJson());

            MotionSessionConfigStore.Snapshot changed = afterRestart.write(
                    "{\"warning\":true,\"lock\":0,\"after\":19}");
            assertNotEquals(saved.sessionId, changed.sessionId);
            assertEquals(0, changed.config.lockCondition);
            MotionSessionConfigStore.Snapshot persistedChange = first.read();
            assertEquals(changed.sessionId, persistedChange.sessionId);
            assertEquals(19, persistedChange.config.afterSeconds);
        } finally {
            Files.deleteIfExists(new File(root, "config.json.tmp").toPath());
            Files.deleteIfExists(new File(root, "config.json").toPath());
            Files.deleteIfExists(root.toPath());
        }
    }

    @Test public void persistedConfigCannotBeReadUnderDifferentInstalledOwner() throws Exception {
        File root = Files.createTempDirectory("motion-config-owner").toFile();
        try {
            new MotionSessionConfigStore(root, 10_123).write("{\"enabled\":true}");
            try {
                new MotionSessionConfigStore(root, 10_124).read();
                fail("owner mismatch must not reuse a prior app's Motion session");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("ownership"));
            }
        } finally {
            Files.deleteIfExists(new File(root, "config.json.tmp").toPath());
            Files.deleteIfExists(new File(root, "config.json").toPath());
            Files.deleteIfExists(root.toPath());
        }
    }
}
