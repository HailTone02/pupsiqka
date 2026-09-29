package com.pupsikcall.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun PupsikCallsScreen(
    state: CallHistoryState,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenMessages: () -> Unit,
) {
    val palette = LocalPupsikPalette.current
    Column(Modifier.fillMaxSize().background(palette.background)) {
        ScreenHeader(stringResource(R.string.calls))
        when (val currentState = state) {
            CallHistoryState.Loading -> CallHistoryMessageState {
                CircularProgressIndicator(color = palette.bronze)
                Spacer(Modifier.height(PupsikSpacing.medium))
                Text(stringResource(R.string.call_history_loading), color = palette.muted)
            }
            CallHistoryState.Empty -> CallHistoryMessageState {
                Icon(Icons.Filled.Call, contentDescription = null, tint = palette.muted, modifier = Modifier.size(34.dp))
                Spacer(Modifier.height(PupsikSpacing.medium))
                Text(stringResource(R.string.no_recent_calls), color = palette.muted, style = MaterialTheme.typography.bodyLarge)
            }
            CallHistoryState.SignedOut -> CallHistoryMessageState {
                Text(stringResource(R.string.profile_signed_out), color = palette.muted)
            }
            CallHistoryState.Error -> CallHistoryMessageState {
                Text(stringResource(R.string.call_history_error), color = palette.danger)
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.call_history_retry), color = palette.bronze)
                }
            }
            is CallHistoryState.Loaded -> {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = PupsikSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(PupsikSpacing.small),
                ) {
                    items(currentState.calls, key = { it.callId }) { call ->
                        CallHistoryRow(call)
                    }
                    if (currentState.nextCursor != null) {
                        item {
                            TextButton(onClick = onLoadMore, enabled = !currentState.loadingMore) {
                                if (currentState.loadingMore) {
                                    CircularProgressIndicator(Modifier.size(16.dp), color = palette.bronze)
                                } else {
                                    Text(stringResource(R.string.call_history_load_more), color = palette.bronze)
                                }
                            }
                        }
                    }
                }
            }
        }
        PupsikBottomNavigation("calls", onOpenContacts, {}, onOpenMessages)
    }
}

