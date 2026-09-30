package com.pupsikcall.app

import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException

internal data class NormalizedPhoneNumber(val value: String)

internal data class LocalPhoneNumber(
    val displayValue: String,
    val normalized: NormalizedPhoneNumber?,
)

internal data class LocalPhoneContact(
    val contactId: Long,
    val lookupKey: String,
    val displayName: String,
    val phoneNumbers: List<LocalPhoneNumber>,
    val photoUri: String?,
)

internal data class ContactRecord(
    val contactId: Long,
    val lookupKey: String?,
    val displayName: String?,
    val phoneNumber: String?,
    val photoUri: String?,
)

internal fun interface LocalContactsRepository {
    suspend fun load(): List<LocalPhoneContact>
}

internal enum class ContactsPermissionStatus {
    GRANTED,
    NOT_REQUESTED,
    DENIED,
    PERMANENTLY_DENIED,
}

internal sealed interface ContactsState {
    data class PermissionRequired(val status: ContactsPermissionStatus) : ContactsState
    data object Loading : ContactsState
    data class Loaded(val contacts: List<LocalPhoneContact>) : ContactsState
    data object Empty : ContactsState
    data object Error : ContactsState
}

internal data class MatchedHailToneAccount(
    val userId: UUID,
    val displayName: String?,
    val username: String? = null,
    val avatarPath: String? = null,
    val blockedByMe: Boolean = false,
    val blockedMe: Boolean = false,
)

internal fun interface SelectedContactIdentityMatcher {
    suspend fun matchSelectedContact(contact: LocalPhoneContact): MatchedHailToneAccount?
}

internal sealed interface ContactIdentityResolution {
    data object Checking : ContactIdentityResolution
    data object LookupUnavailable : ContactIdentityResolution
    data object Unverified : ContactIdentityResolution
    data object Failed : ContactIdentityResolution
    data class Resolved(val account: MatchedHailToneAccount) : ContactIdentityResolution
}

internal enum class AuthenticatedContactAction { CALL, MESSAGE }

internal suspend fun resolveContactIdentity(
    contact: LocalPhoneContact,
    matcher: SelectedContactIdentityMatcher?,
): ContactIdentityResolution {
    if (matcher == null) return ContactIdentityResolution.LookupUnavailable
    return try {
        matcher.matchSelectedContact(contact)?.let(ContactIdentityResolution::Resolved)
            ?: ContactIdentityResolution.Unverified
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ContactIdentityResolution.Failed
    }
}

internal fun routeAuthenticatedContactAction(
    resolution: ContactIdentityResolution,
    action: AuthenticatedContactAction,
    onCall: (UUID) -> Unit,
    onMessage: (UUID) -> Unit,
): Boolean {
    val account = (resolution as? ContactIdentityResolution.Resolved)?.account ?: return false
    if (account.blockedByMe || account.blockedMe) return false
    when (action) {
        AuthenticatedContactAction.CALL -> onCall(account.userId)
        AuthenticatedContactAction.MESSAGE -> onMessage(account.userId)
    }
    return true
}

internal data class ContactInviteCapability(val invitationId: UUID, val code: String)

internal fun normalizeContactInvitationCode(input: String): String? =
    input.trim().lowercase(Locale.ROOT).takeIf { it.matches(Regex("[0-9a-f]{64}")) }

internal fun shouldOfferContactInvite(resolution: ContactIdentityResolution): Boolean =
    resolution != ContactIdentityResolution.Checking && resolution !is ContactIdentityResolution.Resolved

internal fun reconcileSelectedLocalContact(
    selected: LocalPhoneContact?,
    refreshedContacts: List<LocalPhoneContact>,
): LocalPhoneContact? = selected?.let { old -> refreshedContacts.firstOrNull { it.lookupKey == old.lookupKey } }

internal data class NewLocalContact(val displayName: String, val phoneNumber: String)

internal fun newLocalContact(displayName: String, phoneNumber: String): NewLocalContact? {
    val name = displayName.trim()
    val phone = phoneNumber.trim()
    if (name.isEmpty() || name.length > 120 || name.any(Char::isISOControl)) return null
    if (phone.isEmpty() || normalizeLocalPhoneNumber(phone) == null) return null
    return NewLocalContact(name, phone)
}

internal fun localContactShareText(contact: LocalPhoneContact): String = buildString {
    append(contact.displayName)
    contact.phoneNumbers.forEach { number ->
        append('\n')
        append(number.displayValue)
    }
}

internal fun contactsPermissionStatus(
    granted: Boolean,
    requestedBefore: Boolean,
    shouldShowRationale: Boolean,
): ContactsPermissionStatus = when {
    granted -> ContactsPermissionStatus.GRANTED
    !requestedBefore -> ContactsPermissionStatus.NOT_REQUESTED
    shouldShowRationale -> ContactsPermissionStatus.DENIED
    else -> ContactsPermissionStatus.PERMANENTLY_DENIED
}

internal fun contactsState(
    permission: ContactsPermissionStatus,
    result: Result<List<LocalPhoneContact>>? = null,
): ContactsState {
    if (permission != ContactsPermissionStatus.GRANTED) {
        return ContactsState.PermissionRequired(permission)
    }
    if (result == null) return ContactsState.Loading
    if (result.isFailure) return ContactsState.Error
    return result.getOrThrow().takeIf(List<LocalPhoneContact>::isNotEmpty)
        ?.let(ContactsState::Loaded)
        ?: ContactsState.Empty
}

