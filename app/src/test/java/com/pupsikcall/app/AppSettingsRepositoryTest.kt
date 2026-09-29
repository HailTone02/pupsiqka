package com.pupsikcall.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AppSettingsRepositoryTest {
    @Test
    fun defaultAndInvalidStoredValuesDecodeSafely() {
        val defaults = AppSettingsCodec.decode(StoredAppSettings())
        assertEquals(AppearanceMode.SYSTEM, defaults.appearance)
        assertFalse(defaults.autoAnswerEnabled)
        assertEquals(AutoAnswerDelay.TWO, defaults.autoAnswerDelay)
        assertTrue(defaults.trustedAutoAnswerUserIds.isEmpty())

        val invalid = AppSettingsCodec.decode(
            StoredAppSettings(appearance = "neon", autoAnswerEnabled = true, autoAnswerDelaySeconds = 3),
        )
        assertEquals(AppearanceMode.SYSTEM, invalid.appearance)
        assertEquals(AutoAnswerDelay.TWO, invalid.autoAnswerDelay)
        assertFalse(invalid.trustedAutoAnswerUserIds.isNotEmpty())
    }

    @Test
    fun appearanceValuesRoundTripAndSystemIsFallback() {
        AppearanceMode.entries.forEach { appearance ->
            val decoded = AppSettingsCodec.decode(StoredAppSettings(appearance = appearance.preferenceValue))
            assertEquals(appearance, decoded.appearance)
        }
        assertEquals(AppearanceMode.SYSTEM, AppearanceMode.fromPreference("unknown"))
    }

    @Test
    fun autoAnswerDefaultsOffAndDelayAcceptsOnlySupportedValues() {
        assertFalse(AppSettings().autoAnswerEnabled)
        assertEquals(AutoAnswerDelay.TWO, AppSettings().autoAnswerDelay)
        assertEquals(AutoAnswerDelay.ZERO, AutoAnswerDelay.fromSeconds(0))
        assertEquals(AutoAnswerDelay.TWO, AutoAnswerDelay.fromSeconds(2))
        assertEquals(AutoAnswerDelay.FIVE, AutoAnswerDelay.fromSeconds(5))
        assertEquals(AutoAnswerDelay.TWO, AutoAnswerDelay.fromSeconds(-1))
        assertEquals(AutoAnswerDelay.TWO, AutoAnswerDelay.fromSeconds(4))
    }

    @Test
    fun trustedUuidValuesPersistDeduplicateAndRejectMalformedEntries() {
        val first = uuid(1)
        val second = uuid(2)
        val stored = AppSettingsCodec.encode(AppSettings(trustedAutoAnswerUserIds = setOf(first, second)))
        val decoded = AppSettingsCodec.decode(stored.copy(trustedAutoAnswerUserIds = stored.trustedAutoAnswerUserIds + "not-a-uuid"))

        assertEquals(setOf(first, second), decoded.trustedAutoAnswerUserIds)
        assertEquals(2, stored.trustedAutoAnswerUserIds.size)
    }

    @Test
    fun settingsSurviveRepositoryRecreationAndSignedOutClearsTrust() = runBlocking {
        val persistence = FakeSettingsPersistence()
        val repository = AppSettingsRepository(persistence, Dispatchers.Unconfined)
        repository.setAppearance(AppearanceMode.DARK)
        repository.setAutoAnswerEnabled(true)
        repository.setAutoAnswerDelay(AutoAnswerDelay.FIVE)
        assertTrue(repository.addTrustedAuthenticatedUser(uuid(2), uuid(1), uuid(2)))
        repository.close()

        val restored = AppSettingsRepository(persistence, Dispatchers.Unconfined)
        val restoredSettings = (restored.state.value as AppSettingsState.Ready).settings
        assertEquals(AppearanceMode.DARK, restoredSettings.appearance)
        assertTrue(restoredSettings.autoAnswerEnabled)
        assertEquals(AutoAnswerDelay.FIVE, restoredSettings.autoAnswerDelay)
        assertEquals(setOf(uuid(2)), restoredSettings.trustedAutoAnswerUserIds)

        restored.clearAutoAnswerForSignedOut()
        val signedOutSettings = (restored.state.value as AppSettingsState.Ready).settings
        assertEquals(AppearanceMode.DARK, signedOutSettings.appearance)
        assertEquals(AutoAnswerDelay.FIVE, signedOutSettings.autoAnswerDelay)
        assertFalse(signedOutSettings.autoAnswerEnabled)
        assertTrue(signedOutSettings.trustedAutoAnswerUserIds.isEmpty())
        restored.close()
    }

    @Test
    fun addingTrustRequiresMatchedAuthenticatedRecipientAndRejectsSelf() = runBlocking {
        val repository = AppSettingsRepository(FakeSettingsPersistence(), Dispatchers.Unconfined)
        assertFalse(repository.addTrustedAuthenticatedUser(uuid(2), uuid(1), null))
        assertFalse(repository.addTrustedAuthenticatedUser(uuid(2), uuid(1), uuid(3)))
        assertFalse(repository.addTrustedAuthenticatedUser(uuid(1), uuid(1), uuid(1)))
        assertTrue(repository.addTrustedAuthenticatedUser(uuid(2), uuid(1), uuid(2)))
        assertTrue(repository.addTrustedAuthenticatedUser(uuid(2), uuid(1), uuid(2)))
        assertEquals(setOf(uuid(2)), (repository.state.value as AppSettingsState.Ready).settings.trustedAutoAnswerUserIds)
        repository.close()
    }

    private class FakeSettingsPersistence(initial: AppSettings = AppSettings()) : AppSettingsPersistence {
        private val mutableSettings = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = mutableSettings

        override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings =
            transform(mutableSettings.value).also { mutableSettings.value = it }
    }

    companion object {
        private fun uuid(value: Int) = UUID.fromString(
            "00000000-0000-4000-8000-${value.toString().padStart(12, '0')}",
        )
    }
}

