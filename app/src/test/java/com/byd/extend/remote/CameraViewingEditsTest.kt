package com.byd.extend.remote

import org.junit.Assert.*
import org.junit.Test

class CameraViewingEditsTest {
    @Test fun namesAreValidatedWithoutReplacingInvalidInput() {
        assertEquals("my-car", CameraViewingEdits("tailscale", " my-car ").validatedName())
        assertEquals("Camera_A", CameraViewingEdits("vdo", "Camera_A").validatedName())
        assertNull(CameraViewingEdits("zrok").validatedName())
        for (provider in listOf("tailscale", "zrok")) {
            val edits = CameraViewingEdits(provider, "My Car")
            assertThrows(IllegalArgumentException::class.java) { edits.validatedName() }
            assertEquals("My Car", edits.streamId)
        }
    }

    @Test fun secretEditsDistinguishUnchangedFromRemovalAndAcceptTheReportedPassword() {
        assertNull(CameraViewingEdits("vdo").password)
        assertEquals("", CameraViewingEdits("vdo", password = "").password)
        val edits = CameraViewingEdits("vdo", password = "1")
        edits.validateSecrets()
        assertEquals("1", edits.password)
        assertThrows(IllegalArgumentException::class.java) {
            CameraViewingEdits("vdo", password = "x".repeat(1025)).validateSecrets()
        }
        assertThrows(IllegalArgumentException::class.java) {
            CameraViewingEdits("zrok", token = "x".repeat(4097)).validateSecrets()
        }
    }
}
