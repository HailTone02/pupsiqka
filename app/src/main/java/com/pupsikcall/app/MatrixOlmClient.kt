package com.pupsikcall.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import uniffi.matrix_sdk_crypto.CollectStrategy
import uniffi.matrix_sdk_crypto.DecryptionSettings
import uniffi.matrix_sdk_crypto.TrustRequirement
import org.matrix.rustcomponents.sdk.crypto.OlmMachine
import org.matrix.rustcomponents.sdk.crypto.Request
import org.matrix.rustcomponents.sdk.crypto.RequestType
import org.matrix.rustcomponents.sdk.crypto.DeviceLists
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal class MatrixOlmClient(
    private val userId: UUID,
    private val vault: AndroidMessageKeyVault,
    private val machine: OlmMachine,
) : AutoCloseable {
    val matrixUserId: String = matrixUserId(userId)
    val deviceId: String = machine.deviceId()

    constructor(contextDir: File, userId: UUID, vault: AndroidMessageKeyVault) : this(
        userId = userId,
        vault = vault,
        machine = OlmMachine(
            matrixUserId(userId),
            vault.getOrCreateDeviceId(),
            File(contextDir, "olm-${userId}.db").absolutePath,
            vault.getOrCreateCryptoPassphrase(),
        ),
    )

    fun outgoingRequests(): List<Request> = machine.outgoingRequests()

    fun markRequestAsSent(requestId: String, requestType: RequestType, responseBody: String) =
        machine.markRequestAsSent(requestId, requestType, responseBody)

    fun updateTrackedUsers(matrixUserIds: List<String>) = machine.updateTrackedUsers(matrixUserIds)

    fun getMissingSessions(matrixUserIds: List<String>): Request? = machine.getMissingSessions(matrixUserIds)

    fun devicesFor(matrixUserId: String) = machine.getUserDevices(matrixUserId, 0u)

    fun fingerprintFor(device: org.matrix.rustcomponents.sdk.crypto.Device): String {
        val signingKey = device.keys["ed25519:${device.deviceId}"]
            ?: throw IllegalStateException("Device signing key is unavailable")
        val curveKey = device.keys["curve25519:${device.deviceId}"]
            ?: throw IllegalStateException("Device identity key is unavailable")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${device.userId}|${device.deviceId}|$signingKey|$curveKey".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02X".format(byte) }.chunked(4).joinToString(" ")
    }

    fun encryptToDevice(
        recipientMatrixUserId: String,
        recipientDeviceId: String,
        eventType: String,
        plaintextContent: JsonObject,
    ): Request.ToDevice {
        val request = machine.createEncryptedToDeviceRequest(
            recipientMatrixUserId,
            recipientDeviceId,
            eventType,
            plaintextContent.toString(),
            CollectStrategy.ALL_DEVICES,
        ) as? Request.ToDevice ?: throw IllegalStateException("Olm session is unavailable")
        val body = Json.parseToJsonElement(request.body).jsonObject
        if (body["messages"]?.jsonObject?.get(recipientMatrixUserId)?.jsonObject?.get(recipientDeviceId)?.jsonObject == null) {
            throw IllegalStateException("Encrypted device payload is missing")
        }
        return request
    }

    fun decryptToDevice(
        senderMatrixUserId: String,
        eventType: String,
        ciphertext: JsonObject,
        envelopeId: UUID,
    ): List<JsonObject> {
        if (eventType != "m.room.encrypted") return emptyList()
        val event = buildJsonObject {
            put("sender", senderMatrixUserId)
            put("type", eventType)
            put("content", ciphertext)
        }
        val events = buildJsonObject {
            put("events", buildJsonArray { add(event) })
        }
        val result = machine.receiveSyncChanges(
            events.toString(),
            DeviceLists(emptyList(), emptyList()),
            emptyMap(),
            null,
            envelopeId.toString(),
            DecryptionSettings(TrustRequirement.UNTRUSTED),
        )
        return result.toDeviceEvents.mapNotNull { encoded ->
            runCatching { Json.parseToJsonElement(encoded).jsonObject }.getOrNull()
        }
    }

    fun identityKeys(): Map<String, String> = machine.identityKeys()

    override fun close() = machine.close()

    companion object {
        fun matrixUserId(userId: UUID): String = "@${userId}:hailtone.invalid"
    }
}