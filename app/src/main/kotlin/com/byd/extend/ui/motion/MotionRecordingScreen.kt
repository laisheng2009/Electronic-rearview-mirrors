package com.byd.extend.ui.motion

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.byd.extend.MotionConfig
import com.byd.extend.MotionMediaLibrary
import com.byd.extend.MotionRuntime
import com.byd.extend.AppLanguage
import com.byd.extend.R
import com.byd.extend.ui.ActionButton as ProductionActionButton
import com.byd.extend.ui.ChoiceField
import com.byd.extend.ui.NumericSetting
import com.byd.extend.ui.Segmented
import com.byd.extend.ui.Section
import com.byd.extend.ui.SwitchLine
import com.byd.extend.ui.UiPalette
import com.byd.extend.ui.UiStrings
import com.byd.extend.ui.icons.DirectionsCar
import com.byd.extend.ui.icons.OpenInFull
import com.byd.extend.ui.icons.Videocam
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.roundToInt

private const val MOTION_GB = 1_000_000_000L
private const val MOTION_PLAYER_TAG = "MotionPlayer"

private data class RecordingOptions(
    val enabled: Boolean,
    val before: Int,
    val after: Int,
    val audio: Boolean,
    val quality: Int,
    val sd: Boolean,
    val fallback: Boolean,
    val internalLimit: Int,
    val sdLimit: Int,
    val internalKeepFree: Boolean,
    val sdKeepFree: Boolean,
    val internalFreeGb: Int,
    val sdFreeGb: Int,
    val oemCleanup: Boolean,
    val oemQuota: Int,
    val cameraOrder: List<Int>,
) {
    fun write(preferences: android.content.SharedPreferences) = preferences.edit()
        .putBoolean("enabled", enabled).putInt("before", before).putInt("after", after)
        .putBoolean("audio", audio).putInt("quality", quality).putBoolean("sd", sd)
        .putBoolean("fallback", fallback).putInt("internalLimit", internalLimit).putInt("sdLimit", sdLimit)
        .putBoolean("internalKeepFree", internalKeepFree).putBoolean("sdKeepFree", sdKeepFree)
        .putInt("internalFreeGb", internalFreeGb).putInt("sdFreeGb", sdFreeGb)
        .putBoolean("oemCleanup", oemCleanup).putInt("oemQuota", oemQuota)
        .putString("order", cameraOrder.joinToString(",")).apply()

    companion object {
        fun from(config: MotionConfig) = RecordingOptions(
            config.recordingEnabled, config.beforeSeconds, config.afterSeconds, config.audioEnabled,
            config.quality, config.primarySd, config.fallbackEnabled, config.internalLimitGb,
            config.sdLimitGb, config.internalKeepFree, config.sdKeepFree, config.internalFreeGb,
            config.sdFreeGb, config.oemCleanup, config.oemLimitGb, config.cameraOrder.toList())
    }
}

