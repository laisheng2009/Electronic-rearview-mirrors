package com.byd.extend.remote.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Stores small secret values in app-private preferences, encrypted with an Android Keystore key.
 * Preference names are identifiers only; all values are authenticated AES-GCM ciphertext.
 */
class KeystoreSecretStore(context: Context) : TunnelSecretStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_FILE,
        Context.MODE_PRIVATE,
    )
    private val cipher: AesGcmSecretCipher by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AesGcmSecretCipher(loadOrCreateKey())
    }

    @Synchronized
    fun get(key: String): ByteArray? {
        validateName(key)
        val encoded = preferences.getString(key, null) ?: return null
        val record = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (error: IllegalArgumentException) {
            throw SecurityException("Stored secret is malformed", error)
        }
        return try {
            cipher.decrypt(key, record)
        } finally {
            record.fill(0)
        }
    }

    @Synchronized
    fun put(key: String, value: ByteArray) {
        validateName(key)
        val record = cipher.encrypt(key, value)
        try {
            check(preferences.edit().putString(key, Base64.encodeToString(record, Base64.NO_WRAP)).commit()) {
                "Could not persist encrypted secret"
            }
        } finally {
            record.fill(0)
        }
    }

    @Synchronized
    fun remove(key: String) {
        validateName(key)
        check(preferences.edit().remove(key).commit()) { "Could not remove encrypted secret" }
    }

    @Synchronized
    fun removePrefix(prefix: String) {
        require(prefix.isNotEmpty() && NAME_PATTERN.matches(prefix)) { "Invalid secret prefix" }
        val editor = preferences.edit()
        preferences.all.keys.asSequence().filter { it.startsWith(prefix) }.forEach(editor::remove)
        check(editor.commit()) { "Could not remove encrypted secrets" }
    }

    /** JNI clears exceptions: retain their class and operation, never secret names or values. */
    override fun getNative(key: String): ByteArray? = nativeOperation("read") { get(key) }
    override fun putNative(key: String, value: ByteArray) = nativeOperation("write") { put(key, value) }
    override fun removeNative(key: String) = nativeOperation("remove") { remove(key) }
    override fun removePrefixNative(prefix: String) = nativeOperation("remove_prefix") { removePrefix(prefix) }

    /** Used only for one-time, authenticated handoff to the independent viewing process. */
    @Synchronized
    fun snapshot(prefix: String): Map<String, ByteArray> {
        require(prefix.isNotEmpty() && NAME_PATTERN.matches(prefix))
        return preferences.all.keys.filter { it.startsWith(prefix) }
            .associateWith { checkNotNull(get(it)) }
    }

    private fun <T> nativeOperation(operation: String, action: () -> T): T = try { action() }
    catch (failure: Exception) {
        Log.w("CameraViewing", "secret_$operation type=${failure.javaClass.simpleName}" +
            " cause=${failure.cause?.javaClass?.simpleName ?: "none"}")
        throw failure
    }

    private fun loadOrCreateKey(): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        generator.generateKey()
    }

    private fun validateName(name: String) {
        require(NAME_PATTERN.matches(name)) { "Invalid secret key" }
    }

    private companion object {
        val keyLock = Any()
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "byd_extend_tunnel_secret_v1"
        const val PREFERENCES_FILE = "tunnel_secrets"
        val NAME_PATTERN = Regex("[A-Za-z0-9._-]{1,160}")
    }
}
