package com.byd.extend.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import android.util.Log
import com.byd.extend.CameraChargePolicy
import com.byd.extend.RemoteCameraPreparation
import com.byd.extend.RemoteCameraSource
import com.byd.extend.remote.security.EncryptedFileSecretStore
import com.byd.extend.remote.tunnel.CameraTunnelProvider
import com.byd.extend.remote.tunnel.CameraTunnelResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/** Independent shell-process owner. Only the local app IPC can change profiles. */
class CameraViewingRuntime(private val context: Context, directory: File, ownerUid: Int) : AutoCloseable {
    private val owner = Executors.newSingleThreadScheduledExecutor { Thread(it, "viewing-owner") }
    private val store = EncryptedFileSecretStore(File(directory, "profiles"), "com.byd.extend.$ownerUid")
    private val nativeDirectory = File(directory, "native")
    private val nativeLibrary = File(context.applicationInfo.nativeLibraryDir, "libcamera_tunnel.so")
    private val profiles = linkedMapOf<String, Profile>()
    private val lease = CameraViewerLease()
    private var enabled = false
    @Volatile private var closed = false
    @Volatile private var latest = "{}"
    @Volatile private var listener: Consumer<JSONObject>? = null
    private var chargeObservation: AutoCloseable? = null
    private var networkRegistered = false
    @Volatile private var chargeState: CameraChargePolicy.State? = null
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { CameraTunnelProvider.notifyNetworkChanged() }
        override fun onLost(network: Network) { CameraTunnelProvider.notifyNetworkChanged() }
        override fun onLinkPropertiesChanged(network: Network, properties: android.net.LinkProperties) {
            CameraTunnelProvider.notifyNetworkChanged()
        }
    }

    private class Profile(val method: String, var config: JSONObject) {
        var phase = "idle"
        var error: String? = null
        var url: String? = null
        var login: String? = null
        var authorizationRequest = 0L
        var run: Run? = null
        var generation = 0L
        val authorized get() = config.optBoolean("authorized")
        val pendingRevoke get() = config.optBoolean("revocationPending")
    }
    private class Run(val generation: Long) {
        val lease = Any()
        var provider: CameraTunnelProvider? = null
        var publisher: VdoCameraPublisher? = null
        var server: CameraViewerServer? = null
        var source: RemoteCameraSource? = null
        @Volatile var encoder: CameraHlsEncoder? = null
        var viewer = false
        var video = false
        var preparing = false
        var deadline = 0L
        var interactive = false
    }

    fun setListener(value: Consumer<JSONObject>?) { listener = value; value?.accept(snapshot()) }
    fun snapshot(): JSONObject = JSONObject(latest)

    fun request(operation: String, payload: JSONObject): JSONObject = try { owner.submit<JSONObject> {
        check(!closed)
        try { when (operation) {
            "provision" -> provision(payload)
            "configure" -> configure(payload.getBoolean("enabled"))
            "authorize", "reconnect" -> {
                val profile = profile(payload)
                require(!profile.pendingRevoke) { "revocation_pending" }
                require(profile.phase !in setOf("authorizing", "connecting")) { "authorization_in_progress" }
                if (!profile.authorized) {
                    require(operation == "authorize") { "not_authorized" }
                    val config = payload.getJSONObject("profile")
                    validate(profile.method, config)
                    profile.config = JSONObject(config.toString()).put("authorized", false)
                        .put("revocationPending", false)
                        .put("revocationEpoch", profile.config.optLong("revocationEpoch"))
                    save(profile)
                }
                connect(profile, true)
            }
            "revoke" -> revoke(profile(payload))
            "reconfigured" -> {
                observeCharge()
                profiles.values.forEach { p -> p.run?.let { run ->
                if (run.viewer) { closeVideo(run); run.deadline = SystemClock.elapsedRealtime() + 30_000; prepare(p, run) }
                } }
            }
            "authorization_opened" -> {
                val p = profile(payload)
                if (p.authorizationRequest == payload.getLong("request")) p.authorizationRequest = 0
            }
            "shutdown" -> configure(false)
            else -> throw IllegalArgumentException("unsupported_operation")
        } } finally { publish() }
        snapshot()
    }.get(30, TimeUnit.SECONDS)
    } catch (failure: ExecutionException) {
        throw (failure.cause as? RuntimeException ?: IllegalStateException("viewing_request_failed", failure.cause))
    } catch (failure: TimeoutException) {
        throw IllegalStateException("viewing_request_timeout", failure)
    } catch (failure: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IllegalStateException("viewing_request_interrupted", failure)
    }

    private fun profile(payload: JSONObject) = checkNotNull(profiles[payload.getString("transport")])
    private fun save(p: Profile) = store.putNative("profile.${p.method}.config", p.config.toString().toByteArray())

    private fun provision(payload: JSONObject) {
        val entries = payload.getJSONArray("profiles")
        require(entries.length() == 3)
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val method = entry.getString("transport")
            require(method in METHODS)
            if (profiles.containsKey(method)) continue
            val key = Base64.getDecoder().decode(entry.getString("key"))
            val source = entry.getJSONObject("migration")
            val migration = source.keys().asSequence().associateWith { Base64.getDecoder().decode(source.getString(it)) }
            try { store.provision(method, key, migration) }
            finally { key.fill(0); migration.values.forEach { it.fill(0) } }
            val record = store.getNative("profile.$method.config")
            val config = if (record != null) try { JSONObject(String(record, Charsets.UTF_8)) }
                finally { record.fill(0) }
            else JSONObject(entry.getJSONObject("initial").toString()).put("authorized", false)
                .put("revocationPending", false).put("legacyIdentity", migration.isNotEmpty() && method != "vdo")
            val p = Profile(method, config)
            if (record == null) save(p)
            profiles[method] = p
        }
        require(profiles.keys == METHODS.toSet())
        if (!networkRegistered) {
            connectivity.registerDefaultNetworkCallback(networkCallback)
            networkRegistered = true
        }
        if (chargeObservation == null) observeCharge()
        configure(payload.getBoolean("enabled"))
    }

    private fun observeCharge() {
        chargeObservation?.close()
        chargeObservation = null
        chargeState = null
        chargeObservation = RemoteCameraPreparation.observeCharge(context) { value ->
            chargeState = value
            execute { chargeChanged(value) }
        }
    }

    private fun configure(value: Boolean) {
        enabled = value
        for (p in profiles.values) {
            try {
                if (!enabled) stop(p)
                else if (!p.pendingRevoke && (p.authorized || p.config.optBoolean("legacyIdentity")) && p.run == null)
                    connect(p, false)
            } catch (failure: Exception) { failed(p, failure) }
        }
    }

    private fun validate(method: String, config: JSONObject) {
        val name = config.getString("name")
        val password = config.optString("password")
        val token = config.optString("token")
        val edits = CameraViewingEdits(method, name, password, token)
        require(name.isNotBlank() && edits.validatedName() == name) { "invalid_name" }
        edits.validateSecrets()
        require(method == "tailscale" && !config.optBoolean("public") || password.isNotBlank()) { "password_required" }
        require(method != "zrok" || token.isNotBlank()) { "credential_required" }
    }

    private fun connect(p: Profile, interactive: Boolean) {
        require(enabled) { "viewing_disabled" }
        validate(p.method, p.config)
        stop(p)
        val run = Run(++p.generation).also { p.run = it; it.interactive = interactive }
        p.error = null
        if (p.method == "vdo") { start(p, run); return }
        val provider = CameraTunnelProvider(context, p.method, p.config.getString("name"), store,
            nativeDirectory, nativeLibrary).also { run.provider = it }
        p.phase = "authorizing"
        val accept: (CameraTunnelResult) -> Unit = { result -> execute(p, run) { providerResult(p, run, result) } }
        if (p.authorized || p.config.optBoolean("legacyIdentity")) provider.status(accept)
        else provider.authorize(p.config.optString("token").ifBlank { null }, accept)
    }

    private fun providerResult(p: Profile, run: Run, result: CameraTunnelResult) {
        result.errorCode?.let { throw RuntimeFailure(it) }
        for (address in listOfNotNull(result.address, result.loginUrl)) {
            val uri = URI(address)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null)
        }
        when (result.state) {
            "login_required" -> {
                p.phase = "authorizing"; p.login = result.loginUrl
                if (run.interactive && p.login != null) {
                    p.authorizationRequest = SystemClock.elapsedRealtimeNanos(); run.interactive = false
                }
                owner.schedule({ execute(p, run) {
                    run.provider?.status { next -> execute(p, run) { providerResult(p, run, next) } }
                } }, 2, TimeUnit.SECONDS)
            }
            "authorized" -> {
                p.config.put("authorized", true).put("legacyIdentity", false)
                save(p); p.login = null; p.authorizationRequest = 0
                start(p, run)
            }
            "serving" -> { p.phase = "ready"; p.url = result.address; p.login = null }
            else -> throw RuntimeFailure("not_authorized")
        }
        publish()
    }

    private fun start(p: Profile, run: Run) {
        p.phase = "connecting"; p.login = null
        if (p.method == "vdo") {
            run.publisher = VdoCameraPublisher(context, p.config.getString("name"), p.config.getString("password"),
                object : VdoCameraPublisher.Listener {
                    override fun onReady(viewerUrl: String) = execute(p, run) {
                        p.config.put("authorized", true); save(p)
                        p.url = viewerUrl; p.phase = "ready"
                    }
                    override fun onViewerConnected() = execute(p, run) { connected(p, run) }
                    override fun onViewerDisconnected() = execute(p, run) { disconnected(p, run) }
                    override fun onError(encoderUnavailable: Boolean) = execute(p, run) {
                        throw RuntimeFailure(if (encoderUnavailable) "encoder_unavailable" else "connection_failed")
                    }
                }, { lease.acquire(run.lease) }, { lease.release(run.lease) }).also { it.start() }
        } else {
            val privateProof = if (p.method == "tailscale" && !p.config.optBoolean("public")) randomProof() else null
            val server = CameraViewerServer(p.config.optString("password"), privateProof, object : CameraViewerServer.Stream {
                override fun acquire() = lease.acquire(run.lease)
                override fun connected() = execute(p, run) { connected(p, run) }
                override fun disconnected() = execute(p, run) { disconnected(p, run) }
                override fun status() = p.phase
                override fun unavailableChargeMask(): Int = chargeState?.missingMetrics() ?: 3
                override fun video() = run.encoder?.window
                override fun resource(name: String) = context.assets.open("camera-view/$name").use { it.readBytes() }
            })
            run.server = server
            run.provider!!.start(server.port(), p.method == "zrok" || p.config.optBoolean("public"), privateProof) { result ->
                execute(p, run) { providerResult(p, run, result) }
            }
        }
    }

    private fun connected(p: Profile, run: Run) {
        if (run.viewer) return
        run.viewer = true
        run.deadline = SystemClock.elapsedRealtime() + 30_000
        prepare(p, run)
    }
    private fun prepare(p: Profile, run: Run) {
        if (p.run !== run || !enabled || !run.viewer || run.video) return
        run.preparing = false
        val charge = try { RemoteCameraPreparation.readChargeState(context).also { chargeState = it } }
            catch (_: Exception) { retryPreparation(p, run); return }
        if (charge.isBlocked) { closeVideo(run); p.phase = "low_battery"; return }
        p.phase = "preparing"
        val camera = run.source ?: RemoteCameraSource(context) {
            execute(p, run) { throw RuntimeFailure("camera_unavailable") }
        }.also { run.source = it }
        val ready = try { camera.prepare() } catch (_: Exception) { false }
        if (!ready) { retryPreparation(p, run); return }
        val surface = try { run.publisher?.startVideo() ?: CameraHlsEncoder {
            execute(p, run) { throw RuntimeFailure("encoder_unavailable") }
        }.also { run.encoder = it }.start() } catch (_: Exception) { throw RuntimeFailure("encoder_unavailable") }
        camera.attach(surface, CameraHlsEncoder.WIDTH, CameraHlsEncoder.HEIGHT)
        run.video = true; p.phase = "connected"
    }
    private fun retryPreparation(p: Profile, run: Run) {
        if (SystemClock.elapsedRealtime() >= run.deadline) throw RuntimeFailure("camera_unavailable")
        if (!run.preparing) {
            run.preparing = true
            owner.schedule({ execute(p, run) { prepare(p, run) } }, 500, TimeUnit.MILLISECONDS)
        }
    }
    private fun chargeChanged(charge: CameraChargePolicy.State) {
        for (p in profiles.values) p.run?.let { run ->
            if (run.viewer) {
                if (charge.isBlocked) { closeVideo(run); p.phase = "low_battery" }
                else if (p.phase == "low_battery") { run.deadline = SystemClock.elapsedRealtime() + 30_000; prepare(p, run) }
            }
        }
    }
    private fun disconnected(p: Profile, run: Run) {
        run.viewer = false
        closeVideo(run)
        lease.release(run.lease)
        p.phase = "ready"
    }
    private fun closeVideo(run: Run) {
        run.preparing = false
        val source = run.source
        try { source?.close(); run.source = null }
        finally {
            if (source == null || source.isVideoDetached) {
                run.publisher?.stopVideo(); run.encoder?.close(); run.encoder = null; run.video = false
            }
        }
    }
    private fun stop(p: Profile) {
        val run = p.run
        p.generation++
        if (run != null) {
            run.publisher?.stopAccess()
            run.server?.close(); run.server = null
            run.provider?.close(); run.provider = null
            // Retain ownership on failed detach; admitting another camera owner would hide a leak.
            closeVideo(run)
            p.run = null
            run.publisher?.close()
            lease.release(run.lease)
        }
        p.phase = "idle"; p.url = null; p.login = null; p.authorizationRequest = 0
    }

    private fun revoke(p: Profile) {
        p.config.put("revocationPending", true)
        save(p)
        stop(p)
        p.phase = "revoking"; p.error = null
        if (p.method == "vdo") { clearProfile(p); return }
        val run = Run(++p.generation).also { p.run = it }
        val provider = CameraTunnelProvider(context, p.method, p.config.getString("name"), store,
            nativeDirectory, nativeLibrary).also { run.provider = it }
        provider.revoke { result -> execute(p, run) {
            result.errorCode?.let { throw RuntimeFailure(it) }
            stop(p); clearProfile(p)
        } }
    }
    private fun clearProfile(p: Profile) {
        p.config = JSONObject().put("name", p.config.optString("name")).put("password", "").put("token", "")
            .put("public", false).put("authorized", false).put("revocationPending", false)
            .put("revocationEpoch", p.config.optLong("revocationEpoch") + 1)
        save(p); p.phase = "idle"; p.error = null
    }
    private fun publish() {
        val entries = JSONArray()
        profiles.values.forEach { p -> entries.put(JSONObject().put("transport", p.method)
            .put("name", p.config.optString("name")).put("public", p.config.optBoolean("public"))
            .put("authorized", p.authorized).put("revocationPending", p.pendingRevoke)
            .put("revocationEpoch", p.config.optLong("revocationEpoch"))
            .put("identity", if (p.authorized || p.pendingRevoke) p.config.optString("name") else JSONObject.NULL)
            .put("phase", p.phase).put("error", p.error ?: JSONObject.NULL)
            .put("viewerUrl", p.url ?: JSONObject.NULL).put("authorizationUrl", p.login ?: JSONObject.NULL)
            .put("authorizationRequest", p.authorizationRequest)
            .put("passwordConfigured", p.config.optString("password").isNotEmpty())
            .put("tokenConfigured", p.config.optString("token").isNotEmpty())) }
        latest = JSONObject().put("enabled", enabled).put("profiles", entries).toString()
        listener?.accept(JSONObject(latest))
    }
    private fun execute(p: Profile? = null, run: Run? = null, action: () -> Unit) {
        if (closed) return
        owner.execute {
            if (closed || p != null && run != null && (p.run !== run || p.generation != run.generation)) return@execute
            try { action() }
            catch (failure: Exception) {
                failed(p, failure)
            }
            publish()
        }
    }
    private fun failed(p: Profile?, failure: Exception) {
        val code = ((failure as? RuntimeFailure)?.code ?: if (failure is SecurityException)
            "secure_store_error" else "connection_failed")
            .takeIf { it.matches(Regex("[a-z_]{1,64}")) } ?: "connection_failed"
        Log.w("CameraViewing", "runtime provider=${p?.method ?: "shared"} error=$code type=${failure.javaClass.simpleName}")
        if (p != null) {
            runCatching { stop(p) }
            p.phase = if (p.pendingRevoke) "revoke_pending" else "error"
            p.error = code
        }
    }
    override fun close() {
        if (closed) return
        owner.submit {
            configure(false)
            chargeObservation?.close(); chargeObservation = null
            if (networkRegistered) connectivity.unregisterNetworkCallback(networkCallback)
            networkRegistered = false; closed = true
        }.get(30, TimeUnit.SECONDS)
        owner.shutdown()
    }
    private class RuntimeFailure(val code: String) : Exception()
    companion object {
        private val METHODS = listOf("vdo", "tailscale", "zrok")
        private fun randomProof() = ByteArray(32).also(SecureRandom()::nextBytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
