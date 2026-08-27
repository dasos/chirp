package com.chirp.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns audio focus and Bluetooth SCO routing for a conversation session.
 *
 * When a Bluetooth headset is connected we put the device into communication
 * mode and route to the SCO link so the headset microphone is used for STT and
 * speech plays back through the headset. Uses the modern
 * `setCommunicationDevice` API on Android 12+ and the legacy `startBluetoothSco`
 * path below that. [scoActive] reflects whether the SCO link is up so the
 * service can switch TTS routing accordingly.
 */
@Singleton
class AudioRouteManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _scoActive = MutableStateFlow(false)
    val scoActive: StateFlow<Boolean> = _scoActive.asStateFlow()

    private var focusRequest: AudioFocusRequest? = null
    private var focusCallback: FocusCallback? = null
    private var scoReceiver: BroadcastReceiver? = null

    interface FocusCallback {
        fun onFocusLost()
        fun onTransientLoss()
        fun onFocusGained()
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        val changeName = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
            AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"
            else -> "UNKNOWN($change)"
        }
        Log.d(TAG, "focusChange: $changeName")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> focusCallback?.onFocusLost()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> focusCallback?.onTransientLoss()
            AudioManager.AUDIOFOCUS_GAIN -> focusCallback?.onFocusGained()
        }
    }

    /** Acquires focus and routes to a Bluetooth headset if one is present. */
    fun startSession(callback: FocusCallback): Boolean {
        focusCallback = callback
        val granted = requestFocus()
        Log.d(TAG, "startSession: focusGranted=$granted")
        routeToBluetoothIfAvailable()
        return granted
    }

    fun endSession() {
        Log.d(TAG, "endSession")
        stopBluetoothRouting()
        abandonFocus()
        focusCallback = null
    }

    fun isBluetoothHeadsetConnected(): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

    private fun requestFocus(): Boolean {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }

    private fun routeToBluetoothIfAvailable() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = audioManager.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (device != null) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                val ok = runCatching { audioManager.setCommunicationDevice(device) }.getOrDefault(false)
                _scoActive.value = ok
                Log.d(TAG, "routeToBluetoothIfAvailable: setCommunicationDevice(${device}) -> $ok")
            } else {
                _scoActive.value = false
                Log.d(TAG, "routeToBluetoothIfAvailable: no SCO device found")
            }
        } else {
            legacyStartSco()
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyStartSco() {
        if (!isBluetoothHeadsetConnected()) {
            _scoActive.value = false
            return
        }
        registerScoReceiver()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        runCatching {
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
        }
        // _scoActive is set when the SCO_AUDIO_STATE broadcast reports CONNECTED.
    }

    @Suppress("DEPRECATION")
    private fun stopBluetoothRouting() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        } else {
            unregisterScoReceiver()
            runCatching {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
            }
        }
        runCatching { audioManager.mode = AudioManager.MODE_NORMAL }
        _scoActive.value = false
    }

    private fun registerScoReceiver() {
        if (scoReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                @Suppress("DEPRECATION")
                val state = intent?.getIntExtra(
                    AudioManager.EXTRA_SCO_AUDIO_STATE,
                    AudioManager.SCO_AUDIO_STATE_ERROR,
                )
                _scoActive.value = state == AudioManager.SCO_AUDIO_STATE_CONNECTED
            }
        }
        scoReceiver = receiver
        @Suppress("DEPRECATION")
        context.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
    }

    private fun unregisterScoReceiver() {
        scoReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        scoReceiver = null
    }

    companion object {
        private const val TAG = "AudioRouteManager"
    }
}
