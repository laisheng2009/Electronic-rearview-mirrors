package com.byd.extend;

import androidx.core.util.AtomicFile;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class LocalAdbKeyPersistenceTest {
    @Test public void interruptedFirstPrivateKeyWriteIsDiscardedBeforeCreation() throws Exception {
        Path directory = Files.createTempDirectory("byd-adb-interrupted-key-");
        File privateFile = directory.resolve("adb_key.priv").toFile();
        try {
            FileOutputStream interrupted = new AtomicFile(privateFile).startWrite();
            interrupted.write(new byte[]{1, 2, 3});
            interrupted.close();
            assertFalse(privateFile.exists());
            assertTrue(new File(privateFile.getPath() + ".new").exists());

            KeyPair created = LocalAdbClient.loadOrCreateKeys(directory.toFile());
            KeyPair loaded = LocalAdbClient.loadOrCreateKeys(directory.toFile());
            assertArrayEquals(created.getPrivate().getEncoded(), loaded.getPrivate().getEncoded());
            assertArrayEquals(created.getPublic().getEncoded(), loaded.getPublic().getEncoded());
            assertArrayEquals(created.getPrivate().getEncoded(), Files.readAllBytes(privateFile.toPath()));
            assertArrayEquals(created.getPublic().getEncoded(),
                    Files.readAllBytes(directory.resolve("adb_key.pub")));
        } finally {
            deleteKeyFiles(directory);
        }
    }

    @Test public void interruptedReplacementKeepsPrivateIdentityAndRestoresMissingPublicKey() throws Exception {
        Path directory = Files.createTempDirectory("byd-adb-key-identity-");
        File privateFile = directory.resolve("adb_key.priv").toFile();
        Path publicFile = directory.resolve("adb_key.pub");
        try {
            KeyPair original = LocalAdbClient.loadOrCreateKeys(directory.toFile());
            byte[] privateBytes = Files.readAllBytes(privateFile.toPath());

            FileOutputStream interrupted = new AtomicFile(privateFile).startWrite();
            interrupted.write(new byte[]{4, 5, 6});
            interrupted.close();
            assertTrue(privateFile.exists());
            assertTrue(new File(privateFile.getPath() + ".new").exists());
            Files.delete(publicFile);

            KeyPair repaired = LocalAdbClient.loadOrCreateKeys(directory.toFile());
            assertArrayEquals(privateBytes, Files.readAllBytes(privateFile.toPath()));
            assertArrayEquals(original.getPrivate().getEncoded(), repaired.getPrivate().getEncoded());
            assertArrayEquals(original.getPublic().getEncoded(), repaired.getPublic().getEncoded());
            assertArrayEquals(original.getPublic().getEncoded(), Files.readAllBytes(publicFile));
        } finally {
            deleteKeyFiles(directory);
        }
    }

    @Test public void legacyBackupRestoresPrivateIdentityBeforeCreation() throws Exception {
        Path directory = Files.createTempDirectory("byd-adb-key-backup-");
        try {
            KeyPair original = LocalAdbClient.loadOrCreateKeys(directory.toFile());
            Files.move(directory.resolve("adb_key.priv"), directory.resolve("adb_key.priv.bak"));
            KeyPair restored = LocalAdbClient.loadOrCreateKeys(directory.toFile());
            assertArrayEquals(original.getPrivate().getEncoded(), restored.getPrivate().getEncoded());
            assertArrayEquals(original.getPublic().getEncoded(), restored.getPublic().getEncoded());
            assertTrue(Files.exists(directory.resolve("adb_key.priv")));
        } finally {
            deleteKeyFiles(directory);
        }
    }

    private static void deleteKeyFiles(Path directory) throws Exception {
        for (String name : new String[]{
                "adb_key.priv", "adb_key.priv.new", "adb_key.priv.bak",
                "adb_key.pub", "adb_key.pub.new", "adb_key.pub.bak"}) {
            Files.deleteIfExists(directory.resolve(name));
        }
        Files.deleteIfExists(directory);
    }
}
