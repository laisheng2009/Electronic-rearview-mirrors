package com.byd.extend.remote.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.EnumSet
import javax.crypto.spec.SecretKeySpec

/** App-wrapped data keys live only in RAM; each provider is an authenticated atomic record. */
class EncryptedFileSecretStore(private val root: File, private val owner: String) : TunnelSecretStore {
    private data class Profile(val cipher: AesGcmSecretCipher, var values: Map<String, ByteArray>)
    private val profiles = linkedMapOf<String, Profile>()

    @Synchronized
    fun provision(provider: String, key: ByteArray, migration: Map<String, ByteArray>) {
        require(provider in PROVIDERS && key.size == 32)
        if (profiles.containsKey(provider)) return
        check(root.isDirectory || root.mkdirs())
        privatePermissions(root, true)
        val cipher = AesGcmSecretCipher(SecretKeySpec(key, "AES"))
        val file = File(root, "$provider.enc")
        val values = if (file.exists()) {
            require(file.length() in 1..MAX_BYTES.toLong())
            privatePermissions(file, false)
            val plain = cipher.decrypt(aad(provider), file.readBytes())
            try { decode(provider, plain) } finally { plain.fill(0) }
        } else migration.mapValues { (name, value) ->
            require(providerFor(name) == provider)
            value.copyOf()
        }
        val profile = Profile(cipher, values)
        if (!file.exists()) persist(provider, profile, values)
        profiles[provider] = profile
    }

    @Synchronized override fun getNative(key: String): ByteArray? = profile(key).values[key]?.copyOf()

    @Synchronized override fun putNative(key: String, value: ByteArray) {
        require(value.size <= MAX_BYTES / 2)
        val profile = profile(key)
        val next = profile.values + (key to value.copyOf())
        persist(providerFor(key), profile, next)
        profile.values[key]?.fill(0)
        profile.values = next
    }

    @Synchronized override fun removeNative(key: String) {
        val profile = profile(key)
        if (!profile.values.containsKey(key)) return
        persist(providerFor(key), profile, profile.values - key)
        profile.values[key]?.fill(0)
        profile.values = profile.values - key
    }

    @Synchronized override fun removePrefixNative(prefix: String) {
        val provider = providerFor(prefix)
        val profile = checkNotNull(profiles[provider])
        val next = profile.values.filterKeys { !it.startsWith(prefix) }
        persist(provider, profile, next)
        profile.values.filterKeys { it.startsWith(prefix) }.values.forEach { it.fill(0) }
        profile.values = next
    }

    private fun profile(key: String): Profile = checkNotNull(profiles[providerFor(key)]) { "Store not provisioned" }
    private fun aad(provider: String) = "camera-viewing-v1.$owner.$provider"

    private fun persist(provider: String, profile: Profile, values: Map<String, ByteArray>) {
        val plain = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(values.size)
                values.forEach { (name, value) ->
                    require(providerFor(name) == provider)
                    out.writeUTF(name); out.writeInt(value.size); out.write(value)
                }
            }
            bytes.toByteArray()
        }
        require(plain.size <= MAX_BYTES - 64)
        val encrypted = try { profile.cipher.encrypt(aad(provider), plain) } finally { plain.fill(0) }
        val temp = File(root, "$provider.tmp")
        try {
            if (!temp.exists()) check(temp.createNewFile())
            privatePermissions(temp, false)
            FileOutputStream(temp).use { it.write(encrypted); it.fd.sync() }
            Files.move(temp.toPath(), File(root, "$provider.enc").toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { encrypted.fill(0); if (temp.exists()) temp.delete() }
    }

    private fun decode(provider: String, plain: ByteArray): Map<String, ByteArray> =
        DataInputStream(ByteArrayInputStream(plain)).use { input ->
            val count = input.readInt()
            require(count in 0..4096)
            val values = linkedMapOf<String, ByteArray>()
            repeat(count) {
                val name = input.readUTF()
                require(providerFor(name) == provider && !values.containsKey(name))
                val size = input.readInt()
                require(size in 0..input.available())
                values[name] = ByteArray(size).also(input::readFully)
            }
            require(input.available() == 0)
            values
        }

    companion object {
        private val PROVIDERS = setOf("vdo", "tailscale", "zrok")
        private const val MAX_BYTES = 4 * 1024 * 1024
        private fun providerFor(name: String): String {
            require(name.matches(Regex("[A-Za-z0-9._-]{1,160}")))
            return PROVIDERS.firstOrNull { name.startsWith("tunnel.$it.") || name.startsWith("profile.$it.") }
                ?: throw IllegalArgumentException("Unknown secret namespace")
        }
        private fun privatePermissions(file: File, directory: Boolean) {
            val path = file.toPath()
            val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
            if (posix != null) {
                posix.setPermissions(PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
            } else {
                val acl = checkNotNull(Files.getFileAttributeView(path, AclFileAttributeView::class.java))
                acl.acl = listOf(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                    .setPrincipal(acl.owner).setPermissions(EnumSet.allOf(AclEntryPermission::class.java)).build())
            }
        }
    }
}