@Composable
internal fun MotionRecordingScreen(colors: UiPalette, strings: UiStrings) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences(MotionConfig.RECORDING_PREFS, Context.MODE_PRIVATE)
    }
    val mediaLibrary = remember(context) { MotionMediaLibrary(context.applicationContext) }
    var options by remember(context) { mutableStateOf(RecordingOptions.from(MotionConfig.read(context))) }
    var stats by remember { mutableStateOf<MotionMediaLibrary.Stats?>(null) }
    var composition by rememberSaveable { mutableStateOf(false) }
    var archive by rememberSaveable { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var microphoneGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var storageReadGranted by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 23 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
    }
    var storageWriteGranted by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 23 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
    }
    var confirmOemCleanup by rememberSaveable { mutableStateOf(false) }
    var pendingOemEnable by remember { mutableStateOf(false) }

    fun save(next: RecordingOptions) {
        options = next
        next.write(preferences)
        MotionRuntime.settingsChanged(context)
    }

    LaunchedEffect(microphoneGranted, options.audio) {
        if (options.audio && !microphoneGranted) save(options.copy(audio = false))
    }

    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        microphoneGranted = granted
        if (granted) save(options.copy(audio = true))
        else Toast.makeText(context, strings.motionText(R.string.motion_audio_permission_denied), Toast.LENGTH_LONG).show()
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        storageReadGranted = granted
        if (granted) refreshKey++
        else Toast.makeText(context, strings.motionText(R.string.motion_storage_unreadable), Toast.LENGTH_LONG).show()
    }
    val oemStoragePermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        storageReadGranted = grants[Manifest.permission.READ_EXTERNAL_STORAGE] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        storageWriteGranted = grants[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        refreshKey++
        if (pendingOemEnable) {
            pendingOemEnable = false
            if (storageReadGranted && storageWriteGranted) save(options.copy(oemCleanup = true))
            else Toast.makeText(context, strings.motionText(R.string.motion_oem_permission_denied), Toast.LENGTH_LONG).show()
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, options, refreshKey, mediaLibrary) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                try {
                    stats = withContext(Dispatchers.IO) {
                        mediaLibrary.refresh()
                        mediaLibrary.queryStats(MotionConfig.read(context))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    stats = null
                }
                delay(5_000)
            }
        }
    }

    Section(strings.motionText(R.string.motion_video_title), colors) {
        SwitchLine(strings.motionText(R.string.motion_video_enable), strings.motionText(R.string.motion_video_preview),
            options.enabled, { save(options.copy(enabled = it)) }, colors)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RecordingNumber(strings.motionText(R.string.motion_before), options.before,
                    strings.motionText(R.string.motion_seconds), colors, options.enabled) {
                    save(options.copy(before = it))
                }
                RecordingNumber(strings.motionText(R.string.motion_after), options.after,
                    strings.motionText(R.string.motion_seconds), colors, options.enabled) {
                    save(options.copy(after = it))
                }
                Text(strings.motionText(R.string.motion_buffer_hint), color = colors.muted, fontSize = 13.sp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SwitchLine(strings.motionText(R.string.motion_audio), strings.motionText(R.string.motion_audio_hint),
                    options.audio && microphoneGranted, { enabled ->
                        if (!enabled) save(options.copy(audio = false))
                        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            microphoneGranted = true
                            save(options.copy(audio = true))
                        } else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    }, colors, enabled = options.enabled)
                if (!microphoneGranted) {
                    Text(strings.motionText(R.string.motion_audio_permission_denied), color = colors.yellow, fontSize = 12.sp)
                }
                Text(strings.motionText(R.string.motion_quality), color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Segmented(listOf(R.string.motion_quality_low, R.string.motion_quality_medium,
                    R.string.motion_quality_high).map { strings.motionText(it) }, options.quality,
                    colors, Modifier.fillMaxWidth(), enabled = { options.enabled }) {
                    save(options.copy(quality = it))
                }
                Text(strings.motionText(R.string.motion_quality_hint,
                    listOf(1280, 1920, 2560)[options.quality], listOf(2, 4, 8)[options.quality]),
                    color = colors.muted, fontSize = 13.sp)
                ActionButton(strings.motionText(R.string.motion_composition_edit), Icons.Outlined.DirectionsCar,
                    colors, Modifier.fillMaxWidth()) { composition = true }
            }
        }
    }

    Section(strings.motionText(R.string.motion_storage), colors) {
        Text(strings.motionText(R.string.motion_storage_primary), color = colors.text, fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold)
        Segmented(listOf(strings.motionText(R.string.motion_storage_internal), strings.motionText(R.string.motion_storage_sd)),
            if (options.sd) 1 else 0, colors, Modifier.fillMaxWidth(),
            enabled = { index -> val volume = if (index == 0) stats?.primary else stats?.secondary
                volume?.mounted == true && !volume.readOnly }) { save(options.copy(sd = it == 1)) }
        val backup = strings.motionText(if (options.sd) R.string.motion_storage_internal else R.string.motion_storage_sd)
        SwitchLine(strings.motionText(R.string.motion_fallback, backup), strings.motionText(R.string.motion_fallback_hint),
            options.fallback, { save(options.copy(fallback = it)) }, colors)
        Text(strings.motionText(R.string.motion_storage_hint), color = colors.muted, fontSize = 13.sp)
        if (stats == null) Text(strings.motionText(R.string.motion_storage_loading), color = colors.muted, fontSize = 13.sp)
        else {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MotionStorageCard(Modifier.weight(1f), strings.motionText(R.string.motion_storage_internal), stats?.primary,
                    options.internalLimit, options.internalKeepFree, options.internalFreeGb,
                    options.enabled && (!options.sd || options.fallback), options.oemCleanup, colors, strings,
                    onLimit = { save(options.copy(internalLimit = it)) },
                    onKeep = { save(options.copy(internalKeepFree = it)) },
                    onFree = { save(options.copy(internalFreeGb = it)) })
                MotionStorageCard(Modifier.weight(1f), strings.motionText(R.string.motion_storage_sd), stats?.secondary,
                    options.sdLimit, options.sdKeepFree, options.sdFreeGb,
                    options.enabled && (options.sd || options.fallback), options.oemCleanup, colors, strings,
                    onLimit = { save(options.copy(sdLimit = it)) },
                    onKeep = { save(options.copy(sdKeepFree = it)) },
                    onFree = { save(options.copy(sdFreeGb = it)) })
            }
            val oemUnknown = stats?.volumes?.any { it.oemRecorderBytes < 0 } == true
            if (oemUnknown && !storageReadGranted && Build.VERSION.SDK_INT >= 23) {
                ActionButton(strings.motionText(R.string.motion_storage_permission), colors = colors,
                    modifier = Modifier.fillMaxWidth()) { storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE) }
            }
        }
        SwitchLine(strings.motionText(R.string.motion_oem_cleanup), strings.motionText(R.string.motion_oem_cleanup_hint),
            options.oemCleanup, { enabled ->
                if (enabled) confirmOemCleanup = true else save(options.copy(oemCleanup = false))
            }, colors)
        val oemMaxGb = stats?.volumes.orEmpty().filter { it.mounted && it.capacityBytes > 0 }
            .maxOfOrNull { (it.capacityBytes / MOTION_GB).toInt().coerceAtLeast(1) }
        RecordingLimit(strings.motionText(R.string.motion_oem_quota), options.oemQuota, oemMaxGb,
            strings.motionText(R.string.motion_gb), colors, strings, oemMaxGb != null,
            { save(options.copy(oemQuota = it)) })
        Text(strings.motionText(R.string.motion_oem_cleanup_unavailable), color = colors.muted, fontSize = 12.sp)
        if (options.oemCleanup && (!storageReadGranted || !storageWriteGranted) && Build.VERSION.SDK_INT >= 23) {
            ActionButton(strings.motionText(R.string.motion_oem_storage_permission), colors = colors,
                modifier = Modifier.fillMaxWidth()) {
                oemStoragePermissions.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE))
            }
        }
    }

    Section(strings.motionText(R.string.motion_archive), colors) {
        Text(strings.motionText(R.string.motion_archive_hint), color = colors.muted, fontSize = 13.sp)
        ActionButton(strings.motionText(R.string.motion_archive), Icons.Outlined.Videocam,
            colors, Modifier.fillMaxWidth()) { archive = true }
    }

    if (composition) MotionCompositionDialog(options.cameraOrder, colors, strings,
        onClose = { composition = false }, onSave = {
            save(options.copy(cameraOrder = it)); composition = false
        })
    if (confirmOemCleanup) Dialog(onDismissRequest = { confirmOemCleanup = false }) {
        Column(Modifier.fillMaxWidth(.9f).clip(RoundedCornerShape(10.dp)).background(colors.surface)
            .border(1.dp, colors.borderStrong, RoundedCornerShape(10.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings.motionText(R.string.motion_oem_enable_title), color = colors.text,
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(strings.motionText(R.string.motion_oem_enable_confirm), color = colors.muted, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(strings.motionText(R.string.motion_oem_enable), colors = colors,
                    modifier = Modifier.weight(1f), primary = true) {
                    confirmOemCleanup = false
                    if (storageReadGranted && storageWriteGranted) save(options.copy(oemCleanup = true))
                    else {
                        pendingOemEnable = true
                        oemStoragePermissions.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE))
                    }
                }
                ActionButton(strings.motionText(R.string.cancel), colors = colors,
                    modifier = Modifier.weight(1f)) { confirmOemCleanup = false }
            }
        }
    }
    if (archive) MotionArchiveDialog(mediaLibrary, stats, colors, strings,
        onClose = { archive = false }, onChanged = { refreshKey++ })
}

