package com.pupsikcall.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

    private fun record(id: Long, key: String, name: String, number: String) =
        ContactRecord(id, key, name, number, null)
}