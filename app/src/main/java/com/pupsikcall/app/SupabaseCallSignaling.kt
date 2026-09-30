package com.pupsikcall.app

import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcast as sendBroadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SupabaseCallSignaling(
    private val deviceId: String,
    private val supabaseUrl: String,
    private val supabaseKey: String,
    private val listener: Listener,
) : AutoCloseable {
    interface Listener {
        fun onCallInvite(callId: String, fromDeviceId: String, toDeviceId: String)
        fun onCallAccepted(callId: String, fromDeviceId: String)
        fun onCallDeclined(callId: String, fromDeviceId: String)
        fun onCallEnded(callId: String, fromDeviceId: String)
        fun onRemoteOffer(callId: String, fromDeviceId: String, sdp: String)
        fun onRemoteAnswer(callId: String, fromDeviceId: String, sdp: String)
        fun onRemoteIceCandidate(callId: String, fromDeviceId: String, candidate: LocalIceCandidate)
        fun onPeerPresenceChanged(peerDeviceId: String, online: Boolean)
        fun onSignalError(message: String)
    }

    private companion object {
        const val SIGNALING_CHANNEL = "pupsikcall-signaling"
        const val PRESENCE_CHANNEL = "pupsikcall-presence"
        const val SIGNAL_EVENT_NAME = "signal"
        const val TAG = "HailToneCallSignal"
    }

    private data class ChannelSession(
        val channel: RealtimeChannel,
        val started: CompletableDeferred<Unit> = CompletableDeferred(),
        val sendMutex: Mutex = Mutex(),
        val outgoing: OrderedSignalQueue<OutboundSignal> = OrderedSignalQueue(),
    )

    private data class OutboundSignal(
        val callId: String,
        val peerDeviceId: String,
        val type: String,
        val extra: Map<String, String>,
        val sent: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val callChannels = ConcurrentHashMap<String, ChannelSession>()
    private val supabaseClient: SupabaseClient? = createSupabaseClientIfConfigured()
    private val currentRealtime: Realtime? = supabaseClient?.pluginManager?.getPlugin(Realtime)
    private val connectionReady = CompletableDeferred<Unit>()
    private var globalSession: ChannelSession? = null
    private var presenceChannel: RealtimeChannel? = null
    private val presenceTracker = PresenceDeviceTracker(deviceId)
    private val callPhases = CallPhaseMachine()
    private val presenceSessionKey = "$deviceId-${UUID.randomUUID()}"
    @Volatile private var closed = false

    internal val authClient: SupabaseClient?
        get() = supabaseClient

    init {
        if (supabaseUrl.isBlank() || supabaseKey.isBlank()) {
            listener.onSignalError(
                RuntimeDiagnostic.format("REALTIME", "configuration", "IllegalStateException", "Supabase configuration is missing"),
            )
        } else {
            connect()
        }
    }

    fun startCall(callId: String, peerDeviceId: String) {
        val session = globalSession ?: return signalUnavailable()
        val callSession = runCatching { ensureCallChannel(callId) }.getOrElse {
            signalUnavailable()
            return
        }
        val phase = callPhases.record(callId, "ringing")
        Log.d(TAG, "deviceId=$deviceId callId=$callId queued event=ringing peer=$peerDeviceId phase=$phase")
        scope.launch {
            runCatching {
                awaitSubscribed(callSession, "callId=$callId")
                sendSignal(session, callId, peerDeviceId, "ringing")
            }.onFailure {
                reportRealtimeFailure("call-subscription", it)
            }
        }
    }

    fun markConnected(callId: String) {
        val phase = callPhases.record(callId, "connected")
        Log.i(TAG, "deviceId=$deviceId callId=$callId signalingPhase=$phase")
    }

    fun prepareCall(callId: String) {
        ensureCallChannel(callId)
    }

    fun acceptCall(callId: String, peerDeviceId: String) {
        sendOnCallChannel(callId, peerDeviceId, "accepted")
    }

    fun declineCall(callId: String, peerDeviceId: String) {
        sendOnCallChannel(callId, peerDeviceId, "declined", cleanupAfterSend = true)
    }

    fun endCall(callId: String, peerDeviceId: String) {
        sendOnCallChannel(callId, peerDeviceId, "ended", cleanupAfterSend = true)
    }

    fun sendOffer(callId: String, peerDeviceId: String, sdp: String) {
        sendOnCallChannel(callId, peerDeviceId, "offer", mapOf("sdp" to sdp))
    }

    fun sendAnswer(callId: String, peerDeviceId: String, sdp: String) {
        sendOnCallChannel(callId, peerDeviceId, "answer", mapOf("sdp" to sdp))
    }

    fun sendIceCandidate(callId: String, peerDeviceId: String, candidate: LocalIceCandidate) {
        sendOnCallChannel(
            callId,
            peerDeviceId,
            "ice",
            mapOf(
                "sdpMid" to (candidate.sdpMid ?: ""),
                "sdpMLineIndex" to candidate.sdpMLineIndex.toString(),
                "sdp" to candidate.sdp,
            ),
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        scope.launch {
            try {
                callChannels.values.forEach {
                    it.outgoing.close()
                    currentRealtime?.removeChannel(it.channel)
                }
                callChannels.clear()
                globalSession?.let { currentRealtime?.removeChannel(it.channel) }
                presenceChannel?.let { currentRealtime?.removeChannel(it) }
                supabaseClient?.close()
            } finally {
                scope.cancel()
            }
        }
    }

    private fun connect() {
        val realtime = currentRealtime ?: return
        val globalChannel = realtime.channel(SIGNALING_CHANNEL) { broadcast {} }
        globalSession = createSession(globalChannel, "global")
        scope.launch {
            runCatching { realtime.connect() }
                .onSuccess { connectionReady.complete(Unit) }
                .onFailure {
                    reportRealtimeFailure("connect", it)
                    connectionReady.completeExceptionally(it)
                }
        }
        startPresence(realtime)
    }

    private fun ensureCallChannel(callId: String): ChannelSession = callChannels.computeIfAbsent(callId) {
        val channel = currentRealtime?.channel("pupsikcall-call-$callId") { broadcast {} }
            ?: throw IllegalStateException("Supabase Realtime is not configured")
        createSession(channel, "callId=$callId")
    }

    private fun cleanupCall(callId: String) {
        val session = callChannels.remove(callId)
        if (session != null) {
            session.outgoing.close()
            callPhases.remove(callId)
            scope.launch { currentRealtime?.removeChannel(session.channel) }
        }
    }

    private fun createSession(channel: RealtimeChannel, label: String): ChannelSession {
        val session = ChannelSession(channel)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            channel.status.collect { status ->
                Log.d(TAG, "deviceId=$deviceId channel=$label subscription=$status")
                if (status == RealtimeChannel.Status.SUBSCRIBED) session.started.complete(Unit)
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            channel.broadcastFlow<JsonObject>(SIGNAL_EVENT_NAME).collect { handleIncomingMessage(it) }
        }
        scope.launch {
            session.outgoing.asFlow().collect { signal ->
                sendSignal(session, signal.callId, signal.peerDeviceId, signal.type, signal.extra)
                signal.sent.complete(Unit)
            }
        }
        scope.launch {
            var stage = "connect"
            try {
                connectionReady.await()
                stage = "subscribe"
                channel.subscribe(false)
                stage = "await-subscribed"
                awaitSubscribed(session, label)
            } catch (failure: Throwable) {
                reportRealtimeFailure("$label/$stage", failure)
            }
        }
        return session
    }

    private suspend fun awaitSubscribed(session: ChannelSession, label: String) {
        try {
            withTimeout(20_000) {
                session.started.await()
                session.channel.status.first { it == RealtimeChannel.Status.SUBSCRIBED }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw IllegalStateException("Channel $label remained ${session.channel.status.value} for 20 seconds", timeout)
        }
    }

    private fun sendOnCallChannel(
        callId: String,
        peerDeviceId: String,
        type: String,
        extra: Map<String, String> = emptyMap(),
        cleanupAfterSend: Boolean = false,
    ) {
        val session = runCatching { ensureCallChannel(callId) }.getOrElse {
            signalUnavailable()
            return
        }
        val signal = OutboundSignal(callId, peerDeviceId, type, extra)
        if (!session.outgoing.enqueue(signal, terminal = type == "ended" || type == "declined")) {
            Log.d(TAG, "deviceId=$deviceId callId=$callId ignored event=$type after terminal signaling")
            return
        }
        val phase = callPhases.record(callId, type)
        Log.d(TAG, "deviceId=$deviceId callId=$callId queued event=$type peer=$peerDeviceId phase=$phase")
        if (cleanupAfterSend) {
            scope.launch {
                signal.sent.await()
                cleanupCall(callId)
            }
        }
    }

    private suspend fun sendSignal(
        session: ChannelSession,
        callId: String,
        peerDeviceId: String,
        type: String,
        extra: Map<String, String> = emptyMap(),
    ) {
        runCatching {
            awaitSubscribed(session, "callId=$callId")
            session.sendMutex.withLock {
                session.channel.sendBroadcast(
                    SIGNAL_EVENT_NAME,
                    buildSignalMessage(callId, peerDeviceId, type, extra),
                )
            }
        }.onSuccess {
            val phase = callPhases.phase(callId)
            Log.d(TAG, "deviceId=$deviceId callId=$callId sent event=$type peer=$peerDeviceId phase=$phase")
        }.onFailure {
            reportRealtimeFailure("broadcast-$type", it)
        }
    }

    private fun signalUnavailable() {
        listener.onSignalError(
            RuntimeDiagnostic.format("REALTIME", "configuration", "IllegalStateException", "Supabase Realtime is unavailable"),
        )
    }

    private fun reportRealtimeFailure(stage: String, failure: Throwable) {
        val diagnostic = RuntimeDiagnostic.fromThrowable("REALTIME", stage, failure)
        Log.e(TAG, "deviceId=$deviceId $diagnostic")
        listener.onSignalError(diagnostic)
    }

    private fun startPresence(realtime: Realtime) {
        val channel = realtime.channel(PRESENCE_CHANNEL) {
            presence { key = presenceSessionKey }
        }
        presenceChannel = channel

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            channel.presenceChangeFlow().collect { action ->
                action.joins.forEach { (presenceRef, presence) ->
                    presence.state["deviceId"]?.jsonPrimitive?.content?.let { device ->
                        Log.d(TAG, "deviceId=$deviceId presence joined deviceId=$device")
                        presenceTracker.join(presenceRef, device)?.let { (peer, online) ->
                            listener.onPeerPresenceChanged(peer, online)
                        }
                    }
                }
                action.leaves.forEach { (presenceRef, presence) ->
                    val departedDevice = presenceTracker.leave(
                        presenceRef,
                        presence.state["deviceId"]?.jsonPrimitive?.content,
                    )
                    departedDevice?.let { (peer, online) ->
                        Log.d(TAG, "deviceId=$deviceId presence left deviceId=$peer")
                        listener.onPeerPresenceChanged(peer, online)
                    }
                }
            }
        }

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            channel.status.collect { status ->
                Log.d(TAG, "deviceId=$deviceId channel=presence subscription=$status")
                if (status == RealtimeChannel.Status.SUBSCRIBED) {
                    scope.launch {
                        runCatching {
                            channel.track(buildJsonObject { put("deviceId", JsonPrimitive(deviceId)) })
                        }.onFailure {
                            reportRealtimeFailure("presence-track", it)
                        }
                    }
                } else {
                    presenceTracker.clear()?.let { (peer, online) -> listener.onPeerPresenceChanged(peer, online) }
                }
            }
        }

        scope.launch {
            runCatching {
                connectionReady.await()
                channel.subscribe(false)
            }.onFailure {
                reportRealtimeFailure("presence-subscribe", it)
            }
        }
    }

    private fun handleIncomingMessage(message: JsonObject) {
        val callId = message["callId"]?.jsonPrimitive?.content ?: return
        val fromDeviceId = message["fromDeviceId"]?.jsonPrimitive?.content ?: return
        val toDeviceId = message["toDeviceId"]?.jsonPrimitive?.content
        val type = message["type"]?.jsonPrimitive?.content ?: return

        if (fromDeviceId == deviceId) return
        if (toDeviceId != null && toDeviceId != deviceId) return

        val phase = callPhases.record(callId, type)
        Log.d(TAG, "deviceId=$deviceId callId=$callId received event=$type peer=$fromDeviceId phase=$phase")

        when (type) {
            "ringing" -> listener.onCallInvite(callId, fromDeviceId, toDeviceId ?: fromDeviceId)
            "accepted" -> listener.onCallAccepted(callId, fromDeviceId)
            "declined" -> {
                listener.onCallDeclined(callId, fromDeviceId)
                cleanupCall(callId)
            }
            "ended" -> {
                listener.onCallEnded(callId, fromDeviceId)
                cleanupCall(callId)
            }
            "offer" -> {
                val sdp = message["sdp"]?.jsonPrimitive?.content ?: return
                listener.onRemoteOffer(callId, fromDeviceId, sdp)
            }
            "answer" -> {
                val sdp = message["sdp"]?.jsonPrimitive?.content ?: return
                listener.onRemoteAnswer(callId, fromDeviceId, sdp)
            }
            "ice" -> {
                val sdpMid = message["sdpMid"]?.jsonPrimitive?.content?.ifEmpty { null }
                val sdpMLineIndex = message["sdpMLineIndex"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val sdp = message["sdp"]?.jsonPrimitive?.content ?: return
                listener.onRemoteIceCandidate(callId, fromDeviceId, LocalIceCandidate(sdpMid, sdpMLineIndex, sdp))
            }
        }
    }

    private fun buildSignalMessage(
        callId: String,
        peerDeviceId: String,
        type: String,
        extra: Map<String, String> = emptyMap(),
    ): JsonObject = buildJsonObject {
        put("callId", JsonPrimitive(callId))
        put("fromDeviceId", JsonPrimitive(deviceId))
        put("toDeviceId", JsonPrimitive(peerDeviceId))
        put("type", JsonPrimitive(type))
        put("sentAt", JsonPrimitive(System.currentTimeMillis().toString()))
        extra.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
    }

    private fun createSupabaseClientIfConfigured(): SupabaseClient? =
        if (supabaseUrl.isBlank() || supabaseKey.isBlank()) {
            null
        } else {
            createSupabaseClient(supabaseUrl, supabaseKey) {
                httpEngine = createSupabaseRealtimeHttpEngine()
                defaultLogLevel = LogLevel.NONE
                install(Auth) {
                    autoLoadFromStorage = true
                    autoSaveToStorage = true
                }
                install(Realtime)
                install(Postgrest)
            }
        }

}
