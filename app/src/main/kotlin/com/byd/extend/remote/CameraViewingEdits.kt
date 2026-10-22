package com.byd.extend.remote

import androidx.annotation.StringRes
import com.byd.extend.R
import java.util.UUID

/** A snapshot of one provider's edits; null secrets mean unchanged, not deleted. */
class CameraViewingEdits(
    val transport: String,
    val streamId: String? = null,
    val password: String? = null,
    val token: String? = null,
) {
    fun validatedName(): String? {
        require(transport in setOf("vdo", "tailscale", "zrok"))
        val name = streamId?.trim() ?: return null
        val pattern = if (transport == "vdo") "[A-Za-z0-9_]{1,64}"
            else "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
        require(name.isEmpty() || name.matches(Regex(pattern)))
        return name
    }

    fun validateSecrets() {
        validatePassword()
        validateToken()
    }

    fun validatePassword() {
        require(password == null || password.isEmpty() ||
            if (transport == "vdo") password.matches(Regex("[A-Za-z0-9]{1,49}"))
            else password.length <= 1024)
    }

    fun validateToken() {
        require(token == null || token.length <= 4096)
    }

    @StringRes
    fun authorizationError(tailscalePublic: Boolean = false): Int? {
        val name = try { validatedName() }
        catch (_: IllegalArgumentException) {
            return if (transport == "vdo") R.string.cameras_vdo_id_hint else R.string.cameras_error_name
        }
        if (name.isNullOrBlank()) return R.string.cameras_error_name_required

        val passwordRequired = transport == "vdo" || transport == "zrok" ||
            (transport == "tailscale" && tailscalePublic)
        if (passwordRequired && password.isNullOrBlank()) return R.string.cameras_error_viewer_password_required
        try { validatePassword() }
        catch (_: IllegalArgumentException) {
            return if (transport == "vdo") R.string.cameras_vdo_password_hint
            else R.string.cameras_error_password_length
        }

        if (transport == "zrok" && token.isNullOrBlank()) return R.string.cameras_error_token_required
        try { validateToken() }
        catch (_: IllegalArgumentException) { return R.string.cameras_error_token_length }
        return null
    }

    companion object {
        fun newName(transport: String): String =
            (if (transport == "vdo") "byd_" else "byd-") +
                UUID.randomUUID().toString().replace("-", "").take(16)

        fun vdoViewerUrl(streamId: String): String {
            require(streamId.isNotEmpty())
            require(CameraViewingEdits("vdo", streamId).validatedName() == streamId)
            // Prompt in the browser; never put the viewer secret into copy/QR URLs.
            return "https://vdo.ninja/?view=$streamId&password&hangupbutton"
        }
    }
}
