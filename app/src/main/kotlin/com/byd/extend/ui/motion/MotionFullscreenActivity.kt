package com.byd.extend.ui.motion

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.byd.extend.AppLanguage
import com.byd.extend.MotionDetectorClient
import com.byd.extend.MotionDetectorProtocol
import com.byd.extend.ui.UiLanguage
import com.byd.extend.ui.UiStrings

/** Full-screen renderer shared by manual preview and the runtime Motion warning. */
class MotionFullscreenActivity : ComponentActivity() {
    private var sessionId: String? = null
    private var runtimeDismissal = false
    private var takeoverNotified = false
    private var activeRuntimeWarning = false
    private var readyNotifiedForSession: String? = null
    private val windowHandler = Handler(Looper.getMainLooper())
    private var windowReporter: ResultReceiver? = null
    private var windowDeadline = 0L
    private val windowTimeout = Runnable {
        if (activeRuntimeWarning && !isFinishing) {
            runtimeDismissal = true
            finish()
        }
    }
    private var renderGeneration by mutableIntStateOf(0)
    private var template by mutableStateOf<MotionTemplate?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = savedInstanceState?.getString(STATE_SESSION) ?: intent.getStringExtra(EXTRA_SESSION)
        handleIntent(intent)
        if (isFinishing) return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        val store = MotionTemplateStore(applicationContext)
        val settings = getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        val language = when (AppLanguage.read(settings)) {
            AppLanguage.UKRAINIAN -> UiLanguage.Ukrainian
            AppLanguage.CHINESE -> UiLanguage.Chinese
            AppLanguage.RUSSIAN -> UiLanguage.Russian
            else -> UiLanguage.English
        }
        val strings = UiStrings(language, this)
        if (!activeRuntimeWarning) {
            Toast.makeText(this, strings.resource(com.byd.extend.R.string.motion_fullscreen_hint), Toast.LENGTH_LONG).show()
        }
        setContent {
            val drawGeneration = renderGeneration
            val drawSession = sessionId
            val renderedCallback: (() -> Unit)? = remember(drawSession, drawGeneration) {
                drawSession?.let { capturedSession ->
                    { reportTemplateReady(capturedSession, drawGeneration) }
                }
            }
            BackHandler { closeByUser() }
            template?.let { active ->
                Box(Modifier.fillMaxSize().background(Color.Black).clickable { closeByUser() }) {
                    MotionTemplateCanvas(
                        active,
                        store,
                        strings,
                        Modifier.fillMaxSize(),
                        readyKey = drawGeneration,
                        onFirstRendered = renderedCallback
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        windowHandler.removeCallbacks(windowTimeout)
        reportWindowClosed()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        // Like the probe, never leave a runtime warning behind another window.
        if (activeRuntimeWarning && !isFinishing && !runtimeDismissal) closeByUser()
    }

    override fun onResume() {
        super.onResume()
        if (activeRuntimeWarning && windowDeadline > 0L
            && SystemClock.elapsedRealtime() >= windowDeadline) windowTimeout.run()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SESSION, sessionId)
        super.onSaveInstanceState(outState)
    }

    private fun handleIntent(next: Intent?) {
        when (next?.action) {
            ACTION_SHOW -> {
                reportWindowClosed()
                windowHandler.removeCallbacks(windowTimeout)
                readyNotifiedForSession = null
                val requestedSession = next.getStringExtra(EXTRA_SESSION)
                val reporter = next.getParcelableExtra<ResultReceiver>(MotionDetectorProtocol.EXTRA_WINDOW_REPORTER)
                if (requestedSession != null
                    && (reporter == null || !MotionDetectorClient.isLiveWarningSession(applicationContext, requestedSession))) {
                    reporter?.send(MotionDetectorProtocol.WINDOW_CLOSED,
                        Bundle().apply { putBoolean(MotionDetectorProtocol.WINDOW_CANCELLED, true) })
                    activeRuntimeWarning = false
                    sessionId = null
                    template = null
                    finish()
                    return
                }
                sessionId = requestedSession
                runtimeDismissal = false
                takeoverNotified = false
                activeRuntimeWarning = requestedSession != null
                windowReporter = reporter
                if (activeRuntimeWarning) {
                    configureRuntimeWarningWindow()
                    setWindowDeadline(SystemClock.elapsedRealtime() + MotionDetectorProtocol.WINDOW_START_TIMEOUT_MS)
                    val commands = object : ResultReceiver(windowHandler) {
                        override fun onReceiveResult(code: Int, data: Bundle?) {
                            if (windowReporter !== reporter || !activeRuntimeWarning || isFinishing) return
                            when (code) {
                                MotionDetectorProtocol.WINDOW_CLOSE -> {
                                    runtimeDismissal = true
                                    finish()
                                }
                                MotionDetectorProtocol.WINDOW_SET_DEADLINE -> {
                                    val deadline = data?.getLong(MotionDetectorProtocol.WINDOW_DEADLINE) ?: 0L
                                    if (deadline > 0L) setWindowDeadline(deadline)
                                }
                            }
                        }
                    }
                    reporter?.send(MotionDetectorProtocol.WINDOW_READY,
                        Bundle().apply {
                            putParcelable(MotionDetectorProtocol.WINDOW_COMMANDS,
                                MotionDetectorProtocol.remoteReceiver(commands))
                        })
                }
                renderGeneration++
                template = MotionTemplateStore(applicationContext).read().active()
            }
            else -> template = MotionTemplateStore(applicationContext).read().active()
        }
    }

    private fun configureRuntimeWarningWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            or android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }

    private fun closeByUser() {
        takeoverNotified = true
        finish()
    }

    private fun reportTemplateReady(session: String, generation: Int) {
        if (sessionId != session || renderGeneration != generation) return
        if (readyNotifiedForSession == session) return
        if (isFinishing || isDestroyed) return
        val reporter = windowReporter ?: return
        readyNotifiedForSession = session
        setWindowDeadline(SystemClock.elapsedRealtime() + MotionDetectorProtocol.WARNING_MS)
        reporter.send(MotionDetectorProtocol.WINDOW_DRAWN, Bundle.EMPTY)
    }

    private fun setWindowDeadline(deadline: Long) {
        windowDeadline = deadline
        windowHandler.removeCallbacks(windowTimeout)
        windowHandler.postDelayed(windowTimeout, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
    }

    private fun reportWindowClosed() {
        val reporter = windowReporter ?: return
        windowReporter = null
        reporter.send(MotionDetectorProtocol.WINDOW_CLOSED, Bundle().apply {
            putBoolean(MotionDetectorProtocol.WINDOW_CANCELLED, takeoverNotified || !runtimeDismissal)
        })
    }

    companion object {
        private const val ACTION_SHOW = "com.byd.extend.action.SHOW_MOTION_WARNING"
        private const val EXTRA_SESSION = "motion_session_id"
        private const val STATE_SESSION = "motion_saved_session_id"
        private const val SETTINGS_PREFS = "settings"

        @JvmStatic
        fun show(context: Context) {
            val intent = Intent(context, MotionFullscreenActivity::class.java)
                .setAction(ACTION_SHOW)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

    }
}
