package com.chirp.core.session

import com.chirp.core.chat.ChatClient
import com.chirp.core.model.Role
import com.chirp.core.speech.SpeechToTextEngine
import com.chirp.core.util.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionControllerTest {

    private fun newController(
        dispatcher: TestDispatcher,
        chat: ChatClient,
        tts: FakeTextToSpeech = FakeTextToSpeech(),
        store: FakeConversationStore = FakeConversationStore(),
        stt: SpeechToTextEngine = FakeSpeechToText(),
        settings: SessionSettings,
    ): SessionController = SessionController(
        chatClient = chat,
        stt = stt,
        tts = tts,
        store = store,
        settingsProvider = FakeSettingsProvider(settings),
        dispatchers = TestDispatcherProvider(dispatcher),
        clock = Clock { 0L },
    )

    @Test
    fun `submitText runs a full turn and speaks sentence by sentence`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tts = FakeTextToSpeech()
        val store = FakeConversationStore()
        val chat = FakeChatClient(tokens = listOf("Hi", "! ", "How ", "are ", "you?"))
        val controller = newController(
            dispatcher, chat, tts = tts, store = store,
            settings = SessionSettings(model = "m", systemPrompt = null, autoListen = false),
        )

        controller.start(null)
        advanceUntilIdle()
        assertEquals(SessionPhase.PAUSED, controller.state.value.phase)
        assertTrue(controller.state.value.active)

        controller.submitText("Hello there")
        advanceUntilIdle()

        // First sentence spoken from the stream, the trailing one from flush().
        assertEquals(listOf("Hi!", "How are you?"), tts.spoken)

        assertEquals(2, store.messages.size)
        assertEquals(Role.USER, store.messages[0].role)
        assertEquals("Hello there", store.messages[0].text)
        assertEquals(Role.ASSISTANT, store.messages[1].role)
        assertEquals("Hi! How are you?", store.messages[1].text)

        assertEquals("Hi! How are you?", controller.state.value.partialResponse)
        assertEquals(SessionPhase.PAUSED, controller.state.value.phase)

        controller.shutdown()
    }

    @Test
    fun `stop resets the session to idle`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = newController(
            dispatcher, FakeChatClient(),
            settings = SessionSettings(model = "m", systemPrompt = null, autoListen = false),
        )

        controller.start(null)
        advanceUntilIdle()
        assertTrue(controller.state.value.active)

        controller.stop()
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(SessionPhase.IDLE, state.phase)
        assertFalse(state.active)

        controller.shutdown()
    }

    @Test
    fun `stream failure retries then reports connection lost without persisting an empty reply`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tts = FakeTextToSpeech()
        val store = FakeConversationStore()
        val chat = FakeChatClient(failAlways = true)
        val controller = newController(
            dispatcher, chat, tts = tts, store = store,
            settings = SessionSettings(
                model = "m",
                systemPrompt = null,
                autoListen = false,
                maxStreamRetries = 2,
                retryBackoffMs = 10,
            ),
        )

        controller.start(null)
        advanceUntilIdle()

        controller.submitText("test")
        advanceUntilIdle()

        assertEquals("Connection lost", controller.state.value.errorMessage)
        // Only the user message persisted; no empty assistant reply.
        assertEquals(1, store.messages.size)
        assertEquals(Role.USER, store.messages[0].role)
        assertEquals("Connection lost", tts.spoken.last())

        controller.shutdown()
    }
}
