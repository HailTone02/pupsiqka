package com.pupsikcall.app

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserInfo

internal class SupabasePhoneIdentityGateway(
    private val client: SupabaseClient?,
) : PhoneIdentityGateway {
    override suspend fun currentUser(): PhoneAuthIdentity? {
        val auth = client?.auth ?: throw IllegalStateException("Authentication is unavailable")
        val sessionUserId = auth.currentSessionOrNull()?.user?.id ?: return null
        val user = auth.retrieveUserForCurrentSession(updateSession = true)
        if (user.id != sessionUserId) throw IllegalStateException("Authenticated account changed")
        return user.toPhoneAuthIdentity()
    }

    override suspend fun requestPhoneChange(userId: String, e164Phone: String): PhoneAuthIdentity {
        val auth = client?.auth ?: throw IllegalStateException("Authentication is unavailable")
        requireCurrentUser(auth, userId)
        val updatedUser = auth.updateUser(updateCurrentUser = true, redirectUrl = null) {
            phone = e164Phone
        }
        if (updatedUser.id != userId) throw IllegalStateException("Authenticated account changed")
        return updatedUser.toPhoneAuthIdentity()
    }

    override suspend fun resendPhoneChange(userId: String, e164Phone: String): PhoneAuthIdentity {
        val auth = client?.auth ?: throw IllegalStateException("Authentication is unavailable")
        requireCurrentUser(auth, userId)
        auth.resendPhone(OtpType.Phone.PHONE_CHANGE, e164Phone)
        val updatedUser = auth.retrieveUserForCurrentSession(updateSession = true)
        if (updatedUser.id != userId) throw IllegalStateException("Authenticated account changed")
        return updatedUser.toPhoneAuthIdentity()
    }

    override suspend fun verifyPhoneChange(
        userId: String,
        e164Phone: String,
        otp: String,
    ): PhoneAuthIdentity {
        val auth = client?.auth ?: throw IllegalStateException("Authentication is unavailable")
        requireCurrentUser(auth, userId)
        auth.verifyPhoneOtp(OtpType.Phone.PHONE_CHANGE, e164Phone, otp)
        auth.refreshCurrentSession()
        val refreshedUser = auth.retrieveUserForCurrentSession(updateSession = true)
        if (refreshedUser.id != userId) throw IllegalStateException("Authenticated account changed")
        return refreshedUser.toPhoneAuthIdentity()
    }

    private suspend fun requireCurrentUser(
        auth: io.github.jan.supabase.auth.Auth,
        expectedUserId: String,
    ) {
        val sessionUserId = auth.currentSessionOrNull()?.user?.id
            ?: throw IllegalStateException("Authentication session is unavailable")
        val currentUser = auth.retrieveUserForCurrentSession(updateSession = true)
        if (sessionUserId != expectedUserId || currentUser.id != expectedUserId) {
            throw IllegalStateException("Authenticated account changed")
        }
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun UserInfo.toPhoneAuthIdentity() = PhoneAuthIdentity(
        userId = id,
        phone = phone,
        phoneConfirmed = phoneConfirmedAt != null,
        pendingPhone = newPhone,
        phoneChangeSent = phoneChangeSentAt != null,
        phoneChangeSentAtMillis = phoneChangeSentAt?.toEpochMilliseconds(),
    )
}