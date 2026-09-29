package com.pupsikcall.app

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class PhoneAuthIdentity(
    val userId: String,
    val phone: String?,
    val phoneConfirmed: Boolean,
    val pendingPhone: String?,
    val phoneChangeSent: Boolean,
) {
    override fun toString(): String =
        "PhoneAuthIdentity(phonePresent=${phone != null}, phoneConfirmed=$phoneConfirmed, pendingPhonePresent=${pendingPhone != null}, phoneChangeSent=$phoneChangeSent)"
}

internal interface PhoneIdentityGateway {
    suspend fun currentUser(): PhoneAuthIdentity?
    suspend fun requestPhoneChange(userId: String, e164Phone: String): PhoneAuthIdentity
    suspend fun resendPhoneChange(userId: String, e164Phone: String)
    suspend fun verifyPhoneChange(userId: String, e164Phone: String, otp: String): PhoneAuthIdentity
}

internal enum class PhoneVerificationError {
    INVALID_PHONE,
    REQUEST_FAILED,
    CODE_INVALID_EXPIRED_OR_RATE_LIMITED,
    STATUS_CHECK_FAILED,
    SESSION_LOST,
    ACCOUNT_CHANGED,
    CONFIRMATION_MISSING,
}

internal sealed interface PhoneVerificationState {
    data object Checking : PhoneVerificationState
    data object NoPhone : PhoneVerificationState
    data object RequestingOtp : PhoneVerificationState
    data object AwaitingOtp : PhoneVerificationState
    data object Verifying : PhoneVerificationState
    data object Verified : PhoneVerificationState
    data class Error(val reason: PhoneVerificationError) : PhoneVerificationState
}

internal fun normalizeE164Phone(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty() || trimmed.length > 64 || !trimmed.startsWith('+')) return null
    if (trimmed.drop(1).any { character ->
            Character.digit(character, 10) < 0 && !character.isWhitespace() && character !in "().-"
        }
    ) return null

    return try {
        val utility = PhoneNumberUtil.getInstance()
        val parsed = utility.parse(trimmed, "ZZ")
        if (parsed.hasExtension() || !utility.isValidNumber(parsed)) return null
        utility.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
    } catch (_: NumberParseException) {
        null
    }
}

