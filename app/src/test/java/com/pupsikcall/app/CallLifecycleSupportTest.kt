package com.pupsikcall.app

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import io.ktor.client.plugins.websocket.WebSocketCapability
import org.webrtc.PeerConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Known-good physical-call baseline: future Auth, identity, UI, or background work must not alter these calling protocols without a failing regression test or an explicit requirement.
 */
class CallLifecycleSupportTest {
    @Test
    fun disposedOldCallCannotMutateNewerCall() {
        val gate = CallCallbackGate()
        var delivered = 0
        var activeCallId = "old-call"

        gate.dispatch {
            delivered++
            activeCallId = "old-callback"
        }
        assertEquals(1, delivered)
        assertTrue(gate.close())
        activeCallId = "new-call"
        gate.dispatch {
            delivered++
            activeCallId = "late-old-callback"
        }

        assertEquals(1, delivered)
        assertEquals("new-call", activeCallId)
        assertFalse(gate.isOpen())
        assertFalse(gate.close())
    }

    @Test
    fun signalingFailureReportsErrorWithoutFalselyFailingWebRtcState() {
        val status = CallUiStatus(WebRtcCallState.CONNECTING, null)
            .withSignalError("Realtime subscription failed")

        assertEquals(WebRtcCallState.CONNECTING, status.state)
        assertEquals("Realtime subscription failed", status.error)
    }

    @Test
    fun signalingTransitionsFollowTheCompleteCallLifecycle() {
        val caller = CallPhaseMachine()
        val callee = CallPhaseMachine()
        val callId = "call-test"

        assertEquals(CallPhase.RINGING, caller.record(callId, "ringing"))
        assertEquals(CallPhase.RINGING, callee.record(callId, "ringing"))
        assertEquals(CallPhase.RINGING, caller.record(callId, "offer"))
        assertEquals(CallPhase.RINGING, caller.phase(callId))
        assertEquals(CallPhase.ACCEPTED, caller.record(callId, "accepted"))
        assertEquals(CallPhase.ACCEPTED, callee.record(callId, "accepted"))
        assertEquals(CallPhase.OFFERED, caller.record(callId, "offer"))
        assertEquals(CallPhase.OFFERED, callee.record(callId, "offer"))
        assertEquals(CallPhase.ANSWERED, caller.record(callId, "answer"))
        assertEquals(CallPhase.ANSWERED, callee.record(callId, "answer"))
        assertEquals(CallPhase.CONNECTED, caller.record(callId, "connected"))
        assertEquals(CallPhase.CONNECTED, callee.record(callId, "connected"))
        assertEquals(CallPhase.ENDED, caller.record(callId, "ended"))
        assertEquals(CallPhase.ENDED, callee.record(callId, "ended"))
        assertEquals(CallPhase.ENDED, caller.record(callId, "answer"))
    }

    @Test
    fun orderedSignalQueuePreservesSdpBeforeIceAndRejectsAfterTermination() = runBlocking {
        val queue = OrderedSignalQueue<String>()

        assertTrue(queue.enqueue("offer"))
        assertTrue(queue.enqueue("ice-1"))
        assertTrue(queue.enqueue("ice-2"))
        assertTrue(queue.enqueue("ended", terminal = true))
        assertFalse(queue.enqueue("late-ice"))
        queue.close()

        assertEquals(listOf("offer", "ice-1", "ice-2", "ended"), queue.asFlow().toList())
    }

    @Test
    fun earlyRemoteIceIsDrainedInArrivalOrderOnceRemoteDescriptionIsSet() {
        val pendingCandidates = PendingIceBuffer<String>()
        pendingCandidates.add("candidate-1")
        pendingCandidates.add("candidate-2")

        assertEquals(listOf("candidate-1", "candidate-2"), pendingCandidates.drain())
        assertTrue(pendingCandidates.drain().isEmpty())
    }

