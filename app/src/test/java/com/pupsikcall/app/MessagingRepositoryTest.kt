package com.pupsikcall.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class MessagingRepositoryTest {
    @Test
    fun emptyConversationListIsExplicitAndDoesNotInventFallbackRows() = runBlocking {
        val repository = MessagingRepository(FakeMessagingGateway())
        repository.loadConversations()

        assertEquals(ConversationListState.Empty, repository.conversationState.value)
        repository.close()
    }

    @Test
    fun messagePaginationIsStableByCreatedAtThenId() = runBlocking {
        val gateway = FakeMessagingGateway().apply {
            messagePages[null] = listOf(message("2026-09-29T10:00:00Z", 2), message("2026-09-29T10:00:00Z", 1))
        }
        val repository = MessagingRepository(gateway)
        repository.loadMessages(conversationId)

        val loaded = repository.messageState.value as MessageListState.Loaded
        assertEquals(listOf(messageId(1), messageId(2)), loaded.messages.map(DirectMessage::id))
        repository.close()
    }

    @Test
    fun messagePaginationUsesStableCreatedAtAndIdCursorAcrossPages() = runBlocking {
        val timestamp = "2026-09-29T10:00:00Z"
        val firstPage = (50 downTo 1).map { message(timestamp, it) }
        val cursor = MessageCursor(timestamp, messageId(1))
        val olderMessage = message("2026-09-29T09:59:59Z", 0)
        val gateway = FakeMessagingGateway().apply {
            messagePages[null] = firstPage
            messagePages[cursor] = listOf(olderMessage)
        }
        val repository = MessagingRepository(gateway)

        repository.loadMessages(conversationId)
        val firstState = repository.messageState.value as MessageListState.Loaded
        assertEquals(cursor, firstState.nextCursor)
        repository.loadMessages(conversationId, firstState.nextCursor)

        val completeState = repository.messageState.value as MessageListState.Loaded
        assertEquals(51, completeState.messages.size)
        assertEquals(olderMessage.id, completeState.messages.first().id)
        assertEquals(messageId(50), completeState.messages.last().id)
        assertEquals(null, completeState.nextCursor)
        repository.close()
    }

    @Test
    fun retryUsesSameClientMessageIdAndDoesNotDuplicateFakeBackendRow() = runBlocking {
        val gateway = FakeMessagingGateway()
        val repository = MessagingRepository(gateway)
        repository.loadMessages(conversationId)
        val idempotencyKey = UUID.fromString("00000000-0000-4000-8000-000000000099")

        assertEquals(MessageSendResult.SENT, repository.sendTextMessage(conversationId, idempotencyKey, "hello"))
        assertEquals(MessageSendResult.SENT, repository.sendTextMessage(conversationId, idempotencyKey, "hello"))
        assertEquals(1, gateway.storedMessages.size)
        repository.close()
    }

    @Test
    fun incomingRealtimeDuplicatesMergeOnceAndInStableOrder() = runBlocking {
        val gateway = FakeMessagingGateway()
        val repository = MessagingRepository(gateway)
        repository.loadMessages(conversationId)
        repository.startObservingMessages(conversationId)
        val incoming = message("2026-09-29T10:00:00Z", 1)
        gateway.incoming.send(incoming)
        gateway.incoming.send(incoming)

        val loaded = withTimeout(2_000) {
            repository.messageState.first { it is MessageListState.Loaded && it.messages.any { message -> message.id == incoming.id } }
        } as MessageListState.Loaded
        assertEquals(1, loaded.messages.count { it.id == incoming.id })
        repository.close()
    }

    @Test
    fun incomingMessageCannotLeakAcrossConversationSwitch() = runBlocking {
        val secondConversationId = UUID.fromString("00000000-0000-4000-8000-000000000011")
        val gateway = FakeMessagingGateway().apply {
            messagePages[null] = listOf(message("2026-09-29T10:00:00Z", 1))
        }
        val repository = MessagingRepository(gateway)
        repository.loadMessages(conversationId)
        gateway.messagePages[null] = emptyList()
        repository.startObservingMessages(secondConversationId)
        gateway.observationStarted.await()
        gateway.incoming.send(message("2026-09-29T10:00:01Z", 2).copy(conversationId = secondConversationId))
        repository.loadMessages(secondConversationId)

        val loaded = withTimeout(2_000) {
            repository.messageState.first {
                it is MessageListState.Loaded && it.messages.any { message -> message.conversationId == secondConversationId }
            }
        } as MessageListState.Loaded
        assertTrue(loaded.messages.all { it.conversationId == secondConversationId })
        repository.close()
    }

    @Test
    fun sessionLossAndGatewayErrorsMapToSafeStates() = runBlocking {
        val signedOutRepository = MessagingRepository(FakeMessagingGateway().apply { userId = null })
        signedOutRepository.loadConversations()
        assertEquals(ConversationListState.SignedOut, signedOutRepository.conversationState.value)
        signedOutRepository.close()

        val failingRepository = MessagingRepository(FakeMessagingGateway().apply {
            listFailure = IllegalStateException("message body and access_token must not escape")
        })
        failingRepository.loadConversations()
        assertEquals(ConversationListState.Error, failingRepository.conversationState.value)
        assertFalse(failingRepository.conversationState.value.toString().contains("access_token"))
        failingRepository.close()
    }

    @Test
    fun cancellationFromRealtimeSourceIsNotConvertedToRepositoryError() = runBlocking {
        val gateway = FakeMessagingGateway()
        val repository = MessagingRepository(gateway)
        repository.loadMessages(conversationId)
        val job = repository.startObservingMessages(conversationId)
        job.cancel(CancellationException("screen closed"))
        job.join()
        assertTrue(repository.messageState.value is MessageListState.Empty || repository.messageState.value is MessageListState.Loaded)
        repository.close()
    }

    private class FakeMessagingGateway : MessagingGateway {
        var userId: UUID? = currentUserId
        var listFailure: Exception? = null
        val messagePages = mutableMapOf<MessageCursor?, List<DirectMessage>>()
        val storedMessages = linkedMapOf<Triple<UUID, UUID, UUID>, DirectMessage>()
        val incoming = Channel<DirectMessage>(Channel.UNLIMITED)
        val observationStarted = CompletableDeferred<Unit>()

        override suspend fun currentUserId(): UUID? = userId

        override suspend fun listConversations(
            userId: UUID,
            before: ConversationCursor?,
            limit: Int,
        ): List<MessageConversation> {
            listFailure?.let { throw it }
            return emptyList()
        }

        override suspend fun loadMessages(
            userId: UUID,
            conversationId: UUID,
            before: MessageCursor?,
            limit: Int,
        ): List<DirectMessage> = messagePages[before].orEmpty()

        override suspend fun createOrGetDirectConversation(userId: UUID, otherUserId: UUID): UUID = conversationId

        override suspend fun sendMessage(
            userId: UUID,
            conversationId: UUID,
            clientMessageId: UUID,
            body: String,
        ): DirectMessage {
            val key = Triple(conversationId, userId, clientMessageId)
            return storedMessages.getOrPut(key) {
                DirectMessage(messageId(storedMessages.size + 10), conversationId, userId, clientMessageId, body, "2026-09-29T10:00:01Z")
            }
        }

        override fun observeIncomingMessages(userId: UUID, conversationId: UUID) = kotlinx.coroutines.flow.flow {
            observationStarted.complete(Unit)
            incoming.receiveAsFlow().collect { emit(it) }
        }
    }

    companion object {
        private val currentUserId = UUID.fromString("00000000-0000-4000-8000-000000000001")
        private val conversationId = UUID.fromString("00000000-0000-4000-8000-000000000010")

        private fun message(timestamp: String, id: Int) = DirectMessage(
            id = messageId(id),
            conversationId = conversationId,
            senderId = currentUserId,
            clientMessageId = messageId(id + 100),
            body = "message $id",
            createdAt = timestamp,
        )

        private fun messageId(value: Int) = UUID.fromString("00000000-0000-4000-8000-${value.toString().padStart(12, '0')}")
    }
}