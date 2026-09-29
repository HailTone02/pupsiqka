package com.pupsikcall.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

internal data class MessageConversation(
    val id: UUID,
    val otherUserId: UUID,
    val displayName: String?,
    val avatarPath: String?,
    val createdAt: String,
)

internal data class DirectMessage(
    val id: UUID,
    val conversationId: UUID,
    val senderId: UUID,
    val clientMessageId: UUID,
    val body: String,
    val createdAt: String,
)

internal data class ConversationCursor(val createdAt: String, val conversationId: UUID)
internal data class MessageCursor(val createdAt: String, val messageId: UUID)

internal sealed interface ConversationListState {
    data object Loading : ConversationListState
    data object Empty : ConversationListState
    data object SignedOut : ConversationListState
    data class Loaded(
        val conversations: List<MessageConversation>,
        val nextCursor: ConversationCursor?,
        val loadingMore: Boolean = false,
    ) : ConversationListState
    data object Error : ConversationListState
}

internal sealed interface MessageListState {
    data object Loading : MessageListState
    data object Empty : MessageListState
    data object SignedOut : MessageListState
    data class Loaded(
        val messages: List<DirectMessage>,
        val nextCursor: MessageCursor?,
        val loadingOlder: Boolean = false,
        val realtimeError: Boolean = false,
    ) : MessageListState
    data object Error : MessageListState
}

internal enum class MessageSendResult {
    SENT,
    INVALID_BODY,
    SESSION_LOST,
    FAILED,
}

internal interface MessagingGateway {
    suspend fun currentUserId(): UUID?
    suspend fun listConversations(
        userId: UUID,
        before: ConversationCursor?,
        limit: Int,
    ): List<MessageConversation>
    suspend fun loadMessages(
        userId: UUID,
        conversationId: UUID,
        before: MessageCursor?,
        limit: Int,
    ): List<DirectMessage>
    suspend fun createOrGetDirectConversation(userId: UUID, otherUserId: UUID): UUID
    suspend fun sendMessage(
        userId: UUID,
        conversationId: UUID,
        clientMessageId: UUID,
        body: String,
    ): DirectMessage
    fun observeIncomingMessages(userId: UUID, conversationId: UUID): Flow<DirectMessage>
}

