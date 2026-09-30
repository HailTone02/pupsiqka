package com.pupsikcall.app

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

internal data class UserProfile(
    val userId: UUID,
    val displayName: String?,
    val avatarPath: String?,
    val name: String? = null,
    val surname: String? = null,
    val username: String? = null,
    val identityRequired: Boolean = false,
    val identityComplete: Boolean = false,
)

internal data class AuthenticatedUserIdentity(
    val userId: UUID,
)

internal val AUTHENTICATED_PROFILE_COLUMNS = listOf(
    "user_id", "display_name", "avatar_path", "name", "surname", "username", "identity_required", "identity_complete",
)

internal sealed interface AuthenticatedProfileState {
    data object Loading : AuthenticatedProfileState

    data class Profile(
        val identity: AuthenticatedUserIdentity,
        val email: String?,
        val profile: UserProfile,
    ) : AuthenticatedProfileState

    data class MissingProfile(
        val identity: AuthenticatedUserIdentity,
        val email: String?,
    ) : AuthenticatedProfileState

    data class Error(
        val identity: AuthenticatedUserIdentity? = null,
        val email: String? = null,
        val reason: ProfileFailure,
    ) : AuthenticatedProfileState

    data object SignedOut : AuthenticatedProfileState
}

internal fun authenticatedProfileFor(
    state: AuthenticatedProfileState,
    authenticatedUserId: UUID?,
): AuthenticatedProfileState.Profile? {
    if (authenticatedUserId == null) return null
    return (state as? AuthenticatedProfileState.Profile)?.takeIf {
        it.identity.userId == authenticatedUserId && it.profile.userId == authenticatedUserId
    }
}

internal enum class ProfileFailure {
    CONFIGURATION,
    INVALID_AUTH_IDENTITY,
    LOAD_FAILED,
    UPDATE_FAILED,
}

internal enum class ProfileUpdateResult {
    UPDATED,
    INVALID_NAME,
    NOT_AUTHENTICATED,
    MISSING_PROFILE,
    FAILED,
}

internal sealed interface ProfileSessionEvent {
    data object Loading : ProfileSessionEvent
    data object SignedOut : ProfileSessionEvent
    data object Error : ProfileSessionEvent
    data class Authenticated(val userId: String?, val email: String?) : ProfileSessionEvent
}

internal fun authenticatedIdentityFromSessionUserId(userId: String?): AuthenticatedUserIdentity? =
    userId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?.let(::AuthenticatedUserIdentity)

internal fun profileSessionEvent(status: SessionStatus): ProfileSessionEvent = when (status) {
    SessionStatus.Initializing -> ProfileSessionEvent.Loading
    is SessionStatus.Authenticated -> ProfileSessionEvent.Authenticated(
        userId = status.session.user?.id,
        email = status.session.user?.email,
    )
    is SessionStatus.NotAuthenticated -> ProfileSessionEvent.SignedOut
    is SessionStatus.RefreshFailure -> ProfileSessionEvent.Error
}

internal fun profileInitials(displayName: String?, email: String?): String {
    val nameParts = displayName.orEmpty().trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    val initials = when {
        nameParts.size >= 2 -> "${nameParts.first().first()}${nameParts.last().first()}"
        nameParts.size == 1 -> nameParts.first().take(1)
        else -> email.orEmpty().substringBefore('@').take(1)
    }
    return initials.uppercase().ifBlank { "?" }
}

internal interface ProfileGateway {
    suspend fun load(userId: UUID): UserProfile?
    suspend fun updateDisplayName(userId: UUID, displayName: String): UserProfile?
}

