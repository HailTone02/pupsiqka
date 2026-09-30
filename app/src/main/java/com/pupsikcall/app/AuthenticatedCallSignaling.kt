package com.pupsikcall.app

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

internal fun isValidSupabaseClientConfiguration(url: String, key: String): Boolean {
    val parsedUrl = runCatching { URI(url) }.getOrNull() ?: return false
    val validUrl = parsedUrl.scheme.equals("https", ignoreCase = true) &&
        !parsedUrl.host.isNullOrBlank() && parsedUrl.userInfo == null &&
        parsedUrl.query == null && parsedUrl.fragment == null
    val validPublishableKey = key.matches(Regex("sb_publishable_[A-Za-z0-9_-]+"))
    val legacyRole = key.split('.').takeIf { it.size == 3 }?.get(1)
        ?.let(::decodeBase64Url)
        ?.let { runCatching { Json.parseToJsonElement(it).jsonObject["role"]?.jsonPrimitive?.content }.getOrNull() }
    val validLegacyAnonKey = legacyRole == "anon"
    return validUrl && (validPublishableKey || validLegacyAnonKey)
}

private fun decodeBase64Url(value: String): String? {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val bytes = ByteArray(value.length * 3 / 4 + 3)
    var accumulator = 0
    var bitCount = 0
    var byteCount = 0
    for (character in value) {
        val digit = alphabet.indexOf(character)
        if (digit < 0) return null
        accumulator = (accumulator shl 6) or digit
        bitCount += 6
        if (bitCount >= 8) {
            bitCount -= 8
            bytes[byteCount++] = (accumulator shr bitCount).toByte()
        }
    }
    return bytes.copyOf(byteCount).toString(Charsets.UTF_8)
}

