package com.chirp.core.session

import com.chirp.core.chat.ChatClient
import com.chirp.core.chat.ChatRequestSpec
import com.chirp.core.chat.ChatStreamEvent
import com.chirp.core.model.Conversation
import com.chirp.core.model.Message
import com.chirp.core.model.Role
import com.chirp.core.speech.SentenceBuffer
import com.chirp.core.speech.SpeechToTextEngine
import com.chirp.core.speech.SttConfig
import com.chirp.core.speech.SttError
import com.chirp.core.speech.SpeechFormatter
import com.chirp.core.speech.SttEvent
import com.chirp.core.speech.TextToSpeechEngine
import com.chirp.core.speech.TtsException
import com.chirp.core.util.Clock
import com.chirp.core.util.DispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Best-effort instruction appended to every chat request so the model replies in
 * prose that reads naturally when spoken. This is a nudge only — on-device
 * enforcement happens in [com.chirp.core.speech.SpeechFormatter].
 */
const val SPOKEN_OUTPUT_INSTRUCTION: String =
    "Speak as if reading aloud: reply in plain spoken text with no markup of any " +
        "kind — no Markdown, no asterisks, underscores, tildes or backticks, no " +
        "headings, no bullet or numbered lists, no code blocks, no tables, and no " +
        "link syntax like [text](url). Use natural sentences and normal punctuation."

/**
 * The hands-free conversation loop, kept in pure :core so it is unit-testable
 * and reusable. It orchestrates the injected [SpeechToTextEngine],
 * [ChatClient] and [TextToSpeechEngine] over a single long-lived loop:
 *
 *   listen -> (transcript) -> stream from the chat backend -> speak sentence-by-sentence
 *   -> auto-listen again.
 *
 * The first complete sentence is spoken as soon as it streams in (see
 * [SentenceBuffer]); streaming continues concurrently with speech. Control
 * actions (stop/stop-speaking/submit-text/press-primary/park) interrupt the
 * loop by cancelling and relaunching it from a clean point, with intent
 * preserved in fields — this avoids fragile self-cancellation of an in-flight
 * turn. Interrupting a reply mid-stream persists the partial text (marked) so
 * nothing is lost.
 *
 * On the phone this singleton is owned by `ConversationService` (which adds
 * audio focus, Bluetooth SCO and the foreground notification). The UI observes
 * [state]/[events]. PHASE 2: the Wear companion will issue the same
 * [SessionCommand]s through the service via the Data Layer.
 */
