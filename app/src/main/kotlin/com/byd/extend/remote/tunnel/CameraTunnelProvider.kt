package com.byd.extend.remote.tunnel

import android.content.Context
import com.byd.extend.remote.CameraViewingDiagnostics
import com.byd.extend.remote.security.KeystoreSecretStore
import com.byd.extend.remote.security.TunnelSecretStore
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import org.json.JSONObject

data class CameraTunnelResult(
    val state: String,
    val address: String? = null,
    val loginUrl: String? = null,
    val errorCode: String? = null,
    val errorDetail: String? = null,
    val diagnostic: String? = null,
)

/** Serialized app-facing facade for the userspace Tailscale and embedded Zrok providers. */
class CameraTunnelProvider(
    context: Context,
    private val kind: String,
    private val name: String,
    private val secretStore: TunnelSecretStore,
    private val runtimeDirectory: File,
    private val nativeLibraryPath: File,
) : AutoCloseable {
    constructor(context: Context, kind: String, name: String) : this(
        context.applicationContext ?: context,
        kind,
        name,
        appSecretStore(context),
        File((context.applicationContext ?: context).filesDir, "camera-tunnel-runtime"),
        File((context.applicationContext ?: context).applicationInfo.nativeLibraryDir, "libcamera_tunnel.so"),
    )

    private val instanceId = UUID.randomUUID().toString()
    private val executor: ExecutorService = providerExecutor(kind)
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
                        if (isCloseRequested()) {
                            runCatching { callback(cancelledResult()) }
                            return@execute
                        }
                        val result = try {
                            ensureNativeInitialized()
                            if (isCloseRequested()) {
                                cancelledResult()
                            } else {
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
                            }
                        } catch (failure: UnsatisfiedLinkError) {
                            logFailure(operation, failure, credential, privateProxyCredential)
                            unavailableResult()
                        } catch (failure: Exception) {
                            logFailure(operation, failure, credential, privateProxyCredential)
                            unavailableResult()
                        }
                        logNativeResult(operation, result, credential, privateProxyCredential)
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
        val runtimePath = runtimeDirectory.absolutePath
        val libraryPath = nativeLibraryPath.absolutePath
        synchronized(nativeInitLock) {
            nativeConfiguration?.let { current ->
                check(
                    current.runtimeDirectory == runtimePath &&
                        current.nativeLibraryPath == libraryPath &&
                        current.secretStore === secretStore,
                ) { "Native tunnel runtime is already bound to another private configuration." }
                return
            }
            NativeTunnelBridge.loadFromPath(libraryPath)
            check(NativeTunnelBridge.nativeInitialize(secretStore, runtimePath)) {
                "secure native tunnel initialization failed"
            }
            nativeConfiguration = NativeConfiguration(runtimePath, libraryPath, secretStore)
        }
    }

    private fun isCloseRequested(): Boolean = synchronized(lifecycleLock) { closeRequested }

    override fun close() = close { }

    fun close(callback: (CameraTunnelResult) -> Unit) {
        synchronized(lifecycleLock) {
            if (closeRequested) return
            closeRequested = true
        }
        if (nativeConfiguration != null) {
            try {
                NativeTunnelBridge.nativeCancel(kind, instanceId)
            } catch (failure: UnsatisfiedLinkError) {
                logFailure("cancel", failure)
            } catch (failure: Exception) {
                logFailure("cancel", failure)
            }
        }
        val queued = synchronized(lifecycleLock) {
            try {
                executor.execute {
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
                    logNativeResult("close", result)
                    runCatching { callback(result) }
                }
                true
            } catch (_: RejectedExecutionException) {
                false
            }
        }
        if (!queued) {
            runCatching { callback(unavailableResult()) }
        }
    }

    private fun logFailure(operation: String, failure: Throwable, vararg secrets: String?) {
        val knownSecrets = secrets.filterNotNull().toTypedArray()
        CameraViewingDiagnostics.failure("tunnel_$operation generation=$instanceId", kind, failure, *knownSecrets)
    }

    private fun logNativeResult(operation: String, result: CameraTunnelResult, vararg secrets: String?) {
        val knownSecrets = (secrets.filterNotNull() + listOfNotNull(result.loginUrl)).toTypedArray()
        result.diagnostic?.let { detail ->
            CameraViewingDiagnostics.detail("native_${operation}_response generation=$instanceId", kind,
                detail, *knownSecrets)
        }
        if (result.state != "error") return
        val code = CameraViewingDiagnostics.safeText(result.errorCode ?: "missing", *knownSecrets)
        val detail = CameraViewingDiagnostics.safeText(result.errorDetail ?: "native error without detail", *knownSecrets)
        CameraViewingDiagnostics.detail("native_$operation generation=$instanceId", kind, "code=$code cause=$detail")
    }

    private data class NativeConfiguration(
        val runtimeDirectory: String,
        val nativeLibraryPath: String,
        val secretStore: TunnelSecretStore,
    )

    companion object {
        private val nativeInitLock = Any()
        private val appSecretStoreLock = Any()
        private val tailscaleExecutor = newProviderExecutor("tailscale")
        private val zrokExecutor = newProviderExecutor("zrok")
        @Volatile
        private var convenienceSecretStore: TunnelSecretStore? = null

        private fun providerExecutor(kind: String): ExecutorService = when (kind) {
            "tailscale" -> tailscaleExecutor
            "zrok" -> zrokExecutor
            else -> throw IllegalArgumentException("Unsupported tunnel provider kind.")
        }

        private fun newProviderExecutor(kind: String): ExecutorService =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "camera-tunnel-$kind").apply { isDaemon = true }
            }

        private fun appSecretStore(context: Context): TunnelSecretStore =
            convenienceSecretStore ?: synchronized(appSecretStoreLock) {
                convenienceSecretStore ?: KeystoreSecretStore(context.applicationContext ?: context)
                    .also { convenienceSecretStore = it }
            }

        @Volatile
        private var nativeConfiguration: NativeConfiguration? = null

        @JvmStatic
        fun notifyNetworkChanged() {
            if (nativeConfiguration == null) return
            runCatching { NativeTunnelBridge.nativeNetworkChanged() }
                .onFailure { failure ->
                    CameraViewingDiagnostics.failure("tunnel_network_changed", "all", failure)
                }
        }

        private fun parseResult(raw: String?): CameraTunnelResult {
            if (raw.isNullOrBlank()) return unavailableResult()
            val value = JSONObject(raw)
            return CameraTunnelResult(
                state = value.optString("state", "error"),
                address = value.optString("address").takeIf(String::isNotBlank),
                loginUrl = value.optString("loginUrl").takeIf(String::isNotBlank),
                errorCode = value.optString("errorCode").takeIf(String::isNotBlank),
                errorDetail = value.optString("errorDetail").takeIf(String::isNotBlank),
                diagnostic = value.optString("diagnostic").takeIf(String::isNotBlank),
            )
        }

        private fun unavailableResult() = CameraTunnelResult(
            state = "error",
            errorCode = "native_unavailable",
            errorDetail = "The secure tunnel runtime is unavailable on this device.",
        )

        private fun cancelledResult() = CameraTunnelResult(
            state = "cancelled",
            errorDetail = "The tunnel operation was cancelled because its provider was closed.",
        )
    }
}