    @Test
    fun iceCandidateTypesAreClassifiedWithoutRetainingAddresses() {
        assertEquals(IceCandidateType.HOST, classifyIceCandidateType("candidate:1 1 UDP 100 192.0.2.1 5000 typ host"))
        assertEquals(IceCandidateType.SRFLX, classifyIceCandidateType("candidate:2 1 UDP 100 198.51.100.2 5001 typ srflx raddr 192.0.2.1 rport 5000"))
        assertEquals(IceCandidateType.RELAY, classifyIceCandidateType("candidate:3 1 UDP 100 203.0.113.3 5002 typ relay"))
        assertNull(classifyIceCandidateType("end-of-candidates"))
    }

    @Test
    fun rtcConfigurationUsesStunOnlyAndAllPolicyWithoutTurnSettings() {
        val configuration = createWebRtcRtcConfiguration("", "", "", debugBuild = true, forceRelay = false)

        assertEquals(1, configuration.iceServers.size)
        assertEquals(listOf("stun:stun.l.google.com:19302"), configuration.iceServers.single().urls)
        assertEquals(PeerConnection.IceTransportsType.ALL, configuration.iceTransportsType)
    }

    @Test
    fun rtcConfigurationAddsTurnAndCredentialsWhenFullyConfigured() {
        val configuration = createWebRtcRtcConfiguration(
            "turn:turn.example.test:3478, turns:turn.example.test:5349",
            "turn-user",
            "turn-secret",
            debugBuild = true,
            forceRelay = false,
        )

        assertEquals(2, configuration.iceServers.size)
        val turnServer = configuration.iceServers[1]
        assertEquals(listOf("turn:turn.example.test:3478", "turns:turn.example.test:5349"), turnServer.urls)
        assertEquals("turn-user", turnServer.username)
        assertEquals("turn-secret", turnServer.password)
        assertEquals(PeerConnection.IceTransportsType.ALL, configuration.iceTransportsType)
    }

    @Test
    fun relayPolicyOverrideRequiresDebugBuildAndExplicitFlag() {
        val debugRelay = createWebRtcRtcConfiguration("", "", "", debugBuild = true, forceRelay = true)
        val nonDebugRelay = createWebRtcRtcConfiguration("", "", "", debugBuild = false, forceRelay = true)

        assertEquals(PeerConnection.IceTransportsType.RELAY, debugRelay.iceTransportsType)
        assertEquals(PeerConnection.IceTransportsType.ALL, nonDebugRelay.iceTransportsType)
    }

    @Test
    fun partialOrCredentialBearingTurnConfigurationIsIgnored() {
        val partial = createWebRtcRtcConfiguration("turn:turn.example.test:3478", "turn-user", "", true, false)
        val credentialBearingUrl = createWebRtcRtcConfiguration("turn://user:pass@turn.example.test", "turn-user", "turn-secret", true, false)

        assertEquals(1, partial.iceServers.size)
        assertEquals(1, credentialBearingUrl.iceServers.size)
    }

    @Test
    fun remoteIceCandidatesBufferAndFlushAtomicallyAfterRemoteDescription() {
        val firstCandidate = LocalIceCandidate("audio", 0, "candidate:first")
        val secondCandidate = LocalIceCandidate(null, 1, "candidate:second")
        val thirdCandidate = LocalIceCandidate("video", 2, "candidate:third")
        val buffer = RemoteIceCandidateBuffer<LocalIceCandidate>()
        val added = mutableListOf<LocalIceCandidate>()

        assertFalse(buffer.add(firstCandidate, added::add))
        assertFalse(buffer.add(secondCandidate, added::add))
        assertTrue(added.isEmpty())
        buffer.markRemoteDescriptionSet(added::add)
        assertEquals(listOf(firstCandidate, secondCandidate), added)
        assertTrue(buffer.add(thirdCandidate, added::add))
        assertEquals(listOf(firstCandidate, secondCandidate, thirdCandidate), added)
    }