@Composable
private fun ColumnScope.CallHistoryMessageState(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxWidth().padding(horizontal = PupsikSpacing.medium),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun CallHistoryRow(call: CallHistoryRecord) {
    val palette = LocalPupsikPalette.current
    val directionLabel = stringResource(
        if (call.direction == CallHistoryDirection.Incoming) R.string.call_history_incoming else R.string.call_history_outgoing,
    )
    val counterpartName = call.counterpartDisplayName?.takeIf(String::isNotBlank)
        ?: call.counterpartUserId.toString()
    Row(
        Modifier.fillMaxWidth().clip(PupsikShapes.panel).background(palette.surfaceRaised)
            .then(if (palette == PupsikPalettes.Light) Modifier.border(1.dp, palette.outline.copy(alpha = 0.72f), PupsikShapes.panel) else Modifier)
            .padding(PupsikSpacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Call, contentDescription = null, tint = palette.bronze, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(start = PupsikSpacing.medium)) {
            Text(counterpartName, color = palette.text, style = MaterialTheme.typography.titleMedium)
            Text(
                "$directionLabel - ${stringResource(call.status.historyLabelResource())}",
                color = palette.muted,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                listOfNotNull(call.createdAt, call.durationSeconds?.let(::formatCallDuration)).joinToString(" - "),
                color = palette.muted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
    }
}

private fun AuthenticatedCallStatus.historyLabelResource(): Int = when (this) {
    AuthenticatedCallStatus.COMPLETED -> R.string.call_history_completed
    AuthenticatedCallStatus.DECLINED -> R.string.call_history_declined
    AuthenticatedCallStatus.CANCELLED -> R.string.call_history_cancelled
    AuthenticatedCallStatus.MISSED -> R.string.call_history_missed
    AuthenticatedCallStatus.FAILED -> R.string.call_history_failed
    else -> R.string.call_history_error
}

private fun formatCallDuration(durationSeconds: Long): String {
    val minutes = durationSeconds / 60
    val seconds = durationSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
internal fun PupsikSettingsScreen(
    appearance: AppearanceMode,
    onAppearanceChange: (AppearanceMode) -> Unit,
    languageCodes: List<String>,
    languageNames: List<String>,
    selectedLanguageIndex: Int,
    onLanguageSelected: (String) -> Unit,
    onOpenProfile: () -> Unit,
    onLogout: () -> Unit,
) {
    val palette = LocalPupsikPalette.current
    val lightUi = palette == PupsikPalettes.Light
    Column(Modifier.fillMaxSize().background(palette.background)) {
        ScreenHeader(stringResource(R.string.settings))
        Column(Modifier.weight(1f).padding(horizontal = PupsikSpacing.large)) {
            Text(stringResource(R.string.appearance), color = palette.text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = PupsikSpacing.large, bottom = PupsikSpacing.medium))
            Row(
                Modifier.fillMaxWidth().clip(PupsikShapes.control).background(palette.surface)
                    .then(if (lightUi) Modifier.border(1.dp, palette.outline.copy(alpha = 0.72f), PupsikShapes.control) else Modifier)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(AppearanceMode.SYSTEM, AppearanceMode.LIGHT, AppearanceMode.DARK).forEach { mode ->
                    val label = when (mode) {
                        AppearanceMode.SYSTEM -> stringResource(R.string.appearance_system)
                        AppearanceMode.LIGHT -> stringResource(R.string.appearance_light)
                        AppearanceMode.DARK -> stringResource(R.string.appearance_dark)
                    }
                    val selected = mode == appearance
                    Text(
                        text = label,
                        color = if (selected && lightUi) Color.White else if (selected) palette.text else palette.muted,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f).clip(PupsikShapes.control)
                            .background(if (selected && lightUi) palette.bronze else if (selected) palette.surfaceRaised else Color.Transparent)
                            .clickable { onAppearanceChange(mode) }.padding(vertical = 12.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            Text(stringResource(R.string.language), color = palette.text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = PupsikSpacing.xLarge, bottom = PupsikSpacing.small))
            var languagesExpanded by remember { mutableStateOf(false) }
            Box {
                Row(
                    Modifier.fillMaxWidth().clip(PupsikShapes.control)
                        .background(if (lightUi) palette.surfaceRaised else palette.surface)
                        .then(if (lightUi) Modifier.border(1.dp, palette.outline.copy(alpha = 0.72f), PupsikShapes.control) else Modifier)
                        .clickable { languagesExpanded = true }.padding(PupsikSpacing.medium),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(languageNames[selectedLanguageIndex], color = palette.text, style = MaterialTheme.typography.bodyLarge)
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.language), tint = palette.muted)
                }
                DropdownMenu(expanded = languagesExpanded, onDismissRequest = { languagesExpanded = false }) {
                    languageNames.forEachIndexed { index, name ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                languagesExpanded = false
                                onLanguageSelected(languageCodes[index])
                            },
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth()
                    .then(if (lightUi) Modifier.clip(PupsikShapes.control).background(palette.surfaceRaised).border(1.dp, palette.outline.copy(alpha = 0.72f), PupsikShapes.control) else Modifier)
                    .clickable(onClick = onOpenProfile)
                    .padding(horizontal = if (lightUi) PupsikSpacing.medium else 0.dp, vertical = PupsikSpacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Person, contentDescription = null, tint = palette.bronze)
                Text(stringResource(R.string.profile), color = palette.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = PupsikSpacing.medium))
            }
            TextButton(onClick = onLogout) { Text(stringResource(R.string.sign_out), color = palette.danger) }
        }
    }
}

@Composable
internal fun PupsikProfileScreen(
    state: AuthenticatedProfileState,
    phoneState: PhoneVerificationState,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onRetry: () -> Unit,
    onSaveDisplayName: suspend (String) -> ProfileUpdateResult,
    onLoadPhone: suspend () -> Unit,
    onRequestPhone: suspend (String) -> Unit,
    onResendPhone: suspend () -> Unit,
    onVerifyPhone: suspend (String) -> Unit,
) {
    val palette = LocalPupsikPalette.current
    val scope = rememberCoroutineScope()
    var displayName by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var saveResult by remember { mutableStateOf<ProfileUpdateResult?>(null) }
    val profile = (state as? AuthenticatedProfileState.Profile)?.profile
    val email = (state as? AuthenticatedProfileState.Profile)?.email
    LaunchedEffect(profile?.userId, profile?.displayName) {
        if (profile != null) displayName = profile.displayName.orEmpty()
    }
    LaunchedEffect(profile?.userId) {
        if (profile != null) onLoadPhone()
    }
    Column(Modifier.fillMaxSize().background(palette.background)) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = palette.text) }
            Text(stringResource(R.string.profile), color = palette.text, style = MaterialTheme.typography.titleLarge)
        }
        when (state) {
            AuthenticatedProfileState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = palette.bronze)
                    Text(stringResource(R.string.profile_loading), color = palette.muted, modifier = Modifier.padding(top = PupsikSpacing.medium))
                }
            }
            AuthenticatedProfileState.SignedOut -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.profile_signed_out), color = palette.muted)
            }
            is AuthenticatedProfileState.MissingProfile -> ProfileMessage(
                message = stringResource(R.string.profile_missing),
                action = stringResource(R.string.profile_retry),
                onAction = onRetry,
            )
            is AuthenticatedProfileState.Error -> ProfileMessage(
                message = stringResource(R.string.profile_error),
                action = stringResource(R.string.profile_retry),
                onAction = onRetry,
            )
            is AuthenticatedProfileState.Profile -> Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = PupsikSpacing.large),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(PupsikSpacing.section))
                PupsikAvatar(profileInitials(state.profile.displayName, email), 108.dp)
                Text(
                    state.profile.displayName ?: stringResource(R.string.profile_name_not_set),
                    color = if (state.profile.displayName == null) palette.muted else palette.text,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = PupsikSpacing.medium),
                )
                Spacer(Modifier.height(PupsikSpacing.xLarge))
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it; saveResult = null },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving,
                    label = { Text(stringResource(R.string.profile_display_name)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.bronze,
                        unfocusedBorderColor = palette.outline,
                        focusedTextColor = palette.text,
                        unfocusedTextColor = palette.text,
                        cursorColor = palette.bronze,
                    ),
                )
                Button(
                    onClick = {
                        scope.launch {
                            saving = true
                            saveResult = onSaveDisplayName(displayName)
                            saving = false
                        }
                    },
                    enabled = !saving && displayName.trim().isNotEmpty() && displayName.trim() != state.profile.displayName,
                    modifier = Modifier.fillMaxWidth().padding(top = PupsikSpacing.medium),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.bronze),
                ) {
                    if (saving) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text(stringResource(R.string.save_profile))
                }
                saveResult?.let { result ->
                    val resultText = when (result) {
                        ProfileUpdateResult.UPDATED -> stringResource(R.string.profile_saved)
                        ProfileUpdateResult.INVALID_NAME -> stringResource(R.string.profile_invalid_name)
                        ProfileUpdateResult.NOT_AUTHENTICATED -> stringResource(R.string.profile_signed_out)
                        ProfileUpdateResult.MISSING_PROFILE -> stringResource(R.string.profile_missing)
                        ProfileUpdateResult.FAILED -> stringResource(R.string.profile_error)
                    }
                    Text(resultText, color = palette.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = PupsikSpacing.small))
                }
                if (email != null) {
                    ProfileDetail(stringResource(R.string.account), email)
                }
                PhoneVerificationSection(
                    state = phoneState,
                    onLoad = onLoadPhone,
                    onRequest = onRequestPhone,
                    onResend = onResendPhone,
                    onVerify = onVerifyPhone,
                )
                Spacer(Modifier.height(PupsikSpacing.large))
                TextButton(onClick = onLogout, modifier = Modifier.padding(bottom = PupsikSpacing.large)) { Text(stringResource(R.string.sign_out), color = palette.danger) }
            }
        }
    }
}

