package com.pupsikcall.app

import org.webrtc.PeerConnection
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

internal class CallCallbackGate {
    private var open = true

    @Synchronized
    fun close(): Boolean {
        if (!open) return false
        open = false
        return true
    }

    @Synchronized
    fun isOpen(): Boolean = open

    @Synchronized
    fun dispatch(callback: () -> Unit) {
        if (open) callback()
    }
}

internal data class CallUiStatus(val state: WebRtcCallState, val error: String?)

internal fun CallUiStatus.withSignalError(message: String): CallUiStatus = copy(error = message)

internal class OrderedSignalQueue<T> {
    private val queue = Channel<T>(Channel.UNLIMITED)
    private var terminated = false

    @Synchronized
    fun enqueue(signal: T, terminal: Boolean = false): Boolean {
        if (terminated) return false
        if (terminal) terminated = true
        return queue.trySend(signal).isSuccess
    }

    fun asFlow(): Flow<T> = queue.receiveAsFlow()

    fun close() = queue.close()
}

internal enum class CallPhase {
    IDLE,
    RINGING,
    ACCEPTED,
    OFFERED,
    ANSWERED,
    CONNECTED,
    DECLINED,
    ENDED,
}

internal class CallPhaseMachine {
    private val phases = mutableMapOf<String, CallPhase>()

    @Synchronized
    fun record(callId: String, event: String): CallPhase {
        val current = phases[callId] ?: CallPhase.IDLE
        val next = when (event) {
            "ringing" -> if (current == CallPhase.IDLE) CallPhase.RINGING else current
            "accepted" -> if (current == CallPhase.RINGING) CallPhase.ACCEPTED else current
            "offer" -> if (current == CallPhase.ACCEPTED) CallPhase.OFFERED else current
            "answer" -> if (current == CallPhase.OFFERED) CallPhase.ANSWERED else current
            "connected" -> if (current == CallPhase.ANSWERED || current == CallPhase.OFFERED) CallPhase.CONNECTED else current
            "declined" -> if (current != CallPhase.IDLE) CallPhase.DECLINED else current
            "ended" -> if (current != CallPhase.IDLE) CallPhase.ENDED else current
            else -> current
        }
        if (next != CallPhase.IDLE) phases[callId] = next
        return next
    }

    @Synchronized
    fun phase(callId: String): CallPhase = phases[callId] ?: CallPhase.IDLE

    @Synchronized
    fun remove(callId: String) {
        phases.remove(callId)
    }
}

internal class PresenceDeviceTracker(localDeviceId: String) {
    private val peerDeviceId = when (localDeviceId) {
        "pupsik-a" -> "pupsik-b"
        "pupsik-b" -> "pupsik-a"
        else -> null
    }
    private val sessions = mutableMapOf<String, String>()

    @Synchronized
    fun join(presenceRef: String, deviceId: String): Pair<String, Boolean>? {
        val wasOnline = isOnlineLocked()
        sessions[presenceRef] = deviceId
        return changedState(wasOnline)
    }

    @Synchronized
    fun leave(presenceRef: String, fallbackDeviceId: String?): Pair<String, Boolean>? {
        val wasOnline = isOnlineLocked()
        val departedDeviceId = sessions.remove(presenceRef) ?: fallbackDeviceId
        if (departedDeviceId != peerDeviceId) return null
        return changedState(wasOnline)
    }

    @Synchronized
    fun clear(): Pair<String, Boolean>? {
        val wasOnline = isOnlineLocked()
        sessions.clear()
        return changedState(wasOnline)
    }

    @Synchronized
    fun isPeerOnline(): Boolean = isOnlineLocked()

    private fun isOnlineLocked(): Boolean = peerDeviceId != null && sessions.values.any { it == peerDeviceId }

    private fun changedState(wasOnline: Boolean): Pair<String, Boolean>? {
        val peer = peerDeviceId ?: return null
        val isOnline = isOnlineLocked()
        return if (wasOnline == isOnline) null else peer to isOnline
    }
}

internal class PendingIceBuffer<T> {
    private val candidates = mutableListOf<T>()

    @Synchronized
    fun add(candidate: T) {
        candidates.add(candidate)
    }

    @Synchronized
    fun drain(): List<T> = candidates.toList().also { candidates.clear() }

    @Synchronized
    fun clear() = candidates.clear()
}

internal enum class IceCandidateType(val label: String) {
    HOST("host"),
    SRFLX("srflx"),
    RELAY("relay"),
}

