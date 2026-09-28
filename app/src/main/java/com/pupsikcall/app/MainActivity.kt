package com.pupsikcall.app

import android.os.Bundle
import android.util.Log
import android.Manifest
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.UUID
import kotlinx.coroutines.launch

private val AppBackground = Color(0xFF111015)
private val AppSurface = Color(0xFF1D1B23)
private val FieldBackground = Color(0xFF211F27)
private val FieldBorder = Color(0xFF302D37)
private val PrimaryPurple = Color(0xFF8B5CF6)
private val LightPurple = Color(0xFFB89AFF)
private val OnlineGreen = Color(0xFF32D583)
private val DeclineRed = Color(0xFFFF5364)
private val MainText = Color(0xFFF8F7FA)
private val SecondaryText = Color(0xFFA29EAA)
private const val PermissionLogTag = "PupsikCallPermission"

private fun AuthMessage.stringResourceId(): Int = when (this) {
    AuthMessage.CONFIRMATION_REQUIRED -> R.string.auth_confirmation_required
    AuthMessage.CONFIGURATION_MISSING -> R.string.auth_configuration_missing
    AuthMessage.SESSION_RESTORE_FAILED -> R.string.auth_session_restore_failed
    AuthMessage.INVALID_EMAIL -> R.string.auth_invalid_email
    AuthMessage.PASSWORD_REQUIRED -> R.string.auth_password_required
    AuthMessage.WEAK_PASSWORD -> R.string.auth_weak_password
    AuthMessage.DUPLICATE_ACCOUNT -> R.string.auth_duplicate_account
    AuthMessage.INVALID_CREDENTIALS -> R.string.auth_invalid_credentials
    AuthMessage.EMAIL_NOT_CONFIRMED -> R.string.auth_email_not_confirmed
    AuthMessage.SIGNUP_DISABLED -> R.string.auth_signup_disabled
    AuthMessage.RATE_LIMITED -> R.string.auth_rate_limited
    AuthMessage.NETWORK_ERROR -> R.string.auth_network_error
    AuthMessage.REGISTER_FAILED -> R.string.auth_register_failed
    AuthMessage.LOGIN_FAILED -> R.string.auth_login_failed
    AuthMessage.LOGIN_UNVERIFIED -> R.string.auth_login_unverified
    AuthMessage.LOGOUT_FAILED -> R.string.auth_logout_failed
}

private enum class DemoScreen { SignIn, Contacts, IncomingCall, ActiveCall }

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AppBackground.toArgb()
        window.navigationBarColor = AppBackground.toArgb()
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent { PupsikCallApp() }
    }
}