@Composable
private fun ProfileMessage(message: String, action: String, onAction: () -> Unit) {
    val palette = LocalPupsikPalette.current
    Column(
        Modifier.fillMaxSize().padding(PupsikSpacing.large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, color = palette.muted, style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onAction, modifier = Modifier.padding(top = PupsikSpacing.small)) {
            Text(action, color = palette.bronze)
        }
    }
}

@Composable
private fun ProfileDetail(label: String, value: String) {
    val palette = LocalPupsikPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = PupsikSpacing.small)) {
        Text(label, color = palette.muted, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = palette.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = PupsikSpacing.xSmall))
    }
}

@Composable
internal fun PupsikIncomingCallScreen(
    peerName: String,
    errorMessage: String?,
    onDecline: () -> Unit,
    onAnswer: () -> Unit,
) {
    val palette = LocalPupsikPalette.current
    CallBackdrop(peerName) {
        Column(Modifier.fillMaxSize().padding(horizontal = PupsikSpacing.large, vertical = PupsikSpacing.medium), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(0.35f))
            OrbitalAvatar(peerName)
            Text(peerName, color = palette.text, style = MaterialTheme.typography.displayMedium, modifier = Modifier.padding(top = PupsikSpacing.large))
            Text(stringResource(R.string.incoming_call_status), color = palette.muted, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = PupsikSpacing.small))
            if (errorMessage != null) Text(errorMessage, color = palette.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = PupsikSpacing.small))
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SwipeCallAction(stringResource(R.string.swipe_to_decline), positive = false, onComplete = onDecline)
                SwipeCallAction(stringResource(R.string.swipe_to_answer), positive = true, onComplete = onAnswer)
            }
            Spacer(Modifier.height(PupsikSpacing.large))
        }
    }
}

