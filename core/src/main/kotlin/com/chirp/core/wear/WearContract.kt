package com.chirp.core.wear

import com.chirp.core.session.SessionCommand
import com.chirp.core.session.SessionPhase
import com.chirp.core.session.SessionState
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * PHASE 2 — WEAR OS INTEGRATION POINT.
 *
 * Shared contract between the phone and a future Wear OS companion, both of
 * which depend on :core. The watch is a thin remote control: it renders
 * [SessionState] and sends [SessionCommand]s.
 *
 * Transport is the Google Play Services **Data Layer API**:
 *  - Phone publishes state to [PATH_STATE] (a DataItem) on every state change.
 *  - Watch sends commands to [PATH_COMMAND] (a MessageClient message).
 *
 * The actual Wearable* clients live in the Android modules (phone + watch); this
 * object only owns the wire format and path constants so both sides agree. The
 * phone-side publisher hook is marked in `ConversationService`.
 */
object WearContract {

    /** CapabilityClient capability the phone advertises so the watch can find it. */
    const val CAPABILITY_PHONE_APP = "chirp_phone_session"

    const val PATH_STATE = "/chirp/state"
    const val PATH_COMMAND = "/chirp/command"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // --- State (phone -> watch) ---------------------------------------------

    @Serializable
    data class StatePayload(
        val phase: String,
        val active: Boolean,
        val partialResponse: String,
        val model: String,
        val autoListen: Boolean,
        val errorMessage: String? = null,
    )

    fun encodeState(state: SessionState): ByteArray =
        json.encodeToString(
            StatePayload(
                phase = state.phase.name,
                active = state.active,
                partialResponse = state.partialResponse.take(200),
                model = state.model,
                autoListen = state.autoListen,
                errorMessage = state.errorMessage,
            )
        ).encodeToByteArray()

    fun decodeState(bytes: ByteArray): SessionState? = runCatching {
        val p = json.decodeFromString<StatePayload>(bytes.decodeToString())
        SessionState(
            phase = runCatching { SessionPhase.valueOf(p.phase) }.getOrDefault(SessionPhase.IDLE),
            active = p.active,
            partialResponse = p.partialResponse,
            model = p.model,
            autoListen = p.autoListen,
            errorMessage = p.errorMessage,
        )
    }.getOrNull()

    // --- Commands (watch -> phone) ------------------------------------------

    fun encodeCommand(command: SessionCommand): ByteArray = when (command) {
        is SessionCommand.Start -> "start:${command.conversationId ?: ""}"
        SessionCommand.StartListening -> "listen"
        SessionCommand.ToggleListen -> "toggle"
        SessionCommand.Pause -> "pause"
        SessionCommand.Resume -> "resume"
        SessionCommand.Stop -> "stop"
        SessionCommand.StopSpeaking -> "stop_speaking"
        is SessionCommand.SubmitText -> "text:${command.text}"
    }.encodeToByteArray()

    fun decodeCommand(bytes: ByteArray): SessionCommand? {
        val raw = bytes.decodeToString()
        return when {
            raw == "listen" -> SessionCommand.StartListening
            raw == "toggle" -> SessionCommand.ToggleListen
            raw == "pause" -> SessionCommand.Pause
            raw == "resume" -> SessionCommand.Resume
            raw == "stop" -> SessionCommand.Stop
            raw == "stop_speaking" -> SessionCommand.StopSpeaking
            raw.startsWith("start:") ->
                SessionCommand.Start(raw.removePrefix("start:").toLongOrNull())
            raw.startsWith("text:") ->
                SessionCommand.SubmitText(raw.removePrefix("text:"))
            else -> null
        }
    }
}