    @Test
    fun iceDiagnosticsCountCandidatesAndOnlyFormatSafeFields() {
        val diagnostics = IceDiagnostics()
        diagnostics.updateGatheringState("COMPLETE")
        diagnostics.updateIceConnectionState("CHECKING")
        diagnostics.updatePeerConnectionState("CONNECTING")
        diagnostics.recordLocalCandidate("candidate:1 1 UDP 100 192.0.2.1 5000 typ host ufrag local-secret")
        diagnostics.recordLocalCandidate("candidate:2 1 UDP 100 198.51.100.2 5001 typ srflx")
        diagnostics.recordLocalCandidate("candidate:3 1 UDP 100 203.0.113.3 5002 typ relay")
        diagnostics.recordRemoteCandidateReceived("candidate:4 1 UDP 100 192.0.2.4 5003 typ host ufrag remote-secret")
        diagnostics.recordRemoteCandidateReceived("candidate:5 1 UDP 100 198.51.100.5 5004 typ srflx")
        diagnostics.recordRemoteCandidateReceived("candidate:6 1 UDP 100 203.0.113.6 5005 typ relay")
        diagnostics.recordRemoteCandidateResult(true)
        diagnostics.recordRemoteCandidateResult(true)
        diagnostics.recordRemoteCandidateResult(false)
        diagnostics.recordCandidateGatheringError(701)
        diagnostics.updateSelectedCandidatePair(
            "candidate:7 1 UDP 100 192.0.2.7 5006 typ srflx",
            "candidate:8 1 UDP 100 198.51.100.8 5007 typ host",
        )

        val formatted = diagnostics.format()
        assertTrue(formatted.contains("gathering=COMPLETE"))
        assertTrue(formatted.contains("iceConnection=CHECKING"))
        assertTrue(formatted.contains("peerConnection=CONNECTING"))
        assertTrue(formatted.contains("local host=1 srflx=1 relay=1"))
        assertTrue(formatted.contains("remote host=1 srflx=1 relay=1"))
        assertTrue(formatted.contains("candidate add received=3 added=2 failed=1"))
        assertTrue(formatted.contains("gathering errors=1 last-code=701"))
        assertTrue(formatted.contains("selected=srflx <-> host"))
        assertFalse(formatted.contains("192.0.2."))
        assertFalse(formatted.contains("198.51.100."))
        assertFalse(formatted.contains("203.0.113."))
        assertFalse(formatted.contains("local-secret"))
        assertFalse(formatted.contains("remote-secret"))
    }

    @Test
    fun rejectedIceCandidateIsCountedAsFailed() {
        val diagnostics = IceDiagnostics()
        val added = addIceCandidateAndRecordResult("candidate", { false }, diagnostics::recordRemoteCandidateResult)

        assertFalse(added)
        assertTrue(diagnostics.format().contains("candidate add received=0 added=0 failed=1"))
    }

    @Test
    fun failedIceDiagnosticIncludesSafeSnapshot() {
        val diagnostics = IceDiagnostics()
        diagnostics.updateGatheringState("COMPLETE")
        diagnostics.updateIceConnectionState("CHECKING")
        diagnostics.updatePeerConnectionState("FAILED")
        diagnostics.recordLocalCandidate("candidate:1 1 UDP 100 192.0.2.1 5000 typ host")

        val formatted = diagnostics.format()
        assertTrue(formatted.startsWith("ICE FAILED\ngathering=COMPLETE\niceConnection=CHECKING"))
        assertTrue(formatted.contains("local host=1 srflx=0 relay=0"))
    }

    @Test
    fun disconnectedIceDiagnosticRemainsDistinctFromFailure() {
        val diagnostics = IceDiagnostics()
        diagnostics.updateIceConnectionState("DISCONNECTED")

        val formatted = diagnostics.format()
        assertTrue(formatted.startsWith("ICE DISCONNECTED"))
        assertTrue(formatted.contains("iceConnection=DISCONNECTED"))
        assertFalse(formatted.startsWith("ICE FAILED"))
    }