internal class AuthenticatedCallSignaling(
    supabaseUrl: String,
    supabaseKey: String,
    private val listener: Listener,
) : AutoCloseable {
    interface Listener {
        fun onAuthenticatedCallSessionChanged(session: AuthenticatedCallSession)
        fun onAuthenticatedCallSessionLost()
        fun onAuthenticatedCallError()
        fun onRemoteOffer(session: AuthenticatedCallSession, sdp: String)
        fun onRemoteAnswer(session: AuthenticatedCallSession, sdp: String)
        fun onRemoteIceCandidate(session: AuthenticatedCallSession, candidate: LocalIceCandidate)
    }

    private data class OutgoingMedia(
        val session: AuthenticatedCallSession,
        val event: CallMediaEvent,
        val extra: Map<String, String>,
    )

    private data class CallChannel(
        val session: AuthenticatedCallSession,
        val channel: RealtimeChannel,
        val subscribed: CompletableDeferred<Unit> = CompletableDeferred(),
        val sendMutex: Mutex = Mutex(),
        val outgoing: OrderedSignalQueue<OutgoingMedia> = OrderedSignalQueue(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client: SupabaseClient? = createSupabaseClientIfConfigured(supabaseUrl, supabaseKey)
    internal val authClient: SupabaseClient?
        get() = client
    private val realtime: Realtime? = client?.pluginManager?.getPlugin(Realtime)
    private val connectionReady = CompletableDeferred<Unit>()
    private val callChannels = ConcurrentHashMap<UUID, CallChannel>()
    private val inboxGeneration = Any()
    @Volatile private var localUserId: UUID? = null
    private val authenticatedUserState = MutableStateFlow<UUID?>(null)
    @Volatile private var inboxChannel: RealtimeChannel? = null
    @Volatile private var closed = false

    init {
        if (client == null) {
            listener.onAuthenticatedCallError()
        } else {
            connectAuthenticatedRouting()
        }
    }

    fun authenticatedUserId(): UUID? = localUserId

    suspend fun awaitAuthenticatedUserId(): UUID? {
        val configuredClient = client ?: return null
        val status = configuredClient.auth.sessionStatus.first { it != SessionStatus.Initializing }
        val expectedUserId = (status as? SessionStatus.Authenticated)
            ?.let { authenticatedCallUserId(it.session.user?.id) }
            ?: return null
        return withTimeoutOrNull(10_000) {
            authenticatedUserState.filterNotNull().first { it == expectedUserId }
        }
    }

    suspend fun createCallSession(callId: UUID, calleeUserId: UUID): AuthenticatedCallSession {
        val authenticatedUserId = requireAuthenticatedUser()
        if (authenticatedUserId == calleeUserId) throw IllegalArgumentException("Self-calls are not allowed")
        val supabase = requireClient()
        val row = supabase.postgrest.rpc(
            "create_call_session",
            buildJsonObject {
                put("p_call_id", JsonPrimitive(callId.toString()))
                put("p_callee_user_id", JsonPrimitive(calleeUserId.toString()))
            },
        ).decodeSingle<JsonObject>()
        ensureSameUser(authenticatedUserId)
        val session = parseAuthenticatedCallSession(row)
            ?: throw IllegalStateException("Invalid call session response")
        val route = outgoingCallRoute(authenticatedUserId, calleeUserId, session)
            ?: throw IllegalStateException("Call session participants did not match")
        callChannels[session.callId]?.let { callChannels[session.callId] = it.copy(session = route.session) }
        return route.session
    }

    suspend fun prepareCall(session: AuthenticatedCallSession) {
        val userId = requireAuthenticatedUser()
        if (session.remoteUserId(userId) == null) throw SecurityException("Call participant mismatch")
        val currentSession = callChannels[session.callId]
        if (currentSession != null) {
            currentSession.subscribed.await()
            return
        }
        val realtimeClient = realtime ?: throw IllegalStateException("Realtime is unavailable")
        val channel = realtimeClient.channel("pupsikcall-call:${session.callId}") { isPrivate = true }
        val callChannel = CallChannel(session, channel)
        val existing = callChannels.putIfAbsent(session.callId, callChannel)
        if (existing != null) {
            existing.subscribed.await()
            return
        }
        startMediaCollectors(callChannel)
        try {
            channel.subscribe(blockUntilSubscribed = true)
            callChannel.subscribed.complete(Unit)
        } catch (cancelled: CancellationException) {
            callChannels.remove(session.callId, callChannel)
            callChannel.outgoing.close()
            realtimeClient.removeChannel(channel)
            throw cancelled
        } catch (failure: Exception) {
            callChannels.remove(session.callId, callChannel)
            callChannel.outgoing.close()
            realtimeClient.removeChannel(channel)
            callChannel.subscribed.completeExceptionally(failure)
            throw IllegalStateException("Call media channel unavailable")
        }
    }

    suspend fun ringCall(session: AuthenticatedCallSession) = lifecycleRpc("ring_call_session", session.callId)
    suspend fun acceptCall(session: AuthenticatedCallSession) = lifecycleRpc("accept_call_session", session.callId)
    suspend fun declineCall(session: AuthenticatedCallSession) = lifecycleRpc("decline_call_session", session.callId)
    suspend fun cancelCall(session: AuthenticatedCallSession) = lifecycleRpc("cancel_call_session", session.callId)
    suspend fun markConnected(session: AuthenticatedCallSession) = lifecycleRpc("mark_call_connected", session.callId)
    suspend fun finishCall(session: AuthenticatedCallSession) = lifecycleRpc("finish_call_session", session.callId)

    suspend fun failCall(session: AuthenticatedCallSession, failureCode: String) {
        val userId = requireAuthenticatedUser()
        requireParticipant(session, userId)
        requireClient().postgrest.rpc(
            "fail_call_session",
            buildJsonObject {
                put("p_call_id", JsonPrimitive(session.callId.toString()))
                put("p_failure_code", JsonPrimitive(failureCode))
            },
        )
        ensureSameUser(userId)
    }

    fun sendOffer(session: AuthenticatedCallSession, sdp: String) =
        enqueueMedia(session, CallMediaEvent.OFFER, mapOf("sdp" to sdp))

    fun sendAnswer(session: AuthenticatedCallSession, sdp: String) =
        enqueueMedia(session, CallMediaEvent.ANSWER, mapOf("sdp" to sdp))

    fun sendIceCandidate(session: AuthenticatedCallSession, candidate: LocalIceCandidate) =
        enqueueMedia(
            session,
            CallMediaEvent.ICE,
            mapOf(
                "sdpMid" to (candidate.sdpMid ?: ""),
                "sdpMLineIndex" to candidate.sdpMLineIndex.toString(),
                "sdp" to candidate.sdp,
            ),
        )

    suspend fun loadPendingInvitations(): List<AuthenticatedCallSession> {
        val userId = requireAuthenticatedUser()
        val rows = requireClient().postgrest.rpc("list_pending_call_sessions").decodeList<JsonObject>()
        ensureSameUser(userId)
        return rows.mapNotNull(::parseAuthenticatedCallSession)
            .filter { it.calleeUserId == userId && it.status == AuthenticatedCallStatus.RINGING }
    }

    suspend fun registerCallPushToken(installationId: UUID, token: String) {
        val userId = requireAuthenticatedUser()
        if (token.length !in 20..4096 || token != token.trim() || token.any(Char::isISOControl)) {
            throw IllegalArgumentException("Invalid push token")
        }
        requireClient().postgrest.rpc(
            "register_call_push_token",
            buildJsonObject {
                put("p_installation_id", JsonPrimitive(installationId.toString()))
                put("p_fcm_token", JsonPrimitive(token))
            },
        )
        ensureSameUser(userId)
    }

    suspend fun unregisterCallPushToken(installationId: UUID) {
        val userId = requireAuthenticatedUser()
        requireClient().postgrest.rpc(
            "unregister_call_push_token",
            buildJsonObject { put("p_installation_id", JsonPrimitive(installationId.toString())) },
        )
        ensureSameUser(userId)
    }

    suspend fun refreshPendingInvitations(): List<AuthenticatedCallSession> {
        val userId = requireAuthenticatedUser()
        val invitations = loadPendingInvitations()
        ensureSameUser(userId)
        invitations.forEach { dispatchCallSession(it, userId) }
        return invitations
    }

    suspend fun loadPublicDisplayName(userId: UUID): String? {
        val localId = requireAuthenticatedUser()
        val row = requireClient().postgrest.rpc(
            "get_public_profiles",
            buildJsonObject { put("p_user_ids", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive(userId.toString())) }) },
        ).decodeList<JsonObject>().singleOrNull()
        ensureSameUser(localId)
        return row?.get("display_name")?.jsonPrimitive?.content
    }

    override fun close() {
        if (closed) return
        closed = true
        callChannels.values.forEach { it.outgoing.close() }
        callChannels.clear()
        scope.launch {
            try {
                inboxChannel?.let { realtime?.removeChannel(it) }
                realtime?.let { plugin ->
                    callChannels.values.forEach { plugin.removeChannel(it.channel) }
                }
                client?.close()
            } finally {
                scope.cancel()
            }
        }
    }

    private fun connectAuthenticatedRouting() {
        val supabase = client ?: return
        scope.launch {
            try {
                realtime?.connect()
                connectionReady.complete(Unit)
            } catch (_: Exception) {
                listener.onAuthenticatedCallError()
                return@launch
            }
            supabase.auth.sessionStatus.collectLatest { status ->
                val nextUserId = when (status) {
                    is SessionStatus.Authenticated -> authenticatedCallUserId(status.session.user?.id)
                    else -> null
                }
                val previousUserId = localUserId
                if (nextUserId == null) {
                    if (previousUserId != null) clearAuthenticatedChannels()
                    localUserId = null
                    authenticatedUserState.value = null
                    listener.onAuthenticatedCallSessionLost()
                } else if (previousUserId != nextUserId) {
                    clearAuthenticatedChannels()
                    localUserId = nextUserId
                    authenticatedUserState.value = nextUserId
                    subscribeToUserInbox(supabase, nextUserId)
                }
            }
        }
    }

    private fun subscribeToUserInbox(supabase: SupabaseClient, userId: UUID) {
        val inbox = supabase.realtime.channel("pupsikcall-inbox:$userId") { isPrivate = true }
        inboxChannel = inbox
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            inbox.postgresChangeFlow<PostgresAction>(schema = "public") { table = "call_sessions" }
                .collect { action ->
                    val row = when (action) {
                        is PostgresAction.Insert -> action.record
                        is PostgresAction.Update -> action.record
                        is PostgresAction.Delete -> null
                        is PostgresAction.Select -> action.record
                    } ?: return@collect
                    parseAuthenticatedCallSession(row)?.let { dispatchCallSession(it, userId) }
                }
        }
        scope.launch {
            try {
                connectionReady.await()
                inbox.subscribe(blockUntilSubscribed = true)
                loadPendingInvitations().forEach { dispatchCallSession(it, userId) }
            } catch (_: Exception) {
                if (localUserId == userId) listener.onAuthenticatedCallError()
            }
        }
    }

    private fun dispatchCallSession(session: AuthenticatedCallSession, userId: UUID) {
        if (localUserId != userId || session.remoteUserId(userId) == null) return
        callChannels.compute(session.callId) { _, existing ->
            existing?.copy(session = session)
        }
        listener.onAuthenticatedCallSessionChanged(session)
    }

    private fun startMediaCollectors(callChannel: CallChannel) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            callChannel.channel.status.collect { status ->
                if (status == RealtimeChannel.Status.SUBSCRIBED) callChannel.subscribed.complete(Unit)
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            callChannel.channel.broadcastFlow<JsonObject>(MEDIA_EVENT).collect { message ->
                val signal = parseAuthenticatedCallMediaSignal(message) ?: return@collect
                val currentUser = localUserId ?: return@collect
                val currentSession = callChannels[signal.callId]?.session ?: return@collect
                if (!acceptsCallMediaSignal(currentSession, currentUser, currentSession.callId, signal)) return@collect
                when (signal.event) {
                    CallMediaEvent.OFFER -> listener.onRemoteOffer(currentSession, signal.sdp)
                    CallMediaEvent.ANSWER -> listener.onRemoteAnswer(currentSession, signal.sdp)
                    CallMediaEvent.ICE -> listener.onRemoteIceCandidate(
                        currentSession,
                        LocalIceCandidate(signal.sdpMid, signal.sdpMLineIndex, signal.sdp),
                    )
                }
            }
        }
        scope.launch {
            callChannel.outgoing.asFlow().collect { outgoing -> sendMedia(callChannel, outgoing) }
        }
    }

    private fun enqueueMedia(session: AuthenticatedCallSession, event: CallMediaEvent, extra: Map<String, String>) {
        val userId = localUserId ?: return listener.onAuthenticatedCallSessionLost()
        if (session.remoteUserId(userId) == null || session.status.isTerminal) return
        val callChannel = callChannels[session.callId] ?: return listener.onAuthenticatedCallError()
        callChannel.outgoing.enqueue(OutgoingMedia(session, event, extra))
    }

    private suspend fun sendMedia(callChannel: CallChannel, outgoing: OutgoingMedia) {
        val userId = localUserId ?: return
        if (outgoing.session.callId != callChannel.session.callId || outgoing.session.remoteUserId(userId) == null) return
        try {
            callChannel.subscribed.await()
            callChannel.sendMutex.withLock {
                callChannel.channel.broadcast(MEDIA_EVENT, mediaPayload(outgoing.session, userId, outgoing.event, outgoing.extra))
            }
        } catch (_: Exception) {
            if (localUserId == userId) listener.onAuthenticatedCallError()
        }
    }

    private suspend fun lifecycleRpc(function: String, callId: UUID) {
        val userId = requireAuthenticatedUser()
        val session = callChannels[callId]?.session
        if (session != null) requireParticipant(session, userId)
        val rows = requireClient().postgrest.rpc(
            function,
            buildJsonObject { put("p_call_id", JsonPrimitive(callId.toString())) },
        ).decodeList<JsonObject>()
        ensureSameUser(userId)
        rows.firstOrNull()?.let(::parseAuthenticatedCallSession)?.let { updated ->
            callChannels.compute(updated.callId) { _, existing -> existing?.copy(session = updated) }
            listener.onAuthenticatedCallSessionChanged(updated)
        }
    }

    private fun mediaPayload(
        session: AuthenticatedCallSession,
        senderUserId: UUID,
        event: CallMediaEvent,
        extra: Map<String, String>,
    ): JsonObject = buildJsonObject {
        put("callId", JsonPrimitive(session.callId.toString()))
        put("callerUserId", JsonPrimitive(session.callerUserId.toString()))
        put("calleeUserId", JsonPrimitive(session.calleeUserId.toString()))
        put("senderUserId", JsonPrimitive(senderUserId.toString()))
        put("type", JsonPrimitive(event.wireValue))
        extra.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
    }

    private suspend fun requireAuthenticatedUser(): UUID =
        localUserId ?: throw IllegalStateException("Authenticated session unavailable")

    private suspend fun ensureSameUser(expectedUserId: UUID) {
        if (localUserId != expectedUserId) throw CancellationException("Authenticated session changed")
    }

    private fun requireParticipant(session: AuthenticatedCallSession, userId: UUID) {
        if (session.remoteUserId(userId) == null) throw SecurityException("Call participant mismatch")
    }

    private suspend fun clearAuthenticatedChannels() {
        inboxChannel?.let { realtime?.removeChannel(it) }
        inboxChannel = null
        callChannels.values.forEach {
            it.outgoing.close()
            realtime?.removeChannel(it.channel)
        }
        callChannels.clear()
        listener.onAuthenticatedCallSessionLost()
    }

    private fun requireClient(): SupabaseClient =
        client ?: throw IllegalStateException("Authenticated call routing is unavailable")

    private fun createSupabaseClientIfConfigured(url: String, key: String): SupabaseClient? =
        if (!isValidSupabaseClientConfiguration(url, key)) {
            null
        } else {
            createSupabaseClient(url, key) {
                httpEngine = createSupabaseRealtimeHttpEngine()
                defaultLogLevel = LogLevel.NONE
                install(io.github.jan.supabase.auth.Auth) {
                    autoLoadFromStorage = true
                    autoSaveToStorage = true
                }
                install(Realtime)
                install(Postgrest)
            }
        }

    private companion object {
        const val MEDIA_EVENT = "call-media"
    }
}