package com.byd.extend.remote

import android.content.Context
import android.os.SystemClock
import com.byd.extend.CameraChargePolicy
import com.byd.extend.CameraChargeSettings
import com.byd.extend.CameraChargeTelemetry
import com.byd.extend.MotionRuntime
import com.byd.extend.R
import com.byd.extend.RemoteCameraSource
import com.byd.extend.remote.security.KeystoreSecretStore
import com.byd.extend.remote.tunnel.CameraTunnelProvider
import com.byd.extend.remote.tunnel.CameraTunnelResult
import java.security.SecureRandom
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CameraViewingConfig(
    val enabled: Boolean = false,
    val transport: String = "vdo",
    val tailscalePublic: Boolean = false,
    val streamId: String = "",
)

data class CameraViewingState(
    val config: CameraViewingConfig,
    val phase: String = "idle",
    val viewerUrl: String? = null,
    val authorizationUrl: String? = null,
    val error: String? = null,
    val passwordConfigured: Boolean = false,
    val tokenConfigured: Boolean = false,
)

/** Owns only the viewer; no network caller can reach an ADB or arbitrary OEM command. */
class CameraViewingController private constructor(private val context: Context) {
    private val preferences = context.getSharedPreferences("camera_viewing", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "camera-view-owner")
    }
    private val secrets by lazy { KeystoreSecretStore(context) }
    private val mutable = MutableStateFlow(CameraViewingState(readConfig()))
    val state: StateFlow<CameraViewingState> = mutable.asStateFlow()
    private var generation = 0L
    private var runtimeAvailable = false
    private var provider: CameraTunnelProvider? = null
    private var publisher: VdoCameraPublisher? = null
    private var server: CameraViewerServer? = null
    private var source: RemoteCameraSource? = null
    @Volatile private var encoder: CameraHlsEncoder? = null
    private var viewerPresent = false
    private var preparationDeadline = 0L
    private var preparationPending = false
    private var videoActive = false
    private var observation: CameraChargeTelemetry.Observation? = null

    init { execute { refreshConfiguration() } }

    private fun readConfig(): CameraViewingConfig {
        val transport = preferences.getString("transport", "vdo")
            ?.takeIf { it in setOf("vdo", "tailscale", "zrok") } ?: "vdo"
        return CameraViewingConfig(CameraChargeSettings.isRemoteViewingEnabled(context), transport,
            preferences.getBoolean("tailscale_public", false),
            preferences.getString("${transport}_name", "") ?: "")
    }

    private fun refreshConfiguration() {
        val config = readConfig()
        mutable.value = mutable.value.copy(config = config,
            passwordConfigured = readSecret(config.transport, "password").isNotEmpty(),
            tokenConfigured = readSecret(config.transport, "token").isNotEmpty())
    }

    fun getPassword(): String = readSecret(state.value.config.transport, "password")
    fun getToken(): String = readSecret(state.value.config.transport, "token")

    private fun readSecret(transport: String, field: String): String {
        val bytes = secrets.get("camera.$transport.$field") ?: return ""
        return try { bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) }
    }

    fun setPassword(value: String) = setSecret("password", value)
    fun setToken(value: String) = setSecret("token", value)

    private fun setSecret(field: String, value: String) {
        val transport = state.value.config.transport
        execute {
            require(value.length <= if (field == "password") 1024 else 4096)
            if (readSecret(transport, field) == value) return@execute
            stopListening()
            val key = "camera.$transport.$field"
            if (value.isEmpty()) secrets.remove(key)
            else value.toByteArray(Charsets.UTF_8).let { bytes ->
                try { secrets.put(key, bytes) } finally { bytes.fill(0) }
            }
            refreshConfiguration()
        }
    }

    fun setEnabled(enabled: Boolean) = execute {
        if (state.value.config.enabled == enabled) return@execute
        MotionRuntime.setRemoteViewingEnabled(context, enabled)
        refreshConfiguration()
        if (!enabled) stopListening()
        if (enabled && runtimeAvailable && canStart()) startOnOwner()
    }

    fun setTransport(value: String) = execute {
        require(value in setOf("vdo", "tailscale", "zrok"))
        if (value == state.value.config.transport) return@execute
        stopListening()
        check(preferences.edit().putString("transport", value).commit())
        refreshConfiguration()
    }

    fun setTailscalePublic(value: Boolean) = execute {
        if (value == state.value.config.tailscalePublic) return@execute
        stopListening()
        check(preferences.edit().putBoolean("tailscale_public", value).commit())
        refreshConfiguration()
    }

    fun setStreamId(value: String) = execute {
        val name = value.trim()
        val pattern = if (state.value.config.transport == "vdo") "[A-Za-z0-9_-]{1,64}"
            else "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
        if (name.isNotEmpty() && !name.matches(Regex(pattern)))
            throw ViewingFailure(R.string.cameras_error_name)
        if (name == state.value.config.streamId) return@execute
        stopListening()
        check(preferences.edit().putString("${state.value.config.transport}_name", name).commit())
        refreshConfiguration()
    }

    /** Called by the existing foreground runtime, never by a socket or a web command. */
    fun runtimeStarted() = execute {
        runtimeAvailable = true
        if (observation == null) observation = CameraChargeTelemetry.get(context).observe { charge ->
            execute { chargeChanged(charge) }
        }
        refreshConfiguration()
        if (state.value.config.enabled && provider == null && server == null && publisher == null && canStart()) startOnOwner()
    }

    fun runtimeStopped() = execute {
        runtimeAvailable = false
        observation?.close(); observation = null
        stopListening()
    }

    fun workerReconfigured() = execute {
        if (!viewerPresent) return@execute
        closeVideo()
        preparationDeadline = SystemClock.elapsedRealtime() + 30_000L
        prepareVideo(generation)
    }

    private fun publicAccess(): Boolean = state.value.config.let {
        it.transport != "tailscale" || it.tailscalePublic
    }

    private fun canStart(): Boolean = !publicAccess() || getPassword().isNotBlank()

    private fun requirePassword() {
        if (!canStart()) throw ViewingFailure(R.string.cameras_error_password_required)
    }

    private fun ensureName(): String {
        if (state.value.config.streamId.isNotEmpty()) return state.value.config.streamId
        val name = "byd-" + UUID.randomUUID().toString().replace("-", "").take(16)
        check(preferences.edit().putString("${state.value.config.transport}_name", name).commit())
        refreshConfiguration()
        return name
    }

    fun authorize() = execute {
        requirePassword()
        stopListening()
        val config = state.value.config
        if (config.transport == "vdo") {
            ensureName()
            mutable.value = state.value.copy(phase = "ready", error = null)
            return@execute
        }
        val token = getToken().ifBlank { null }
        val active = CameraTunnelProvider(context, config.transport, ensureName())
        provider = active
        val epoch = generation
        mutable.value = state.value.copy(phase = "authorizing", error = null)
        active.authorize(token) { result -> execute {
            if (epoch != generation || provider !== active) return@execute
            acceptProviderResult(result)
            if (result.state == "login_required") awaitAuthorization(active, epoch)
        } }
    }

    private fun awaitAuthorization(active: CameraTunnelProvider, epoch: Long) {
        executor.schedule({ execute {
            if (epoch != generation || provider !== active || state.value.phase != "authorizing") return@execute
            active.status { result -> execute {
                if (epoch != generation || provider !== active) return@execute
                acceptProviderResult(result)
                if (result.state == "login_required") awaitAuthorization(active, epoch)
            } }
        } }, 2, TimeUnit.SECONDS)
    }

    fun start() = execute { startOnOwner() }

    private fun startOnOwner() {
        if (!state.value.config.enabled) return
        requirePassword()
        stopListening()
        val config = state.value.config
        val name = ensureName()
        val password = getPassword()
        val epoch = generation
        mutable.value = state.value.copy(phase = "preparing", error = null, authorizationUrl = null)
        MotionRuntime.settingsChanged(context)
        if (config.transport == "vdo") {
            publisher = VdoCameraPublisher(context, name, password, object : VdoCameraPublisher.Listener {
                override fun onReady(viewerUrl: String) = execute {
                    if (epoch == generation) mutable.value = state.value.copy(
                        viewerUrl = viewerUrl, phase = "ready", error = null)
                }
                override fun onViewerConnected() = execute { if (epoch == generation) viewerConnected(epoch) }
                override fun onViewerDisconnected() = execute { if (epoch == generation) viewerDisconnected() }
                override fun onError(encoderUnavailable: Boolean) = execute {
                    if (epoch == generation) throw ViewingFailure(if (encoderUnavailable)
                        R.string.cameras_error_encoder_unavailable else R.string.cameras_error_connection)
                }
            }).also { it.start() }
            return
        }
        val privateProof = if (publicAccess()) null else randomProof()
        val http = CameraViewerServer(password, privateProof, object : CameraViewerServer.Stream {
            override fun connected() = execute { if (epoch == generation) viewerConnected(epoch) }
            override fun disconnected() = execute { if (epoch == generation) viewerDisconnected() }
            override fun status(): String = state.value.phase
            override fun unavailableChargeMask(): Int = CameraChargeTelemetry.get(context).snapshot().let {
                (if (it.isSocUnavailable) 1 else 0) or (if (it.isVoltageUnavailable) 2 else 0)
            }
            override fun video(): FragmentedVideoWindow? = encoder?.window
            override fun resource(name: String): ByteArray = context.assets.open("camera-view/$name").use { it.readBytes() }
        })
        server = http
        val active = provider ?: CameraTunnelProvider(context, config.transport, name).also { provider = it }
        active.start(http.port(), config.tailscalePublic || config.transport == "zrok", privateProof) { result -> execute {
            if (epoch != generation || provider !== active) return@execute
            acceptProviderResult(result)
        } }
    }

    private fun acceptProviderResult(result: CameraTunnelResult) {
        if (result.errorCode != null) throw ViewingFailure(R.string.cameras_error_connection)
        for (address in listOfNotNull(result.address, result.loginUrl)) {
            val uri = URI(address)
            if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null)
                throw ViewingFailure(R.string.cameras_error_connection)
        }
        mutable.value = state.value.copy(
            phase = when (result.state) {
                "serving", "authorized" -> "ready"
                "login_required" -> "authorizing"
                else -> "idle"
            },
            viewerUrl = result.address.takeIf { result.state == "serving" },
            authorizationUrl = result.loginUrl, error = null)
    }

    private fun viewerConnected(epoch: Long) {
        if (viewerPresent) return
        viewerPresent = true
        preparationDeadline = SystemClock.elapsedRealtime() + 30_000L
        prepareVideo(epoch)
    }

    private fun prepareVideo(epoch: Long) {
        if (epoch != generation || !viewerPresent || videoActive || !state.value.config.enabled) return
        preparationPending = false
        val charge = try { if (source == null) CameraChargeTelemetry.checkBeforeViewing(context)
            else CameraChargeTelemetry.get(context).snapshot() }
        catch (unavailable: Exception) { retryPreparation(epoch); return }
        if (charge.isBlocked) {
            closeVideo()
            mutable.value = state.value.copy(phase = "low_battery", error = null)
            return
        }
        mutable.value = state.value.copy(phase = "preparing", error = null)
        val camera = source ?: RemoteCameraSource(context) { _ -> execute {
            if (epoch == generation) throw ViewingFailure(R.string.cameras_error_camera_unavailable)
        } }.also { source = it }
        val ready = try { camera.prepare() } catch (notReady: Exception) { false }
        if (!ready) { retryPreparation(epoch); return }
        val surface = try {
            publisher?.startVideo() ?: CameraHlsEncoder { _ -> execute {
                if (epoch == generation) throw ViewingFailure(R.string.cameras_error_encoder_unavailable)
            } }.also { encoder = it }.start()
        } catch (failure: Exception) { throw ViewingFailure(R.string.cameras_error_encoder_unavailable) }
        try { camera.attach(surface, CameraHlsEncoder.WIDTH, CameraHlsEncoder.HEIGHT) }
        catch (failure: Exception) { throw ViewingFailure(R.string.cameras_error_camera_unavailable) }
        videoActive = true
        mutable.value = state.value.copy(phase = "connected", error = null)
    }

    private fun retryPreparation(epoch: Long) {
        if (SystemClock.elapsedRealtime() >= preparationDeadline)
            throw ViewingFailure(R.string.cameras_error_camera_unavailable)
        if (!preparationPending) {
            preparationPending = true
            executor.schedule({ execute { if (epoch == generation) prepareVideo(epoch) } }, 500, TimeUnit.MILLISECONDS)
        }
    }

    private fun chargeChanged(charge: CameraChargePolicy.State) {
        if (!viewerPresent) return
        if (charge.isBlocked) {
            closeVideo()
            mutable.value = state.value.copy(phase = "low_battery", error = null)
        } else if (state.value.phase == "low_battery") {
            preparationDeadline = SystemClock.elapsedRealtime() + 30_000L
            prepareVideo(generation)
        }
    }

    private fun viewerDisconnected() {
        viewerPresent = false
        closeVideo()
        mutable.value = state.value.copy(phase = if (server != null || publisher != null) "ready" else "idle")
    }

    private fun closeVideo() {
        preparationPending = false
        val current = source
        try {
            current?.close()
            source = null
        } finally {
            // A failed preparation-lease release must not retain an already detached encoder.
            if (current == null || current.isVideoDetached) {
                publisher?.stopVideo()
                encoder?.close()
                encoder = null
                videoActive = false
            }
        }
    }

    fun stop() = execute { stopListening() }

    private fun stopListening(reportTunnelFailure: Boolean = true) {
        generation++
        val epoch = generation
        viewerPresent = false
        server?.close(); server = null
        val closingProvider = provider
        provider = null
        closingProvider?.close { result ->
            if (reportTunnelFailure && result.errorCode != null) execute {
                if (epoch == generation) throw ViewingFailure(R.string.cameras_error_connection)
            }
        }
        try {
            closeVideo()
        } finally {
            if (source == null || source?.isVideoDetached == true) {
                publisher?.close(); publisher = null
            }
            mutable.value = state.value.copy(phase = "idle", viewerUrl = null, authorizationUrl = null, error = null)
        }
    }

    fun revoke() = execute {
        val config = state.value.config
        val name = ensureName()
        // Revocation must continue even when a detached shell lease needs a later cleanup retry.
        val cleanupFailure = try { stopListening(reportTunnelFailure = false); null }
            catch (failure: Exception) { failure }
        val old = if (config.transport != "vdo") CameraTunnelProvider(context, config.transport, name) else null
        MotionRuntime.setRemoteViewingEnabled(context, false)
        secrets.removePrefix("camera.${config.transport}.")
        check(preferences.edit().remove("${config.transport}_name").commit())
        refreshConfiguration()
        val epoch = generation
        old?.revoke { result -> execute {
            old.close()
            if (epoch == generation) {
                if (result.errorCode != null) throw ViewingFailure(R.string.cameras_error_connection)
                if (cleanupFailure != null) throw cleanupFailure
            }
        } }
        if (old == null && cleanupFailure != null) throw cleanupFailure
    }

    private fun execute(action: () -> Unit) {
        executor.execute {
            try { action() }
            catch (failure: Exception) {
                runCatching { stopListening() }
                val message = (failure as? ViewingFailure)?.messageResource
                    ?: if (failure is SecurityException) R.string.cameras_error_secret
                    else R.string.cameras_error_connection
                mutable.value = state.value.copy(phase = "error", error = context.getString(message))
            }
        }
    }

    private class ViewingFailure(val messageResource: Int) : Exception()

    companion object {
        @Volatile private var instance: CameraViewingController? = null
        @JvmStatic fun get(context: Context): CameraViewingController = instance ?: synchronized(this) {
            instance ?: CameraViewingController(context.applicationContext).also { instance = it }
        }
        private fun randomProof(): String = ByteArray(32).also(SecureRandom()::nextBytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