@Singleton
class SessionController @Inject constructor(
    private val chatClient: ChatClient,
    private val stt: SpeechToTextEngine,
    private val tts: TextToSpeechEngine,
    private val store: ConversationStore,
    private val settingsProvider: SettingsProvider,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    // Serializes control actions and loop (re)launches so two loops never run.
    private val loopMutex = Mutex()
    private var loopJob: Job? = null

    // Wakes the loop from the paused gate.
    private val resumeSignal = Channel<Unit>(Channel.CONFLATED)

    // Loop-state owned by the controller, read by the loop coroutine.
    @Volatile private var running = false
    @Volatile private var paused = false
    /** When an in-flight reply is cancelled, persist the partial text received so far. */
    @Volatile private var saveOnInterrupt = false
    @Volatile private var injectedText: String? = null
    @Volatile private var conversationId: Long? = null
    @Volatile private var settings: SessionSettings? = null
    @Volatile private var ttsInitialized = false
    private var noMatchCount = 0
    private val utteranceCounter = AtomicLong(0)

    // region Public command API ------------------------------------------------

    /** Maps a [SessionCommand] to the corresponding method (used by the service/Wear). */
    fun dispatch(command: SessionCommand) {
        when (command) {
            is SessionCommand.Start -> start(command.conversationId)
            SessionCommand.StartListening -> startListening()
            SessionCommand.PressPrimary -> pressPrimary()
            SessionCommand.Stop -> stop()
            SessionCommand.StopSpeaking -> stopSpeaking()
            is SessionCommand.SubmitText -> submitText(command.text)
        }
    }

    fun start(conversationId: Long?) = control {
        val s = settingsProvider.current().also { settings = it }
        if (s.model.isBlank()) {
            emitError("No model configured. Open settings and select a model.")
            return@control
        }
        if (!ttsInitialized) {
            ttsInitialized = runCatching { tts.init() }.getOrDefault(false)
            if (!ttsInitialized) emitError("Text-to-speech unavailable")
        }
        applyTtsSettings(s)

        val id = conversationId ?: store.createConversation(s.model, s.systemPrompt)
        this.conversationId = id
        running = true
        paused = !s.autoListen // auto-listen: start now; otherwise wait for a mic tap
        noMatchCount = 0
        _state.update {
            it.copy(
                active = true,
                conversationId = id,
                model = s.model,
                autoListen = s.autoListen,
                errorMessage = null,
                partialResponse = "",
                partialTranscript = "",
                phase = if (s.autoListen) SessionPhase.LISTENING else SessionPhase.PAUSED,
            )
        }
        restartLoopLocked()
    }

    fun startListening() = control {
        if (!running) return@control
        paused = false
        injectedText = null
        noMatchCount = 0
        restartLoopLocked()
    }

    /**
     * The big push-to-talk button. Walks the loop through its states:
     *  - idle: start (or resume into) the session,
     *  - listening: the user has finished their turn — submit the transcript,
     *  - speaking/thinking: interrupt the reply (persist the partial) and listen,
     *  - parked/error: start listening again.
     */
    fun pressPrimary() = control {
        if (!running) {
            start(null)
            return@control
        }
        when (_state.value.phase) {
            SessionPhase.LISTENING -> submitPartialAsUser()
            SessionPhase.SPEAKING, SessionPhase.THINKING -> interruptAndListen()
            SessionPhase.PAUSED, SessionPhase.ERROR -> startListening()
            SessionPhase.IDLE -> Unit
        }
    }

    /**
     * Park the session in a "Ready"/waiting state without ending it: stop any
     * in-flight reply (persisting the partial) and stop listening. Used on
     * permanent audio-focus loss and headset hold actions; the big button
     * (`PressPrimary`) wakes it again.
     */
    fun park() = control {
        if (!running) return@control
        saveOnInterrupt = isReplying()
        paused = true
        injectedText = null
        noMatchCount = 0
        restartLoopLocked()
    }

    /**
     * Stop smart speech early. Interrupts the in-flight reply, persists the
     * partial text received so far (marked), then listens so the user can speak.
     */
    fun stopSpeaking() = control {
        if (!running) return@control
        interruptAndListen()
    }

    fun submitText(text: String) = control {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@control
        if (!running) {
            val s = settingsProvider.current().also { settings = it }
            if (s.model.isBlank()) {
                emitError("No model configured. Open settings and select a model.")
                return@control
            }
            if (!ttsInitialized) ttsInitialized = runCatching { tts.init() }.getOrDefault(false)
            applyTtsSettings(s)
            conversationId = conversationId ?: store.createConversation(s.model, s.systemPrompt)
            running = true
            _state.update { it.copy(active = true, conversationId = conversationId, model = s.model, autoListen = s.autoListen) }
        } else if (isReplying()) {
            // Typing mid-reply interrupts the assistant like the big button does.
            saveOnInterrupt = true
        }
        injectedText = trimmed
        paused = false
        restartLoopLocked()
    }

    /**
     * End the session. If the assistant is mid-reply, stop it and persist the
     * partial text; a mid-listen transcript is discarded (not sent to the model).
     * Never re-listens afterwards.
     */
    fun stop() = control {
        if (!running) return@control
        saveOnInterrupt = isReplying()
        loopJob?.cancelAndJoin()
        loopJob = null
        saveOnInterrupt = false
        running = false
        paused = false
        injectedText = null
        resumeSignal.trySend(Unit)
        runCatching { tts.stop() }
        _state.update {
            it.copy(
                phase = SessionPhase.IDLE,
                active = false,
                partialTranscript = "",
                partialResponse = "",
                rms = 0f,
                thinkingStartedAtMillis = null,
            )
        }
    }

    /** Releases engine resources. Call from the service's onDestroy. */
    fun shutdown() {
        runCatching { tts.shutdown() }
        scope.cancel()
    }

    // endregion

    // region Loop ---------------------------------------------------------------

    private suspend fun runLoop() {
        val s = settings ?: return
        while (running) {
            if (paused) {
                _state.update {
                    it.copy(phase = SessionPhase.PAUSED, rms = 0f, partialTranscript = "")
                }
                resumeSignal.receive()
                if (!running) break
                paused = false
                continue
            }

            val text = listenOnce(s)
            if (text == null) continue // re-listen or parked, depending on paused

            respond(text, s)

            if (!s.autoListen) paused = true
        }
    }

    private suspend fun listenOnce(s: SessionSettings): String? {
        consumeInjectedText()?.let { return it }

        _state.update {
            it.copy(phase = SessionPhase.LISTENING, partialTranscript = "", partialResponse = "", errorMessage = null)
        }
        _events.tryEmit(SessionEvent.ListeningStarted)

        var finalText: String? = null
        var error: SttError? = null
        var remainingRetries = 1 // one transient retry for audio errors
        while (remainingRetries >= 0) {
            error = null
            finalText = null
            try {
                stt.listen(SttConfig(silenceTimeoutMs = s.listeningTimeoutMs)).collect { event ->
                    when (event) {
                        is SttEvent.PartialResult -> _state.update { it.copy(partialTranscript = event.text) }
                        is SttEvent.RmsChanged -> _state.update { it.copy(rms = event.rms) }
                        is SttEvent.FinalResult -> finalText = event.text
                        is SttEvent.Error -> error = event.type
                        SttEvent.ReadyForSpeech,
                        SttEvent.BeginningOfSpeech,
                        SttEvent.EndOfSpeech -> Unit
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                error = SttError.UNKNOWN
            }

            if (error == SttError.AUDIO && remainingRetries > 0) {
                remainingRetries--
                delay(1_000L)
                continue
            }
            break
        }

        _events.tryEmit(SessionEvent.ListeningStopped)
        _state.update { it.copy(rms = 0f) }

        return when {
            error == SttError.NO_MATCH || error == SttError.SPEECH_TIMEOUT -> {
                // These are normal no-speech outcomes, not microphone failures.
                // Let the existing retry/auto-listen policy handle them instead
                // of speaking "I had trouble hearing you".
                handleNoMatch(s)
                null
            }
            error != null -> {
                handleSttError(error!!, s)
                null
            }
            !finalText.isNullOrBlank() -> {
                noMatchCount = 0
                finalText!!.trim()
            }
            else -> {
                handleNoMatch(s)
                null
            }
        }
    }

    private suspend fun respond(userText: String, s: SessionSettings) {
        saveOnInterrupt = false
        _events.tryEmit(SessionEvent.UserUtterance(userText))
        val convId = conversationId ?: store.createConversation(s.model, s.systemPrompt).also { conversationId = it }
        store.appendMessage(convId, Role.USER, userText)

        _state.update {
            it.copy(
                phase = SessionPhase.THINKING,
                partialTranscript = "",
                partialResponse = "",
                thinkingStartedAtMillis = clock.now(),
                errorMessage = null,
            )
        }

        val history = buildHistory(convId, s)
        val spec = ChatRequestSpec(
            model = s.model,
            messages = history,
            temperature = s.temperature,
            webSearch = s.webSearch,
        )

        val sentenceBuffer = SentenceBuffer()
        val speechFormatter = SpeechFormatter()
        val full = StringBuilder()
        val sentences = Channel<String>(Channel.UNLIMITED)
        var failed = false

        var streamCompleted = false
        coroutineScope {
            val speaker = launch {
                for (sentence in sentences) {
                    ensureSpeakingPhase()
                    // UI keeps the raw markdown; only what reaches TTS is sanitized.
                    _events.tryEmit(SessionEvent.AssistantSentence(sentence))
                    if (ttsInitialized) {
                        val spoken = speechFormatter.toSpokenText(sentence)
                        if (spoken.isNotBlank()) {
                            runCatching { tts.speak(spoken, nextUtteranceId()) }
                                .onFailure { if (it is CancellationException) throw it }
                        }
                    }
                }
            }
            try {
                streamWithRetry(spec, s) { token ->
                    full.append(token)
                    _state.update { it.copy(partialResponse = full.toString()) }
                    sentenceBuffer.append(token).forEach { sentences.send(it) }
                }
                sentenceBuffer.flush()?.let { sentences.send(it) }
                streamCompleted = true
            } catch (c: CancellationException) {
                // Interrupted mid-reply (big button, stop-speaking, or End):
                // persist whatever was received rather than losing it.
                persistPartialOnInterrupt(convId, full.toString(), s, streamCompleted = streamCompleted)
                throw c
            } catch (_: Throwable) {
                failed = true
            } finally {
                sentences.close()
            }
            try {
                speaker.join()
            } catch (c: CancellationException) {
                persistPartialOnInterrupt(convId, full.toString(), s, streamCompleted = streamCompleted)
                throw c
            }
        }

        val assistantText = full.toString()
        if (assistantText.isNotBlank()) {
            store.appendMessage(convId, Role.ASSISTANT, assistantText)
        }
        maybeGenerateTitle(convId, s)

        if (failed) {
            val msg = "Connection lost"
            emitError(msg)
            _state.update { it.copy(phase = SessionPhase.ERROR, errorMessage = msg) }
            speakBestEffort(msg)
            paused = true // park after a hard failure; the user can tap the mic to retry
        }
    }

    /**
     * Collects the chat stream, retrying transient failures with backoff — but
     * only while *no* tokens have been received, so a mid-stream drop never
     * causes the already-spoken text to be regenerated and repeated.
     */
    private suspend fun streamWithRetry(
        spec: ChatRequestSpec,
        s: SessionSettings,
        onToken: suspend (String) -> Unit,
    ) {
        var attempt = 0
        while (true) {
            var tokens = 0
            try {
                chatClient.streamChat(spec).collect { event ->
                    when (event) {
                        is ChatStreamEvent.Token -> {
                            tokens++
                            onToken(event.content)
                        }
                        is ChatStreamEvent.Completed -> Unit
                    }
                }
                return
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                if (tokens == 0 && attempt < s.maxStreamRetries) {
                    attempt++
                    emitError("Connection issue, retrying")
                    speakBestEffort("Connection issue, retrying")
                    delay(s.retryBackoffMs * attempt)
                } else {
                    throw t
                }
            }
        }
    }

    private suspend fun handleNoMatch(s: SessionSettings) {
        noMatchCount++
        if (s.autoListen && noMatchCount <= s.maxNoMatchRetries) {
            return // silently listen again
        }
        noMatchCount = 0
        speakBestEffort("I didn't catch that. Tap the mic when you're ready.")
        paused = true
    }

    private suspend fun handleSttError(type: SttError, @Suppress("UNUSED_PARAMETER") s: SessionSettings) {
        val message = when (type) {
            SttError.PERMISSION -> "Microphone permission is required"
            SttError.NOT_AVAILABLE -> "Speech recognition is unavailable"
            SttError.NETWORK, SttError.NETWORK_TIMEOUT -> "Speech service unavailable"
            SttError.AUDIO -> "Microphone error"
            else -> "I had trouble hearing you"
        }
        emitError(message)
        speakBestEffort(message)
        paused = true
    }

    // endregion

    // region Helpers ------------------------------------------------------------

    private suspend fun buildHistory(convId: Long, s: SessionSettings): List<Message> {
        val stored = store.loadMessages(convId)
        val system = s.systemPrompt?.takeIf { it.isNotBlank() }
        return buildList {
            if (system != null) add(Message(role = Role.SYSTEM, text = system))
            // Best-effort nudge to produce spoken prose; [SpeechFormatter] is the
            // real enforcement at the TTS boundary. A separate system message so a
            // user-supplied prompt is never rewritten.
            add(Message(role = Role.SYSTEM, text = SPOKEN_OUTPUT_INSTRUCTION))
            addAll(stored)
        }
    }

    private fun maybeGenerateTitle(convId: Long, s: SessionSettings) {
        scope.launch {
            runCatching {
                val messages = store.loadMessages(convId)
                val userCount = messages.count { it.role == Role.USER }
                if (userCount == 1) {
                    val rawTitle = chatClient.generateTitle(messages, s.model)
                    if (!rawTitle.isNullOrBlank()) {
                        val sanitized = Conversation.sanitizeTitle(rawTitle)
                        store.updateTitle(convId, sanitized)
                    }
                }
            }
        }
    }

    private fun ensureSpeakingPhase() {
        _state.update {
            if (it.phase == SessionPhase.THINKING) it.copy(phase = SessionPhase.SPEAKING) else it
        }
    }

    private fun isReplying() =
        _state.value.phase == SessionPhase.SPEAKING || _state.value.phase == SessionPhase.THINKING

    /** Cuts the assistant off: keep speaking, persist the partial, then listen. */
    private suspend fun interruptAndListen() {
        saveOnInterrupt = true
        paused = false
        injectedText = null
        noMatchCount = 0
        restartLoopLocked()
    }

    /** Big button while listening: the user is done — send the current transcript. */
    private suspend fun submitPartialAsUser() {
        val transcript = _state.value.partialTranscript.trim()
        if (transcript.isEmpty()) return
        injectedText = transcript
        paused = false
        noMatchCount = 0
        restartLoopLocked()
    }

    private fun markInterrupted(text: String, isFullText: Boolean): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        return if (isFullText) trimmed else "$trimmed …"
    }

    /** Persists the partial reply on interrupt (best-effort, detached from the cancelled loop). */
    private fun persistPartialOnInterrupt(
        convId: Long,
        partial: String,
        s: SessionSettings,
        streamCompleted: Boolean = false,
    ) {
        if (partial.isBlank() || !saveOnInterrupt) return
        val text = markInterrupted(partial, isFullText = streamCompleted)
        if (text.isBlank()) return
        scope.launch {
            runCatching {
                store.appendMessage(convId, Role.ASSISTANT, text)
                maybeGenerateTitle(convId, s)
            }
        }
    }

    private fun consumeInjectedText(): String? {
        val injected = injectedText ?: return null
        injectedText = null
        val trimmed = injected.trim()
        if (trimmed.isEmpty()) return null
        noMatchCount = 0
        return trimmed
    }

    private fun applyTtsSettings(s: SessionSettings) {
        runCatching {
            tts.setSpeed(s.ttsSpeed)
            tts.setVoice(s.ttsVoiceId)
        }
    }

    private fun emitError(message: String) {
        _events.tryEmit(SessionEvent.RecoverableError(message))
        _state.update { it.copy(errorMessage = message) }
    }

    private suspend fun speakBestEffort(text: String) {
        if (!ttsInitialized) return
        runCatching { tts.speak(text, nextUtteranceId()) }
            .onFailure { if (it is CancellationException) throw it }
    }

    private fun nextUtteranceId(): String = "chirp-${utteranceCounter.incrementAndGet()}"

    // --- loop lifecycle (must hold loopMutex) ---

    private fun control(block: suspend () -> Unit) {
        scope.launch { loopMutex.withLock { block() } }
    }

    private suspend fun restartLoopLocked() {
        loopJob?.cancelAndJoin()
        loopJob = scope.launch {
            try {
                runLoop()
            } catch (_: CancellationException) {
                // expected on control actions
            } catch (t: Throwable) {
                emitError(t.message ?: "Unexpected error")
                _state.update { it.copy(phase = SessionPhase.ERROR) }
            }
        }
    }

    // endregion

    companion object {
        // PHASE 2 — WEAR INTEGRATION POINT:
        // The service collects [state] and publishes it via WearContract.encodeState
        // to the Data Layer; incoming Data Layer messages are decoded with
        // WearContract.decodeCommand and fed to [dispatch].
    }
}
