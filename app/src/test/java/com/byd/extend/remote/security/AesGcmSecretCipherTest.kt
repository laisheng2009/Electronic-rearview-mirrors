package com.byd.extend.remote.security

import javax.crypto.SecretKey
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AesGcmSecretCipherTest {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun encryptsAndAuthenticatesTheSecretAndItsName() {
        val cipher = AesGcmSecretCipher(key)
        val plaintext = "private state containing a node key".toByteArray()

        val first = cipher.encrypt("tunnel.tailscale.state.netmap", plaintext)
        val second = cipher.encrypt("tunnel.tailscale.state.netmap", plaintext)

        assertNotEquals(plaintext.toList(), first.toList())
        assertNotEquals(first.toList(), second.toList())
        assertArrayEquals(plaintext, cipher.decrypt("tunnel.tailscale.state.netmap", first))
        assertThrows(SecurityException::class.java) {
            cipher.decrypt("tunnel.zrok.environment", first)
        }

        val tampered = first.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(SecurityException::class.java) {
            cipher.decrypt("tunnel.tailscale.state.netmap", tampered)
        }
    }
}