internal class PhoneVerificationController(
    private val gateway: PhoneIdentityGateway,
) {
    private val mutableState = MutableStateFlow<PhoneVerificationState>(PhoneVerificationState.Checking)
    val state: StateFlow<PhoneVerificationState> = mutableState.asStateFlow()

    private var currentUserId: String? = null
    private var pendingPhone: String? = null

    suspend fun load() {
        mutableState.value = PhoneVerificationState.Checking
        try {
            val identity = gateway.currentUser()
            if (identity == null) {
                currentUserId = null
                pendingPhone = null
                mutableState.value = PhoneVerificationState.Error(PhoneVerificationError.SESSION_LOST)
                return
            }
            currentUserId = identity.userId
            val verifiedPhone = identity.phone?.let(::normalizeE164Phone)
            val normalizedPendingPhone = identity.pendingPhone?.let(::normalizeE164Phone)
            if (identity.phoneChangeSent && normalizedPendingPhone != null) {
                pendingPhone = normalizedPendingPhone
                mutableState.value = PhoneVerificationState.AwaitingOtp
            } else if (identity.phoneConfirmed && verifiedPhone != null) {
                pendingPhone = null
                mutableState.value = PhoneVerificationState.Verified
            } else {
                pendingPhone = null
                mutableState.value = PhoneVerificationState.NoPhone
            }
        } catch (cancelled: CancellationException) {
            mutableState.value = PhoneVerificationState.Error(PhoneVerificationError.SESSION_LOST)
            throw cancelled
        } catch (_: Exception) {
            currentUserId = null
            pendingPhone = null
            mutableState.value = PhoneVerificationState.Error(PhoneVerificationError.STATUS_CHECK_FAILED)
        }
    }

    suspend fun requestVerification(input: String) {
        val e164Phone = normalizeE164Phone(input)
        if (e164Phone == null) {
            mutableState.value = PhoneVerificationState.Error(PhoneVerificationError.INVALID_PHONE)
            return
        }

        mutableState.value = PhoneVerificationState.RequestingOtp
        try {
            val identity = gateway.currentUser()
            if (identity == null) {
                mutableState.value = PhoneVerificationState.Error(PhoneVerificationError.SESSION_LOST)
                return
            }
            currentUserId = identity.userId
            if (identity.phoneConfirmed && identity.phone?.let(::normalizeE164Phone) == e164Phone) {
                pendingPhone = null
                mutableState.value = PhoneVerificationState.Verified
                return
            }

            pendingPhone = e164Phone
            val updatedIdentity = gateway.requestPhoneChange(identity.userId, e164Phone)
            if (updatedIdentity.userId != identity.userId) {
                fail(PhoneVerificationError.ACCOUNT_CHANGED)
            } else if (isConfirmedFor(updatedIdentity, identity.userId, e164Phone)) {
                pendingPhone = null
                mutableState.value = PhoneVerificationState.Verified
            } else if (updatedIdentity.pendingPhone?.let(::normalizeE164Phone) == e164Phone && updatedIdentity.phoneChangeSent) {
                mutableState.value = PhoneVerificationState.AwaitingOtp
            } else {
                fail(PhoneVerificationError.REQUEST_FAILED)
            }
        } catch (cancelled: CancellationException) {
            restoreAfterCancellation()
            throw cancelled
        } catch (_: Exception) {
            fail(PhoneVerificationError.REQUEST_FAILED)
        }
    }

    suspend fun resendVerification() {
        val userId = currentUserId
        val e164Phone = pendingPhone
        if (userId == null) {
            fail(PhoneVerificationError.SESSION_LOST)
            return
        }
        if (e164Phone == null) {
            fail(PhoneVerificationError.REQUEST_FAILED)
            return
        }

        mutableState.value = PhoneVerificationState.RequestingOtp
        try {
            val identity = gateway.currentUser()
            if (identity == null) {
                fail(PhoneVerificationError.SESSION_LOST)
                return
            }
            if (identity.userId != userId) {
                fail(PhoneVerificationError.ACCOUNT_CHANGED)
                return
            }
            gateway.resendPhoneChange(userId, e164Phone)
            mutableState.value = PhoneVerificationState.AwaitingOtp
        } catch (cancelled: CancellationException) {
            restoreAfterCancellation()
            throw cancelled
        } catch (_: Exception) {
            fail(PhoneVerificationError.REQUEST_FAILED)
        }
    }

    suspend fun verify(inputOtp: String) {
        val userId = currentUserId
        val e164Phone = pendingPhone
        val otp = inputOtp.trim()
        if (userId == null || e164Phone == null) {
            fail(PhoneVerificationError.SESSION_LOST)
            return
        }
        if (otp.length !in 4..8 || otp.any { it !in '0'..'9' }) {
            fail(PhoneVerificationError.CODE_INVALID_EXPIRED_OR_RATE_LIMITED)
            return
        }

        mutableState.value = PhoneVerificationState.Verifying
        try {
            val identity = gateway.currentUser()
            if (identity == null) {
                fail(PhoneVerificationError.SESSION_LOST)
                return
            }
            if (identity.userId != userId) {
                fail(PhoneVerificationError.ACCOUNT_CHANGED)
                return
            }
            val refreshedIdentity = gateway.verifyPhoneChange(userId, e164Phone, otp)
            if (refreshedIdentity.userId != userId) {
                fail(PhoneVerificationError.ACCOUNT_CHANGED)
            } else if (isConfirmedFor(refreshedIdentity, userId, e164Phone)) {
                pendingPhone = null
                mutableState.value = PhoneVerificationState.Verified
            } else {
                fail(PhoneVerificationError.CONFIRMATION_MISSING)
            }
        } catch (cancelled: CancellationException) {
            restoreAfterCancellation()
            throw cancelled
        } catch (_: Exception) {
            fail(PhoneVerificationError.CODE_INVALID_EXPIRED_OR_RATE_LIMITED)
        }
    }

    private fun isConfirmedFor(identity: PhoneAuthIdentity, userId: String, e164Phone: String): Boolean =
        identity.userId == userId && identity.phoneConfirmed && identity.phone?.let(::normalizeE164Phone) == e164Phone

    private fun fail(reason: PhoneVerificationError) {
        mutableState.value = PhoneVerificationState.Error(reason)
    }

    private fun restoreAfterCancellation() {
        mutableState.value = if (pendingPhone == null) {
            PhoneVerificationState.NoPhone
        } else {
            PhoneVerificationState.AwaitingOtp
        }
    }
}