    @Test
    fun turnCredentialsAndCredentialBearingUrlsNeverAppearInIceDiagnostics() {
        val diagnostics = IceDiagnostics()
        diagnostics.updateGatheringState("COMPLETE")
        diagnostics.updateIceConnectionState("FAILED")
        diagnostics.updatePeerConnectionState("FAILED")
        diagnostics.recordLocalCandidate("candidate:1 1 UDP 100 192.0.2.1 5000 typ relay")

        val diagnostic = diagnostics.format()
        assertTrue(diagnostic.contains("local host=0 srflx=0 relay=1"))
        assertTrue(diagnostic.contains("peerConnection=FAILED"))
        assertFalse(diagnostic.contains("turn-user"))
        assertFalse(diagnostic.contains("turn-secret"))
        assertFalse(diagnostic.contains("192.0.2.1"))

        val sanitized = RuntimeDiagnostic.sanitize(
            "turns://turn-user:turn-secret@turn.example.test:5349 turn:turn.example.test:3478 username=turn-user credential=turn-secret",
        )
        assertFalse(sanitized.contains("turn-user"))
        assertFalse(sanitized.contains("turn-secret"))
        assertFalse(sanitized.contains("turn.example.test"))
    }

    @Test
    fun presenceTracksJoinsLeavesDuplicateSessionsAndReconnects() {
        val presence = PresenceDeviceTracker("pupsik-a")

        assertFalse(presence.isPeerOnline())
        assertEquals("pupsik-b" to true, presence.join("session-1", "pupsik-b"))
        assertNull(presence.join("session-2", "pupsik-b"))
        assertNull(presence.leave("session-1", "pupsik-b"))
        assertEquals("pupsik-b" to false, presence.leave("session-2", "pupsik-b"))
        assertNull(presence.clear())
        assertEquals("pupsik-b" to true, presence.join("session-3", "pupsik-b"))
        assertEquals("pupsik-b" to false, presence.clear())
        assertTrue(presence.isPeerOnline().not())
    }

    @Test
    fun microphonePermissionMustBeGrantedBeforeAudioSetupCanBegin() {
        val deniedGate = AudioSetupGate()

        assertFalse(deniedGate.begin(microphonePermissionGranted = false))
        assertEquals(AudioSetupStatus.FAILED, deniedGate.status)
        assertEquals("Microphone permission denied", deniedGate.failureReason)
        assertFalse(deniedGate.canNegotiate)

        val grantedGate = AudioSetupGate()
        assertTrue(grantedGate.begin(microphonePermissionGranted = true))
        assertEquals(AudioSetupStatus.PREPARING, grantedGate.status)
        assertFalse(grantedGate.canNegotiate)
    }

    @Test
    fun negotiationIsBlockedUntilAudioSetupSucceedsAndFailureKeepsReason() {
        val gate = AudioSetupGate()

        assertTrue(gate.begin(microphonePermissionGranted = true))
        assertFalse(gate.canNegotiate)
        assertTrue(gate.markReady())
        assertTrue(gate.canNegotiate)

        val failedGate = AudioSetupGate()
        assertTrue(failedGate.begin(microphonePermissionGranted = true))
        failedGate.fail("PeerConnection creation failed: IllegalStateException: native factory unavailable")
        assertFalse(failedGate.canNegotiate)
        assertEquals(AudioSetupStatus.FAILED, failedGate.status)
        assertTrue(failedGate.failureReason!!.contains("native factory unavailable"))
    }

    @Test
    fun productionRealtimeEngineSupportsWebSocketUpgrades() {
        val engine = createSupabaseRealtimeHttpEngine()
        try {
            assertTrue(engine.supportedCapabilities.contains(WebSocketCapability))
        } finally {
            engine.close()
        }
    }

    @Test
    fun runtimeDiagnosticsKeepStageAndTypeButRedactNetworkSecrets() {
        val diagnostic = RuntimeDiagnostic.fromThrowable(
            "REALTIME",
            "subscribe",
            IllegalStateException(
                "Failed at wss://user:secret@project.supabase.co/realtime/v1/websocket?apikey=sb_publishable-secret access_token=eyJabc.def.ghi",
            ),
        )

        assertTrue(diagnostic.startsWith("REALTIME / subscribe / IllegalStateException:"))
        assertTrue(diagnostic.contains("[URL redacted]"))
        assertFalse(diagnostic.contains("project.supabase.co"))
        assertFalse(diagnostic.contains("sb_publishable-secret"))
        assertFalse(diagnostic.contains("eyJabc"))
        assertFalse(diagnostic.contains("user:secret"))
    }

