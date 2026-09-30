package com.pupsikcall.app

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal enum class CallHistoryDirection {
    Incoming,
    Outgoing,
}

internal data class CallHistoryCursor(val createdAt: String, val callId: UUID)

internal data class CallHistoryRecord(
    val callId: UUID,
    val counterpartUserId: UUID,
    val direction: CallHistoryDirection,
    val status: AuthenticatedCallStatus,
    val createdAt: String,
    val acceptedAt: String?,
    val connectedAt: String?,
    val endedAt: String?,
    val durationSeconds: Long?,
    val counterpartDisplayName: String? = null,
    val counterpartAvatarPath: String? = null,
)

internal sealed interface CallHistoryState {
    data object Loading : CallHistoryState
    data object Empty : CallHistoryState
    data object SignedOut : CallHistoryState
    data object Error : CallHistoryState
    data class Loaded(
        val calls: List<CallHistoryRecord>,
        val nextCursor: CallHistoryCursor?,
        val loadingMore: Boolean = false,
    ) : CallHistoryState
}

internal data class CallHistoryRow(
    val callId: UUID,
    val counterpartUserId: UUID,
    val direction: String,
    val status: String,
    val createdAt: String,
    val acceptedAt: String?,
    val connectedAt: String?,
    val endedAt: String?,
    val durationSeconds: Long?,
    val counterpartDisplayName: String? = null,
    val counterpartAvatarPath: String? = null,
)

internal interface CallHistoryGateway {
    suspend fun currentUserId(): UUID?
    fun observeUserId(): Flow<UUID?>
    suspend fun listCallHistory(
        userId: UUID,
        before: CallHistoryCursor?,
        limit: Int,
    ): List<CallHistoryRow>
}

