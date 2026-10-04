package com.chirp.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioDeviceCallback
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns audio focus and Bluetooth SCO routing for a conversation session.
 *
 * When a Bluetooth headset is connected we put the device into communication
 * mode and route to the SCO link so the headset microphone is used for STT and
 * speech plays back through the headset. Uses the modern
 * `setCommunicationDevice` API on Android 12+ and the legacy `startBluetoothSco`
 * path below that.
 *
 * The system can drop the SCO link mid-session (e.g. when the screen turns off)
 * even though the headset stays connected. [reassertCommunicationRoute] re-applies
 * the voice link, and the service calls it at every mic-listening / speaking phase
 * boundary — so the link is healed exactly when the mic or speaker is about to be
 * used. TTS output is keyed off [isBluetoothHeadsetConnected] (durable headset
 * presence) rather than live link state, so a momentary drop never flips the
 * audio mode.
 */
@Singleton
class AudioRouteManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)

    private var focusRequest: AudioFocusRequest? = null
    private var focusCallback: FocusCallback? = null

    @Volatile private var sessionActive = false
    private val routeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var routeRetryJob: Job? = null
    private var observersRegistered = false

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (sessionActive) scheduleRouteRetry("devices added")
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (sessionActive) scheduleRouteRetry("devices removed")
        }
    }

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!sessionActive) return
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> scheduleRouteRetry("screen off")
                Intent.ACTION_SCREEN_ON -> scheduleRouteRetry("screen on")
            }
        }
    }

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
        sessionActive = true
        registerObservers()
        val granted = requestFocus()
        Log.d(TAG, "startSession: focusGranted=$granted headset=${isBluetoothHeadsetConnected()}")
        reassertCommunicationRoute()
        scheduleRouteRetry("session start")
        return granted
    }

    fun endSession() {
        Log.d(TAG, "endSession")
        sessionActive = false
        routeRetryJob?.cancel()
        routeRetryJob = null
        unregisterObservers()
        stopBluetoothRouting()
        abandonFocus()
        focusCallback = null
    }

    /**
     * Re-applies the Bluetooth voice link if a headset is connected, and is a
     * no-op otherwise. The service calls this at every mic-listening / speaking
     * phase boundary: the system can drop the SCO link mid-session (e.g. at
     * screen-off) even though the headset stays connected, and this heals the
     * link exactly when the mic or speaker is about to be used.
     */
    fun reassertCommunicationRoute(): Boolean {
        if (!sessionActive) return false
        if (isBluetoothHeadsetConnected()) {
            val routed = routeToBluetoothIfAvailable()
            if (!routed) scheduleRouteRetry("route unavailable")
            return routed
        }
        Log.d(TAG, "reassert: no headset connected, leaving routing alone")
        return false
    }

    @SuppressLint("MissingPermission")
    fun isBluetoothHeadsetConnected(): Boolean =
        runCatching {
            bluetoothManager?.adapter?.getProfileConnectionState(BluetoothProfile.HEADSET) ==
                android.bluetooth.BluetoothAdapter.STATE_CONNECTED
        }.getOrDefault(false)

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

    private fun routeToBluetoothIfAvailable(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = audioManager.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (device == null) {
                Log.d(
                    TAG,
                    "routeToBluetoothIfAvailable: no SCO device found; " +
                        "available=${audioManager.availableCommunicationDevices.joinToString()}",
                )
                return false
            }
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            val ok = runCatching { audioManager.setCommunicationDevice(device) }.getOrDefault(false)
            Log.d(
                TAG,
                "routeToBluetoothIfAvailable: setCommunicationDevice($device) -> $ok " +
                    "selected=${audioManager.communicationDevice}",
            )
            return ok
        }
        legacyStartSco()
        return true
    }

    @Suppress("DEPRECATION")
    private fun legacyStartSco() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        runCatching {
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
        }
    }

    @Suppress("DEPRECATION")
    private fun stopBluetoothRouting() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        } else {
            runCatching {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
            }
        }
        runCatching { audioManager.mode = AudioManager.MODE_NORMAL }
    }

    private fun scheduleRouteRetry(reason: String) {
        if (!sessionActive || !isBluetoothHeadsetConnected()) return
        routeRetryJob?.cancel()
        routeRetryJob = routeScope.launch {
            repeat(ROUTE_RETRY_COUNT) { attempt ->
                if (!sessionActive || !isBluetoothHeadsetConnected()) return@launch
                if (routeToBluetoothIfAvailable()) {
                    Log.d(TAG, "route retry succeeded: reason=$reason attempt=${attempt + 1}")
                    return@launch
                }
                delay(ROUTE_RETRY_DELAY_MS)
            }
            Log.w(TAG, "route retry exhausted: reason=$reason")
        }
    }

    private fun registerObservers() {
        if (observersRegistered) return
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
        ContextCompat.registerReceiver(
            context,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        observersRegistered = true
    }

    private fun unregisterObservers() {
        if (!observersRegistered) return
        runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
        runCatching { context.unregisterReceiver(screenReceiver) }
        observersRegistered = false
    }

    companion object {
        private const val TAG = "AudioRouteManager"
        private const val ROUTE_RETRY_COUNT = 6
        private const val ROUTE_RETRY_DELAY_MS = 250L
    }
}
