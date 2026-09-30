package com.pupsikcall.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.util.UUID

class LocalContactsTest {
    @Test
    fun phoneNormalizationRemovesFormattingAndPreservesInternationalPrefix() {
        assertEquals(
            NormalizedPhoneNumber("+12025550123"),
            normalizeLocalPhoneNumber("+1 (202) 555-0123"),
        )
        assertEquals(
            NormalizedPhoneNumber("+12025550123"),
            normalizeLocalPhoneNumber("+١ ٢٠٢ ٥٥٥ ٠١٢٣"),
        )
    }

    @Test
    fun phoneNormalizationPreservesExtensionsAndRejectsUnsupportedText() {
        assertEquals(
            NormalizedPhoneNumber("2025550123;ext=42"),
            normalizeLocalPhoneNumber("202-555-0123 ext. 42"),
        )
        assertTrue(normalizeLocalPhoneNumber("202-555-0123 x 42") != normalizeLocalPhoneNumber("202-555-0123"))
        assertNull(normalizeLocalPhoneNumber("1-800-FLOWERS"))
        assertNull(normalizeLocalPhoneNumber("---"))
    }

    @Test
    fun assemblyDeduplicatesNumbersWithinContactButNotAcrossContacts() {
        val contacts = assembleLocalContacts(
            listOf(
                record(2, "other", "Same Person", "+1 202 555 0123"),
                record(1, "owner", "Same Person", "+1 (202) 555-0123"),
                record(1, "owner", "Same Person", "+12025550123"),
            ),
        )

        assertEquals(2, contacts.size)
        assertEquals(1, contacts.first { it.lookupKey == "owner" }.phoneNumbers.size)
        assertEquals(1, contacts.first { it.lookupKey == "other" }.phoneNumbers.size)
    }

    @Test
    fun assemblyUsesStableAlphabeticAndLookupKeyOrdering() {
        val contacts = assembleLocalContacts(
            listOf(
                record(4, "z", "Zoe", "555-0004"),
                record(3, "b", "Alex", "555-0003"),
                record(2, "a", "Alex", "555-0002"),
                record(1, "m", "Mia", "555-0001"),
            ),
        )

        assertEquals(listOf("a", "b", "m", "z"), contacts.map(LocalPhoneContact::lookupKey))
    }

    @Test
    fun permissionAndLoadResultsMapToDistinctUiStates() {
        assertEquals(
            ContactsState.PermissionRequired(ContactsPermissionStatus.DENIED),
            contactsState(ContactsPermissionStatus.DENIED),
        )
        assertEquals(ContactsState.Loading, contactsState(ContactsPermissionStatus.GRANTED))
        assertEquals(ContactsState.Empty, contactsState(ContactsPermissionStatus.GRANTED, Result.success(emptyList())))
        assertEquals(ContactsState.Error, contactsState(ContactsPermissionStatus.GRANTED, Result.failure(IllegalStateException())))
    }

    @Test
    fun permissionStatusDistinguishesFirstRequestDenialAndSettingsRequired() {
        assertEquals(ContactsPermissionStatus.NOT_REQUESTED, contactsPermissionStatus(false, false, false))
        assertEquals(ContactsPermissionStatus.GRANTED, contactsPermissionStatus(true, true, false))
        assertEquals(ContactsPermissionStatus.DENIED, contactsPermissionStatus(false, true, true))
        assertEquals(ContactsPermissionStatus.PERMANENTLY_DENIED, contactsPermissionStatus(false, true, false))
    }

    @Test
    fun initialsFallbackUsesNameOnlyWhenPhotoIsUnavailable() {
        assertEquals("AC", contactAvatarFallbackInitials("Alice Cooper", photoLoaded = false))
        assertNull(contactAvatarFallbackInitials("Alice Cooper", photoLoaded = true))
        assertEquals("?", contactInitials("   "))
    }

    @Test
    fun localSearchMatchesNamesAndNumbers() {
        val contacts = assembleLocalContacts(listOf(record(1, "ada", "Ada Lovelace", "+12025550123")))

        assertEquals(1, filterLocalContacts(contacts, "lovelace").size)
        assertEquals(1, filterLocalContacts(contacts, "202 555").size)
        assertTrue(filterLocalContacts(contacts, "nobody").isEmpty())
    }

    @Test
    fun unresolvedContactCannotRouteCallOrMessage() = runBlocking {
        val contact = assembleLocalContacts(listOf(record(1, "local", "Local Name", "+12025550123"))).single()
        val calls = mutableListOf<UUID>()
        val messages = mutableListOf<UUID>()
        val result = resolveContactIdentity(contact, null)

        assertEquals(ContactIdentityResolution.LookupUnavailable, result)
        assertTrue(!routeAuthenticatedContactAction(result, AuthenticatedContactAction.CALL, calls::add, messages::add))
        assertTrue(!routeAuthenticatedContactAction(result, AuthenticatedContactAction.MESSAGE, calls::add, messages::add))
        assertTrue(calls.isEmpty())
        assertTrue(messages.isEmpty())
    }

    @Test
    fun authenticatedActionsUseOnlyResolvedSupabaseUuid() = runBlocking {
        val contact = assembleLocalContacts(listOf(record(1, "local", "Spoofed Local Name", "+12025550123"))).single()
        val userId = UUID.fromString("00000000-0000-4000-8000-000000000099")
        val resolution = resolveContactIdentity(contact, SelectedContactIdentityMatcher {
            assertEquals("local", it.lookupKey)
            MatchedHailToneAccount(userId, "Verified Profile")
        })
        val calls = mutableListOf<UUID>()
        val messages = mutableListOf<UUID>()

        assertTrue(routeAuthenticatedContactAction(resolution, AuthenticatedContactAction.CALL, calls::add, messages::add))
        assertTrue(routeAuthenticatedContactAction(resolution, AuthenticatedContactAction.MESSAGE, calls::add, messages::add))
        assertEquals(listOf(userId), calls)
        assertEquals(listOf(userId), messages)
        assertEquals("Verified Profile", (resolution as ContactIdentityResolution.Resolved).account.displayName)
    }

