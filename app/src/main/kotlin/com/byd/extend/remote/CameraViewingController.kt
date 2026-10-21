package com.byd.extend.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.byd.extend.CameraChargeSettings
import com.byd.extend.CameraViewingShellClient
import com.byd.extend.MotionRuntime
import com.byd.extend.R
import com.byd.extend.remote.security.KeystoreSecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors

data class CameraViewingConfig(val enabled: Boolean = false, val transport: String = "vdo",
    val tailscalePublic: Boolean = false, val streamId: String = "")
data class CameraViewingState(val config: CameraViewingConfig, val phase: String = "idle",
    val viewerUrl: String? = null, val authorizationUrl: String? = null, val error: String? = null,
    val passwordConfigured: Boolean = false, val tokenConfigured: Boolean = false,
    val authorizationRequest: Long = 0, val authorized: Boolean = false,
    val revocationPending: Boolean = false, val identity: String? = null,
    val busy: Boolean = false, val fieldError: String? = null)

/** UI/draft facade; providers and camera lifetimes belong to the independent shell process. */
class CameraViewingController private constructor(private val context: Context) {
    private val preferences = context.getSharedPreferences("camera_viewing", Context.MODE_PRIVATE)
    private val secrets by lazy { KeystoreSecretStore(context) }
    private val owner = Executors.newSingleThreadExecutor { Thread(it, "viewing-app-client") }
    private val backend = Executors.newSingleThreadExecutor { Thread(it, "viewing-backend-setup") }
    private var backendSetup: CameraViewingShellClient? = null
    private var backendResetRequested = false
    private var backendError: String? = null
    private val main = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow(CameraViewingState(readConfig()))
    val state: StateFlow<CameraViewingState> = mutable.asStateFlow()
    private var client: CameraViewingShellClient? = null
    private val snapshots = linkedMapOf<String, JSONObject>()
    init { execute { refresh() } }

    private fun selected() = preferences.getString("transport", "vdo")?.takeIf { it in METHODS } ?: "vdo"
    private fun draftName(method: String) = preferences.getString("draft_${method}_name",
        preferences.getString("${method}_name", "")) ?: ""
    private fun publicMode() = preferences.getBoolean("draft_tailscale_public", preferences.getBoolean("tailscale_public", false))
    private fun readConfig() = selected().let { CameraViewingConfig(
        CameraChargeSettings.isRemoteViewingEnabled(context), it, publicMode(), draftName(it)) }
    private fun secret(method: String, field: String): String {
        val bytes = secrets.get("draft.camera.$method.$field") ?: secrets.get("camera.$method.$field") ?: return ""
        return try { String(bytes, Charsets.UTF_8) } finally { bytes.fill(0) }
    }
    fun getPassword() = secret(selected(), "password")
    fun getToken() = secret(selected(), "token")
    private fun shadow(method: String, field: String) = preferences.getBoolean("${method}_$field", false)
    private fun locked(method: String) = snapshots[method]?.let {
        it.optBoolean("authorized") || it.optBoolean("revocationPending")
    } ?: (shadow(method, "authorized") || shadow(method, "revocationPending"))

    private fun refresh() {
        val config = readConfig()
        val method = config.transport
        val current = snapshots[method]
        val authorized = current?.optBoolean("authorized") ?: shadow(method, "authorized")
        val pending = current?.optBoolean("revocationPending") ?: shadow(method, "revocationPending")
        val phase = current?.optString("phase", "idle") ?: "idle"
        mutable.value = CameraViewingState(
            config = if ((authorized || pending) && current != null) config.copy(
                streamId = current.optString("name"), tailscalePublic = current.optBoolean("public")) else config,
            phase = phase, viewerUrl = current?.nullable("viewerUrl"),
            authorizationUrl = current?.nullable("authorizationUrl"),
            error = (current?.nullable("error") ?: backendError)?.let { context.getString(errorResource(it)) },
            passwordConfigured = secret(method, "password").isNotEmpty(),
            tokenConfigured = secret(method, "token").isNotEmpty(),
            authorizationRequest = current?.optLong("authorizationRequest") ?: 0,
            authorized = authorized, revocationPending = pending,
            identity = current?.nullable("identity") ?: if (authorized || pending)
                preferences.getString("${method}_identity", null) else null,
            busy = phase in setOf("authorizing", "connecting", "revoking"))
    }