@Composable
private fun RecordingNumber(title: String, value: Int, suffix: String, colors: UiPalette,
    enabled: Boolean = true, onChange: (Int) -> Unit) {
    NumericSetting(title, value.toString(), suffix, colors, { raw -> raw.toIntOrNull()?.let(onChange) },
        1f..30f, enabled = enabled, adjustable = true, slider = true,
        identity = "motion-recording-$title")
}

@Composable
private fun MotionStorageCard(
    modifier: Modifier,
    title: String,
    volume: MotionMediaLibrary.VolumeStats?,
    limit: Int,
    keepFree: Boolean,
    freeGb: Int,
    active: Boolean,
    oemActive: Boolean,
    colors: UiPalette,
    strings: UiStrings,
    onLimit: (Int) -> Unit,
    onKeep: (Boolean) -> Unit,
    onFree: (Int) -> Unit,
) {
    Column(modifier.border(1.dp, colors.borderStrong, RoundedCornerShape(8.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (volume == null || !volume.mounted || volume.capacityBytes <= 0) {
            Text(strings.motionText(R.string.motion_storage_missing), color = colors.muted, fontSize = 13.sp)
        } else {
            if (volume.readOnly) Text(strings.motionText(R.string.motion_storage_readonly), color = colors.yellow, fontSize = 12.sp)
            MotionStorageBar(volume, active, oemActive, colors, strings)
        }
        val maxGb = volume?.capacityBytes?.takeIf { it > 0 }?.div(MOTION_GB)?.toInt()?.coerceAtLeast(1)
        RecordingLimit(strings.motionText(R.string.motion_quota), limit, maxGb,
            strings.motionText(R.string.motion_gb), colors, strings, volume?.mounted == true, onLimit)
        SwitchLine(strings.motionText(R.string.motion_keep_free), strings.motionText(R.string.motion_keep_free_hint),
            keepFree, onKeep, colors)
        RecordingLimit(strings.motionText(R.string.motion_free_amount), freeGb, maxGb,
            strings.motionText(R.string.motion_gb), colors, strings, volume?.mounted == true, onFree)
    }
}

@Composable
private fun RecordingLimit(title: String, value: Int, maximum: Int?, suffix: String,
    colors: UiPalette, strings: UiStrings, enabled: Boolean, onChange: (Int) -> Unit) {
    val upper = (maximum ?: 9999).coerceAtLeast(1)
    NumericSetting(title, value.coerceAtMost(upper).toString(), suffix, colors,
        { onChange(it.toFloatOrNull()?.toInt()?.coerceIn(1, upper) ?: value) },
        1f..upper.toFloat(), enabled = enabled, adjustable = true, slider = maximum != null,
        identity = "motion-storage-$title")
    if (maximum == null) Text(strings.motionText(R.string.motion_storage_unknown_limit), color = colors.muted, fontSize = 12.sp)
}

@Composable
private fun MotionStorageBar(
    volume: MotionMediaLibrary.VolumeStats,
    active: Boolean,
    oemActive: Boolean,
    colors: UiPalette,
    strings: UiStrings,
) {
    val capacity = volume.capacityBytes.coerceAtLeast(0)
    fun gb(value: Long) = String.format(Locale.US, "%.1f", value.toDouble() / MOTION_GB)
    val oemKnown = volume.oemRecorderBytes >= 0
    val usageKnown = oemKnown && volume.otherBytes >= 0
    val knownUsed = (volume.otherBytes.coerceAtLeast(0) + volume.ownBytes.coerceAtLeast(0) +
        if (oemKnown) volume.oemRecorderBytes else 0L).coerceAtMost(capacity)
    Text(if (capacity > 0) strings.motionText(
        if (usageKnown) R.string.motion_storage_numbers else R.string.motion_storage_numbers_partial,
        gb(knownUsed), gb(volume.freeBytes.coerceAtLeast(0)), gb(capacity))
        else strings.motionText(R.string.motion_storage_no_data),
        color = colors.muted, fontSize = 13.sp)
    Canvas(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(5.dp))
        .border(1.dp, colors.borderStrong, RoundedCornerShape(5.dp))) {
        if (capacity <= 0) return@Canvas
        var x = 0f
        fun width(bytes: Long): Float = (size.width * (bytes.coerceAtLeast(0).toDouble() / capacity)).toFloat()
            .coerceAtMost(size.width - x)
        fun segment(bytes: Long, color: Color, hatch: Boolean = false) {
            val segmentWidth = width(bytes)
            if (segmentWidth <= 0f) return
            if (hatch) motionHatch(color, x, x + segmentWidth) else drawRect(color, Offset(x, 0f), Size(segmentWidth, size.height))
            x += segmentWidth
        }
        segment(volume.otherBytes, MotionOtherData)
        if (oemKnown) {
            segment(volume.oemRecorderBytes, MotionOemData)
            if (oemActive) segment(volume.oemAllowanceBytes, MotionOemData, true)
        }
        segment(volume.ownBytes, colors.yellow)
        if (active) segment(volume.ownAllowanceBytes, colors.yellow, true)
        segment(volume.unallocatedBytes, Color.Transparent)
    }
    val labels = listOf(R.string.motion_storage_other, R.string.motion_storage_oem, R.string.motion_storage_own)
    val legendColors = listOf(MotionOtherData, MotionOemData, colors.yellow)
    val usedValues = listOf(volume.otherBytes.takeIf { it >= 0 },
        volume.oemRecorderBytes.takeIf { it >= 0 }, volume.ownBytes.takeIf { it >= 0 })
    val allowanceValues = listOf(null, volume.oemAllowanceBytes.takeIf { oemKnown && oemActive },
        volume.ownAllowanceBytes.takeIf { active })
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        labels.forEachIndexed { index, label -> Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(12.dp).border(1.dp, colors.borderStrong, RoundedCornerShape(2.dp))) {
                drawRect(legendColors[index])
            }
            Spacer(Modifier.width(4.dp)); Text(strings.motionText(label), color = colors.muted, fontSize = 11.sp)
        } }
    }
    listOf(usedValues, allowanceValues).forEachIndexed { row, values ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            values.forEachIndexed { index, bytes -> Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(12.dp).border(1.dp, colors.borderStrong, RoundedCornerShape(2.dp))) {
                    if (row == 0) drawRect(legendColors[index])
                    else if (bytes != null) motionHatch(legendColors[index], 0f, size.width)
                }
                Spacer(Modifier.width(4.dp))
                Text(strings.motionText(if (row == 0) R.string.motion_storage_used_value else R.string.motion_storage_free_value,
                    bytes?.let(::gb) ?: "—"), color = colors.muted, fontSize = 11.sp, maxLines = 2)
            } }
        }
    }
    Text(strings.motionText(R.string.motion_storage_unallocated_value,
        if (usageKnown) gb(volume.unallocatedBytes) else "—"), color = colors.muted, fontSize = 11.sp)
    if (!oemKnown) Text(strings.motionText(R.string.motion_oem_bytes_unknown), color = colors.muted, fontSize = 12.sp)
}

