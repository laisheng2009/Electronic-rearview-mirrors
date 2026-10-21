package com.byd.extend.ui.motion

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.byd.extend.CameraChargePolicy
import com.byd.extend.CameraChargeSettings
import com.byd.extend.CameraChargeTelemetry
import com.byd.extend.R
import com.byd.extend.remote.CameraViewingController
import com.byd.extend.ui.ActionButton
import com.byd.extend.ui.ChoiceField
import com.byd.extend.ui.FormScope
import com.byd.extend.ui.FormSection
import com.byd.extend.ui.ImeDismissalEffect
import com.byd.extend.ui.NumericSetting
import com.byd.extend.ui.Section
import com.byd.extend.ui.StatusPill
import com.byd.extend.ui.StatusTone
import com.byd.extend.ui.StatusUiState
import com.byd.extend.ui.Segmented
import com.byd.extend.ui.SwitchLine
import com.byd.extend.ui.UiPalette
import com.byd.extend.ui.UiStrings
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.awaitCancellation
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun rememberCameraChargeState(context: Context): CameraChargePolicy.State {
    val telemetry = remember(context) { CameraChargeTelemetry.get(context) }
    val state by produceState(initialValue = telemetry.snapshot(), telemetry) {
        val output = this
        val listener = object : CameraChargeTelemetry.Listener {
            override fun onState(next: CameraChargePolicy.State) {
                output.value = next
            }
        }
        val observation = telemetry.observe(listener)
        try {
            awaitCancellation()
        } finally {
            observation.close()
        }
    }
    return state
}

@Composable
internal fun CameraChargeSettingsSection(
    colors: UiPalette,
    strings: UiStrings,
    state: CameraChargePolicy.State,
    socThreshold: Int,
    voltageThreshold: Float,
    onSocThreshold: (Int) -> Unit,
    onVoltageThreshold: (Float) -> Unit,
) {
    Section(strings.motionText(R.string.cameras_battery_title), colors) {
        NumericSetting(
            "SOC", socThreshold.toString(), "%", colors,
            onCommit = { raw -> raw.toIntOrNull()?.let { onSocThreshold(it.coerceIn(10, 90)) } },
            range = 10f..90f, adjustable = true, identity = "camera-charge-soc",
        )
        NumericSetting(
            strings.motionText(R.string.cameras_low_voltage),
            String.format(Locale.US, "%.1f", voltageThreshold),
            strings.motionText(R.string.cameras_volts), colors,
            onCommit = { raw -> raw.toFloatOrNull()?.let { onVoltageThreshold((it * 10f).roundToInt() / 10f) } },
            range = 12f..13f, adjustable = true, adjustmentStep = 0.1f, decimalPlaces = 1,
            identity = "camera-charge-voltage",
        )
        Text(strings.motionText(R.string.cameras_battery_shared), color = colors.muted, fontSize = 13.sp)
        if (state.isBlocked()) {
            Text(strings.motionText(R.string.cameras_charge_paused), color = colors.yellow, fontSize = 13.sp)
        } else if (state.shouldWarnUnavailable()) {
            Text(chargeUnavailableText(strings, state),
                color = colors.yellow, fontSize = 13.sp)
        }
    }
}