@Composable
private fun PupsikCallApp() {
    var screen by rememberSaveable { mutableStateOf(DemoScreen.SignIn) }
    var isMuted by rememberSaveable { mutableStateOf(false) }
    var speakerEnabled by rememberSaveable { mutableStateOf(true) }
    var callState by remember { mutableStateOf(WebRtcCallState.IDLE) }
    var callError by remember { mutableStateOf<String?>(null) }
    var iceDiagnostics by remember { mutableStateOf("") }
    var engine by remember { mutableStateOf<WebRtcAudioCallEngine?>(null) }
    var currentCallId by remember { mutableStateOf<String?>(null) }
    var peerDeviceId by remember { mutableStateOf<String?>(null) }
    var pendingRemoteOffer by remember { mutableStateOf<String?>(null) }
    var pendingRemoteIce by remember { mutableStateOf(emptyList<LocalIceCandidate>()) }
    var peerOnline by remember { mutableStateOf(false) }
    var calleeAccepted by remember { mutableStateOf(false) }
    var signalingInstance: SupabaseCallSignaling? by remember { mutableStateOf(null) }
    val context = LocalContext.current
    val deviceId = BuildConfig.PUPSIKCALL_DEVICE_ID.ifBlank { "pupsik-a" }
    val languageCodes = stringArrayResource(R.array.supported_language_codes).toList()
    val languageNames = stringArrayResource(R.array.supported_language_names).toList()
    val applicationLocaleTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val systemLocaleTag = LocalConfiguration.current.locales.get(0)?.toLanguageTag().orEmpty()
    val selectedLanguageIndex = selectedLanguageIndex(languageCodes, applicationLocaleTags, systemLocaleTag)
    val signaling = remember {
        var signalingRef: SupabaseCallSignaling? = null
        val newSignaling = SupabaseCallSignaling(
            deviceId = deviceId,
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
            listener = object : SupabaseCallSignaling.Listener {
                override fun onCallInvite(callId: String, fromDeviceId: String, toDeviceId: String) {
                    if (currentCallId != null && currentCallId != callId) return
                    callError = null
                    calleeAccepted = false
                    currentCallId = callId
                    peerDeviceId = fromDeviceId
                    signalingRef?.prepareCall(callId)
                    screen = DemoScreen.IncomingCall
                }

                override fun onCallAccepted(callId: String, fromDeviceId: String) {
                    if (currentCallId != callId || peerDeviceId != fromDeviceId) return
                    engine?.startOffer()
                }

                override fun onCallDeclined(callId: String, fromDeviceId: String) {
                    if (currentCallId == callId && peerDeviceId == fromDeviceId) {
                        currentCallId = null
                        peerDeviceId = null
                        pendingRemoteOffer = null
                        pendingRemoteIce = emptyList()
                        engine?.dispose("peer declined")
                        engine = null
                        callState = WebRtcCallState.IDLE
                        callError = null
                        screen = DemoScreen.Contacts
                    }
                }

                override fun onCallEnded(callId: String, fromDeviceId: String) {
                    if (currentCallId == callId && peerDeviceId == fromDeviceId) {
                        currentCallId = null
                        peerDeviceId = null
                        pendingRemoteOffer = null
                        pendingRemoteIce = emptyList()
                        engine?.dispose("peer ended call")
                        engine = null
                        callState = WebRtcCallState.IDLE
                        callError = null
                        screen = DemoScreen.Contacts
                    }
                }

                override fun onRemoteOffer(callId: String, fromDeviceId: String, sdp: String) {
                    if (currentCallId != null && currentCallId != callId) return
                    currentCallId = callId
                    peerDeviceId = fromDeviceId
                    if (screen != DemoScreen.ActiveCall) {
                        pendingRemoteOffer = sdp
                        screen = DemoScreen.IncomingCall
                        return
                    }
                    if (engine == null) {
                        val incomingEngine = WebRtcAudioCallEngine(context.applicationContext, deviceId, callId, object : WebRtcAudioCallEngine.Listener {
                            override fun onStateChanged(state: WebRtcCallState, error: String?) {
                                callState = state
                                callError = error
                                if (state == WebRtcCallState.CONNECTED) signalingRef?.markConnected(callId)
                            }

                            override fun onLocalDescription(type: String, sdp: String) {
                                if (type == "offer") {
                                    signalingRef?.sendOffer(callId, fromDeviceId, sdp)
                                } else if (type == "answer") {
                                    signalingRef?.sendAnswer(callId, fromDeviceId, sdp)
                                }
                            }

                            override fun onLocalIceCandidate(candidate: LocalIceCandidate) {
                                signalingRef?.sendIceCandidate(callId, fromDeviceId, candidate)
                            }

                            override fun onIceDiagnosticsChanged(diagnostic: String) {
                                iceDiagnostics = diagnostic
                            }
                        })
                        engine = incomingEngine
                        incomingEngine.prepareForRemoteOffer()
                        incomingEngine.setMuted(isMuted)
                        incomingEngine.setSpeakerEnabled(speakerEnabled)
                        pendingRemoteIce.forEach(incomingEngine::addRemoteIceCandidate)
                        pendingRemoteIce = emptyList()
                    }
                    pendingRemoteOffer = null
                    engine?.applyRemoteOffer(sdp)
                }

                override fun onRemoteAnswer(callId: String, fromDeviceId: String, sdp: String) {
                    if (currentCallId != callId || peerDeviceId != fromDeviceId) return
                    engine?.applyRemoteAnswer(sdp)
                    screen = DemoScreen.ActiveCall
                }

                override fun onRemoteIceCandidate(callId: String, fromDeviceId: String, candidate: LocalIceCandidate) {
                    if (currentCallId != callId || peerDeviceId != fromDeviceId) return
                    val activeEngine = engine
                    if (activeEngine != null) {
                        activeEngine.addRemoteIceCandidate(candidate)
                    } else {
                        pendingRemoteIce = pendingRemoteIce + candidate
                    }
                }

                override fun onPeerPresenceChanged(peerDeviceId: String, online: Boolean) {
                    val expectedPeer = if (deviceId == "pupsik-a") "pupsik-b" else "pupsik-a"
                    if (peerDeviceId == expectedPeer) peerOnline = online
                }

                override fun onSignalError(message: String) {
                    val updatedStatus = CallUiStatus(callState, callError).withSignalError(message)
                    callState = updatedStatus.state
                    callError = updatedStatus.error
                }
            },
        )
        signalingRef = newSignaling
        signalingInstance = newSignaling
        newSignaling
    }
    val authController = remember(signaling) { SupabaseAuthController(signaling.authClient) }
    val authState by authController.state.collectAsState()
    val localizedAuthMessage = authState.message?.let { stringResource(it.stringResourceId()) }
    val authScope = rememberCoroutineScope()

    val startLocalCall = fun() {
        val targetDevice = if (deviceId == "pupsik-a") "pupsik-b" else "pupsik-a"
        val newCallId = UUID.randomUUID().toString()
        if (engine != null) {
            engine?.dispose("replaced by a new call")
        }
        val callEngine = WebRtcAudioCallEngine(context.applicationContext, deviceId, newCallId, object : WebRtcAudioCallEngine.Listener {
            override fun onStateChanged(state: WebRtcCallState, error: String?) {
                callState = state
                callError = error
                if (state == WebRtcCallState.CONNECTED) signaling.markConnected(newCallId)
            }

            override fun onLocalDescription(type: String, sdp: String) {
                if (type == "offer") {
                    signaling.sendOffer(newCallId, targetDevice, sdp)
                } else if (type == "answer") {
                    signaling.sendAnswer(newCallId, targetDevice, sdp)
                }
            }

            override fun onLocalIceCandidate(candidate: LocalIceCandidate) {
                signaling.sendIceCandidate(newCallId, targetDevice, candidate)
            }

            override fun onIceDiagnosticsChanged(diagnostic: String) {
                iceDiagnostics = diagnostic
            }
        })
        currentCallId = newCallId
        peerDeviceId = targetDevice
        engine = callEngine
        callEngine.setMuted(isMuted)
        callEngine.setSpeakerEnabled(speakerEnabled)
        screen = DemoScreen.ActiveCall
        signaling.startCall(newCallId, targetDevice)
    }

    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Log.i(PermissionLogTag, "deviceId=$deviceId microphonePermission=${if (granted) "granted" else "denied"} purpose=caller")
        if (granted) {
            callError = null
            startLocalCall()
        } else {
            callState = WebRtcCallState.FAILED
            callError = context.getString(R.string.microphone_permission_error)
        }
    }
    val requestCall = fun() {
        val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        Log.i(PermissionLogTag, "deviceId=$deviceId microphonePermission=${if (granted) "granted" else "not-granted"} purpose=caller")
        if (granted) {
            callError = null
            startLocalCall()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val rejectAnswerForPermission = fun() {
        val activeCallId = currentCallId
        val activePeer = peerDeviceId
        if (activeCallId != null && activePeer != null) signaling.declineCall(activeCallId, activePeer)
        engine?.dispose("callee microphone permission denied")
        engine = null
        currentCallId = null
        peerDeviceId = null
        pendingRemoteOffer = null
        pendingRemoteIce = emptyList()
        calleeAccepted = false
        callState = WebRtcCallState.FAILED
        callError = context.getString(R.string.microphone_permission_error)
        screen = DemoScreen.Contacts
    }

    val completeAnswer = fun() {
        val activeCallId = currentCallId ?: return
        val activePeer = peerDeviceId ?: return
        val permissionGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        Log.i(PermissionLogTag, "deviceId=$deviceId callId=$activeCallId microphonePermission=${if (permissionGranted) "granted" else "denied"} purpose=callee")
        if (!permissionGranted) {
            rejectAnswerForPermission()
            return
        }
        callError = null
        if (engine == null) {
            val incomingEngine = WebRtcAudioCallEngine(context.applicationContext, deviceId, activeCallId, object : WebRtcAudioCallEngine.Listener {
                override fun onStateChanged(state: WebRtcCallState, error: String?) {
                    callState = state
                    callError = error
                    if (state == WebRtcCallState.FAILED && !calleeAccepted && currentCallId == activeCallId) {
                        signaling.declineCall(activeCallId, activePeer)
                        currentCallId = null
                        peerDeviceId = null
                        pendingRemoteOffer = null
                        pendingRemoteIce = emptyList()
                        screen = DemoScreen.Contacts
                    }
                    if (state == WebRtcCallState.CONNECTED) signaling.markConnected(activeCallId)
                }

                override fun onLocalDescription(type: String, sdp: String) {
                    if (type == "offer") {
                        signaling.sendOffer(activeCallId, activePeer, sdp)
                    } else if (type == "answer") {
                        signaling.sendAnswer(activeCallId, activePeer, sdp)
                    }
                }

                override fun onLocalIceCandidate(candidate: LocalIceCandidate) {
                    signaling.sendIceCandidate(activeCallId, activePeer, candidate)
                }

                override fun onIceDiagnosticsChanged(diagnostic: String) {
                    iceDiagnostics = diagnostic
                }
            })
            engine = incomingEngine
            incomingEngine.setMuted(isMuted)
            incomingEngine.setSpeakerEnabled(speakerEnabled)
            pendingRemoteIce.forEach(incomingEngine::addRemoteIceCandidate)
            pendingRemoteIce = emptyList()
        }
        val activeEngine = engine ?: return
        activeEngine.prepareForRemoteOffer {
            if (currentCallId != activeCallId || peerDeviceId != activePeer || engine !== activeEngine) return@prepareForRemoteOffer
            callError = null
            calleeAccepted = true
            signaling.acceptCall(activeCallId, activePeer)
            pendingRemoteOffer?.let(activeEngine::applyRemoteOffer)
            screen = DemoScreen.ActiveCall
            pendingRemoteOffer = null
        }
    }

    val answerMicrophonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val activeCallId = currentCallId ?: "none"
        Log.i(PermissionLogTag, "deviceId=$deviceId callId=$activeCallId microphonePermission=${if (granted) "granted" else "denied"} purpose=callee")
        if (granted) {
            completeAnswer()
        } else {
            rejectAnswerForPermission()
        }
    }
    val handleAnswer = fun() {
        val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        Log.i(PermissionLogTag, "deviceId=$deviceId microphonePermission=${if (granted) "granted" else "not-granted"} purpose=callee")
        if (granted) {
            callError = null
            completeAnswer()
        } else {
            answerMicrophonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val handleDecline = fun() {
        val activeCallId = currentCallId ?: return
        val activePeer = peerDeviceId ?: return
        signaling.declineCall(activeCallId, activePeer)
        engine?.dispose("local decline")
        engine = null
        currentCallId = null
        peerDeviceId = null
        pendingRemoteOffer = null
        pendingRemoteIce = emptyList()
        calleeAccepted = false
        callState = WebRtcCallState.IDLE
        screen = DemoScreen.Contacts
    }

    val endCall = fun() {
        val activeCallId = currentCallId ?: return
        val activePeer = peerDeviceId ?: return
        signaling.endCall(activeCallId, activePeer)
        val activeEngine = engine
        engine = null
        activeEngine?.endCall("local end call")
        currentCallId = null
        peerDeviceId = null
        pendingRemoteOffer = null
        pendingRemoteIce = emptyList()
        calleeAccepted = false
        callState = WebRtcCallState.IDLE
        isMuted = false
        speakerEnabled = true
        screen = DemoScreen.Contacts
    }

    DisposableEffect(engine) {
        val activeEngine = engine
        onDispose { activeEngine?.dispose() }
    }

    DisposableEffect(signaling) {
        onDispose { signaling.close() }
    }

    DisposableEffect(authController) {
        onDispose { authController.close() }
    }

    LaunchedEffect(authState.phase) {
        if (authState.phase == AuthPhase.AUTHENTICATED) {
            if (screen == DemoScreen.SignIn) screen = DemoScreen.Contacts
        } else if (authState.phase != AuthPhase.SIGNING_OUT) {
            screen = DemoScreen.SignIn
        }
    }

    BackHandler(enabled = authState.phase == AuthPhase.AUTHENTICATED && screen != DemoScreen.SignIn) {
        when (screen) {
            DemoScreen.ActiveCall -> endCall()
            DemoScreen.IncomingCall -> handleDecline()
            DemoScreen.Contacts -> screen = DemoScreen.SignIn
            DemoScreen.SignIn -> Unit
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PrimaryPurple,
            secondary = OnlineGreen,
            background = AppBackground,
            surface = AppSurface,
            onBackground = MainText,
            onSurface = MainText,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppBackground)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            when {
                authState.phase == AuthPhase.CHECKING_SESSION -> AuthLoadingScreen()
                authState.phase != AuthPhase.AUTHENTICATED && authState.phase != AuthPhase.SIGNING_OUT -> SignInScreen(
                    state = authState,
                    languageCodes = languageCodes,
                    languageNames = languageNames,
                    selectedLanguageIndex = selectedLanguageIndex,
                    onLanguageSelected = ::applyApplicationLanguage,
                    onSignIn = { email, password -> authScope.launch { authController.login(email, password) } },
                    onRegister = { email, password -> authScope.launch { authController.register(email, password) } },
                )
                else -> when (screen) {
                    DemoScreen.SignIn, DemoScreen.Contacts -> ContactsScreen(
                        deviceId = deviceId,
                        peerOnline = peerOnline,
                        errorMessage = localizedAuthMessage ?: callError.takeIf { BuildConfig.DEBUG },
                        loggingOut = authState.phase == AuthPhase.SIGNING_OUT,
                        onCall = requestCall,
                        onLogout = { authScope.launch { authController.logout() } },
                    )
                    DemoScreen.IncomingCall -> IncomingCallScreen(
                        errorMessage = callError.takeIf { BuildConfig.DEBUG },
                        onDecline = handleDecline,
                        onAnswer = handleAnswer,
                    )
                    DemoScreen.ActiveCall -> ActiveCallScreen(
                        isMuted = isMuted,
                        speakerEnabled = speakerEnabled,
                        callState = callState,
                        errorMessage = callError.takeIf { BuildConfig.DEBUG },
                        iceDiagnostics = iceDiagnostics,
                        onBack = endCall,
                        onToggleMute = {
                            isMuted = !isMuted
                            engine?.setMuted(isMuted)
                        },
                        onToggleSpeaker = {
                            speakerEnabled = !speakerEnabled
                            engine?.setSpeakerEnabled(speakerEnabled)
                        },
                        onEndCall = endCall,
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthLoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark()
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(color = LightPurple)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.checking_session), color = SecondaryText, fontSize = 14.sp)
        }
    }
}

@Composable
private fun SignInScreen(
    state: AuthUiState,
    languageCodes: List<String>,
    languageNames: List<String>,
    selectedLanguageIndex: Int,
    onLanguageSelected: (String) -> Unit,
    onSignIn: (String, String) -> Unit,
    onRegister: (String, String) -> Unit,
) {
    var registering by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val busy = state.isBusy

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 28.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BrandMark()
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.app_name), color = MainText, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(
            stringResource(if (registering) R.string.register_title else R.string.tagline),
            color = SecondaryText,
            fontSize = 15.sp,
        )
        Spacer(Modifier.height(10.dp))
        LanguageSelector(languageCodes, languageNames, selectedLanguageIndex, onLanguageSelected)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            modifier = Modifier.fillMaxWidth().height(60.dp),
            enabled = !busy,
            placeholder = { Text(stringResource(R.string.email), fontSize = 15.sp) },
            leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(21.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            colors = signInFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        )
        Spacer(Modifier.height(13.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth().height(60.dp),
            enabled = !busy,
            placeholder = { Text(stringResource(R.string.password), fontSize = 15.sp) },
            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(21.dp)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            shape = RoundedCornerShape(18.dp),
            colors = signInFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Spacer(Modifier.height(18.dp))
        state.message?.let { message ->
            Text(
            stringResource(message.stringResourceId()),
            color = if (message == AuthMessage.CONFIRMATION_REQUIRED) SecondaryText else DeclineRed,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
        }
        Button(
            enabled = !busy,
            onClick = {
                val submittedPassword = password
                password = ""
                if (registering) onRegister(email, submittedPassword) else onSignIn(email, submittedPassword)
            },
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple),
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MainText, strokeWidth = 2.dp)
                Spacer(Modifier.size(10.dp))
            }
            Text(
                when {
                    state.phase == AuthPhase.REGISTERING -> stringResource(R.string.creating_account)
                    state.phase == AuthPhase.LOGGING_IN -> stringResource(R.string.signing_in)
                    registering -> stringResource(R.string.create_account)
                    else -> stringResource(R.string.sign_in)
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(17.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (registering) R.string.already_registered else R.string.no_account),
                color = SecondaryText,
                fontSize = 13.sp,
            )
            TextButton(enabled = !busy, onClick = { registering = !registering; password = "" }) {
                Text(
                    stringResource(if (registering) R.string.sign_in else R.string.create_account),
                    color = LightPurple,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun LanguageSelector(
    languageCodes: List<String>,
    languageNames: List<String>,
    selectedLanguageIndex: Int,
    onLanguageSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.language), color = SecondaryText, fontSize = 13.sp)
            TextButton(onClick = { expanded = true }) {
                Text(languageNames[selectedLanguageIndex], color = LightPurple, fontSize = 14.sp)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            languageNames.forEachIndexed { index, name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        expanded = false
                        onLanguageSelected(languageCodes[index])
                    },
                )
            }
        }
    }
}

@Composable
private fun signInFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MainText,
    unfocusedTextColor = MainText,
    focusedContainerColor = FieldBackground,
    unfocusedContainerColor = FieldBackground,
    focusedBorderColor = PrimaryPurple,
    unfocusedBorderColor = FieldBorder,
    focusedPlaceholderColor = SecondaryText,
    unfocusedPlaceholderColor = SecondaryText,
    focusedLeadingIconColor = SecondaryText,
    unfocusedLeadingIconColor = SecondaryText,
    cursorColor = PrimaryPurple,
)

