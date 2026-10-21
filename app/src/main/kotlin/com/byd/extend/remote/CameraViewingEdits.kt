package com.byd.extend.remote

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

    companion object {
        fun newName(transport: String): String =
            (if (transport == "vdo") "byd_" else "byd-") +
                UUID.randomUUID().toString().replace("-", "").take(16)

        fun vdoViewerUrl(streamId: String): String {
            require(streamId.isNotEmpty())
            require(CameraViewingEdits("vdo", streamId).validatedName() == streamId)
            // Prompt in the browser; never put the viewer secret into copy/QR URLs.
            return "https://vdo.ninja/?view=$streamId&password"
        }
    }
}