@Composable
internal fun FormScope.CameraViewingScreen(
    colors: UiPalette,
    strings: UiStrings,
    chargeState: CameraChargePolicy.State,
): FormScope {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val controller = remember(context) { CameraViewingController.get(context) }
    val state by controller.state.collectAsState()
    var streamIdDraft by remember { mutableStateOf(state.config.streamId) }
    var streamIdDirty by remember { mutableStateOf(false) }
    var passwordDraft by remember { mutableStateOf("") }
    var tokenDraft by remember { mutableStateOf("") }
    var passwordDirty by remember { mutableStateOf(false) }
    var tokenDirty by remember { mutableStateOf(false) }
    var passwordLoaded by remember { mutableStateOf(false) }
    var tokenLoaded by remember { mutableStateOf(false) }
    var revealPassword by remember { mutableStateOf(false) }
    var revealToken by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    val transport = state.config.transport
    val isZrok = transport == "zrok"

    fun clearLocalSecrets() {
        passwordDraft = ""
        tokenDraft = ""
        passwordDirty = false
        tokenDirty = false
        passwordLoaded = false
        tokenLoaded = false
        revealPassword = false
        revealToken = false
    }

    fun commitStreamIdEdit() {
        if (streamIdDirty) {
            controller.setStreamId(streamIdDraft)
            streamIdDirty = false
        }
    }

    fun commitPasswordEdit() {
        if (passwordLoaded && passwordDirty) {
            controller.setPassword(passwordDraft)
            passwordDirty = false
        }
    }

    fun commitTokenEdit() {
        if (tokenLoaded && tokenDirty) {
            controller.setToken(tokenDraft)
            tokenDirty = false
        }
    }

    fun commitEdits() {
        commitStreamIdEdit()
        commitPasswordEdit()
        commitTokenEdit()
    }

    LaunchedEffect(transport, state.config.tailscalePublic) {
        focus.clearFocus()
        streamIdDraft = state.config.streamId
        streamIdDirty = false
        clearLocalSecrets()
        showQr = false
        val needsPassword = transport != "tailscale" || state.config.tailscalePublic
        if (needsPassword) {
            passwordDraft = runCatching { controller.getPassword() }.getOrDefault("")
            passwordLoaded = true
        }
        if (isZrok) {
            tokenDraft = runCatching { controller.getToken() }.getOrDefault("")
            tokenLoaded = true
        } else {
            tokenDraft = ""
            tokenLoaded = true
        }
    }
    LaunchedEffect(state.config.streamId) {
        if (!streamIdDirty) streamIdDraft = state.config.streamId
    }
    DisposableEffect(controller) {
        onDispose {
            commitEdits()
            passwordDraft = ""
            tokenDraft = ""
            passwordDirty = false
            tokenDirty = false
            revealPassword = false
            revealToken = false
        }
    }

    val providerIndex = when (transport) {
        "tailscale" -> 1
        "zrok" -> 2
        else -> 0
    }
    val phase = state.phase
    val blocked = chargeState.isBlocked()
    val hasViewerUrl = state.viewerUrl != null
    val readyToStart = phase == "ready" && !hasViewerUrl
    val active = phase == "preparing" || phase == "connected" || phase == "low_battery" ||
        (phase == "ready" && hasViewerUrl)
    val passwordRequired = transport == "vdo" || transport == "zrok" ||
        (transport == "tailscale" && state.config.tailscalePublic)
    val passwordAvailable = state.passwordConfigured || passwordDraft.isNotBlank()
    val tokenAvailable = transport != "zrok" || state.tokenConfigured || tokenDraft.isNotBlank()
    val phaseStatus = when {
        blocked || phase == "low_battery" -> strings.motionText(R.string.cameras_status_low_battery)
        phase == "authorizing" -> strings.motionText(R.string.cameras_status_authorizing)
        phase == "ready" -> strings.motionText(R.string.cameras_status_ready)
        phase == "preparing" -> strings.motionText(R.string.cameras_status_preparing)
        phase == "connected" -> strings.motionText(R.string.cameras_status_connected)
        phase == "error" -> strings.motionText(R.string.cameras_status_error)
        else -> strings.motionText(R.string.cameras_not_connected)
    }
    val phaseTone = when {
        blocked || phase == "low_battery" || phase == "authorizing" || phase == "preparing" -> StatusTone.Warning
        phase == "connected" -> StatusTone.Ok
        phase == "error" -> StatusTone.Error
        phase == "ready" -> StatusTone.Ok
        else -> StatusTone.Neutral
    }

    FormSection(strings.motionText(R.string.cameras_viewing), colors, key = "cameras-viewing") {
        row("enabled") {
            SwitchLine(strings.motionText(R.string.cameras_allow_viewing),
                strings.motionText(R.string.cameras_on_demand), state.config.enabled,
                { enabled -> commitEdits(); focus.clearFocus(); controller.setEnabled(enabled) }, colors)
        }
        row("method") {
            ChoiceField(strings.motionText(R.string.cameras_connection_method),
                listOf("VDO.Ninja", "Tailscale", "Zrok"), providerIndex, { selected ->
                    commitEdits()
                    focus.clearFocus()
                    clearLocalSecrets()
                    controller.setTransport(listOf("vdo", "tailscale", "zrok")[selected])
                }, colors)
        }
        this
    }

    val providerTitle = when (transport) {
        "tailscale" -> strings.motionText(R.string.cameras_tailscale_title)
        "zrok" -> "Zrok"
        else -> "VDO.Ninja"
    }
    FormSection(providerTitle, colors, key = "cameras-provider-$transport") {
        when (transport) {
            "tailscale" -> {
                row("mode") {
                    Segmented(listOf(strings.motionText(R.string.cameras_private),
                        strings.motionText(R.string.cameras_public)),
                        if (state.config.tailscalePublic) 1 else 0, colors, Modifier.fillMaxWidth()) {
                        commitEdits()
                        focus.clearFocus()
                        clearLocalSecrets()
                        controller.setTailscalePublic(it == 1)
                    }
                }
                row("security") {
                    Text(strings.motionText(if (state.config.tailscalePublic)
                        R.string.cameras_public_security else R.string.cameras_private_security),
                        color = colors.muted, fontSize = 13.sp)
                }
                row("name") {
                    ConnectionField(strings.motionText(R.string.cameras_device_name), streamIdDraft,
                        colors, onValueChange = {
                            streamIdDraft = it
                            streamIdDirty = true
                        }, onCommit = ::commitStreamIdEdit)
                }
                if (state.config.tailscalePublic) row("password") {
                    ConnectionField(strings.motionText(R.string.cameras_viewer_password), passwordDraft,
                        colors, secret = true, revealed = revealPassword, enabled = passwordLoaded,
                        revealDescription = strings.motionText(if (revealPassword) R.string.cameras_secret_hide
                            else R.string.cameras_secret_show),
                        onReveal = { revealPassword = !revealPassword }, onValueChange = {
                            passwordDraft = it
                            passwordDirty = true
                        }, onCommit = ::commitPasswordEdit)
                }
            }
            "zrok" -> {
                row("hint") {
                    Text(strings.motionText(R.string.cameras_zrok_hint), color = colors.muted, fontSize = 13.sp)
                }
                row("token") {
                    ConnectionField(strings.motionText(R.string.cameras_account_token), tokenDraft,
                        colors, secret = true, revealed = revealToken, enabled = tokenLoaded,
                        revealDescription = strings.motionText(if (revealToken) R.string.cameras_secret_hide
                            else R.string.cameras_secret_show),
                        onReveal = { revealToken = !revealToken }, onValueChange = {
                            tokenDraft = it
                            tokenDirty = true
                        }, onCommit = ::commitTokenEdit)
                }
                row("name") {
                    ConnectionField(strings.motionText(R.string.cameras_connection_name), streamIdDraft,
                        colors, onValueChange = {
                            streamIdDraft = it
                            streamIdDirty = true
                        }, onCommit = ::commitStreamIdEdit)
                }
                row("password") {
                    ConnectionField(strings.motionText(R.string.cameras_viewer_password), passwordDraft,
                        colors, secret = true, revealed = revealPassword, enabled = passwordLoaded,
                        revealDescription = strings.motionText(if (revealPassword) R.string.cameras_secret_hide
                            else R.string.cameras_secret_show),
                        onReveal = { revealPassword = !revealPassword }, onValueChange = {
                            passwordDraft = it
                            passwordDirty = true
                        }, onCommit = ::commitPasswordEdit)
                }
            }
            else -> {
                row("hint") {
                    Text(strings.motionText(R.string.cameras_vdo_hint), color = colors.muted, fontSize = 13.sp)
                }
                row("id") {
                    ConnectionField(strings.motionText(R.string.cameras_stream_id), streamIdDraft,
                        colors, onValueChange = {
                            streamIdDraft = it
                            streamIdDirty = true
                        }, onCommit = ::commitStreamIdEdit)
                }
                row("password") {
                    ConnectionField(strings.motionText(R.string.cameras_viewer_password), passwordDraft,
                        colors, secret = true, revealed = revealPassword, enabled = passwordLoaded,
                        revealDescription = strings.motionText(if (revealPassword) R.string.cameras_secret_hide
                            else R.string.cameras_secret_show),
                        onReveal = { revealPassword = !revealPassword }, onValueChange = {
                            passwordDraft = it
                            passwordDirty = true
                        }, onCommit = ::commitPasswordEdit)
                }
            }
        }
        row("provider-actions") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(strings.motionText(if (transport == "vdo") R.string.cameras_regenerate
                    else R.string.cameras_authorize), colors, Modifier.weight(1f),
                    enabled = state.config.enabled && !active && phase != "authorizing" &&
                        (!passwordRequired || passwordAvailable) && tokenAvailable) {
                    commitEdits()
                    focus.clearFocus()
                    controller.authorize()
                }
                if (readyToStart) ActionButton(strings.motionText(R.string.cameras_start), colors,
                    Modifier.weight(1f), primary = true, enabled = state.config.enabled && !blocked &&
                        (!passwordRequired || passwordAvailable) && tokenAvailable) {
                    commitEdits()
                    focus.clearFocus()
                    controller.start()
                }
                if (active) ActionButton(strings.motionText(R.string.cameras_stop), colors,
                    Modifier.weight(1f), destructive = true) {
                    commitEdits()
                    focus.clearFocus()
                    controller.stop()
                }
            }
        }
        this
    }

    FormSection(strings.motionText(R.string.cameras_access), colors, key = "cameras-access") {
        row("status") {
            StatusPill(StatusUiState(phaseStatus, phaseTone, true), phaseStatus, colors)
            state.error?.takeIf { it.isNotBlank() }?.let { Text(it, color = colors.red, fontSize = 13.sp) }
        }
        state.authorizationUrl?.let { authorizationUrl -> row("authorization") {
            ActionButton(strings.motionText(R.string.cameras_open_authorization), colors,
                Modifier.fillMaxWidth(), enabled = state.config.enabled) {
                openAuthorization(context, authorizationUrl, strings)
            }
        } }
        state.viewerUrl?.let { viewerUrl -> row("viewer-url") {
            Text(viewerUrl, color = colors.muted, fontSize = 12.sp)
        } }
        row("link-actions") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(strings.motionText(R.string.cameras_copy_link), colors,
                    Modifier.weight(1f), enabled = !state.viewerUrl.isNullOrBlank()) {
                    commitEdits()
                    state.viewerUrl?.let { copyViewerUrl(context, it, strings) }
                }
                ActionButton(strings.motionText(R.string.cameras_qr), colors, Modifier.weight(.6f),
                    enabled = !state.viewerUrl.isNullOrBlank()) { commitEdits(); showQr = true }
                ActionButton(strings.motionText(R.string.cameras_revoke), colors,
                    Modifier.weight(1f), destructive = true,
                    enabled = state.config.enabled || state.viewerUrl != null || state.phase != "idle" ||
                        state.passwordConfigured || state.tokenConfigured) {
                    commitEdits()
                    focus.clearFocus()
                    controller.revoke()
                    streamIdDraft = ""
                    streamIdDirty = false
                    passwordDraft = ""
                    tokenDraft = ""
                    passwordDirty = false
                    tokenDirty = false
                    revealPassword = false
                    revealToken = false
                    showQr = false
                    passwordLoaded = true
                    tokenLoaded = true
                }
            }
        }
        if (state.viewerUrl.isNullOrBlank()) row("link-pending") {
            Text(strings.motionText(R.string.cameras_link_pending), color = colors.muted, fontSize = 13.sp)
        }
        if (blocked) row("charge-blocked") {
            Text(strings.motionText(R.string.cameras_charge_paused), color = colors.yellow, fontSize = 13.sp)
        } else if (chargeState.shouldWarnUnavailable()) row("charge-unavailable") {
            Text(chargeUnavailableText(strings, chargeState), color = colors.yellow, fontSize = 13.sp)
        }
        row("session-hint") {
            Text(strings.motionText(R.string.cameras_session_hint), color = colors.muted, fontSize = 13.sp)
        }
        this
    }

    if (showQr) state.viewerUrl?.let { url -> CameraViewerQrDialog(url, colors, strings) { showQr = false } }
    return this
}