internal fun classifyIceCandidateType(candidate: String): IceCandidateType? =
    when (Regex("(?:^|\\s)typ\\s+(host|srflx|relay)(?:\\s|$)", RegexOption.IGNORE_CASE).find(candidate)?.groupValues?.get(1)?.lowercase()) {
        "host" -> IceCandidateType.HOST
        "srflx" -> IceCandidateType.SRFLX
        "relay" -> IceCandidateType.RELAY
        else -> null
    }

    internal fun createWebRtcRtcConfiguration(
        turnUrls: String,
        turnUsername: String,
        turnCredential: String,
        debugBuild: Boolean,
        forceRelay: Boolean,
    ): PeerConnection.RTCConfiguration {
        val iceServers = mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        )
        val urls = turnUrls.split(',').map(String::trim).filter(String::isNotEmpty)
        val hasCompleteCredentials = turnUsername.isNotBlank() && turnCredential.isNotBlank()
        if (urls.isNotEmpty() && hasCompleteCredentials && urls.all(::isSafeTurnUrl)) {
            iceServers += PeerConnection.IceServer.builder(urls)
                .setUsername(turnUsername)
                .setPassword(turnCredential)
                .createIceServer()
        }

        return PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = if (debugBuild && forceRelay) {
                PeerConnection.IceTransportsType.RELAY
            } else {
                PeerConnection.IceTransportsType.ALL
            }
        }
    }

    private fun isSafeTurnUrl(url: String): Boolean =
        (url.startsWith("turn:", ignoreCase = true) || url.startsWith("turns:", ignoreCase = true)) &&
            !url.contains('@') &&
            !Regex("(?i)[?&](?:username|password|credential|token)=").containsMatchIn(url)

internal data class IceCandidateTypeCounts(
    val host: Int = 0,
    val srflx: Int = 0,
    val relay: Int = 0,
) {
    fun record(candidate: String): IceCandidateTypeCounts = when (classifyIceCandidateType(candidate)) {
        IceCandidateType.HOST -> copy(host = host + 1)
        IceCandidateType.SRFLX -> copy(srflx = srflx + 1)
        IceCandidateType.RELAY -> copy(relay = relay + 1)
        null -> this
    }

    fun format(): String = "host=$host srflx=$srflx relay=$relay"
}

internal class IceDiagnostics {
    private var gatheringState = "NEW"
    private var iceConnectionState = "NEW"
    private var peerConnectionState = "NEW"
    private var localCandidates = IceCandidateTypeCounts()
    private var remoteCandidates = IceCandidateTypeCounts()
    private var remoteReceived = 0
    private var remoteAdded = 0
    private var remoteAddFailed = 0
    private var candidateGatheringErrors = 0
    private var lastCandidateGatheringErrorCode: Int? = null
    private var selectedCandidatePair: String? = null

    @Synchronized
    fun updateGatheringState(state: String) {
        gatheringState = state.takeIf { it in GATHERING_STATES } ?: "UNKNOWN"
    }

    @Synchronized
    fun updateIceConnectionState(state: String) {
        iceConnectionState = state.takeIf { it in ICE_CONNECTION_STATES } ?: "UNKNOWN"
    }

    @Synchronized
    fun updatePeerConnectionState(state: String) {
        peerConnectionState = state.takeIf { it in PEER_CONNECTION_STATES } ?: "UNKNOWN"
    }

    @Synchronized
    fun recordLocalCandidate(candidate: String) {
        localCandidates = localCandidates.record(candidate)
    }

    @Synchronized
    fun recordRemoteCandidateReceived(candidate: String) {
        remoteReceived++
        remoteCandidates = remoteCandidates.record(candidate)
    }

    @Synchronized
    fun recordRemoteCandidateResult(added: Boolean) {
        if (added) remoteAdded++ else remoteAddFailed++
    }

    @Synchronized
    fun recordCandidateGatheringError(errorCode: Int) {
        candidateGatheringErrors++
        lastCandidateGatheringErrorCode = errorCode
    }

    @Synchronized
    fun updateSelectedCandidatePair(localCandidate: String, remoteCandidate: String) {
        val localType = classifyIceCandidateType(localCandidate)?.label ?: "other"
        val remoteType = classifyIceCandidateType(remoteCandidate)?.label ?: "other"
        selectedCandidatePair = "$localType <-> $remoteType"
    }

    @Synchronized
    fun format(): String = buildString {
        append("ICE ")
        append(if (iceConnectionState == "FAILED" || peerConnectionState == "FAILED") "FAILED" else iceConnectionState)
        append("\ngathering=").append(gatheringState)
        append("\niceConnection=").append(iceConnectionState)
        append("\npeerConnection=").append(peerConnectionState)
        append("\nlocal ").append(localCandidates.format())
        append("\nremote ").append(remoteCandidates.format())
        append("\ncandidate add received=").append(remoteReceived)
            .append(" added=").append(remoteAdded)
            .append(" failed=").append(remoteAddFailed)
        if (candidateGatheringErrors > 0) {
            append("\ngathering errors=").append(candidateGatheringErrors)
                .append(" last-code=").append(lastCandidateGatheringErrorCode)
        }
        selectedCandidatePair?.let { append("\nselected=").append(it) }
    }

