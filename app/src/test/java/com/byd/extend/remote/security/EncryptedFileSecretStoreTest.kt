package com.byd.extend.remote.security

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EncryptedFileSecretStoreTest {
    @Rule @JvmField val temporary = TemporaryFolder()

    @Test
    fun reopensWithTheSameOwnerAndPreservesTheProviderIdentity() {
        val root = temporary.newFolder("profiles")
        val key = testKey(1)
        val identityKey = "profile.vdo.config"
        val identity = "{\"name\":\"custom_camera_id\"}".toByteArray()

        EncryptedFileSecretStore(root, "owner-1001").apply {
            provision("vdo", key, emptyMap())
            putNative(identityKey, identity)
        }

        val reopened = EncryptedFileSecretStore(root, "owner-1001")
        reopened.provision("vdo", key, emptyMap())

        assertArrayEquals(identity, reopened.getNative(identityKey))
    }

    @Test
    fun rejectsWrongOwnerWrongKeyAndTamperedProviderRecords() {
        val key = testKey(2)
        val identityKey = "profile.vdo.config"

        val wrongOwnerRoot = temporary.newFolder("wrong-owner")
        EncryptedFileSecretStore(wrongOwnerRoot, "owner-1001").apply {
            provision("vdo", key, emptyMap())
            putNative(identityKey, "identity".toByteArray())
        }
        assertThrows(SecurityException::class.java) {
            EncryptedFileSecretStore(wrongOwnerRoot, "owner-2002").provision("vdo", key, emptyMap())
        }

        val wrongKeyRoot = temporary.newFolder("wrong-key")
        EncryptedFileSecretStore(wrongKeyRoot, "owner-1001").apply {
            provision("vdo", key, emptyMap())
            putNative(identityKey, "identity".toByteArray())
        }
        assertThrows(SecurityException::class.java) {
            EncryptedFileSecretStore(wrongKeyRoot, "owner-1001")
                .provision("vdo", testKey(3), emptyMap())
        }

        val tamperedRoot = temporary.newFolder("tampered")
        EncryptedFileSecretStore(tamperedRoot, "owner-1001").apply {
            provision("vdo", key, emptyMap())
            putNative(identityKey, "identity".toByteArray())
        }
        val record = File(tamperedRoot, "vdo.enc")
        val tampered = record.readBytes().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        }
        record.writeBytes(tampered)
        assertThrows(SecurityException::class.java) {
            EncryptedFileSecretStore(tamperedRoot, "owner-1001").provision("vdo", key, emptyMap())
        }

        val providerBoundRoot = temporary.newFolder("provider-bound")
        EncryptedFileSecretStore(providerBoundRoot, "owner-1001").apply {
            provision("vdo", key, emptyMap())
            putNative(identityKey, "identity".toByteArray())
        }
        File(providerBoundRoot, "vdo.enc").copyTo(File(providerBoundRoot, "tailscale.enc"))
        assertThrows(SecurityException::class.java) {
            EncryptedFileSecretStore(providerBoundRoot, "owner-1001").provision("tailscale", key, emptyMap())
        }
    }

    @Test
    fun removingOneProviderKeepsOtherProviderSecretsAcrossReopen() {
        val root = temporary.newFolder("provider-removal")
        val keys = mapOf("vdo" to testKey(4), "tailscale" to testKey(5), "zrok" to testKey(6))
        val stored = EncryptedFileSecretStore(root, "owner-1001")
        keys.forEach { (provider, key) -> stored.provision(provider, key, emptyMap()) }

        val vdoIdentity = "profile.vdo.config"
        val vdoSecret = "tunnel.vdo.password"
        val tailscaleIdentity = "profile.tailscale.config"
        val tailscaleSecret = "tunnel.tailscale.state"
        val zrokIdentity = "profile.zrok.config"
        val zrokSecret = "tunnel.zrok.environment"
        val values = mapOf(
            vdoIdentity to "custom-vdo-id",
            vdoSecret to "vdo-secret",
            tailscaleIdentity to "tailscale-account",
            tailscaleSecret to "tailscale-state",
            zrokIdentity to "zrok-account",
            zrokSecret to "zrok-token",
        ).mapValues { (_, value) -> value.toByteArray() }
        values.forEach { (name, value) -> stored.putNative(name, value) }

        stored.removePrefixNative("profile.vdo.")
        stored.removePrefixNative("tunnel.vdo.")

        val reopened = EncryptedFileSecretStore(root, "owner-1001")
        keys.forEach { (provider, key) -> reopened.provision(provider, key, emptyMap()) }

        assertNull(reopened.getNative(vdoIdentity))
        assertNull(reopened.getNative(vdoSecret))
        assertArrayEquals(values.getValue(tailscaleIdentity), reopened.getNative(tailscaleIdentity))
        assertArrayEquals(values.getValue(tailscaleSecret), reopened.getNative(tailscaleSecret))
        assertArrayEquals(values.getValue(zrokIdentity), reopened.getNative(zrokIdentity))
        assertArrayEquals(values.getValue(zrokSecret), reopened.getNative(zrokSecret))
    }

    private fun testKey(seed: Int): ByteArray = ByteArray(32) { (seed + it).toByte() }
}
