package com.pupsikcall.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class HailToneContactDirectoryTest {
    @Test
    fun acceptedInviteAssociationResolvesOnlyTheServerReturnedUuid() = runBlocking {
        val inviteId = uuid("00000000-0000-4000-8000-000000000201")
        val accountId = uuid("00000000-0000-4000-8000-000000000202")
        val contact = contact()
        val store = FakeAssociationStore().apply { associateContact(contact.lookupKey, inviteId) }
        val backend = FakeBackend(listOf(link(inviteId, accountId)))
        val directory = HailToneContactDirectory(backend, store)

        val result = directory.matchSelectedContact(contact)

        assertEquals(accountId, result?.userId)
        assertEquals("Verified Profile", result?.displayName)
        assertEquals(1, backend.listCalls)
    }

    @Test
    fun arbitraryDirectoryEntriesDoNotResolveWithoutLocalAcceptedInviteAssociation() = runBlocking {
        val inviteId = uuid("00000000-0000-4000-8000-000000000203")
        val contact = contact()
        val directory = HailToneContactDirectory(
            FakeBackend(listOf(link(inviteId, uuid("00000000-0000-4000-8000-000000000204")))),
            FakeAssociationStore(),
        )

        assertNull(directory.matchSelectedContact(contact))
    }

    @Test
    fun inviteAndAcceptanceBindOnlyTheSelectedLocalContact() = runBlocking {
        val contact = contact()
        val inviteId = uuid("00000000-0000-4000-8000-000000000205")
        val accountId = uuid("00000000-0000-4000-8000-000000000206")
        val store = FakeAssociationStore()
        val backend = FakeBackend(emptyList()).apply {
            createdInvite = ContactInviteCapability(inviteId, "a".repeat(64))
            acceptedLink = link(inviteId, accountId)
        }
        val directory = HailToneContactDirectory(backend, store)

        val invite = directory.createInviteFor(contact)
        assertEquals("a".repeat(64), invite.code)
        assertEquals(inviteId, store.invitationForContact(contact.lookupKey))

        val accepted = directory.acceptInviteFor(contact, invite.code.uppercase())
        assertEquals(accountId, accepted.account.userId)
        assertEquals(inviteId, store.invitationForContact(contact.lookupKey))
        assertEquals("a".repeat(64), backend.lastAcceptedCode)
    }

    @Test
    fun malformedInviteCodeFailsBeforeCallingBackend() = runBlocking {
        val backend = FakeBackend(emptyList())
        val directory = HailToneContactDirectory(backend, FakeAssociationStore())

        val failure = runCatching { directory.acceptInviteFor(contact(), "not-a-code") }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertNull(backend.lastAcceptedCode)
    }

    @Test
    fun blockAndUnblockUseOnlyAResolvedLinkedUuid() = runBlocking {
        val inviteId = uuid("00000000-0000-4000-8000-000000000207")
        val accountId = uuid("00000000-0000-4000-8000-000000000208")
        val backend = FakeBackend(listOf(link(inviteId, accountId)))
        val directory = HailToneContactDirectory(backend, FakeAssociationStore())

        directory.blockResolvedContact(accountId)
        directory.unblockResolvedContact(accountId)

        assertEquals(listOf(accountId), backend.blockedIds)
        assertEquals(listOf(accountId), backend.unblockedIds)
    }

    @Test
    fun blockCannotTargetAnUnlinkedClientSuppliedUuid() = runBlocking {
        val inviteId = uuid("00000000-0000-4000-8000-000000000209")
        val backend = FakeBackend(listOf(link(inviteId, uuid("00000000-0000-4000-8000-000000000210"))))
        val directory = HailToneContactDirectory(backend, FakeAssociationStore())

        val failure = runCatching {
            directory.blockResolvedContact(uuid("00000000-0000-4000-8000-000000000211"))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(backend.blockedIds.isEmpty())
    }

    @Test
    fun blockedListCanRestoreUnblockWithoutALocalContactAssociation() = runBlocking {
        val inviteId = uuid("00000000-0000-4000-8000-000000000213")
        val accountId = uuid("00000000-0000-4000-8000-000000000214")
        val directory = HailToneContactDirectory(
            FakeBackend(listOf(link(inviteId, accountId).copy(account = MatchedHailToneAccount(
                accountId,
                "Verified Profile",
                blockedByMe = true,
            )))),
            FakeAssociationStore(),
        )

        val blocked = directory.listBlockedContacts()

        assertEquals(1, blocked.size)
        assertEquals(accountId, blocked.single().account.userId)
        assertTrue(blocked.single().account.blockedByMe)
    }

    private class FakeBackend(initialLinks: List<HailToneContactLink>) : HailToneContactDirectoryBackend {
        var links = initialLinks
        var createdInvite = ContactInviteCapability(uuid("00000000-0000-4000-8000-000000000212"), "b".repeat(64))
        var acceptedLink: HailToneContactLink? = null
        var listCalls = 0
        var lastAcceptedCode: String? = null
        val blockedIds = mutableListOf<UUID>()
        val unblockedIds = mutableListOf<UUID>()

        override suspend fun listLinkedContacts(): List<HailToneContactLink> {
            listCalls++
            return links
        }

        override suspend fun createInvite(): ContactInviteCapability = createdInvite

        override suspend fun acceptInvite(code: String): HailToneContactLink {
            lastAcceptedCode = code
            return requireNotNull(acceptedLink)
        }

        override suspend fun block(userId: UUID) {
            blockedIds += userId
        }

        override suspend fun unblock(userId: UUID) {
            unblockedIds += userId
        }
    }

    private class FakeAssociationStore : ContactInviteAssociationStore {
        private val associations = mutableMapOf<String, UUID>()
        override fun invitationForContact(lookupKey: String): UUID? = associations[lookupKey]
        override fun associateContact(lookupKey: String, invitationId: UUID) {
            associations[lookupKey] = invitationId
        }
    }

    private companion object {
        fun uuid(value: String) = UUID.fromString(value)
        fun contact() = LocalPhoneContact(
            contactId = 42,
            lookupKey = "private local contact key",
            displayName = "Untrusted Local Name",
            phoneNumbers = listOf(LocalPhoneNumber("+15555550100", NormalizedPhoneNumber("+15555550100"))),
            photoUri = null,
        )
        fun link(invitationId: UUID, userId: UUID) = HailToneContactLink(
            invitationId,
            MatchedHailToneAccount(userId, "Verified Profile"),
        )
    }
}