@Composable
internal fun PupsikActiveCallScreen(
    peerName: String,
    isMuted: Boolean,
    speakerEnabled: Boolean,
    callState: WebRtcCallState,
    errorMessage: String?,
    iceDiagnostics: String,
    onBack: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onEndCall: () -> Unit,
) {
    val palette = LocalPupsikPalette.current
    CallBackdrop(peerName) {
        Column(Modifier.fillMaxSize().padding(horizontal = PupsikSpacing.large, vertical = PupsikSpacing.small), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = palette.text) }
                Spacer(Modifier.weight(1f))
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.call_controls), tint = palette.muted)
            }
            Spacer(Modifier.weight(0.5f))
            OrbitalAvatar(peerName)
            Text(peerName, color = palette.text, style = MaterialTheme.typography.displayMedium, modifier = Modifier.padding(top = PupsikSpacing.large))
            val status = when (callState) {
                WebRtcCallState.CONNECTED -> stringResource(R.string.call_connected)
                WebRtcCallState.FAILED -> RuntimeDiagnostic.failureDisplayText(BuildConfig.DEBUG, stringResource(R.string.call_state_failed), errorMessage)
                else -> callStateText(callState)
            }
            Text(status, color = if (callState == WebRtcCallState.CONNECTED) palette.caramel else palette.muted, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = PupsikSpacing.small))
            if (errorMessage != null) Text(errorMessage, color = palette.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = PupsikSpacing.small))
            if (BuildConfig.DEBUG && iceDiagnostics.isNotBlank()) Text(iceDiagnostics, color = palette.muted, fontSize = 10.sp, modifier = Modifier.padding(top = PupsikSpacing.small))
            Spacer(Modifier.height(PupsikSpacing.large))
            PupsikVoiceWaveform(Modifier.fillMaxWidth(0.58f).height(45.dp), active = callState == WebRtcCallState.CONNECTED)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                CallToggle(stringResource(R.string.mute), isMuted, onToggleMute) { MicrophoneIcon(isMuted) }
                CallToggle(stringResource(R.string.speaker), speakerEnabled, onToggleSpeaker) { SpeakerIcon() }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = onEndCall, modifier = Modifier.size(70.dp).clip(CircleShape).background(palette.danger)) {
                        Icon(Icons.Filled.Call, contentDescription = stringResource(R.string.end_call_description), tint = Color.White, modifier = Modifier.size(30.dp).rotate(135f))
                    }
                    Text(stringResource(R.string.end_call), color = palette.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = PupsikSpacing.small))
                }
            }
            Spacer(Modifier.height(PupsikSpacing.large))
        }
    }
}