internal class AuthenticatedProfileRepository(
    sessionEvents: Flow<ProfileSessionEvent>,
    private val profiles: ProfileGateway,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    constructor(client: SupabaseClient?) : this(
        sessionEvents = client?.auth?.sessionStatus?.map(::profileSessionEvent)
            ?: flowOf(ProfileSessionEvent.Error),
        profiles = client?.let(::SupabaseProfileGateway) ?: UnavailableProfileGateway,
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow<AuthenticatedProfileState>(AuthenticatedProfileState.Loading)
    private val updateMutex = Mutex()
    private val sessionJob = scope.launch {
        sessionEvents.collectLatest { event ->
            when (event) {
                ProfileSessionEvent.Loading -> {
                    currentSession = null
                    sessionRevision++
                    mutableState.value = AuthenticatedProfileState.Loading
                }
                ProfileSessionEvent.SignedOut -> {
                    currentSession = null
                    sessionRevision++
                    mutableState.value = AuthenticatedProfileState.SignedOut
                }
                ProfileSessionEvent.Error -> {
                    currentSession = null
                    sessionRevision++
                    mutableState.value = AuthenticatedProfileState.Error(reason = ProfileFailure.CONFIGURATION)
                }
                is ProfileSessionEvent.Authenticated -> loadProfile(event)
            }
        }
    }
    @Volatile private var currentSession: ProfileSessionEvent.Authenticated? = null
    @Volatile private var sessionRevision = 0L
    val state = mutableState.asStateFlow()

    fun reload() {
        val session = currentSession ?: return
        val revision = sessionRevision
        scope.launch { loadProfile(session, revision) }
    }

    suspend fun updateDisplayName(displayName: String): ProfileUpdateResult = updateMutex.withLock {
        val current = mutableState.value as? AuthenticatedProfileState.Profile
            ?: return@withLock ProfileUpdateResult.NOT_AUTHENTICATED
        val normalized = displayName.trim()
        if (normalized.isEmpty() || normalized.length > 80 || normalized.any(Char::isISOControl)) {
            return@withLock ProfileUpdateResult.INVALID_NAME
        }
        val revision = sessionRevision
        try {
            val updated = profiles.updateDisplayName(current.identity.userId, normalized)
            if (revision != sessionRevision || (mutableState.value as? AuthenticatedProfileState.Profile)?.identity?.userId != current.identity.userId) {
                return@withLock ProfileUpdateResult.NOT_AUTHENTICATED
            }
            if (updated == null) {
                mutableState.value = AuthenticatedProfileState.MissingProfile(current.identity, current.email)
                ProfileUpdateResult.MISSING_PROFILE
            } else if (updated.userId != current.identity.userId) {
                mutableState.value = AuthenticatedProfileState.Error(current.identity, current.email, ProfileFailure.UPDATE_FAILED)
                ProfileUpdateResult.FAILED
            } else {
                mutableState.value = current.copy(profile = updated)
                ProfileUpdateResult.UPDATED
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            if (revision == sessionRevision) {
                mutableState.value = AuthenticatedProfileState.Error(current.identity, current.email, ProfileFailure.UPDATE_FAILED)
            }
            ProfileUpdateResult.FAILED
        }
    }

    private suspend fun loadProfile(session: ProfileSessionEvent.Authenticated, expectedRevision: Long? = null) {
        currentSession = session
        val revision = expectedRevision ?: ++sessionRevision
        val identity = authenticatedIdentityFromSessionUserId(session.userId)
        if (identity == null) {
            mutableState.value = AuthenticatedProfileState.Error(reason = ProfileFailure.INVALID_AUTH_IDENTITY)
            return
        }
        mutableState.value = AuthenticatedProfileState.Loading
        try {
            val profile = profiles.load(identity.userId)
            if (revision != sessionRevision) return
            mutableState.value = if (profile == null) {
                AuthenticatedProfileState.MissingProfile(identity, session.email)
            } else if (profile.userId != identity.userId) {
                AuthenticatedProfileState.Error(identity, session.email, ProfileFailure.LOAD_FAILED)
            } else {
                AuthenticatedProfileState.Profile(identity, session.email, profile)
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            if (revision == sessionRevision) {
                mutableState.value = AuthenticatedProfileState.Error(identity, session.email, ProfileFailure.LOAD_FAILED)
            }
        }
    }

    override fun close() {
        sessionJob.cancel()
        scope.cancel()
    }
}

private class SupabaseProfileGateway(private val client: SupabaseClient) : ProfileGateway {
    override suspend fun load(userId: UUID): UserProfile? {
        val row = client.from("profiles").select(columns = Columns.list(*AUTHENTICATED_PROFILE_COLUMNS.toTypedArray())) {
            filter { eq("user_id", userId.toString()) }
        }.decodeList<JsonObject>().singleOrNull() ?: return null
        return UserProfile(
            userId = UUID.fromString(row.stringOrNull("user_id") ?: return null),
            displayName = row.stringOrNull("display_name"),
            avatarPath = row.stringOrNull("avatar_path"),
            name = row.stringOrNull("name"),
            surname = row.stringOrNull("surname"),
            username = row.stringOrNull("username"),
            identityRequired = row.booleanOrFalse("identity_required"),
            identityComplete = row.booleanOrFalse("identity_complete"),
        )
    }

    override suspend fun updateDisplayName(userId: UUID, displayName: String): UserProfile? {
        client.from("profiles").update({
            set("display_name", displayName)
        }) {
            filter { eq("user_id", userId.toString()) }
        }
        return load(userId)
    }

    private fun JsonObject.stringOrNull(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.booleanOrFalse(name: String): Boolean =
        (this[name] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
}

private object UnavailableProfileGateway : ProfileGateway {
    override suspend fun load(userId: UUID): UserProfile? = error("Supabase profile client is unavailable")
    override suspend fun updateDisplayName(userId: UUID, displayName: String): UserProfile? = error("Supabase profile client is unavailable")
}