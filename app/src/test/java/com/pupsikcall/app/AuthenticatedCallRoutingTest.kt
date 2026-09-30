package com.pupsikcall.app

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AuthenticatedCallRoutingTest {
    @Test
    fun supabaseClientConfigurationRequiresHttpsAndPublicClientKey() {
        assertTrue(isValidSupabaseClientConfiguration("https://example.supabase.co", "sb_publishable_test-key"))
        assertTrue(isValidSupabaseClientConfiguration("https://example.supabase.co", legacyJwt("anon")))
        assertFalse(isValidSupabaseClientConfiguration("", "sb_publishable_test-key"))
        assertFalse(isValidSupabaseClientConfiguration("http://example.supabase.co", "sb_publishable_test-key"))
        assertFalse(isValidSupabaseClientConfiguration("https://example.supabase.co", ""))
        assertFalse(isValidSupabaseClientConfiguration("https://example.supabase.co", "sb_secret_test-key"))
        assertFalse(isValidSupabaseClientConfiguration("https://example.supabase.co", legacyJwt("service_role")))
        assertFalse(isValidSupabaseClientConfiguration("https://user@example.supabase.co", "sb_publishable_test-key"))
    }

    private fun legacyJwt(role: String): String {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"role\":\"$role\"}".toByteArray())
        return "eyJhbGciOiJIUzI1NiJ9.$payload.signature"
    }

    @Test
    fun localIdentityIsParsedOnlyFromAuthenticatedUuid() {
        assertEquals(callerId, authenticatedCallUserId(callerId.toString()))
        assertNull(authenticatedCallUserId(null))
        assertNull(authenticatedCallUserId("pupsik-a"))
        assertNull(authenticatedCallUserId("person@example.test"))
    }

    @Test
    fun outgoingRouteRequiresAuthCallerAndExplicitRemoteUuid() {
        assertEquals(
            calleeId,
            outgoingCallRoute(callerId, calleeId, session())?.remoteUserId,
        )
        assertNull(outgoingCallRoute(callerId, callerId, session()))
        assertNull(outgoingCallRoute(unrelatedId, calleeId, session()))
        assertNull(outgoingCallRoute(callerId, null, session()))
    }

    @Test
    fun incomingInviteMustBeRingingAndAddressedToCurrentAuthUser() {
        assertEquals(callerId, incomingCallRoute(calleeId, session())?.remoteUserId)
        assertNull(incomingCallRoute(callerId, session()))
        assertNull(incomingCallRoute(unrelatedId, session()))
        assertNull(incomingCallRoute(calleeId, session(AuthenticatedCallStatus.COMPLETED)))
    }

    @Test
    fun malformedCallAndParticipantUuidsAreRejected() {
        assertNull(parseAuthenticatedCallSession(buildJsonObject {
            put("id", "not-a-uuid")
            put("caller_user_id", callerId.toString())
            put("callee_user_id", calleeId.toString())
            put("status", "ringing")
            put("created_at", "2026-09-29T12:00:00Z")
        }))
        assertNull(parseAuthenticatedCallSession(sessionJson(callerId, callerId)))
        assertNull(parseAuthenticatedCallMediaSignal(buildJsonObject {
            put("callId", session().callId.toString())
            put("callerUserId", callerId.toString())
            put("calleeUserId", calleeId.toString())
            put("senderUserId", "malformed")
            put("type", "offer")
            put("sdp", "offer")
        }))
    }

    @Test
    fun mediaSignalMustMatchSessionPairActiveCallAndRemoteSender() {
        val call = session()
        val valid = signal(call, sender = calleeId)
        assertTrue(acceptsCallMediaSignal(call, callerId, call.callId, valid))
        assertFalse(acceptsCallMediaSignal(call, callerId, UUID.randomUUID(), valid))
        assertFalse(acceptsCallMediaSignal(call, unrelatedId, call.callId, valid))
        assertFalse(acceptsCallMediaSignal(call, callerId, call.callId, signal(call, sender = callerId)))
        assertFalse(acceptsCallMediaSignal(call, callerId, call.callId, signal(call.copy(calleeUserId = unrelatedId), sender = calleeId)))
    }

    @Test
    fun mediaSignalPreservesSdpAndCandidateTupleAndExistingSignalOrder() {
        val call = session()
        val candidate = LocalIceCandidate("audio", 0, "candidate:unchanged")
        val iceSignal = AuthenticatedCallMediaSignal(
            call.callId,
            call.callerUserId,
            call.calleeUserId,
            calleeId,
            CallMediaEvent.ICE,
            candidate.sdp,
            candidate.sdpMid,
            candidate.sdpMLineIndex,
        )
        assertEquals(candidate, LocalIceCandidate(iceSignal.sdpMid, iceSignal.sdpMLineIndex, iceSignal.sdp))

        val queue = OrderedSignalQueue<String>()
        queue.enqueue("offer")
        queue.enqueue("ice")
        queue.enqueue("answer")
        val ordered = kotlinx.coroutines.runBlocking { queue.asFlow().take(3).toList() }
        queue.close()
        assertEquals(listOf("offer", "ice", "answer"), ordered)
    }

    private fun session(status: AuthenticatedCallStatus = AuthenticatedCallStatus.RINGING) = AuthenticatedCallSession(
        callId = callId,
        callerUserId = callerId,
        calleeUserId = calleeId,
        status = status,
        createdAt = "2026-09-29T12:00:00Z",
        acceptedAt = null,
        connectedAt = null,
        endedAt = null,
        failureCode = null,
    )

    private fun sessionJson(caller: UUID, callee: UUID) = buildJsonObject {
        put("id", callId.toString())
        put("caller_user_id", caller.toString())
        put("callee_user_id", callee.toString())
        put("status", "ringing")
        put("created_at", "2026-09-29T12:00:00Z")
    }

    private fun signal(call: AuthenticatedCallSession, sender: UUID) = AuthenticatedCallMediaSignal(
        callId = call.callId,
        callerUserId = call.callerUserId,
        calleeUserId = call.calleeUserId,
        senderUserId = sender,
        event = CallMediaEvent.OFFER,
        sdp = "sdp-unchanged",
    )

    companion object {
        private val callerId = UUID.fromString("00000000-0000-4000-8000-000000000001")
        private val calleeId = UUID.fromString("00000000-0000-4000-8000-000000000002")
        private val unrelatedId = UUID.fromString("00000000-0000-4000-8000-000000000003")
        private val callId = UUID.fromString("00000000-0000-4000-8000-000000000010")
    }
}