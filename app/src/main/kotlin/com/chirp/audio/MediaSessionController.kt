package com.chirp.audio

import android.content.Context
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps headset media buttons (play/pause, stop) to session control via a
 * [MediaSessionCompat]. The play/pause button drives the push-to-talk primary
 * action or parks the session in "Ready"; a stop event ends it. The foreground
 * service supplies the [Callback] and keeps the playback state in sync so the
 * OS routes the correct transport action.
 *
 * PHASE 2: the Wear companion sends commands through the Data Layer rather than
 * media buttons, but both ultimately call the same service actions.
 */
@Singleton
class MediaSessionController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    interface Callback {
        fun onPlay()
        fun onPause()
        fun onStop()
    }

    private var session: MediaSessionCompat? = null

    val sessionToken: MediaSessionCompat.Token?
        get() = session?.sessionToken

    fun activate(callback: Callback) {
        if (session != null) return
        session = MediaSessionCompat(context, "Chirp").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = callback.onPlay()
                override fun onPause() = callback.onPause()
                override fun onStop() = callback.onStop()
                override fun onSkipToNext() = callback.onPlay()
                override fun onSkipToPrevious() = callback.onPause()
            })
            setPlaybackState(buildState(playing = true))
            isActive = true
        }
    }

    /** Keeps the play/pause routing correct as the loop state changes. */
    fun setPlaying(playing: Boolean) {
        session?.setPlaybackState(buildState(playing))
    }

    fun release() {
        session?.apply {
            isActive = false
            release()
        }
        session = null
    }

    private fun buildState(playing: Boolean): PlaybackStateCompat =
        PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_STOP,
            )
            .setState(
                if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                1.0f,
            )
            .build()
}
