package com.chirp.core.session

import com.chirp.core.chat.ChatClient
import com.chirp.core.chat.ChatRequestSpec
import com.chirp.core.chat.ChatStreamEvent
import com.chirp.core.model.Conversation
import com.chirp.core.model.Role
import com.chirp.core.speech.SpeechToTextEngine
import com.chirp.core.speech.SttConfig
import com.chirp.core.speech.SttEvent
import com.chirp.core.util.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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

    @Test
    fun `first turn triggers title generation and updates store`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FakeConversationStore()
        val chat = FakeChatClient(
            tokens = listOf("Paris is the capital of France."),
            title = "\"Capital of France\"",
        )
        val controller = newController(
            dispatcher, chat, store = store,
            settings = SessionSettings(model = "m", systemPrompt = null, autoListen = false),
        )

        controller.start(null)
        advanceUntilIdle()

        controller.submitText("What is the capital of France?")
        advanceUntilIdle()

        assertEquals(1, chat.titleRequests)
        assertEquals("Capital of France", store.conversations.first().title)

        // Second turn does not re-trigger title generation
        controller.submitText("Tell me more")
        advanceUntilIdle()
        assertEquals(1, chat.titleRequests)

        controller.shutdown()
    }

    @Test
    fun `sanitizeTitle strips quotes and clamps length`() {
        assertEquals("Capital of France", Conversation.sanitizeTitle("\"Capital of France\""))
        assertEquals("Capital of France", Conversation.sanitizeTitle("  'Capital of France.'  "))
        assertEquals("Capital of France", Conversation.sanitizeTitle("**Capital of France:**"))
        assertEquals("New conversation", Conversation.sanitizeTitle(""))
        assertEquals("New conversation", Conversation.sanitizeTitle("   "))
        assertEquals("A".repeat(50), Conversation.sanitizeTitle("A".repeat(50)))
        assertEquals("A".repeat(50) + "…", Conversation.sanitizeTitle("A".repeat(60)))
    }

    @Test
    fun `pressPrimary while listening submits the partial transcript as the user turn`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FakeConversationStore()
        val chat = FakeChatClient(tokens = listOf("Hi,", " how are you?"))
        val stt = object : SpeechToTextEngine {
            override suspend fun isAvailable(): Boolean = true
            override fun listen(config: SttConfig): Flow<SttEvent> = flow {
                emit(SttEvent.PartialResult("Hello there"))
                awaitCancellation()
            }
        }
        val controller = newController(
            dispatcher, chat, store = store, stt = stt,
            settings = SessionSettings(model = "m", systemPrompt = null, autoListen = true),
        )

        controller.start(null)
        advanceUntilIdle()
        assertEquals(SessionPhase.LISTENING, controller.state.value.phase)

        controller.pressPrimary()
        advanceUntilIdle()

        val user = store.messages.first { it.role == Role.USER }
        assertEquals("Hello there", user.text)
        assertEquals("Hi, how are you?", store.messages.first { it.role == Role.ASSISTANT }.text)

        controller.shutdown()
    }

    @Test
    fun `interrupting a reply on pressPrimary persists the partial reply with a cut marker`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FakeConversationStore()
        val chat = object : FakeChatClient(tokens = listOf("Paris", " is", " the", " capital.")) {
            override fun streamChat(spec: ChatRequestSpec): Flow<ChatStreamEvent> = flow {
                tokens.forEach { emit(ChatStreamEvent.Token(it)) }
                awaitCancellation()
            }
        }
        val stt = object : SpeechToTextEngine {
            override suspend fun isAvailable(): Boolean = true
            override fun listen(config: SttConfig): Flow<SttEvent> = flow {
                awaitCancellation()
            }
        }
        val controller = newController(
            dispatcher, chat, store = store, stt = stt,
            settings = SessionSettings(model = "m", systemPrompt = null, autoListen = true),
        )

        controller.start(null)
        advanceUntilIdle()
        controller.submitText("Cancel this turn")
        advanceUntilIdle()
        assertEquals(SessionPhase.THINKING, controller.state.value.phase)

        controller.pressPrimary()
        advanceUntilIdle()

        val assistant = store.messages.first { it.role == Role.ASSISTANT }
        assertTrue(assistant.text.startsWith("Paris is the capital."))
        assertTrue(assistant.text.endsWith("…"))

        controller.shutdown()
    }

    @Test
    fun `end while listening discards the transcript without sending to the model`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FakeConversationStore()
        val chat = FakeChatClient()
        val stt = object : SpeechToTextEngine {
            override suspend fun isAvailable(): Boolean = true
            override fun listen(config: SttConfig): Flow<SttEvent> = flow {
                emit(SttEvent.PartialResult("partial thoughts"))
                awaitCancellation()
            }
        }
        val controller = newController(
            dispatcher, chat, store = store, stt = stt,
            settings = SessionSettings(model = "m", systemPrompt = null, autoListen = true),
        )

        controller.start(null)
        advanceUntilIdle()
        assertEquals(SessionPhase.LISTENING, controller.state.value.phase)

        controller.stop()
        advanceUntilIdle()

        assertEquals(SessionPhase.IDLE, controller.state.value.phase)
        assertFalse(controller.state.value.active)
        assertTrue(store.messages.isEmpty())

        controller.shutdown()
    }

    @Test
    fun `end while replying persists the partial reply`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FakeConversationStore()
        val chat = object : FakeChatClient(tokens = listOf("Partial", " answer")) {
            override fun streamChat(spec: ChatRequestSpec): Flow<ChatStreamEvent> = flow {
                tokens.forEach { emit(ChatStreamEvent.Token(it)) }
                awaitCancellation()
            }
        }
        val controller = newController(
            dispatcher, chat, store = store,
            settings = SessionSettings(model = "m", systemPrompt = null, autoListen = true),
        )

        controller.start(null)
        advanceUntilIdle()
        controller.submitText("Type a question")
        advanceUntilIdle()
        assertEquals(SessionPhase.THINKING, controller.state.value.phase)

        controller.stop()
        advanceUntilIdle()

        val assistant = store.messages.first { it.role == Role.ASSISTANT }
        assertTrue(assistant.text.startsWith("Partial answer"))
        assertTrue(assistant.text.endsWith("…"))

        controller.shutdown()
    }

    @Test
    fun `interrupt while stream completed but tts is speaking persists full text without ellipsis`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FakeConversationStore()
        val chat = FakeChatClient(tokens = listOf("Full", " answer", " completed."))
        val tts = object : FakeTextToSpeech() {
            override suspend fun speak(text: String, utteranceId: String) {
                super.speak(text, utteranceId)
                awaitCancellation()
            }
        }
        val controller = newController(
            dispatcher, chat, tts = tts, store = store,
            settings = SessionSettings(model = "m", systemPrompt = null, autoListen = true),
        )

        controller.start(null)
        advanceUntilIdle()
        controller.submitText("Type a question")
        advanceUntilIdle()
        assertEquals(SessionPhase.SPEAKING, controller.state.value.phase)

        controller.stop()
        advanceUntilIdle()

        val assistant = store.messages.first { it.role == Role.ASSISTANT }
        assertEquals("Full answer completed.", assistant.text)
        assertFalse(assistant.text.endsWith("…"))

        controller.shutdown()
    }

    @Test
    fun `listening times out and parks when the recognizer never calls back`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tts = FakeTextToSpeech()
        // Simulates the real-world failure mode: continuous background noise
        // keeps the recognizer from ever finalizing (no FinalResult, no Error) —
        // the app must give up on its own rather than listen forever.
        val stt = object : SpeechToTextEngine {
            override suspend fun isAvailable(): Boolean = true
            override fun listen(config: SttConfig): Flow<SttEvent> = flow { awaitCancellation() }
        }
        val controller = newController(
            dispatcher, FakeChatClient(), tts = tts, stt = stt,
            settings = SessionSettings(
                model = "m", systemPrompt = null, autoListen = true,
                listeningTimeoutMs = 100L, maxNoMatchRetries = 0,
            ),
        )

        controller.start(null)
        advanceUntilIdle()

        assertEquals(SessionPhase.PAUSED, controller.state.value.phase)
        assertEquals("I didn't catch that. Tap the mic when you're ready.", tts.spoken.last())

        controller.shutdown()
    }

    @Test
    fun `spaced partial results keep listening alive; sustained silence still ends it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tts = FakeTextToSpeech()
        // Three partials 80ms apart, each inside the 100ms deadline, then
        // silence: listening should survive well past a single 100ms window
        // (proving the debounce doesn't cut off genuine ongoing speech) but
        // still give up once real silence exceeds the deadline.
        val stt = object : SpeechToTextEngine {
            override suspend fun isAvailable(): Boolean = true
            override fun listen(config: SttConfig): Flow<SttEvent> = flow {
                emit(SttEvent.PartialResult("uh"))
                delay(80L)
                emit(SttEvent.PartialResult("uh, um"))
                delay(80L)
                emit(SttEvent.PartialResult("uh, um, hmm"))
                awaitCancellation()
            }
        }
        val controller = newController(
            dispatcher, FakeChatClient(), tts = tts, stt = stt,
            settings = SessionSettings(
                model = "m", systemPrompt = null, autoListen = true,
                listeningTimeoutMs = 100L, maxNoMatchRetries = 0,
            ),
        )

        controller.start(null)
        advanceTimeBy(190L)
        runCurrent()
        assertEquals(SessionPhase.LISTENING, controller.state.value.phase)

        advanceUntilIdle()
        assertEquals(SessionPhase.PAUSED, controller.state.value.phase)
        assertEquals("I didn't catch that. Tap the mic when you're ready.", tts.spoken.last())

        controller.shutdown()
    }

    @Test
    fun `markdown is stripped for TTS but preserved in history`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tts = FakeTextToSpeech()
        val store = FakeConversationStore()
        val chat = FakeChatClient(tokens = listOf("**Hello**", "! ", "See ", "[docs](https://x.com)", "."))
        val controller = newController(
            dispatcher, chat, tts = tts, store = store,
            settings = SessionSettings(model = "m", systemPrompt = null, autoListen = false),
        )

        controller.start(null)
        advanceUntilIdle()
        controller.submitText("hi")
        advanceUntilIdle()

        // TTS heard the prose, not the markdown decorators.
        assertEquals(listOf("Hello!", "See docs."), tts.spoken)

        // The stored reply keeps the raw markdown for the UI/history.
        val assistant = store.messages.first { it.role == Role.ASSISTANT }
        assertEquals("**Hello**! See [docs](https://x.com).", assistant.text)

        controller.shutdown()
    }
}
