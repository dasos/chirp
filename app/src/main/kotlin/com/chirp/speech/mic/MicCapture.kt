package com.chirp.speech.mic

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.chirp.core.speech.Vad
import java.io.Closeable
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Opens the microphone and hands out fixed-size PCM frames.
 *
 * Sized to the VAD: 16kHz mono PCM16 in [Vad.FRAME_SAMPLES]-sample frames.
 * Unlike the platform recognizer, opening `AudioRecord` plays no sound — which
 * is the entire reason this pipeline exists.
 *
 * Bluetooth: [com.chirp.audio.AudioRouteManager] has already put the device in
 * `MODE_IN_COMMUNICATION` and selected the SCO device before listening starts,
 * so capture follows that route. `VOICE_RECOGNITION` is preferred because it
 * applies the least processing (best for ASR), but it does not reliably follow
 * the communication device on every OEM, so a headset session uses
 * `VOICE_COMMUNICATION` instead.
 */
class MicCapture(
    private val preferCommunicationSource: Boolean,
) : Closeable {

    class MicUnavailableException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    /** True when effects were attached this turn (only on the comm path). */
    private var effectsAttached = false

    /** Opens the mic. Throws [MicUnavailableException] if it cannot be started. */
    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked before a session starts
    fun start() {
        val minBuffer = AudioRecord.getMinBufferSize(
            Vad.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            throw MicUnavailableException("AudioRecord.getMinBufferSize returned $minBuffer")
        }

        val source = if (preferCommunicationSource) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }

        // Buffer generously: a stall while transcribing must not drop audio on
        // the next turn, and the cost is a few hundred KB at most.
        val bufferBytes = maxOf(minBuffer, Vad.FRAME_SAMPLES * BYTES_PER_SAMPLE * BUFFER_FRAMES)

        val created = try {
            AudioRecord(
                source,
                Vad.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (e: IllegalArgumentException) {
            throw MicUnavailableException("Could not construct AudioRecord", e)
        }

        if (created.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { created.release() }
            throw MicUnavailableException("AudioRecord failed to initialize (source=$source)")
        }

        try {
            created.startRecording()
        } catch (e: IllegalStateException) {
            runCatching { created.release() }
            throw MicUnavailableException("AudioRecord.startRecording failed", e)
        }

        if (created.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            runCatching { created.release() }
            throw MicUnavailableException("AudioRecord did not enter the recording state")
        }

        record = created
        // Effects only on the communication path: VOICE_RECOGNITION gets its
        // pre-processing from the HAL and adding an AEC/NS effect on top has
        // been observed to garble the signal into noise on some devices.
        if (preferCommunicationSource) {
            attachEffects(created.audioSessionId)
            effectsAttached = true
        }
        Log.d(TAG, "microphone open: source=$source buffer=$bufferBytes effects=$effectsAttached")
    }

    /**
     * Blocking read of exactly one frame. Returns null once the mic has been
     * closed; throws [MicUnavailableException] on a read error.
     */
    fun readFrame(): ShortArray? {
        val active = record ?: return null
        val frame = ShortArray(Vad.FRAME_SAMPLES)
        var offset = 0
        while (offset < frame.size) {
            val read = active.read(frame, offset, frame.size - offset)
            when {
                read > 0 -> offset += read
                // A closed recorder reports an invalid operation; treat as EOF.
                read == 0 -> return null
                read == AudioRecord.ERROR_INVALID_OPERATION -> return null
                else -> throw MicUnavailableException("AudioRecord.read returned $read")
            }
        }
        return frame
    }

    private fun attachEffects(sessionId: Int) {
        if (AcousticEchoCanceler.isAvailable()) {
            aec = runCatching { AcousticEchoCanceler.create(sessionId) }
                .getOrNull()
                ?.apply { runCatching { setEnabled(true) } }
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = runCatching { NoiseSuppressor.create(sessionId) }
                .getOrNull()
                ?.apply { runCatching { setEnabled(true) } }
        }
    }

    override fun close() {
        record?.let {
            runCatching { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() }
            runCatching { it.release() }
        }
        record = null
        aec?.let { runCatching { it.release() } }
        ns?.let { runCatching { it.release() } }
        aec = null
        ns = null
        effectsAttached = false
    }

    companion object {
        private const val TAG = "MicCapture"
        private const val BYTES_PER_SAMPLE = 2
        private const val BUFFER_FRAMES = 32

        /**
         * Frame amplitude for the UI's mic pulse, scaled to the same rough
         * -2..10 range the platform recognizer used to report, so
         * `MicStatusIndicator` keeps behaving as before. Speech sits around
         * -30..-10 dBFS, which the offset maps onto roughly 0..10.
         */
        fun rmsDb(frame: ShortArray): Float {
            if (frame.isEmpty()) return 0f
            var sum = 0.0
            for (s in frame) {
                val v = s.toDouble()
                sum += v * v
            }
            val rms = sqrt(sum / frame.size)
            if (rms < 1.0) return 0f
            return (20.0 * log10(rms / 32768.0)).toFloat() + DB_OFFSET
        }

        /** Shifts dBFS (negative) into the indicator's expected range. */
        private const val DB_OFFSET = 20f
    }
}
