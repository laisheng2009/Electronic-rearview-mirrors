package com.byd.extend.remote

import ninja.vdo.sdk.core.PasswordContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VdoPasswordTest {
    @Test fun signalingRequiresTheConfiguredPasswordAndRejectsPlaintextDowngrade() {
        val publisher = PasswordContext("1", "vdo.ninja")
        val viewer = PasswordContext("1", "vdo.ninja")
        val description = JSONObject().put("type", "answer").put("sdp", "test-description")
        val envelope = JSONObject()
        viewer.encryptInto(envelope, "description", description)

        assertEquals("test-description", (publisher.decodeField(envelope, "description") as JSONObject).getString("sdp"))
        val wrongPassword = PasswordContext("wrong-password", "vdo.ninja")
        assertNull(wrongPassword.decodeField(envelope, "description"))
        assertNotNull(runCatching { wrongPassword.decodeFieldOrThrow(envelope, "description") }.exceptionOrNull())
        assertNull(publisher.decodeField(JSONObject().put("description", description), "description"))
        envelope.remove("vector")
        assertNull(publisher.decodeField(envelope, "description"))
        assertNotNull(runCatching { publisher.decodeFieldOrThrow(envelope, "description") }.exceptionOrNull())
    }
}