@Composable
private fun BrandMark() {
    Box(
        modifier = Modifier.size(102.dp).clip(CircleShape).background(PrimaryPurple),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Call, contentDescription = null, tint = Color.White, modifier = Modifier.size(47.dp))
        Canvas(Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 17.dp).size(20.dp)) {
            val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            drawArc(Color.White, -52f, 104f, false, style = stroke)
            drawArc(
                Color.White,
                -52f,
                104f,
                false,
                topLeft = androidx.compose.ui.geometry.Offset(4.dp.toPx(), 4.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(size.width - 8.dp.toPx(), size.height - 8.dp.toPx()),
                style = stroke,
            )
        }
    }
}

@Composable
private fun ContactsScreen(
    deviceId: String,
    peerOnline: Boolean,
    errorMessage: String?,
    loggingOut: Boolean,
    onCall: () -> Unit,
    onLogout: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 23.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(68.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.contacts), color = MainText, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                TextButton(enabled = !loggingOut, onClick = onLogout) {
                    Text(stringResource(if (loggingOut) R.string.signing_out else R.string.log_out), color = SecondaryText, fontSize = 12.sp)
                }
                IconButton(onClick = {}, modifier = Modifier.size(48.dp)) {
                    PersonAddGlyph()
                }
            }
        }
        if (errorMessage != null) {
            Text(errorMessage, color = DeclineRed, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.height(11.dp))
        val peerName = stringResource(if (deviceId == "pupsik-a") R.string.peer_b else R.string.peer_a)
        ContactRow("P", peerName, stringResource(if (peerOnline) R.string.online else R.string.offline), peerOnline, onCall)
        Spacer(Modifier.weight(1f))
        BottomNavigationBar()
    }
}

@Composable
private fun PersonAddGlyph() {
    Box(Modifier.size(27.dp)) {
        Icon(Icons.Filled.Person, contentDescription = stringResource(R.string.add_contact), tint = MainText, modifier = Modifier.align(Alignment.CenterStart).size(24.dp))
        Canvas(Modifier.align(Alignment.BottomEnd).size(12.dp)) {
            drawCircle(AppBackground)
            val strokeWidth = 1.7.dp.toPx()
            drawLine(MainText, Offset(size.width * 0.5f, size.height * 0.18f), Offset(size.width * 0.5f, size.height * 0.82f), strokeWidth, cap = StrokeCap.Round)
            drawLine(MainText, Offset(size.width * 0.18f, size.height * 0.5f), Offset(size.width * 0.82f, size.height * 0.5f), strokeWidth, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun ContactRow(
    initial: String,
    name: String,
    status: String,
    online: Boolean,
    onCall: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(82.dp)
            .clip(RoundedCornerShape(20.dp))
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileAvatar(initial, 55.dp, online)
        Column(modifier = Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.Center) {
            Text(name, color = MainText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (online) OnlineGreen else Color(0xFF77737D)))
                Spacer(Modifier.size(6.dp))
                Text(status, color = if (online) OnlineGreen else SecondaryText, fontSize = 12.sp)
            }
        }
        IconButton(
            onClick = onCall,
            modifier = Modifier.size(48.dp).clip(CircleShape).background(OnlineGreen),
        ) {
            Icon(Icons.Filled.Call, contentDescription = stringResource(R.string.call_contact, name), tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun ProfileAvatar(initial: String, size: Dp, isTanya: Boolean) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (isTanya) Brush.linearGradient(listOf(Color(0xFF755A88), Color(0xFF3B344A)))
                else Brush.linearGradient(listOf(Color(0xFF77757E), Color(0xFF54525B))),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            color = Color.White,
            fontSize = if (size >= 120.dp) 48.sp else 22.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun BottomNavigationBar() {
    Column {
        HorizontalDivider(color = FieldBorder, thickness = 1.dp)
        Row(
            modifier = Modifier.fillMaxWidth().height(70.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BottomNavigationItem(stringResource(R.string.contacts), true, Icons.Filled.Person)
            BottomNavigationItem(stringResource(R.string.settings), false, Icons.Filled.Settings)
        }
    }
}

@Composable
private fun BottomNavigationItem(
    label: String,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    val itemColor = if (selected) PrimaryPurple else SecondaryText
    Column(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = itemColor, modifier = Modifier.size(23.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = itemColor, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun IncomingCallScreen(errorMessage: String?, onDecline: () -> Unit, onAnswer: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1D1922), AppBackground, AppBackground)))
            .padding(horizontal = 28.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Spacer(Modifier.height(20.dp))
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ProfileAvatar("T", 148.dp, true)
            Spacer(Modifier.height(22.dp))
            Text(stringResource(R.string.demo_contact_name), color = MainText, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.incoming_call_status), color = SecondaryText, fontSize = 16.sp)
            if (errorMessage != null) {
                Spacer(Modifier.height(10.dp))
                Text(errorMessage, color = DeclineRed, fontSize = 13.sp)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            CallActionButton(stringResource(R.string.decline), DeclineRed, onDecline, rotatePhone = true)
            CallActionButton(stringResource(R.string.answer), OnlineGreen, onAnswer)
        }
    }
}

@Composable
private fun CallActionButton(
    label: String,
    color: Color,
    onClick: () -> Unit,
    rotatePhone: Boolean = false,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(72.dp).clip(CircleShape).background(color),
        ) {
            Icon(
                Icons.Filled.Call,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(29.dp).then(if (rotatePhone) Modifier.rotate(135f) else Modifier),
            )
        }
        Spacer(Modifier.height(9.dp))
        Text(label, color = MainText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun callStateText(state: WebRtcCallState): String = when (state) {
    WebRtcCallState.IDLE -> ""
    WebRtcCallState.INITIALIZING -> stringResource(R.string.call_state_preparing_audio)
    WebRtcCallState.CREATING_OFFER -> stringResource(R.string.call_state_creating_offer)
    WebRtcCallState.WAITING_FOR_REMOTE -> stringResource(R.string.call_state_waiting_for_peer)
    WebRtcCallState.WAITING_FOR_OFFER -> stringResource(R.string.call_state_waiting_for_offer)
    WebRtcCallState.CREATING_ANSWER -> stringResource(R.string.call_state_creating_answer)
    WebRtcCallState.CONNECTING -> stringResource(R.string.call_state_connecting)
    WebRtcCallState.CONNECTED -> stringResource(R.string.call_state_connected)
    WebRtcCallState.DISCONNECTED -> stringResource(R.string.call_state_disconnected)
    WebRtcCallState.FAILED -> stringResource(R.string.call_state_failed)
    WebRtcCallState.ENDED -> stringResource(R.string.call_state_ended)
}

@Composable
private fun ActiveCallScreen(
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
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = MainText, modifier = Modifier.size(24.dp))
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ProfileAvatar("T", 142.dp, true)
            Spacer(Modifier.height(19.dp))
            Text(stringResource(R.string.demo_contact_name), color = MainText, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(7.dp))
            Text(
                if (callState == WebRtcCallState.FAILED) {
                    RuntimeDiagnostic.failureDisplayText(BuildConfig.DEBUG, stringResource(R.string.call_state_failed), errorMessage)
                } else callStateText(callState),
                color = if (callState == WebRtcCallState.CONNECTED) OnlineGreen else SecondaryText,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
            )
            if (errorMessage != null && !(BuildConfig.DEBUG && callState == WebRtcCallState.FAILED)) {
                Spacer(Modifier.height(8.dp))
                Text(errorMessage, color = DeclineRed, fontSize = 13.sp)
            }
            if (BuildConfig.DEBUG && iceDiagnostics.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(iceDiagnostics, color = SecondaryText, fontSize = 11.sp, lineHeight = 14.sp)
            }
            Spacer(Modifier.height(39.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CallControl(stringResource(R.string.mute), isMuted, onToggleMute) { MicrophoneGlyph(isMuted) }
                CallControl(stringResource(R.string.speaker), speakerEnabled, onToggleSpeaker) { SpeakerGlyph() }
                CallControl(stringResource(R.string.more), false, {}) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more), tint = Color.White, modifier = Modifier.size(23.dp))
                }
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 13.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconButton(
                onClick = onEndCall,
                modifier = Modifier.size(76.dp).clip(CircleShape).background(DeclineRed),
            ) {
                Icon(Icons.Filled.Call, contentDescription = stringResource(R.string.end_call_description), tint = Color.White, modifier = Modifier.size(31.dp).rotate(135f))
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.end_call), color = MainText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun CallControl(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val controlColor = if (selected) PrimaryPurple else Color(0xFF302D36)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(controlColor)
                .then(if (selected) Modifier.border(1.dp, LightPurple.copy(alpha = 0.65f), CircleShape) else Modifier),
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = SecondaryText, fontSize = 12.sp)
    }
}

@Composable
private fun MicrophoneGlyph(muted: Boolean) {
    Canvas(Modifier.size(24.dp)) {
        val strokeWidth = 2.dp.toPx()
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        drawRoundRect(
            Color.White,
            topLeft = Offset(size.width * 0.38f, size.height * 0.12f),
            size = Size(size.width * 0.24f, size.height * 0.48f),
            cornerRadius = CornerRadius(size.width * 0.12f),
            style = stroke,
        )
        drawArc(
            Color.White,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width * 0.2f, size.height * 0.32f),
            size = Size(size.width * 0.6f, size.height * 0.46f),
            style = stroke,
        )
        drawLine(Color.White, Offset(size.width * 0.5f, size.height * 0.78f), Offset(size.width * 0.5f, size.height * 0.93f), strokeWidth, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(size.width * 0.3f, size.height * 0.94f), Offset(size.width * 0.7f, size.height * 0.94f), strokeWidth, cap = StrokeCap.Round)
        if (muted) {
            drawLine(Color.White, Offset(size.width * 0.12f, size.height * 0.12f), Offset(size.width * 0.88f, size.height * 0.88f), strokeWidth, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun SpeakerGlyph() {
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
        drawArc(Color.White, -52f, 104f, false, topLeft = Offset(size.width * 0.4f, size.height * 0.25f), size = Size(size.width * 0.45f, size.height * 0.5f), style = stroke)
        drawArc(Color.White, -52f, 104f, false, topLeft = Offset(size.width * 0.43f, size.height * 0.08f), size = Size(size.width * 0.58f, size.height * 0.84f), style = stroke)
    }
}