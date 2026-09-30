package com.pupsikcall.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

internal class IncomingCallFirebaseMessagingService : FirebaseMessagingService() {
    private val lifecycle by lazy {
        IncomingCallNotificationLifecycle(SharedPreferencesCallPushEventStore(applicationContext))
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val event = IncomingCallPushEvent.fromData(message.data) ?: return
        runBlocking(Dispatchers.IO) {
            val signaling = createCallSignaling()
            try {
                withTimeout(10_000) {
                    val localUserId = signaling.awaitAuthenticatedUserId() ?: return@withTimeout
                    val verifiedSession = signaling.loadPendingInvitations().firstOrNull { it.callId == event.callId }
                        ?.takeIf { incomingCallRoute(localUserId, it) != null }
                    when (lifecycle.apply(event, verifiedSession)) {
                        CallNotificationTransition.SHOW -> {
                            val session = verifiedSession ?: return@withTimeout
                            IncomingCallNotificationManager.showCall(this@IncomingCallFirebaseMessagingService, session.callId, session.callerUserId)
                        }
                        CallNotificationTransition.CANCEL -> IncomingCallNotificationManager.cancel(this@IncomingCallFirebaseMessagingService, event.callId)
                        CallNotificationTransition.IGNORE -> Unit
                    }
                }
            } catch (_: Exception) {
                // A failed verification must never display a notification from unverified payload data.
            } finally {
                signaling.close()
            }
        }
    }

    override fun onNewToken(token: String) {
        runBlocking(Dispatchers.IO) {
            val signaling = createCallSignaling()
            try {
                withTimeout(10_000) {
                    DevicePushTokenRegistrar(applicationContext).registerRotatedToken(signaling, token)
                }
            } catch (_: Exception) {
                // Registration retries when the authenticated app next becomes active.
            } finally {
                signaling.close()
            }
        }
    }

    private fun createCallSignaling() = AuthenticatedCallSignaling(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        listener = object : AuthenticatedCallSignaling.Listener {
            override fun onAuthenticatedCallSessionChanged(session: AuthenticatedCallSession) = Unit
            override fun onAuthenticatedCallSessionLost() = Unit
            override fun onAuthenticatedCallError() = Unit
            override fun onRemoteOffer(session: AuthenticatedCallSession, sdp: String) = Unit
            override fun onRemoteAnswer(session: AuthenticatedCallSession, sdp: String) = Unit
            override fun onRemoteIceCandidate(session: AuthenticatedCallSession, candidate: LocalIceCandidate) = Unit
        },
    )
}