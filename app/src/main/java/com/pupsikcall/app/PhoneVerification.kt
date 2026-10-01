package com.pupsikcall.app

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import io.github.jan.supabase.auth.exception.AuthRestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import java.io.IOException

internal data class PhoneAuthIdentity(
    val userId: String,
    val phone: String?,
    val phoneConfirmed: Boolean,
    val pendingPhone: String?,
    val phoneChangeSent: Boolean,
    val phoneChangeSentAtMillis: Long? = null,
) {
    override fun toString(): String =
        "PhoneAuthIdentity(phonePresent=${phone != null}, phoneConfirmed=$phoneConfirmed, pendingPhonePresent=${pendingPhone != null}, phoneChangeSent=$phoneChangeSent)"
}

internal interface PhoneIdentityGateway {
    suspend fun currentUser(): PhoneAuthIdentity?
    suspend fun requestPhoneChange(userId: String, e164Phone: String): PhoneAuthIdentity
    suspend fun resendPhoneChange(userId: String, e164Phone: String): PhoneAuthIdentity
    suspend fun verifyPhoneChange(userId: String, e164Phone: String, otp: String): PhoneAuthIdentity
}

internal enum class PhoneVerificationError {
    INVALID_PHONE,
    REQUEST_FAILED,
    NETWORK_FAILED,
    RATE_LIMITED,
    PROVIDER_CONFIGURATION_FAILED,
    SUPABASE_REJECTED,
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
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val resendCooldownMillis: Long = 60_000,
) {
    private val mutableState = MutableStateFlow<PhoneVerificationState>(PhoneVerificationState.Checking)
    val state: StateFlow<PhoneVerificationState> = mutableState.asStateFlow()

    private val operationMutex = Mutex()
    private var currentUserId: String? = null
    private var pendingPhone: String? = null
    @Volatile private var generation = 0L
    private var resendAvailableAtMillis = 0L

    fun resendCooldownSeconds(): Int {
        val remainingMillis = (resendAvailableAtMillis - nowMillis()).coerceAtLeast(0)
        return ((remainingMillis + 999) / 1_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun clearForSignOut() {
        generation++
        clearIdentity()
        mutableState.value = PhoneVerificationState.NoPhone
    }

    private fun clearIdentity() {
        currentUserId = null
        pendingPhone = null
        resendAvailableAtMillis = 0
    }

    suspend fun load() {
        if (!operationMutex.tryLock()) return
        val operationGeneration = generation
        try {
            mutableState.value = PhoneVerificationState.Checking
            try {
                val identity = gateway.currentUser()
                if (generation != operationGeneration) return
                if (identity == null) {
                    clearIdentity()
                    mutableState.value = PhoneVerificationState.Error(PhoneVerificationError.SESSION_LOST)
                    return
                }
                currentUserId = identity.userId
                val verifiedPhone = identity.phone?.let(::normalizeE164Phone)
                val normalizedPendingPhone = identity.pendingPhone?.let(::normalizeE164Phone)
                if (identity.phoneChangeSent && normalizedPendingPhone != null) {
                    pendingPhone = normalizedPendingPhone
                    setResendCooldown(identity.phoneChangeSentAtMillis)
                    mutableState.value = PhoneVerificationState.AwaitingOtp
                } else if (identity.phoneConfirmed && verifiedPhone != null) {
                    pendingPhone = null
                    resendAvailableAtMillis = 0
                    mutableState.value = PhoneVerificationState.Verified
                } else {
                    pendingPhone = null
                    resendAvailableAtMillis = 0
                    mutableState.value = PhoneVerificationState.NoPhone
                }
            } catch (cancelled: CancellationException) {
                if (generation == operationGeneration) {
                    clearIdentity()
                    mutableState.value = PhoneVerificationState.Error(PhoneVerificationError.SESSION_LOST)
                }
                throw cancelled
            } catch (_: Exception) {
                if (generation == operationGeneration) {
                    clearIdentity()
                    mutableState.value = PhoneVerificationState.Error(PhoneVerificationError.STATUS_CHECK_FAILED)
                }
            }
        } finally {
            operationMutex.unlock()
        }
    }

    suspend fun requestVerification(input: String) {
        if (!operationMutex.tryLock()) return
        val operationGeneration = generation
        try {
            val e164Phone = normalizeE164Phone(input)
            if (e164Phone == null) {
                mutableState.value = PhoneVerificationState.Error(PhoneVerificationError.INVALID_PHONE)
                return
            }
            if (resendCooldownSeconds() > 0) return

            mutableState.value = PhoneVerificationState.RequestingOtp
            try {
                val identity = gateway.currentUser()
                if (generation != operationGeneration) return
                if (identity == null) {
                    clearIdentity()
                    mutableState.value = PhoneVerificationState.Error(PhoneVerificationError.SESSION_LOST)
                    return
                }
                currentUserId = identity.userId
                if (identity.phoneConfirmed && identity.phone?.let(::normalizeE164Phone) == e164Phone) {
                    pendingPhone = null
                    resendAvailableAtMillis = 0
                    mutableState.value = PhoneVerificationState.Verified
                    return
                }

                pendingPhone = e164Phone
                val updatedIdentity = gateway.requestPhoneChange(identity.userId, e164Phone)
                if (generation != operationGeneration) return
                if (updatedIdentity.userId != identity.userId) {
                    clearIdentity()
                    fail(PhoneVerificationError.ACCOUNT_CHANGED)
                } else if (isConfirmedFor(updatedIdentity, identity.userId, e164Phone)) {
                    pendingPhone = null
                    resendAvailableAtMillis = 0
                    mutableState.value = PhoneVerificationState.Verified
                } else if (updatedIdentity.pendingPhone?.let(::normalizeE164Phone) == e164Phone && updatedIdentity.phoneChangeSent) {
                    setResendCooldown(updatedIdentity.phoneChangeSentAtMillis)
                    mutableState.value = PhoneVerificationState.AwaitingOtp
                } else {
                    fail(PhoneVerificationError.REQUEST_FAILED)
                }
            } catch (cancelled: CancellationException) {
                if (generation == operationGeneration) restoreAfterCancellation()
                throw cancelled
            } catch (failure: Exception) {
                if (generation == operationGeneration) fail(phoneRequestFailure(failure))
            }
        } finally {
            operationMutex.unlock()
        }
    }

    suspend fun resendVerification() {
        if (resendCooldownSeconds() > 0 || !operationMutex.tryLock()) return
        val operationGeneration = generation
        try {
            if (resendCooldownSeconds() > 0) return
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
                if (generation != operationGeneration) return
                if (identity == null) {
                    clearIdentity()
                    fail(PhoneVerificationError.SESSION_LOST)
                    return
                }
                if (identity.userId != userId) {
                    clearIdentity()
                    fail(PhoneVerificationError.ACCOUNT_CHANGED)
                    return
                }
                if (isConfirmedFor(identity, userId, e164Phone)) {
                    pendingPhone = null
                    resendAvailableAtMillis = 0
                    mutableState.value = PhoneVerificationState.Verified
                    return
                }
                if (identity.pendingPhone?.let(::normalizeE164Phone) != e164Phone || !identity.phoneChangeSent) {
                    clearIdentity()
                    fail(PhoneVerificationError.CONFIRMATION_MISSING)
                    return
                }
                val updatedIdentity = gateway.resendPhoneChange(userId, e164Phone)
                if (generation != operationGeneration) return
                if (updatedIdentity.userId != userId) {
                    clearIdentity()
                    fail(PhoneVerificationError.ACCOUNT_CHANGED)
                } else if (updatedIdentity.pendingPhone?.let(::normalizeE164Phone) == e164Phone && updatedIdentity.phoneChangeSent) {
                    setResendCooldown(updatedIdentity.phoneChangeSentAtMillis)
                    mutableState.value = PhoneVerificationState.AwaitingOtp
                } else {
                    fail(PhoneVerificationError.REQUEST_FAILED)
                }
            } catch (cancelled: CancellationException) {
                if (generation == operationGeneration) restoreAfterCancellation()
                throw cancelled
            } catch (failure: Exception) {
                if (generation == operationGeneration) fail(phoneRequestFailure(failure))
            }
        } finally {
            operationMutex.unlock()
        }
    }

    suspend fun verify(inputOtp: String) {
        if (!operationMutex.tryLock()) return
        val operationGeneration = generation
        try {
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
                if (generation != operationGeneration) return
                if (identity == null) {
                    clearIdentity()
                    fail(PhoneVerificationError.SESSION_LOST)
                    return
                }
                if (identity.userId != userId) {
                    clearIdentity()
                    fail(PhoneVerificationError.ACCOUNT_CHANGED)
                    return
                }
                val refreshedIdentity = gateway.verifyPhoneChange(userId, e164Phone, otp)
                if (generation != operationGeneration) return
                if (refreshedIdentity.userId != userId) {
                    clearIdentity()
                    fail(PhoneVerificationError.ACCOUNT_CHANGED)
                } else if (isConfirmedFor(refreshedIdentity, userId, e164Phone)) {
                    pendingPhone = null
                    resendAvailableAtMillis = 0
                    mutableState.value = PhoneVerificationState.Verified
                } else {
                    fail(PhoneVerificationError.CODE_INVALID_EXPIRED_OR_RATE_LIMITED)
                }
            } catch (cancelled: CancellationException) {
                if (generation == operationGeneration) restoreAfterCancellation()
                throw cancelled
            } catch (_: Exception) {
                if (generation == operationGeneration) fail(PhoneVerificationError.CODE_INVALID_EXPIRED_OR_RATE_LIMITED)
            }
        } finally {
            operationMutex.unlock()
        }
    }

    private fun setResendCooldown(sentAtMillis: Long?) {
        resendAvailableAtMillis = (sentAtMillis ?: nowMillis()) + resendCooldownMillis
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

internal fun phoneRequestFailure(failure: Exception): PhoneVerificationError {
    if (failure is AuthRestException) {
        val code = failure.errorCode?.name.orEmpty()
        return when {
            code.contains("rate", ignoreCase = true) || code.contains("limit", ignoreCase = true) ->
                PhoneVerificationError.RATE_LIMITED
            code.contains("provider", ignoreCase = true) || code.contains("sms", ignoreCase = true) ->
                PhoneVerificationError.PROVIDER_CONFIGURATION_FAILED
            else -> PhoneVerificationError.SUPABASE_REJECTED
        }
    }
    if (failure is IOException || failure::class.simpleName?.contains("Timeout", ignoreCase = true) == true) {
        return PhoneVerificationError.NETWORK_FAILED
    }
    return PhoneVerificationError.REQUEST_FAILED
}