package com.byd.extend.remote

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
        val pattern = if (transport == "vdo") "[A-Za-z0-9_-]{1,64}"
            else "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
        require(name.isEmpty() || name.matches(Regex(pattern)))
        return name
    }

    fun validateSecrets() {
        require(password == null || password.length <= 1024)
        require(token == null || token.length <= 4096)
    }
}