@Composable
private fun CallBackdrop(peerName: String, content: @Composable () -> Unit) {
    val palette = LocalPupsikPalette.current
    Box(Modifier.fillMaxSize().background(palette.background)) {
        PupsikAvatar(peerName.take(1), 300.dp, Modifier.align(Alignment.Center).blur(46.dp).scale(1.2f))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(palette.background.copy(alpha = 0.54f), palette.background.copy(alpha = 0.88f), palette.background))))
        content()
    }
}

@Composable
private fun OrbitalAvatar(peerName: String) {
    val palette = LocalPupsikPalette.current
    val motion = rememberInfiniteTransition(label = "avatar-orbit")
    val rotation by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(14000), RepeatMode.Restart), label = "orbit-rotation")
    val pulse by motion.animateFloat(0.94f, 1.04f, infiniteRepeatable(tween(1700), RepeatMode.Reverse), label = "avatar-pulse")
    Box(Modifier.size(226.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().rotate(rotation)) {
            drawCircle(palette.bronze.copy(alpha = 0.22f), style = Stroke(width = 1.dp.toPx()))
            drawArc(palette.caramel, -52f, 96f, false, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            drawCircle(palette.glow, radius = 4.dp.toPx(), center = Offset(size.width * 0.86f, size.height * 0.32f))
        }
        Canvas(Modifier.size(194.dp).rotate(-rotation * 0.62f)) {
            drawCircle(palette.caramel.copy(alpha = 0.28f), style = Stroke(width = 1.dp.toPx()))
            drawArc(palette.glow.copy(alpha = 0.9f), 132f, 62f, false, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
        }
        PupsikAvatar(peerName.take(1), 154.dp, Modifier.scale(pulse))
    }
}

@Composable
private fun SwipeCallAction(label: String, positive: Boolean, onComplete: () -> Unit) {
    val palette = LocalPupsikPalette.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val travel = with(density) { 92.dp.toPx() }
    val drag = remember { Animatable(0f) }
    val actionColor = if (positive) palette.online else palette.danger
    val progress = (drag.value / travel).coerceIn(0f, 1f)
    val direction = if (positive) 1 else -1
    val thumbX = if (positive) drag.value else -drag.value
    Box(
        Modifier.width(146.dp).height(64.dp).clip(PupsikShapes.capsule)
            .background(actionColor.copy(alpha = 0.12f + progress * 0.2f))
            .border(1.dp, actionColor.copy(alpha = 0.3f + progress * 0.45f), PupsikShapes.capsule),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = palette.text, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Box(
            Modifier.align(if (positive) Alignment.CenterStart else Alignment.CenterEnd)
                .offset { IntOffset(thumbX.roundToInt(), 0) }
                .size(54.dp).clip(CircleShape).background(actionColor.copy(alpha = 0.82f + progress * 0.18f))
                .pointerInput(positive, onComplete) {
                    detectDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (drag.value >= travel * 0.72f) {
                                    drag.animateTo(travel, spring(dampingRatio = 0.72f, stiffness = 340f))
                                    onComplete()
                                    drag.snapTo(0f)
                                } else {
                                    drag.animateTo(0f, spring(dampingRatio = 0.54f, stiffness = 380f))
                                }
                            }
                        },
                        onDragCancel = { scope.launch { drag.animateTo(0f, spring()) } },
                    ) { change, amount ->
                        change.consume()
                        scope.launch { drag.snapTo((drag.value + amount.x * direction).coerceIn(0f, travel)) }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Call, contentDescription = label, tint = Color.White, modifier = Modifier.size(24.dp).rotate(if (positive) 0f else 135f))
        }
    }
}