private val MotionOtherData = Color(0xFF3A3D42)
private val MotionOemData = Color(0xFF58BCEB)

private fun DrawScope.motionHatch(color: Color, left: Float, right: Float) {
    if (right <= left) return
    clipRect(left = left, right = right) {
        var x = left - size.height
        while (x < right) {
            drawLine(color, Offset(x, size.height), Offset(x + size.height, 0f), 1.dp.toPx())
            x += 6.dp.toPx()
        }
    }
}

@Composable
private fun MotionArchiveDialog(
    library: MotionMediaLibrary,
    initialStats: MotionMediaLibrary.Stats?,
    colors: UiPalette,
    strings: UiStrings,
    onClose: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val locale = motionLocale(context)
    val zone = ZoneId.systemDefault()
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var rangeFrom by remember { mutableStateOf<LocalDateTime?>(null) }
    var rangeTo by remember { mutableStateOf<LocalDateTime?>(null) }
    var monthDays by remember { mutableStateOf(emptyList<MotionMediaLibrary.Day>()) }
    var entries by remember { mutableStateOf(emptyList<MotionMediaLibrary.Entry>()) }
    var stats by remember { mutableStateOf(initialStats) }
    var volumeId by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var selectedForDelete by remember { mutableStateOf(emptySet<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var playerEntry by remember { mutableStateOf<MotionMediaLibrary.Entry?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var deleteResult by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<List<MotionMediaLibrary.Entry>?>(null) }
    var pendingDeletePermission by remember { mutableStateOf<List<MotionMediaLibrary.Entry>?>(null) }
    val rangeValid = rangeFrom != null && rangeTo != null && rangeFrom!!.isBefore(rangeTo!!)
    val rangeIncomplete = (rangeFrom == null) != (rangeTo == null)
    val rangeInvalid = rangeFrom != null && rangeTo != null && !rangeFrom!!.isBefore(rangeTo!!)
    val rangeFormatter = remember(locale, zone) {
        DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", locale)
    }

    val legacyDeletePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val chosen = pendingDeletePermission
        pendingDeletePermission = null
        if (granted && chosen != null) pendingDelete = chosen
        else if (chosen != null) Toast.makeText(context,
            strings.motionText(R.string.motion_archive_delete_permission), Toast.LENGTH_LONG).show()
    }

    val firstOfMonth = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val firstOfNextMonth = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val filterStart = if (rangeValid) rangeFrom!!.atZone(zone).toInstant().toEpochMilli()
        else selectedDate?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: firstOfMonth
    val filterEnd = if (rangeValid) rangeTo!!.atZone(zone).toInstant().toEpochMilli()
        else selectedDate?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: firstOfNextMonth

    fun showRangePicker(isStart: Boolean) {
        val fallback = if (isStart) {
            selectedDate?.atStartOfDay() ?: month.atDay(1).atStartOfDay()
        } else {
            selectedDate?.plusDays(1)?.atStartOfDay() ?: LocalDateTime.now().withSecond(0).withNano(0)
        }
        val initial = (if (isStart) rangeFrom else rangeTo) ?: fallback
        DatePickerDialog(context, { _, year, monthIndex, day ->
            val date = LocalDate.of(year, monthIndex + 1, day)
            TimePickerDialog(context, { _, hour, minute ->
                val chosen = date.atTime(hour, minute)
                if (isStart) rangeFrom = chosen else rangeTo = chosen
            }, initial.hour, initial.minute, true).show()
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
    }
    LaunchedEffect(library) {
        try {
            val latestAvailable = withContext(Dispatchers.IO) {
                library.refresh()
                library.query(MotionMediaLibrary.Query(null, null, null, MotionMediaLibrary.Sort.NEWEST_FIRST))
                    .firstOrNull { it.available }
            }
            if (latestAvailable != null) {
                val date = Instant.ofEpochMilli(latestAvailable.recordedAtMillis).atZone(zone).toLocalDate()
                selectedDate = date
                month = YearMonth.from(date)
            }
            stats = withContext(Dispatchers.IO) { library.queryStats(MotionConfig.read(context)) }
            loadFailed = false
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { loadFailed = true }
    }
    LaunchedEffect(month, volumeId, sort, rangeFrom, rangeTo, refreshKey) {
        try {
            monthDays = withContext(Dispatchers.IO) {
                library.queryDays(MotionMediaLibrary.Query(
                    if (rangeValid) rangeFrom!!.atZone(zone).toInstant().toEpochMilli() else firstOfMonth,
                    if (rangeValid) rangeTo!!.atZone(zone).toInstant().toEpochMilli() else firstOfNextMonth,
                    volumeId, if (sort == 0) MotionMediaLibrary.Sort.NEWEST_FIRST else MotionMediaLibrary.Sort.OLDEST_FIRST))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { loadFailed = true }
    }
    LaunchedEffect(month, selectedDate, rangeFrom, rangeTo, volumeId, sort, refreshKey) {
        loading = true
        try {
            entries = withContext(Dispatchers.IO) {
                library.query(MotionMediaLibrary.Query(filterStart, filterEnd, volumeId,
                    if (sort == 0) MotionMediaLibrary.Sort.NEWEST_FIRST else MotionMediaLibrary.Sort.OLDEST_FIRST))
            }
            selectedForDelete = selectedForDelete.intersect(entries.filter(::canDelete).map { it.contentUri.toString() }.toSet())
            loadFailed = false
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { loadFailed = true; entries = emptyList() }
        finally { loading = false }
    }
    LaunchedEffect(pendingDelete) {
        val chosen = pendingDelete ?: return@LaunchedEffect
        try {
            val result = withContext(Dispatchers.IO) { library.deleteSelected(chosen) }
            selectedForDelete = emptySet()
            deleteResult = strings.motionText(R.string.motion_archive_delete_result,
                result.deletedCount, result.failedCount, result.skippedCount)
            refreshKey++
            stats = withContext(Dispatchers.IO) { library.queryStats(MotionConfig.read(context)) }
            onChanged()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { loadFailed = true }
        finally { pendingDelete = null }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize(.97f).clip(RoundedCornerShape(10.dp)).background(colors.surface)
            .border(1.dp, colors.borderStrong, RoundedCornerShape(10.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(strings.motionText(R.string.motion_archive), color = colors.text, fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text(strings.motionText(R.string.motion_archive_hint), color = colors.muted, fontSize = 12.sp)
                }
                ActionButton(strings.motionText(R.string.motion_close), colors = colors, onClick = onClose)
            }
            val volumeChoices: List<Pair<String?, String>> = listOf(null to strings.motionText(R.string.motion_archive_all)) +
                (stats?.volumes.orEmpty().map { it.id to it.label })
            Segmented(volumeChoices.map { it.second }, volumeChoices.indexOfFirst { it.first == volumeId }.coerceAtLeast(0),
                colors, Modifier.fillMaxWidth()) { volumeId = volumeChoices[it].first }
            Segmented(listOf(strings.motionText(R.string.motion_archive_newest), strings.motionText(R.string.motion_archive_oldest)),
                sort, colors, Modifier.fillMaxWidth()) { sort = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("${strings.motionText(R.string.motion_archive_from)}: ${rangeFrom?.format(rangeFormatter)
                    ?: strings.motionText(R.string.motion_archive_choose)}", colors = colors,
                    modifier = Modifier.weight(1f)) { showRangePicker(true) }
                ActionButton("${strings.motionText(R.string.motion_archive_to)}: ${rangeTo?.format(rangeFormatter)
                    ?: strings.motionText(R.string.motion_archive_choose)}", colors = colors,
                    modifier = Modifier.weight(1f)) { showRangePicker(false) }
                if (rangeFrom != null || rangeTo != null) ActionButton(
                    strings.motionText(R.string.motion_archive_clear_range), colors = colors) {
                    rangeFrom = null
                    rangeTo = null
                }
            }
            if (rangeIncomplete) Text(strings.motionText(R.string.motion_archive_range_incomplete),
                color = colors.yellow, fontSize = 12.sp)
            if (rangeInvalid) Text(strings.motionText(R.string.motion_archive_range_invalid),
                color = colors.yellow, fontSize = 12.sp)
            CalendarPicker(month, selectedDate, monthDays, locale, colors, strings,
                onMonth = { month = it; selectedDate = null }, onDate = { selectedDate = it })
            if (selectedDate != null) {
                val day = monthDays.firstOrNull { it.date == selectedDate }
                if (day != null) Text(strings.motionText(R.string.motion_archive_events,
                    day.entryCount, day.yellowEvents, day.redEvents), color = colors.muted, fontSize = 13.sp)
            }
            val mounted = stats?.volumes?.any { it.mounted } == true
            if (!mounted && !loadFailed) Text(strings.motionText(R.string.motion_archive_no_storage),
                color = colors.yellow, fontSize = 14.sp)
            if (loadFailed) Text(strings.motionText(R.string.motion_storage_unreadable), color = colors.yellow, fontSize = 14.sp)
            if (selectedForDelete.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.motionText(R.string.motion_archive_protected), Modifier.weight(1f), color = colors.muted, fontSize = 12.sp)
                    ActionButton(strings.motionText(R.string.motion_delete), Icons.Outlined.Delete, colors,
                        destructive = true) { confirmDelete = true }
                }
            }
            if (entries.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(if (loading) strings.motionText(R.string.motion_storage_loading)
                        else strings.motionText(R.string.motion_archive_empty), color = colors.muted, fontSize = 15.sp)
                }
            } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(entries, key = { it.contentUri.toString() }) { entry ->
                    MotionArchiveRow(entry, selectedForDelete.contains(entry.contentUri.toString()), colors, strings,
                        onPlay = {
                            if (entry.available) playerEntry = entry
                            else Toast.makeText(context, strings.motionText(R.string.motion_archive_unavailable), Toast.LENGTH_SHORT).show()
                        }, onToggleDelete = {
                            val id = entry.contentUri.toString()
                            selectedForDelete = if (id in selectedForDelete) selectedForDelete - id else selectedForDelete + id
                        }, locale = locale, zone = zone)
                }
            }
            if (confirmDelete) {
                val chosen = entries.filter { it.contentUri.toString() in selectedForDelete && canDelete(it) }
                Dialog(onDismissRequest = { confirmDelete = false }) {
                    Column(Modifier.fillMaxWidth(.85f).clip(RoundedCornerShape(10.dp)).background(colors.surface)
                        .border(1.dp, colors.borderStrong, RoundedCornerShape(10.dp)).padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(strings.motionText(R.string.motion_archive_delete_confirm, chosen.size),
                            color = colors.text, fontSize = 17.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ActionButton(strings.motionText(R.string.motion_delete), colors = colors,
                                modifier = Modifier.weight(1f), destructive = true) {
                                confirmDelete = false
                                if (chosen.isNotEmpty()) {
                                    if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(
                                            context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                                        pendingDeletePermission = chosen
                                        legacyDeletePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                    } else pendingDelete = chosen
                                }
                            }
                            ActionButton(strings.motionText(R.string.cancel), colors = colors,
                                modifier = Modifier.weight(1f)) { confirmDelete = false }
                        }
                    }
                }
            }
            deleteResult?.let { Text(it, color = colors.muted, fontSize = 12.sp) }
        }
    }
    playerEntry?.let { entry -> MotionPlayerDialog(entry, colors, strings, locale) { playerEntry = null } }
}

private fun canDelete(entry: MotionMediaLibrary.Entry) =
    entry.status in setOf(MotionMediaLibrary.Status.COMPLETE, MotionMediaLibrary.Status.INTERRUPTED,
        MotionMediaLibrary.Status.UNKNOWN) && entry.available && !entry.protectedFile

@Composable
private fun CalendarPicker(
    month: YearMonth,
    selected: LocalDate?,
    days: List<MotionMediaLibrary.Day>,
    locale: Locale,
    colors: UiPalette,
    strings: UiStrings,
    onMonth: (YearMonth) -> Unit,
    onDate: (LocalDate) -> Unit,
) {
    val formatter = remember(locale) { DateTimeFormatter.ofPattern("LLLL yyyy", locale) }
    val weekdayFormatter = remember(locale) { DateTimeFormatter.ofPattern("EEEEE", locale) }
    val firstDay = WeekFields.of(locale).firstDayOfWeek
    val orderedWeek = (0..6).map { DayOfWeek.of((firstDay.value - 1 + it) % 7 + 1) }
    val offset = (month.atDay(1).dayOfWeek.value - firstDay.value + 7) % 7
    val cells = (List(offset) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }).chunked(7)
    val counts = days.associateBy { it.date }
    Section(strings.motionText(R.string.motion_date), colors) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ActionButton("‹", colors = colors, modifier = Modifier.size(44.dp)) { onMonth(month.minusMonths(1)) }
            Text(month.format(formatter), Modifier.weight(1f), color = colors.text, textAlign = TextAlign.Center,
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            ActionButton("›", colors = colors, modifier = Modifier.size(44.dp)) { onMonth(month.plusMonths(1)) }
        }
        Row(Modifier.fillMaxWidth()) {
            orderedWeek.forEach { weekday -> Text(weekdayFormatter.format(weekday), Modifier.weight(1f),
                color = colors.muted, textAlign = TextAlign.Center, fontSize = 12.sp) }
        }
        cells.forEach { week -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            week.forEach { date ->
                if (date == null) Spacer(Modifier.weight(1f).height(38.dp)) else {
                    val day = counts[date]
                    val chosen = date == selected
                    Column(Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(5.dp))
                        .background(if (chosen) colors.accent else Color.Transparent)
                        .clickable(role = Role.Button) { onDate(date) }
                        .semantics { contentDescription = "${date.format(DateTimeFormatter.ISO_LOCAL_DATE)} ${day?.entryCount ?: 0}" },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(date.dayOfMonth.toString(), color = if (chosen) Color.White else colors.text, fontSize = 12.sp)
                        if (day != null) Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (day.yellowEvents > 0) Box(Modifier.size(4.dp).background(colors.yellow, RoundedCornerShape(4.dp)))
                            if (day.redEvents > 0) Box(Modifier.size(4.dp).background(colors.red, RoundedCornerShape(4.dp)))
                        }
                    }
                }
            }
            repeat((7 - week.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f).height(38.dp)) }
        } }
    }
}

