package com.byd.extend.ui.motion

import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentDialog
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.byd.extend.MotionCompositionLayout
import com.byd.extend.MotionDirectionIcon
import com.byd.extend.MotionMediaLibrary
import com.byd.extend.R
import com.byd.extend.ui.ActionButton
import com.byd.extend.ui.ChoiceField
import com.byd.extend.ui.UiPalette
import com.byd.extend.ui.UiStrings
import com.byd.extend.ui.icons.MotionPlayerFullscreen
import com.byd.extend.ui.icons.MotionPlayerFullscreenExit
import com.byd.extend.ui.icons.MotionPlayerNext
import com.byd.extend.ui.icons.MotionPlayerPause
import com.byd.extend.ui.icons.MotionPlayerPlay
import com.byd.extend.ui.icons.MotionPlayerPrevious
import com.byd.extend.ui.icons.MotionPlayerRestart
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

private const val MOTION_PLAYER_TAG = "MotionPlayer"
private val motionPlayerSpeeds = listOf(.25f, .5f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MotionPlayerDialog(
    entries: List<MotionMediaLibrary.Entry>,
    initialEntry: MotionMediaLibrary.Entry,
    colors: UiPalette,
    strings: UiStrings,
    locale: Locale,
    onSelectEntry: (MotionMediaLibrary.Entry) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val entry = initialEntry
    val availableEntries = remember(entries) { entries.filter { it.available } }
    val entryIndex = availableEntries.indexOfFirst { it.contentUri == entry.contentUri }
    val hostState = remember(entry.contentUri) { mutableStateOf<MotionTexturePlayer?>(null) }
    var lifecycleActive by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    var isReady by remember(entry.contentUri) { mutableStateOf(false) }
    var position by remember(entry.contentUri) { mutableLongStateOf(0L) }
    var playerDuration by remember(entry.contentUri) {
        mutableLongStateOf(entry.durationMs.coerceIn(1L, Int.MAX_VALUE.toLong()))
    }
    var playing by remember(entry.contentUri) { mutableStateOf(true) }
    var scrubbing by remember(entry.contentUri) { mutableStateOf(false) }
    var selectedSpeed by remember(entry.contentUri) { mutableIntStateOf(2) }
    var speedFailed by remember(entry.contentUri) { mutableStateOf(false) }
    var playbackFailed by remember(entry.contentUri) { mutableStateOf(false) }
    var selectedDirection by rememberSaveable { mutableIntStateOf(-1) }
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    val duration = playerDuration.coerceAtLeast(1L)
    val cameraLabels = listOf(R.string.motion_player_composition, R.string.motion_camera_front,
        R.string.motion_camera_rear, R.string.motion_camera_left, R.string.motion_camera_right)
    val canCrop = remember(entry.cameraOrder?.toList()) {
        (0..3).map { direction ->
            MotionCompositionLayout.cameraRectForDirection(direction, entry.cameraOrder, 1920, 1080) != null
        }
    }
    val activeDirection = if (selectedDirection >= 0 && !canCrop.getOrElse(selectedDirection) { false }) -1
        else if (entry.cameraOrder == null) -1 else selectedDirection
    val seekInteraction = remember(entry.contentUri) { androidx.compose.foundation.interaction.MutableInteractionSource() }

    DisposableEffect(hostState) { onDispose { hostState.value?.release() } }
    DisposableEffect(lifecycleOwner, hostState) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    lifecycleActive = true
                    hostState.value?.setPlaybackAllowed(true)
                }
                Lifecycle.Event.ON_STOP -> {
                    lifecycleActive = false
                    scrubbing = false
                    hostState.value?.setPlaybackAllowed(false)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        hostState.value?.setPlaybackAllowed(lifecycleActive)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(entry.contentUri, scrubbing, playerDuration) {
        while (isActive) {
            val host = hostState.value
            if (host != null) {
                isReady = host.isReady
                if (!scrubbing && !host.isSeeking) {
                    playing = host.isPlaying
                    position = host.currentPositionOrZero().toLong().coerceIn(0L, playerDuration)
                }
            }
            delay(100)
        }
    }

    val parentView = LocalView.current
    val compositionContext = rememberCompositionContext()
    val dialog = remember(context) { ComponentDialog(context, R.style.MotionPlayerWindowTheme) }
    val composeView = remember(dialog, compositionContext) {
        ComposeView(dialog.context).apply {
            setParentCompositionContext(compositionContext)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(dialog)
            setViewTreeSavedStateRegistryOwner(dialog)
            setViewTreeOnBackPressedDispatcherOwner(dialog)
        }
    }
    val latestCancel = rememberUpdatedState { if (fullScreen) fullScreen = false else onClose() }
    val playerWindowState = remember(dialog) { mutableStateOf<MotionPlayerSystemBars?>(null) }
    LaunchedEffect(dialog, fullScreen) {
        val window = dialog.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (fullScreen) {
            playerWindowState.value = MotionPlayerSystemBars.capture(parentView, controller.systemBarsBehavior)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            playerWindowState.value?.restore(controller)
            playerWindowState.value = null
        }
    }

    val playerContent: @Composable () -> Unit = {
        BackHandler { if (fullScreen) fullScreen = false else onClose() }
        val shape = if (fullScreen) RectangleShape else RoundedCornerShape(10.dp)
        Box(Modifier.fillMaxSize().background(if (fullScreen) colors.surface else Color.Black.copy(alpha = .42f))) {
            val panelModifier = if (fullScreen) Modifier.fillMaxSize() else Modifier
                .align(Alignment.Center)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .fillMaxSize(.97f)
            Column(panelModifier.clip(shape).background(colors.surface)
                .then(if (fullScreen) Modifier else Modifier.border(1.dp, colors.borderStrong, shape))
                .padding(if (fullScreen) 0.dp else 14.dp)) {
                if (!fullScreen) {
                    Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(motionRecordingDisplayName(entry.recordedAtMillis), Modifier.weight(1f), color = colors.text,
                            fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        ActionButton(strings.playerText(R.string.motion_close), colors = colors,
                            height = 40.dp, onClick = onClose)
                    }
                }
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!fullScreen) {
                        Column(Modifier.width(128.dp).fillMaxHeight().selectableGroup(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            cameraLabels.forEachIndexed { index, label ->
                                val direction = index - 1
                                val enabled = index == 0 || canCrop.getOrElse(direction) { false }
                                val chosen = activeDirection == direction
                                Column(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(7.dp))
                                    .background(if (chosen) colors.active else colors.panelAlt)
                                    .border(1.dp, if (chosen) colors.accent else colors.border, RoundedCornerShape(7.dp))
                                    .semantics { contentDescription = strings.playerText(label) }
                                    .selectable(chosen, enabled = enabled, role = Role.Tab) { selectedDirection = direction }
                                    .padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center) {
                                    if (direction < 0) MotionCompositionGlyph(colors, Modifier.size(28.dp))
                                    else androidx.compose.foundation.Image(
                                        painter = androidx.compose.ui.res.painterResource(MotionDirectionIcon.drawableFor(direction)),
                                        contentDescription = null, modifier = Modifier.size(28.dp))
                                    Text(strings.playerText(label), color = if (enabled) colors.text else colors.muted,
                                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    key(entry.contentUri) {
                        AndroidView(factory = { viewContext ->
                            MotionTexturePlayer(viewContext, entry.contentUri, entry.cameraOrder,
                                onPrepared = { actualDuration ->
                                    isReady = true
                                    if (actualDuration > 0) playerDuration = actualDuration.toLong()
                                },
                                onSeekSettled = { actualPosition ->
                                    position = actualPosition.toLong()
                                    playing = hostState.value?.isPlaying == true
                                },
                                onCompletion = {
                                    playing = false
                                    position = hostState.value?.currentPositionOrZero()?.toLong() ?: position
                                },
                                onError = { what, extra ->
                                    Log.e(MOTION_PLAYER_TAG, "Playback failed for ${entry.contentUri}: what=$what extra=$extra")
                                    playbackFailed = true
                                    isReady = false
                                    Toast.makeText(context, strings.playerText(R.string.motion_player_error), Toast.LENGTH_LONG).show()
                                }).also { host ->
                                    hostState.value = host
                                    host.setPlaybackAllowed(lifecycleActive)
                                    host.showCrop(activeDirection)
                                }
                        }, modifier = Modifier.weight(1f).fillMaxHeight(), update = { it.showCrop(activeDirection) })
                    }
                }

                Column(Modifier.fillMaxWidth()
                    .then(if (fullScreen) Modifier.background(colors.surface.copy(alpha = .94f)).navigationBarsPadding()
                        else Modifier.background(colors.surface))
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Slider(value = position.toFloat().coerceIn(0f, duration.toFloat()),
                    onValueChange = { value ->
                        if (!scrubbing) {
                            hostState.value?.beginScrubbing()
                            scrubbing = true
                        }
                        val target = value.toLong().coerceIn(0L, duration)
                        position = target
                        hostState.value?.seekTo(target.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    },
                    onValueChangeFinished = {
                        hostState.value?.finishScrubbing(position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        scrubbing = false
                    },
                    valueRange = 0f..duration.toFloat(),
                    enabled = isReady,
                    interactionSource = seekInteraction,
                    modifier = Modifier.fillMaxWidth().height(40.dp).semantics {
                        contentDescription = strings.playerText(R.string.motion_player_seek)
                    },
                    thumb = { Box(Modifier.width(4.dp).height(28.dp).background(colors.text)) },
                    track = {
                        Canvas(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(4.dp))) {
                            drawRect(colors.borderStrong)
                            val durationFloat = duration.toFloat().coerceAtLeast(1f)
                            val inset = 3.dp.toPx()
                            entry.markers.filter { (it.level == 1 || it.level == 2) && it.positionMs < duration }
                                .sortedBy { it.level }.forEach { marker ->
                                    val startMs = marker.positionMs.coerceIn(0L, duration)
                                    val intervalMs = marker.durationMs.coerceAtLeast(0L).coerceAtMost(duration - startMs)
                                    val startX = size.width * (startMs.toFloat() / durationFloat)
                                    val intervalWidth = size.width * (intervalMs.toFloat() / durationFloat)
                                    val minimumWidth = 2.dp.toPx().coerceAtMost(size.width)
                                    val left = startX.coerceAtMost((size.width - minimumWidth).coerceAtLeast(0f))
                                    val bandWidth = maxOf(intervalWidth, minimumWidth).coerceAtMost(size.width - left)
                                    drawRect(if (marker.level == 2) colors.red else colors.yellow,
                                        topLeft = Offset(left, inset), size = Size(bandWidth, size.height - 2f * inset))
                                }
                            val progressX = size.width * (position.toFloat() / durationFloat).coerceIn(0f, 1f)
                            drawRect(Brush.verticalGradient(
                                0f to colors.accent.copy(alpha = .6f),
                                .5f to colors.accent.copy(alpha = .12f),
                                1f to colors.accent.copy(alpha = .6f),
                                startY = 0f, endY = size.height,
                            ), size = Size(progressX, size.height))
                            drawLine(colors.accent, Offset.Zero, Offset(progressX, 0f), strokeWidth = 2.dp.toPx())
                            drawLine(colors.accent, Offset(0f, size.height), Offset(progressX, size.height),
                                strokeWidth = 2.dp.toPx())
                        }
                    })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(formatPlayerDuration(position, locale), color = colors.muted, fontSize = 12.sp)
                    Text(formatPlayerDuration(duration, locale), color = colors.muted, fontSize = 12.sp)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    PlayerAction(strings, R.string.motion_player_previous, Icons.Outlined.MotionPlayerPrevious,
                        colors, enabled = isReady && !scrubbing && entryIndex > 0, width = 44.dp) {
                        availableEntries.getOrNull(entryIndex - 1)?.let(onSelectEntry)
                    }
                    PlayerAction(strings, if (playing) R.string.motion_player_pause else R.string.motion_player_play,
                        if (playing) Icons.Outlined.MotionPlayerPause else Icons.Outlined.MotionPlayerPlay,
                        colors, enabled = isReady && !scrubbing, width = 52.dp, primary = true) {
                        playing = hostState.value?.togglePlayback() ?: playing
                    }
                    PlayerAction(strings, R.string.motion_player_next, Icons.Outlined.MotionPlayerNext,
                        colors, enabled = isReady && !scrubbing && entryIndex >= 0 && entryIndex < availableEntries.lastIndex,
                        width = 44.dp) {
                        availableEntries.getOrNull(entryIndex + 1)?.let(onSelectEntry)
                    }
                    PlayerAction(strings, R.string.motion_player_restart, Icons.Outlined.MotionPlayerRestart,
                        colors, enabled = isReady && !scrubbing, width = 44.dp) {
                        if (hostState.value?.restart() == true) {
                            playing = true
                            position = 0L
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    ChoiceField("", motionPlayerSpeeds.map { "×${it.toString().removeSuffix(".0")}" },
                        selectedSpeed, onSelect = { index ->
                            if (hostState.value?.setPlaybackSpeed(motionPlayerSpeeds[index]) == true) {
                                selectedSpeed = index
                                speedFailed = false
                            } else speedFailed = true
                        }, colors = colors,
                        modifier = Modifier.width(112.dp).semantics {
                            contentDescription = strings.playerText(R.string.motion_player_speed)
                        }, enabled = isReady, hudCompact = true)
                    PlayerAction(strings, if (fullScreen) R.string.motion_player_windowed else R.string.motion_player_fullscreen,
                        if (fullScreen) Icons.Outlined.MotionPlayerFullscreenExit else Icons.Outlined.MotionPlayerFullscreen,
                        colors, enabled = true, width = 44.dp) { fullScreen = !fullScreen }
                }
                if (speedFailed) Text(strings.playerText(R.string.motion_player_speed_error),
                    color = colors.yellow, fontSize = 12.sp)
                if (playbackFailed) Text(strings.playerText(R.string.motion_player_error),
                    color = colors.yellow, fontSize = 12.sp)
                }
            }
        }
    }

    val currentPlayerContent = rememberUpdatedState(playerContent)
    DisposableEffect(dialog, composeView) {
        composeView.setContent {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides dialog) {
                currentPlayerContent.value.invoke()
            }
        }
        dialog.setContentView(composeView, ViewGroup.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT))
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { latestCancel.value.invoke() }
        dialog.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        onDispose {
            dialog.setOnCancelListener(null)
            dialog.window?.let { window ->
                playerWindowState.value?.restore(WindowCompat.getInsetsController(window, window.decorView))
                playerWindowState.value = null
            }
            dialog.dismiss()
        }
    }
}

private data class MotionPlayerSystemBars(
    val behavior: Int,
    val statusVisible: Boolean,
    val navigationVisible: Boolean,
) {
    fun restore(controller: WindowInsetsControllerCompat) {
        controller.systemBarsBehavior = behavior
        if (statusVisible) controller.show(WindowInsetsCompat.Type.statusBars())
        else controller.hide(WindowInsetsCompat.Type.statusBars())
        if (navigationVisible) controller.show(WindowInsetsCompat.Type.navigationBars())
        else controller.hide(WindowInsetsCompat.Type.navigationBars())
    }

    companion object {
        fun capture(view: View, behavior: Int): MotionPlayerSystemBars {
            val insets = ViewCompat.getRootWindowInsets(view)
            val legacyFlags = view.rootView.systemUiVisibility
            return MotionPlayerSystemBars(
                behavior,
                // If Insets are not attached yet, preserve the explicit legacy visibility flags.
                insets?.isVisible(WindowInsetsCompat.Type.statusBars())
                    ?: (legacyFlags and View.SYSTEM_UI_FLAG_FULLSCREEN == 0),
                insets?.isVisible(WindowInsetsCompat.Type.navigationBars())
                    ?: (legacyFlags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0),
            )
        }
    }
}

@Composable
private fun PlayerAction(
    strings: UiStrings,
    label: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    colors: UiPalette,
    enabled: Boolean,
    width: androidx.compose.ui.unit.Dp,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    ActionButton("", colors, Modifier.width(width).semantics {
        contentDescription = strings.playerText(label)
    }, icon = icon, primary = primary, enabled = enabled, height = 42.dp, onClick = onClick)
}

@Composable
private fun MotionCompositionGlyph(colors: UiPalette, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val gap = size.minDimension * .12f
        val tile = (size.minDimension - gap) / 2f
        for (row in 0..1) for (column in 0..1) {
            drawRoundRect(colors.text, Offset(column * (tile + gap), row * (tile + gap)), Size(tile, tile),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(tile * .12f))
        }
    }
}

private fun UiStrings.playerText(id: Int): String = resource(id)

private fun formatPlayerDuration(durationMs: Long, locale: Locale): String {
    val seconds = (durationMs / 1000).coerceAtLeast(0)
    return String.format(locale, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}
