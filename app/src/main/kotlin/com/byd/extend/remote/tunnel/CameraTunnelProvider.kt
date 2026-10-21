package com.byd.extend.remote.tunnel

import android.content.Context
import android.util.Log
import com.byd.extend.remote.security.KeystoreSecretStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.UUID
import org.json.JSONObject

data class CameraTunnelResult(
    val state: String,
    val address: String? = null,
    val loginUrl: String? = null,
    val errorCode: String? = null,
    val errorDetail: String? = null,
)

/** Serialized app-facing facade for the userspace Tailscale and embedded Zrok providers. */
class CameraTunnelProvider(
    context: Context,
    private val kind: String,
    private val name: String,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val secretStore = KeystoreSecretStore(appContext)
    private val instanceId = UUID.randomUUID().toString()
    private val executor = acquireExecutor()
    private val lifecycleLock = Any()
    private var closeRequested = false

    fun authorize(credential: String? = null, callback: (CameraTunnelResult) -> Unit) = submit(
        operation = "authorize",
        callback = callback,
        credential = credential,
    )

    fun status(callback: (CameraTunnelResult) -> Unit) = submit("status", callback)

    fun start(
        localPort: Int,
        public: Boolean,
        privateProxyCredential: String? = null,
        callback: (CameraTunnelResult) -> Unit,
    ) = submit(
        operation = "start",
        callback = callback,
        localPort = localPort,
        public = public,
        privateProxyCredential = privateProxyCredential,
    )

    fun stop(callback: (CameraTunnelResult) -> Unit) = submit("stop", callback)

    fun revoke(callback: (CameraTunnelResult) -> Unit) = submit("revoke", callback)

    private fun submit(
        operation: String,
        callback: (CameraTunnelResult) -> Unit,
        credential: String? = null,
        localPort: Int = 0,
        public: Boolean = false,
        privateProxyCredential: String? = null,
    ) {
        val accepted = synchronized(lifecycleLock) {
            if (closeRequested) {
                false
            } else {
                try {
                    executor.execute {
                        val result = try {
                            ensureNativeInitialized()
                            val request = JSONObject()
                                .put("provider", kind)
                                .put("instanceId", instanceId)
                                .put("name", name)
                                .put("localPort", localPort)
                                .put("public", public)
                            if (credential != null) request.put("credential", credential)
                            if (privateProxyCredential != null) {
                                request.put("privateProxyCredential", privateProxyCredential)
                            }
                            parseResult(NativeTunnelBridge.nativeCall(operation, request.toString()))
                        } catch (failure: UnsatisfiedLinkError) {
                            logFailure(operation, failure)
                            unavailableResult()
                        } catch (failure: Exception) {
                            logFailure(operation, failure)
                            unavailableResult()
                        }
                        runCatching { callback(result) }
                    }
                    true
                } catch (_: RejectedExecutionException) {
                    false
                }
            }
        }
        if (!accepted) runCatching { callback(unavailableResult()) }
    }

    private fun ensureNativeInitialized() {
        synchronized(nativeInitLock) {
            if (nativeInitialized) return
            check(NativeTunnelBridge.nativeInitialize(secretStore, appContext.cacheDir.absolutePath)) {
                "secure native tunnel initialization failed"
            }
            nativeInitialized = true
        }
    }

    override fun close() = close { }

    fun close(callback: (CameraTunnelResult) -> Unit) {
        var releaseNow = false
        synchronized(lifecycleLock) {
            if (closeRequested) return
            closeRequested = true
            try {
                executor.execute {
                    try {
                        val result = try {
                            ensureNativeInitialized()
                            parseResult(NativeTunnelBridge.nativeCall(
                                "close",
                                JSONObject()
                                    .put("provider", kind)
                                    .put("instanceId", instanceId)
                                    .put("name", name)
                                    .toString(),
                            ))
                        } catch (failure: UnsatisfiedLinkError) {
                            logFailure("close", failure)
                            unavailableResult()
                        } catch (failure: Exception) {
                            logFailure("close", failure)
                            unavailableResult()
                        }
                        runCatching { callback(result) }
                    } finally {
                        releaseExecutor(executor)
                    }
                }
            } catch (_: RejectedExecutionException) {
                releaseNow = true
            } finally {
                if (releaseNow) releaseExecutor(executor)
            }
        }
        if (releaseNow) runCatching { callback(unavailableResult()) }
    }

    private fun logFailure(operation: String, failure: Throwable) {
        Log.w("CameraViewing", "tunnel_$operation provider=$kind type=${failure.javaClass.simpleName}" +
            " cause=${failure.cause?.javaClass?.simpleName ?: "none"}")
    }

    private companion object {
        private val executorLock = Any()
        val nativeInitLock = Any()
        private var sharedExecutor: ExecutorService? = null
        private var executorLeaseCount = 0

        @Volatile
        var nativeInitialized = false

        fun acquireExecutor(): ExecutorService = synchronized(executorLock) {
            val current = sharedExecutor ?: Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "camera-tunnel").apply { isDaemon = true }
            }.also { sharedExecutor = it }
            executorLeaseCount++
            current
        }

        fun releaseExecutor(executor: ExecutorService) {
            synchronized(executorLock) {
                if (sharedExecutor !== executor) return
                check(executorLeaseCount > 0) { "Tunnel executor lease underflow" }
                executorLeaseCount--
                if (executorLeaseCount == 0) {
                    sharedExecutor = null
                    executor.shutdown()
                }
            }
        }

        fun parseResult(raw: String?): CameraTunnelResult {
            if (raw.isNullOrBlank()) return unavailableResult()
            val value = JSONObject(raw)
            return CameraTunnelResult(
                state = value.optString("state", "error"),
                address = value.optString("address").takeIf(String::isNotBlank),
                loginUrl = value.optString("loginUrl").takeIf(String::isNotBlank),
                errorCode = value.optString("errorCode").takeIf(String::isNotBlank),
                errorDetail = value.optString("errorDetail").takeIf(String::isNotBlank),
            )
        }

        fun unavailableResult() = CameraTunnelResult(
            state = "error",
            errorCode = "native_unavailable",
            errorDetail = "The secure tunnel runtime is unavailable on this device.",
        )
    }
}