@Composable
private fun MotionArchiveRow(
    entry: MotionMediaLibrary.Entry,
    selected: Boolean,
    colors: UiPalette,
    strings: UiStrings,
    onPlay: () -> Unit,
    onToggleDelete: () -> Unit,
    locale: Locale,
    zone: ZoneId,
) {
    val time = remember(entry.recordedAtMillis, locale, zone) {
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy · HH:mm", locale)
            .withZone(zone).format(java.time.Instant.ofEpochMilli(entry.recordedAtMillis))
    }
    val status = when (entry.status) {
        MotionMediaLibrary.Status.RECORDING -> R.string.motion_archive_recording
        MotionMediaLibrary.Status.COMPLETE -> R.string.motion_archive_complete
        MotionMediaLibrary.Status.INTERRUPTED -> R.string.motion_archive_interrupted
        else -> R.string.motion_archive_unknown
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(colors.panelAlt)
        .border(1.dp, colors.border, RoundedCornerShape(7.dp)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f).clickable(onClick = onPlay)) {
            Text(entry.title.ifBlank { time }, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1)
            Text("$time · ${formatDuration(entry.durationMs)} · ${formatSize(entry.sizeBytes)} · ${strings.motionText(status)}",
                color = colors.muted, fontSize = 12.sp, maxLines = 2)
            if (entry.markers.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                val yellow = entry.markers.count { it.level == 1 }
                val red = entry.markers.count { it.level == 2 }
                if (yellow > 0) Text("${strings.motionText(R.string.motion_archive_yellow)}: $yellow",
                    color = colors.yellow, fontSize = 11.sp)
                if (red > 0) Text("${strings.motionText(R.string.motion_archive_red)}: $red",
                    color = colors.red, fontSize = 11.sp)
            }
        }
        ActionButton(if (selected) "✓" else "□", colors = colors, modifier = Modifier.size(42.dp),
            enabled = canDelete(entry), destructive = selected, onClick = onToggleDelete)
    }
}

