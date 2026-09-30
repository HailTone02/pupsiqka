package com.pupsikcall.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AndroidMessageKeyVault(context: Context, private val userId: UUID) {
    private val preferences = context.applicationContext.getSharedPreferences("hailtone_message_keys", Context.MODE_PRIVATE)
    private val alias = "hailtone_message_${userId}"
    private val lock = Any()

    fun getOrCreateDeviceId(): String = getOrCreate("device_id") { UUID.randomUUID().toString().replace("-", "").uppercase() }

    fun getOrCreateCryptoPassphrase(): String = getOrCreate("crypto_passphrase") {
        ByteArray(32).also(SecureRandom()::nextBytes).let { Base64.encodeToString(it, Base64.NO_WRAP) }
    }

    fun getVerifiedFingerprint(peerUserId: UUID, peerDeviceId: String): String? =
        preferences.getString("$userId:$peerUserId:$peerDeviceId", null)?.let(::decrypt)

    fun verifyFingerprint(peerUserId: UUID, peerDeviceId: String, fingerprint: String) {
        preferences.edit().putString("$userId:$peerUserId:$peerDeviceId", encrypt(fingerprint)).apply()
    }

    fun messageHistoryKey(): SecretKey = getOrCreateKey()

    private fun getOrCreate(name: String, create: () -> String): String = synchronized(lock) {
        preferences.getString("$userId:$name", null)?.let(::decrypt) ?: create().also { value ->
            preferences.edit().putString("$userId:$name", encrypt(value)).commit()
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val sealed = Base64.decode(value, Base64.NO_WRAP)
        require(sealed.size > GCM_IV_BYTES) { "Encrypted messaging state is invalid" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, sealed, 0, GCM_IV_BYTES))
        return cipher.doFinal(sealed, GCM_IV_BYTES, sealed.size - GCM_IV_BYTES).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}

internal class AndroidEncryptedMessageStore(context: Context, userId: UUID, private val vault: AndroidMessageKeyVault) {
    private val file = AtomicFile(File(File(context.applicationContext.noBackupFilesDir, "secure-messages"), "$userId.bin"))
    private val lock = Any()

    fun load(conversationId: UUID? = null): List<DirectMessage> = synchronized(lock) {
        if (!file.baseFile.exists()) return@synchronized emptyList()
        val plaintext = decrypt(file.readFully())
        val rows = Json.parseToJsonElement(plaintext).jsonArray.map { it.jsonObject.toDirectMessage() }
        rows.filter { conversationId == null || it.conversationId == conversationId }
            .sortedWith(compareBy({ it.createdAt }, { it.id.toString() }))
    }

    fun upsert(messages: Iterable<DirectMessage>) = synchronized(lock) {
        val merged = linkedMapOf<UUID, DirectMessage>()
        load().forEach { merged[it.id] = it }
        messages.forEach { merged[it.id] = it }
        val data = buildJsonArray { merged.values.sortedWith(compareBy({ it.createdAt }, { it.id.toString() })).forEach { add(it.toJson()) } }
            .toString().toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try {
            output.write(encrypt(data))
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, vault.messageHistoryKey())
        return cipher.iv + cipher.doFinal(plaintext)
    }

    private fun decrypt(sealed: ByteArray): String {
        require(sealed.size > IV_BYTES) { "Encrypted message history is invalid" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, vault.messageHistoryKey(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES).toString(Charsets.UTF_8)
    }

    private companion object {
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

private fun DirectMessage.toJson() = buildJsonObject {
    put("id", id.toString())
    put("conversationId", conversationId.toString())
    put("senderId", senderId.toString())
    put("clientMessageId", clientMessageId.toString())
    put("body", body)
    put("createdAt", createdAt)
    put("deliveryState", deliveryState.name)
    put("isRead", isRead)
    put("pendingOlmRequestIds", JsonArray(pendingOlmRequestIds.map(::JsonPrimitive)))
}

private fun JsonObject.toDirectMessage() = DirectMessage(
    id = UUID.fromString(getValue("id").jsonPrimitive.content),
    conversationId = UUID.fromString(getValue("conversationId").jsonPrimitive.content),
    senderId = UUID.fromString(getValue("senderId").jsonPrimitive.content),
    clientMessageId = UUID.fromString(getValue("clientMessageId").jsonPrimitive.content),
    body = getValue("body").jsonPrimitive.content,
    createdAt = getValue("createdAt").jsonPrimitive.content,
    deliveryState = get("deliveryState")?.jsonPrimitive?.contentOrNull?.let(MessageDeliveryState::valueOf) ?: MessageDeliveryState.DELIVERED,
    isRead = get("isRead")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
    pendingOlmRequestIds = get("pendingOlmRequestIds")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
)