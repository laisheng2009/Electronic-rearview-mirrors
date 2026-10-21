package com.byd.extend.remote

import ninja.vdo.sdk.core.PasswordContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VdoPasswordTest {
    @Test fun signalingRequiresTheConfiguredPasswordAndRejectsPlaintextDowngrade() {
        val publisher = PasswordContext("camera-password", "vdo.ninja")
        val viewer = PasswordContext("camera-password", "vdo.ninja")
        val description = JSONObject().put("type", "answer").put("sdp", "test-description")
        val envelope = JSONObject()
        viewer.encryptInto(envelope, "description", description)

        assertEquals("test-description", (publisher.decodeField(envelope, "description") as JSONObject).getString("sdp"))
        assertNull(PasswordContext("wrong-password", "vdo.ninja").decodeField(envelope, "description"))
        assertNull(publisher.decodeField(JSONObject().put("description", description), "description"))
        envelope.remove("vector")
        assertNull(publisher.decodeField(envelope, "description"))
    }
}