@Composable
private fun CallToggle(label: String, selected: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val palette = LocalPupsikPalette.current
    val tint = if (selected) palette.caramel else palette.muted
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(62.dp).clip(CircleShape)
                .background(if (selected) palette.bronze.copy(alpha = 0.32f) else palette.callGlass)
                .border(1.dp, if (selected) palette.glow.copy(alpha = 0.8f) else palette.outline.copy(alpha = 0.75f), CircleShape),
        ) { Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() } }
        Text(label, color = tint, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = PupsikSpacing.small))
    }
}

@Composable
private fun PupsikVoiceWaveform(modifier: Modifier = Modifier, active: Boolean) {
    val palette = LocalPupsikPalette.current
    val transition = rememberInfiniteTransition(label = "voice-wave")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(if (active) 900 else 1700), RepeatMode.Reverse), label = "wave-phase")
    Canvas(modifier) {
        val count = 19
        val gap = size.width / (count * 1.9f)
        val barWidth = gap * 0.55f
        repeat(count) { index ->
            val x = gap * index * 1.45f + barWidth / 2f
            val wave = kotlin.math.abs(kotlin.math.sin(index * 0.72f + phase * 4.4f))
            val base = if (active) 0.16f else 0.08f
            val height = size.height * (base + wave * if (active) 0.7f else 0.3f)
            drawRoundRect(palette.caramel.copy(alpha = 0.45f + wave * 0.48f), Offset(x, (size.height - height) / 2f), Size(barWidth, height), androidx.compose.ui.geometry.CornerRadius(barWidth))
        }
    }
}

