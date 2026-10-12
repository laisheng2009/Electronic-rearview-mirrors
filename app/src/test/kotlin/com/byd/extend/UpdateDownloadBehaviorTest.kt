package com.byd.extend

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.CancellationSignal
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowSettings
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [29], application = Application::class, qualifiers = "w1600dp-h1000dp-mdpi",
    shadows = [AndroidBehaviorHarness.CameraBoundary::class, AndroidBehaviorHarness.AudioAssetsBoundary::class,
        UpdateDownloadBehaviorTest.DownloadBoundary::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class UpdateDownloadBehaviorTest {
    @get:Rule val ui = createEmptyComposeRule()

    @Before fun resetDownloads() {
        DownloadBoundary.requests.clear()
        DownloadBoundary.installs = 0
        UpdateHintRuntime::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    @Test fun closingModalKeepsDownloadButNeverInstallsInBackground() {
        val app = RuntimeEnvironment.getApplication()
        AndroidBehaviorHarness.settings(app)
        InstallationReminder(app).acknowledge()
        ShadowSettings.setCanDrawOverlays(true)
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = Robolectric.buildActivity(CameraProbeActivity::class.java).setup().visible()
        val runtime = UpdateHintRuntime.get(app)
        try {
            activity.windowFocusChanged(true)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
            awaitUi { LocalAdbClient.AUTHORIZATION.foregroundSnapshot()?.isActive == false }
            val notes = "Camera recovery and audio routing improvements"
            val first = AppUpdateManager.UpdateInfo("9.0.0", "https://github.com/test/fixture.apk", notes)
            assertTrue(runtime.startDownload(first))
            AndroidBehaviorHarness.await("download worker did not start") { DownloadBoundary.requests.size == 1 }
            val request = DownloadBoundary.requests[0]
            request.progress.onProgress(45)
            ui.waitForIdle()
            ui.onNodeWithText(notes, substring = true).assertIsDisplayed()
            assertEquals(45, runtime.downloadOperation().progress)
            ui.onNodeWithText("Close", useUnmergedTree = true).performClick()
            ui.waitForIdle()
            assertFalse(runtime.downloadOperation().dialogVisible)
            assertEquals(UpdateHintRuntime.DownloadPhase.DOWNLOADING, runtime.downloadOperation().phase)
            assertEquals(1L, request.finish.count)
            assertFalse("reopening must reuse the owned download", runtime.startDownload(first))
            ui.waitForIdle()
            ui.onNodeWithText(notes, substring = true).assertIsDisplayed()
            ui.onNodeWithText("Close", useUnmergedTree = true).performClick()
            request.finish.countDown()
            awaitUi { runtime.downloadOperation().phase == UpdateHintRuntime.DownloadPhase.READY }
            assertEquals(0, DownloadBoundary.installs)
            runtime.showDownloadOperation()
            ui.waitForIdle()
            assertEquals("a hidden completion must not install when reopened", 0, DownloadBoundary.installs)
            ui.onNodeWithText(notes, substring = true).assertIsDisplayed()
            ui.onNodeWithText("Close", useUnmergedTree = true).performClick()

            val next = AppUpdateManager.UpdateInfo("9.0.1", first.downloadUrl, "Newer release")
            assertTrue(runtime.startDownload(next))
            AndroidBehaviorHarness.await("second download did not start") { DownloadBoundary.requests.size == 2 }
            request.progress.onProgress(99)
            AndroidBehaviorHarness.call(runtime, "finishDownload", request.cancellation, File(app.cacheDir, "old.apk"), null)
            ui.waitForIdle()
            assertEquals(next.resultId, runtime.downloadOperation().info.resultId)
            assertEquals(0, runtime.downloadOperation().progress)
            assertEquals(UpdateHintRuntime.DownloadPhase.DOWNLOADING, runtime.downloadOperation().phase)
            ui.onNodeWithText("Close", useUnmergedTree = true).performClick()
            DownloadBoundary.requests[1].finish.countDown()
            awaitUi { runtime.downloadOperation().phase == UpdateHintRuntime.DownloadPhase.READY }
            assertEquals(0, DownloadBoundary.installs)
        } finally {
            DownloadBoundary.requests.forEach { it.finish.countDown() }
            activity.pause().stop().destroy()
            LocalAdbClient.AUTHORIZATION.cancelForeground()
            LocalAdbClient.setSocketFactoryForTest(null)
        }
    }

    @Test fun cancelKeepsModalAndSameReleaseRetryRejectsOldCompletion() {
        val app = RuntimeEnvironment.getApplication()
        AndroidBehaviorHarness.settings(app)
        InstallationReminder(app).acknowledge()
        ShadowSettings.setCanDrawOverlays(true)
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = Robolectric.buildActivity(CameraProbeActivity::class.java).setup().visible()
        val runtime = UpdateHintRuntime.get(app)
        try {
            activity.windowFocusChanged(true)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
            awaitUi { LocalAdbClient.AUTHORIZATION.foregroundSnapshot()?.isActive == false }
            val info = AppUpdateManager.UpdateInfo("9.0.0", "https://github.com/test/fixture.apk", "Keep these notes")
            runtime.startDownload(info)
            AndroidBehaviorHarness.await("download did not start") { DownloadBoundary.requests.size == 1 }
            val first = DownloadBoundary.requests[0]
            first.progress.onProgress(45)
            ui.waitForIdle()
            ui.onNodeWithText("Cancel", useUnmergedTree = true).assertIsEnabled().performClick()
            ui.waitForIdle()
            assertTrue(first.cancellation.isCanceled)
            assertTrue(runtime.downloadOperation().dialogVisible)
            assertEquals(UpdateHintRuntime.DownloadPhase.CANCELLED, runtime.downloadOperation().phase)
            assertEquals(0, runtime.downloadOperation().progress)
            ui.onNodeWithText("Keep these notes", substring = true).assertIsDisplayed()
            ui.onNode(hasText("Update") and hasClickAction()).performClick()
            ui.waitForIdle()
            first.progress.onProgress(100)
            first.finish.countDown() // Simulate completion already in flight when Cancel was pressed.
            AndroidBehaviorHarness.await("retry did not start") { DownloadBoundary.requests.size == 2 }
            awaitUi { runtime.downloadOperation().phase == UpdateHintRuntime.DownloadPhase.DOWNLOADING }
            assertEquals(0, runtime.downloadOperation().progress)
            assertEquals(0, DownloadBoundary.installs)
            ui.onNodeWithText("Cancel", useUnmergedTree = true).assertIsEnabled()
            DownloadBoundary.requests[1].finish.countDown()
            awaitUi { DownloadBoundary.installs == 1 }
            assertEquals(UpdateHintRuntime.DownloadPhase.READY, runtime.downloadOperation().phase)
            val later = AppUpdateManager.UpdateInfo("9.0.1", info.downloadUrl, "Later update")
            runtime.startDownload(later)
            AndroidBehaviorHarness.await("later download did not start") { DownloadBoundary.requests.size == 3 }
            ui.waitForIdle()
            ui.onNodeWithText("Cancel", useUnmergedTree = true).performClick()
            ui.waitForIdle()
            ui.onNodeWithText("Close", useUnmergedTree = true).performClick()
            ui.waitForIdle()
            assertNull("closing a cancelled offer must allow a fresh update check", runtime.downloadOperation())
            assertEquals(1, DownloadBoundary.installs)
        } finally {
            DownloadBoundary.requests.forEach { it.finish.countDown() }
            activity.pause().stop().destroy()
            LocalAdbClient.AUTHORIZATION.cancelForeground()
            LocalAdbClient.setSocketFactoryForTest(null)
        }
    }

    private fun awaitUi(done: () -> Boolean) {
        AndroidBehaviorHarness.await("download callback did not reach the Activity") {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            done()
        }
        ui.waitForIdle()
    }

    // Only network/file verification and installer launch are external; ownership and UI stay real.
    @Implements(AppUpdateManager::class)
    class DownloadBoundary {
        class Request(val progress: AppUpdateManager.ProgressListener,
                val cancellation: CancellationSignal) { val finish = CountDownLatch(1) }
        companion object {
            val requests = CopyOnWriteArrayList<Request>()
            var installs = 0
        }
        @Implementation protected fun downloadAndVerify(context: Context, info: AppUpdateManager.UpdateInfo,
                listener: AppUpdateManager.ProgressListener, cancellation: CancellationSignal): File {
            val request = Request(listener, cancellation)
            requests.add(request)
            check(request.finish.await(10, TimeUnit.SECONDS)) { "test did not release download" }
            return File(context.cacheDir, "${info.resultId}.apk")
        }
        @Implementation protected fun install(context: Context, info: AppUpdateManager.UpdateInfo, file: File) {
            installs++
        }
    }
}
