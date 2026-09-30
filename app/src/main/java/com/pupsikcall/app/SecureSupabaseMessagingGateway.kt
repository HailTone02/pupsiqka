package com.pupsikcall.app

import android.content.Context
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.matrix.rustcomponents.sdk.crypto.Device
import org.matrix.rustcomponents.sdk.crypto.DeviceLists
import org.matrix.rustcomponents.sdk.crypto.OlmMachine
import org.matrix.rustcomponents.sdk.crypto.Request
import org.matrix.rustcomponents.sdk.crypto.RequestType
import uniffi.matrix_sdk_crypto.CollectStrategy
import uniffi.matrix_sdk_crypto.DecryptionSettings
import uniffi.matrix_sdk_crypto.TrustRequirement
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class SecureSupabaseMessagingGateway(
    private val client: SupabaseClient?,
    context: Context,
) : MessagingGateway, AutoCloseable {
    private val appContext = context.applicationContext
    private val states = ConcurrentHashMap<UUID, LocalUserState>()
    private val publicKeyMutex = Mutex()

    override suspend fun currentUserId(): UUID? = runCatching {
        client?.auth?.currentUserOrNull()?.id?.let(UUID::fromString)
    }.getOrNull()

    override suspend fun listConversations(
        userId: UUID,
        before: ConversationCursor?,
        limit: Int,
    ): List<MessageConversation> {
        requireCurrentUser(userId)
        val rows = requireClient().postgrest.rpc(
            "list_direct_conversations",
            buildJsonObject {
                put("p_before_created_at", before?.createdAt?.let(::JsonPrimitive) ?: JsonNull)
                put("p_before_conversation_id", before?.conversationId?.toString()?.let(::JsonPrimitive) ?: JsonNull)
                put("p_limit", limit)
            },
        ).decodeList<JsonObject>()
        requireCurrentUser(userId)
        val ids = rows.mapNotNull { it.string("other_user_id")?.uuidOrNull() }.distinct()
        val profiles = loadPublicProfiles(ids).associateBy(UserProfile::userId)
        return rows.mapNotNull { row ->
            val conversationId = row.string("conversation_id")?.uuidOrNull() ?: return@mapNotNull null
            val otherUserId = row.string("other_user_id")?.uuidOrNull() ?: return@mapNotNull null
            val profile = profiles[otherUserId]
            MessageConversation(
                id = conversationId,
                otherUserId = otherUserId,
                displayName = profile?.displayName,
                avatarPath = profile?.avatarPath,
                createdAt = row.requiredString("created_at"),
            )
        }
    }

    override suspend fun loadMessages(
        userId: UUID,
        conversationId: UUID,
        before: MessageCursor?,
        limit: Int,
    ): List<DirectMessage> {
        requireCurrentUser(userId)
        val all = stateFor(userId).history.load(conversationId)
        val older = if (before == null) all else all.filter {
            compareValuesBy(it, DirectMessage(before.messageId, conversationId, userId, before.messageId, "", before.createdAt),
                DirectMessage::createdAt, { it.id.toString() }) < 0
        }
        return older.takeLast(limit)
    }

    override suspend fun createOrGetDirectConversation(userId: UUID, otherUserId: UUID): UUID {
        requireCurrentUser(userId)
        require(otherUserId != userId)
        val row = requireClient().postgrest.rpc(
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
        requireCurrentUser(userId)
        val local = stateFor(userId)
        val existing = local.history.load(conversationId).firstOrNull { it.clientMessageId == clientMessageId }
        if (existing != null && existing.body != body) throw IllegalArgumentException("Message retry content changed")
        var message = existing ?: DirectMessage(
            id = clientMessageId,
            conversationId = conversationId,
            senderId = userId,
            clientMessageId = clientMessageId,
            body = body,
            createdAt = java.time.Instant.now().toString(),
            deliveryState = MessageDeliveryState.QUEUED,
        ).also { local.history.upsert(listOf(it)) }

        local.mutex.withLock {
            requireCurrentUser(userId)
            val recipients = listOf(userId, otherParticipant(conversationId, userId)).distinct()
            val devices = prepareOlmDevices(local, recipients)
            val devicesToSend = devices.flatMap { (recipientUserId, recipientDevices) ->
                recipientDevices.filter { it.deviceId != local.olm.deviceId }.map { recipientUserId to it }
            }
            if (devicesToSend.isEmpty()) throw IllegalStateException("Recipient has no registered messaging device")
            devicesToSend.forEach { (recipientUserId, device) ->
                val fingerprint = local.olm.fingerprintFor(device)
                if (local.vault.getVerifiedFingerprint(recipientUserId, device.deviceId) != fingerprint) {
                    throw IdentityVerificationRequired(recipientUserId, device.deviceId, fingerprint)
                }
            }

            var pendingRequestIds = message.pendingOlmRequestIds
            if (pendingRequestIds.isEmpty() && message.deliveryState == MessageDeliveryState.QUEUED) {
                val content = buildJsonObject {
                    put("kind", "message")
                    put("conversation_id", conversationId.toString())
                    put("message_id", message.id.toString())
                    put("client_message_id", clientMessageId.toString())
                    put("body", body)
                }
                val requests = devicesToSend.map { (recipientUserId, device) ->
                    local.olm.encryptToDevice(
                        MatrixOlmClient.matrixUserId(recipientUserId),
                        device.deviceId,
                        MESSAGE_EVENT_TYPE,
                        content,
                    )
                }
                pendingRequestIds = requests.map(Request.ToDevice::requestId)
                message = message.copy(pendingOlmRequestIds = pendingRequestIds)
                local.history.upsert(listOf(message))
            }

            val outstanding = local.olm.outgoingRequests().filterIsInstance<Request.ToDevice>()
                .associateBy(Request.ToDevice::requestId)
            for (requestId in pendingRequestIds.toList()) {
                val request = outstanding[requestId] ?: throw IllegalStateException("Pending encrypted request is unavailable")
                val bodyJson = Json.parseToJsonElement(request.body).jsonObject
                val messages = bodyJson["messages"] ?: throw IllegalStateException("Encrypted request has no device messages")
                val response = rpc(
                    "send_hailtone_olm_envelopes",
                    buildJsonObject {
                        put("p_conversation_id", conversationId.toString())
                        put("p_client_message_id", clientMessageId.toString())
                        put("p_sender_device_id", local.olm.deviceId)
                        put("p_event_type", request.eventType)
                        put("p_messages", messages)
                    },
                )
                local.olm.markRequestAsSent(request.requestId, RequestType.TO_DEVICE, response.toString())
                message = message.copy(pendingOlmRequestIds = message.pendingOlmRequestIds - requestId)
                local.history.upsert(listOf(message))
                requireCurrentUser(userId)
            }
            message = message.copy(deliveryState = MessageDeliveryState.SENT)
            local.history.upsert(listOf(message))
        }
        return message
    }

    override fun observeIncomingMessages(userId: UUID, conversationId: UUID): Flow<DirectMessage> = flow {
        val local = stateFor(userId)
        while (currentUserId() == userId) {
            local.history.load().filter { it.deliveryState == MessageDeliveryState.QUEUED }.forEach { queued ->
                runCatching { sendMessage(userId, queued.conversationId, queued.clientMessageId, queued.body) }
            }
            val newMessages = local.mutex.withLock {
                requireCurrentUser(userId)
                fetchAndProcessEnvelopes(local)
                    .filter { it.conversationId == conversationId }
            }
            newMessages.forEach { emit(it) }
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    override suspend fun pendingPeerFingerprint(userId: UUID): PeerDeviceFingerprint? {
        val currentUserId = requireCurrentUser()
        val local = stateFor(currentUserId)
        return local.mutex.withLock {
            val devices = prepareOlmDevices(local, listOf(userId, currentUserId))
            devices.flatMap { (owner, userDevices) ->
                userDevices.filter { it.deviceId != local.olm.deviceId }.map { device ->
                    PeerDeviceFingerprint(owner, device.deviceId, local.olm.fingerprintFor(device))
                }
            }.firstOrNull { local.vault.getVerifiedFingerprint(it.userId, it.deviceId) != it.value }
        }
    }

    override suspend fun verifyPeerFingerprint(userId: UUID, deviceId: String, fingerprint: String): Boolean {
        val currentUserId = requireCurrentUser()
        val local = stateFor(currentUserId)
        return local.mutex.withLock {
            val devices = prepareOlmDevices(local, listOf(userId, currentUserId))[userId].orEmpty()
            val device = devices.firstOrNull { it.deviceId == deviceId } ?: return@withLock false
            if (local.olm.fingerprintFor(device) != fingerprint) return@withLock false
            local.vault.verifyFingerprint(userId, deviceId, fingerprint)
            true
        }
    }

    override suspend fun markConversationRead(userId: UUID, conversationId: UUID) {
        requireCurrentUser(userId)
        val local = stateFor(userId)
        val unread = local.history.load(conversationId).filter { it.senderId != userId && !it.isRead }
        if (unread.isEmpty()) return
        local.history.upsert(unread.map { it.copy(isRead = true) })
        sendControlMessage(local, conversationId, "read_receipt", unread.map { it.id.toString() })
    }

    private suspend fun fetchAndProcessEnvelopes(local: LocalUserState): List<DirectMessage> {
        val rows = requireClient().postgrest.rpc(
            "fetch_hailtone_olm_envelopes",
            buildJsonObject { put("p_device_id", local.olm.deviceId) },
        ).decodeList<JsonObject>()
        val received = mutableListOf<DirectMessage>()
        for (row in rows) {
            val envelopeId = row.requiredUuid("id")
            val conversationId = row.requiredUuid("conversation_id")
            val senderUserId = row.requiredUuid("sender_user_id")
            val senderDeviceId = row.requiredString("sender_device_id")
            val ciphertext = row["ciphertext"]?.jsonObject ?: throw IllegalStateException("Encrypted envelope is invalid")
            val events = local.olm.decryptToDevice(
                MatrixOlmClient.matrixUserId(senderUserId),
                row.requiredString("event_type"),
                ciphertext,
                envelopeId,
            )
            val newMessages = mutableListOf<DirectMessage>()
            for (event in events) {
                when (event.string("type")) {
                    MESSAGE_EVENT_TYPE -> {
                        val content = event["content"]?.jsonObject ?: continue
                        if (content.string("kind") != "message" || content.string("conversation_id") != conversationId.toString()) continue
                        val messageId = content.string("message_id")?.uuidOrNull() ?: continue
                        val clientMessageId = content.string("client_message_id")?.uuidOrNull() ?: continue
                        val eventSender = event.string("sender")?.let(::matrixUserUuid) ?: senderUserId
                        if (eventSender != senderUserId) continue
                        newMessages += DirectMessage(
                            id = messageId,
                            conversationId = conversationId,
                            senderId = senderUserId,
                            clientMessageId = clientMessageId,
                            body = content.string("body") ?: continue,
                            createdAt = row.requiredString("created_at"),
                            deliveryState = MessageDeliveryState.DELIVERED,
                        )
                    }
                    CONTROL_EVENT_TYPE -> applyControl(local, senderUserId, event["content"]?.jsonObject ?: continue)
                }
            }
            local.history.upsert(newMessages)
            requireCurrentUser(local.userId)
            val ack: Boolean = requireClient().postgrest.rpc("ack_hailtone_olm_envelope", buildJsonObject {
                put("p_envelope_id", envelopeId.toString())
                put("p_device_id", local.olm.deviceId)
            }).decodeSingle()
            if (!ack) throw IllegalStateException("Encrypted envelope acknowledgement failed")
            received += newMessages
            if (newMessages.isNotEmpty()) {
                runCatching { sendControlMessage(local, conversationId, "delivery_receipt", newMessages.map { it.id.toString() }) }
            }
        }
        return received
    }

    private suspend fun applyControl(local: LocalUserState, senderUserId: UUID, content: JsonObject) {
        val kind = content.string("kind") ?: return
        val conversationId = content.string("conversation_id")?.uuidOrNull() ?: return
        if (findConversationPeer(local.userId, conversationId) != senderUserId) return
        val messageIds = content["message_ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.uuidOrNull() }.orEmpty()
        if (messageIds.isEmpty()) return
        val old = local.history.load().associateBy(DirectMessage::id)
        val updated = messageIds.mapNotNull { id ->
            val message = old[id] ?: return@mapNotNull null
            if (message.senderId != local.userId || message.conversationId != conversationId) return@mapNotNull null
            when (kind) {
                "delivery_receipt" -> message.copy(deliveryState = if (message.isRead) MessageDeliveryState.READ else MessageDeliveryState.DELIVERED)
                "read_receipt" -> message.copy(deliveryState = MessageDeliveryState.READ, isRead = true)
                else -> null
            }
        }
        local.history.upsert(updated)
    }

    private suspend fun sendControlMessage(
        local: LocalUserState,
        conversationId: UUID,
        kind: String,
        messageIds: List<String>,
    ) {
        val conversation = findConversationPeer(local.userId, conversationId)
        val devices = prepareOlmDevices(local, listOf(conversation, local.userId))
        val targets = devices.flatMap { (owner, userDevices) ->
            userDevices.filter { it.deviceId != local.olm.deviceId }.map { owner to it }
        }
        if (targets.isEmpty()) return
        targets.forEach { (owner, device) ->
            val fingerprint = local.olm.fingerprintFor(device)
            if (local.vault.getVerifiedFingerprint(owner, device.deviceId) != fingerprint) return@forEach
        }
        val content = buildJsonObject {
            put("kind", kind)
            put("conversation_id", conversationId.toString())
            put("message_ids", JsonArray(messageIds.map(::JsonPrimitive)))
        }
        val requestIds = targets.mapNotNull { (owner, device) ->
            if (local.vault.getVerifiedFingerprint(owner, device.deviceId) != local.olm.fingerprintFor(device)) return@mapNotNull null
            local.olm.encryptToDevice(MatrixOlmClient.matrixUserId(owner), device.deviceId, CONTROL_EVENT_TYPE, content).requestId
        }
        val outstanding = local.olm.outgoingRequests().filterIsInstance<Request.ToDevice>().associateBy(Request.ToDevice::requestId)
        for (requestId in requestIds) {
            val request = outstanding[requestId] ?: continue
            val messages = Json.parseToJsonElement(request.body).jsonObject["messages"] ?: continue
            val response = rpc("send_hailtone_olm_envelopes", buildJsonObject {
                put("p_conversation_id", conversationId.toString())
                put("p_client_message_id", UUID.randomUUID().toString())
                put("p_sender_device_id", local.olm.deviceId)
                put("p_event_type", request.eventType)
                put("p_messages", messages)
            })
            local.olm.markRequestAsSent(request.requestId, RequestType.TO_DEVICE, response.toString())
        }
    }

    private suspend fun prepareOlmDevices(local: LocalUserState, users: List<UUID>): Map<UUID, List<Device>> = publicKeyMutex.withLock {
        val matrixIds = users.distinct().map(MatrixOlmClient::matrixUserId)
        local.olm.updateTrackedUsers(matrixIds)
        for (request in local.olm.outgoingRequests()) {
            when (request) {
                is Request.KeysUpload -> {
                    val body = Json.parseToJsonElement(request.body).jsonObject
                    val response = rpc("publish_hailtone_olm_keys", buildJsonObject {
                        put("p_device_keys", body["device_keys"] ?: JsonNull)
                        put("p_one_time_keys", body["one_time_keys"] ?: buildJsonObject {})
                    })
                    local.olm.markRequestAsSent(request.requestId, RequestType.KEYS_UPLOAD, response.toString())
                }
                is Request.KeysQuery -> {
                    val response = rpc("query_hailtone_olm_keys", buildJsonObject {
                        put("p_matrix_user_ids", JsonArray(request.users.map(::JsonPrimitive)))
                    })
                    local.olm.markRequestAsSent(request.requestId, RequestType.KEYS_QUERY, response.toString())
                }
                else -> Unit
            }
        }
        val missingSessions = local.olm.getMissingSessions(matrixIds) as? Request.KeysClaim
        if (missingSessions != null) {
            val claimBody = buildJsonObject {
                put("one_time_keys", buildJsonObject {
                    missingSessions.oneTimeKeys.forEach { (matrixUserId, devices) ->
                        put(matrixUserId, buildJsonObject { devices.forEach { (deviceId, algorithm) -> put(deviceId, algorithm) } })
                    }
                })
            }
            val response = rpc("claim_hailtone_olm_keys", buildJsonObject { put("p_one_time_key_request", claimBody) })
            local.olm.markRequestAsSent(missingSessions.requestId, RequestType.KEYS_CLAIM, response.toString())
        }
        users.distinct().associateWith { uuid ->
            local.olm.devicesFor(MatrixOlmClient.matrixUserId(uuid))
        }
    }

    private suspend fun findConversationPeer(userId: UUID, conversationId: UUID): UUID {
        val row = requireClient().postgrest.rpc(
            "list_direct_conversations",
            buildJsonObject { put("p_limit", 100) },
        ).decodeList<JsonObject>().firstOrNull { it.string("conversation_id") == conversationId.toString() }
            ?: throw IllegalStateException("Conversation unavailable")
        requireCurrentUser(userId)
        return row.requiredUuid("other_user_id")
    }

    private suspend fun otherParticipant(conversationId: UUID, userId: UUID): UUID =
        findConversationPeer(userId, conversationId)

    private suspend fun loadPublicProfiles(userIds: List<UUID>): List<UserProfile> {
        if (userIds.isEmpty()) return emptyList()
        return requireClient().postgrest.rpc(
            "get_public_profiles",
            buildJsonObject { put("p_user_ids", JsonArray(userIds.map { JsonPrimitive(it.toString()) })) },
        ).decodeList<JsonObject>().mapNotNull { row ->
            val id = row.string("user_id")?.uuidOrNull() ?: return@mapNotNull null
            UserProfile(id, row.string("display_name"), row.string("avatar_path"))
        }
    }

    private fun stateFor(userId: UUID): LocalUserState = states.getOrPut(userId) {
        val vault = AndroidMessageKeyVault(appContext, userId)
        val olm = MatrixOlmClient(appContext.noBackupFilesDir, userId, vault)
        LocalUserState(userId, vault, olm, AndroidEncryptedMessageStore(appContext, userId, vault))
    }

    private suspend fun requireCurrentUser(expected: UUID) {
        if (currentUserId() != expected) throw IllegalStateException("Authenticated session unavailable")
    }

    private suspend fun requireCurrentUser(): UUID = currentUserId()
        ?: throw IllegalStateException("Authenticated session unavailable")

    private suspend fun rpc(name: String, parameters: JsonObject): JsonObject {
        val response = requireClient().postgrest.rpc(name, parameters).decodeSingle<JsonObject>()
        return response
    }

    private fun requireClient() = client ?: throw IllegalStateException("Messaging is unavailable")

    override fun close() {
        states.values.forEach { it.olm.close() }
        states.clear()
    }

    private data class LocalUserState(
        val userId: UUID,
        val vault: AndroidMessageKeyVault,
        val olm: MatrixOlmClient,
        val history: AndroidEncryptedMessageStore,
        val mutex: Mutex = Mutex(),
    )

    private companion object {
        const val PublicProfileLookupLimit = 50
        const val MESSAGE_EVENT_TYPE = "com.hailtone.message"
        const val CONTROL_EVENT_TYPE = "com.hailtone.control"
        const val POLL_INTERVAL_MILLIS = 3_000L
    }
}

private fun JsonObject.toConversationRow(): MessageConversation? {
    val id = string("conversation_id")?.uuidOrNull() ?: return null
    val other = string("other_user_id")?.uuidOrNull() ?: return null
    return MessageConversation(id, other, null, null, requiredString("created_at"))
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
    string(key)?.uuidOrNull() ?: throw IllegalStateException("Invalid secure messaging response")

private fun JsonObject.requiredString(key: String): String =
    string(key) ?: throw IllegalStateException("Invalid secure messaging response")

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun String.uuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

private fun matrixUserUuid(matrixUserId: String): UUID? =
    matrixUserId.takeIf { it.startsWith('@') && it.endsWith(":hailtone.invalid") }
        ?.substring(1, matrixUserId.length - ":hailtone.invalid".length)
        ?.uuidOrNull()
