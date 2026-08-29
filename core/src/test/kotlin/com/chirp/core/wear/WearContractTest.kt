package com.chirp.core.wear

import com.chirp.core.session.SessionCommand
import com.chirp.core.session.SessionPhase
import com.chirp.core.session.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WearContractTest {

    @Test
    fun `state round-trips with start-listening pref`() {
        val state = SessionState(
            phase = SessionPhase.SPEAKING,
            active = true,
            partialResponse = "Hello there",
            model = "meta-llama",
            autoListen = true,
        )
        val payload = WearContract.decodeStatePayload(
            WearContract.encodeState(state, startListeningOnNewConversation = true),
        )!!
        assertTrue(payload.startListeningOnNewConversation)

        assertEquals(SessionPhase.SPEAKING, WearContract.toSessionState(payload).phase)
        assertEquals("Hello there", WearContract.toSessionState(payload).partialResponse)
    }

    @Test
    fun `state round-trips with start-listening pref off`() {
        val bytes = WearContract.encodeState(SessionState(), startListeningOnNewConversation = false)
        assertTrue(WearContract.decodeStatePayload(bytes)?.startListeningOnNewConversation == false)
    }

    @Test
    fun `decodeState tolerates unknown phase`() {
        val state = WearContract.decodeState(
            WearContract.encodeState(SessionState(phase = SessionPhase.LISTENING, active = true)),
        )!!
        assertEquals(SessionPhase.LISTENING, state.phase)
        assertTrue(state.active)
    }

    @Test
    fun `command round-trips`() {
        for (command in listOf<SessionCommand>(
            SessionCommand.Start(7),
            SessionCommand.Start(null),
            SessionCommand.StartListening,
            SessionCommand.PressPrimary,
            SessionCommand.Stop,
            SessionCommand.StopSpeaking,
            SessionCommand.SubmitText("hey there"),
        )) {
            assertEquals(command, WearContract.decodeCommand(WearContract.encodeCommand(command)))
        }
    }
}