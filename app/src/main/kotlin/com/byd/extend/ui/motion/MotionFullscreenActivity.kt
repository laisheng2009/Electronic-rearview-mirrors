package com.byd.extend.ui.motion

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
import com.byd.extend.ui.UiLanguage
import com.byd.extend.ui.UiStrings
import com.byd.extend.ui.UiTheme
import com.byd.extend.ui.palette
import java.lang.ref.WeakReference

/** Full-screen renderer shared by manual preview and the runtime Motion warning. */
class MotionFullscreenActivity : ComponentActivity() {
    private var sessionId: String? = null
    private var runtimeDismissal = false
    private var takeoverNotified = false
    private var activeRuntimeWarning = false
    private var readyNotifiedForSession: String? = null
    private var renderGeneration by mutableIntStateOf(0)
    private var template by mutableStateOf<MotionTemplate?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeActivity = WeakReference(this)
        sessionId = savedInstanceState?.getString(STATE_SESSION) ?: intent.getStringExtra(EXTRA_SESSION)
        handleIntent(intent)
        if (isFinishing || intent?.action == ACTION_DISMISS) {
            finish()
            return
        }
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
        val colors = palette(if (settings.getBoolean("ui_dark_theme", true)) UiTheme.Dark else UiTheme.Light)
        val strings = UiStrings(language, this)
        Toast.makeText(this, strings.resource(com.byd.extend.R.string.motion_fullscreen_hint), Toast.LENGTH_LONG).show()
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
        activeActivity = WeakReference(this)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        if (activeActivity?.get() === this) activeActivity = null
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val activeSession = sessionId ?: return
        if (!activeRuntimeWarning || readyNotifiedForSession != activeSession
            || runtimeDismissal || takeoverNotified) return
        MotionDetectorClient.dismissWarning(applicationContext, activeSession)
        takeoverNotified = true
        activeRuntimeWarning = false
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SESSION, sessionId)
        super.onSaveInstanceState(outState)
    }

    private fun handleIntent(next: Intent?) {
        when (next?.action) {
            ACTION_SHOW -> {
                readyNotifiedForSession = null
                val requestedSession = next.getStringExtra(EXTRA_SESSION)
                if (requestedSession != null
                    && !MotionDetectorClient.isLiveWarningSession(applicationContext, requestedSession)) {
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
                if (activeRuntimeWarning) configureRuntimeWarningWindow()
                renderGeneration++
                template = MotionTemplateStore(applicationContext).read().active()
            }
            ACTION_DISMISS -> {
                val requestedSession = next.getStringExtra(EXTRA_SESSION)
                if (activeRuntimeWarning && requestedSession != null && requestedSession == sessionId) {
                    runtimeDismissal = true
                    activeRuntimeWarning = false
                    finish()
                }
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
        if (!takeoverNotified) {
            sessionId?.let { MotionDetectorClient.dismissWarning(applicationContext, it) }
            takeoverNotified = true
        }
        finish()
    }

    private fun reportTemplateReady(session: String, generation: Int) {
        if (sessionId != session || renderGeneration != generation) return
        if (readyNotifiedForSession == session) return
        if (isFinishing || isDestroyed) return
        if (MotionDetectorClient.warningWindowReady(applicationContext, session))
            readyNotifiedForSession = session
    }

    companion object {
        private const val ACTION_SHOW = "com.byd.extend.action.SHOW_MOTION_WARNING"
        private const val ACTION_DISMISS = "com.byd.extend.action.DISMISS_MOTION_WARNING"
        private const val EXTRA_SESSION = "motion_session_id"
        private const val STATE_SESSION = "motion_saved_session_id"
        private const val SETTINGS_PREFS = "settings"
        @Volatile private var activeActivity: WeakReference<MotionFullscreenActivity>? = null

        @JvmStatic
        fun show(context: Context, sessionId: String? = null) {
            val intent = Intent(context, MotionFullscreenActivity::class.java)
                .setAction(ACTION_SHOW)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_SESSION, sessionId)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        @JvmStatic
        fun dismiss(context: Context, sessionId: String) {
            val activity = activeActivity?.get() ?: return
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed && activity.sessionId == sessionId) {
                    activity.runtimeDismissal = true
                    activity.finish()
                }
            }
        }
    }
}