internal class MessagingRepository(
    private val gateway: MessagingGateway,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableConversationState = MutableStateFlow<ConversationListState>(ConversationListState.Loading)
    private val mutableMessageState = MutableStateFlow<MessageListState>(MessageListState.Loading)
    private val messageMutex = Mutex()
    private val incomingWhileLoading = ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, DirectMessage>>()
    @Volatile private var activeConversationId: UUID? = null
    @Volatile private var loadedConversationId: UUID? = null
    private var realtimeJob: Job? = null

    val conversationState: StateFlow<ConversationListState> = mutableConversationState.asStateFlow()
    val messageState: StateFlow<MessageListState> = mutableMessageState.asStateFlow()

    suspend fun loadConversations(before: ConversationCursor? = null) {
        val current = gateway.currentUserId()
        if (current == null) {
            mutableConversationState.value = ConversationListState.SignedOut
            return
        }
        mutableConversationState.value = if (before == null) {
            ConversationListState.Loading
        } else {
            (mutableConversationState.value as? ConversationListState.Loaded)
                ?.copy(loadingMore = true)
                ?: ConversationListState.Loading
        }

        try {
            val page = gateway.listConversations(current, before, ConversationPageSize)
            if (gateway.currentUserId() != current) {
                mutableConversationState.value = ConversationListState.SignedOut
                return
            }
            val existing = (mutableConversationState.value as? ConversationListState.Loaded)?.conversations.orEmpty()
            val combined = mergeConversations(existing, page)
            val nextCursor = page.lastOrNull()?.let { ConversationCursor(it.createdAt, it.id) }
                .takeIf { page.size == ConversationPageSize }
            mutableConversationState.value = if (combined.isEmpty()) {
                ConversationListState.Empty
            } else {
                ConversationListState.Loaded(combined, nextCursor)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableConversationState.value = ConversationListState.Error
        }
    }

    suspend fun createOrGetDirectConversation(otherUserId: UUID): UUID? {
        val current = gateway.currentUserId() ?: run {
            mutableConversationState.value = ConversationListState.SignedOut
            return null
        }
        if (current == otherUserId) return null
        return try {
            val conversationId = gateway.createOrGetDirectConversation(current, otherUserId)
            if (gateway.currentUserId() == current) conversationId else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableConversationState.value = ConversationListState.Error
            null
        }
    }

    suspend fun loadMessages(conversationId: UUID, before: MessageCursor? = null) {
        val current = gateway.currentUserId()
        if (current == null) {
            loadedConversationId = null
            mutableMessageState.value = MessageListState.SignedOut
            return
        }
        val preserveCurrentPage = before != null && loadedConversationId == conversationId
        activeConversationId = conversationId
        if (!preserveCurrentPage) loadedConversationId = null
        val previous = mutableMessageState.value as? MessageListState.Loaded
        mutableMessageState.value = when {
            preserveCurrentPage && previous != null -> previous.copy(loadingOlder = true)
            else -> MessageListState.Loading
        }

        try {
            val page = gateway.loadMessages(current, conversationId, before, MessagePageSize)
            if (gateway.currentUserId() != current) {
                mutableMessageState.value = MessageListState.SignedOut
                return
            }
            messageMutex.withLock {
                if (activeConversationId != conversationId) return@withLock
                val currentMessages = if (loadedConversationId == conversationId) {
                    (mutableMessageState.value as? MessageListState.Loaded)?.messages.orEmpty()
                } else {
                    emptyList()
                }
                val buffered = incomingWhileLoading.remove(conversationId)?.values.orEmpty()
                val combined = mergeDirectMessages(currentMessages, page + buffered)
                val oldest = page.minWithOrNull(messageOrder)
                val nextCursor = oldest?.let { MessageCursor(it.createdAt, it.id) }
                    .takeIf { page.size == MessagePageSize }
                loadedConversationId = conversationId
                mutableMessageState.value = if (combined.isEmpty()) {
                    MessageListState.Empty
                } else {
                    MessageListState.Loaded(combined, nextCursor)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            messageMutex.withLock {
                if (activeConversationId == conversationId) {
                    loadedConversationId = null
                    mutableMessageState.value = MessageListState.Error
                }
            }
        }
    }

    suspend fun sendTextMessage(
        conversationId: UUID,
        clientMessageId: UUID,
        body: String,
    ): MessageSendResult {
        val normalizedBody = body.trim()
        if (normalizedBody.isEmpty() || normalizedBody.length > MaxMessageLength) {
            return MessageSendResult.INVALID_BODY
        }
        val current = gateway.currentUserId() ?: run {
            mutableMessageState.value = MessageListState.SignedOut
            return MessageSendResult.SESSION_LOST
        }
        return try {
            val message = gateway.sendMessage(current, conversationId, clientMessageId, normalizedBody)
            if (gateway.currentUserId() != current) return MessageSendResult.SESSION_LOST
            if (message.senderId != current || message.conversationId != conversationId || message.clientMessageId != clientMessageId) {
                return MessageSendResult.FAILED
            }
            messageMutex.withLock {
                when (val state = mutableMessageState.value) {
                    is MessageListState.Loaded if loadedConversationId == conversationId -> mutableMessageState.value = state.copy(
                        messages = mergeDirectMessages(state.messages, listOf(message)),
                    )
                    MessageListState.Empty if loadedConversationId == conversationId -> mutableMessageState.value = MessageListState.Loaded(listOf(message), null)
                    else -> Unit
                }
            }
            MessageSendResult.SENT
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            MessageSendResult.FAILED
        }
    }

    fun startObservingMessages(conversationId: UUID): Job {
        realtimeJob?.cancel()
        if (activeConversationId != conversationId) incomingWhileLoading.clear()
        activeConversationId = conversationId
        if (loadedConversationId != conversationId) {
            loadedConversationId = null
            mutableMessageState.value = MessageListState.Loading
        }
        val job = scope.launch {
            val current = gateway.currentUserId()
            if (current == null) {
                if (activeConversationId == conversationId) {
                    loadedConversationId = null
                    mutableMessageState.value = MessageListState.SignedOut
                }
                return@launch
            }
            try {
                gateway.observeIncomingMessages(current, conversationId).collect { message ->
                    if (gateway.currentUserId() != current) {
                        if (activeConversationId == conversationId) {
                            loadedConversationId = null
                            mutableMessageState.value = MessageListState.SignedOut
                        }
                        realtimeJob?.cancel()
                    } else if (message.conversationId == conversationId) {
                        messageMutex.withLock {
                            if (activeConversationId != conversationId) return@withLock
                            when (val state = mutableMessageState.value) {
                                is MessageListState.Loaded if loadedConversationId == conversationId -> mutableMessageState.value = state.copy(
                                    messages = mergeDirectMessages(state.messages, listOf(message)),
                                )
                                MessageListState.Empty if loadedConversationId == conversationId -> mutableMessageState.value = MessageListState.Loaded(listOf(message), null)
                                else -> incomingWhileLoading.computeIfAbsent(conversationId) { ConcurrentHashMap() }
                                    .putIfAbsent(message.id, message)
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                messageMutex.withLock {
                    if (activeConversationId != conversationId) return@withLock
                    when (val state = mutableMessageState.value) {
                        is MessageListState.Loaded -> mutableMessageState.value = state.copy(realtimeError = true)
                        MessageListState.Empty -> mutableMessageState.value = MessageListState.Error
                        else -> mutableMessageState.value = MessageListState.Error
                    }
                }
            }
        }
        realtimeJob = job
        return job
    }

    fun stopObservingMessages() {
        realtimeJob?.cancel()
        realtimeJob = null
    }

    override fun close() {
        stopObservingMessages()
        scope.cancel()
    }

    private companion object {
        const val ConversationPageSize = 50
        const val MessagePageSize = 50
        const val MaxMessageLength = 4000

        val messageOrder = compareBy<DirectMessage>({ it.createdAt }, { it.id.toString() })
    }
}

internal fun mergeDirectMessages(
    existing: List<DirectMessage>,
    incoming: Iterable<DirectMessage>,
): List<DirectMessage> {
    val byId = linkedMapOf<UUID, DirectMessage>()
    existing.forEach { byId.putIfAbsent(it.id, it) }
    incoming.forEach { byId.putIfAbsent(it.id, it) }
    return byId.values.sortedWith(compareBy({ it.createdAt }, { it.id.toString() }))
}

private fun mergeConversations(
    existing: List<MessageConversation>,
    incoming: List<MessageConversation>,
): List<MessageConversation> {
    val byId = linkedMapOf<UUID, MessageConversation>()
    existing.forEach { byId.putIfAbsent(it.id, it) }
    incoming.forEach { byId.putIfAbsent(it.id, it) }
    return byId.values.sortedWith(compareByDescending<MessageConversation> { it.createdAt }.thenByDescending { it.id.toString() })
}