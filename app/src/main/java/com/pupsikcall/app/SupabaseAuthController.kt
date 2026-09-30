package com.pupsikcall.app

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.exception.AuthWeakPasswordException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.Phone
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.Locale

internal enum class AuthPhase {
    CHECKING_SESSION,
    UNAUTHENTICATED,
    REGISTERING,
    LOGGING_IN,
    SIGNING_OUT,
    AUTHENTICATED,
    ERROR,
}

internal enum class AuthMessage {
    CONFIRMATION_REQUIRED,
    CONFIGURATION_MISSING,
    SESSION_RESTORE_FAILED,
    INVALID_EMAIL,
    PASSWORD_REQUIRED,
    WEAK_PASSWORD,
    DUPLICATE_ACCOUNT,
    INVALID_CREDENTIALS,
    EMAIL_NOT_CONFIRMED,
    SIGNUP_DISABLED,
    RATE_LIMITED,
    NETWORK_ERROR,
    REGISTER_FAILED,
    LOGIN_FAILED,
    LOGIN_UNVERIFIED,
    LOGOUT_FAILED,
}

internal data class AuthUiState(
    val phase: AuthPhase = AuthPhase.CHECKING_SESSION,
    val email: String? = null,
    val message: AuthMessage? = null,
) {
    val isBusy: Boolean
        get() = phase == AuthPhase.REGISTERING || phase == AuthPhase.LOGGING_IN || phase == AuthPhase.SIGNING_OUT
}

internal enum class AuthAction { REGISTER, LOGIN }

internal enum class RegistrationResult { SIGNED_IN, CONFIRMATION_REQUIRED, FAILED }

internal data class RegistrationInput(
    val name: String,
    val surname: String,
    val username: String,
    val email: String,
    val phone: String,
    val password: String,
    val confirmPassword: String,
)

internal enum class RegistrationValidationError {
    NAME_REQUIRED,
    SURNAME_REQUIRED,
    USERNAME_INVALID,
    EMAIL_INVALID,
    PHONE_INVALID,
    PASSWORD_REQUIRED,
    PASSWORD_MISMATCH,
}

internal fun normalizeHailToneUsername(value: String): String = value.trim().removePrefix("@").lowercase(Locale.ROOT)

internal fun validateRegistration(input: RegistrationInput): RegistrationValidationError? {
    if (input.name.trim().isEmpty() || input.name.trim().length > 80 || input.name.any(Char::isISOControl) ||
        input.name.trim().length + input.surname.trim().length + 1 > 80
    ) {
        return RegistrationValidationError.NAME_REQUIRED
    }
    if (input.surname.trim().isEmpty() || input.surname.trim().length > 80 || input.surname.any(Char::isISOControl)) {
        return RegistrationValidationError.SURNAME_REQUIRED
    }
    val username = normalizeHailToneUsername(input.username)
    if (!USERNAME_PATTERN.matches(username) || username in RESERVED_USERNAMES) return RegistrationValidationError.USERNAME_INVALID
    if (!EMAIL_PATTERN.matches(input.email.trim())) return RegistrationValidationError.EMAIL_INVALID
    if (normalizeE164Phone(input.phone) == null) return RegistrationValidationError.PHONE_INVALID
    if (input.password.isEmpty()) return RegistrationValidationError.PASSWORD_REQUIRED
    if (input.password != input.confirmPassword) return RegistrationValidationError.PASSWORD_MISMATCH
    return null
}

internal enum class LoginIdentifierKind { EMAIL, VERIFIED_PHONE }

internal fun loginIdentifierKind(value: String): LoginIdentifierKind? = when {
    EMAIL_PATTERN.matches(value.trim()) -> LoginIdentifierKind.EMAIL
    normalizeE164Phone(value) != null -> LoginIdentifierKind.VERIFIED_PHONE
    else -> null
}

private val USERNAME_PATTERN = Regex("^[a-z0-9_]{3,30}$")
private val RESERVED_USERNAMES = setOf(
    "admin", "administrator", "api", "billing", "contact", "help", "hailtone", "null", "root", "security", "support", "system", "www",
)