@Composable
private fun PupsikAvatar(initial: String, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val palette = LocalPupsikPalette.current
    val lightUi = palette == PupsikPalettes.Light
    val avatarBrush = if (lightUi) {
        Brush.linearGradient(listOf(Color(0xFFFFF8EC), Color(0xFFEAD7BA)))
    } else {
        Brush.linearGradient(listOf(palette.caramel.copy(alpha = 0.9f), palette.bronze, palette.surfaceRaised))
    }
    Box(
        modifier.size(size).clip(CircleShape).background(avatarBrush)
            .border(1.dp, if (lightUi) palette.outline else palette.glow.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial.uppercase().take(1), color = if (lightUi) palette.bronze else palette.text, fontSize = if (size >= 100.dp) 48.sp else 22.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun PupsikBottomNavigation(selected: String, onContacts: () -> Unit, onCalls: () -> Unit, onMessages: () -> Unit) {
    val palette = LocalPupsikPalette.current
    val lightUi = palette == PupsikPalettes.Light
    Row(
        if (lightUi) {
            Modifier.fillMaxWidth().padding(horizontal = PupsikSpacing.medium, vertical = PupsikSpacing.small)
                .height(68.dp).clip(PupsikShapes.panel).background(palette.surfaceRaised)
                .border(1.dp, palette.outline.copy(alpha = 0.72f), PupsikShapes.panel).padding(horizontal = 12.dp)
        } else {
            Modifier.fillMaxWidth().height(72.dp).background(palette.surface).padding(horizontal = PupsikSpacing.medium)
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        BottomNavigationItem(stringResource(R.string.contacts), "contacts", selected, onContacts) { Icon(Icons.Filled.Person, contentDescription = null) }
        BottomNavigationItem(stringResource(R.string.calls), "calls", selected, onCalls) { Icon(Icons.Filled.Call, contentDescription = null) }
        BottomNavigationItem(stringResource(R.string.messages), "messages", selected, onMessages) { Icon(Icons.Filled.Email, contentDescription = null) }
    }
}

@Composable
private fun BottomNavigationItem(label: String, key: String, selected: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val palette = LocalPupsikPalette.current
    val lightUi = palette == PupsikPalettes.Light
    val tint = if (key == selected) palette.bronze else palette.muted
    Column(Modifier.clip(PupsikShapes.control).clickable(onClick = onClick).padding(horizontal = if (lightUi) 14.dp else PupsikSpacing.medium, vertical = PupsikSpacing.small), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(if (lightUi) 30.dp else 22.dp).clip(CircleShape)
                .background(if (lightUi && key == selected) palette.bronze.copy(alpha = 0.12f) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) { androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides tint) { icon() } }
        Text(label, color = tint, fontSize = 11.sp, fontWeight = if (key == selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(top = PupsikSpacing.xSmall))
    }
}

@Composable
internal fun ScreenHeader(title: String) {
    val palette = LocalPupsikPalette.current
    val lightUi = palette == PupsikPalettes.Light
    Text(title, color = palette.text, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(horizontal = PupsikSpacing.large, vertical = if (lightUi) 20.dp else PupsikSpacing.medium))
}

@Composable
private fun BronzeButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPupsikPalette.current
    Button(onClick = onClick, modifier = modifier.height(54.dp), shape = if (palette == PupsikPalettes.Light) PupsikShapes.control else PupsikShapes.capsule, colors = ButtonDefaults.buttonColors(containerColor = palette.bronze, contentColor = Color.White)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun MicrophoneIcon(muted: Boolean) {
    val palette = LocalPupsikPalette.current
    Canvas(Modifier.size(24.dp)) {
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        drawRoundRect(Color.White, Offset(size.width * 0.38f, size.height * 0.12f), Size(size.width * 0.24f, size.height * 0.48f), androidx.compose.ui.geometry.CornerRadius(size.width * 0.12f), style = stroke)
        drawArc(Color.White, 0f, 180f, false, Offset(size.width * 0.2f, size.height * 0.32f), Size(size.width * 0.6f, size.height * 0.46f), style = stroke)
        drawLine(Color.White, Offset(size.width * 0.5f, size.height * 0.78f), Offset(size.width * 0.5f, size.height * 0.93f), 2.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Color.White, Offset(size.width * 0.3f, size.height * 0.94f), Offset(size.width * 0.7f, size.height * 0.94f), 2.dp.toPx(), cap = StrokeCap.Round)
        if (muted) drawLine(palette.glow, Offset(size.width * 0.12f, size.height * 0.12f), Offset(size.width * 0.88f, size.height * 0.88f), 2.dp.toPx(), cap = StrokeCap.Round)
    }
}

@Composable
private fun SpeakerIcon() {
    Canvas(Modifier.size(24.dp)) {
        val speaker = Path().apply {
            moveTo(size.width * 0.12f, size.height * 0.4f)
            lineTo(size.width * 0.32f, size.height * 0.4f)
            lineTo(size.width * 0.56f, size.height * 0.2f)
            lineTo(size.width * 0.56f, size.height * 0.8f)
            lineTo(size.width * 0.32f, size.height * 0.6f)
            lineTo(size.width * 0.12f, size.height * 0.6f)
            close()
        }
        drawPath(speaker, Color.White)
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        drawArc(Color.White, -52f, 104f, false, Offset(size.width * 0.4f, size.height * 0.25f), Size(size.width * 0.45f, size.height * 0.5f), style = stroke)
        drawArc(Color.White, -52f, 104f, false, Offset(size.width * 0.43f, size.height * 0.08f), Size(size.width * 0.58f, size.height * 0.84f), style = stroke)
    }
}
