package com.pupsikcall.app

import android.content.Context
import android.content.SharedPreferences
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

internal data class HailToneContactLink(
    val invitationId: UUID,
    val account: MatchedHailToneAccount,
)

internal interface HailToneContactDirectoryBackend {
    suspend fun listLinkedContacts(): List<HailToneContactLink>
    suspend fun createInvite(): ContactInviteCapability
    suspend fun acceptInvite(code: String): HailToneContactLink
    suspend fun block(userId: UUID)
    suspend fun unblock(userId: UUID)
}

internal interface ContactInviteAssociationStore {
    fun invitationForContact(lookupKey: String): UUID?
    fun associateContact(lookupKey: String, invitationId: UUID)
}

internal class HailToneContactDirectory(
    private val backend: HailToneContactDirectoryBackend?,
    private val associations: ContactInviteAssociationStore,
) : SelectedContactIdentityMatcher {
    override suspend fun matchSelectedContact(contact: LocalPhoneContact): MatchedHailToneAccount? {
        val invitationId = associations.invitationForContact(contact.lookupKey) ?: return null
        return requireBackend().listLinkedContacts()
            .firstOrNull { it.invitationId == invitationId }
            ?.account
    }

    suspend fun createInviteFor(contact: LocalPhoneContact): ContactInviteCapability {
        val invite = requireBackend().createInvite()
        associations.associateContact(contact.lookupKey, invite.invitationId)
        return invite
    }

    suspend fun acceptInviteFor(contact: LocalPhoneContact, input: String): HailToneContactLink {
        val code = normalizeContactInvitationCode(input)
            ?: throw IllegalArgumentException("Invalid HailTone invite code")
        val link = requireBackend().acceptInvite(code)
        associations.associateContact(contact.lookupKey, link.invitationId)
        return link
    }

    suspend fun listBlockedContacts(): List<HailToneContactLink> =
        requireBackend().listLinkedContacts().filter { it.account.blockedByMe }

    suspend fun blockResolvedContact(userId: UUID) {
        requireLinkedUser(userId)
        requireBackend().block(userId)
    }

    suspend fun unblockResolvedContact(userId: UUID) {
        requireLinkedUser(userId)
        requireBackend().unblock(userId)
    }

    private suspend fun requireLinkedUser(userId: UUID) {
        require(requireBackend().listLinkedContacts().any { it.account.userId == userId }) {
            "HailTone contact is unavailable"
        }
    }

    private fun requireBackend(): HailToneContactDirectoryBackend =
        backend ?: throw IllegalStateException("Authenticated HailTone contact service is unavailable")
}

internal class SupabaseHailToneContactDirectoryBackend(
    private val client: SupabaseClient?,
) : HailToneContactDirectoryBackend {
    override suspend fun listLinkedContacts(): List<HailToneContactLink> =
        authenticatedClient().postgrest.rpc("list_hailtone_contacts")
            .decodeList<JsonObject>()
            .mapNotNull { it.toContactLink() }

    override suspend fun createInvite(): ContactInviteCapability {
        val row = authenticatedClient().postgrest.rpc("create_hailtone_contact_invite")
            .decodeList<JsonObject>().singleOrNull()
            ?: throw IllegalStateException("Invite could not be created")
        val invitationId = row.uuidOrNull("contact_invite_id")
            ?: throw IllegalStateException("Invite response was invalid")
        val code = row.stringOrNull("invitation_code")
            ?.takeIf { normalizeContactInvitationCode(it) != null }
            ?: throw IllegalStateException("Invite response was invalid")
        return ContactInviteCapability(invitationId, code)
    }

    override suspend fun acceptInvite(code: String): HailToneContactLink {
        val row = authenticatedClient().postgrest.rpc(
            "accept_hailtone_contact_invite",
            buildJsonObject { put("p_invitation_code", JsonPrimitive(code)) },
        ).decodeList<JsonObject>().singleOrNull()
            ?: throw IllegalStateException("Invite could not be accepted")
        return row.toContactLink()
            ?: throw IllegalStateException("Invite response was invalid")
    }

    override suspend fun block(userId: UUID) {
        authenticatedClient().postgrest.rpc(
            "block_hailtone_contact",
            buildJsonObject { put("p_contact_user_id", JsonPrimitive(userId.toString())) },
        )
    }

    override suspend fun unblock(userId: UUID) {
        authenticatedClient().postgrest.rpc(
            "unblock_hailtone_contact",
            buildJsonObject { put("p_contact_user_id", JsonPrimitive(userId.toString())) },
        )
    }

    private fun authenticatedClient(): SupabaseClient {
        val configured = client ?: throw IllegalStateException("Authentication is unavailable")
        val currentUserId = configured.auth.currentSessionOrNull()?.user?.id
        if (currentUserId == null || runCatching { UUID.fromString(currentUserId) }.isFailure) {
            throw IllegalStateException("Authentication session is unavailable")
        }
        return configured
    }

    private fun JsonObject.toContactLink(): HailToneContactLink? {
        val invitationId = uuidOrNull("contact_invite_id") ?: return null
        val userId = uuidOrNull("contact_user_id") ?: return null
        return HailToneContactLink(
            invitationId = invitationId,
            account = MatchedHailToneAccount(
                userId = userId,
                displayName = stringOrNull("display_name"),
                username = stringOrNull("username"),
                avatarPath = stringOrNull("avatar_path"),
                blockedByMe = booleanOrFalse("blocked_by_me"),
                blockedMe = booleanOrFalse("blocked_me"),
            ),
        )
    }

    private fun JsonObject.uuidOrNull(key: String): UUID? =
        stringOrNull(key)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.booleanOrFalse(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
}

internal class AndroidContactInviteAssociationStore(context: Context) : ContactInviteAssociationStore {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun invitationForContact(lookupKey: String): UUID? =
        preferences.getString(keyFor(lookupKey), null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    override fun associateContact(lookupKey: String, invitationId: UUID) {
        preferences.edit().putString(keyFor(lookupKey), invitationId.toString()).apply()
    }

    private fun keyFor(lookupKey: String) = "$ASSOCIATION_PREFIX$lookupKey"

    private companion object {
        const val PREFERENCES_NAME = "hailtone_contact_invite_associations"
        const val ASSOCIATION_PREFIX = "invite_for_contact:"
    }
}