package com.byd.extend.remote

import java.net.URI
import ninja.vdo.sdk.core.Sanitize
import org.junit.Assert.*
import org.junit.Test

class CameraViewingEditsTest {
    @Test fun namesAreValidatedWithoutReplacingInvalidInput() {
        assertEquals("my-car", CameraViewingEdits("tailscale", " my-car ").validatedName())
        assertEquals("Camera_A", CameraViewingEdits("vdo", "Camera_A").validatedName())
        assertNull(CameraViewingEdits("zrok").validatedName())
        for (name in listOf("Camera_A", "Z".repeat(64), CameraViewingEdits.newName("vdo"))) {
            assertEquals(name, CameraViewingEdits("vdo", name).validatedName())
            assertEquals(name, Sanitize.streamId(name))
        }
        for (name in listOf("byd-old-id", "a".repeat(65), "Camera A", "Камера")) {
            val edits = CameraViewingEdits("vdo", name)
            assertThrows(IllegalArgumentException::class.java) { edits.validatedName() }
            assertEquals(name, edits.streamId)
        }
        for (provider in listOf("tailscale", "zrok")) {
            val generated = CameraViewingEdits.newName(provider)
            assertEquals(generated, CameraViewingEdits(provider, generated).validatedName())
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
        CameraViewingEdits("vdo", password = "A1".repeat(24) + "z").validateSecrets()
        for (password in listOf("x".repeat(50), "a-b", "a b", "пароль")) {
            val invalid = CameraViewingEdits("vdo", password = password)
            assertThrows(IllegalArgumentException::class.java) { invalid.validateSecrets() }
            assertEquals(password, invalid.password)
        }
        for (provider in listOf("tailscale", "zrok")) {
            CameraViewingEdits(provider, password = "a !".repeat(341) + "z").validateSecrets()
            assertThrows(IllegalArgumentException::class.java) {
                CameraViewingEdits(provider, password = "x".repeat(1025)).validateSecrets()
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CameraViewingEdits("zrok", token = "x".repeat(4097)).validateSecrets()
        }
    }

    @Test fun viewerLinkKeepsTheSdkIdAndRequestsPasswordWithoutExposingIt() {
        val name = CameraViewingEdits.newName("vdo")
        val link = URI(CameraViewingEdits.vdoViewerUrl(name))
        assertEquals("https", link.scheme)
        assertEquals("vdo.ninja", link.host)
        assertNull(link.userInfo)
        val query = link.rawQuery.split('&').associate { parameter ->
            val parts = parameter.split('=', limit = 2)
            parts.first() to parts.getOrElse(1) { "" }
        }
        assertEquals(mapOf("view" to Sanitize.streamId(name), "password" to ""), query)
        assertThrows(IllegalArgumentException::class.java) {
            CameraViewingEdits.vdoViewerUrl("old-invalid-id")
        }
    }
}
