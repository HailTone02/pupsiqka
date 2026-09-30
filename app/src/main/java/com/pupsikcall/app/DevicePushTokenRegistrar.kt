package com.pupsikcall.app

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

internal class DevicePushTokenRegistrar(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    suspend fun registerCurrentToken(signaling: AuthenticatedCallSignaling): Boolean {
        val firebaseApp = FirebaseApp.getApps(appContext).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME } ?: return false
        val userId = signaling.awaitAuthenticatedUserId() ?: return false
        val token = fetchToken(firebaseApp) ?: return false
        if (signaling.authenticatedUserId() != userId) return false
        signaling.registerCallPushToken(installationId(), token)
        return true
    }

    suspend fun registerRotatedToken(signaling: AuthenticatedCallSignaling, token: String): Boolean {
        if (token.isBlank()) return false
        val userId = signaling.awaitAuthenticatedUserId() ?: return false
        if (signaling.authenticatedUserId() != userId) return false
        signaling.registerCallPushToken(installationId(), token)
        return true
    }

    suspend fun unregisterCurrentInstallation(signaling: AuthenticatedCallSignaling): Boolean {
        val userId = signaling.awaitAuthenticatedUserId() ?: return false
        if (signaling.authenticatedUserId() != userId) return false
        signaling.unregisterCallPushToken(installationId())
        return true
    }

    private suspend fun fetchToken(firebaseApp: FirebaseApp): String? = suspendCancellableCoroutine { continuation ->
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!continuation.isActive) return@addOnCompleteListener
                continuation.resume(task.result.takeIf { task.isSuccessful && it.isNotBlank() })
            }
        } catch (_: Exception) {
            if (continuation.isActive) continuation.resume(null)
        }
    }

    private fun installationId(): UUID = synchronized(installationLock) {
        preferences.getString(INSTALLATION_ID_KEY, null)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: UUID.randomUUID().also { created ->
                preferences.edit().putString(INSTALLATION_ID_KEY, created.toString()).commit()
            }
    }

    private companion object {
        const val PREFERENCES_NAME = "device_push_registration"
        const val INSTALLATION_ID_KEY = "installation_id"
        val installationLock = Any()
    }
}