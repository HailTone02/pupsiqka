package com.pupsikcall.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.webrtc.DataChannel
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.IceCandidateErrorEvent
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

enum class WebRtcCallState(val displayText: String) {
    IDLE(""),
    INITIALIZING("Preparing audio"),
    CREATING_OFFER("Creating offer"),
    WAITING_FOR_REMOTE("Waiting for peer"),
    WAITING_FOR_OFFER("Waiting for offer"),
    CREATING_ANSWER("Creating answer"),
    CONNECTING("Connecting"),
    CONNECTED("Connected"),
    DISCONNECTED("Disconnected"),
    FAILED("Audio setup failed"),
    ENDED("Call ended"),
}

data class LocalIceCandidate(
    val sdpMid: String?,
    val sdpMLineIndex: Int,
    val sdp: String,
)

class WebRtcAudioCallEngine(
    context: Context,
    private val deviceId: String,
    private val callId: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onStateChanged(state: WebRtcCallState, error: String?)
        fun onLocalDescription(type: String, sdp: String)
        fun onLocalIceCandidate(candidate: LocalIceCandidate)
        fun onIceDiagnosticsChanged(diagnostic: String) = Unit
    }

    companion object {
        private const val TAG = "PupsikCallWebRTC"
        private val initializationLock = Any()

        @Volatile
        private var factoryInitialized = false
    }

    private val appContext = context.applicationContext
    private val audioManager: AudioManager by lazy(LazyThreadSafetyMode.NONE) {
        checkNotNull(appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager) {
            "AudioManager service is unavailable"
        }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val callbackGate = CallCallbackGate()
    private val candidatesLock = Any()
    private val pendingRemoteCandidates = RemoteIceCandidateBuffer<IceCandidate>()
    private val pendingLocalCandidates = PendingIceBuffer<IceCandidate>()
    private val audioSetupGate = AudioSetupGate()
    private val iceDiagnostics = IceDiagnostics()

    @Volatile
    var state: WebRtcCallState = WebRtcCallState.IDLE
        private set

    @Volatile
    private var muted = false

    @Volatile
    private var speakerEnabled = true

    @Volatile
    private var localDescriptionAnnounced = false
    @Volatile private var setupStage = "not-started"

    private var audioDeviceModule: AudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var audioSource: org.webrtc.AudioSource? = null
    private var audioTrack: org.webrtc.AudioTrack? = null
    private var peerConnection: PeerConnection? = null
    private var audioModeCaptured = false
    private var previousAudioMode: Int? = null
    private var previousSpeakerphoneOn: Boolean? = null
    private var previousCommunicationDevice: AudioDeviceInfo? = null

    fun startOffer() = enqueue {
        if (peerConnection != null) return@enqueue
        transition(WebRtcCallState.INITIALIZING)
        try {
            preparePeerConnection()
            check(audioSetupGate.canNegotiate) { audioSetupGate.failureReason ?: "Audio setup did not reach READY" }
            transition(WebRtcCallState.CREATING_OFFER)
            val connection = requireNotNull(peerConnection)
            setupStage = "create-offer"
            connection.createOffer(
                sdpObserver(
                    onCreateSuccess = { description -> setLocalDescription(connection, description) },
                    onFailure = { fail("create-offer", it) },
                ),
                MediaConstraints(),
            )
        } catch (exception: Exception) {
            fail(setupStage, exception)
        } catch (error: LinkageError) {
            fail(setupStage, error)
        }
    }

    fun prepareForRemoteOffer(onReady: () -> Unit = {}) = enqueue {
        if (peerConnection != null) {
            if (audioSetupGate.canNegotiate) postListener(onReady) else fail("audio-readiness", IllegalStateException(audioSetupGate.failureReason ?: "Audio setup is not ready"))
            return@enqueue
        }
        transition(WebRtcCallState.INITIALIZING)
        try {
            preparePeerConnection()
            transition(WebRtcCallState.WAITING_FOR_OFFER)
            postListener(onReady)
        } catch (exception: Exception) {
            fail(setupStage, exception)
        } catch (error: LinkageError) {
            fail(setupStage, error)
        }
    }

    fun applyRemoteOffer(sdp: String) = enqueue {
        if (!audioSetupGate.canNegotiate) {
            return@enqueue fail("apply-remote-offer", IllegalStateException(audioSetupGate.failureReason ?: "Audio setup is not ready"))
        }
        val connection = peerConnection ?: return@enqueue fail("apply-remote-offer", IllegalStateException("PeerConnection was not created"))
        try {
            connection.setRemoteDescription(
                sdpObserver(
                    onSetSuccess = {
                        flushRemoteCandidates(connection)
                        transition(WebRtcCallState.CREATING_ANSWER)
                        runPeerOperation("create-answer") {
                            connection.createAnswer(
                                sdpObserver(
                                    onCreateSuccess = { description -> setLocalDescription(connection, description) },
                                    onFailure = { fail("create-answer", it) },
                                ),
                                MediaConstraints(),
                            )
                        }
                    },
                    onFailure = { fail("set-remote-offer", it) },
                ),
                SessionDescription(SessionDescription.Type.OFFER, sdp),
            )
        } catch (exception: Exception) {
            fail("set-remote-offer", exception)
        } catch (error: LinkageError) {
            fail("set-remote-offer", error)
        }
    }

    fun applyRemoteAnswer(sdp: String) = enqueue {
        if (!audioSetupGate.canNegotiate) {
            return@enqueue fail("apply-remote-answer", IllegalStateException(audioSetupGate.failureReason ?: "Audio setup is not ready"))
        }
        val connection = peerConnection ?: return@enqueue fail("apply-remote-answer", IllegalStateException("PeerConnection was not created"))
        try {
            connection.setRemoteDescription(
                sdpObserver(
                    onSetSuccess = {
                        flushRemoteCandidates(connection)
                        transition(WebRtcCallState.CONNECTING)
                    },
                    onFailure = { fail("set-remote-answer", it) },
                ),
                SessionDescription(SessionDescription.Type.ANSWER, sdp),
            )
        } catch (exception: Exception) {
            fail("set-remote-answer", exception)
        } catch (error: LinkageError) {
            fail("set-remote-answer", error)
        }
    }

    fun addRemoteIceCandidate(candidate: LocalIceCandidate) = enqueue {
        val iceCandidate = IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
        if (BuildConfig.DEBUG) {
            iceDiagnostics.recordRemoteCandidateReceived(candidate.sdp)
            publishIceDiagnostics()
        }
        pendingRemoteCandidates.add(iceCandidate) { pendingCandidate ->
            val connection = peerConnection
            if (connection == null) {
                recordRemoteCandidateResult(false)
                fail("add-remote-ice", IllegalStateException("PeerConnection was not created"))
            } else {
                addRemoteCandidate(connection, pendingCandidate)
            }
        }
    }

    fun setMuted(isMuted: Boolean) {
        muted = isMuted
        enqueue { audioTrack?.setEnabled(!muted) }
    }

    fun setSpeakerEnabled(enabled: Boolean) {
        speakerEnabled = enabled
        enqueue {
            if (audioModeCaptured) applySpeakerRoute()
        }
    }

    fun endCall(reason: String = "local end requested") = dispose(reason)

    fun dispose(reason: String = "call lifecycle cleanup") {
        if (!callbackGate.close()) return
        Log.i(TAG, "deviceId=$deviceId callId=$callId termination reason=$reason")
        try {
            executor.execute {
                cleanupStage("peerconnection-close") { peerConnection?.close() }
                cleanupStage("peerconnection-dispose") { peerConnection?.dispose() }
                peerConnection = null
                synchronized(candidatesLock) {
                    pendingRemoteCandidates.clear()
                    pendingLocalCandidates.clear()
                }
                cleanupStage("audio-track-dispose") { audioTrack?.dispose() }
                audioTrack = null
                cleanupStage("audio-source-dispose") { audioSource?.dispose() }
                audioSource = null
                cleanupStage("peerconnectionfactory-dispose") { factory?.dispose() }
                factory = null
                cleanupStage("audio-device-module-release") { audioDeviceModule?.release() }
                audioDeviceModule = null
                restoreAudioRoute()
                state = WebRtcCallState.ENDED
                executor.shutdown()
            }
        } catch (_: RejectedExecutionException) {
            Log.e(TAG, "deviceId=$deviceId callId=$callId cleanupFailureStage=executor-shutdown")
            executor.shutdown()
        }
    }

    private fun preparePeerConnection() {
        setupStage = "microphone-permission"
        val microphonePermissionGranted = appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "deviceId=$deviceId callId=$callId stage=$setupStage granted=$microphonePermissionGranted")
        if (!audioSetupGate.begin(microphonePermissionGranted)) throw SecurityException("RECORD_AUDIO permission denied")

        setupStage = "audio-routing"
        configureAudioRoute()
        Log.i(TAG, "deviceId=$deviceId callId=$callId stage=$setupStage configured=true")

        setupStage = "peerconnectionfactory-initialization"
        synchronized(initializationLock) {
            if (!factoryInitialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions(),
                )
                factoryInitialized = true
            }
        }
        Log.i(TAG, "deviceId=$deviceId callId=$callId stage=$setupStage initialized=true")

        setupStage = "audio-device-module"
        val deviceModule = checkNotNull(JavaAudioDeviceModule.builder(appContext).createAudioDeviceModule()) {
            "JavaAudioDeviceModule returned null"
        }
        audioDeviceModule = deviceModule
        Log.i(TAG, "deviceId=$deviceId callId=$callId stage=$setupStage initialized=true")

        setupStage = "peerconnectionfactory-creation"
        val peerFactory = checkNotNull(PeerConnectionFactory.builder()
            .setAudioDeviceModule(deviceModule)
            .createPeerConnectionFactory()) {
            "PeerConnectionFactory returned null"
        }
        factory = peerFactory
        Log.i(TAG, "deviceId=$deviceId callId=$callId stage=$setupStage created=true")

        setupStage = "audio-source"
        audioSource = checkNotNull(peerFactory.createAudioSource(MediaConstraints())) { "AudioSource returned null" }
        Log.i(TAG, "deviceId=$deviceId callId=$callId stage=$setupStage created=true")

        setupStage = "audio-track"
        audioTrack = checkNotNull(peerFactory.createAudioTrack("pupsikcall-audio", requireNotNull(audioSource))) {
            "AudioTrack returned null"
        }.apply {
            setEnabled(!muted)
        }
        Log.i(TAG, "deviceId=$deviceId callId=$callId stage=$setupStage created=true")

        setupStage = "peerconnection-creation"
        val configuration = createWebRtcRtcConfiguration(
            turnUrls = BuildConfig.PUPSIKCALL_TURN_URL,
            turnUsername = BuildConfig.PUPSIKCALL_TURN_USERNAME,
            turnCredential = BuildConfig.PUPSIKCALL_TURN_CREDENTIAL,
            debugBuild = BuildConfig.DEBUG,
            forceRelay = BuildConfig.PUPSIKCALL_DEBUG_FORCE_RELAY,
        )
        val connection = peerFactory.createPeerConnection(configuration, peerConnectionObserver())
            ?: error("Unable to create peer connection")
        peerConnection = connection
        publishIceDiagnostics()
        Log.i(TAG, "deviceId=$deviceId callId=$callId stage=$setupStage created=true stun=stun:stun.l.google.com:19302")

        setupStage = "audio-track-attachment"
        checkNotNull(connection.addTrack(requireNotNull(audioTrack), listOf("pupsikcall-audio"))) {
            "PeerConnection.addTrack returned null"
        }
        Log.i(TAG, "deviceId=$deviceId callId=$callId stage=$setupStage attached=true")
        check(audioSetupGate.markReady()) { "Audio setup gate rejected the ready transition" }
        setupStage = "ready"
        Log.i(TAG, "deviceId=$deviceId callId=$callId stage=$setupStage status=READY")
    }

    private fun peerConnectionObserver() = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) {
            Log.d(TAG, "deviceId=$deviceId callId=$callId signalingState=$state")
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.d(TAG, "deviceId=$deviceId callId=$callId iceConnectionState=$state")
            if (BuildConfig.DEBUG) {
                iceDiagnostics.updateIceConnectionState(state.name)
                publishIceDiagnostics()
            }
            when (state) {
                PeerConnection.IceConnectionState.CHECKING -> transition(WebRtcCallState.CONNECTING)
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED,
                -> transition(WebRtcCallState.CONNECTED)
                PeerConnection.IceConnectionState.DISCONNECTED -> transition(WebRtcCallState.DISCONNECTED)
                PeerConnection.IceConnectionState.FAILED -> fail("ice-connection", "ICE connection entered FAILED")
                PeerConnection.IceConnectionState.CLOSED -> transition(WebRtcCallState.ENDED)
                PeerConnection.IceConnectionState.NEW -> Unit
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            Log.d(TAG, "deviceId=$deviceId callId=$callId iceGatheringState=$state")
            if (BuildConfig.DEBUG) {
                iceDiagnostics.updateGatheringState(state.name)
                publishIceDiagnostics()
            }
        }

        override fun onIceCandidateError(event: IceCandidateErrorEvent) {
            if (BuildConfig.DEBUG) {
                iceDiagnostics.recordCandidateGatheringError(event.errorCode)
                publishIceDiagnostics()
            }
        }

        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
            if (BuildConfig.DEBUG) {
                iceDiagnostics.updateSelectedCandidatePair(event.local.sdp, event.remote.sdp)
                publishIceDiagnostics()
            }
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            if (BuildConfig.DEBUG) {
                iceDiagnostics.recordLocalCandidate(candidate.sdp)
                publishIceDiagnostics()
            }
            val canSendNow = synchronized(candidatesLock) {
                if (localDescriptionAnnounced) {
                    true
                } else {
                    pendingLocalCandidates.add(candidate)
                    false
                }
            }
            if (canSendNow) postLocalIceCandidate(candidate)
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(dataChannel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            Log.d(TAG, "deviceId=$deviceId callId=$callId peerConnectionState=$state")
            if (BuildConfig.DEBUG) {
                iceDiagnostics.updatePeerConnectionState(state.name)
                publishIceDiagnostics()
            }
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTING -> transition(WebRtcCallState.CONNECTING)
                PeerConnection.PeerConnectionState.CONNECTED -> transition(WebRtcCallState.CONNECTED)
                PeerConnection.PeerConnectionState.DISCONNECTED -> transition(WebRtcCallState.DISCONNECTED)
                PeerConnection.PeerConnectionState.FAILED -> fail("peerconnection-state", "PeerConnection entered FAILED")
                PeerConnection.PeerConnectionState.CLOSED -> transition(WebRtcCallState.ENDED)
                PeerConnection.PeerConnectionState.NEW -> Unit
            }
        }
    }

    private fun setLocalDescription(connection: PeerConnection, description: SessionDescription) {
        val stage = "set-local-${description.type.canonicalForm()}"
        setupStage = stage
        runPeerOperation(stage) {
            connection.setLocalDescription(
                sdpObserver(
                    onSetSuccess = {
                        synchronized(candidatesLock) {
                            localDescriptionAnnounced = true
                            val candidates = pendingLocalCandidates.drain()
                            postListener {
                                listener.onLocalDescription(description.type.canonicalForm(), description.description)
                                candidates.forEach(::notifyLocalIceCandidate)
                            }
                        }
                        Log.i(TAG, "deviceId=$deviceId callId=$callId stage=$stage success=true")
                        transition(
                            if (description.type == SessionDescription.Type.OFFER) {
                                WebRtcCallState.WAITING_FOR_REMOTE
                            } else {
                                WebRtcCallState.CONNECTING
                            },
                        )
                    },
                    onFailure = { fail(stage, it) },
                ),
                description,
            )
        }
    }

    private fun flushRemoteCandidates(connection: PeerConnection) {
        pendingRemoteCandidates.markRemoteDescriptionSet { candidate -> addRemoteCandidate(connection, candidate) }
    }

    private fun addRemoteCandidate(connection: PeerConnection, candidate: IceCandidate) {
        val added = try {
            addIceCandidateAndRecordResult(candidate, connection::addIceCandidate, ::recordRemoteCandidateResult)
        } catch (exception: Exception) {
            recordRemoteCandidateResult(false)
            fail("add-remote-ice", exception)
            return
        } catch (error: LinkageError) {
            recordRemoteCandidateResult(false)
            fail("add-remote-ice", error)
            return
        }
        if (!added) fail("add-remote-ice", IllegalStateException("PeerConnection rejected the candidate"))
    }

    private fun recordRemoteCandidateResult(added: Boolean) {
        if (BuildConfig.DEBUG) {
            iceDiagnostics.recordRemoteCandidateResult(added)
            publishIceDiagnostics()
        }
    }

    private fun publishIceDiagnostics() {
        if (!BuildConfig.DEBUG) return
        val diagnostic = iceDiagnostics.format()
        postListener { listener.onIceDiagnosticsChanged(diagnostic) }
    }

    private fun postLocalIceCandidate(candidate: IceCandidate) = postListener {
        notifyLocalIceCandidate(candidate)
    }

    private fun notifyLocalIceCandidate(candidate: IceCandidate) {
        listener.onLocalIceCandidate(LocalIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp))
    }

    private fun sdpObserver(
        onCreateSuccess: (SessionDescription) -> Unit = {},
        onSetSuccess: () -> Unit = {},
        onFailure: (String) -> Unit,
    ) = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = onCreateSuccess.invoke(description)
        override fun onSetSuccess() = onSetSuccess.invoke()
        override fun onCreateFailure(error: String) = onFailure(error)
        override fun onSetFailure(error: String) = onFailure(error)
    }

    private fun configureAudioRoute() {
        if (!audioModeCaptured) {
            previousAudioMode = audioManager.mode
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                previousCommunicationDevice = audioManager.communicationDevice
            } else {
                previousSpeakerphoneOn = audioManager.isSpeakerphoneOn
            }
            audioModeCaptured = true
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        applySpeakerRoute()
    }

    @Suppress("DEPRECATION")
    private fun applySpeakerRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val desiredType = if (speakerEnabled) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            val device = audioManager.availableCommunicationDevices.firstOrNull { it.type == desiredType }
            if (device != null) {
                val routed = audioManager.setCommunicationDevice(device)
                Log.i(TAG, "deviceId=$deviceId callId=$callId stage=speaker-routing route=$desiredType success=$routed")
            } else {
                audioManager.clearCommunicationDevice()
                Log.w(TAG, "deviceId=$deviceId callId=$callId stage=speaker-routing route=$desiredType available=false")
            }
        } else {
            audioManager.isSpeakerphoneOn = speakerEnabled
            Log.i(TAG, "deviceId=$deviceId callId=$callId stage=speaker-routing route=speakerphone enabled=$speakerEnabled")
        }
    }

    @Suppress("DEPRECATION")
    private fun restoreAudioRoute() {
        if (!audioModeCaptured) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                previousCommunicationDevice?.let(audioManager::setCommunicationDevice) ?: audioManager.clearCommunicationDevice()
            } else {
                previousSpeakerphoneOn?.let { audioManager.isSpeakerphoneOn = it }
            }
            previousAudioMode?.let { audioManager.mode = it }
        }.onFailure {
            logFailure("audio-route-restore", it)
        }
        audioModeCaptured = false
    }

    private fun transition(next: WebRtcCallState, error: String? = null) {
        if (!callbackGate.isOpen()) return
        state = next
        Log.i(TAG, "deviceId=$deviceId callId=$callId state=$next${if (error == null) "" else " reason=$error"}")
        mainHandler.post {
            callbackGate.dispatch { listener.onStateChanged(next, error) }
        }
    }

    private fun fail(stage: String, message: String) {
        val reason = RuntimeDiagnostic.format("AUDIO", stage, "WebRTC", message)
        audioSetupGate.fail(reason)
        Log.e(TAG, "deviceId=$deviceId callId=$callId $reason")
        transition(WebRtcCallState.FAILED, reason)
    }

    private inline fun runPeerOperation(stage: String, operation: () -> Unit) {
        try {
            operation()
        } catch (exception: Exception) {
            fail(stage, exception)
        } catch (error: LinkageError) {
            fail(stage, error)
        }
    }

    private fun fail(stage: String, failure: Throwable) {
        val reason = RuntimeDiagnostic.fromThrowable("AUDIO", stage, failure)
        audioSetupGate.fail(reason)
        Log.e(TAG, "deviceId=$deviceId callId=$callId $reason")
        transition(WebRtcCallState.FAILED, reason)
    }

    private fun cleanupStage(stage: String, cleanup: () -> Unit) {
        runCatching(cleanup).onFailure { logFailure(stage, it) }
    }

    private fun logFailure(stage: String, failure: Throwable) {
        Log.e(TAG, "deviceId=$deviceId callId=$callId ${RuntimeDiagnostic.fromThrowable("AUDIO", stage, failure)}")
    }

    private fun postListener(callback: () -> Unit) {
        mainHandler.post { callbackGate.dispatch(callback) }
    }

    private fun enqueue(task: () -> Unit) {
        if (!callbackGate.isOpen()) return
        try {
            executor.execute { if (callbackGate.isOpen()) task() }
        } catch (_: RejectedExecutionException) {
            Unit
        }
    }
}