    @Test
    fun runtimeDiagnosticsRedactBareSupabasePublishableAndSecretKeys() {
        val publishableKey = "sb_publishable_testKey_123"
        val secretKey = "sb_secret_testSecret_456"
        val diagnostic = RuntimeDiagnostic.fromThrowable(
            "REALTIME",
            "client-creation",
            IllegalArgumentException("Invalid credentials $publishableKey and $secretKey"),
        )

        assertTrue(diagnostic.startsWith("REALTIME / client-creation / IllegalArgumentException:"))
        assertTrue(diagnostic.contains("[Supabase key redacted]"))
        assertFalse(diagnostic.contains(publishableKey))
        assertFalse(diagnostic.contains(secretKey))
    }

    @Test
    fun runtimeDiagnosticsRedactBearerAndTokenCredentials() {
        val bearerCredential = "Bearer bearerSecret_123-abc"
        val tokenCredential = "tokenSecret_456"
        val jwtCredential = "eyJabc.def.ghi"
        val diagnostic = RuntimeDiagnostic.fromThrowable(
            "REALTIME",
            "authorization",
            IllegalStateException("Request rejected: $bearerCredential access_token=$tokenCredential $jwtCredential"),
        )

        assertTrue(diagnostic.startsWith("REALTIME / authorization / IllegalStateException:"))
        assertFalse(diagnostic.contains("bearerSecret_123-abc"))
        assertFalse(diagnostic.contains(tokenCredential))
        assertFalse(diagnostic.contains(jwtCredential))
    }

    @Test
    fun runtimeDiagnosticsRedactSdpAndKeepUsefulFailureContext() {
        val sdpCredential = "ice-password-secret"
        val diagnostic = RuntimeDiagnostic.fromThrowable(
            "AUDIO",
            "set-remote-offer",
            IllegalStateException("Remote description rejected\nv=0\r\na=ice-pwd:$sdpCredential\r\na=ice-ufrag:user-secret"),
        )

        assertTrue(diagnostic.startsWith("AUDIO / set-remote-offer / IllegalStateException:"))
        assertTrue(diagnostic.contains("Remote description rejected"))
        assertTrue(diagnostic.contains("[SDP redacted]"))
        assertFalse(diagnostic.contains(sdpCredential))
        assertFalse(diagnostic.contains("user-secret"))
    }

    @Test
    fun runtimeDiagnosticsRedactPartialSdpWithoutSessionHeader() {
        val fingerprint = "a=fingerprint:sha-256 01:02:03:04"
        val address = "c=IN IP4 192.0.2.10"
        val diagnostic = RuntimeDiagnostic.fromThrowable(
            "AUDIO",
            "set-remote-answer",
            IllegalStateException("Remote description rejected\n$fingerprint\n$address\nm=audio 9 UDP/TLS/RTP/SAVPF 111"),
        )

        assertTrue(diagnostic.contains("Remote description rejected"))
        assertFalse(diagnostic.contains("fingerprint"))
        assertFalse(diagnostic.contains("192.0.2.10"))
        assertFalse(diagnostic.contains("m=audio"))
    }

    @Test
    fun debugFailureDisplayCannotFallBackToGenericAudioLabel() {
        assertEquals(
            "AUDIO / audio-source / IllegalStateException: source rejected",
            RuntimeDiagnostic.failureDisplayText(
                debugBuild = true,
                genericText = "Audio setup failed",
                diagnostic = "AUDIO / audio-source / IllegalStateException: source rejected",
            ),
        )
        assertEquals(
            "AUDIO / unknown / diagnostic unavailable",
            RuntimeDiagnostic.failureDisplayText(true, "Audio setup failed", null),
        )
        assertEquals(
            "Audio setup failed",
            RuntimeDiagnostic.failureDisplayText(false, "Audio setup failed", "AUDIO / failure"),
        )
    }
}