    /** Focus loss, keyboard dismissal and provider selection commit drafts only. */
    fun update(edits: CameraViewingEdits, enabled: Boolean? = null, transport: String? = null,
               tailscalePublic: Boolean? = null, authorize: Boolean = false, regenerate: Boolean = false,
               onSaved: () -> Unit = {}) = execute {
        require(edits.transport in METHODS && (transport == null || transport in METHODS))
        val method = edits.transport
        val wasLocked = locked(method)
        val editor = preferences.edit()
        if (!wasLocked) {
            edits.streamId?.let { editor.putString("draft_${method}_name", it) }
            if (regenerate) editor.putString("draft_${method}_name", CameraViewingEdits.newName(method))
            tailscalePublic?.let { editor.putBoolean("draft_tailscale_public", it) }
            for ((field, value) in listOf("password" to edits.password, "token" to edits.token)) if (value != null) {
                val bytes = value.toByteArray()
                try { secrets.put("draft.camera.$method.$field", bytes) } finally { bytes.fill(0) }
            }
        }
        transport?.let { editor.putString("transport", it) }
        check(editor.commit())
        if (enabled != null) MotionRuntime.setRemoteViewingEnabled(context, enabled)
        refresh()
        main.post(onSaved)
        if (enabled == true) {
            accept(ensureClient().request("configure", JSONObject().put("enabled", true)))
            client?.let { prepareBackend(it) }
        }
        else if (enabled == false && preferences.getBoolean("helper_provisioned", false))
            accept(ensureClient(false).request("configure", JSONObject().put("enabled", false)))
        if (authorize) {
            val payload = profilePayload(method)
            if (!wasLocked) {
                val authorizationEdits = CameraViewingEdits(method, payload.getString("name"),
                    payload.getString("password"), payload.getString("token"))
                val validation = authorizationEdits.authorizationError(payload.getBoolean("public"))
                if (validation != null) {
                    mutable.value = state.value.copy(fieldError = context.getString(validation))
                    return@execute
                }
                val name = checkNotNull(authorizationEdits.validatedName())
                payload.put("name", name)
                check(preferences.edit().putString("draft_${method}_name", name).commit())
                refresh()
            }
            accept(ensureClient().request(if (wasLocked) "reconnect" else "authorize",
                JSONObject().put("transport", method).put("profile", payload)))
            client?.let { prepareBackend(it) }
        }
    }

    private fun profilePayload(method: String) = JSONObject().put("name", draftName(method))
        .put("password", secret(method, "password")).put("token", secret(method, "token"))
        .put("public", method == "tailscale" && publicMode())

    private fun ensureClient(restoreEnabled: Boolean = CameraChargeSettings.isRemoteViewingEnabled(context)): CameraViewingShellClient {
        client?.let { if (it.isConnected) return it else { it.close(); client = null } }
        val connected = CameraViewingShellClient(context)
        val observer = object : CameraViewingShellClient.Listener {
            override fun onSnapshot(snapshot: JSONObject) = execute { if (client === connected) accept(snapshot) }
            override fun onHelperDied() = execute {
                if (client === connected) {
                    client = null
                    snapshots.values.forEach { it.put("phase", "error").put("error", "helper_unavailable")
                        .put("viewerUrl", JSONObject.NULL).put("authorizationUrl", JSONObject.NULL) }
                    refresh()
                }
            }
        }
        try {
            connected.ensureConnected()
            connected.setListener(observer)
            val profiles = JSONArray()
            for (method in METHODS) {
                val keyName = "viewing.$method.data_key"
                val key = secrets.get(keyName) ?: ByteArray(32).also { SecureRandom().nextBytes(it); secrets.put(keyName, it) }
                val migration = secrets.snapshot("tunnel.$method.")
                try {
                    val entries = JSONObject()
                    migration.forEach { (name, bytes) -> entries.put(name, Base64.getEncoder().encodeToString(bytes)) }
                    profiles.put(JSONObject().put("transport", method).put("key", Base64.getEncoder().encodeToString(key))
                        .put("migration", entries).put("initial", profilePayload(method)))
                } finally { key.fill(0); migration.values.forEach { it.fill(0) } }
            }
            client = connected
            accept(connected.request("provision", JSONObject().put("profiles", profiles)
                .put("enabled", restoreEnabled)))
            check(preferences.edit().putBoolean("helper_provisioned", true).commit())
            if (restoreEnabled) prepareBackend(connected)
            return connected
        } catch (failure: Exception) {
            if (client === connected) client = null
            connected.close()
            throw failure
        }
    }

    private fun prepareBackend(connected: CameraViewingShellClient, reset: Boolean = false) {
        if (backendSetup === connected) {
            backendResetRequested = backendResetRequested || reset
            return
        }
        backendSetup = connected
        backendResetRequested = false
        backend.execute {
            val failure = runCatching {
                connected.prepareCameraBackend()
                connected.request("reconfigured", JSONObject().put("restartVideo", reset))
            }.exceptionOrNull()
            execute {
                if (backendSetup === connected) backendSetup = null
                if (client !== connected) return@execute
                backendError = if (failure == null) null else "camera_backend_unavailable"
                if (failure != null) Log.w("CameraViewing",
                    "stage=backend_setup type=${failure.javaClass.simpleName}")
                refresh()
                if (backendResetRequested) {
                    backendResetRequested = false
                    prepareBackend(connected, true)
                }
            }
        }
    }

