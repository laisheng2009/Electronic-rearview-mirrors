package com.byd.extend.remote.security

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM envelope for small app secrets. The record is version || nonce || ciphertext+tag. */
internal class AesGcmSecretCipher(
    private val key: SecretKey,
) {
    fun encrypt(name: String, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Android Keystore requires a provider-generated IV for randomized-encryption keys.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val nonce = cipher.iv
        check(nonce.size == NONCE_BYTES)
        cipher.updateAAD(name.toByteArray(StandardCharsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(1 + nonce.size + ciphertext.size)
            .put(VERSION)
            .put(nonce)
            .put(ciphertext)
            .array()
    }

    fun decrypt(name: String, record: ByteArray): ByteArray {
        require(record.size >= MIN_RECORD_BYTES && record[0] == VERSION) {
            "Unsupported or malformed encrypted secret"
        }
        val nonce = record.copyOfRange(1, 1 + NONCE_BYTES)
        val ciphertext = record.copyOfRange(1 + NONCE_BYTES, record.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(name.toByteArray(StandardCharsets.UTF_8))
        return try {
            cipher.doFinal(ciphertext)
        } catch (error: AEADBadTagException) {
            throw SecurityException("Encrypted secret authentication failed", error)
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val MIN_RECORD_BYTES = 1 + NONCE_BYTES + 16
    }
}