    @Test
    fun identityMatcherReceivesSelectedContactAssociationRatherThanAnIndividualPhoneNumber() = runBlocking {
        val contact = assembleLocalContacts(
            listOf(
                record(1, "local", "Local Name", "+12025550123"),
                record(1, "local", "Local Name", "+12025550124"),
            ),
        ).single()
        var lookupCount = 0
        val resolution = resolveContactIdentity(contact, SelectedContactIdentityMatcher { selected ->
            lookupCount++
            assertEquals(contact.lookupKey, selected.lookupKey)
            assertEquals(2, selected.phoneNumbers.size)
            null
        })
        assertEquals(1, lookupCount)
        assertEquals(ContactIdentityResolution.Unverified, resolution)
    }

    @Test
    fun identityLookupFailureRemainsUnverifiedAndCannotAuthorizeActions() = runBlocking {
        val contact = assembleLocalContacts(listOf(record(1, "local", "Local Name", "+12025550123"))).single()
        val resolution = resolveContactIdentity(contact, SelectedContactIdentityMatcher {
            throw IllegalStateException("private backend details")
        })
        val routedIds = mutableListOf<UUID>()

        assertEquals(ContactIdentityResolution.Failed, resolution)
        assertTrue(shouldOfferContactInvite(resolution))
        assertTrue(!routeAuthenticatedContactAction(resolution, AuthenticatedContactAction.CALL, routedIds::add, routedIds::add))
        assertTrue(routedIds.isEmpty())
    }

    @Test
    fun blockedAuthoritativeContactCannotRouteCallOrMessage() {
        val userId = UUID.fromString("00000000-0000-4000-8000-000000000097")
        val resolution = ContactIdentityResolution.Resolved(
            MatchedHailToneAccount(userId, "Verified Profile", blockedByMe = true),
        )
        val routedIds = mutableListOf<UUID>()

        assertTrue(!routeAuthenticatedContactAction(resolution, AuthenticatedContactAction.CALL, routedIds::add, routedIds::add))
        assertTrue(!routeAuthenticatedContactAction(resolution, AuthenticatedContactAction.MESSAGE, routedIds::add, routedIds::add))
        assertTrue(routedIds.isEmpty())
    }

    @Test
    fun contactBlockedByTheOtherAccountCannotRouteCallOrMessage() {
        val userId = UUID.fromString("00000000-0000-4000-8000-000000000096")
        val resolution = ContactIdentityResolution.Resolved(
            MatchedHailToneAccount(userId, "Verified Profile", blockedMe = true),
        )
        val routedIds = mutableListOf<UUID>()

        assertTrue(!routeAuthenticatedContactAction(resolution, AuthenticatedContactAction.CALL, routedIds::add, routedIds::add))
        assertTrue(!routeAuthenticatedContactAction(resolution, AuthenticatedContactAction.MESSAGE, routedIds::add, routedIds::add))
        assertTrue(routedIds.isEmpty())
    }

    @Test
    fun contactDraftRequiresRealNameAndValidPhoneAndShareUsesOnlyLocalDetails() {
        assertNull(newLocalContact("  ", "+12025550123"))
        assertNull(newLocalContact("Name", "not a phone"))
        assertEquals(NewLocalContact("Name", "+12025550123"), newLocalContact(" Name ", " +12025550123 "))
        val contact = assembleLocalContacts(listOf(record(1, "local", "Ada Example", "+12025550123"))).single()
        assertEquals("Ada Example\n+12025550123", localContactShareText(contact))
    }

    @Test
    fun invitationCodesAreNormalizedButMalformedValuesAreRejected() {
        val code = "a".repeat(64)
        assertEquals(code, normalizeContactInvitationCode(" ${code.uppercase()} "))
        assertNull(normalizeContactInvitationCode("not-an-invite"))
        assertNull(normalizeContactInvitationCode("a".repeat(63)))
    }

    @Test
    fun inviteIsOfferedOnlyAfterLookupStopsAndVerifiedMatchesDoNotGetInvited() {
        assertTrue(!shouldOfferContactInvite(ContactIdentityResolution.Checking))
        assertTrue(shouldOfferContactInvite(ContactIdentityResolution.LookupUnavailable))
        assertTrue(shouldOfferContactInvite(ContactIdentityResolution.Unverified))
        assertTrue(shouldOfferContactInvite(ContactIdentityResolution.Failed))
        assertTrue(!shouldOfferContactInvite(ContactIdentityResolution.Resolved(MatchedHailToneAccount(
            UUID.fromString("00000000-0000-4000-8000-000000000098"), "Verified Name",
        ))))
    }

    @Test
    fun refreshedContactsUpdateOrCloseTheSelectedDetailsContact() {
        val original = assembleLocalContacts(listOf(record(1, "local", "Original Name", "+12025550123"))).single()
        val updated = assembleLocalContacts(listOf(record(1, "local", "Updated Name", "+12025550123"))).single()

        assertEquals(updated, reconcileSelectedLocalContact(original, listOf(updated)))
        assertNull(reconcileSelectedLocalContact(original, emptyList()))
    }

    private fun record(id: Long, key: String, name: String, number: String) =
        ContactRecord(id, key, name, number, null)
}