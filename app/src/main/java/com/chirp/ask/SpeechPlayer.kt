package com.chirp.ask

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import com.chirp.data.SettingsStore
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

class SpeechPlayer(
    context: Context,
    private val settingsStore: SettingsStore,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var player: MediaPlayer? = null
    private var previousAudioMode: Int? = null
    private var previousSpeakerphoneState: Boolean? = null

    suspend fun play(file: File) {
        stop()
        val useSpeakerphone = settingsStore.settingsFlow().value.speakerphone
        configureAudioRoute(useSpeakerphone)

        suspendCancellableCoroutine { continuation ->
            val mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(
                            if (useSpeakerphone) AudioAttributes.USAGE_MEDIA
                            else AudioAttributes.USAGE_VOICE_COMMUNICATION,
                        )
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    cleanup(file)
                    if (continuation.isActive) continuation.resume(Unit)
                }
                setOnErrorListener { _, _, _ ->
                    cleanup(file)
                    if (continuation.isActive) continuation.resume(Unit)
                    true
                }
                prepare()
                start()
            }

            player = mediaPlayer
            continuation.invokeOnCancellation {
                cleanup(file)
            }
        }
    }

    fun stop() {
        cleanup(null)
    }

    private fun configureAudioRoute(useSpeakerphone: Boolean) {
        if (useSpeakerphone) {
            // USAGE_MEDIA routes through the loudspeaker by default; no AudioManager changes needed.
            return
        }
        if (previousAudioMode == null) {
            previousAudioMode = audioManager.mode
        }
        if (previousSpeakerphoneState == null) {
            previousSpeakerphoneState = audioManager.isSpeakerphoneOn
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false
    }

    private fun cleanup(file: File?) {
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        try {
            player?.reset()
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        restoreAudioRoute()
        file?.delete()
    }

    private fun restoreAudioRoute() {
        previousSpeakerphoneState?.let { audioManager.isSpeakerphoneOn = it }
        previousAudioMode?.let { audioManager.mode = it }
        previousSpeakerphoneState = null
        previousAudioMode = null
    }
}
