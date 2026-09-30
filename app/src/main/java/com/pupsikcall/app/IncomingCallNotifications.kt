package com.pupsikcall.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.UUID

internal data class IncomingCallPushEvent(
    val callId: UUID,
) {
    companion object {
        fun fromData(data: Map<String, String>): IncomingCallPushEvent? {
            if (data.keys != setOf("call_id")) return null
            val callId = data["call_id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
            return IncomingCallPushEvent(callId)
        }
    }
}

internal data class StoredCallPushState(
    val authenticatedUserId: UUID,
    val callerUserId: UUID,
    val status: AuthenticatedCallStatus,
)

internal interface CallPushEventStore {
    fun read(callId: UUID): StoredCallPushState?
    fun write(callId: UUID, state: StoredCallPushState)
    fun remove(callId: UUID)
    fun clearAll()
}

internal enum class CallNotificationTransition {
    SHOW,
    CANCEL,
    IGNORE,
}

internal class IncomingCallNotificationLifecycle(private val store: CallPushEventStore) {
    @Synchronized
    fun apply(
        event: IncomingCallPushEvent,
        verifiedSession: AuthenticatedCallSession?,
        authenticatedUserId: UUID?,
    ): CallNotificationTransition {
        val previous = store.read(event.callId)
        if (verifiedSession == null || verifiedSession.callId != event.callId ||
            verifiedSession.status != AuthenticatedCallStatus.RINGING ||
            incomingCallRoute(authenticatedUserId, verifiedSession) == null
        ) {
            store.remove(event.callId)
            return CallNotificationTransition.CANCEL
        }
        val verifiedUserId = authenticatedUserId ?: return CallNotificationTransition.CANCEL
        if (previous != null && previous.authenticatedUserId != verifiedUserId) {
            store.write(event.callId, StoredCallPushState(verifiedUserId, verifiedSession.callerUserId, verifiedSession.status))
            return CallNotificationTransition.SHOW
        }
        if (previous != null && previous.callerUserId != verifiedSession.callerUserId) return CallNotificationTransition.IGNORE
        if (previous?.status != AuthenticatedCallStatus.RINGING) {
            store.write(event.callId, StoredCallPushState(verifiedUserId, verifiedSession.callerUserId, verifiedSession.status))
            return CallNotificationTransition.SHOW
        }
        return CallNotificationTransition.IGNORE
    }
}

internal enum class IncomingCallNotificationActionKind {
    ANSWER,
    DECLINE,
}

internal data class IncomingCallNotificationAction(
    val callId: UUID,
    val callerUserId: UUID,
    val authenticatedUserId: UUID,
    val kind: IncomingCallNotificationActionKind,
    val requestId: String,
)

internal class IncomingCallNotificationActionGate {
    private val processedRequestIds = linkedSetOf<String>()

    @Synchronized
    fun claim(action: IncomingCallNotificationAction): Boolean {
        if (action.requestId.isBlank() || !processedRequestIds.add(action.requestId)) return false
        while (processedRequestIds.size > 128) processedRequestIds.remove(processedRequestIds.first())
        return true
    }
}

internal fun dispatchIncomingCallNotificationAction(
    kind: IncomingCallNotificationActionKind,
    answer: () -> Unit,
    decline: () -> Unit,
) {
    when (kind) {
        IncomingCallNotificationActionKind.ANSWER -> answer()
        IncomingCallNotificationActionKind.DECLINE -> decline()
    }
}

internal fun validateIncomingCallNotificationAction(
    action: IncomingCallNotificationAction,
    pendingInvitations: List<AuthenticatedCallSession>,
    authenticatedUserId: UUID?,
): AuthenticatedCallSession? = pendingInvitations.firstOrNull { session ->
    action.authenticatedUserId == authenticatedUserId && session.callId == action.callId &&
        session.callerUserId == action.callerUserId && incomingCallRoute(authenticatedUserId, session) != null
}

internal class SharedPreferencesCallPushEventStore(context: Context) : CallPushEventStore {
    private val preferences: SharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(callId: UUID): StoredCallPushState? {
        val prefix = "$ENTRY_PREFIX$callId."
        val authenticatedUserId = preferences.getString(prefix + "user", null)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        val callerId = preferences.getString(prefix + "caller", null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return null
        val status = AuthenticatedCallStatus.parse(preferences.getString(prefix + "status", null)) ?: return null
        return StoredCallPushState(authenticatedUserId, callerId, status)
    }

    override fun write(callId: UUID, state: StoredCallPushState) {
        val prefix = "$ENTRY_PREFIX$callId."
        preferences.edit()
            .putString(prefix + "user", state.authenticatedUserId.toString())
            .putString(prefix + "caller", state.callerUserId.toString())
            .putString(prefix + "status", state.status.databaseValue)
            .commit()
    }

    override fun remove(callId: UUID) {
        val prefix = "$ENTRY_PREFIX$callId."
        preferences.edit().remove(prefix + "user").remove(prefix + "caller").remove(prefix + "status").commit()
    }

    override fun clearAll() {
        preferences.edit().clear().commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "incoming_call_push_events"
        const val ENTRY_PREFIX = "call."
    }
}

internal object IncomingCallNotificationManager {
    const val CHANNEL_ID = "incoming_calls"
    const val NOTIFICATION_ID = 7101
    const val ACTION_ANSWER = "com.pupsikcall.app.action.ANSWER_INCOMING_CALL"
    const val ACTION_DECLINE = "com.pupsikcall.app.action.DECLINE_INCOMING_CALL"
    const val ACTION_OPEN = "com.pupsikcall.app.action.OPEN_INCOMING_CALL"
    const val EXTRA_CALL_ID = "pupsikcall.call_id"
    const val EXTRA_CALLER_ID = "pupsikcall.caller_user_id"
    const val EXTRA_AUTHENTICATED_USER_ID = "pupsikcall.authenticated_user_id"
    const val EXTRA_REQUEST_ID = "pupsikcall.request_id"

    private val accountLock = Any()

    fun setAuthenticatedUser(context: Context, userId: UUID?) = synchronized(accountLock) {
        val preferences = accountPreferences(context)
        val nextUserId = userId?.toString()
        if (preferences.getString(AUTHENTICATED_USER_ID_KEY, null) != nextUserId) {
            clearIncomingCalls(context)
            if (nextUserId == null) preferences.edit().remove(AUTHENTICATED_USER_ID_KEY).commit()
            else preferences.edit().putString(AUTHENTICATED_USER_ID_KEY, nextUserId).commit()
        }
    }

    fun clearSession(context: Context) = synchronized(accountLock) {
        clearIncomingCalls(context)
        accountPreferences(context).edit().remove(AUTHENTICATED_USER_ID_KEY).commit()
    }

    fun isCurrentAuthenticatedUser(context: Context, userId: UUID): Boolean = synchronized(accountLock) {
        accountPreferences(context).getString(AUTHENTICATED_USER_ID_KEY, null) == userId.toString()
    }

    fun runIfCurrentAuthenticatedUser(context: Context, userId: UUID, action: () -> Unit): Boolean = synchronized(accountLock) {
        if (accountPreferences(context).getString(AUTHENTICATED_USER_ID_KEY, null) != userId.toString()) return false
        action()
        true
    }

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.incoming_call_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.incoming_call_status)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
        manager.createNotificationChannel(channel)
    }

    fun showCall(context: Context, authenticatedUserId: UUID, callId: UUID, callerUserId: UUID) = synchronized(accountLock) {
        if (accountPreferences(context).getString(AUTHENTICATED_USER_ID_KEY, null) != authenticatedUserId.toString()) return
        createChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(context.getString(R.string.incoming_call))
            .setContentText(context.getString(R.string.incoming_call_status))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setContentIntent(activityPendingIntent(context, callId, callerUserId, authenticatedUserId, IncomingCallNotificationActionKind.ANSWER, ACTION_OPEN))
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.decline),
                activityPendingIntent(context, callId, callerUserId, authenticatedUserId, IncomingCallNotificationActionKind.DECLINE, ACTION_DECLINE),
            )
            .addAction(
                android.R.drawable.sym_action_call,
                context.getString(R.string.answer),
                activityPendingIntent(context, callId, callerUserId, authenticatedUserId, IncomingCallNotificationActionKind.ANSWER, ACTION_ANSWER),
            )
        try {
            NotificationManagerCompat.from(context).notify(callId.toString(), NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // Notification permission may be denied; authenticated call handling remains available in-app.
        }
    }

    fun cancel(context: Context, callId: UUID) {
        NotificationManagerCompat.from(context).cancel(callId.toString(), NOTIFICATION_ID)
    }

    fun actionFromIntent(intent: Intent?): IncomingCallNotificationAction? {
        val kind = when (intent?.action) {
            ACTION_ANSWER -> IncomingCallNotificationActionKind.ANSWER
            ACTION_DECLINE -> IncomingCallNotificationActionKind.DECLINE
            else -> return null
        }
        val callId = intent.getStringExtra(EXTRA_CALL_ID)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        val callerUserId = intent.getStringExtra(EXTRA_CALLER_ID)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        val authenticatedUserId = intent.getStringExtra(EXTRA_AUTHENTICATED_USER_ID)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)?.takeIf(String::isNotBlank) ?: return null
        return IncomingCallNotificationAction(callId, callerUserId, authenticatedUserId, kind, requestId)
    }

    private fun clearIncomingCalls(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications
            .filter { notification ->
                notification.id == NOTIFICATION_ID &&
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        notification.notification.channelId == CHANNEL_ID
                    } else {
                        notification.notification.category == Notification.CATEGORY_CALL
                    })
            }
            .forEach { notification -> manager.cancel(notification.tag, notification.id) }
        SharedPreferencesCallPushEventStore(context).clearAll()
    }

    private fun accountPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences(ACCOUNT_PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun activityPendingIntent(
        context: Context,
        callId: UUID,
        callerUserId: UUID,
        authenticatedUserId: UUID,
        kind: IncomingCallNotificationActionKind,
        action: String,
    ): PendingIntent {
        val requestId = UUID.randomUUID().toString()
        val intent = Intent(context, MainActivity::class.java).apply {
            this.action = action
            data = Uri.parse("pupsikcall://incoming/$callId/${kind.name.lowercase()}/$requestId")
            putExtra(EXTRA_CALL_ID, callId.toString())
            putExtra(EXTRA_CALLER_ID, callerUserId.toString())
            putExtra(EXTRA_AUTHENTICATED_USER_ID, authenticatedUserId.toString())
            putExtra(EXTRA_REQUEST_ID, requestId)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            context,
            (callId.toString() + kind.name).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private const val ACCOUNT_PREFERENCES_NAME = "incoming_call_account"
    private const val AUTHENTICATED_USER_ID_KEY = "authenticated_user_id"
}