internal class CallHistoryRepository(
    private val gateway: CallHistoryGateway,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    constructor(client: SupabaseClient?) : this(SupabaseCallHistoryGateway(client))

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow<CallHistoryState>(CallHistoryState.Loading)
    val state = mutableState.asStateFlow()
    private val mutableAuthenticatedUserId = MutableStateFlow<UUID?>(null)
    val authenticatedUserId = mutableAuthenticatedUserId.asStateFlow()
    private val requestGeneration = AtomicLong()
    private val identityLock = Any()
    @Volatile private var activeUserId: UUID? = null
    private var identityInitialized = false

    init {
        scope.launch {
            gateway.observeUserId().distinctUntilChanged().collect(::updateAuthenticatedUser)
        }
    }

    suspend fun loadHistory(before: CallHistoryCursor? = null) {
        val userId = try {
            gateway.currentUserId()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = CallHistoryState.Error
            return
        }
        if (userId == null) {
            updateAuthenticatedUser(null)
            return
        }
        updateAuthenticatedUser(userId)
        val generation = requestGeneration.get()
        if (!isRequestActive(userId, generation)) return
        if (before != null && (mutableState.value as? CallHistoryState.Loaded)?.nextCursor != before) return

        val previous = mutableState.value as? CallHistoryState.Loaded
        mutableState.value = if (before == null) {
            CallHistoryState.Loading
        } else {
            previous?.copy(loadingMore = true) ?: CallHistoryState.Loading
        }

        try {
            val page = gateway.listCallHistory(userId, before, CallHistoryPageSize)
                .map(::toCallHistoryRecord)
            if (!isCurrentRequest(userId, generation)) return
            val existing = if (before == null) emptyList() else previous?.calls.orEmpty()
            val calls = mergeCallHistory(existing, page)
            val pageCursor = page.lastOrNull()?.let { CallHistoryCursor(it.createdAt, it.callId) }
                .takeIf { page.size == CallHistoryPageSize }
            publishIfCurrent(userId, generation, if (calls.isEmpty()) {
                CallHistoryState.Empty
            } else {
                CallHistoryState.Loaded(calls, pageCursor)
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (isCurrentRequest(userId, generation)) {
                publishIfCurrent(userId, generation, CallHistoryState.Error)
            }
        }
    }

    override fun close() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private fun updateAuthenticatedUser(userId: UUID?) {
        synchronized(identityLock) {
            if (identityInitialized && activeUserId == userId) return
            identityInitialized = true
            activeUserId = userId
            requestGeneration.incrementAndGet()
            mutableState.value = if (userId == null) CallHistoryState.SignedOut else CallHistoryState.Loading
            mutableAuthenticatedUserId.value = userId
        }
    }

    private fun isRequestActive(userId: UUID, generation: Long): Boolean = synchronized(identityLock) {
        identityInitialized && activeUserId == userId && requestGeneration.get() == generation
    }

    private suspend fun isCurrentRequest(userId: UUID, generation: Long): Boolean {
        if (!isRequestActive(userId, generation)) return false
        val currentUserId = runCatching { gateway.currentUserId() }.getOrNull()
        if (currentUserId != userId) {
            updateAuthenticatedUser(currentUserId)
            return false
        }
        return isRequestActive(userId, generation)
    }

    private fun publishIfCurrent(userId: UUID, generation: Long, nextState: CallHistoryState) {
        synchronized(identityLock) {
            if (identityInitialized && activeUserId == userId && requestGeneration.get() == generation) {
                mutableState.value = nextState
            }
        }
    }

    private fun toCallHistoryRecord(row: CallHistoryRow): CallHistoryRecord {
        val direction = when (row.direction) {
            "incoming" -> CallHistoryDirection.Incoming
            "outgoing" -> CallHistoryDirection.Outgoing
            else -> throw IllegalStateException("Invalid call history response")
        }
        val status = AuthenticatedCallStatus.parse(row.status)
            ?.takeIf(AuthenticatedCallStatus::isTerminal)
            ?: throw IllegalStateException("Invalid call history response")
        if (row.createdAt.isBlank()) throw IllegalStateException("Invalid call history response")
        val durationSeconds = row.durationSeconds?.takeIf {
            row.connectedAt != null && row.endedAt != null && it >= 0
        }
        return CallHistoryRecord(
            callId = row.callId,
            counterpartUserId = row.counterpartUserId,
            direction = direction,
            status = status,
            createdAt = row.createdAt,
            acceptedAt = row.acceptedAt,
            connectedAt = row.connectedAt,
            endedAt = row.endedAt,
            durationSeconds = durationSeconds,
            counterpartDisplayName = row.counterpartDisplayName,
            counterpartAvatarPath = row.counterpartAvatarPath,
        )
    }

    private fun mergeCallHistory(
        existing: List<CallHistoryRecord>,
        incoming: List<CallHistoryRecord>,
    ): List<CallHistoryRecord> = (existing + incoming).distinctBy(CallHistoryRecord::callId)

    private companion object {
        const val CallHistoryPageSize = 50
    }
}

private class SupabaseCallHistoryGateway(
    private val client: SupabaseClient?,
) : CallHistoryGateway {
    override suspend fun currentUserId(): UUID? = runCatching {
        client?.auth?.currentUserOrNull()?.id?.let(UUID::fromString)
    }.getOrNull()

    override fun observeUserId(): Flow<UUID?> = client?.auth?.sessionStatus
        ?.filter { it != SessionStatus.Initializing }
        ?.map { status ->
            (status as? SessionStatus.Authenticated)
                ?.session?.user?.id?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        }
        ?: flowOf(null)

    override suspend fun listCallHistory(
        userId: UUID,
        before: CallHistoryCursor?,
        limit: Int,
    ): List<CallHistoryRow> {
        val supabase = requireClient()
        requireCurrentUser(userId)
        val rows = supabase.postgrest.rpc(
            "list_call_history",
            buildJsonObject {
                put("p_before_created_at", before?.createdAt?.let(::JsonPrimitive) ?: JsonNull)
                put("p_before_call_id", before?.callId?.toString()?.let(::JsonPrimitive) ?: JsonNull)
                put("p_limit", JsonPrimitive(limit))
            },
        ).decodeList<JsonObject>()
        val historyRows = rows.map(JsonObject::toCallHistoryRow)
        val profiles = try {
            loadPublicProfiles(supabase, historyRows.map(CallHistoryRow::counterpartUserId).distinct())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }
        requireCurrentUser(userId)
        return historyRows.map { row ->
            val profile = profiles[row.counterpartUserId]
            row.copy(
                counterpartDisplayName = profile?.displayName,
                counterpartAvatarPath = profile?.avatarPath,
            )
        }
    }

    private suspend fun loadPublicProfiles(supabase: SupabaseClient, userIds: List<UUID>): Map<UUID, PublicCallProfile> {
        if (userIds.isEmpty()) return emptyMap()
        return userIds.chunked(PublicProfileLookupLimit).flatMap { chunk ->
            val profiles = supabase.postgrest.rpc(
                "get_public_profiles",
                buildJsonObject { put("p_user_ids", JsonArray(chunk.map { JsonPrimitive(it.toString()) })) },
            ).decodeList<JsonObject>()
            chunk.zip(profiles).mapNotNull { (userId, row) ->
                val username = row.stringOrNull("username")
                val avatarPath = row.stringOrNull("avatar_path")
                if (username == null && avatarPath == null) return@mapNotNull null
                userId to PublicCallProfile(username?.let { "@$it" }, avatarPath)
            }
        }.toMap()
    }

    private suspend fun requireCurrentUser(expectedUserId: UUID) {
        if (currentUserId() != expectedUserId) throw IllegalStateException("Authenticated session unavailable")
    }

    private fun requireClient(): SupabaseClient = client ?: throw IllegalStateException("Call history is unavailable")

    private data class PublicCallProfile(val displayName: String?, val avatarPath: String?)

    private companion object {
        const val PublicProfileLookupLimit = 50
    }
}

private fun JsonObject.toCallHistoryRow(): CallHistoryRow = CallHistoryRow(
    callId = requiredUuid("call_id"),
    counterpartUserId = requiredUuid("counterpart_user_id"),
    direction = requiredString("direction"),
    status = requiredString("status"),
    createdAt = requiredString("created_at"),
    acceptedAt = stringOrNull("accepted_at"),
    connectedAt = stringOrNull("connected_at"),
    endedAt = stringOrNull("ended_at"),
    durationSeconds = (this["duration_seconds"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
)

private fun JsonObject.requiredUuid(key: String): UUID =
    uuidOrNull(key) ?: throw IllegalStateException("Invalid call history response")

private fun JsonObject.uuidOrNull(key: String): UUID? =
    stringOrNull(key)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

private fun JsonObject.requiredString(key: String): String =
    stringOrNull(key) ?: throw IllegalStateException("Invalid call history response")

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull
