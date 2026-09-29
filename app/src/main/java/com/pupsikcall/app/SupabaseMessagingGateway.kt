package com.pupsikcall.app

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.UUID

internal class SupabaseMessagingGateway(
    private val client: SupabaseClient?,
) : MessagingGateway {
    override suspend fun currentUserId(): UUID? = runCatching {
        client?.auth?.currentUserOrNull()?.id?.let(UUID::fromString)
    }.getOrNull()

    override suspend fun listConversations(
        userId: UUID,
        before: ConversationCursor?,
        limit: Int,
    ): List<MessageConversation> {
        val supabase = requireClient()
        requireCurrentUser(userId)
        val rows = supabase.postgrest.rpc(
            "list_direct_conversations",
            buildJsonObject {
                put("p_before_created_at", before?.createdAt?.let(::JsonPrimitive) ?: JsonNull)
                put("p_before_conversation_id", before?.conversationId?.toString()?.let(::JsonPrimitive) ?: JsonNull)
                put("p_limit", JsonPrimitive(limit))
            },
        ).decodeList<JsonObject>()

        val conversationRows = rows.map { row ->
            ConversationRow(
                id = row.requiredUuid("conversation_id"),
                otherUserId = row.requiredUuid("other_user_id"),
                createdAt = row.requiredString("created_at"),
            )
        }
        val profilesByUserId = conversationRows.map(ConversationRow::otherUserId)
            .distinct()
            .chunked(PublicProfileLookupLimit)
            .flatMap { userIds -> loadPublicProfiles(supabase, userIds) }
            .associateBy(UserProfile::userId)

        requireCurrentUser(userId)
        return conversationRows.map { row ->
            val profile = profilesByUserId[row.otherUserId]
            MessageConversation(
                id = row.id,
                otherUserId = row.otherUserId,
                displayName = profile?.displayName,
                avatarPath = profile?.avatarPath,
                createdAt = row.createdAt,
            )
        }
    }

    override suspend fun loadMessages(
        userId: UUID,
        conversationId: UUID,
        before: MessageCursor?,
        limit: Int,
    ): List<DirectMessage> {
        val supabase = requireClient()
        requireCurrentUser(userId)
        val rows = supabase.postgrest.rpc(
            "get_conversation_messages",
            buildJsonObject {
                put("p_conversation_id", conversationId.toString())
                put("p_before_created_at", before?.createdAt?.let(::JsonPrimitive) ?: JsonNull)
                put("p_before_id", before?.messageId?.toString()?.let(::JsonPrimitive) ?: JsonNull)
                put("p_limit", JsonPrimitive(limit))
            },
        ).decodeList<JsonObject>()
        requireCurrentUser(userId)
        return rows.map(JsonObject::toDirectMessage)
    }

    override suspend fun createOrGetDirectConversation(userId: UUID, otherUserId: UUID): UUID {
        val supabase = requireClient()
        requireCurrentUser(userId)
        val row = supabase.postgrest.rpc(
            "get_or_create_direct_conversation",
            buildJsonObject { put("p_other_user_id", otherUserId.toString()) },
        ).decodeSingle<JsonObject>()
        requireCurrentUser(userId)
        return row.requiredUuid("conversation_id")
    }

    override suspend fun sendMessage(
        userId: UUID,
        conversationId: UUID,
        clientMessageId: UUID,
        body: String,
    ): DirectMessage {
        val supabase = requireClient()
        requireCurrentUser(userId)
        val row = supabase.postgrest.rpc(
            "send_message",
            buildJsonObject {
                put("p_conversation_id", conversationId.toString())
                put("p_client_message_id", clientMessageId.toString())
                put("p_body", body)
            },
        ).decodeSingle<JsonObject>()
        requireCurrentUser(userId)
        return row.toDirectMessage()
    }

    override fun observeIncomingMessages(userId: UUID, conversationId: UUID): Flow<DirectMessage> = flow {
        val supabase = requireClient()
        requireCurrentUser(userId)
        coroutineScope {
            val channel = supabase.realtime.channel("pupsikcall-messages:$conversationId") {
                isPrivate = true
            }
            val events = Channel<DirectMessage>(Channel.BUFFERED)
            val changeFlow = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                table = "messages"
                filter("conversation_id", io.github.jan.supabase.postgrest.query.filter.FilterOperator.EQ, conversationId.toString())
            }
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                changeFlow.collect { change ->
                    val message = change.record.toDirectMessage()
                    if (message.conversationId == conversationId) events.send(message)
                }
            }
            try {
                channel.subscribe(blockUntilSubscribed = true)
                for (message in events) emit(message)
            } finally {
                collector.cancel()
                events.close()
                withContext(NonCancellable) { channel.unsubscribe() }
            }
        }
    }

    private suspend fun loadPublicProfiles(supabase: SupabaseClient, userIds: List<UUID>): List<UserProfile> {
        if (userIds.isEmpty()) return emptyList()
        val rows = supabase.postgrest.rpc(
            "get_public_profiles",
            buildJsonObject {
                put("p_user_ids", JsonArray(userIds.map { JsonPrimitive(it.toString()) }))
            },
        ).decodeList<JsonObject>()
        return rows.mapNotNull { row ->
            val userId = row.string("user_id")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@mapNotNull null
            UserProfile(userId, row.string("display_name"), row.string("avatar_path"))
        }
    }

    private suspend fun requireCurrentUser(expectedUserId: UUID) {
        if (currentUserId() != expectedUserId) throw IllegalStateException("Authenticated session unavailable")
    }

    private fun requireClient(): SupabaseClient = client ?: throw IllegalStateException("Messaging is unavailable")

    private data class ConversationRow(val id: UUID, val otherUserId: UUID, val createdAt: String)

    private companion object {
        const val PublicProfileLookupLimit = 50
    }
}

private fun JsonObject.toDirectMessage(): DirectMessage = DirectMessage(
    id = requiredUuid("id"),
    conversationId = requiredUuid("conversation_id"),
    senderId = requiredUuid("sender_id"),
    clientMessageId = requiredUuid("client_message_id"),
    body = requiredString("body"),
    createdAt = requiredString("created_at"),
)

private fun JsonObject.requiredUuid(key: String): UUID =
    string(key)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw IllegalStateException("Invalid messaging response")

private fun JsonObject.requiredString(key: String): String =
    string(key) ?: throw IllegalStateException("Invalid messaging response")

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull