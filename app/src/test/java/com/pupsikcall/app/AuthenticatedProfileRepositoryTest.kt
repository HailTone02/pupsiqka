package com.pupsikcall.app

import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AuthenticatedProfileRepositoryTest {
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