package com.pupsikcall.app

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class IncomingCallNotificationsTest {
    private val callId = UUID.fromString("e43fc877-3c0f-4fcf-8e2b-6b85fe3f1001")
    private val callerId = UUID.fromString("e43fc877-3c0f-4fcf-8e2b-6b85fe3f1002")
    private val calleeId = UUID.fromString("e43fc877-3c0f-4fcf-8e2b-6b85fe3f1003")

    @Test
    fun onlyCallIdWakeupIsAcceptedAndPayloadCannotSupplyIdentityOrMedia() {
        val event = IncomingCallPushEvent.fromData(data())

        assertEquals(IncomingCallPushEvent(callId), event)
        assertNull(IncomingCallPushEvent.fromData(data() + ("caller_user_id" to callerId.toString())))
        assertNull(IncomingCallPushEvent.fromData(data() + ("status" to "ringing")))
        assertNull(IncomingCallPushEvent.fromData(data() + ("display_name" to "untrusted")))
        assertNull(IncomingCallPushEvent.fromData(data() + ("sdp" to "must-not-be-in-push")))
        assertNull(IncomingCallPushEvent.fromData(data() + ("sdpMid" to "audio")))
        assertNull(IncomingCallPushEvent.fromData(data() + ("credential" to "secret")))
        assertNull(IncomingCallPushEvent.fromData(mapOf("call_id" to "not-a-uuid")))
    }

    @Test
    fun legitimateRingingShowsNotificationAndDuplicateIsIgnored() {
        val lifecycle = IncomingCallNotificationLifecycle(InMemoryCallPushEventStore())
        val ringing = session(AuthenticatedCallStatus.RINGING)

        assertEquals(CallNotificationTransition.SHOW, lifecycle.apply(event(), ringing))
        assertEquals(CallNotificationTransition.IGNORE, lifecycle.apply(event(), ringing))
    }

    @Test
    fun laterTerminalEventCancelsNotificationAndStaleRingingCannotReshowIt() {
        val lifecycle = IncomingCallNotificationLifecycle(InMemoryCallPushEventStore())

        assertEquals(CallNotificationTransition.SHOW, lifecycle.apply(event(), session(AuthenticatedCallStatus.RINGING)))
        assertEquals(CallNotificationTransition.CANCEL, lifecycle.apply(event(), session(AuthenticatedCallStatus.CANCELLED)))
        assertEquals(CallNotificationTransition.CANCEL, lifecycle.apply(event(), null))
    }

    @Test
    fun acceptedAndFailedEventsCancelRingingNotification() {
        listOf(AuthenticatedCallStatus.ACCEPTED, AuthenticatedCallStatus.FAILED).forEach { terminalOrAccepted ->
            val lifecycle = IncomingCallNotificationLifecycle(InMemoryCallPushEventStore())
            lifecycle.apply(event(), session(AuthenticatedCallStatus.RINGING))

            assertEquals(CallNotificationTransition.CANCEL, lifecycle.apply(event(), session(terminalOrAccepted)))
        }
    }

    @Test
    fun changedCallerIdentityForSameCallIsRejected() {
        val lifecycle = IncomingCallNotificationLifecycle(InMemoryCallPushEventStore())
        lifecycle.apply(event(), session(AuthenticatedCallStatus.RINGING))
        val changedCaller = session(AuthenticatedCallStatus.RINGING).copy(callerUserId = UUID.randomUUID())

        assertEquals(CallNotificationTransition.IGNORE, lifecycle.apply(event(), changedCaller))
    }

    @Test
    fun notificationAnswerAndDeclineRequireExactFreshAuthenticatedInvitation() {
        val session = session(AuthenticatedCallStatus.RINGING)
        val answerAction = IncomingCallNotificationAction(callId, callerId, IncomingCallNotificationActionKind.ANSWER, "answer-once")
        val declineAction = answerAction.copy(kind = IncomingCallNotificationActionKind.DECLINE)

        assertSame(session, validateIncomingCallNotificationAction(answerAction, listOf(session), calleeId))
        assertSame(session, validateIncomingCallNotificationAction(declineAction, listOf(session), calleeId))
        assertNull(validateIncomingCallNotificationAction(answerAction, listOf(session), UUID.randomUUID()))
        assertNull(validateIncomingCallNotificationAction(answerAction.copy(callerUserId = UUID.randomUUID()), listOf(session), calleeId))
        assertNull(validateIncomingCallNotificationAction(answerAction.copy(callId = UUID.randomUUID()), listOf(session), calleeId))
        assertNull(validateIncomingCallNotificationAction(answerAction, listOf(session(AuthenticatedCallStatus.ACCEPTED)), calleeId))
    }

    @Test
    fun notificationActionDispatchUsesExistingAnswerAndDeclineCallbacks() {
        var answerCount = 0
        var declineCount = 0

        dispatchIncomingCallNotificationAction(IncomingCallNotificationActionKind.ANSWER, { answerCount++ }, { declineCount++ })
        dispatchIncomingCallNotificationAction(IncomingCallNotificationActionKind.DECLINE, { answerCount++ }, { declineCount++ })

        assertEquals(1, answerCount)
        assertEquals(1, declineCount)
    }

    @Test
    fun repeatedNotificationActionRequestIdCanBeClaimedOnlyOnce() {
        val gate = IncomingCallNotificationActionGate()
        val action = IncomingCallNotificationAction(callId, callerId, IncomingCallNotificationActionKind.ANSWER, "once")

        assertEquals(true, gate.claim(action))
        assertEquals(false, gate.claim(action))
        assertEquals(true, gate.claim(action.copy(requestId = "second")))
    }

    private fun data() = mapOf("call_id" to callId.toString())

    private fun event() = IncomingCallPushEvent(callId)

    private fun session(status: AuthenticatedCallStatus) = AuthenticatedCallSession(
        callId = callId,
        callerUserId = callerId,
        calleeUserId = calleeId,
        status = status,
        createdAt = "2026-09-29T00:00:00Z",
        acceptedAt = null,
        connectedAt = null,
        endedAt = null,
        failureCode = null,
    )

    private class InMemoryCallPushEventStore : CallPushEventStore {
        private val entries = mutableMapOf<UUID, StoredCallPushState>()

        override fun read(callId: UUID): StoredCallPushState? = entries[callId]

        override fun write(callId: UUID, state: StoredCallPushState) {
            entries[callId] = state
        }

        override fun remove(callId: UUID) {
            entries.remove(callId)
        }
    }
}