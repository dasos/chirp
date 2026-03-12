package com.chirp.ask

import com.chirp.data.SettingsStore
import com.chirp.data.TranscriptRepository
import com.chirp.realtime.TranscriptStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AskSessionController(
    private val scope: CoroutineScope,
    private val recorder: PushToTalkRecorder,
    private val askClient: OpenAiAskClient,
    private val player: SpeechPlayer,
    private val transcriptStore: TranscriptStore,
    private val transcriptRepository: TranscriptRepository,
    private val settingsStore: SettingsStore,
) {
    private val _state = MutableStateFlow(AskState())
    val state: StateFlow<AskState> = _state

    fun startRecording() {
        if (_state.value.status == AskStatus.RECORDING || _state.value.status == AskStatus.PROCESSING) return
        val started = try {
            recorder.start()
        } catch (e: Exception) {
            _state.value = AskState(AskStatus.ERROR, "Ask failed", e.message)
            false
        }
        if (started) {
            _state.value = AskState(AskStatus.RECORDING, "Recording… release to ask")
        }
    }

    fun finishRecording() {
        if (_state.value.status != AskStatus.RECORDING) return
        val audioFile = recorder.stop()
        if (audioFile == null) {
            _state.value = AskState(AskStatus.ERROR, "Ask failed", "Could not capture audio")
            return
        }

        scope.launch {
            _state.value = AskState(AskStatus.PROCESSING, "Transcribing…")
            try {
                val sessionId = transcriptStore.startSessionIfNeeded()
                val transcript = askClient.transcribe(audioFile)
                audioFile.delete()
                if (transcript.isNullOrBlank()) {
                    _state.value = AskState(AskStatus.ERROR, "Ask failed", "Could not transcribe audio")
                    return@launch
                }

                val history = transcriptRepository.getRecentBySession(sessionId, HISTORY_LIMIT)
                transcriptStore.addCompletedMessage("user", transcript)

                _state.value = AskState(AskStatus.PROCESSING, "Thinking…")
                val reply = askClient.generateReply(history, transcript, settingsStore.settingsFlow().value.maxOutputTokens)
                if (reply.isNullOrBlank()) {
                    _state.value = AskState(AskStatus.ERROR, "Ask failed", "Could not generate a response")
                    return@launch
                }

                transcriptStore.addCompletedMessage("assistant", reply)

                _state.value = AskState(AskStatus.PROCESSING, "Speaking…")
                val speechFile = askClient.synthesize(reply)
                if (speechFile != null) {
                    player.play(speechFile)
                }
                _state.value = AskState()
            } catch (e: Exception) {
                _state.value = AskState(AskStatus.ERROR, "Ask failed", e.message)
            }
        }
    }

    fun cancelRecording() {
        if (_state.value.status != AskStatus.RECORDING) return
        recorder.cancel()
        _state.value = AskState()
    }

    private companion object {
        private const val HISTORY_LIMIT = 20
    }
}
