package com.byd.extend;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAKeyGenParameterSpec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class LocalAdbKeysTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void persistedIdentityStillSignsAdbTokensAndTlsCertificate() throws Exception {
        File directory = temporary.newFolder();
        KeyPair original = generate(RSAKeyGenParameterSpec.F4);
        Files.write(new File(directory, "adb_key.priv").toPath(), original.getPrivate().getEncoded());
        Files.write(new File(directory, "adb_key.pub").toPath(), original.getPublic().getEncoded());

        for (int reopen = 0; reopen < 2; reopen++) {
            KeyPair loaded = LocalAdbClient.loadOrCreateKeys(directory);
            assertIdentityUnchanged(directory, original, loaded);
            assertTrue(loaded.getPrivate() instanceof RSAPrivateCrtKey);
            assertAdbSignature(original, loaded);
            X509Certificate certificate = AdbTlsIdentity.create(loaded);
            certificate.verify(original.getPublic());
            assertArrayEquals(original.getPublic().getEncoded(), certificate.getPublicKey().getEncoded());
        }
    }

    @Test public void newlyCreatedIdentitySurvivesReload() throws Exception {
        File directory = temporary.newFolder();
        KeyPair created = LocalAdbClient.loadOrCreateKeys(directory);
        KeyPair loaded = LocalAdbClient.loadOrCreateKeys(directory);
        assertIdentityUnchanged(directory, created, loaded);
        assertAdbSignature(created, loaded);
    }

    @Test public void restoresMissingPublicCacheUsingSavedPrivateIdentityAndExponent() throws Exception {
        KeyPair original = generate(RSAKeyGenParameterSpec.F0);
        File directory = temporary.newFolder();
        Files.write(new File(directory, "adb_key.priv").toPath(), original.getPrivate().getEncoded());
        KeyPair loaded = LocalAdbClient.loadOrCreateKeys(directory);
        assertIdentityUnchanged(directory, original, loaded);
        assertEquals(RSAKeyGenParameterSpec.F0, ((RSAPublicKey) loaded.getPublic()).getPublicExponent());
        assertAdbSignature(original, loaded);
    }

    @Test public void repairsCorruptOrMismatchedPublicCacheWithoutReplacingPrivateKey() throws Exception {
        File renameSource = temporary.newFile();
        File renameTarget = temporary.newFile();
        // AndroidX AtomicFile uses renameTo over an existing file, which Windows does not support.
        assumeTrue("Host filesystem cannot exercise AtomicFile replacement", renameSource.renameTo(renameTarget));
        KeyPair original = generate(RSAKeyGenParameterSpec.F0);
        KeyPair unrelated = generate(RSAKeyGenParameterSpec.F4);
        byte[][] publicInputs = {{1, 2, 3}, unrelated.getPublic().getEncoded()};
        for (byte[] publicInput : publicInputs) {
            File directory = temporary.newFolder();
            Files.write(new File(directory, "adb_key.priv").toPath(), original.getPrivate().getEncoded());
            Files.write(new File(directory, "adb_key.pub").toPath(), publicInput);
            KeyPair loaded = LocalAdbClient.loadOrCreateKeys(directory);
            assertIdentityUnchanged(directory, original, loaded);
            assertEquals(RSAKeyGenParameterSpec.F0, ((RSAPublicKey) loaded.getPublic()).getPublicExponent());
            assertAdbSignature(original, loaded);
        }
    }

    @Test public void corruptPrivateKeyIsNeverReplaced() throws Exception {
        File directory = temporary.newFolder();
        byte[] corrupt = {1, 2, 3};
        byte[] publicKey = generate(RSAKeyGenParameterSpec.F4).getPublic().getEncoded();
        Files.write(new File(directory, "adb_key.priv").toPath(), corrupt);
        Files.write(new File(directory, "adb_key.pub").toPath(), publicKey);
        assertThrows(IOException.class, () -> LocalAdbClient.loadOrCreateKeys(directory));
        assertArrayEquals(corrupt, Files.readAllBytes(new File(directory, "adb_key.priv").toPath()));
        assertArrayEquals(publicKey, Files.readAllBytes(new File(directory, "adb_key.pub").toPath()));
    }

    @Test public void orphanPublicKeyDoesNotGenerateReplacementIdentity() throws Exception {
        File directory = temporary.newFolder();
        byte[] publicKey = generate(RSAKeyGenParameterSpec.F4).getPublic().getEncoded();
        Files.write(new File(directory, "adb_key.pub").toPath(), publicKey);
        assertThrows(IOException.class, () -> LocalAdbClient.loadOrCreateKeys(directory));
        assertFalse(new File(directory, "adb_key.priv").exists());
        assertArrayEquals(publicKey, Files.readAllBytes(new File(directory, "adb_key.pub").toPath()));
    }

    private static KeyPair generate(java.math.BigInteger exponent) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(new RSAKeyGenParameterSpec(2048, exponent));
        return generator.generateKeyPair();
    }

    private static void assertIdentityUnchanged(File directory, KeyPair original, KeyPair loaded)
            throws Exception {
        assertArrayEquals(original.getPrivate().getEncoded(),
                Files.readAllBytes(new File(directory, "adb_key.priv").toPath()));
        assertArrayEquals(original.getPublic().getEncoded(),
                Files.readAllBytes(new File(directory, "adb_key.pub").toPath()));
        assertArrayEquals(original.getPublic().getEncoded(), loaded.getPublic().getEncoded());
        assertEquals(AdbKeyFormatter.formatPublicKey((RSAPublicKey) original.getPublic()),
                AdbKeyFormatter.formatPublicKey((RSAPublicKey) loaded.getPublic()));
    }

    private static void assertAdbSignature(KeyPair original, KeyPair loaded) throws Exception {
        byte[] challenge = "ADB persisted-key regression".getBytes(StandardCharsets.UTF_8);
        byte[] token = MessageDigest.getInstance("SHA-1").digest(challenge);
        byte[] signedToken = LocalAdbClient.signToken(loaded.getPrivate(), token);
        Signature verifier = Signature.getInstance("SHA1withRSA");
        verifier.initVerify(original.getPublic());
        verifier.update(challenge);
        assertTrue(verifier.verify(signedToken));
    }
}