private fun chargeUnavailableText(strings: UiStrings, state: CameraChargePolicy.State): String {
    val missing = buildList {
        if (state.isSocUnavailable()) add("SOC")
        if (state.isVoltageUnavailable()) add(strings.motionText(R.string.cameras_low_voltage))
    }.joinToString(" / ")
    return strings.motionText(R.string.cameras_charge_unavailable, missing)
}

@Composable
private fun ConnectionField(
    title: String,
    value: String,
    colors: UiPalette,
    secret: Boolean = false,
    revealed: Boolean = false,
    enabled: Boolean = true,
    revealDescription: String = title,
    onReveal: () -> Unit = {},
    onValueChange: (String) -> Unit,
    onCommit: () -> Unit = {},
) {
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    ImeDismissalEffect(focused) { focus.clearFocus() }
    Text(title, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp))
        .background(colors.field).border(1.dp, colors.borderStrong, RoundedCornerShape(7.dp))
        .padding(start = 12.dp, end = if (secret) 4.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = value,
            onValueChange = { if (enabled) onValueChange(it) },
            enabled = enabled,
            singleLine = true,
            visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onCommit(); focus.clearFocus() }),
            textStyle = TextStyle(color = if (enabled) colors.text else colors.muted,
                fontSize = 16.sp, fontWeight = FontWeight.Normal),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.weight(1f).onFocusChanged {
                val wasFocused = focused
                focused = it.isFocused
                if (wasFocused && !focused) onCommit()
            }
                .semantics { contentDescription = title }.padding(vertical = 12.dp),
        )
        if (secret) {
            IconButton(onClick = onReveal, enabled = enabled, modifier = Modifier.size(38.dp)
                .semantics { contentDescription = revealDescription }) {
                Canvas(Modifier.size(24.dp)) {
                    drawOval(colors.muted,
                        androidx.compose.ui.geometry.Offset(size.width * .12f, size.height * .28f),
                        androidx.compose.ui.geometry.Size(size.width * .76f, size.height * .44f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                    drawCircle(colors.muted, radius = size.width * .1f)
                    if (!revealed) {
                        drawLine(colors.muted, androidx.compose.ui.geometry.Offset(size.width * .16f, size.height * .82f),
                            androidx.compose.ui.geometry.Offset(size.width * .84f, size.height * .18f), strokeWidth = 2.dp.toPx())
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraViewerQrDialog(url: String, colors: UiPalette, strings: UiStrings, onClose: () -> Unit) {
    val bitmap = remember(url) {
        runCatching {
            val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 480, 480)
            val pixels = IntArray(matrix.width * matrix.height) { index ->
                if (matrix.get(index % matrix.width, index / matrix.width)) AndroidColor.BLACK else AndroidColor.WHITE
            }
            Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
            }
        }.getOrNull()
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(.8f).clip(RoundedCornerShape(10.dp)).background(colors.surface)
            .border(1.dp, colors.borderStrong, RoundedCornerShape(10.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(strings.motionText(R.string.cameras_qr), color = colors.text, fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold)
            if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = strings.motionText(R.string.cameras_qr),
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White))
            else Text(strings.motionText(R.string.cameras_qr_failed), color = colors.red, fontSize = 14.sp)
            ActionButton(strings.motionText(R.string.motion_close), colors, Modifier.fillMaxWidth(), onClick = onClose)
        }
    }
}

private fun openAuthorization(context: Context, url: String, strings: UiStrings) {
    try {
        val uri = Uri.parse(url)
        if (uri.scheme !in setOf("https", "http")) return
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        Toast.makeText(context, strings.motionText(R.string.cameras_open_authorization_failed), Toast.LENGTH_LONG).show()
    }
}

private fun copyViewerUrl(context: Context, url: String, strings: UiStrings) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(strings.motionText(R.string.cameras_viewing), url))
    Toast.makeText(context, strings.motionText(R.string.cameras_copied), Toast.LENGTH_SHORT).show()
}

private fun UiStrings.motionText(id: Int, vararg args: Any): String {
    val value = resource(id)
    return if (args.isEmpty()) value else String.format(Locale.getDefault(), value, *args)
}
