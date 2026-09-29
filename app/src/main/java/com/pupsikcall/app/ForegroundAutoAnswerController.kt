package com.pupsikcall.app

import android.os.Handler
import android.os.Looper
import java.util.UUID

internal fun interface ScheduledAutoAnswer {
    fun cancel()
}

internal fun interface AutoAnswerScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): ScheduledAutoAnswer
}

internal class HandlerAutoAnswerScheduler : AutoAnswerScheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun schedule(delayMillis: Long, action: () -> Unit): ScheduledAutoAnswer {
        val runnable = Runnable(action)
        handler.postDelayed(runnable, delayMillis)
        return ScheduledAutoAnswer { handler.removeCallbacks(runnable) }
    }
}

internal class ForegroundAutoAnswerController(private val scheduler: AutoAnswerScheduler) {
    private data class TimerKey(val callId: UUID, val callerUserId: UUID, val delay: AutoAnswerDelay)

    private var latestCallId: UUID? = null
    private var latestInput: AutoAnswerPolicyInput? = null
    private var latestAnswerAction: (() -> Unit)? = null
    private var pendingKey: TimerKey? = null
    private var pendingTask: ScheduledAutoAnswer? = null
    private var consumedCallId: UUID? = null
    private var generation = 0L

    @Synchronized
    fun update(callId: UUID?, input: AutoAnswerPolicyInput, answerAction: () -> Unit) {
        latestCallId = callId
        latestInput = input
        latestAnswerAction = answerAction
        if (consumedCallId != callId) consumedCallId = null

        val decision = AutoAnswerPolicy.evaluate(input) as? AutoAnswerDecision.EligibleAfter
        val callerId = input.routedCallerUserId
        if (callId == null || callerId == null || decision == null) {
            cancelPending()
            return
        }
        if (consumedCallId == callId) return

        val key = TimerKey(callId, callerId, input.delay)
        if (pendingKey == key) return
        cancelPending()
        pendingKey = key
        if (decision.delaySeconds == 0) {
            pendingKey = null
            consumedCallId = callId
            latestAnswerAction?.invoke()
            return
        }
        val scheduledGeneration = generation
        pendingTask = scheduler.schedule(decision.delaySeconds * 1_000L) {
            completeTimer(key, scheduledGeneration)
        }
    }

    @Synchronized
    fun onManualAction(callId: UUID?, action: ManualCallAction) {
        cancelPending()
        if (callId != null && action != ManualCallAction.NONE) consumedCallId = callId
    }

    @Synchronized
    fun cancel() {
        cancelPending()
        latestCallId = null
        latestInput = null
        latestAnswerAction = null
        consumedCallId = null
    }

    private fun completeTimer(key: TimerKey, scheduledGeneration: Long) {
        val action = synchronized(this) {
            if (generation != scheduledGeneration || pendingKey != key || latestCallId != key.callId) return
            pendingKey = null
            pendingTask = null
            val currentInput = latestInput ?: return
            val decision = AutoAnswerPolicy.evaluate(currentInput) as? AutoAnswerDecision.EligibleAfter
            if (currentInput.routedCallerUserId != key.callerUserId || currentInput.delay != key.delay ||
                decision?.delaySeconds != key.delay.seconds || consumedCallId == key.callId
            ) return
            consumedCallId = key.callId
            latestAnswerAction
        }
        action?.invoke()
    }

    private fun cancelPending() {
        generation++
        pendingTask?.cancel()
        pendingTask = null
        pendingKey = null
    }
}