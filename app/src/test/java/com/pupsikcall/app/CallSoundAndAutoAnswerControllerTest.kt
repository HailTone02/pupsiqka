package com.pupsikcall.app

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSoundAndAutoAnswerControllerTest {
    private val callerId = UUID.fromString("e43fc877-3c0f-4fcf-8e2b-6b85fe3f0001")
    private val calleeId = UUID.fromString("e43fc877-3c0f-4fcf-8e2b-6b85fe3f0002")
    private val callId = UUID.fromString("e43fc877-3c0f-4fcf-8e2b-6b85fe3f0003")

    @Test
    fun outgoingRingbackStartsOnlyForAuthenticatedOutgoingRingingAndDoesNotDuplicate() {
        val output = RecordingCallSoundOutput()
        val controller = OutgoingRingbackController(output)
        val ringing = session(AuthenticatedCallStatus.RINGING)

        controller.update(ringing, callerId, callId, signedIn = true, foreground = true)
        controller.update(ringing, callerId, callId, signedIn = true, foreground = true)

        assertEquals(listOf("start:OUTGOING_RINGBACK:$callId"), output.events)
    }

    @Test
    fun outgoingRingbackStopsForEveryAuthoritativeNonRingingState() {
        listOf(
            AuthenticatedCallStatus.ACCEPTED,
            AuthenticatedCallStatus.CONNECTED,
            AuthenticatedCallStatus.DECLINED,
            AuthenticatedCallStatus.CANCELLED,
            AuthenticatedCallStatus.MISSED,
            AuthenticatedCallStatus.FAILED,
            AuthenticatedCallStatus.COMPLETED,
        ).forEach { status ->
            val output = RecordingCallSoundOutput()
            val controller = OutgoingRingbackController(output)
            controller.update(session(AuthenticatedCallStatus.RINGING), callerId, callId, true, true)
            controller.update(session(status), callerId, callId, true, true)
            assertEquals(status.name, listOf("start:OUTGOING_RINGBACK:$callId", "stop:OUTGOING_RINGBACK"), output.events)
        }
    }

    @Test
    fun outgoingRingbackStopsOnDisposalAndIgnoresStateWhenUiIsNotForeground() {
        val output = RecordingCallSoundOutput()
        val controller = OutgoingRingbackController(output)
        val ringing = session(AuthenticatedCallStatus.RINGING)
        controller.update(ringing, callerId, callId, true, foreground = false)
        assertTrue(output.events.isEmpty())
        controller.update(ringing, callerId, callId, true, foreground = true)
        controller.stopForCall(callId)
        controller.stop()

        assertEquals(listOf("start:OUTGOING_RINGBACK:$callId", "stop:OUTGOING_RINGBACK"), output.events)
    }

    @Test
    fun outgoingCallIdChangeStopsOldRingbackBeforeStartingCurrentCall() {
        val output = RecordingCallSoundOutput()
        val controller = OutgoingRingbackController(output)
        val nextCallId = UUID.fromString("e43fc877-3c0f-4fcf-8e2b-6b85fe3f0011")
        controller.update(session(AuthenticatedCallStatus.RINGING), callerId, callId, true, true)
        controller.update(
            session(AuthenticatedCallStatus.RINGING).copy(callId = nextCallId),
            callerId,
            nextCallId,
            true,
            true,
        )

        assertEquals(
            listOf(
                "start:OUTGOING_RINGBACK:$callId",
                "stop:OUTGOING_RINGBACK",
                "start:OUTGOING_RINGBACK:$nextCallId",
            ),
            output.events,
        )
    }

    @Test
    fun incomingRingtoneStartsForLegitimateIncomingRingingAndStopsOnLifecycleStates() {
        listOf(
            AuthenticatedCallStatus.ACCEPTED,
            AuthenticatedCallStatus.CONNECTED,
            AuthenticatedCallStatus.DECLINED,
            AuthenticatedCallStatus.CANCELLED,
            AuthenticatedCallStatus.MISSED,
            AuthenticatedCallStatus.FAILED,
            AuthenticatedCallStatus.COMPLETED,
        ).forEach { status ->
            val output = RecordingCallSoundOutput()
            val controller = IncomingRingtoneController(output)
            val ringing = session(AuthenticatedCallStatus.RINGING)
            controller.update(ringing, calleeId, callId, signedIn = true, foreground = true)
            controller.update(ringing.copy(status = status), calleeId, callId, signedIn = true, foreground = true)
            assertEquals(status.name, listOf("start:INCOMING_RINGTONE:$callId", "stop:INCOMING_RINGTONE"), output.events)
        }
    }

    @Test
    fun incomingRingtoneRejectsWrongRoleAndUnauthenticatedCallsAndDoesNotDuplicate() {
        val output = RecordingCallSoundOutput()
        val controller = IncomingRingtoneController(output)
        val ringing = session(AuthenticatedCallStatus.RINGING)
        controller.update(ringing, callerId, callId, signedIn = true, foreground = true)
        controller.update(ringing, calleeId, callId, signedIn = false, foreground = true)
        controller.update(ringing, calleeId, callId, signedIn = true, foreground = true)
        controller.update(ringing, calleeId, callId, signedIn = true, foreground = true)

        assertEquals(listOf("start:INCOMING_RINGTONE:$callId"), output.events)
        controller.stopForCall(callId)
        assertEquals("stop:INCOMING_RINGTONE", output.events.last())
    }

    @Test
    fun autoAnswerRejectsDisabledUntrustedMalformedAndSignedOutCallers() {
        val scheduler = TestAutoAnswerScheduler()
        val controller = ForegroundAutoAnswerController(scheduler)
        var answers = 0
        val valid = policyInput()

        controller.update(callId, valid.copy(featureEnabled = false)) { answers++ }
        controller.update(callId, valid.copy(trustedUserIds = emptySet())) { answers++ }
        controller.update(callId, valid.copy(incomingCallerUserId = "not-a-uuid")) { answers++ }
        controller.update(callId, valid.copy(incomingCallerUserId = null)) { answers++ }
        controller.update(callId, valid.copy(routedCallerUserId = null)) { answers++ }
        controller.update(callId, valid.copy(signedIn = false)) { answers++ }
        scheduler.advanceBy(10_000)

        assertEquals(0, answers)
    }

    @Test
    fun trustedZeroDelayInvokesAnswerImmediatelyAndOnlyOnce() {
        val controller = ForegroundAutoAnswerController(TestAutoAnswerScheduler())
        var answers = 0
        val input = policyInput(AutoAnswerDelay.ZERO)
        controller.update(callId, input) { answers++ }
        controller.update(callId, input) { answers++ }

        assertEquals(1, answers)
    }

    @Test
    fun trustedTwoAndFiveSecondDelaysAnswerOnlyAtConfiguredTime() {
        listOf(AutoAnswerDelay.TWO to 2_000L, AutoAnswerDelay.FIVE to 5_000L).forEach { (delay, millis) ->
            val scheduler = TestAutoAnswerScheduler()
            val controller = ForegroundAutoAnswerController(scheduler)
            var answers = 0
            controller.update(callId, policyInput(delay)) { answers++ }
            scheduler.advanceBy(millis - 1)
            assertEquals("$delay before due", 0, answers)
            scheduler.advanceBy(1)
            assertEquals("$delay at due", 1, answers)
        }
    }

    @Test
    fun timerReevaluatesPolicyAndCancelsWhenEligibilityIsRemoved() {
        val scheduler = TestAutoAnswerScheduler()
        val controller = ForegroundAutoAnswerController(scheduler)
        var answers = 0
        controller.update(callId, policyInput()) { answers++ }
        controller.update(callId, policyInput().copy(featureEnabled = false)) { answers++ }
        scheduler.advanceBy(2_000)

        assertEquals(0, answers)
    }

    @Test
    fun callChangeManualActionsTerminalStateSignOutAndTrustRemovalCancelTimer() {
        val cancellationCases: List<(ForegroundAutoAnswerController) -> Unit> = listOf(
            { controller -> controller.update(UUID.randomUUID(), policyInput()) {} },
            { controller -> controller.onManualAction(callId, ManualCallAction.ANSWERED) },
            { controller -> controller.onManualAction(callId, ManualCallAction.DECLINED) },
            { controller -> controller.update(callId, policyInput().copy(callState = AutoAnswerCallState.ENDED)) {} },
            { controller -> controller.update(callId, policyInput().copy(signedIn = false)) {} },
            { controller -> controller.update(callId, policyInput().copy(trustedUserIds = emptySet())) {} },
        )
        cancellationCases.forEach { cancel ->
            val scheduler = TestAutoAnswerScheduler()
            val controller = ForegroundAutoAnswerController(scheduler)
            var answers = 0
            controller.update(callId, policyInput()) { answers++ }
            cancel(controller)
            scheduler.advanceBy(10_000)
            assertEquals(0, answers)
        }
    }

    @Test
    fun delayChangeInvalidatesOldTimerAndStartsAnewWindow() {
        val scheduler = TestAutoAnswerScheduler()
        val controller = ForegroundAutoAnswerController(scheduler)
        var answers = 0
        controller.update(callId, policyInput(AutoAnswerDelay.FIVE)) { answers++ }
        scheduler.advanceBy(2_000)
        controller.update(callId, policyInput(AutoAnswerDelay.TWO)) { answers++ }
        scheduler.advanceBy(1_999)
        assertEquals(0, answers)
        scheduler.advanceBy(1)

        assertEquals(1, answers)
    }

    @Test
    fun callerIdentityChangeRestartsEligibilityWindow() {
        val scheduler = TestAutoAnswerScheduler()
        val controller = ForegroundAutoAnswerController(scheduler)
        val replacementCallerId = UUID.fromString("e43fc877-3c0f-4fcf-8e2b-6b85fe3f0010")
        var answers = 0
        controller.update(callId, policyInput()) { answers++ }
        scheduler.advanceBy(1_000)
        controller.update(
            callId,
            policyInput().copy(
                incomingCallerUserId = replacementCallerId.toString(),
                routedCallerUserId = replacementCallerId,
                trustedUserIds = setOf(replacementCallerId),
            ),
        ) { answers++ }
        scheduler.advanceBy(1_000)
        assertEquals(0, answers)
        scheduler.advanceBy(1_000)

        assertEquals(1, answers)
    }

    @Test
    fun localAuthenticatedUserChangeCancelsPendingAnswer() {
        val scheduler = TestAutoAnswerScheduler()
        val controller = ForegroundAutoAnswerController(scheduler)
        var answers = 0
        controller.update(callId, policyInput()) { answers++ }
        controller.onAuthenticatedUserChanged(UUID.randomUUID())
        scheduler.advanceBy(10_000)

        assertEquals(0, answers)
    }

    @Test
    fun timerRaceCannotInvokeAnswerMoreThanOnce() {
        val scheduler = TestAutoAnswerScheduler()
        val controller = ForegroundAutoAnswerController(scheduler)
        var answers = 0
        controller.update(callId, policyInput()) { answers++ }
        scheduler.advanceBy(2_000)
        controller.update(callId, policyInput()) { answers++ }
        controller.onManualAction(callId, ManualCallAction.ANSWERED)

        assertEquals(1, answers)
    }

    @Test
    fun concurrentManualActionAndTimerCannotInvokeAnswerTwice() {
        repeat(20) {
            val scheduler = TestAutoAnswerScheduler()
            val controller = ForegroundAutoAnswerController(scheduler)
            val answers = AtomicInteger()
            controller.update(callId, policyInput()) { answers.incrementAndGet() }
            val start = CountDownLatch(1)
            val timer = Thread {
                check(start.await(5, TimeUnit.SECONDS))
                scheduler.dueAction().invoke()
            }
            val manualAction = Thread {
                check(start.await(5, TimeUnit.SECONDS))
                controller.onManualAction(callId, ManualCallAction.ANSWERED)
            }
            timer.start()
            manualAction.start()
            start.countDown()
            timer.join(5_000)
            manualAction.join(5_000)

            assertFalse(timer.isAlive)
            assertFalse(manualAction.isAlive)
            assertTrue(answers.get() <= 1)
        }
    }

    @Test
    fun controllerDisposalCancelsPendingAnswer() {
        val scheduler = TestAutoAnswerScheduler()
        val controller = ForegroundAutoAnswerController(scheduler)
        var answers = 0
        controller.update(callId, policyInput()) { answers++ }
        controller.cancel()
        scheduler.advanceBy(2_000)

        assertFalse(answers > 0)
    }

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

    private fun policyInput(delay: AutoAnswerDelay = AutoAnswerDelay.TWO) = AutoAnswerPolicyInput(
        featureEnabled = true,
        signedIn = true,
        localAuthenticatedUserId = calleeId,
        incomingCallerUserId = callerId.toString(),
        routedCallerUserId = callerId,
        trustedUserIds = setOf(callerId),
        delay = delay,
        callState = AutoAnswerCallState.RINGING,
    )

    private class RecordingCallSoundOutput : CallSoundOutput {
        val events = mutableListOf<String>()

        override fun start(kind: CallSoundKind, callId: UUID) {
            events += "start:$kind:$callId"
        }

        override fun stop(kind: CallSoundKind) {
            events += "stop:$kind"
        }

        override fun release() = Unit
    }

    private class TestAutoAnswerScheduler : AutoAnswerScheduler {
        private data class Task(val dueAt: Long, val action: () -> Unit, var cancelled: Boolean = false)

        private val tasks = mutableListOf<Task>()
        private var now = 0L

        override fun schedule(delayMillis: Long, action: () -> Unit): ScheduledAutoAnswer {
            val task = Task(now + delayMillis, action)
            tasks += task
            return ScheduledAutoAnswer { task.cancelled = true }
        }

        fun advanceBy(millis: Long) {
            now += millis
            tasks.filter { !it.cancelled && it.dueAt <= now }
                .sortedBy(Task::dueAt)
                .forEach { task ->
                    task.cancelled = true
                    task.action()
                }
        }

            fun dueAction(): () -> Unit = tasks.single().action
    }
}