internal fun validateAuthCredentials(email: String, password: String): AuthMessage? = when {
    !EMAIL_PATTERN.matches(email) -> AuthMessage.INVALID_EMAIL
    password.isEmpty() -> AuthMessage.PASSWORD_REQUIRED
    else -> null
}

private val EMAIL_PATTERN = Regex("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$")

internal object AuthStateTransitions {
    fun onSessionStatus(current: AuthUiState, status: SessionStatus): AuthUiState = when (status) {
        SessionStatus.Initializing -> current
        is SessionStatus.Authenticated -> authenticated(status.session.user?.email ?: current.email)
        is SessionStatus.NotAuthenticated -> when (current.phase) {
            AuthPhase.CHECKING_SESSION, AuthPhase.AUTHENTICATED -> unauthenticated()
            else -> current
        }
        is SessionStatus.RefreshFailure -> if (current.isBusy) current else error(AuthMessage.SESSION_RESTORE_FAILED)
    }

    fun begin(action: AuthAction, email: String) = AuthUiState(
        phase = if (action == AuthAction.REGISTER) AuthPhase.REGISTERING else AuthPhase.LOGGING_IN,
        email = email,
    )

    fun authenticated(email: String?) = AuthUiState(AuthPhase.AUTHENTICATED, email = email)

    fun confirmationRequired() = AuthUiState(
        phase = AuthPhase.UNAUTHENTICATED,
        message = AuthMessage.CONFIRMATION_REQUIRED,
    )

    fun signingOut(email: String?) = AuthUiState(AuthPhase.SIGNING_OUT, email = email)

    fun logoutFailed(email: String?) = AuthUiState(
        phase = AuthPhase.AUTHENTICATED,
        email = email,
        message = AuthMessage.LOGOUT_FAILED,
    )

    fun unauthenticated(message: AuthMessage? = null) = AuthUiState(
        AuthPhase.UNAUTHENTICATED,
        message = message,
    )

    fun error(message: AuthMessage) = AuthUiState(AuthPhase.ERROR, message = message)
}

internal fun sanitizedAuthError(action: AuthAction, failure: Throwable): AuthMessage {
    if (failure is AuthWeakPasswordException) return AuthMessage.WEAK_PASSWORD
    if (failure is AuthRestException) {
        if (action == AuthAction.LOGIN) return AuthMessage.INVALID_CREDENTIALS
        return authRestErrorMessage(action, failure.errorCode)
    }
    if (failure is IOException) return AuthMessage.NETWORK_ERROR
    return genericAuthError(action)
}

internal fun authRestErrorMessage(action: AuthAction, errorCode: AuthErrorCode?): AuthMessage = when (errorCode) {
    AuthErrorCode.UserAlreadyExists, AuthErrorCode.EmailExists, AuthErrorCode.InvalidCredentials ->
        if (action == AuthAction.REGISTER) AuthMessage.REGISTER_FAILED else AuthMessage.INVALID_CREDENTIALS
    AuthErrorCode.EmailNotConfirmed -> AuthMessage.EMAIL_NOT_CONFIRMED
    AuthErrorCode.EmailAddressInvalid -> AuthMessage.INVALID_EMAIL
    AuthErrorCode.WeakPassword -> AuthMessage.WEAK_PASSWORD
    AuthErrorCode.SignupDisabled -> AuthMessage.SIGNUP_DISABLED
    AuthErrorCode.OverRequestRateLimit, AuthErrorCode.OverEmailSendRateLimit -> AuthMessage.RATE_LIMITED
    else -> genericAuthError(action)
}

private fun genericAuthError(action: AuthAction): AuthMessage = when (action) {
    AuthAction.REGISTER -> AuthMessage.REGISTER_FAILED
    AuthAction.LOGIN -> AuthMessage.LOGIN_FAILED
}