    private companion object {
        val GATHERING_STATES = setOf("NEW", "GATHERING", "COMPLETE")
        val ICE_CONNECTION_STATES = setOf("NEW", "CHECKING", "CONNECTED", "COMPLETED", "DISCONNECTED", "FAILED", "CLOSED")
        val PEER_CONNECTION_STATES = setOf("NEW", "CONNECTING", "CONNECTED", "DISCONNECTED", "FAILED", "CLOSED")
    }
}

internal fun <T> addIceCandidateAndRecordResult(
    candidate: T,
    addCandidate: (T) -> Boolean,
    recordResult: (Boolean) -> Unit,
): Boolean {
    val added = addCandidate(candidate)
    recordResult(added)
    return added
}

internal class RemoteIceCandidateBuffer<T> {
    private val candidates = mutableListOf<T>()
    private var remoteDescriptionSet = false

    @Synchronized
    fun add(candidate: T, addNow: (T) -> Unit): Boolean {
        if (!remoteDescriptionSet) {
            candidates.add(candidate)
            return false
        }
        addNow(candidate)
        return true
    }

    @Synchronized
    fun markRemoteDescriptionSet(addNow: (T) -> Unit) {
        remoteDescriptionSet = true
        val buffered = candidates.toList()
        candidates.clear()
        buffered.forEach(addNow)
    }

    @Synchronized
    fun clear() = candidates.clear()
}

internal enum class AudioSetupStatus {
    NOT_STARTED,
    PREPARING,
    READY,
    FAILED,
}

internal class AudioSetupGate {
    @Volatile
    var status: AudioSetupStatus = AudioSetupStatus.NOT_STARTED
        private set

    @Volatile
    var failureReason: String? = null
        private set

    val canNegotiate: Boolean
        get() = status == AudioSetupStatus.READY

    @Synchronized
    fun begin(microphonePermissionGranted: Boolean): Boolean {
        if (!microphonePermissionGranted) {
            fail("Microphone permission denied")
            return false
        }
        if (status == AudioSetupStatus.READY) return true
        if (status == AudioSetupStatus.FAILED) return false
        status = AudioSetupStatus.PREPARING
        failureReason = null
        return true
    }

    @Synchronized
    fun markReady(): Boolean {
        if (status != AudioSetupStatus.PREPARING) return false
        status = AudioSetupStatus.READY
        return true
    }

    @Synchronized
    fun fail(reason: String) {
        status = AudioSetupStatus.FAILED
        failureReason = reason
    }
}

internal object RuntimeDiagnostic {
    fun failureDisplayText(debugBuild: Boolean, genericText: String, diagnostic: String?): String =
        if (debugBuild) diagnostic ?: "AUDIO / unknown / diagnostic unavailable" else genericText

    fun fromThrowable(subsystem: String, stage: String, failure: Throwable): String {
        var root = failure
        val seen = mutableSetOf<Throwable>()
        while (root.cause != null && seen.add(root)) root = root.cause!!
        val type = root::class.java.simpleName.ifBlank { "Failure" }
        return format(subsystem, stage, type, root.message ?: "No detail provided")
    }

    fun format(subsystem: String, stage: String, type: String, message: String): String =
        "$subsystem / ${sanitize(stage)} / ${sanitize(type)}: ${sanitize(message)}"

    fun sanitize(value: String): String = value
        .replace(Regex("(?is)\\bv=0.*"), "[SDP redacted]")
        .replace(Regex("(?i)\\bturns?:[^\\s,;)'\\]}>]+"), "[TURN URL redacted]")
        .lineSequence()
        .filterNot { line ->
            val normalized = line.trimStart()
            normalized.startsWith("a=") ||
                normalized.startsWith("c=") ||
                normalized.startsWith("m=") ||
                normalized.startsWith("o=") ||
                normalized.startsWith("s=") ||
                normalized.startsWith("t=") ||
                normalized.startsWith("candidate:") ||
                normalized.startsWith("v=")
        }
        .joinToString(" ")
        .replace(Regex("(?i)\\b(?:https?|wss?)://[^\\s,;)'\\]}>]+"), "[URL redacted]")
        .replace(
            Regex("(?i)(api[_-]?key|supabase[_-]?key|access[_-]?token|token|username|credential|password|authorization|ice-pwd|ice-ufrag)(\\s*[:=]\\s*)[^&\\s,;]+"),
            "$1$2[redacted]",
        )
        .replace(Regex("(?i)\\bsb_(?:publishable|secret)_[A-Za-z0-9_-]+"), "[Supabase key redacted]")
        .replace(Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+"), "Bearer [token redacted]")
        .replace(Regex("\\beyJ[A-Za-z0-9_-]*\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b"), "[token redacted]")
        .ifBlank { "No detail provided" }
        .take(180)
}