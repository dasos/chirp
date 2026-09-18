package com.chirp.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.SoundPool
import android.util.Log
import androidx.annotation.RawRes
import com.chirp.R
import com.chirp.core.util.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two short earcons that bracket a listening turn: one as the microphone
 * opens, one as it closes.
 *
 * Hands-free with the screen off, the mic pulse and the haptic are not enough —
 * these are the only cue that Chirp is actually recording, and the only signal
 * that it has stopped. [Cue.START] is played *before* `AudioRecord` opens and
 * [playAndAwait] holds until it has finished, so the cue is never captured into
 * the utterance; [Cue.STOP] is fired after the recorder is released, for the
 * same reason.
 *
 * Routing mirrors [AndroidTextToSpeech][com.chirp.speech.AndroidTextToSpeech]:
 * `USAGE_VOICE_COMMUNICATION` so the cue rides the SCO link into the headset,
 * or `USAGE_MEDIA` on the loudspeaker when no headset is connected. Usage is
 * fixed when a [SoundPool] is built, so the pool is rebuilt if headset presence
 * changes between turns — that is rare, and the samples are a few KB each.
 */
@Singleton
class ListeningCues @Inject constructor(
    @ApplicationContext context: Context,
    private val audioRouteManager: AudioRouteManager,
    dispatchers: DispatcherProvider,
) {

    enum class Cue(@RawRes val resId: Int) {
        START(R.raw.chirp_start_listening),
        STOP(R.raw.chirp_stop_listening),
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    /** Guards [pool] / [poolUsage] / [samples] — all replaced together. */
    private val poolLock = Mutex()
    private var pool: SoundPool? = null
    private var poolUsage: Int? = null
    private var samples: Map<Cue, Sample> = emptyMap()

    /** Clip lengths, measured once per cue. Independent of routing. */
    private val durationsMs = ConcurrentHashMap<Cue, Long>()

    private class Sample(val id: Int, val loaded: CompletableDeferred<Boolean>)

    private class Prepared(val pool: SoundPool, val samples: Map<Cue, Sample>)

    /** Plays [cue] without waiting for it — use when nothing follows it. */
    fun play(cue: Cue) {
        scope.launch { playInternal(cue, awaitPlayback = false) }
    }

    /** Plays [cue] and suspends until it has finished sounding. */
    suspend fun playAndAwait(cue: Cue) = playInternal(cue, awaitPlayback = true)

    private suspend fun playInternal(cue: Cue, awaitPlayback: Boolean) {
        val usage = if (audioRouteManager.isBluetoothHeadsetConnected()) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION
        } else {
            AudioAttributes.USAGE_MEDIA
        }

        val prepared = poolLock.withLock { prepare(usage) } ?: return
        val sample = prepared.samples[cue] ?: return

        // Decoding is quick but not instant, and the first turn of a session
        // races it. Never hold up the microphone for longer than the timeout.
        val ready = withTimeoutOrNull(LOAD_TIMEOUT_MS) { sample.loaded.await() } ?: false
        if (!ready) {
            Log.w(TAG, "cue $cue not loaded in time; skipping")
            return
        }

        // The pool may have been replaced by a routing change on another turn;
        // playing into a released pool is logged by the framework, not fatal.
        runCatching { prepared.pool.play(sample.id, VOLUME, VOLUME, PRIORITY, 0, 1f) }
            .onFailure { Log.w(TAG, "could not play cue $cue", it) }

        if (awaitPlayback) delay(durationMs(cue) + TAIL_MS)
    }

    /** Returns a pool configured for [usage], rebuilding it if the route changed. */
    private fun prepare(usage: Int): Prepared? {
        pool?.let { existing -> if (poolUsage == usage) return Prepared(existing, samples) }

        pool?.let { runCatching { it.release() } }
        pool = null
        poolUsage = null
        samples = emptyMap()

        val attributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val created = runCatching {
            SoundPool.Builder()
                .setMaxStreams(1)
                .setAudioAttributes(attributes)
                .build()
        }.getOrElse {
            Log.w(TAG, "could not create SoundPool", it)
            return null
        }

        val loading = Cue.entries.associateWith { cue ->
            Sample(created.load(appContext, cue.resId, PRIORITY), CompletableDeferred())
        }
        val byId = loading.values.associateBy { it.id }
        created.setOnLoadCompleteListener { _, sampleId, status ->
            byId[sampleId]?.loaded?.complete(status == 0)
        }

        pool = created
        poolUsage = usage
        samples = loading
        return Prepared(created, loading)
    }

    /**
     * Length of the clip, read from the resource rather than hard-coded so
     * replacing a wav does not silently leave the start cue bleeding into the
     * recording.
     */
    private fun durationMs(cue: Cue): Long = durationsMs.getOrPut(cue) {
        val retriever = MediaMetadataRetriever()
        try {
            appContext.resources.openRawResourceFd(cue.resId).use { fd ->
                retriever.setDataSource(fd.fileDescriptor, fd.startOffset, fd.declaredLength)
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: FALLBACK_DURATION_MS
        } catch (e: Exception) {
            Log.w(TAG, "could not measure cue $cue", e)
            FALLBACK_DURATION_MS
        } finally {
            runCatching { retriever.release() }
        }
    }

    private companion object {
        const val TAG = "ListeningCues"

        /** The clips peak just under full scale; speech does not. */
        const val VOLUME = 0.6f

        const val PRIORITY = 1

        /** Playback latency slack, so the mic never opens over the tail. */
        const val TAIL_MS = 40L

        const val LOAD_TIMEOUT_MS = 1_000L

        /** Only used if the clip cannot be measured. */
        const val FALLBACK_DURATION_MS = 250L
    }
}