internal class SupabaseAuthController(private val client: SupabaseClient?) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow(
        if (client == null) AuthStateTransitions.error(AuthMessage.CONFIGURATION_MISSING) else AuthUiState(),
    )
    val state = mutableState.asStateFlow()
    private val sessionJob: Job? = client?.let { configuredClient ->
        scope.launch {
            configuredClient.auth.sessionStatus.collect { sessionStatus ->
                mutableState.value = AuthStateTransitions.onSessionStatus(mutableState.value, sessionStatus)
            }
        }
    }

    suspend fun register(input: RegistrationInput): RegistrationResult = operationMutex.withLock {
        if (validateRegistration(input) != null) {
            mutableState.value = AuthStateTransitions.error(AuthMessage.REGISTER_FAILED)
            return@withLock RegistrationResult.FAILED
        }
        val normalizedEmail = input.email.trim().lowercase(Locale.ROOT)
        val configuredClient = client ?: return@withLock RegistrationResult.FAILED
        mutableState.value = AuthStateTransitions.begin(AuthAction.REGISTER, normalizedEmail)
        try {
            configuredClient.auth.signUpWith(Email) {
                this.email = normalizedEmail
                this.password = input.password
                data = buildJsonObject {
                    put("hailtone_name", input.name.trim())
                    put("hailtone_surname", input.surname.trim())
                    put("hailtone_username", normalizeHailToneUsername(input.username))
                }
            }
            val session = configuredClient.auth.currentSessionOrNull()
            if (session != null) {
                mutableState.value = AuthStateTransitions.authenticated(session.user?.email ?: normalizedEmail)
                RegistrationResult.SIGNED_IN
            } else {
                mutableState.value = AuthStateTransitions.confirmationRequired()
                RegistrationResult.CONFIRMATION_REQUIRED
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            if (failure is AuthRestException && failure.errorCode in setOf(AuthErrorCode.UserAlreadyExists, AuthErrorCode.EmailExists)) {
                mutableState.value = AuthStateTransitions.confirmationRequired()
                RegistrationResult.CONFIRMATION_REQUIRED
            } else {
                mutableState.value = AuthStateTransitions.error(sanitizedAuthError(AuthAction.REGISTER, failure))
                RegistrationResult.FAILED
            }
        }
    }

    suspend fun login(identifier: String, password: String) = operationMutex.withLock {
        val normalizedIdentifier = identifier.trim()
        val kind = loginIdentifierKind(normalizedIdentifier)
        if (kind == null || password.isEmpty()) {
            mutableState.value = AuthStateTransitions.error(AuthMessage.INVALID_CREDENTIALS)
            return@withLock
        }
        val configuredClient = client ?: return@withLock
        mutableState.value = AuthStateTransitions.begin(AuthAction.LOGIN, normalizedIdentifier)
        try {
            when (kind) {
                LoginIdentifierKind.EMAIL -> configuredClient.auth.signInWith(Email) {
                    this.email = normalizedIdentifier.lowercase(Locale.ROOT)
                    this.password = password
                }
                LoginIdentifierKind.VERIFIED_PHONE -> configuredClient.auth.signInWith(Phone) {
                    phone = normalizeE164Phone(normalizedIdentifier) ?: throw IllegalArgumentException("Invalid phone")
                    this.password = password
                }
            }
            val session = configuredClient.auth.currentSessionOrNull()
            if (session == null) {
                mutableState.value = AuthStateTransitions.error(AuthMessage.LOGIN_UNVERIFIED)
            } else {
                mutableState.value = AuthStateTransitions.authenticated(session.user?.email ?: normalizedIdentifier)
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            mutableState.value = AuthStateTransitions.error(sanitizedAuthError(AuthAction.LOGIN, failure))
        }
    }

    suspend fun logout() = operationMutex.withLock {
        val configuredClient = client ?: return@withLock
        mutableState.value = AuthStateTransitions.signingOut(mutableState.value.email)
        try {
            configuredClient.auth.signOut()
            mutableState.value = AuthStateTransitions.unauthenticated()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            mutableState.value = AuthStateTransitions.logoutFailed(mutableState.value.email)
        }
    }

    override fun close() {
        sessionJob?.cancel()
        scope.cancel()
    }
}