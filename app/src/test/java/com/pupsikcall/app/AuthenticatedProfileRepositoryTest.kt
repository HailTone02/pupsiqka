package com.pupsikcall.app

import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AuthenticatedProfileRepositoryTest {
    @Test
    fun accountAProfileCannotSatisfyAccountBGate() {
        val accountA = UUID.fromString("00000000-0000-4000-8000-000000000021")
        val accountB = UUID.fromString("00000000-0000-4000-8000-000000000022")
        val profileA = profileState(accountA)

        assertNull(authenticatedProfileFor(profileA, accountB))
        assertEquals(profileA, authenticatedProfileFor(profileA, accountA))
    }

    @Test
    fun lateAccountAProfileReloadIsIgnoredAfterSwitchingToB() = runBlocking {
        val accountA = UUID.fromString("00000000-0000-4000-8000-000000000023")
        val accountB = UUID.fromString("00000000-0000-4000-8000-000000000024")
        val profileA = UserProfile(accountA, "Account A", null)
        val profileB = UserProfile(accountB, "Account B", null)
        val staleAResponse = CompletableDeferred<UserProfile?>()
        var accountALoads = 0
        val gateway = object : ProfileGateway {
            override suspend fun load(userId: UUID): UserProfile? = when (userId) {
                accountA -> if (++accountALoads == 1) profileA else staleAResponse.await()
                accountB -> profileB
                else -> null
            }

            override suspend fun updateDisplayName(userId: UUID, displayName: String): UserProfile? = null
        }
        val events = MutableSharedFlow<ProfileSessionEvent>(extraBufferCapacity = 1)
        val repository = AuthenticatedProfileRepository(events, gateway, Dispatchers.Unconfined)

        try {
            events.emit(ProfileSessionEvent.Authenticated(accountA.toString(), "a@example.test"))
            repository.state.first { it is AuthenticatedProfileState.Profile }
            repository.reload()
            yield()
            events.emit(ProfileSessionEvent.Authenticated(accountB.toString(), "b@example.test"))
            repository.state.first {
                it is AuthenticatedProfileState.Profile && it.identity.userId == accountB
            }
            staleAResponse.complete(profileA)
            yield()

            val current = repository.state.value as AuthenticatedProfileState.Profile
            assertEquals(accountB, current.identity.userId)
            assertEquals(accountB, current.profile.userId)
        } finally {
            repository.close()
        }
    }

    @Test
    fun nullUnknownErrorAndMismatchedProfileStatesFailClosed() {
        val accountId = UUID.fromString("00000000-0000-4000-8000-000000000025")
        val otherId = UUID.fromString("00000000-0000-4000-8000-000000000026")
        val mismatchedRow = AuthenticatedProfileState.Profile(
            AuthenticatedUserIdentity(accountId),
            null,
            UserProfile(otherId, "Other", null),
        )

        assertNull(authenticatedProfileFor(profileState(accountId), null))
        assertNull(authenticatedProfileFor(AuthenticatedProfileState.Loading, accountId))
        assertNull(authenticatedProfileFor(AuthenticatedProfileState.SignedOut, accountId))
        assertNull(authenticatedProfileFor(AuthenticatedProfileState.MissingProfile(AuthenticatedUserIdentity(accountId), null), accountId))
        assertNull(authenticatedProfileFor(AuthenticatedProfileState.Error(AuthenticatedUserIdentity(accountId), null, ProfileFailure.LOAD_FAILED), accountId))
        assertNull(authenticatedProfileFor(mismatchedRow, accountId))
    }

    @Test
    fun matchingCurrentAuthUuidProfileSatisfiesGate() {
        val accountId = UUID.fromString("00000000-0000-4000-8000-000000000027")
        val profile = profileState(accountId)

        assertEquals(profile, authenticatedProfileFor(profile, accountId))
    }

    @Test
    fun authenticatedProfileProjectionUsesOnlyColumnsGrantedByRls() {
        assertEquals(
            listOf("user_id", "display_name", "avatar_path", "name", "surname", "username", "identity_required", "identity_complete"),
            AUTHENTICATED_PROFILE_COLUMNS,
        )
        assertFalse(AUTHENTICATED_PROFILE_COLUMNS.contains("created_at"))
        assertFalse(AUTHENTICATED_PROFILE_COLUMNS.contains("updated_at"))
    }

    @Test
    fun restoredAuthenticatedSessionUsesItsUuidAsCanonicalIdentity() = runBlocking {
        val userId = UUID.fromString("00000000-0000-4000-8000-000000000011")
        val gateway = FakeProfileGateway(UserProfile(userId, "Real Profile Name", null))
        val repository = repository(
            gateway,
            ProfileSessionEvent.Loading,
            ProfileSessionEvent.Authenticated(userId.toString(), "account@example.test"),
        )

        try {
            val profileState = repository.state.first { it is AuthenticatedProfileState.Profile }
                as AuthenticatedProfileState.Profile

            assertEquals(userId, profileState.identity.userId)
            assertEquals("account@example.test", profileState.email)
            assertEquals(userId, gateway.loadedUserIds.single())
            assertEquals("Real Profile Name", profileState.profile.displayName)
        } finally {
            repository.close()
        }
    }

    @Test
    fun missingProfileIsDistinctFromLoadingAndError() = runBlocking {
        val userId = UUID.fromString("00000000-0000-4000-8000-000000000012")
        val repository = repository(
            FakeProfileGateway(null),
            ProfileSessionEvent.Authenticated(userId.toString(), null),
        )

        try {
            val state = repository.state.first { it is AuthenticatedProfileState.MissingProfile }
            assertEquals(userId, (state as AuthenticatedProfileState.MissingProfile).identity.userId)
        } finally {
            repository.close()
        }
    }

    @Test
    fun displayNameUpdateTargetsTheAuthenticatedProfile() = runBlocking {
        val userId = UUID.fromString("00000000-0000-4000-8000-000000000013")
        val gateway = FakeProfileGateway(UserProfile(userId, null, null))
        val repository = repository(
            gateway,
            ProfileSessionEvent.Authenticated(userId.toString(), null),
        )

        try {
            repository.state.first { it is AuthenticatedProfileState.Profile }
            assertEquals(ProfileUpdateResult.UPDATED, repository.updateDisplayName("  New Name  "))

            val state = repository.state.first {
                it is AuthenticatedProfileState.Profile && it.profile.displayName == "New Name"
            } as AuthenticatedProfileState.Profile
            assertEquals(userId, gateway.updatedUserIds.single())
            assertEquals("New Name", state.profile.displayName)
        } finally {
            repository.close()
        }
    }

    @Test
    fun signedOutSessionClearsAuthenticatedProfileState() = runBlocking {
        val repository = repository(FakeProfileGateway(null), ProfileSessionEvent.SignedOut)

        try {
            assertEquals(AuthenticatedProfileState.SignedOut, repository.state.first { it == AuthenticatedProfileState.SignedOut })
            assertEquals(ProfileUpdateResult.NOT_AUTHENTICATED, repository.updateDisplayName("Name"))
        } finally {
            repository.close()
        }
    }

    @Test
    fun invalidOrMissingAuthUuidNeverFallsBackToLegacyDeviceIdentity() = runBlocking {
        assertNull(authenticatedIdentityFromSessionUserId(null))
        assertNull(authenticatedIdentityFromSessionUserId("pupsik-a"))
        assertNull(authenticatedIdentityFromSessionUserId("account@example.test"))

        val gateway = FakeProfileGateway(null)
        val repository = repository(
            gateway,
            ProfileSessionEvent.Authenticated(null, "account@example.test"),
        )

        try {
            val state = repository.state.first { it is AuthenticatedProfileState.Error }
                as AuthenticatedProfileState.Error
            assertEquals(ProfileFailure.INVALID_AUTH_IDENTITY, state.reason)
            assertTrue(gateway.loadedUserIds.isEmpty())
        } finally {
            repository.close()
        }
    }

    @Test
    fun authSessionRestoreEventsMapToLoadingAndSignedOutStates() {
        assertEquals(ProfileSessionEvent.Loading, profileSessionEvent(SessionStatus.Initializing))
        assertEquals(ProfileSessionEvent.SignedOut, profileSessionEvent(SessionStatus.NotAuthenticated()))
    }

    @Test
    fun profileInitialsUseRealNameThenEmailAndDoNotInventAnIdentity() {
        assertEquals("AC", profileInitials("Alice Cooper", "other@example.test"))
        assertEquals("A", profileInitials("Alice", "other@example.test"))
        assertEquals("A", profileInitials(null, "account@example.test"))
        assertEquals("?", profileInitials(null, null))
    }

    private fun repository(gateway: FakeProfileGateway, vararg events: ProfileSessionEvent) =
        AuthenticatedProfileRepository(flowOf(*events), gateway)

    private fun profileState(userId: UUID) = AuthenticatedProfileState.Profile(
        identity = AuthenticatedUserIdentity(userId),
        email = null,
        profile = UserProfile(userId, "Profile", null),
    )

    private class FakeProfileGateway(initial: UserProfile?) : ProfileGateway {
        private var profile = initial
        val loadedUserIds = mutableListOf<UUID>()
        val updatedUserIds = mutableListOf<UUID>()

        override suspend fun load(userId: UUID): UserProfile? {
            loadedUserIds += userId
            return profile?.takeIf { it.userId == userId }
        }

        override suspend fun updateDisplayName(userId: UUID, displayName: String): UserProfile? {
            updatedUserIds += userId
            profile = profile?.takeIf { it.userId == userId }?.copy(displayName = displayName)
            return profile
        }
    }
}