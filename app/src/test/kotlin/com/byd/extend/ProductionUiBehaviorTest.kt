package com.byd.extend

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.provider.Settings
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.byd.extend.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [29], application = Application::class, qualifiers = "w1600dp-h1000dp-mdpi",
    shadows = [AndroidBehaviorHarness.CameraBoundary::class, AndroidBehaviorHarness.AudioAssetsBoundary::class,
        AndroidBehaviorHarness.SecureReadBoundary::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProductionUiBehaviorTest {
    @get:Rule val ui = createEmptyComposeRule()
    private lateinit var activity: ActivityController<CameraProbeActivity>
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("settings", 0)
    private val controller get() = AndroidBehaviorHarness.field(activity.get(), "productionUi") as ProductionUiController

    @Before fun setup() {
        AndroidBehaviorHarness.settings(app)
        newInstallationAcknowledged()
        RuntimeUiSession.clearProcessState()
        ShadowSettings.setCanDrawOverlays(true)
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        activity = Robolectric.buildActivity(CameraProbeActivity::class.java).setup().visible()
        ui.waitForIdle()
    }

    private fun newInstallationAcknowledged() = InstallationReminder(app).acknowledge()

    @After fun cleanup() {
        activity.pause().stop().destroy()
        LocalAdbClient.cancelPendingAuthorization()
        LocalAdbClient.setSocketFactoryForTest(null)
        RuntimeUiSession.clearProcessState()
    }

    private fun switch(label: String) = ui.onNode(
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch) and hasText(label))
    private fun thumb(label: String) = ui.onNode(
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch) and hasParent(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch) and hasAnyDescendant(hasText(label))),
        useUnmergedTree = true)
    private fun click(label: String) = ui.onNode(hasClickAction() and hasText(label))
        .performSemanticsAction(SemanticsActions.OnClick) { it() }
    private fun mirror() = ui.onNode(hasText("Rearview mirror") and
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        .performSemanticsAction(SemanticsActions.OnClick) { it() }

    @Test fun switchesRetainStateAndPositionAcrossNavigation() {
        mirror()
        ui.waitForIdle()
        val labels = listOf("Rearview mirror", "Do not show while panorama is open", "Front camera integration")
        switch(labels[0]).performSemanticsAction(SemanticsActions.OnClick) { it() }
        ui.waitForIdle()
        switch(labels[0]).assertIsOn()
        val bounds = labels.associateWith { switch(it).fetchSemanticsNode().boundsInRoot }
        val thumbBounds = labels.associateWith { thumb(it).fetchSemanticsNode().boundsInRoot }
        val states = labels.associateWith {
            val node = switch(it).fetchSemanticsNode().config
            node[SemanticsProperties.ToggleableState] to node.contains(SemanticsProperties.Disabled)
        }
        val saved = prefs.all.filterKeys { !it.startsWith("ui_") }
        ui.mainClock.autoAdvance = false
        for (tab in listOf("Settings", "Blind-zone cameras", "Reverse cameras")) {
            click(tab)
            ui.mainClock.advanceTimeBy(400)
            mirror()
            var elapsed = 0L
            for (ms in listOf(0L, 16L, 80L, 400L)) {
                ui.mainClock.advanceTimeBy(ms - elapsed)
                elapsed = ms
                switch(labels[0]).assertIsOn().assertIsEnabled()
                for (label in labels) {
                    val node = switch(label).fetchSemanticsNode().config
                    assertEquals("$label state changed at $ms ms", states[label],
                        node[SemanticsProperties.ToggleableState] to node.contains(SemanticsProperties.Disabled))
                    assertEquals("$label row moved at $ms ms", bounds[label], switch(label).fetchSemanticsNode().boundsInRoot)
                    assertEquals("$label switch moved at $ms ms", thumbBounds[label], thumb(label).fetchSemanticsNode().boundsInRoot)
                }
            }
        }
        assertEquals("navigation must not rewrite feature settings", saved, prefs.all.filterKeys { !it.startsWith("ui_") })
    }

    @Test fun engineButtonOnlyIsKeyGatedAndRetainsItsSavedValue() {
        click("AVAS (external speaker)")
        ui.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("avas-engine"))
        val label = "Toggle engine sound only with the button"
        switch(label).assertIsOff().assertIsNotEnabled()
        switch("ENGINE SIMULATION").performSemanticsAction(SemanticsActions.OnClick) { it() }
        switch(label).assertIsOff().assertIsNotEnabled()
        CameraButtonBindings.save(prefs, CameraButtonBindings.Action.AvasEngine,
            CameraButtonBindings.Binding(305, CameraButtonBindings.Press.Single))
        controller.reload()
        ui.waitForIdle()
        switch(label).assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
        switch(label).assertIsOn()
        assertTrue(AvasAudioLibrary(app, prefs).loadConfig().engine.buttonOnly)
        controller.dispatch(BydExtendUiAction.ResetCameraButton(CameraButtonBindings.Action.AvasEngine))
        ui.waitForIdle()
        switch(label).assertIsOn().assertIsNotEnabled()
        assertTrue("removing a key must not erase the saved option", AvasAudioLibrary(app, prefs).loadConfig().engine.buttonOnly)
        switch("ENGINE SIMULATION").performSemanticsAction(SemanticsActions.OnClick) { it() }
        switch(label).assertIsOn().assertIsNotEnabled()
        activity.recreate()
        ui.waitForIdle()
        assertTrue(activity.get().productionAvasState().engine.buttonOnly)
        assertFalse(activity.get().productionAvasState().engine.enabled)
    }

    @Test fun backgroundChangesBecomeVisibleAfterResume() {
        mirror()
        switch("Rearview mirror").assertIsOff()
        activity.pause()
        val worker = Thread {
            prefs.edit().putBoolean(RearviewMirrorSettings.PREF_ENABLED, true).commit()
            // The steering-button/service callback, not a fabricated UI state.
            CameraProbeActivity.publishMirrorSettingsChanged()
        }
        worker.start()
        worker.join(3000)
        assertFalse(worker.isAlive)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        activity.resume()
        ui.waitForIdle()
        assertEquals(RootTab.Mirror, controller.state.activeTab)
        switch("Rearview mirror").assertIsOn()
        prefs.edit().putBoolean(RearviewMirrorSettings.PREF_ENABLED, false).commit()
        CameraProbeActivity.publishMirrorSettingsChanged()
        ui.waitForIdle()
        switch("Rearview mirror").assertIsOff()
    }

    @Test fun readbackUpdatesPillsWithoutAdbGating() {
        fun refresh() {
            AndroidBehaviorHarness.call(activity.get(), "refreshProductionHeader")
            ui.waitForIdle()
        }
        val adbBefore = controller.state.header.adb.tone
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "com.byd.extend/com.byd.extend.WeatherRefreshAccessibilityService")
        Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        Shadows.shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationListenerAccessGranted(
            ComponentName(app, RecoveryNotificationListener::class.java), true)
        refresh()
        ui.onNodeWithText("Permissions: OK").assertIsDisplayed()
        assertEquals(adbBefore, controller.state.header.adb.tone)
        AndroidBehaviorHarness.SecureReadBoundary.unavailable = true
        refresh()
        ui.onNodeWithText("Permissions: Error").assertIsDisplayed()
        assertEquals(adbBefore, controller.state.header.adb.tone)
        AndroidBehaviorHarness.SecureReadBoundary.unavailable = false
        refresh()
        ui.onNodeWithText("Permissions: OK").assertIsDisplayed()
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        prefs.edit().putBoolean(AvasMicrophoneSettings.ENABLED, false).commit()
        refresh()
        ui.onNodeWithText("Permissions: OK").assertIsDisplayed()
        prefs.edit().putBoolean(AvasMicrophoneSettings.ENABLED, true).commit()
        refresh()
        ui.onNodeWithText("Permissions: Error").assertIsDisplayed()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        prefs.edit().putBoolean(WeatherRuntime.PREF_ENABLED, true).commit()
        refresh()
        ui.onNodeWithText("Permissions: OK").assertIsDisplayed()
        assertEquals(StatusTone.Error, controller.state.header.location.tone)
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        refresh()
        ui.onNodeWithText("Permissions: Error").assertIsDisplayed()
        assertEquals(adbBefore, controller.state.header.adb.tone)
    }
}
