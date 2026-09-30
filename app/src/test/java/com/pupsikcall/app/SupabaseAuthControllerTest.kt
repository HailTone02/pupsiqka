package com.pupsikcall.app

import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseAuthControllerTest {
    @Test
    fun startupWaitsForRestoredSessionStatusBeforeChoosingAuthRoute() {
        val checking = AuthUiState()
        assertEquals(AuthPhase.CHECKING_SESSION, AuthStateTransitions.onSessionStatus(checking, SessionStatus.Initializing).phase)

        val signedOut = AuthStateTransitions.onSessionStatus(checking, SessionStatus.NotAuthenticated())
        assertEquals(AuthPhase.UNAUTHENTICATED, signedOut.phase)

        val authenticated = AuthStateTransitions.onSessionStatus(checking, SessionStatus.Authenticated(testSession()))
        assertEquals(AuthPhase.AUTHENTICATED, authenticated.phase)
    }

    @Test
    fun loginAndRegistrationTransitionsDoNotStoreCredentialsInUiState() {
        val login = AuthStateTransitions.begin(AuthAction.LOGIN, "user@example.test")
        assertEquals(AuthPhase.LOGGING_IN, login.phase)
        assertEquals("user@example.test", login.email)
        assertNull(login.message)
        assertEquals(AuthPhase.AUTHENTICATED, AuthStateTransitions.authenticated(login.email).phase)

        val registration = AuthStateTransitions.begin(AuthAction.REGISTER, "user@example.test")
        assertEquals(AuthPhase.REGISTERING, registration.phase)
        assertFalse(registration.toString().contains("password"))
        assertFalse(registration.toString().contains("token"))
    }

    @Test
    fun emailConfirmationDoesNotClaimAnAuthenticatedAccount() {
        val confirmation = AuthStateTransitions.confirmationRequired()

        assertEquals(AuthPhase.UNAUTHENTICATED, confirmation.phase)
        assertEquals(AuthMessage.CONFIRMATION_REQUIRED, confirmation.message)
    }

    @Test
    fun logoutClearsAuthenticatedUiStateAfterSdkSignOut() {
        val signingOut = AuthStateTransitions.signingOut("user@example.test")
        assertEquals(AuthPhase.SIGNING_OUT, signingOut.phase)
        assertEquals(AuthPhase.UNAUTHENTICATED, AuthStateTransitions.unauthenticated().phase)

        val failure = AuthStateTransitions.logoutFailed(signingOut.email)
        assertEquals(AuthPhase.AUTHENTICATED, failure.phase)
        assertEquals("user@example.test", failure.email)
        assertEquals(AuthMessage.LOGOUT_FAILED, failure.message)
    }

    @Test
    fun invalidEmailAndMissingPasswordAreRejectedLocally() {
        assertEquals(AuthMessage.INVALID_EMAIL, validateAuthCredentials("not-an-email", "password"))
        assertEquals(AuthMessage.PASSWORD_REQUIRED, validateAuthCredentials("user@example.test", ""))
        assertNull(validateAuthCredentials("user@example.test", "valid-password"))
    }

    @Test
    fun registrationRequiresAllIdentityFieldsAndMatchingPasswords() {
        val valid = RegistrationInput(
            name = "Ada",
            surname = "Lovelace",
            username = "Ada_L",
            email = "ada@example.test",
            phone = "+1 202 555 0123",
            password = "StrongPassword1!",
            confirmPassword = "StrongPassword1!",
        )

        assertNull(validateRegistration(valid))
        assertEquals("ada_l", normalizeHailToneUsername(valid.username))
        assertEquals(RegistrationValidationError.PASSWORD_MISMATCH, validateRegistration(valid.copy(confirmPassword = "different")))
        assertEquals(RegistrationValidationError.NAME_REQUIRED, validateRegistration(valid.copy(name = " ")))
        assertEquals(RegistrationValidationError.SURNAME_REQUIRED, validateRegistration(valid.copy(surname = " ")))
        assertEquals(RegistrationValidationError.USERNAME_INVALID, validateRegistration(valid.copy(username = "ab")))
        assertEquals(RegistrationValidationError.USERNAME_INVALID, validateRegistration(valid.copy(username = "admin")))
        assertEquals(RegistrationValidationError.EMAIL_INVALID, validateRegistration(valid.copy(email = "not-email")))
        assertEquals(RegistrationValidationError.PHONE_INVALID, validateRegistration(valid.copy(phone = "2025550123")))
    }

    @Test
    fun onlySupabaseEmailAndVerifiedPhoneIdentifiersAreAcceptedForPasswordLogin() {
        assertEquals(LoginIdentifierKind.EMAIL, loginIdentifierKind(" User@example.test "))
        assertEquals(LoginIdentifierKind.VERIFIED_PHONE, loginIdentifierKind("+1 202 555 0123"))
        assertNull(loginIdentifierKind("@ada_l"))
        assertNull(loginIdentifierKind("not-an-identifier"))
    }

    @Test
    fun authFailuresUseSafeMessagesWithoutEchoingPasswordsOrTokens() {
        val failure = IllegalStateException("password=never-show access_token=never-show refresh_token=never-show")
        val message = sanitizedAuthError(AuthAction.LOGIN, failure)

        assertEquals(AuthMessage.LOGIN_FAILED, message)
        assertFalse(message.name.contains("never-show"))
        assertEquals(
            AuthMessage.WEAK_PASSWORD,
            authRestErrorMessage(AuthAction.REGISTER, AuthErrorCode.WeakPassword),
        )
        assertEquals(
            AuthMessage.REGISTER_FAILED,
            authRestErrorMessage(AuthAction.REGISTER, AuthErrorCode.UserAlreadyExists),
        )
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun testSession() = UserSession(
        accessToken = "test-access-token",
        refreshToken = "test-refresh-token",
        expiresIn = 3600,
        tokenType = "bearer",
    )
}