package com.pupsikcall.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

internal enum class AuthenticatedCallStatus(val databaseValue: String) {
    PREPARING("preparing"),
    RINGING("ringing"),
    ACCEPTED("accepted"),
    CONNECTED("connected"),
    COMPLETED("completed"),
    DECLINED("declined"),
    CANCELLED("cancelled"),
    MISSED("missed"),
    FAILED("failed");

    val isTerminal: Boolean
        get() = this in setOf(COMPLETED, DECLINED, CANCELLED, MISSED, FAILED)

    companion object {
        fun parse(value: String?): AuthenticatedCallStatus? = entries.firstOrNull { it.databaseValue == value }
    }
}

internal data class AuthenticatedCallSession(
    val callId: UUID,
    val callerUserId: UUID,
    val calleeUserId: UUID,
    val status: AuthenticatedCallStatus,
    val createdAt: String,
    val acceptedAt: String?,
    val connectedAt: String?,
    val endedAt: String?,
    val failureCode: String?,
) {
    fun remoteUserId(localUserId: UUID): UUID? = when (localUserId) {
        callerUserId -> calleeUserId
        calleeUserId -> callerUserId
        else -> null
    }
}

internal enum class CallMediaEvent(val wireValue: String) {
    OFFER("offer"),
    ANSWER("answer"),
    ICE("ice");

    companion object {
        fun parse(value: String?): CallMediaEvent? = entries.firstOrNull { it.wireValue == value }
    }
}

internal data class AuthenticatedCallMediaSignal(
    val callId: UUID,
    val callerUserId: UUID,
    val calleeUserId: UUID,
    val senderUserId: UUID,
    val event: CallMediaEvent,
    val sdp: String,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int = 0,
)

internal data class AuthenticatedCallRoute(
    val session: AuthenticatedCallSession,
    val localUserId: UUID,
    val remoteUserId: UUID,
)

internal fun authenticatedCallUserId(sessionUserId: String?): UUID? =
    sessionUserId?.let { runCatching { UUID.fromString(it) }.getOrNull() }

internal fun parseAuthenticatedCallSession(row: JsonObject): AuthenticatedCallSession? {
    val callId = row.uuidOrNull("id") ?: return null
    val caller = row.uuidOrNull("caller_user_id") ?: return null
    val callee = row.uuidOrNull("callee_user_id") ?: return null
    val status = AuthenticatedCallStatus.parse(row.stringOrNull("status")) ?: return null
    if (caller == callee) return null
    val createdAt = row.stringOrNull("created_at") ?: return null
    return AuthenticatedCallSession(
        callId = callId,
        callerUserId = caller,
        calleeUserId = callee,
        status = status,
        createdAt = createdAt,
        acceptedAt = row.stringOrNull("accepted_at"),
        connectedAt = row.stringOrNull("connected_at"),
        endedAt = row.stringOrNull("ended_at"),
        failureCode = row.stringOrNull("failure_code"),
    )
}

internal fun parseAuthenticatedCallMediaSignal(row: JsonObject): AuthenticatedCallMediaSignal? {
    val callId = row.uuidOrNull("callId") ?: return null
    val caller = row.uuidOrNull("callerUserId") ?: return null
    val callee = row.uuidOrNull("calleeUserId") ?: return null
    val sender = row.uuidOrNull("senderUserId") ?: return null
    val event = CallMediaEvent.parse(row.stringOrNull("type")) ?: return null
    if (caller == callee || sender != caller && sender != callee) return null
    val sdp = row.stringOrNull("sdp") ?: return null
    val lineIndex = row.stringOrNull("sdpMLineIndex")?.toIntOrNull() ?: 0
    return AuthenticatedCallMediaSignal(
        callId = callId,
        callerUserId = caller,
        calleeUserId = callee,
        senderUserId = sender,
        event = event,
        sdp = sdp,
        sdpMid = row.stringOrNull("sdpMid")?.ifEmpty { null },
        sdpMLineIndex = lineIndex,
    )
}

internal fun outgoingCallRoute(
    localUserId: UUID?,
    requestedCalleeUserId: UUID?,
    session: AuthenticatedCallSession,
): AuthenticatedCallRoute? {
    if (localUserId == null || requestedCalleeUserId == null || localUserId == requestedCalleeUserId) return null
    if (session.callerUserId != localUserId || session.calleeUserId != requestedCalleeUserId) return null
    if (session.status !in setOf(AuthenticatedCallStatus.PREPARING, AuthenticatedCallStatus.RINGING)) return null
    return AuthenticatedCallRoute(session, localUserId, requestedCalleeUserId)
}

internal fun incomingCallRoute(
    localUserId: UUID?,
    session: AuthenticatedCallSession,
): AuthenticatedCallRoute? {
    if (localUserId == null || session.status != AuthenticatedCallStatus.RINGING) return null
    if (session.calleeUserId != localUserId || session.callerUserId == localUserId) return null
    return AuthenticatedCallRoute(session, localUserId, session.callerUserId)
}

internal fun acceptsCallMediaSignal(
    session: AuthenticatedCallSession,
    localUserId: UUID?,
    activeCallId: UUID?,
    signal: AuthenticatedCallMediaSignal,
): Boolean {
    if (localUserId == null || activeCallId != session.callId || signal.callId != session.callId) return false
    if (signal.callerUserId != session.callerUserId || signal.calleeUserId != session.calleeUserId) return false
    val expectedRemote = session.remoteUserId(localUserId) ?: return false
    return signal.senderUserId == expectedRemote && !session.status.isTerminal
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.uuidOrNull(key: String): UUID? =
    stringOrNull(key)?.let { runCatching { UUID.fromString(it) }.getOrNull() }