@Composable
private fun MotionPlayerDialog(
    entry: MotionMediaLibrary.Entry,
    colors: UiPalette,
    strings: UiStrings,
    locale: Locale,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var video by remember(entry.contentUri) { mutableStateOf<VideoView?>(null) }
    var position by remember(entry.contentUri) { mutableIntStateOf(0) }
    var fullScreen by rememberSaveable(entry.contentUri.toString()) { mutableStateOf(false) }
    var playbackFailed by remember(entry.contentUri) { mutableStateOf(false) }
    val duration = entry.durationMs.coerceAtLeast(1L)
    DisposableEffect(entry.contentUri) { onDispose { video?.stopPlayback() } }
    LaunchedEffect(entry.contentUri) {
        while (isActive) {
            position = video?.currentPosition?.coerceAtLeast(0) ?: 0
            delay(250)
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = !fullScreen)) {
        val dialogView = LocalView.current
        val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
        DisposableEffect(dialogWindow, fullScreen) {
            val controller = dialogWindow?.let { WindowInsetsControllerCompat(it, it.decorView) }
            if (fullScreen) controller?.hide(WindowInsetsCompat.Type.systemBars())
            else controller?.show(WindowInsetsCompat.Type.systemBars())
            onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
        }
        val playerShape = if (fullScreen) RectangleShape else RoundedCornerShape(10.dp)
        Column(Modifier.fillMaxSize(if (fullScreen) 1f else .96f).clip(playerShape).background(colors.surface)
            .border(1.dp, colors.borderStrong, playerShape).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(entry.title, Modifier.weight(1f), color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                ActionButton(strings.motionText(if (fullScreen) R.string.motion_player_windowed
                    else R.string.motion_player_fullscreen), Icons.Outlined.OpenInFull, colors,
                    onClick = { fullScreen = !fullScreen })
                ActionButton(strings.motionText(R.string.motion_close), colors = colors, onClick = onClose)
            }
            AndroidView(factory = { viewContext ->
                VideoView(viewContext).also { player ->
                    val controller = MediaController(viewContext)
                    controller.setAnchorView(player)
                    player.setMediaController(controller)
                    player.setOnPreparedListener { it.isLooping = false; player.start() }
                    player.setOnErrorListener { _, what, extra ->
                        Log.e(MOTION_PLAYER_TAG, "Playback failed for ${entry.contentUri}: what=$what extra=$extra")
                        playbackFailed = true
                        player.stopPlayback()
                        video = null
                        Toast.makeText(context, strings.motionText(R.string.motion_player_error), Toast.LENGTH_LONG).show()
                        true
                    }
                    video = player
                    try {
                        player.setVideoURI(entry.contentUri)
                    } catch (error: RuntimeException) {
                        Log.e(MOTION_PLAYER_TAG, "Could not open Motion media ${entry.contentUri}", error)
                        playbackFailed = true
                        player.stopPlayback()
                        video = null
                        Toast.makeText(context, strings.motionText(R.string.motion_player_error), Toast.LENGTH_LONG).show()
                    }
                }
            }, modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(6.dp)))
            if (playbackFailed) Text(strings.motionText(R.string.motion_player_error),
                color = colors.yellow, fontSize = 13.sp)
            Canvas(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(4.dp))
                .background(colors.panelAlt).pointerInput(entry.contentUri, duration) {
                    detectTapGestures { point ->
                        val target = (point.x / size.width * duration.toFloat()).toLong().coerceIn(0L, duration)
                        video?.seekTo(target.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    }
                }) {
                val progressX = size.width * (position.toDouble() / duration).toFloat().coerceIn(0f, 1f)
                drawRect(colors.accent.copy(alpha = .55f), size = Size(progressX, size.height))
                entry.markers.filter { it.level == 1 || it.level == 2 }.forEach { marker ->
                    val startMs = marker.positionMs.coerceIn(0L, duration)
                    val intervalMs = marker.durationMs.coerceAtLeast(0L).coerceAtMost(duration - startMs)
                    val startX = size.width * (startMs.toDouble() / duration).toFloat()
                    val intervalWidth = size.width * (intervalMs.toDouble() / duration).toFloat()
                    val minimumWidth = 2.dp.toPx().coerceAtMost(size.width)
                    val left = startX.coerceAtMost((size.width - minimumWidth).coerceAtLeast(0f))
                    val bandWidth = maxOf(intervalWidth, minimumWidth).coerceAtMost(size.width - left)
                    drawRect(if (marker.level == 2) colors.red else colors.yellow,
                        topLeft = Offset(left, 0f), size = Size(bandWidth, size.height))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(position.toLong()), color = colors.muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val yellow = entry.markers.count { it.level == 1 }
                    val red = entry.markers.count { it.level == 2 }
                    if (yellow > 0) Text("${strings.motionText(R.string.motion_archive_yellow)}: $yellow",
                        color = colors.yellow, fontSize = 12.sp)
                    if (red > 0) Text("${strings.motionText(R.string.motion_archive_red)}: $red",
                        color = colors.red, fontSize = 12.sp)
                }
                Text(formatDuration(duration), color = colors.muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MotionCompositionDialog(
    initial: List<Int>,
    colors: UiPalette,
    strings: UiStrings,
    onClose: () -> Unit,
    onSave: (List<Int>) -> Unit,
) {
    var order by rememberSaveable { mutableStateOf(initial.joinToString(",")) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize(.93f).clip(RoundedCornerShape(10.dp)).background(colors.surface)
            .border(1.dp, colors.borderStrong, RoundedCornerShape(10.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings.motionText(R.string.motion_composition_edit), color = colors.text,
                fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(strings.motionText(R.string.motion_composition_hint), color = colors.muted, fontSize = 14.sp)
            MotionCompositionGrid(motionCameraOrder(order), colors, strings, Modifier.weight(1f).fillMaxWidth()) {
                order = it.joinToString(",")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(strings.motionText(R.string.motion_composition_save), colors = colors,
                    primary = true, modifier = Modifier.weight(1f)) { onSave(motionCameraOrder(order)) }
                ActionButton(strings.motionText(R.string.cancel), colors = colors,
                    mainBackground = true, modifier = Modifier.weight(1f), onClick = onClose)
            }
        }
    }
}

@Composable
private fun MotionCompositionGrid(order: List<Int>, colors: UiPalette, strings: UiStrings,
    modifier: Modifier, onChange: ((List<Int>) -> Unit)? = null) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var start by remember { mutableStateOf(Offset.Zero) }
    var position by remember { mutableStateOf(Offset.Zero) }
    val latestOrder by rememberUpdatedState(order)
    val update by rememberUpdatedState(onChange)
    fun tile(point: Offset) = if (size.width > 0 && size.height > 0)
        motionTileAt(point.x / size.width, point.y / size.height) else null
    val target = if (dragging != null) tile(position) else null
    Column(modifier.onSizeChanged { size = it }.clipToBounds()
        .then(if (onChange == null) Modifier else Modifier.pointerInput(Unit) {
            detectDragGesturesAfterLongPress(onDragStart = { dragging = tile(it); start = it; position = it },
                onDrag = { change, amount -> change.consume(); position += amount },
                onDragCancel = { dragging = null }, onDragEnd = {
                    val from = dragging
                    val to = tile(position)
                    if (from != null && to != null) update?.invoke(swapMotionCameras(latestOrder, from, to))
                    dragging = null
                })
        }), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(2) { row ->
            Row(Modifier.weight(1f).zIndex(if (dragging?.div(2) == row) 1f else 0f),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(2) { column ->
                    val index = row * 2 + column
                    val source = order[index]
                    val label = strings.motionText(listOf(R.string.motion_camera_front, R.string.motion_camera_rear,
                        R.string.motion_camera_left, R.string.motion_camera_right)[source])
                    Box(Modifier.weight(1f).fillMaxHeight().zIndex(if (dragging == index) 1f else 0f)
                        .graphicsLayer { if (dragging == index) {
                            translationX = position.x - start.x; translationY = position.y - start.y; alpha = .85f
                        } }.clip(RoundedCornerShape(7.dp)).background(colors.panelAlt)
                        .border(if (target == index) 2.dp else 1.dp,
                            if (target == index) colors.yellow else colors.borderStrong, RoundedCornerShape(7.dp))
                        .semantics {
                            contentDescription = label
                            if (onChange != null) customActions = (0..3).filter { it != index }.map { other ->
                                CustomAccessibilityAction(strings.motionText(R.string.motion_composition_swap,
                                    strings.motionText(listOf(R.string.motion_camera_front, R.string.motion_camera_rear,
                                        R.string.motion_camera_left, R.string.motion_camera_right)[order[other]]))) {
                                    onChange(swapMotionCameras(order, index, other)); true
                                }
                            }
                        }, contentAlignment = Alignment.Center) {
                        Text(label, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center)
                        CameraDirectionIcon(source, colors, Modifier.align(Alignment.TopEnd).padding(8.dp).size(38.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraDirectionIcon(camera: Int, colors: UiPalette, modifier: Modifier) {
    Canvas(modifier) {
        drawRoundRect(colors.muted, Offset(size.width * .34f, size.height * .22f),
            Size(size.width * .32f, size.height * .56f), CornerRadius(size.width * .08f), style = Stroke(2.dp.toPx()))
        val arrow = Path().apply {
            val points = when (camera) {
                0 -> listOf(Offset(.5f, 0f), Offset(.23f, .18f), Offset(.77f, .18f))
                1 -> listOf(Offset(.5f, 1f), Offset(.23f, .82f), Offset(.77f, .82f))
                2 -> listOf(Offset(0f, .5f), Offset(.28f, .24f), Offset(.28f, .76f))
                else -> listOf(Offset(1f, .5f), Offset(.72f, .24f), Offset(.72f, .76f))
            }
            moveTo(points[0].x * size.width, points[0].y * size.height)
            points.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) }; close()
        }
        drawPath(arrow, colors.yellow)
    }
}

@Composable
private fun ActionButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    colors: UiPalette, modifier: Modifier = Modifier, primary: Boolean = false, destructive: Boolean = false,
    enabled: Boolean = true, mainBackground: Boolean = false, onClick: () -> Unit) =
    ProductionActionButton(text, colors, modifier, icon, primary, destructive, enabled, mainBackground, onClick = onClick)

private fun UiStrings.motionText(id: Int, vararg args: Any): String {
    val value = resource(id)
    return if (args.isEmpty()) value else String.format(Locale.getDefault(), value, *args)
}

private fun motionLocale(context: Context): Locale = Locale.forLanguageTag(
    AppLanguage.read(context.getSharedPreferences("settings", Context.MODE_PRIVATE)))

private fun formatDuration(durationMs: Long): String {
    val seconds = (durationMs / 1000).coerceAtLeast(0)
    return "%d:%02d:%02d".format(Locale.US, seconds / 3600, (seconds / 60) % 60, seconds % 60)
}

private fun formatSize(bytes: Long): String = "%.1f GB".format(Locale.US,
    bytes.coerceAtLeast(0).toDouble() / MOTION_GB)