    private fun accept(snapshot: JSONObject) {
        val profiles = snapshot.optJSONArray("profiles") ?: return
        val editor = preferences.edit()
        for (i in 0 until profiles.length()) {
            val item = profiles.getJSONObject(i)
            val method = item.getString("transport")
            require(method in METHODS)
            val authorized = item.optBoolean("authorized")
            val pending = item.optBoolean("revocationPending")
            if (item.optLong("revocationEpoch") > preferences.getLong("${method}_revocationEpoch", 0)) {
                secrets.removePrefix("draft.camera.$method."); secrets.removePrefix("camera.$method.")
                secrets.removePrefix("tunnel.$method.")
                editor.putString("draft_${method}_name", item.optString("name"))
                    .putLong("${method}_revocationEpoch", item.optLong("revocationEpoch"))
            }
            snapshots[method] = JSONObject(item.toString())
            editor.putBoolean("${method}_authorized", authorized).putBoolean("${method}_revocationPending", pending)
                .putString("${method}_identity", item.nullable("identity"))
        }
        check(editor.commit())
        refresh()
    }

    fun runtimeStarted() = execute {
        if (CameraChargeSettings.isRemoteViewingEnabled(context)) prepareBackend(ensureClient())
    }
    fun runtimeStopped() = execute { client?.close(); client = null; backendError = null }
    fun explicitShutdown() = explicitShutdown(Runnable {})
    fun explicitShutdown(onComplete: Runnable) = execute {
        try { (client ?: if (preferences.getBoolean("helper_provisioned", false)) ensureClient(false) else null)
            ?.request("shutdown", JSONObject()) }
        finally { client?.close(); client = null; main.post(onComplete) }
    }
    fun workerReconfigured() = execute { client?.let {
        prepareBackend(it, true)
    } }
    fun authorizationOpened(request: Long) = execute {
        client?.let { accept(it.request("authorization_opened", JSONObject().put("transport", selected()).put("request", request))) }
    }
    fun revoke() = execute { accept(ensureClient().request("revoke", JSONObject().put("transport", selected()))) }

    private fun execute(action: () -> Unit) {
        owner.execute {
            try { action() }
            catch (failure: Exception) {
                Log.w("CameraViewing", "app_operation failed type=${failure.javaClass.simpleName}")
                mutable.value = state.value.copy(busy = false, error = context.getString(
                    if (failure is SecurityException) R.string.cameras_error_secret else R.string.cameras_error_helper))
            }
        }
    }
    companion object {
        private val METHODS = listOf("vdo", "tailscale", "zrok")
        @Volatile private var instance: CameraViewingController? = null
        @JvmStatic fun get(context: Context): CameraViewingController = instance ?: synchronized(this) {
            instance ?: CameraViewingController(context.applicationContext).also { instance = it }
        }
        @JvmStatic fun hasAuthorizedProfiles(context: Context): Boolean {
            val p = context.getSharedPreferences("camera_viewing", Context.MODE_PRIVATE)
            return METHODS.any { p.getBoolean("${it}_authorized", false) || p.getBoolean("${it}_revocationPending", false) }
        }
        private fun JSONObject.nullable(name: String): String? = if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }
        private fun errorResource(code: String) = when (code) {
            "invalid_name" -> R.string.cameras_error_name
            "credential_required" -> R.string.cameras_error_token_required
            "password_required" -> R.string.cameras_error_password_required
            "not_authorized", "login_required", "revoke_required" -> R.string.cameras_error_authorization_required
            "secure_store_error" -> R.string.cameras_error_secret
            "native_unavailable", "runtime_error", "invalid_provider" -> R.string.cameras_error_tunnel_runtime
            "invalid_port", "private_credential_missing", "invalid_request" -> R.string.cameras_error_local_viewer
            "tailscale_https_failed" -> R.string.cameras_error_tailscale_https
            "tailscale_funnel_failed" -> R.string.cameras_error_tailscale_funnel
            "zrok_enable_failed" -> R.string.cameras_error_zrok_enable
            "zrok_share_failed" -> R.string.cameras_error_zrok_share
            "camera_unavailable" -> R.string.cameras_error_camera_unavailable
            "camera_backend_unavailable", "helper_unavailable" -> R.string.cameras_error_helper
            "encoder_unavailable" -> R.string.cameras_error_encoder_unavailable
            else -> R.string.cameras_error_connection
        }
    }
}