class AutoAnswerPolicyTest {
    @Test
    fun disabledUntrustedMissingMalformedStaleAndSignedOutCallersRequireManualAnswer() {
        assertManual(input(enabled = false))
        assertManual(input(enabled = true, trusted = emptySet()))
        assertManual(input(enabled = true, incomingId = null))
        assertManual(input(enabled = true, incomingId = "bad-uuid"))
        assertManual(input(enabled = true, routedId = uuid(3)))
        assertManual(input(enabled = true, signedIn = false))
        assertManual(input(enabled = true, callState = AutoAnswerCallState.ACCEPTED))
    }

    @Test
    fun trustedAuthenticatedRingingCallerIsEligibleOnlyAfterConfiguredDelay() {
        listOf(
            AutoAnswerDelay.ZERO to 0,
            AutoAnswerDelay.TWO to 2,
            AutoAnswerDelay.FIVE to 5,
        ).forEach { (delay, seconds) ->
            val decision = AutoAnswerPolicy.evaluate(input(enabled = true, delay = delay))
            assertTrue(decision is AutoAnswerDecision.EligibleAfter)
            val eligible = decision as AutoAnswerDecision.EligibleAfter
            assertEquals(seconds, eligible.delaySeconds)
            assertTrue(eligible.isReady(seconds))
            if (seconds > 0) assertFalse(eligible.isReady(seconds - 1))
        }
    }

    @Test
    fun timerCancelsWhenCallerChangesCallEndsManualActionOrSessionSignsOut() {
        val callId = uuid(10)
        val callerId = uuid(20)
        fun cancelled(
            currentCallId: UUID? = callId,
            currentCallerId: UUID? = callerId,
            signedIn: Boolean = true,
            callState: AutoAnswerCallState = AutoAnswerCallState.RINGING,
            action: ManualCallAction = ManualCallAction.NONE,
        ) = AutoAnswerPolicy.shouldCancelTimer(
            expectedCallId = callId,
            expectedCallerUserId = callerId,
            currentCallId = currentCallId,
            currentCallerUserId = currentCallerId,
            signedIn = signedIn,
            callState = callState,
            manualAction = action,
        )

        assertTrue(cancelled(currentCallerId = uuid(21)))
        assertTrue(cancelled(callState = AutoAnswerCallState.ENDED))
        assertTrue(cancelled(callState = AutoAnswerCallState.DECLINED, action = ManualCallAction.DECLINED))
        assertTrue(cancelled(callState = AutoAnswerCallState.ACCEPTED, action = ManualCallAction.ANSWERED))
        assertTrue(cancelled(signedIn = false))
        assertFalse(cancelled())
    }

    private fun assertManual(policyInput: AutoAnswerPolicyInput) {
        assertEquals(AutoAnswerDecision.ManualAnswerRequired, AutoAnswerPolicy.evaluate(policyInput))
    }

    private fun input(
        enabled: Boolean,
        signedIn: Boolean = true,
        incomingId: String? = uuid(2).toString(),
        routedId: UUID? = uuid(2),
        trusted: Set<UUID> = setOf(uuid(2)),
        delay: AutoAnswerDelay = AutoAnswerDelay.TWO,
        callState: AutoAnswerCallState = AutoAnswerCallState.RINGING,
    ) = AutoAnswerPolicyInput(
        featureEnabled = enabled,
        signedIn = signedIn,
        incomingCallerUserId = incomingId,
        routedCallerUserId = routedId,
        trustedUserIds = trusted,
        delay = delay,
        callState = callState,
    )

    companion object {
        private fun uuid(value: Int) = UUID.fromString(
            "00000000-0000-4000-8000-${value.toString().padStart(12, '0')}",
        )
    }
}
