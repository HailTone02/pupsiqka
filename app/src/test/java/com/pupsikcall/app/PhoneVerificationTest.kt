package com.pupsikcall.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneVerificationTest {
    @Test
    fun e164NormalizationRequiresInternationalPrefixAndValidLength() {
        assertEquals("+12025550123", normalizeE164Phone(" +1 (202) 555-0123 "))
        assertEquals("+12025550123", normalizeE164Phone("+١ ٢٠٢ ٥٥٥ ٠١٢٣"))
        assertNull(normalizeE164Phone("202-555-0123"))
        assertNull(normalizeE164Phone("+01234567"))
        assertNull(normalizeE164Phone("+1234567"))
        assertNull(normalizeE164Phone("+1234567890123456"))
        assertNull(normalizeE164Phone("+1 800 FLOWERS"))
    }

    @Test
    fun sameAccountRequestAndConfirmedAuthSnapshotCompleteVerification() = runBlocking {
        val gateway = FakePhoneIdentityGateway(initialIdentity())
        val controller = PhoneVerificationController(gateway)

        controller.load()
        assertEquals(PhoneVerificationState.NoPhone, controller.state.value)
        controller.requestVerification("+1 (202) 555-0123")
        assertEquals(PhoneVerificationState.AwaitingOtp, controller.state.value)
        controller.verify("123456")

        assertEquals(PhoneVerificationState.Verified, controller.state.value)
        assertEquals("same-auth-uuid", gateway.requestUserIds.single())
        assertEquals("same-auth-uuid", gateway.verifiedUserIds.single())
        assertEquals("+12025550123", gateway.requestedPhones.single())
    }

    @Test
    fun requestFailureDoesNotExposePhoneOrServerError() = runBlocking {
        val gateway = FakePhoneIdentityGateway(initialIdentity()).apply {
            requestFailure = IllegalStateException("phone=+12025550123 otp=123456 access_token=secret")
        }
        val controller = PhoneVerificationController(gateway)

        controller.load()
        controller.requestVerification("+12025550123")

        assertEquals(PhoneVerificationState.Error(PhoneVerificationError.REQUEST_FAILED), controller.state.value)
        assertFalse(controller.state.value.toString().contains("+12025550123"))
        assertFalse(controller.state.value.toString().contains("secret"))
    }

    @Test
    fun authIdentityStringificationRedactsPhoneAndPendingPhone() {
        val identity = initialIdentity().copy(
            phone = "+12025550123",
            pendingPhone = "+12025550124",
        )

        assertFalse(identity.toString().contains("+12025550123"))
        assertFalse(identity.toString().contains("+12025550124"))
    }

    @Test
    fun verificationFailureIsSanitizedAndCanRepresentExpiredOrRateLimitedCode() = runBlocking {
        val gateway = FakePhoneIdentityGateway(initialIdentity()).apply {
            verifyFailure = IllegalStateException("otp=123456 phone=+12025550123")
        }
        val controller = PhoneVerificationController(gateway)
        controller.load()
        controller.requestVerification("+12025550123")
        controller.verify("123456")

        assertEquals(
            PhoneVerificationState.Error(PhoneVerificationError.CODE_INVALID_EXPIRED_OR_RATE_LIMITED),
            controller.state.value,
        )
        assertFalse(controller.state.value.toString().contains("123456"))
        assertFalse(controller.state.value.toString().contains("+12025550123"))
    }

    @Test
    fun successRequiresConfirmedPhoneOnSameAuthIdentity() = runBlocking {
        val gateway = FakePhoneIdentityGateway(initialIdentity()).apply {
            verifyResult = PhoneAuthIdentity("same-auth-uuid", "+12025550123", false, null, false)
        }
        val controller = PhoneVerificationController(gateway)
        controller.load()
        controller.requestVerification("+12025550123")
        controller.verify("123456")

        assertEquals(PhoneVerificationState.Error(PhoneVerificationError.CONFIRMATION_MISSING), controller.state.value)
    }

    @Test
    fun changedAuthUuidCannotBecomeVerified() = runBlocking {
        val gateway = FakePhoneIdentityGateway(initialIdentity()).apply {
            verifyResult = PhoneAuthIdentity("different-auth-uuid", "+12025550123", true, null, false)
        }
        val controller = PhoneVerificationController(gateway)
        controller.load()
        controller.requestVerification("+12025550123")
        controller.verify("123456")

        assertEquals(PhoneVerificationState.Error(PhoneVerificationError.ACCOUNT_CHANGED), controller.state.value)
    }

    @Test
    fun missingSessionIsReportedWithoutStartingPhoneChange() = runBlocking {
        val controller = PhoneVerificationController(FakePhoneIdentityGateway(null))
        controller.load()
        controller.requestVerification("+12025550123")

        assertEquals(PhoneVerificationState.Error(PhoneVerificationError.SESSION_LOST), controller.state.value)
    }

    @Test
    fun authStatusNetworkFailureIsNotMisreportedAsSessionLoss() = runBlocking {
        val gateway = FakePhoneIdentityGateway(initialIdentity()).apply {
            currentUserFailure = IllegalStateException("network failure phone=+12025550123")
        }
        val controller = PhoneVerificationController(gateway)

        controller.load()

        assertEquals(PhoneVerificationState.Error(PhoneVerificationError.STATUS_CHECK_FAILED), controller.state.value)
        assertFalse(controller.state.value.toString().contains("+12025550123"))
    }

    @Test
    fun cancellationDuringRequestReturnsToOtpEntryWithoutPersistingCode() = runBlocking {
        val gateway = FakePhoneIdentityGateway(initialIdentity()).apply {
            requestFailure = kotlinx.coroutines.CancellationException("cancelled")
        }
        val controller = PhoneVerificationController(gateway)
        controller.load()

        try {
            controller.requestVerification("+12025550123")
        } catch (_: kotlinx.coroutines.CancellationException) {
            Unit
        }

        assertEquals(PhoneVerificationState.AwaitingOtp, controller.state.value)
        assertFalse(controller.state.value.toString().contains("+12025550123"))
    }

    @Test
    fun malformedPhoneNeverReachesAuthGateway() = runBlocking {
        val gateway = FakePhoneIdentityGateway(initialIdentity())
        val controller = PhoneVerificationController(gateway)

        controller.requestVerification("555-0100")

        assertEquals(PhoneVerificationState.Error(PhoneVerificationError.INVALID_PHONE), controller.state.value)
        assertTrue(gateway.requestedPhones.isEmpty())
    }

    @Test
    fun alreadyVerifiedSameNumberDoesNotRequestAnotherOtp() = runBlocking {
        val gateway = FakePhoneIdentityGateway(initialIdentity().copy(phone = "+12025550123", phoneConfirmed = true))
        val controller = PhoneVerificationController(gateway)
        controller.load()
        controller.requestVerification("+1 (202) 555-0123")

        assertEquals(PhoneVerificationState.Verified, controller.state.value)
        assertTrue(gateway.requestedPhones.isEmpty())
    }

    @Test
    fun pendingChangeOtpTakesPrecedenceOverPreviouslyVerifiedPhoneAfterReload() = runBlocking {
        val gateway = FakePhoneIdentityGateway(
            initialIdentity().copy(
                phone = "+12025550123",
                phoneConfirmed = true,
                pendingPhone = "+442071838750",
                phoneChangeSent = true,
            ),
        )
        val controller = PhoneVerificationController(gateway)

        controller.load()

        assertEquals(PhoneVerificationState.AwaitingOtp, controller.state.value)
        controller.verify("123456")
        assertEquals(PhoneVerificationState.Verified, controller.state.value)
        assertEquals("same-auth-uuid", gateway.verifiedUserIds.single())
    }

    private fun initialIdentity() = PhoneAuthIdentity(
        userId = "same-auth-uuid",
        phone = null,
        phoneConfirmed = false,
        pendingPhone = null,
        phoneChangeSent = false,
    )

    private class FakePhoneIdentityGateway(initial: PhoneAuthIdentity?) : PhoneIdentityGateway {
        var identity = initial
        var currentUserFailure: Exception? = null
        var requestFailure: Exception? = null
        var verifyFailure: Exception? = null
        var verifyResult: PhoneAuthIdentity? = null
        val requestUserIds = mutableListOf<String>()
        val verifiedUserIds = mutableListOf<String>()
        val requestedPhones = mutableListOf<String>()

        override suspend fun currentUser(): PhoneAuthIdentity? {
            currentUserFailure?.let { throw it }
            return identity
        }

        override suspend fun requestPhoneChange(userId: String, e164Phone: String): PhoneAuthIdentity {
            requestFailure?.let { throw it }
            requestUserIds += userId
            requestedPhones += e164Phone
            return PhoneAuthIdentity(userId, null, false, e164Phone, true).also { identity = it }
        }

        override suspend fun resendPhoneChange(userId: String, e164Phone: String) = Unit

        override suspend fun verifyPhoneChange(userId: String, e164Phone: String, otp: String): PhoneAuthIdentity {
            verifyFailure?.let { throw it }
            verifiedUserIds += userId
            return (verifyResult ?: PhoneAuthIdentity(userId, e164Phone, true, null, false)).also { identity = it }
        }
    }
}