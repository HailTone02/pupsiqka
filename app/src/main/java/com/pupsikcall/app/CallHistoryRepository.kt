package com.pupsikcall.app

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.UUID

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
            mutableState.value = CallHistoryState.SignedOut
            return
        }
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
            if (gateway.currentUserId() != userId) {
                mutableState.value = CallHistoryState.SignedOut
                return
            }
            val existing = if (before == null) emptyList() else previous?.calls.orEmpty()
            val calls = mergeCallHistory(existing, page)
            val pageCursor = page.lastOrNull()?.let { CallHistoryCursor(it.createdAt, it.callId) }
                .takeIf { page.size == CallHistoryPageSize }
            mutableState.value = if (calls.isEmpty()) {
                CallHistoryState.Empty
            } else {
                CallHistoryState.Loaded(calls, pageCursor)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = CallHistoryState.Error
        }
    }

    override fun close() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
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
            supabase.postgrest.rpc(
                "get_public_profiles",
                buildJsonObject { put("p_user_ids", JsonArray(chunk.map { JsonPrimitive(it.toString()) })) },
            ).decodeList<JsonObject>().mapNotNull { row ->
                val userId = row.uuidOrNull("user_id") ?: return@mapNotNull null
                userId to PublicCallProfile(row.stringOrNull("display_name"), row.stringOrNull("avatar_path"))
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
