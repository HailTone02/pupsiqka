package com.pupsikcall.app

import android.os.Bundle
import android.util.Log
import android.Manifest
import android.app.Activity
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
import androidx.compose.runtime.SideEffect
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

private val AppBackground: Color @Composable get() = LocalPupsikPalette.current.background
private val AppSurface: Color @Composable get() = LocalPupsikPalette.current.surface
private val FieldBackground: Color @Composable get() = LocalPupsikPalette.current.field
private val FieldBorder: Color @Composable get() = LocalPupsikPalette.current.outline
private val PrimaryPurple: Color @Composable get() = LocalPupsikPalette.current.bronze
private val LightPurple: Color @Composable get() = LocalPupsikPalette.current.caramel
private val OnlineGreen: Color @Composable get() = LocalPupsikPalette.current.online
private val DeclineRed: Color @Composable get() = LocalPupsikPalette.current.danger
private val MainText: Color @Composable get() = LocalPupsikPalette.current.text
private val SecondaryText: Color @Composable get() = LocalPupsikPalette.current.muted
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

private enum class DemoScreen { SignIn, Contacts, Calls, Messages, Conversation, Settings, Profile, IncomingCall, ActiveCall }

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
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
    var selectedConversationId by rememberSaveable { mutableStateOf<String?>(null) }
    var isMuted by rememberSaveable { mutableStateOf(false) }
    var speakerEnabled by rememberSaveable { mutableStateOf(true) }
    var callState by remember { mutableStateOf(WebRtcCallState.IDLE) }
    var callError by remember { mutableStateOf<String?>(null) }
    var iceDiagnostics by remember { mutableStateOf("") }
    var engine by remember { mutableStateOf<WebRtcAudioCallEngine?>(null) }
    var currentCallId by remember { mutableStateOf<String?>(null) }
    var activeCallSession by remember { mutableStateOf<AuthenticatedCallSession?>(null) }
    var callParticipantName by remember { mutableStateOf("") }
    var pendingRemoteOffer by remember { mutableStateOf<String?>(null) }
    var pendingRemoteIce by remember { mutableStateOf(emptyList<LocalIceCandidate>()) }
    var calleeAccepted by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val authScope = rememberCoroutineScope()
    var appearanceMode by rememberSaveable {
        mutableStateOf(AppearanceMode.fromPreference(context.getSharedPreferences("pupsikcall.preferences", 0).getString("appearance", "system")))
    }
    val deviceId = "authenticated-call"
    val languageCodes = stringArrayResource(R.array.supported_language_codes).toList()
    val languageNames = stringArrayResource(R.array.supported_language_names).toList()
    val applicationLocaleTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val systemLocaleTag = LocalConfiguration.current.locales.get(0)?.toLanguageTag().orEmpty()
    val selectedLanguageIndex = selectedLanguageIndex(languageCodes, applicationLocaleTags, systemLocaleTag)
    val defaultCallParticipantName = stringResource(R.string.call_participant)
    var signalingRef: AuthenticatedCallSignaling? = null

    fun isCurrentCall(session: AuthenticatedCallSession): Boolean {
        val localUserId = signalingRef?.authenticatedUserId() ?: return false
        return currentCallId == session.callId.toString()
            && activeCallSession?.callId == session.callId
            && session.remoteUserId(localUserId) != null
    }

    fun createCallEngine(session: AuthenticatedCallSession): WebRtcAudioCallEngine =
        WebRtcAudioCallEngine(context.applicationContext, deviceId, session.callId.toString(), object : WebRtcAudioCallEngine.Listener {
            override fun onStateChanged(state: WebRtcCallState, error: String?) {
                if (!isCurrentCall(session)) return
                callState = state
                callError = error
                if (state == WebRtcCallState.CONNECTED) authScope.launch { signalingRef?.markConnected(session) }
                if (state == WebRtcCallState.FAILED) authScope.launch { runCatching { signalingRef?.failCall(session, "media_failed") } }
            }

            override fun onLocalDescription(type: String, sdp: String) {
                if (!isCurrentCall(session)) return
                if (type == "offer") signalingRef?.sendOffer(session, sdp)
                else if (type == "answer") signalingRef?.sendAnswer(session, sdp)
            }

            override fun onLocalIceCandidate(candidate: LocalIceCandidate) {
                if (isCurrentCall(session)) signalingRef?.sendIceCandidate(session, candidate)
            }

            override fun onIceDiagnosticsChanged(diagnostic: String) {
                if (isCurrentCall(session)) iceDiagnostics = diagnostic
            }
        })

    val signaling = remember {
        val newSignaling = AuthenticatedCallSignaling(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
            listener = object : AuthenticatedCallSignaling.Listener {
                override fun onAuthenticatedCallSessionChanged(session: AuthenticatedCallSession) {
                    val localUserId = signalingRef?.authenticatedUserId() ?: return
                    if (session.status == AuthenticatedCallStatus.RINGING
                        && session.calleeUserId == localUserId
                        && currentCallId == null
                    ) {
                        currentCallId = session.callId.toString()
                        activeCallSession = session
                        callParticipantName = defaultCallParticipantName
                        calleeAccepted = false
                        callError = null
                        screen = DemoScreen.IncomingCall
                        authScope.launch {
                            runCatching { signalingRef?.prepareCall(session) }
                        }
                        authScope.launch {
                            val name = runCatching { signalingRef?.loadPublicDisplayName(session.callerUserId) }.getOrNull()
                            if (currentCallId == session.callId.toString() && !name.isNullOrBlank()) callParticipantName = name
                        }
                    }
                    if (currentCallId != session.callId.toString()) return
                    activeCallSession = session
                    if (session.status == AuthenticatedCallStatus.ACCEPTED && session.callerUserId == localUserId) {
                        engine?.startOffer()
                    }
                    if (session.status.isTerminal) {
                        currentCallId = null
                        activeCallSession = null
                        pendingRemoteOffer = null
                        pendingRemoteIce = emptyList()
                        engine?.dispose("call session ended")
                        engine = null
                        calleeAccepted = false
                        callState = WebRtcCallState.IDLE
                        callError = null
                        screen = DemoScreen.Contacts
                    }
                }

                override fun onAuthenticatedCallSessionLost() {
                    currentCallId = null
                    activeCallSession = null
                    pendingRemoteOffer = null
                    pendingRemoteIce = emptyList()
                    engine?.dispose("authenticated session lost")
                    engine = null
                    calleeAccepted = false
                    callState = WebRtcCallState.IDLE
                    if (screen == DemoScreen.IncomingCall || screen == DemoScreen.ActiveCall) screen = DemoScreen.Contacts
                }

                override fun onAuthenticatedCallError() {
                    callError = context.getString(R.string.call_routing_error)
                }

                override fun onRemoteOffer(session: AuthenticatedCallSession, sdp: String) {
                    if (currentCallId != session.callId.toString() || activeCallSession?.callerUserId != session.callerUserId
                        || activeCallSession?.calleeUserId != session.calleeUserId
                    ) return
                    if (screen != DemoScreen.ActiveCall) {
                        pendingRemoteOffer = sdp
                        return
                    }
                    val incomingEngine = engine ?: createCallEngine(session).also { engine = it }
                    incomingEngine.prepareForRemoteOffer()
                    incomingEngine.setMuted(isMuted)
                    incomingEngine.setSpeakerEnabled(speakerEnabled)
                    pendingRemoteIce.forEach(incomingEngine::addRemoteIceCandidate)
                    pendingRemoteIce = emptyList()
                    pendingRemoteOffer = null
                    incomingEngine.applyRemoteOffer(sdp)
                }

                override fun onRemoteAnswer(session: AuthenticatedCallSession, sdp: String) {
                    if (!isCurrentCall(session)) return
                    engine?.applyRemoteAnswer(sdp)
                    screen = DemoScreen.ActiveCall
                }

                override fun onRemoteIceCandidate(session: AuthenticatedCallSession, candidate: LocalIceCandidate) {
                    if (!isCurrentCall(session)) return
                    val activeEngine = engine
                    if (activeEngine != null) activeEngine.addRemoteIceCandidate(candidate)
                    else pendingRemoteIce = pendingRemoteIce + candidate
                }
            },
        )
        newSignaling
    }
    signalingRef = signaling
    val authController = remember(signaling) { SupabaseAuthController(signaling.authClient) }
    val authState by authController.state.collectAsState()
    val profileRepository = remember(signaling) { AuthenticatedProfileRepository(signaling.authClient) }
    val profileState by profileRepository.state.collectAsState()
    val phoneVerificationController = remember(signaling) {
        PhoneVerificationController(SupabasePhoneIdentityGateway(signaling.authClient))
    }
    val phoneVerificationState by phoneVerificationController.state.collectAsState()
    val messagingRepository = remember(signaling) { MessagingRepository(SupabaseMessagingGateway(signaling.authClient)) }
    val conversationListState by messagingRepository.conversationState.collectAsState()
    val messageListState by messagingRepository.messageState.collectAsState()
    val selectedConversation = (conversationListState as? ConversationListState.Loaded)
        ?.conversations?.firstOrNull { it.id.toString() == selectedConversationId }
    val localizedAuthMessage = authState.message?.let { stringResource(it.stringResourceId()) }

    val startAuthenticatedCall = fun(calleeUserId: UUID) {
        authScope.launch {
            try {
                val requestedCallId = UUID.randomUUID()
                val session = signaling.createCallSession(requestedCallId, calleeUserId)
                val localUserId = signaling.authenticatedUserId()
                if (localUserId == null || outgoingCallRoute(localUserId, calleeUserId, session) == null) return@launch
                engine?.dispose("replaced by an authenticated call")
                currentCallId = session.callId.toString()
                activeCallSession = session
                callParticipantName = defaultCallParticipantName
                engine = createCallEngine(session)
                engine?.setMuted(isMuted)
                engine?.setSpeakerEnabled(speakerEnabled)
                callState = WebRtcCallState.WAITING_FOR_REMOTE
                screen = DemoScreen.ActiveCall
                signaling.prepareCall(session)
                signaling.ringCall(session)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                callError = context.getString(R.string.call_routing_error)
                screen = DemoScreen.Calls
            }
        }
    }

    val rejectAnswerForPermission = fun() {
        val activeSession = activeCallSession
        if (activeSession != null) authScope.launch { runCatching { signaling.declineCall(activeSession) } }
        engine?.dispose("callee microphone permission denied")
        engine = null
        currentCallId = null
        activeCallSession = null
        pendingRemoteOffer = null
        pendingRemoteIce = emptyList()
        calleeAccepted = false
        callState = WebRtcCallState.FAILED
        callError = context.getString(R.string.microphone_permission_error)
        screen = DemoScreen.Contacts
    }

    val completeAnswer = fun() {
        val activeSession = activeCallSession ?: return
        val activeCallId = activeSession.callId.toString()
        if (signaling.authenticatedUserId() != activeSession.calleeUserId) return
        val permissionGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        Log.i(PermissionLogTag, "deviceId=$deviceId callId=$activeCallId microphonePermission=${if (permissionGranted) "granted" else "denied"} purpose=callee")
        if (!permissionGranted) {
            rejectAnswerForPermission()
            return
        }
        callError = null
        if (engine == null) {
            val incomingEngine = createCallEngine(activeSession)
            engine = incomingEngine
            incomingEngine.setMuted(isMuted)
            incomingEngine.setSpeakerEnabled(speakerEnabled)
            pendingRemoteIce.forEach(incomingEngine::addRemoteIceCandidate)
            pendingRemoteIce = emptyList()
        }
        val activeEngine = engine ?: return
        activeEngine.prepareForRemoteOffer {
            authScope.launch {
                if (currentCallId != activeCallId || activeCallSession?.callId != activeSession.callId || engine !== activeEngine) return@launch
                try {
                    signaling.prepareCall(activeSession)
                    signaling.acceptCall(activeSession)
                    calleeAccepted = true
                    callError = null
                    pendingRemoteOffer?.let(activeEngine::applyRemoteOffer)
                    screen = DemoScreen.ActiveCall
                    pendingRemoteOffer = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    callError = context.getString(R.string.call_routing_error)
                }
            }
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
        val activeSession = activeCallSession ?: return
        authScope.launch { runCatching { signaling.declineCall(activeSession) } }
        engine?.dispose("local decline")
        engine = null
        currentCallId = null
        activeCallSession = null
        pendingRemoteOffer = null
        pendingRemoteIce = emptyList()
        calleeAccepted = false
        callState = WebRtcCallState.IDLE
        screen = DemoScreen.Contacts
    }

    val endCall = fun() {
        val activeSession = activeCallSession ?: return
        authScope.launch { runCatching { signaling.finishCall(activeSession) } }
        val activeEngine = engine
        engine = null
        activeEngine?.endCall("local end call")
        currentCallId = null
        activeCallSession = null
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

    DisposableEffect(profileRepository) {
        onDispose { profileRepository.close() }
    }

    DisposableEffect(messagingRepository) {
        onDispose { messagingRepository.close() }
    }

    LaunchedEffect(authState.phase, screen) {
        if (authState.phase == AuthPhase.AUTHENTICATED && screen == DemoScreen.Messages) {
            messagingRepository.loadConversations()
        }
    }

    LaunchedEffect(authState.phase, screen, selectedConversationId) {
        val conversationId = selectedConversationId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (authState.phase == AuthPhase.AUTHENTICATED && screen == DemoScreen.Conversation && conversationId != null) {
            try {
                messagingRepository.startObservingMessages(conversationId)
                messagingRepository.loadMessages(conversationId)
                awaitCancellation()
            } finally {
                messagingRepository.stopObservingMessages()
            }
        }
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
            DemoScreen.Conversation -> screen = DemoScreen.Messages
            DemoScreen.Settings, DemoScreen.Profile, DemoScreen.Calls, DemoScreen.Messages -> screen = DemoScreen.Contacts
            DemoScreen.Contacts -> screen = DemoScreen.SignIn
            DemoScreen.SignIn -> Unit
        }
    }

    PupsikTheme(appearanceMode) {
        val palette = LocalPupsikPalette.current
        val darkAppearance = when (appearanceMode) {
            AppearanceMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
            AppearanceMode.LIGHT -> false
            AppearanceMode.DARK -> true
        }
        SideEffect {
            val window = (context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = palette.background.toArgb()
                window.navigationBarColor = palette.background.toArgb()
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkAppearance
                    isAppearanceLightNavigationBars = !darkAppearance
                }
            }
            context.getSharedPreferences("pupsikcall.preferences", 0).edit().putString("appearance", appearanceMode.preferenceValue).apply()
        }
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
                    DemoScreen.SignIn, DemoScreen.Contacts -> PupsikContactsScreen(
                        profileName = (profileState as? AuthenticatedProfileState.Profile)
                            ?.profile?.displayName.orEmpty(),
                        onOpenCalls = { screen = DemoScreen.Calls },
                        onOpenMessages = { screen = DemoScreen.Messages },
                        onOpenSettings = { screen = DemoScreen.Settings },
                        onOpenProfile = { screen = DemoScreen.Profile },
                    )
                    DemoScreen.Calls -> PupsikCallsScreen(
                        onOpenContacts = { screen = DemoScreen.Contacts },
                        onOpenMessages = { screen = DemoScreen.Messages },
                    )
                    DemoScreen.Messages -> PupsikMessagesScreen(
                        state = conversationListState,
                        onRetry = { authScope.launch { messagingRepository.loadConversations() } },
                        onLoadMore = {
                            val cursor = (conversationListState as? ConversationListState.Loaded)?.nextCursor
                            if (cursor != null) authScope.launch { messagingRepository.loadConversations(cursor) }
                        },
                        onOpenConversation = { conversation ->
                            selectedConversationId = conversation.id.toString()
                            screen = DemoScreen.Conversation
                        },
                        onOpenContacts = { screen = DemoScreen.Contacts },
                        onOpenCalls = { screen = DemoScreen.Calls },
                    )
                    DemoScreen.Conversation -> selectedConversation?.let { conversation ->
                        PupsikConversationScreen(
                            conversation = conversation,
                            state = messageListState,
                            onBack = { screen = DemoScreen.Messages },
                            onRetry = { authScope.launch { messagingRepository.loadMessages(conversation.id) } },
                            onRetryRealtime = {
                                messagingRepository.startObservingMessages(conversation.id)
                                authScope.launch { messagingRepository.loadMessages(conversation.id) }
                            },
                            onLoadOlder = {
                                val cursor = (messageListState as? MessageListState.Loaded)?.nextCursor
                                if (cursor != null) authScope.launch { messagingRepository.loadMessages(conversation.id, cursor) }
                            },
                            onSend = { conversationId, clientMessageId, body ->
                                messagingRepository.sendTextMessage(conversationId, clientMessageId, body)
                            },
                        )
                    } ?: PupsikMessagesScreen(
                        state = conversationListState,
                        onRetry = { authScope.launch { messagingRepository.loadConversations() } },
                        onLoadMore = {
                            val cursor = (conversationListState as? ConversationListState.Loaded)?.nextCursor
                            if (cursor != null) authScope.launch { messagingRepository.loadConversations(cursor) }
                        },
                        onOpenConversation = { conversation ->
                            selectedConversationId = conversation.id.toString()
                            screen = DemoScreen.Conversation
                        },
                        onOpenContacts = { screen = DemoScreen.Contacts },
                        onOpenCalls = { screen = DemoScreen.Calls },
                    )
                    DemoScreen.Settings -> PupsikSettingsScreen(
                        appearance = appearanceMode,
                        onAppearanceChange = { appearanceMode = it },
                        languageCodes = languageCodes,
                        languageNames = languageNames,
                        selectedLanguageIndex = selectedLanguageIndex,
                        onLanguageSelected = ::applyApplicationLanguage,
                        onOpenProfile = { screen = DemoScreen.Profile },
                        onLogout = { authScope.launch { authController.logout() } },
                    )
                    DemoScreen.Profile -> PupsikProfileScreen(
                        state = profileState,
                        phoneState = phoneVerificationState,
                        onBack = { screen = DemoScreen.Settings },
                        onLogout = { authScope.launch { authController.logout() } },
                        onRetry = profileRepository::reload,
                        onSaveDisplayName = { name -> profileRepository.updateDisplayName(name) },
                        onLoadPhone = phoneVerificationController::load,
                        onRequestPhone = phoneVerificationController::requestVerification,
                        onResendPhone = phoneVerificationController::resendVerification,
                        onVerifyPhone = phoneVerificationController::verify,
                    )
                    DemoScreen.IncomingCall -> PupsikIncomingCallScreen(
                        peerName = callParticipantName.ifBlank { defaultCallParticipantName },
                        errorMessage = callError.takeIf { BuildConfig.DEBUG },
                        onDecline = handleDecline,
                        onAnswer = handleAnswer,
                    )
                    DemoScreen.ActiveCall -> PupsikActiveCallScreen(
                        peerName = callParticipantName.ifBlank { defaultCallParticipantName },
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
    val palette = LocalPupsikPalette.current
    val lightUi = palette == PupsikPalettes.Light

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
        Text(stringResource(R.string.app_name), color = MainText, style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(5.dp))
        Text(
            stringResource(if (registering) R.string.register_title else R.string.tagline),
            color = SecondaryText,
            fontSize = 15.sp,
        )
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier.fillMaxWidth()
                .then(if (lightUi) Modifier.clip(PupsikShapes.panel).background(palette.surfaceRaised).border(1.dp, palette.outline.copy(alpha = 0.72f), PupsikShapes.panel) else Modifier)
                .padding(if (lightUi) PupsikSpacing.medium else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
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
                shape = PupsikShapes.panel,
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
                shape = PupsikShapes.panel,
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
                shape = PupsikShapes.panel,
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
private fun ProfileAvatar(initial: String, size: Dp, isOnline: Boolean) {
    val palette = LocalPupsikPalette.current
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (isOnline) Brush.linearGradient(listOf(palette.caramel, palette.bronze))
                else Brush.linearGradient(listOf(palette.subtle, palette.surface)),
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
private fun IncomingCallScreen(peerName: String, errorMessage: String?, onDecline: () -> Unit, onAnswer: () -> Unit) {
    val palette = LocalPupsikPalette.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(palette.surface, AppBackground, AppBackground)))
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
            ProfileAvatar(peerName.take(1), 148.dp, true)
            Spacer(Modifier.height(22.dp))
            Text(peerName, color = MainText, fontSize = 32.sp, fontWeight = FontWeight.Bold)
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
internal fun callStateText(state: WebRtcCallState): String = when (state) {
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
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = MainText, modifier = Modifier.size(24.dp))
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ProfileAvatar(peerName.take(1), 142.dp, true)
            Spacer(Modifier.height(19.dp))
            Text(peerName, color = MainText, fontSize = 30.sp, fontWeight = FontWeight.Bold)
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
    val controlColor = if (selected) PrimaryPurple else LocalPupsikPalette.current.surfaceRaised
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