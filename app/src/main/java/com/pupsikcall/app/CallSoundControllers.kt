package com.pupsikcall.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

internal enum class CallSoundKind {
    OUTGOING_RINGBACK,
    INCOMING_RINGTONE,
}

internal interface CallSoundOutput {
    fun start(kind: CallSoundKind, callId: UUID)
    fun stop(kind: CallSoundKind)
    fun release()
}

internal class OutgoingRingbackController(private val output: CallSoundOutput) {
    private var activeCallId: UUID? = null

    @Synchronized
    fun update(
        session: AuthenticatedCallSession?,
        localUserId: UUID?,
        currentCallId: UUID?,
        signedIn: Boolean,
        foreground: Boolean,
    ) {
        val callId = session?.callId
        if (activeCallId != null && activeCallId != currentCallId) stop()
        val shouldPlay = session != null && signedIn && foreground && callId == currentCallId &&
            session.status == AuthenticatedCallStatus.RINGING &&
            outgoingCallRoute(localUserId, session.calleeUserId, session) != null
        if (shouldPlay) {
            if (activeCallId != callId) {
                activeCallId = callId
                output.start(CallSoundKind.OUTGOING_RINGBACK, callId!!)
            }
        } else if (callId == null || activeCallId == callId) {
            stop()
        }
    }

    @Synchronized
    fun stopForCall(callId: UUID) {
        if (activeCallId == callId) stop()
    }

    @Synchronized
    fun stop() {
        if (activeCallId != null) {
            activeCallId = null
            output.stop(CallSoundKind.OUTGOING_RINGBACK)
        }
    }
}

internal class IncomingRingtoneController(private val output: CallSoundOutput) {
    private var activeCallId: UUID? = null

    @Synchronized
    fun update(
        session: AuthenticatedCallSession?,
        localUserId: UUID?,
        currentCallId: UUID?,
        signedIn: Boolean,
        foreground: Boolean,
    ) {
        val callId = session?.callId
        if (activeCallId != null && activeCallId != currentCallId) stop()
        val shouldPlay = session != null && signedIn && foreground && callId == currentCallId &&
            incomingCallRoute(localUserId, session) != null
        if (shouldPlay) {
            if (activeCallId != callId) {
                activeCallId = callId
                output.start(CallSoundKind.INCOMING_RINGTONE, callId!!)
            }
        } else if (callId == null || activeCallId == callId) {
            stop()
        }
    }

    @Synchronized
    fun stopForCall(callId: UUID) {
        if (activeCallId == callId) stop()
    }

    @Synchronized
    fun stop() {
        if (activeCallId != null) {
            activeCallId = null
            output.stop(CallSoundKind.INCOMING_RINGTONE)
        }
    }
}

internal class AndroidCallSoundOutput(context: Context) : CallSoundOutput {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeKind: CallSoundKind? = null
    private var activeCallId: UUID? = null
    private var mediaPlayer: MediaPlayer? = null
    private var ringtone: android.media.Ringtone? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var released = false

    override fun start(kind: CallSoundKind, callId: UUID) {
        mainHandler.post {
            if (released || activeKind == kind && activeCallId == callId) return@post
            stopCurrent()
            val attributes = audioAttributes(kind)
            if (!requestFocus(attributes)) return@post
            try {
                when (kind) {
                    CallSoundKind.OUTGOING_RINGBACK -> playRingback(attributes)
                    CallSoundKind.INCOMING_RINGTONE -> playSystemRingtone(attributes)
                }
                activeKind = kind
                activeCallId = callId
            } catch (_: Exception) {
                stopCurrent()
            }
        }
    }

    override fun stop(kind: CallSoundKind) {
        mainHandler.post {
            if (activeKind == kind) stopCurrent()
        }
    }

    override fun release() {
        mainHandler.post {
            released = true
            stopCurrent()
        }
    }

    private fun playRingback(attributes: AudioAttributes) {
        val player = MediaPlayer.create(appContext, R.raw.hailtone_modern_ringback_v3_3s)
            ?: throw IllegalStateException("Ringback resource could not be opened")
        mediaPlayer = player
        player.setAudioAttributes(attributes)
        player.isLooping = true
        player.setOnErrorListener { _, _, _ ->
            if (mediaPlayer === player) stopCurrent()
            true
        }
        player.start()
    }

    private fun playSystemRingtone(attributes: AudioAttributes) {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: Uri.EMPTY
        val player = RingtoneManager.getRingtone(appContext, uri)
            ?: throw IllegalStateException("System ringtone is unavailable")
        ringtone = player
        player.audioAttributes = attributes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) player.isLooping = true
        player.play()
    }

    private fun requestFocus(attributes: AudioAttributes): Boolean {
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change != AudioManager.AUDIOFOCUS_GAIN) mainHandler.post { stopCurrent() }
        }
        focusListener = listener
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(listener, mainHandler)
                .setWillPauseWhenDucked(true)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            audioManager.requestAudioFocus(
                listener,
                if (attributes.usage == AudioAttributes.USAGE_NOTIFICATION_RINGTONE) AudioManager.STREAM_RING
                else AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        if (!granted) abandonFocus()
        return granted
    }

    private fun stopCurrent() {
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        runCatching { ringtone?.stop() }
        ringtone = null
        activeKind = null
        activeCallId = null
        abandonFocus()
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            focusListener?.let { audioManager.abandonAudioFocus(it) }
        }
        focusRequest = null
        focusListener = null
    }

    private fun audioAttributes(kind: CallSoundKind): AudioAttributes = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setUsage(
            if (kind == CallSoundKind.INCOMING_RINGTONE) AudioAttributes.USAGE_NOTIFICATION_RINGTONE
            else AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
        )
        .build()
}