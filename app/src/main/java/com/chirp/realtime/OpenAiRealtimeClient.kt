package com.chirp.realtime

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.JavaAudioDeviceModule
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class OpenAiRealtimeClient(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val apiKeyStore: com.chirp.data.ApiKeyStore,
    private val transcriptStore: TranscriptStore,
) {
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var localStream: MediaStream? = null
    private val transcriptScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var iceGatheringComplete: CompletableDeferred<Unit>? = null

    suspend fun start(config: SessionConfig, onStatus: (SessionState) -> Unit) {
        val apiKey = apiKeyStore.getKey()?.trim().orEmpty()
        if (apiKey.isBlank()) {
            onStatus(SessionState(SessionStatus.ERROR, "Missing API key", false, "API key required"))
            return
        }

        onStatus(SessionState(SessionStatus.CONNECTING, "Requesting session…", false))

        try {
            val sessionInfo = createSession(apiKey, config)
            onStatus(SessionState(SessionStatus.CONNECTING, "Preparing audio…", false))

            ensurePeerConnectionFactory()
            val pc = buildPeerConnection(onStatus)
            peerConnection = pc

            val audioSource = peerConnectionFactory!!.createAudioSource(MediaConstraints())
            val audioTrack = peerConnectionFactory!!.createAudioTrack("chirp-audio", audioSource)
            localAudioSource = audioSource
            localAudioTrack = audioTrack

            localStream = peerConnectionFactory!!.createLocalMediaStream("chirp-stream")
            localStream?.addTrack(audioTrack)
            pc.addTrack(audioTrack)

            val dc = pc.createDataChannel("oai-events", DataChannel.Init())
            dataChannel = dc
            dc.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit

                override fun onStateChange() {
                    if (dc.state() == DataChannel.State.OPEN) {
                        sendSessionUpdate(config)
                    }
                }

                override fun onMessage(buffer: DataChannel.Buffer) {
                    val data = ByteArray(buffer.data.remaining())
                    buffer.data.get(data)
                    val text = String(data, StandardCharsets.UTF_8)
                    handleEvent(text, onStatus)
                }
            })

            val offer = createOffer(pc)
            setLocalDescription(pc, offer)
            awaitIceGathering(pc)

            val sdpAnswer = exchangeSdp(sessionInfo.clientSecret, sessionInfo.model, offer.description)
            setRemoteDescription(pc, SessionDescription(SessionDescription.Type.ANSWER, sdpAnswer))

            onStatus(SessionState(SessionStatus.CONNECTED, "Connected — speak now", true))
        } catch (e: Exception) {
            onStatus(SessionState(SessionStatus.ERROR, "Connection failed", false, e.message))
            stop()
        }
    }

    suspend fun stop() {
        dataChannel?.unregisterObserver()
        dataChannel?.close()
        dataChannel = null

        try {
            peerConnection?.close()
        } catch (_: Exception) {
        }
        peerConnection = null

        try {
            localAudioTrack?.dispose()
            localAudioSource?.dispose()
        } catch (_: Exception) {
        }
        localAudioTrack = null
        localAudioSource = null
        localStream = null
    }

    private suspend fun createSession(apiKey: String, config: SessionConfig): SessionInfo {
        return withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("model", config.model)
                put("voice", config.voice)
                val modalities = if (config.transcribe) {
                    JSONArray().put("text").put("audio")
                } else {
                    JSONArray().put("audio")
                }
                put("modalities", modalities)
            }

            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://api.openai.com/v1/realtime/sessions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("OpenAI-Beta", "realtime=v1")
                .post(body)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("Session error ${response.code}: $raw")
                }
                val json = JSONObject(raw)
                val clientSecret = json.getJSONObject("client_secret").getString("value")
                val model = json.optString("model", config.model)
                SessionInfo(clientSecret, model)
            }
        }
    }

    private suspend fun exchangeSdp(ephemeralKey: String, model: String, offerSdp: String): String {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://api.openai.com/v1/realtime?model=$model")
                .addHeader("Authorization", "Bearer $ephemeralKey")
                .addHeader("Content-Type", "application/sdp")
                .addHeader("OpenAI-Beta", "realtime=v1")
                .post(offerSdp.toRequestBody("application/sdp".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("SDP error ${response.code}: $raw")
                }
                raw
            }
        }
    }

    private fun ensurePeerConnectionFactory() {
        if (peerConnectionFactory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
        val options = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(JavaAudioDeviceModule.builder(context).createAudioDeviceModule())
            .createPeerConnectionFactory()
    }

    private fun buildPeerConnection(onStatus: (SessionState) -> Unit): PeerConnection {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        iceGatheringComplete = CompletableDeferred()
        return peerConnectionFactory!!.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit

                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                    if (newState == PeerConnection.IceConnectionState.CONNECTED ||
                        newState == PeerConnection.IceConnectionState.COMPLETED
                    ) {
                        onStatus(SessionState(SessionStatus.CONNECTED, "Connected — speak now", true))
                    }
                    if (newState == PeerConnection.IceConnectionState.DISCONNECTED ||
                        newState == PeerConnection.IceConnectionState.FAILED ||
                        newState == PeerConnection.IceConnectionState.CLOSED
                    ) {
                        onStatus(SessionState(SessionStatus.IDLE, "Disconnected", false))
                    }
                }

                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
                    if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                        iceGatheringComplete?.complete(Unit)
                    }
                }

                override fun onIceCandidate(candidate: IceCandidate) = Unit

                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit

                override fun onAddStream(stream: MediaStream) = Unit

                override fun onRemoveStream(stream: MediaStream) = Unit

                override fun onDataChannel(channel: DataChannel) = Unit

                override fun onRenegotiationNeeded() = Unit

                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit

                override fun onTrack(transceiver: RtpTransceiver?) = Unit
            },
        ) ?: throw IllegalStateException("Unable to create PeerConnection")
    }

    private suspend fun createOffer(peerConnection: PeerConnection): SessionDescription {
        val deferred = CompletableDeferred<SessionDescription>()
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        peerConnection.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                deferred.complete(sdp)
            }

            override fun onCreateFailure(error: String) {
                deferred.completeExceptionally(IllegalStateException(error))
            }
        }, constraints)
        return deferred.await()
    }

    private suspend fun setLocalDescription(peerConnection: PeerConnection, sdp: SessionDescription) {
        val deferred = CompletableDeferred<Unit>()
        peerConnection.setLocalDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                deferred.complete(Unit)
            }

            override fun onSetFailure(error: String) {
                deferred.completeExceptionally(IllegalStateException(error))
            }
        }, sdp)
        deferred.await()
    }

    private suspend fun setRemoteDescription(peerConnection: PeerConnection, sdp: SessionDescription) {
        val deferred = CompletableDeferred<Unit>()
        peerConnection.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                deferred.complete(Unit)
            }

            override fun onSetFailure(error: String) {
                deferred.completeExceptionally(IllegalStateException(error))
            }
        }, sdp)
        deferred.await()
    }

    private suspend fun awaitIceGathering(peerConnection: PeerConnection) {
        if (peerConnection.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) return
        withTimeoutOrNull(1500) {
            iceGatheringComplete?.await()
        }
    }

    private fun sendSessionUpdate(config: SessionConfig) {
        val payload = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("input_audio_format", if (config.lowBandwidth) "g711_ulaw" else "pcm16")
                put("output_audio_format", if (config.lowBandwidth) "g711_ulaw" else "pcm16")
                put("max_output_tokens", config.maxOutputTokens)
                put(
                    "output_modalities",
                    if (config.transcribe) JSONArray().put("audio").put("text") else JSONArray().put("audio"),
                )
                put("instructions", "You are a friendly voice assistant. Keep replies concise.")
                put("voice", config.voice)
                put("turn_detection", JSONObject().apply {
                    put("type", "server_vad")
                    put("silence_duration_ms", 400)
                })
                if (config.transcribe) {
                    put("input_audio_transcription", JSONObject().apply {
                        put("model", "gpt-4o-mini-transcribe")
                    })
                } else {
                    put("input_audio_transcription", JSONObject.NULL)
                }
            })
        }
        val bytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
        dataChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    private fun handleEvent(raw: String, onStatus: (SessionState) -> Unit) {
        try {
            val json = JSONObject(raw)
            val type = json.optString("type")
            when (type) {
                "error" -> {
                    val error = json.optJSONObject("error")
                    onStatus(
                        SessionState(
                            SessionStatus.ERROR,
                            error?.optString("message") ?: "Realtime error",
                            false,
                        ),
                    )
                }
                "conversation.item.created" -> {
                    val item = json.optJSONObject("item") ?: return
                    if (item.optString("type") == "message") {
                        val role = item.optString("role")
                        if (role == "user" || role == "assistant") {
                            val text = textFromContent(item.optJSONArray("content"))
                            safeTranscript { transcriptStore.ensure(item.getString("id"), role, text) }
                        }
                    }
                }
                "conversation.item.input_audio_transcription.delta" -> {
                    val itemId = json.optString("item_id")
                    val delta = json.optString("delta")
                    safeTranscript { transcriptStore.append(itemId, delta) }
                }
                "conversation.item.input_audio_transcription.completed" -> {
                    val itemId = json.optString("item_id")
                    val transcript = json.optString("transcript")
                    safeTranscript { transcriptStore.finalize(itemId, transcript) }
                }
                "response.audio_transcript.delta" -> {
                    val itemId = json.optString("item_id")
                    val delta = json.optString("delta")
                    safeTranscript { transcriptStore.append(itemId, delta) }
                }
                "response.audio_transcript.done" -> {
                    val itemId = json.optString("item_id")
                    val transcript = json.optString("transcript")
                    safeTranscript { transcriptStore.finalize(itemId, transcript) }
                }
                "response.done" -> {
                    val response = json.optJSONObject("response") ?: return
                    val output = response.optJSONArray("output") ?: return
                    for (i in 0 until output.length()) {
                        val item = output.optJSONObject(i) ?: continue
                        if (item.optString("type") == "message" && item.optString("role") == "assistant") {
                            val text = textFromContent(item.optJSONArray("content"))
                            safeTranscript { transcriptStore.finalize(item.getString("id"), text) }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // ignore malformed events
        }
    }

    private fun textFromContent(content: JSONArray?): String {
        if (content == null) return ""
        for (i in 0 until content.length()) {
            val part = content.optJSONObject(i) ?: continue
            val type = part.optString("type")
            if ((type == "input_text" || type == "output_text") && part.has("text")) {
                return part.optString("text")
            }
            if (type == "audio" && part.has("transcript")) {
                return part.optString("transcript")
            }
        }
        return ""
    }

    private fun safeTranscript(block: suspend () -> Unit) {
        // fire-and-forget to avoid blocking WebRTC callbacks
        transcriptScope.launch {
            try {
                block()
            } catch (_: Exception) {
            }
        }
    }

    private data class SessionInfo(
        val clientSecret: String,
        val model: String,
    )
}

open class SimpleSdpObserver : org.webrtc.SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String) = Unit
    override fun onSetFailure(error: String) = Unit
}
