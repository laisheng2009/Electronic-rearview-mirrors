package com.byd.extend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.byd.extend.CallsSettings
import com.byd.extend.R

@Composable
internal fun FormScope.CallsIntegrationSettings(
    state: CallsUiState,
    strings: UiStrings,
    colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit,
    onEditDestination: (CallsSettings.Profile) -> Unit,
): FormScope {
    row("calls-intro") {
        Text(strings.resource(R.string.calls_hint), color = colors.muted, fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
    }
    CallsProfileSection(
        CallsSettings.Profile.Sos, R.string.calls_profile_sos, R.string.calls_hold_sos, state.sos,
        strings, colors, onAction, onEditDestination,
    )
    CallsProfileSection(
        CallsSettings.Profile.Help, R.string.calls_profile_help, R.string.calls_hold_help, state.help,
        strings, colors, onAction, onEditDestination,
    )
    if (state.feedback.visible) {
        row("calls-feedback") { Box(Modifier.padding(14.dp)) { StatusText(state.feedback, colors) } }
    }
    return this
}

@Composable
private fun FormScope.CallsProfileSection(
    profile: CallsSettings.Profile,
    titleRes: Int,
    holdTitleRes: Int,
    value: CallsProfileUiState,
    strings: UiStrings,
    colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit,
    onEditDestination: (CallsSettings.Profile) -> Unit,
): FormScope {
    val title = strings.resource(titleRes)
    val holdTitle = strings.resource(holdTitleRes)
    FormSection(title, colors, bodyPadding = 10.dp, key = "calls-${profile.keyName()}") {
        row("${profile.keyName()}-enable") {
            SwitchLine(holdTitle, strings.resource(R.string.calls_profile_hint), value.enabled,
                { onAction(BydExtendUiAction.Calls(CallsUiAction.SetEnabled(profile, it))) },
                colors, compactSwitch = false)
        }
        row("${profile.keyName()}-destination") {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp))
                .background(colors.panelAlt).border(1.dp, colors.borderStrong, RoundedCornerShape(7.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(strings.resource(R.string.calls_destination), color = colors.muted, fontSize = 12.sp)
                Text(value.name.ifBlank {
                    strings.resource(if (value.number.isBlank()) R.string.calls_not_selected else R.string.calls_manual_number)
                },
                    color = if (value.number.isBlank()) colors.muted else colors.text,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                if (value.number.isNotBlank()) Text(value.number, color = colors.text,
                    fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            }
        }
        row("${profile.keyName()}-actions") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(strings.resource(R.string.calls_choose_contact), colors,
                    Modifier.weight(1f), enabled = value.enabled, primary = true) {
                    onAction(BydExtendUiAction.Calls(CallsUiAction.BeginContactSelection(profile)))
                }
                ActionButton(strings.resource(R.string.calls_enter_manually), colors,
                    Modifier.weight(1f), enabled = value.enabled, mainBackground = true) {
                    onEditDestination(profile)
                }
            }
        }
    }
    return this
}

@Composable
internal fun CallsDestinationDialog(
    profile: CallsSettings.Profile,
    initial: CallsProfileUiState,
    strings: UiStrings,
    colors: UiPalette,
    onSave: (name: String, normalizedNumber: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(profile) { mutableStateOf(initial.name) }
    var number by rememberSaveable(profile) { mutableStateOf(initial.number) }
    val normalizedNumber = CallsSettings.normalizeNumber(number)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth().widthIn(max = 540.dp).clip(RoundedCornerShape(8.dp))
                .background(colors.surface).border(1.dp, colors.borderStrong, RoundedCornerShape(8.dp))
                .padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings.resource(if (profile == CallsSettings.Profile.Sos)
                    R.string.calls_hold_sos else R.string.calls_hold_help),
                    color = colors.text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                CallsTextInput(strings.resource(R.string.calls_contact_name), name, { name = it },
                    keyboardType = KeyboardType.Text, imeAction = ImeAction.Next, colors = colors)
                CallsTextInput(strings.resource(R.string.calls_phone_number), number, { number = it },
                    keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done, colors = colors)
                Text(if (normalizedNumber == null && number.isNotBlank())
                    strings.resource(R.string.calls_number_invalid)
                else strings.resource(R.string.calls_number_hint),
                    color = if (normalizedNumber == null && number.isNotBlank()) colors.red else colors.muted,
                    fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    ActionButton(strings.resource(R.string.calls_save), colors,
                        Modifier.width(150.dp), primary = true, enabled = normalizedNumber != null) {
                        normalizedNumber?.let { onSave(name, it) }
                    }
                    Spacer(Modifier.size(8.dp))
                    ActionButton(strings.resource(R.string.calls_cancel), colors,
                        Modifier.width(150.dp), mainBackground = true, onClick = onDismiss)
                }
            }
        }
    }
}

@Composable
private fun CallsTextInput(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    colors: UiPalette,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(colors.field)
            .border(1.dp, colors.borderStrong, RoundedCornerShape(7.dp)).padding(horizontal = 12.dp, vertical = 11.dp)) {
            BasicTextField(value, onValueChange, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                textStyle = TextStyle(color = colors.text, fontSize = 16.sp),
                cursorBrush = SolidColor(colors.accent), modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun CallsSettings.Profile.keyName() = when (this) {
    CallsSettings.Profile.Sos -> "sos"
    CallsSettings.Profile.Help -> "help"
}