internal fun normalizeLocalPhoneNumber(raw: String): NormalizedPhoneNumber? {
    val value = raw.trim()
    if (value.isEmpty()) return null

    val extensionMatch = phoneExtension.find(value)
    val numberPart = extensionMatch?.let { value.substring(0, it.range.first) } ?: value
    val normalized = StringBuilder()
    var hasDigit = false

    for (character in numberPart) {
        val digit = Character.digit(character, 10)
        when {
            digit >= 0 -> {
                normalized.append(digit)
                hasDigit = true
            }
            character == '+' && normalized.isEmpty() -> normalized.append('+')
            character.isWhitespace() || character in "-()./" -> Unit
            else -> return null
        }
    }

    if (!hasDigit) return null
    extensionMatch?.groupValues?.get(1)?.let { extension ->
        val extensionDigits = extension.mapNotNull { Character.digit(it, 10).takeIf { digit -> digit >= 0 } }
            .joinToString("")
        if (extensionDigits.isNotEmpty()) normalized.append(";ext=").append(extensionDigits)
    }
    return NormalizedPhoneNumber(normalized.toString())
}

internal fun assembleLocalContacts(records: Iterable<ContactRecord>): List<LocalPhoneContact> {
    val contacts = linkedMapOf<String, ContactAccumulator>()
    val orderedRecords = records.sortedWith(
        compareBy<ContactRecord>(
            { it.lookupKey.orEmpty() },
            { it.displayName.orEmpty().lowercase(Locale.ROOT) },
            { it.displayName.orEmpty() },
            { it.phoneNumber.orEmpty() },
            { it.photoUri.orEmpty() },
            { it.contactId },
        ),
    )

    orderedRecords.forEach { record ->
        val lookupKey = record.lookupKey?.takeIf(String::isNotBlank) ?: return@forEach
        val phoneDisplay = record.phoneNumber?.trim()?.takeIf(String::isNotEmpty) ?: return@forEach
        val normalized = normalizeLocalPhoneNumber(phoneDisplay)
        val number = LocalPhoneNumber(phoneDisplay, normalized)
        val contact = contacts.getOrPut(lookupKey) {
            ContactAccumulator(record.contactId, lookupKey)
        }
        if (contact.displayName.isNullOrBlank()) {
            contact.displayName = record.displayName?.trim()?.takeIf(String::isNotEmpty)
        }
        record.photoUri?.takeIf(String::isNotBlank)?.let { photo ->
            if (contact.photoUri == null || photo < contact.photoUri!!) contact.photoUri = photo
        }
        val dedupeKey = normalized?.value ?: phoneDisplay.filterNot(::isPhoneFormattingCharacter).lowercase(Locale.ROOT)
        val existing = contact.phoneNumbers[dedupeKey]
        if (existing == null || phoneDisplay < existing.displayValue) {
            contact.phoneNumbers[dedupeKey] = number
        }
    }

    return contacts.values.mapNotNull { contact ->
        val phoneNumbers = contact.phoneNumbers.values.sortedWith(
            compareBy({ it.normalized?.value.orEmpty() }, { it.displayValue }),
        )
        if (phoneNumbers.isEmpty()) return@mapNotNull null
        LocalPhoneContact(
            contactId = contact.contactId,
            lookupKey = contact.lookupKey,
            displayName = contact.displayName ?: phoneNumbers.first().displayValue,
            phoneNumbers = phoneNumbers,
            photoUri = contact.photoUri,
        )
    }.sortedWith(
        compareBy<LocalPhoneContact>(
            { it.displayName.lowercase(Locale.ROOT) },
            { it.displayName },
            { it.lookupKey },
            { it.phoneNumbers.first().normalized?.value.orEmpty() },
        ),
    )
}

internal fun filterLocalContacts(contacts: List<LocalPhoneContact>, query: String): List<LocalPhoneContact> {
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    if (normalizedQuery.isEmpty()) return contacts
    val normalizedNumberQuery = normalizeLocalPhoneNumber(query)?.value
    return contacts.filter { contact ->
        contact.displayName.lowercase(Locale.ROOT).contains(normalizedQuery) ||
            contact.phoneNumbers.any { phone ->
                phone.displayValue.lowercase(Locale.ROOT).contains(normalizedQuery) ||
                    normalizedNumberQuery?.let { phone.normalized?.value?.contains(it) } == true
            }
    }
}

internal fun contactAvatarFallbackInitials(displayName: String, photoLoaded: Boolean): String? =
    if (photoLoaded) null else contactInitials(displayName)

internal fun contactInitials(displayName: String): String {
    val parts = displayName.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    if (parts.isEmpty()) return "?"
    return parts.take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
}

private val phoneExtension = Regex("(?i)\\s*(?:ext(?:ension)?\\.?|x|#)\\s*([\\p{Nd}]+)\\s*$")

private fun isPhoneFormattingCharacter(character: Char): Boolean =
    character.isWhitespace() || character in "-()./"

private class ContactAccumulator(
    val contactId: Long,
    val lookupKey: String,
) {
    var displayName: String? = null
    var photoUri: String? = null
    val phoneNumbers = linkedMapOf<String, LocalPhoneNumber>()
}